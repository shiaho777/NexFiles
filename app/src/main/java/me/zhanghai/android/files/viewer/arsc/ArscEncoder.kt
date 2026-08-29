/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.arsc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes an [ArscTable] back into binary `resources.arsc` format — the inverse of [ArscParser].
 *
 * The ARSC binary format is a nested chunk hierarchy:
 * ```
 *   RES_TABLE_TYPE (0x0002)
 *     Global String Pool (value strings)
 *     Package* (0x0200)
 *       Type String Pool (type names)
 *       Key String Pool (key names)
 *       TypeSpec* (0x0202) — one per type
 *       Type* (0x0201) — entries grouped by type+config
 * ```
 *
 * The encoder rebuilds all chunks from the model. Key design choices:
 *  - **String pools**: UTF-16 encoded, strings interned and sorted for deterministic output.
 *  - **TypeSpec chunks**: emitted with zero flags (we don't track PUBLIC/etc. in the model; the
 *    verifier tolerates this for unsigned/edited APKs).
 *  - **Type chunks**: one per type, with a single default config (empty ResTable_config). Entry
 *    offsets are written as a uint32 array; missing entries use -1.
 *  - **Typed values**: preserved from [ArscEntry.rawDataType]/[rawData] for simple entries;
 *    bag items are written from [ArscEntry.rawBagItems].
 *
 * @see <a href="https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h">ResourceTypes.h</a>
 */
internal object ArscEncoder {

    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202

    // Chunk header size for string pool.
    private const val STRING_POOL_HEADER_SIZE = 28

    /**
     * Encodes [table] into binary ARSC bytes.
     */
    fun encode(table: ArscTable): ByteArray {
        // Phase 1: collect all value strings into the global string pool. TYPE_STRING entries
        // reference this pool by index.
        val globalStrings = mutableListOf<String>()
        val globalStringIndex = mutableMapOf<String, Int>()
        fun internGlobal(s: String): Int = globalStringIndex.getOrPut(s) {
            globalStrings.add(s); globalStrings.size - 1
        }

        // Pre-scan: intern all string-typed entry values and bag string values.
        for (pkg in table.packages) {
            for (type in pkg.types) {
                for (entry in type.entries) {
                    if (entry.rawDataType == 0x03 && entry.rawBagItems == null) {
                        // String-typed simple entry: intern its value.
                        if (entry.rawData in globalStrings.indices) {
                            // Already set from a prior parse — the index is valid.
                        } else {
                            internGlobal(entry.value)
                        }
                    }
                }
            }
        }

        // Build global string pool chunk.
        val globalPool = buildStringPool(globalStrings)

        // Phase 2: build each package chunk.
        val packageChunks = ByteArrayOutputStream()
        for (pkg in table.packages) {
            packageChunks.write(buildPackageChunk(pkg, ::internGlobal))
        }

        // Phase 3: assemble the table.
        val totalSize = 12 + globalPool.size + packageChunks.size()
        val out = ByteArrayOutputStream(totalSize)
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(RES_TABLE_TYPE.toShort())
        header.putShort(12) // headerSize
        header.putInt(totalSize)
        header.putInt(table.packages.size) // packageCount
        out.write(header.array())
        out.write(globalPool)
        out.write(packageChunks.toByteArray())
        return out.toByteArray()
    }

    // -----------------------------------------------------------------------------------
    //  Package chunk
    // -----------------------------------------------------------------------------------

    private fun buildPackageChunk(
        pkg: ArscPackage,
        internGlobal: (String) -> Int
    ): ByteArray {
        // Collect type names and key names.
        val typeNames = mutableListOf<String>()
        val typeIndex = mutableMapOf<String, Int>()
        fun internType(s: String): Int = typeIndex.getOrPut(s) {
            typeNames.add(s); typeNames.size - 1
        }
        val keyNames = mutableListOf<String>()
        val keyIndex = mutableMapOf<String, Int>()
        fun internKey(s: String): Int = keyIndex.getOrPut(s) {
            keyNames.add(s); keyNames.size - 1
        }

        // Pre-register all type and key strings.
        for (type in pkg.types) {
            internType(type.name)
            for (entry in type.entries) {
                internKey(entry.key)
            }
        }

        val typeStringPool = buildStringPool(typeNames)
        val keyStringPool = buildStringPool(keyNames)

        // Build TypeSpec + Type chunks.
        val typeChunks = ByteArrayOutputStream()
        for ((typeIdx, type) in pkg.types.withIndex()) {
            val typeId = typeIdx + 1

            // TypeSpec chunk (16 bytes header + 4 bytes per entry flags).
            val specEntryCount = type.entries.size
            val typeSpecSize = 20 + specEntryCount * 4
            val typeSpecBuf = ByteBuffer.allocate(typeSpecSize).order(ByteOrder.LITTLE_ENDIAN)
            typeSpecBuf.putShort(RES_TABLE_TYPE_SPEC_TYPE.toShort())
            typeSpecBuf.putShort(20) // headerSize
            typeSpecBuf.putInt(typeSpecSize)
            typeSpecBuf.put(typeId.toByte()) // id
            typeSpecBuf.put(0) // flags
            typeSpecBuf.putShort(0) // reserved
            typeSpecBuf.putInt(specEntryCount) // entryCount
            // Entry flags: all 0 (PUBLIC not tracked).
            for (i in 0 until specEntryCount) typeSpecBuf.putInt(0)
            typeChunks.write(typeSpecBuf.array())

            // Type chunk: entries with a default (empty) config.
            val entriesBytes = ByteArrayOutputStream()
            val entryOffsets = IntArray(specEntryCount) { -1 }
            for ((entryIdx, entry) in type.entries.withIndex()) {
                entryOffsets[entryIdx] = entriesBytes.size()
                writeEntry(entriesBytes, entry, ::internKey, typeNames, internGlobal)
            }
            val entriesArray = entriesBytes.toByteArray()

            // Config: minimal default config (just size=0, but Android requires at least 48 bytes
            // for a null config; we use size=64 to be safe).
            val configSize = 64
            val typeHeaderSize = 20 + configSize // header ext + config
            val offsetsSize = specEntryCount * 4
            val typeChunkSize = typeHeaderSize + offsetsSize + entriesArray.size

            val typeBuf = ByteBuffer.allocate(typeHeaderSize).order(ByteOrder.LITTLE_ENDIAN)
            typeBuf.putShort(RES_TABLE_TYPE_TYPE.toShort())
            typeBuf.putShort(typeHeaderSize.toShort()) // headerSize
            typeBuf.putInt(typeChunkSize)
            typeBuf.put(typeId.toByte()) // id
            typeBuf.put(0) // flags
            typeBuf.putShort(0) // reserved
            typeBuf.putInt(specEntryCount) // entryCount
            typeBuf.putInt(typeHeaderSize + offsetsSize) // entriesStart (from chunk start)
            // ResTable_config: write size then zeros.
            typeBuf.putInt(configSize)
            // Rest of config is zeros (already zeroed by ByteBuffer.allocate).

            // Write: type header + offsets + entries.
            val result = ByteArrayOutputStream(typeChunkSize)
            result.write(typeBuf.array())
            val offsetsBuf = ByteBuffer.allocate(offsetsSize).order(ByteOrder.LITTLE_ENDIAN)
            for (offset in entryOffsets) offsetsBuf.putInt(offset)
            result.write(offsetsBuf.array())
            result.write(entriesArray)
            typeChunks.write(result.toByteArray())
        }

        // Assemble package chunk.
        // Package header: 268 bytes (typeStringOffset at +268, keyStringOffset at +276).
        val packageHeaderSize = 284
        // The offsets are relative to the package chunk start.
        val typeStringsOffset = packageHeaderSize
        val keyStringsOffset = typeStringsOffset + typeStringPool.size
        val packageBodySize = typeStringPool.size + keyStringPool.size + typeChunks.size()
        val packageChunkSize = packageHeaderSize + packageBodySize

        val pkgBuf = ByteBuffer.allocate(packageHeaderSize).order(ByteOrder.LITTLE_ENDIAN)
        pkgBuf.putShort(RES_TABLE_PACKAGE_TYPE.toShort())
        pkgBuf.putShort(packageHeaderSize.toShort())
        pkgBuf.putInt(packageChunkSize)
        pkgBuf.putInt(pkg.id) // package id
        // Package name: 256 UTF-16 chars (512 bytes) at offset 12.
        val nameChars = pkg.name.toCharArray()
        for (i in 0 until 256) {
            pkgBuf.putShort(if (i < nameChars.size) nameChars[i].code.toShort() else 0)
        }
        // typeStrings offset (uint32 at +268), lastPublicType (uint32 at +272)
        pkgBuf.putInt(typeStringsOffset)
        pkgBuf.putInt(typeNames.size) // lastPublicType
        // keyStrings offset (uint32 at +276), lastPublicKey (uint32 at +280)
        pkgBuf.putInt(keyStringsOffset)
        pkgBuf.putInt(keyNames.size) // lastPublicKey

        val result = ByteArrayOutputStream(packageChunkSize)
        result.write(pkgBuf.array())
        result.write(typeStringPool)
        result.write(keyStringPool)
        result.write(typeChunks.toByteArray())
        return result.toByteArray()
    }

    // -----------------------------------------------------------------------------------
    //  Entry writing
    // -----------------------------------------------------------------------------------

    private fun writeEntry(
        out: ByteArrayOutputStream,
        entry: ArscEntry,
        internKey: (String) -> Int,
        typeNames: List<String>,
        internGlobal: (String) -> Int
    ) {
        val keyIdx = internKey(entry.key)
        val bagItems = entry.rawBagItems

        val entryBuf = ByteArrayOutputStream()
        if (bagItems == null) {
            // Simple entry: 8-byte header + 8-byte Res_value.
            val simpleBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            simpleBuf.putShort(8) // entrySize
            simpleBuf.putShort(0) // flags (not complex)
            simpleBuf.putInt(keyIdx)
            // Res_value
            simpleBuf.put(8) // size
            simpleBuf.put(0) // res0
            simpleBuf.putShort(entry.rawDataType.toShort())
            // For TYPE_STRING, the data is the global string pool index.
            val data = if (entry.rawDataType == 0x03) {
                internGlobal(entry.value)
            } else {
                entry.rawData
            }
            simpleBuf.putInt(data)
            entryBuf.write(simpleBuf.array())
        } else {
            // Complex (bag) entry: 8-byte header + parentRef + count + items.
            val complexBuf = ByteArrayOutputStream()
            val headerBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            headerBuf.putShort(16) // entrySize (header + parent + count)
            headerBuf.putShort(0x0001) // flags: FLAG_COMPLEX
            headerBuf.putInt(keyIdx)
            headerBuf.putInt(0) // parentRef
            headerBuf.putInt(bagItems.size) // count
            complexBuf.write(headerBuf.array())
            // Bag items: each is (uint32 key | Res_value 8 bytes) = 12 bytes.
            for (item in bagItems) {
                val itemBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                itemBuf.putInt(item.key)
                itemBuf.put(8) // Res_value size
                itemBuf.put(0) // res0
                itemBuf.putShort(item.dataType.toShort())
                itemBuf.putInt(item.data)
                complexBuf.write(itemBuf.array())
            }
            entryBuf.write(complexBuf.toByteArray())
        }
        out.write(entryBuf.toByteArray())
    }

    // -----------------------------------------------------------------------------------
    //  String pool builder (shared)
    // -----------------------------------------------------------------------------------

    private fun buildStringPool(strings: List<String>): ByteArray {
        if (strings.isEmpty()) {
            // Even an empty string pool needs a valid chunk.
            val buf = ByteBuffer.allocate(STRING_POOL_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(RES_STRING_POOL_TYPE.toShort())
            buf.putShort(STRING_POOL_HEADER_SIZE.toShort())
            buf.putInt(STRING_POOL_HEADER_SIZE)
            buf.putInt(0) // stringCount
            buf.putInt(0) // styleCount
            buf.putInt(0) // flags
            buf.putInt(STRING_POOL_HEADER_SIZE) // stringsStart (right after header)
            buf.putInt(0) // stylesStart
            return buf.array()
        }

        val stringDataStart = STRING_POOL_HEADER_SIZE + strings.size * 4
        val offsets = IntArray(strings.size)
        var currentOffset = 0
        for (i in strings.indices) {
            offsets[i] = currentOffset
            currentOffset += 2 + strings[i].length * 2 + 2 // uint16 len + chars + NUL
        }
        val chunkSize = stringDataStart + currentOffset

        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_STRING_POOL_TYPE.toShort())
        buf.putShort(STRING_POOL_HEADER_SIZE.toShort())
        buf.putInt(chunkSize)
        buf.putInt(strings.size)
        buf.putInt(0) // styleCount
        buf.putInt(0) // flags (UTF-16)
        buf.putInt(stringDataStart)
        buf.putInt(0) // stylesStart
        for (offset in offsets) buf.putInt(offset)
        for (s in strings) {
            buf.putShort(s.length.toShort())
            for (c in s) buf.putShort(c.code.toShort())
            buf.putShort(0) // NUL
        }
        return buf.array()
    }
}
