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
    val moduleInfo: List<Pair<String, String>>,
    val kpmInfo: KpmInfo?,
    val strings: List<String>
) {
    data class Section(
        val name: String,
        val type: Long,
        val flags: Long,
        val size: Long,
        val offset: Long,
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

    /** KernelPatch module info from `.kpm_info`, when present. */
    data class KpmInfo(val magic: String, val versionName: String?, val author: String?)

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

        // KernelPatch stores its metadata in a dedicated .kpm_info section; the leading magic
        // identifies it. See KernelPatch's kpm headers for the authoritative layout.
        private const val KPM_MAGIC = "KPM"

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
            val moduleInfo = readModInfo(sections, bytes)
            val kpmInfo = readKpmInfo(sections, bytes)
            val strings = extractStrings(bytes)
            return KpmModel(
                elfClass, isLittleEndian, elfType, machine,
                sections, symbols, moduleInfo, kpmInfo, strings
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
                    size = reader.u64(offset + 32)
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
                    isKpmInfo = name == ".kpm_info",
                    isModInfo = name == ".modinfo",
                    isVersions = name == "__versions"
                )
            }
        }

        private fun readSymbols(sections: List<Section>, reader: BufferReader): List<Symbol> {
            val symtab = sections.firstOrNull { it.type == SHT_SYMTAB } ?: return emptyList()
            val strtab = sections.filter { it.type == SHT_STRTAB }
                .firstOrNull { it.name == ".strtab" }
                ?: return emptyList()
            val symbols = ArrayList<Symbol>()
            var offset = symtab.offset
            val end = symtab.offset + symtab.size
            while (offset + SYMBOL_SIZE_64 <= end && offset + SYMBOL_SIZE_64 <= reader.size) {
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
                offset += SYMBOL_SIZE_64
            }
            return symbols
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

        /** KernelPatch `.kpm_info` begins with a "KPM" magic followed by module metadata. */
        private fun readKpmInfo(sections: List<Section>, bytes: ByteArray): KpmInfo? {
            val section = sections.firstOrNull { it.isKpmInfo } ?: return null
            val length = section.size
                .coerceAtMost(MODINFO_MAX_BYTES.toLong())
                .coerceAtMost((bytes.size - section.offset).coerceAtLeast(0).toLong())
            if (length < KPM_MAGIC.length) {
                return null
            }
            val content = String(
                bytes, section.offset.toInt(), length.toInt(), StandardCharsets.UTF_8
            )
            if (!content.startsWith(KPM_MAGIC)) {
                return KpmInfo(magic = content.take(KPM_MAGIC.length), null, null)
            }
            // The remainder is NUL-separated metadata; keep the recognizable fields.
            val fields = content.substring(KPM_MAGIC.length).split('\u0000')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            fun fieldWith(prefix: String): String? =
                fields.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            return KpmInfo(
                magic = KPM_MAGIC,
                versionName = fieldWith("version=") ?: fields.firstOrNull(),
                author = fieldWith("author=") ?: fields.getOrNull(1)
            )
        }

        /** Printable ASCII runs, capped so a 10 MB module cannot flood the view. */
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
            val size: Long
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
