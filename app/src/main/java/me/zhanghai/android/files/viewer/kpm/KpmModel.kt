/*
 * Copyright (c) NexFiles contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.kpm

import me.zhanghai.android.files.provider.common.newInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Read-only structural analysis of kernel modules: Linux `.ko` relocatable objects and
 * KernelPatch `.kpm` modules. Both are ELF files; everything interesting lives in the section
 * table (`.modinfo`/`.kpm_info`), the symbol table (which kernel symbols the module references
 * — the primary signal of what it hooks), and the string data.
 *
 * The parser is intentionally minimal: it reads what a viewer needs and never maps the whole
 * file into memory. It is analysis-only — loading a module is a kernel operation and out of
 * scope here.
 */
class KpmModel private constructor(
    val elfClass: Int,
    val isLittleEndian: Boolean,
    val elfType: Int,
    val machine: Int,
    val sections: List<Section>,
    val symbols: List<Symbol>,
    // Symbol references from .rela* sections, resolved to names — the definitive list of which
    // kernel facilities the module calls into (hook targets, exports it patches).
    val externalReferences: List<String>,
    val moduleInfo: List<Pair<String, String>>,
    val kpmInfo: KpmInfo?,
    // The target kernel string from .modinfo's vermagic=, e.g. "4.14.117-perf SMP preempt
    // mod_unload modversions aarch64" — the direct answer to "which kernel was this built for".
    val vermagic: String?,
    // The real module name from .gnu.linkonce.this_module (struct module's name field), which
    // can differ from the file name; the sample ships as "4.14.117.ko" but is really "entryi".
    val thisModuleName: String?,
    // Symbol→CRC pairs from __versions, when the module was built with modversions enabled.
    val versionChecksums: List<Pair<String, Long>>,
    val strings: List<String>
) {
    enum class Capability(val explanation: String) {
        SYSCALL_HOOK("Hooks system calls — can intercept and forge any syscall result"),
        MEMORY_READ_WRITE("Reads and writes other processes' memory"),
        INPUT_DEVICE_ACCESS("Accesses input devices — touch/key event injection or capture"),
        EVENT_HIDING("Hides input events — the signature of touch-spoofing overlays"),
        CHARDEV_INTERFACE("Registers a character device — talks to a userland companion app"),
        PROC_ENTRY("Creates procfs entries — another userland control channel"),
        BTF_OFFSET_PROBING("Probes BTF type info — adapts struct offsets across kernel versions"),
        KALLSYMS_PROBING("Resolves arbitrary kernel symbols by name"),
        OBFUSCATED_SYMBOLS("Contains deliberately scrambled symbol names")
    }

    data class CapabilityEvidence(val capability: Capability, val matchedNames: List<String>)

    /**
     * The module's capability profile: what it can do, derived from which kernel facilities it
     * references and how its symbols are named. Computed once at parse time.
     */
    val capabilities: List<CapabilityEvidence> by lazy {
        detectCapabilities()
    }

    private fun detectCapabilities(): List<CapabilityEvidence> {
        val evidence = ArrayList<CapabilityEvidence>()
        val referenceSet = externalReferences.toSet()
        val stringSet = strings.toSet()
        val allNames = symbols.map { it.name } + externalReferences

        fun matched(vararg patterns: Regex, pool: Collection<String> = allNames): List<String> =
            pool.filter { name -> patterns.any { it.containsMatchIn(name) } }

        Capability.entries.forEach { capability ->
            val hits: List<String> = when (capability) {
                Capability.SYSCALL_HOOK -> matched(
                    Regex("""hook_syscall"""),
                    Regex("""unhook_syscall"""),
                    Regex("""syscall(_table|_wrapper)?\b"""),
                    Regex("""__x64_sys_|__arm64_sys_""")
                )
                Capability.MEMORY_READ_WRITE -> matched(
                    Regex("""access_(process|remote)_vm"""),
                    Regex("""\bfind_vma\b"""),
                    Regex("""get_task_mm"""),
                    Regex("""copy_(to|from)_user"""),
                    Regex("""__arch_copy_(to|from)_user"""),
                    Regex("""virt_to_page|phys_addr"""),
                    Regex("""get_user_pages""")
                )
                Capability.INPUT_DEVICE_ACCESS -> {
                    // Both symbol references and literal strings count: "/dev/input/*" often
                    // appears only as a path constant.
                    val symbolHits = matched(
                        Regex("""input_(dev|handler|event|absinfo)"""),
                        Regex("""\buinput\b""")
                    )
                    val stringHits = stringSet.filter {
                        it.contains("/dev/input") || it.contains("uinput")
                    }
                    (symbolHits + stringHits).distinct()
                }
                Capability.EVENT_HIDING -> matched(
                    Regex("""hide_event|unhide_event"""),
                    Regex("""hide_icmp|hide_tcp|hide_process""")
                )
                Capability.CHARDEV_INTERFACE -> matched(
                    Regex("""\bcdev_(add|init|del)\b"""),
                    Regex("""alloc_chrdev_region|register_chrdev"""),
                    Regex("""device_(create|destroy)"""),
                    Regex("""class_(create|destroy)""")
                )
                Capability.PROC_ENTRY -> matched(
                    Regex("""proc_(create|remove)"""),
                    Regex("""remove_proc_entry""")
                )
                Capability.BTF_OFFSET_PROBING -> matched(
                    Regex("""\bbtf_\w+""", RegexOption.IGNORE_CASE)
                )
                Capability.KALLSYMS_PROBING -> matched(
                    Regex("""kallsyms_lookup_name"""),
                    Regex("""kallsyms_on_each_symbol""")
                )
                Capability.OBFUSCATED_SYMBOLS -> {
                    // Score symbol names for scrambles: consonant runs with digit sprinkles in
                    // the 6–14 char band, like "F6ash_qg" or "h4kaPo_". Only enough volume
                    // matters — a single odd name is noise, a dozen is a scheme.
                    val suspicious = allNames.filter { isLikelyObfuscated(it) }
                    if (suspicious.size >= OBFUSCATION_THRESHOLD) {
                        suspicious.take(16)
                    } else {
                        emptyList()
                    }
                }
            }
            if (hits.isNotEmpty()) {
                evidence += CapabilityEvidence(capability, hits)
            }
        }
        return evidence
    }


    data class Section(
        val name: String,
        val type: Long,
        val flags: Long,
        val size: Long,
        val offset: Long,
        val link: Int,
        val entrySize: Long,
        val isKpmInfo: Boolean,
        val isModInfo: Boolean,
        val isVersions: Boolean
    )

    data class Symbol(
        val name: String,
        val value: Long,
        val size: Long,
        val type: Int,
        val bind: Int,
        val sectionIndex: Int
    ) {
        val isUndefined: Boolean get() = sectionIndex == SHN_UNDEF
    }

    /** KernelPatch module info from the `.kpm.info`/`.kpm_info` section, when present. */
    data class KpmInfo(
        val magic: String,
        val name: String?,
        val version: String?,
        val license: String?,
        val author: String?,
        val description: String?,
        val extra: List<String>
    )

    val isKpmModule: Boolean
        get() = kpmInfo != null

    val machineName: String
        get() = when (machine) {
            0x3E -> "x86-64"
            0xB7 -> "AArch64"
            0x28 -> "ARM"
            0x03 -> "x86"
            0xB4 -> "LoongArch64"
            0xF3 -> "RISC-V"
            else -> "machine 0x${machine.toString(16)}"
        }

    val elfTypeName: String
        get() = when (elfType) {
            1 -> "Relocatable (kernel module shape)"
            2 -> "Executable"
            3 -> "Shared object"
            else -> "type $elfType"
        }

    companion object {
        private const val ELF_MAGIC_SIZE = 4
        private const val ELF_HEADER_MIN_64 = 64
        private const val SECTION_NAME_SIZE = 40
        private const val SYMBOL_SIZE_64 = 24
        private const val MODINFO_MAX_BYTES = 64 * 1024
        private const val STRINGS_MAX_BYTES = 256 * 1024
        private const val STRINGS_MAX_COUNT = 2000
        private const val MIN_PRINTABLE_RUN = 5

        private const val SHN_UNDEF = 0
        private const val SHT_SYMTAB = 2L
        private const val SHT_STRTAB = 3L
        private const val SHT_RELA = 4L
        private const val RELA_SIZE_64 = 24
        private const val VERSIONS_ENTRY_SIZE = 64
        private const val OBFUSCATION_THRESHOLD = 8

        /**
         * Heuristic for deliberately scrambled symbol names: consonant runs with digit sprinkles
         * in the 6–14 char band, like "F6ash_qg" or "h4kaPo_". Real kernel symbols rarely mix
         * 2+ digits into a short name with no vowels; scramble generators do it constantly.
         */
        internal fun isLikelyObfuscated(name: String): Boolean {
            if (name.length < 6 || name.length > 14) {
                return false
            }
            val vowels = name.count { it in "aeiouAEIOU" }
            val digits = name.count { it.isDigit() }
            val underscores = name.count { it == '_' }
            return digits >= 2 && vowels <= 1 || (underscores >= 2 && digits >= 1 && vowels <= 2)
        }

        // KernelPatch metadata sections are named `.kpm.info` in current toolchains and
        // `.kpm_info` in some older docs; both are flagged.
        private const val KPM_MARKER = "KPM"

        fun read(input: InputStream): KpmModel {
            val bytes = input.readBytes()
            if (bytes.size < ELF_HEADER_MIN_64) {
                throw IOException("File too small for an ELF header")
            }
            if (bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
            ) {
                throw IOException("Not an ELF file")
            }
            val elfClass = bytes[4].toInt() and 0xff
            val isLittleEndian = bytes[5].toInt() and 0xff == 1
            if (elfClass != 1 && elfClass != 2) {
                throw IOException("Unsupported ELF class $elfClass")
            }
            val reader = BufferReader(bytes, isLittleEndian)
            val elfType = reader.u16(offset = 16)
            val machine = reader.u16(offset = 18)
            val sectionTableOffset = reader.u64(offset = 0x28)
            val sectionHeaderSize = reader.u16(offset = 0x3A)
            val sectionCount = reader.u16(offset = 0x3C)
            val sectionNameTableIndex = reader.u16(offset = 0x3E)

            val sections = readSections(
                reader, sectionTableOffset, sectionHeaderSize, sectionCount,
                sectionNameTableIndex
            )
            val symbols = readSymbols(sections, reader)
            val externalReferences = readExternalReferences(sections, reader, symbols)
            val moduleInfo = readModInfo(sections, bytes)
            val kpmInfo = readKpmInfo(sections, bytes)
            val vermagic = moduleInfo.firstOrNull { it.first == "vermagic" }?.second
            val thisModuleName = readThisModuleName(sections, bytes)
            val versionChecksums = readVersionChecksums(sections, bytes)
            val strings = extractStrings(bytes)
            return KpmModel(
                elfClass, isLittleEndian, elfType, machine,
                sections, symbols, externalReferences, moduleInfo, kpmInfo,
                vermagic, thisModuleName, versionChecksums, strings
            )
        }

        private fun readSections(
            reader: BufferReader,
            tableOffset: Long,
            headerSize: Int,
            count: Int,
            nameTableIndex: Int
        ): List<Section> {
            if (headerSize < SECTION_NAME_SIZE || tableOffset <= 0) {
                return emptyList()
            }
            // Read the name table section first (usually the last header), then resolve names.
            val rawHeaders = ArrayList<RawSectionHeader>(count.coerceAtMost(4096))
            for (index in 0 until count) {
                val offset = (tableOffset + index.toLong() * headerSize).toInt()
                if (offset < 0 || offset + SECTION_NAME_SIZE > reader.size) {
                    break
                }
                rawHeaders += RawSectionHeader(
                    nameOffset = reader.u32(offset + 0),
                    type = reader.u32(offset + 4),
                    flags = reader.u64(offset + 8),
                    offset = reader.u64(offset + 24),
                    size = reader.u64(offset + 32),
                    link = reader.u32(offset + 40).toInt(),
                    entrySize = reader.u64(offset + 56)
                )
            }
            val nameTable = rawHeaders.getOrNull(nameTableIndex) ?: return emptyList()
            return rawHeaders.map { header ->
                val name = reader.cString(nameTable.offset + header.nameOffset) ?: ""
                Section(
                    name = name,
                    type = header.type,
                    flags = header.flags,
                    size = header.size,
                    offset = header.offset,
                    link = header.link,
                    entrySize = header.entrySize,
                    isKpmInfo = name == ".kpm_info" || name == ".kpm.info",
                    isModInfo = name == ".modinfo",
                    isVersions = name == "__versions"
                )
            }
        }

        /**
         * Reads the module's symbol table. The associated string table comes from the symtab's
         * `sh_link`, not from a fixed name — relocatable objects produced by different toolchains
         * arrange string tables differently.
         */
        private fun readSymbols(sections: List<Section>, reader: BufferReader): List<Symbol> {
            val symtab = sections.firstOrNull { it.type == SHT_SYMTAB } ?: return emptyList()
            val strtab = sections.getOrNull(symtab.link)
                ?.takeIf { it.type == SHT_STRTAB }
                ?: sections.filter { it.type == SHT_STRTAB }.firstOrNull { it.name == ".strtab" }
                ?: return emptyList()
            val entrySize = if (symtab.entrySize > 0) symtab.entrySize.toInt() else SYMBOL_SIZE_64
            val symbols = ArrayList<Symbol>()
            var offset = symtab.offset
            val end = symtab.offset + symtab.size
            while (offset + entrySize <= end && offset + entrySize <= reader.size) {
                val offsetInt = offset.toInt()
                val nameOffset = reader.u32(offsetInt)
                val value = reader.u64(offsetInt + 8)
                val size = reader.u64(offsetInt + 16)
                val info = reader.u8(offsetInt + 20)
                val sectionIndex = reader.u16(offsetInt + 22).toInt()
                val name = reader.cString(strtab.offset + nameOffset)
                if (!name.isNullOrEmpty()) {
                    symbols += Symbol(
                        name = name,
                        value = value,
                        size = size,
                        type = info and 0xf,
                        bind = (info shr 4) and 0xf,
                        sectionIndex = sectionIndex
                    )
                }
                offset += entrySize
            }
            return symbols
        }

        /**
         * Walks the relocation tables (.rela.*, SHT_RELA) and resolves every reference through
         * the symbol table. Undefined symbols reached this way are exactly the kernel facilities
         * the module calls — hook helpers like `hook_syscalln`, or patched functions such as
         * `access_process_vm` — which is the strongest single signal of what a module does.
         */
        private fun readExternalReferences(
            sections: List<Section>,
            reader: BufferReader,
            symbols: List<Symbol>
        ): List<String> {
            val references = LinkedHashSet<String>()
            for (section in sections) {
                if (section.type != SHT_RELA) {
                    continue
                }
                val symtab = sections.getOrNull(section.link)
                    ?.takeIf { it.type == SHT_SYMTAB }
                    ?: continue
                val entrySize = if (section.entrySize > 0) section.entrySize.toInt() else RELA_SIZE_64
                var offset = section.offset
                val end = section.offset + section.size
                while (offset + entrySize <= end && offset + entrySize <= reader.size) {
                    val offsetInt = offset.toInt()
                    val symbolIndex = reader.u32(offsetInt + 8).toInt()
                    symbols.getOrNull(symbolIndex)?.let { symbol ->
                        if (symbol.sectionIndex == SHN_UNDEF) {
                            references += symbol.name
                        }
                    }
                    offset += entrySize
                }
            }
            // Fall back to the symtab's undefined entries when there are no relocation tables.
            if (references.isEmpty()) {
                symbols.filterTo(LinkedHashSet()) { it.isUndefined }.forEach { references.add(it.name) }
            }
            return references.toList()
        }

        /** Linux `.modinfo` is a NUL-separated list of `key=value` strings. */
        private fun readModInfo(sections: List<Section>, bytes: ByteArray): List<Pair<String, String>> {
            val section = sections.firstOrNull { it.isModInfo } ?: return emptyList()
            val length = section.size.coerceAtMost(MODINFO_MAX_BYTES.toLong())
                .coerceAtMost((bytes.size - section.offset).coerceAtLeast(0).toLong())
            if (length <= 0) {
                return emptyList()
            }
            val content = String(bytes, section.offset.toInt(), length.toInt(), StandardCharsets.UTF_8)
            return content.split('\u0000')
                .filter { it.contains('=') }
                .map { entry ->
                    val index = entry.indexOf('=')
                    entry.substring(0, index) to entry.substring(index + 1)
                }
        }

        /**
         * KernelPatch module metadata. Real-world modules (verified against a KernelPatch 0.10.x
         * build) carry it in a `.kpm.info` section holding NUL/newline-separated `key=value`
         * lines (name/version/license/author/description) rather than the binary "KPM"-magic
         * struct — parse it like modinfo and only fall back to a raw dump when the content
         * doesn't fit that shape.
         */
        private fun readKpmInfo(sections: List<Section>, bytes: ByteArray): KpmInfo? {
            val section = sections.firstOrNull { it.isKpmInfo } ?: return null
            val length = section.size
                .coerceAtMost(MODINFO_MAX_BYTES.toLong())
                .coerceAtMost((bytes.size - section.offset).coerceAtLeast(0).toLong())
            if (length <= 0) {
                return null
            }
            val content = String(
                bytes, section.offset.toInt(), length.toInt(), StandardCharsets.UTF_8
            )
            val fields = content.split('\u0000', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            fun field(key: String): String? =
                fields.firstOrNull { it.startsWith("$key=") }?.substring(key.length + 1)
            if (fields.any { it.startsWith("name=") }) {
                return KpmInfo(
                    magic = KPM_MARKER,
                    name = field("name"),
                    version = field("version") ?: field("version_name"),
                    license = field("license"),
                    author = field("author"),
                    description = field("description"),
                    // kpm.info may list per-kernel-version offsets; keep them as-is.
                    extra = fields.filterNot { line ->
                        listOf("name=", "version=", "license=", "author=", "description=")
                            .any { line.startsWith(it) }
                    }
                )
            }
            if (content.startsWith(KPM_MARKER)) {
                return KpmInfo(
                    magic = KPM_MARKER, name = null, version = null, license = null,
                    author = null, description = null,
                    extra = content.substring(KPM_MARKER.length).split('\u0000', '\n')
                        .map { it.trim() }.filter { it.isNotEmpty() }
                )
            }
            return KpmInfo(
                magic = content.take(KPM_MARKER.length), name = null, version = null,
                license = null, author = null, description = null, extra = fields
            )
        }

        /**
         * Extracts the real module name from `.gnu.linkonce.this_module` — the in-memory
         * `struct module` whose name field sits a few bytes in (offset varies across kernel
         * versions). The sample's file name is "4.14.117.ko" but the struct says "entryi", which
         * is exactly the kind of discrepancy worth surfacing. Falls back to the first printable
         * run within the section's first 64 bytes.
         */
        private fun readThisModuleName(sections: List<Section>, bytes: ByteArray): String? {
            val section = sections.firstOrNull { it.name == ".gnu.linkonce.this_module" }
                ?: return null
            val length = section.size
                .coerceAtMost(64L)
                .coerceAtMost((bytes.size - section.offset).coerceAtLeast(0).toLong())
            if (length <= 0) {
                return null
            }
            // Known struct offsets of the name field (module versions 4.x–6.x put it at 0..16);
            // try them first, then accept any printable run.
            for (probe in intArrayOf(0, 8, 16, 24, 32, 48)) {
                if (probe >= length) {
                    break
                }
                val candidate = readPrintableRun(bytes, section.offset.toInt() + probe, 64)
                if (!candidate.isNullOrEmpty() && candidate[0].isLetter()) {
                    return candidate
                }
            }
            return readPrintableRun(bytes, section.offset.toInt(), length.toInt())
        }

        private fun readPrintableRun(bytes: ByteArray, start: Int, maxLength: Int): String? {
            var index = start
            val end = minOf(start + maxLength, bytes.size)
            while (index < end && bytes[index].toInt() in 0x21..0x7e) {
                ++index
            }
            val run = String(bytes, start, index - start, StandardCharsets.UTF_8)
            return run.takeIf { it.length >= 3 }
        }

        /**
         * Reads the __versions table (`{ unsigned long crc; char name[56]; }` per entry) used
         * when the module was built with modversions. Empty for modules pinned to one kernel.
         */
        private fun readVersionChecksums(
            sections: List<Section>,
            bytes: ByteArray
        ): List<Pair<String, Long>> {
            val section = sections.firstOrNull { it.isVersions } ?: return emptyList()
            val versions = ArrayList<Pair<String, Long>>()
            var offset = section.offset
            val end = section.offset + section.size
            while (offset + VERSIONS_ENTRY_SIZE <= end && offset + VERSIONS_ENTRY_SIZE <= bytes.size) {
                val offsetInt = offset.toInt()
                val crc = readLittleEndianLong(bytes, offsetInt)
                val nameStart = offsetInt + 8
                val name = readPrintableRun(bytes, nameStart, 56)
                if (!name.isNullOrEmpty()) {
                    versions += name to crc
                }
                offset += VERSIONS_ENTRY_SIZE
            }
            return versions
        }

        private fun readLittleEndianLong(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (index in 7 downTo 0) {
                value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
            }
            return value
        }

        /**
         * Printable ASCII runs, capped so a 10 MB module cannot flood the view.
         */
        private fun extractStrings(bytes: ByteArray): List<String> {
            val strings = ArrayList<String>()
            val builder = StringBuilder()
            val limit = bytes.size.coerceAtMost(STRINGS_MAX_BYTES)
            for (index in 0 until limit) {
                val b = bytes[index].toInt() and 0xff
                if (b in 0x20..0x7e) {
                    builder.append(b.toChar())
                } else {
                    if (builder.length >= MIN_PRINTABLE_RUN) {
                        strings += builder.toString()
                        if (strings.size >= STRINGS_MAX_COUNT) {
                            return strings
                        }
                    }
                    builder.setLength(0)
                }
            }
            if (builder.length >= MIN_PRINTABLE_RUN && strings.size < STRINGS_MAX_COUNT) {
                strings += builder.toString()
            }
            return strings
        }

        private data class RawSectionHeader(
            val nameOffset: Long,
            val type: Long,
            val flags: Long,
            val offset: Long,
            val size: Long,
            val link: Int,
            val entrySize: Long
        )
    }
}

/** Byte-level reads over a fixed buffer, honoring the file's endianness. */
private class BufferReader(private val bytes: ByteArray, private val littleEndian: Boolean) {
    val size: Int
        get() = bytes.size

    fun u8(offset: Int): Int =
        if (offset in bytes.indices) bytes[offset].toInt() and 0xff else 0

    fun u16(offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) {
            return 0
        }
        val a = bytes[offset].toInt() and 0xff
        val b = bytes[offset + 1].toInt() and 0xff
        return if (littleEndian) a or (b shl 8) else (a shl 8) or b
    }

    fun u32(offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) {
            return 0
        }
        var value = 0L
        if (littleEndian) {
            for (index in 3 downTo 0) {
                value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
            }
        } else {
            for (index in 0..3) {
                value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
            }
        }
        return value
    }

    fun u64(offset: Int): Long {
        if (offset < 0 || offset + 8 > bytes.size) {
            return 0
        }
        var value = 0L
        if (littleEndian) {
            for (index in 7 downTo 0) {
                value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
            }
        } else {
            for (index in 0..7) {
                value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
            }
        }
        return value
    }

    fun cString(offset: Long): String? {
        val start = offset.toInt()
        if (start < 0 || start >= bytes.size) {
            return null
        }
        var end = start
        while (end < bytes.size && bytes[end] != 0.toByte()) {
            ++end
        }
        return String(bytes, start, end - start, StandardCharsets.UTF_8)
    }
}
