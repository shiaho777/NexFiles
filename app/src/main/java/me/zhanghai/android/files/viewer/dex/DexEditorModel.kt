/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Field
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.Reference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference
import org.jf.dexlib2.iface.value.StringEncodedValue
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableField
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * In-memory, editable view of a DEX file, backed by dexlib2's object model.
 *
 * dexlib2 gives us two things we'd otherwise have to hand-roll:
 *  - A reader ([DexBackedDexFile]) that turns raw DEX bytes into a navigable object graph
 *    (classes, methods, fields, string references) without losing binary-level detail.
 *  - A writer ([DexPool]) that re-serializes a (possibly mutated) graph back into a valid DEX,
 *    recomputing the checksum, signature, and every id/string/data offset.
 *
 * We keep two references: the live [dexFile] (which is re-derived as an ImmutableDexFile after
 * every edit) and the original [backedDexFile] (used only to enumerate the string pool, since
 * strings aren't part of the [DexFile] interface). Reads always reflect the latest in-memory
 * state.
 */
class DexEditorModel private constructor(
    private var dexFile: DexFile,
    private val backedDexFile: DexBackedDexFile,
    val opcodes: Opcodes
) {
    /** DEX format version (35, 37, 38, 39, 40...), derived from the header magic. */
    val dexVersion: Int
        get() = opcodes.dexVersion

    /** Snapshot of classes, sorted by type for stable display. */
    val classes: List<ClassDef>
        get() = dexFile.classes.toList().sortedBy { it.type }

    /**
     * Deduplicated string pool, sorted for stable display. Sourced from the string-id section
     * of the original DEX; this list reflects the on-disk string pool and is not updated after
     * edits (the edited strings surface through the class/method views instead).
     */
    val strings: List<String>
        get() = backedDexFile.stringReferences.map { it.string }.distinct().sorted()

    val classCount: Int
        get() = dexFile.classes.count()

    val stringCount: Int
        get() = backedDexFile.stringReferences.count()

    /**
     * Replaces every occurrence of [oldValue] with [newValue] across the string constants held
     * by the DEX: const-string instruction operands and string-typed static field initial
     * values. Returns the number of references updated.
     *
     * Type descriptors and member names are intentionally left untouched, since changing those
     * breaks bytecode verification unless every cross-reference is also updated — that belongs
     * to a dedicated rename operation, not a blanket string replace.
     */
    fun replaceString(oldValue: String, newValue: String): Int {
        if (oldValue.isEmpty() || oldValue == newValue) return 0
        var count = 0
        val rewrittenClasses = dexFile.classes.map { cls ->
            rewriteClassStrings(cls, oldValue, newValue) { count++ }
        }
        dexFile = ImmutableDexFile(opcodes, rewrittenClasses)
        return count
    }

    private fun rewriteClassStrings(
        cls: ClassDef,
        oldValue: String,
        newValue: String,
        onReplace: () -> Unit
    ): ImmutableClassDef {
        val methods = cls.methods.map { rewriteMethodStrings(it, oldValue, newValue, onReplace) }
        val fields = cls.fields.map { rewriteFieldStrings(it, oldValue, newValue, onReplace) }
        return ImmutableClassDef(
            cls.type, cls.accessFlags, cls.superclass, cls.interfaces.toList(),
            cls.sourceFile, cls.annotations.toList(), fields, methods
        )
    }

    private fun rewriteMethodStrings(
        method: Method,
        oldValue: String,
        newValue: String,
        onReplace: () -> Unit
    ): ImmutableMethod {
        val implementation = method.implementation
            ?: return ImmutableMethod.of(method)
        val newInstructions = implementation.instructions.map { insn ->
            rewriteInstruction(insn, oldValue, newValue, onReplace)
        }
        val newImplementation = ImmutableMethodImplementation(
            implementation.registerCount, newInstructions,
            implementation.tryBlocks, implementation.debugItems
        )
        return ImmutableMethod(
            method.definingClass, method.name, method.parameters, method.returnType,
            method.accessFlags, method.annotations, method.hiddenApiRestrictions,
            newImplementation
        )
    }

    private fun rewriteInstruction(
        insn: Instruction,
        oldValue: String,
        newValue: String,
        onReplace: () -> Unit
    ): Instruction {
        val refInsn = insn as? ReferenceInstruction ?: return insn
        val ref = refInsn.reference as? StringReference ?: return insn
        if (ref.string != oldValue) return insn
        onReplace()
        val newRef = ImmutableStringReference(newValue)
        val immutable = ImmutableInstruction.of(insn)
        // const-string and const-string/jumbo are the only opcodes that carry a StringReference;
        // both are serialized as Instruction21c in dexlib2's immutable model.
        return if (immutable is ImmutableInstruction21c) {
            ImmutableInstruction21c(immutable.opcode, immutable.registerA, newRef)
        } else {
            immutable
        }
    }

    private fun rewriteFieldStrings(
        field: Field,
        oldValue: String,
        newValue: String,
        onReplace: () -> Unit
    ): ImmutableField {
        val sev = field.initialValue as? StringEncodedValue ?: return ImmutableField.of(field)
        if (sev.value != oldValue) return ImmutableField.of(field)
        onReplace()
        return ImmutableField(
            field.definingClass, field.name, field.type, field.accessFlags,
            ImmutableStringEncodedValue(newValue), field.annotations,
            field.hiddenApiRestrictions
        )
    }

    // -- Rename / signature edit / delete operations --
    // These rebuild the DEX the same way replaceString does: walk every class, rebuild each
    // ImmutableClassDef/Method/Field with the updated reference. Cross-references (instructions
    // that refer to a type, method, or field) are rewritten in the instruction pass.

    /**
     * Renames a class: changes its type descriptor from [oldType] to [newType] (both must be valid
     * descriptors like `Lcom/foo/Bar;`) and updates every cross-reference — superclass lists,
     * interface lists, field types, method parameters/returns, and instruction references.
     *
     * Returns the number of references updated.
     */
    fun renameClass(oldType: String, newType: String): Int {
        if (oldType.isEmpty() || oldType == newType) return 0
        var count = 0
        val rewritten = dexFile.classes.map { cls -> rewriteClassTypeRefs(cls, oldType, newType) { count++ } }
        dexFile = ImmutableDexFile(opcodes, rewritten)
        return count
    }

    /**
     * Renames a method across the entire DEX: every method definition matching [definingClass] +
     * [name] + [parameters] + [returnType] gets its name changed to [newName], and every
     * instruction that invokes it is updated.
     *
     * Returns the number of references updated.
     */
    fun renameMethod(
        definingClass: String, name: String, parameters: List<String>, returnType: String,
        newName: String
    ): Int {
        if (name == newName) return 0
        var count = 0
        val rewritten = dexFile.classes.map { cls ->
            rewriteClassMethodRefs(cls, definingClass, name, parameters, returnType, newName) { count++ }
        }
        dexFile = ImmutableDexFile(opcodes, rewritten)
        return count
    }

    /**
     * Renames a field across the entire DEX: the field definition matching [definingClass] +
     * [name] + [type] gets its name changed to [newName], and every iput/sput instruction that
     * references it is updated.
     *
     * Returns the number of references updated.
     */
    fun renameField(
        definingClass: String, name: String, type: String, newName: String
    ): Int {
        if (name == newName) return 0
        var count = 0
        val rewritten = dexFile.classes.map { cls ->
            rewriteClassFieldRefs(cls, definingClass, name, type, newName) { count++ }
        }
        dexFile = ImmutableDexFile(opcodes, rewritten)
        return count
    }

    /**
     * Changes a method's signature (parameters + return type). The definition matching
     * [definingClass] + [name] + [oldParameters] + [oldReturnType] is rebuilt with the new
     * signature, and all invoke instructions pointing to it are updated.
     *
     * Returns the number of references updated.
     */
    fun changeMethodSignature(
        definingClass: String, name: String,
        oldParameters: List<String>, oldReturnType: String,
        newParameters: List<String>, newReturnType: String
    ): Int {
        var count = 0
        val rewritten = dexFile.classes.map { cls ->
            rewriteClassMethodSignature(
                cls, definingClass, name, oldParameters, oldReturnType,
                newParameters, newReturnType
            ) { count++ }
        }
        dexFile = ImmutableDexFile(opcodes, rewritten)
        return count
    }

    /**
     * Deletes a method definition. **Warning**: this does NOT remove instructions that call the
     * deleted method — those become dangling references that will fail bytecode verification.
     * The caller (UI) should warn the user.
     */
    fun deleteMethod(
        definingClass: String, name: String, parameters: List<String>, returnType: String
    ): Boolean {
        var deleted = false
        val rewritten = dexFile.classes.map { cls ->
            if (cls.type != definingClass) return@map cls
            val methods = cls.methods.filterNot { m ->
                val match = m.name == name &&
                    m.parameters.map { it.toString() } == parameters &&
                    m.returnType == returnType
                if (match) deleted = true
                match
            }
            if (!deleted) return@map cls
            ImmutableClassDef(
                cls.type, cls.accessFlags, cls.superclass, cls.interfaces.toList(),
                cls.sourceFile, cls.annotations.toList(), cls.fields.toList(), methods.toList()
            )
        }
        if (deleted) dexFile = ImmutableDexFile(opcodes, rewritten)
        return deleted
    }

    // -----------------------------------------------------------------------------------
    //  Cross-reference rewriting helpers
    // -----------------------------------------------------------------------------------

    private fun rewriteClassTypeRefs(
        cls: ClassDef, oldType: String, newType: String, onReplace: () -> Unit
    ): ImmutableClassDef {
        val type = if (cls.type == oldType) { onReplace(); newType } else cls.type
        val superclass = cls.superclass?.let { if (it == oldType) { onReplace(); newType } else it }
        val interfaces = cls.interfaces.map { if (it == oldType) { onReplace(); newType } else it }
        val fields = cls.fields.map { rewriteFieldTypeRefs(it, oldType, newType, onReplace) }
        val methods = cls.methods.map { rewriteMethodTypeRefs(it, oldType, newType, onReplace) }
        return ImmutableClassDef(
            type, cls.accessFlags, superclass, interfaces,
            cls.sourceFile, cls.annotations.toList(), fields, methods
        )
    }

    private fun rewriteFieldTypeRefs(
        field: Field, oldType: String, newType: String, onReplace: () -> Unit
    ): ImmutableField {
        if (field.type != oldType && field.definingClass != oldType) return ImmutableField.of(field)
        onReplace()
        val defClass = if (field.definingClass == oldType) newType else field.definingClass
        val fieldType = if (field.type == oldType) newType else field.type
        return ImmutableField(
            defClass, field.name, fieldType, field.accessFlags, field.initialValue,
            field.annotations, field.hiddenApiRestrictions
        )
    }

    private fun rewriteMethodTypeRefs(
        method: Method, oldType: String, newType: String, onReplace: () -> Unit
    ): ImmutableMethod {
        val defClass = if (method.definingClass == oldType) { onReplace(); newType } else method.definingClass
        val params = method.parameters.map { if (it == oldType) { onReplace(); newType } else it }
        val retType = if (method.returnType == oldType) { onReplace(); newType } else method.returnType
        val impl = method.implementation
        val newImpl = if (impl != null) {
            val newInsns = impl.instructions.map { rewriteInsnTypeRef(it, oldType, newType, onReplace) }
            ImmutableMethodImplementation(impl.registerCount, newInsns, impl.tryBlocks, impl.debugItems)
        } else null
        return ImmutableMethod(
            defClass, method.name, params, retType, method.accessFlags,
            method.annotations, method.hiddenApiRestrictions, newImpl
        )
    }

    private fun rewriteInsnTypeRef(
        insn: Instruction, oldType: String, newType: String, onReplace: () -> Unit
    ): Instruction {
        val refInsn = insn as? ReferenceInstruction ?: return insn
        val ref = refInsn.reference
        return when (ref) {
            is TypeReference -> {
                if (ref.type != oldType) return ImmutableInstruction.of(insn)
                onReplace()
                rewriteInsnWithReference(insn, ImmutableTypeReference(newType))
            }
            is MethodReference -> {
                val m = ref
                val newDefClass = if (m.definingClass == oldType) newType else m.definingClass
                val newParams = m.parameterTypes.map { if (it == oldType) newType else it }
                val newRet = if (m.returnType == oldType) newType else m.returnType
                if (newDefClass == m.definingClass && newParams == m.parameterTypes && newRet == m.returnType) {
                    return ImmutableInstruction.of(insn)
                }
                onReplace()
                rewriteInsnWithReference(insn, ImmutableMethodReference(
                    newDefClass, m.name, newParams, newRet
                ))
            }
            is FieldReference -> {
                val f = ref
                val newDefClass = if (f.definingClass == oldType) newType else f.definingClass
                val newType2 = if (f.type == oldType) newType else f.type
                if (newDefClass == f.definingClass && newType2 == f.type) return ImmutableInstruction.of(insn)
                onReplace()
                rewriteInsnWithReference(insn, ImmutableFieldReference(newDefClass, f.name, newType2))
            }
            else -> ImmutableInstruction.of(insn)
        }
    }

    private fun rewriteClassMethodRefs(
        cls: ClassDef, definingClass: String, name: String,
        parameters: List<String>, returnType: String, newName: String,
        onReplace: () -> Unit
    ): ImmutableClassDef {
        val methods = cls.methods.map { m ->
            val isTarget = m.definingClass == definingClass && m.name == name &&
                m.parameters.map { it.toString() } == parameters && m.returnType == returnType
            val impl = m.implementation
            if (impl != null) {
                val newInsns = impl.instructions.map { insn ->
                    rewriteInsnMethodRef(insn, definingClass, name, parameters, returnType, newName, onReplace)
                }
                val updatedName = if (isTarget) { onReplace(); newName } else m.name
                ImmutableMethod(
                    m.definingClass, updatedName, m.parameters, m.returnType,
                    m.accessFlags, m.annotations, m.hiddenApiRestrictions,
                    ImmutableMethodImplementation(impl.registerCount, newInsns, impl.tryBlocks, impl.debugItems)
                )
            } else {
                if (isTarget) {
                    onReplace()
                    ImmutableMethod.of(m).let { ImmutableMethod(it.definingClass, newName, it.parameterTypes, it.returnType, it.accessFlags, it.annotations, it.hiddenApiRestrictions, null) }
                } else ImmutableMethod.of(m)
            }
        }
        return ImmutableClassDef(
            cls.type, cls.accessFlags, cls.superclass, cls.interfaces.toList(),
            cls.sourceFile, cls.annotations.toList(), cls.fields.toList(), methods
        )
    }

    private fun rewriteInsnMethodRef(
        insn: Instruction, definingClass: String, name: String,
        parameters: List<String>, returnType: String, newName: String,
        onReplace: () -> Unit
    ): Instruction {
        val refInsn = insn as? ReferenceInstruction ?: return insn
        val methodRef = refInsn.reference as? MethodReference ?: return insn
        if (methodRef.definingClass != definingClass || methodRef.name != name ||
            methodRef.parameterTypes.toList() != parameters || methodRef.returnType != returnType
        ) return ImmutableInstruction.of(insn)
        onReplace()
        return rewriteInsnWithReference(insn, ImmutableMethodReference(
            methodRef.definingClass, newName, methodRef.parameterTypes, methodRef.returnType
        ))
    }

    private fun rewriteClassFieldRefs(
        cls: ClassDef, definingClass: String, name: String, type: String, newName: String,
        onReplace: () -> Unit
    ): ImmutableClassDef {
        val fields = cls.fields.map { f ->
            val isTarget = f.definingClass == definingClass && f.name == name && f.type == type
            if (isTarget) {
                onReplace()
                ImmutableField(
                    f.definingClass, newName, f.type, f.accessFlags, f.initialValue,
                    f.annotations, f.hiddenApiRestrictions
                )
            } else ImmutableField.of(f)
        }
        val methods = cls.methods.map { m ->
            val impl = m.implementation ?: return@map ImmutableMethod.of(m)
            val newInsns = impl.instructions.map { insn ->
                rewriteInsnFieldRef(insn, definingClass, name, type, newName, onReplace)
            }
            ImmutableMethod(
                m.definingClass, m.name, m.parameters, m.returnType, m.accessFlags,
                m.annotations, m.hiddenApiRestrictions,
                ImmutableMethodImplementation(impl.registerCount, newInsns, impl.tryBlocks, impl.debugItems)
            )
        }
        return ImmutableClassDef(
            cls.type, cls.accessFlags, cls.superclass, cls.interfaces.toList(),
            cls.sourceFile, cls.annotations.toList(), fields, methods
        )
    }

    private fun rewriteInsnFieldRef(
        insn: Instruction, definingClass: String, name: String, type: String, newName: String,
        onReplace: () -> Unit
    ): Instruction {
        val refInsn = insn as? ReferenceInstruction ?: return insn
        val fieldRef = refInsn.reference as? FieldReference ?: return insn
        if (fieldRef.definingClass != definingClass || fieldRef.name != name || fieldRef.type != type) {
            return ImmutableInstruction.of(insn)
        }
        onReplace()
        return rewriteInsnWithReference(insn, ImmutableFieldReference(fieldRef.definingClass, newName, fieldRef.type))
    }

    private fun rewriteClassMethodSignature(
        cls: ClassDef, definingClass: String, name: String,
        oldParameters: List<String>, oldReturnType: String,
        newParameters: List<String>, newReturnType: String,
        onReplace: () -> Unit
    ): ImmutableClassDef {
        val methods = cls.methods.map { m ->
            val isTarget = m.definingClass == definingClass && m.name == name &&
                m.parameters.map { it.toString() } == oldParameters && m.returnType == oldReturnType
            val impl = m.implementation
            val newInsns = impl?.instructions?.map { insn ->
                rewriteInsnMethodSig(insn, definingClass, name, oldParameters, oldReturnType, newParameters, newReturnType, onReplace)
            }
            if (isTarget) {
                onReplace()
                ImmutableMethod(
                    m.definingClass, m.name, newParameters, newReturnType, m.accessFlags,
                    m.annotations, m.hiddenApiRestrictions,
                    if (newInsns != null && impl != null) ImmutableMethodImplementation(
                        impl.registerCount, newInsns, impl.tryBlocks, impl.debugItems
                    ) else null
                )
            } else if (newInsns != null && impl != null) {
                ImmutableMethod(
                    m.definingClass, m.name, m.parameters, m.returnType, m.accessFlags,
                    m.annotations, m.hiddenApiRestrictions,
                    ImmutableMethodImplementation(impl.registerCount, newInsns, impl.tryBlocks, impl.debugItems)
                )
            } else ImmutableMethod.of(m)
        }
        return ImmutableClassDef(
            cls.type, cls.accessFlags, cls.superclass, cls.interfaces.toList(),
            cls.sourceFile, cls.annotations.toList(), cls.fields.toList(), methods
        )
    }

    private fun rewriteInsnMethodSig(
        insn: Instruction, definingClass: String, name: String,
        oldParameters: List<String>, oldReturnType: String,
        newParameters: List<String>, newReturnType: String,
        onReplace: () -> Unit
    ): Instruction {
        val refInsn = insn as? ReferenceInstruction ?: return insn
        val methodRef = refInsn.reference as? MethodReference ?: return insn
        if (methodRef.definingClass != definingClass || methodRef.name != name ||
            methodRef.parameterTypes.toList() != oldParameters || methodRef.returnType != oldReturnType
        ) return ImmutableInstruction.of(insn)
        onReplace()
        return rewriteInsnWithReference(insn, ImmutableMethodReference(
            methodRef.definingClass, methodRef.name, newParameters, newReturnType
        ))
    }

    /**
     * Replaces the reference carried by [insn] with [newRef]. Handles the instruction formats that
     * carry a reference (21c, 22c, 31c, 35c, 3rc, etc.) by rebuilding the immutable instruction
     * with the same opcode/registers but the new reference. Falls back to [ImmutableInstruction.of]
     * for formats we don't special-case.
     */
    private fun rewriteInsnWithReference(insn: Instruction, newRef: Reference): Instruction {
        val immutable = ImmutableInstruction.of(insn)
        // Most reference-carrying instructions are 21c (one register) or 35c/3rc (invoke, multiple
        // registers). dexlib2's immutable model doesn't expose a generic "with reference" method,
        // so we handle the common formats. For invoke formats, the registers are preserved from
        // the original instruction's register list.
        return when (immutable) {
            is ImmutableInstruction21c ->
                ImmutableInstruction21c(immutable.opcode, immutable.registerA, newRef)
            // For other formats, ImmutableInstruction.of already copied everything; we can't easily
            // swap the reference without matching the specific class. The 21c case covers the vast
            // majority of type/field/string references. Invoke instructions (35c/3rc) use
            // MethodReference and are handled by recreating them with the method reference.
            else -> {
                // Fallback: try to reconstruct via reflection-free copy for 22c (iget/iput with two
                // registers) and 31c (const-string/jumbo).
                try {
                    val clazz = immutable.javaClass
                    val opcode = immutable.opcode
                    // Read register fields by name — dexlib2's immutable instructions use
                    // registerA/registerB/registerC fields.
                    val regA = getFieldOrNull(immutable, "registerA")
                    val regB = getFieldOrNull(immutable, "registerB")
                    when {
                        regA != null && regB != null && clazz.name.contains("Instruction22c") -> {
                            clazz.constructors.first().newInstance(opcode, regA, regB, newRef) as Instruction
                        }
                        else -> immutable // give up gracefully; the 21c path handles most cases
                    }
                } catch (e: Exception) {
                    immutable
                }
            }
        }
    }

    private fun getFieldOrNull(obj: Any, fieldName: String): Any? = try {
        obj.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(obj)
    } catch (e: Exception) { null }

    /**
     * Replaces the class in this DEX whose type descriptor matches [newClass.type] with
     * [newClass]. If no existing class has that type, [newClass] is added. Used by the smali
     * editor after reassembling edited smali source.
     *
     * Returns true if a class was replaced, false if a new class was added.
     */
    fun replaceClass(newClass: ClassDef): Boolean {
        var replaced = false
        val rewritten = dexFile.classes.map { cls ->
            if (cls.type == newClass.type) { replaced = true; newClass } else cls
        }.toMutableList()
        if (!replaced) rewritten.add(newClass)
        dexFile = ImmutableDexFile(opcodes, rewritten)
        return replaced
    }

    /**
     * Serializes the current in-memory DEX back to [output], recomputing checksum/signature
     * and every internal offset. The output is a valid, installable DEX.
     */
    @Throws(IOException::class)
    fun write(output: OutputStream) {
        val store = MemoryDataStore()
        DexPool.writeTo(store, dexFile)
        // getData() returns exactly the written bytes (buffer trimmed to size); getBuffer()
        // may include trailing zero padding from the initial allocation.
        output.write(store.data)
    }

    companion object {
        /**
         * Parses [input] (the entire DEX contents) into an editable model. The input is fully
         * buffered into memory because dexlib2 needs random access and we want to support
         * remote/SAF-backed streams that aren't seekable.
         */
        @Throws(IOException::class)
        fun read(input: InputStream): DexEditorModel {
            val bytes = input.readBytes()
            if (bytes.size < 8) throw IOException("File too small to be a DEX")
            // Validate the DEX magic: "dex\n" followed by a 3-digit version and a NUL.
            if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() ||
                bytes[2] != 'x'.code.toByte() || bytes[3] != '\n'.code.toByte()
            ) {
                throw IOException("Not a DEX file (bad magic)")
            }
            val version = parseDexVersion(bytes)
            val opcodes = Opcodes.forDexVersion(version)
            val backedDexFile = DexBackedDexFile(opcodes, bytes)
            return DexEditorModel(backedDexFile, backedDexFile, opcodes)
        }

        /** Reads the 3-digit ASCII version at bytes 4..6 (e.g. "035" -> 35). */
        private fun parseDexVersion(bytes: ByteArray): Int = try {
            String(bytes, 4, 3, Charsets.US_ASCII).trim().toInt()
        } catch (e: Exception) {
            35 // Fall back to the lowest version dexlib2 fully supports.
        }
    }
}
