/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.arsc

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses Android's compiled resource table (`resources.arsc`) into an [ArscTable] suitable for
 * read-only display.
 *
 * The format (see `ResourceTypes.h`) is:
 * ```
 *   RES_TABLE_TYPE (0x000C0002)
 *     Global String Pool
 *     Package* (RES_TABLE_PACKAGE_TYPE = 0x0200)
 *       Type String Pool       (names of types: "string", "layout", "drawable"…)
 *       Key String Pool        (names of resource entries: "app_name", "ic_launcher"…)
 *       TypeSpec* (0x0202)     (one per type, lists entry flags like PUBLIC)
 *       Type* (0x0201)         (concrete entries for a type, possibly multiple per type for configs)
 * ```
 *
 * We extract: for each package, for each type, the list of entries (key name + resolved value
 * string). Values that reference other resources or are complex (e.g. color ints) are rendered
 * as their raw representation.
 *
 * @see <a href="https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h">ResourceTypes.h</a>
 */
internal object ArscParser {

    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202

    /**
     * Parses [data] into an [ArscTable]. Throws [IOException] on malformed input.
     */
    fun parse(data: ByteArray): ArscTable {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Table header
        val tableType = buf.short.toInt() and 0xFFFF
        if (tableType != RES_TABLE_TYPE) {
            throw IOException("Not a resources.arsc file (type 0x${tableType.toString(16)})")
        }
        val headerSize = (buf.short.toInt() and 0xFFFF)
        buf.int // chunkSize
        val packageCount = buf.getInt(8)

        // Global string pool (the "value" strings pool — contains string resource values, etc.)
        val globalStrings = readStringPool(buf, 0)

        // Skip to first package: position right after the table header + string pool chunk.
        // The string pool chunk starts at offset 0 (the table header and string pool share the
        // leading chunk). Actually the table header IS the first chunk; the string pool is the
        // second chunk at offset = tableChunkSize. Let me re-read.
        // The on-disk layout: [table_chunk_header(12 bytes incl packageCount)] then the string
        // pool chunk, then packages. The table chunkSize covers everything.

        // Re-position: after table header (headerSize) the string pool chunk begins.
        var pos = headerSize // string pool chunk start
        val stringPoolChunkSize = buf.getInt(pos + 4)
        pos += stringPoolChunkSize // first package

        val packages = mutableListOf<ArscPackage>()
        for (i in 0 until packageCount) {
            if (pos + 8 > buf.limit()) break
            val pkg = readPackage(buf, pos, globalStrings)
            if (pkg != null) packages.add(pkg)
            val pkgChunkSize = buf.getInt(pos + 4)
            pos += pkgChunkSize
        }

        return ArscTable(packages)
    }

    // -----------------------------------------------------------------------------------
    //  Package
    // -----------------------------------------------------------------------------------

    private fun readPackage(
        buf: ByteBuffer, chunkStart: Int, globalStrings: List<String>
    ): ArscPackage? {
        val chunkType = buf.getShort(chunkStart).toInt() and 0xFFFF
        if (chunkType != RES_TABLE_PACKAGE_TYPE) return null

        val headerSize = buf.getShort(chunkStart + 2).toInt() and 0xFFFF
        val chunkSize = buf.getInt(chunkStart + 4)
        // Package ID (uint32) at offset 8.
        val packageId = buf.getInt(chunkStart + 8)
        // Package name: 256 UTF-16 chars (512 bytes) at offset 12.
        val name = readFixedUtf16String(buf, chunkStart + 12, 256)
        // typeStrings offset (uint32) at +268, lastPublicType (uint32) at +272
        // keyStrings offset (uint32) at +276, lastPublicKey (uint32) at +280
        val typeStringsOffset = buf.getInt(chunkStart + 268)
        val typeCount = buf.getInt(chunkStart + 272)
        val keyStringsOffset = buf.getInt(chunkStart + 276)
        val keyCount = buf.getInt(chunkStart + 280)

        val typeNames: List<String> = if (typeStringsOffset > 0) {
            readStringPool(buf, chunkStart + typeStringsOffset)
        } else emptyList()
        val keyNames: List<String> = if (keyStringsOffset > 0) {
            readStringPool(buf, chunkStart + keyStringsOffset)
        } else emptyList()

        // Walk sub-chunks after the header to find Type chunks (0x0201) that carry entries.
        // TypeSpec (0x0202) and Type (0x0201) chunks appear after the key string pool.
        val entriesByType = mutableMapOf<String, MutableList<ArscEntry>>()

        var subPos = chunkStart + headerSize
        // Skip past the string pool chunks (they live within the package body but at known offsets).
        // The type/key string pools start at typeStringsOffset/keyStringsOffset from chunkStart.
        // Sub-chunks (TypeSpec, Type) appear after both string pools. We scan from the end of
        // the last string pool to the end of the package chunk.
        val scanStart = maxOf(
            chunkStart + (if (keyStringsOffset > 0) keyStringsOffset else typeStringsOffset),
            chunkStart + headerSize
        )
        // Account for string pool chunk size.
        val stringPoolEnd = if (keyStringsOffset > 0) {
            val spSize = buf.getInt(chunkStart + keyStringsOffset + 4)
            chunkStart + keyStringsOffset + spSize
        } else scanStart
        subPos = maxOf(scanStart, stringPoolEnd)

        while (subPos + 8 <= chunkStart + chunkSize) {
            val subType = buf.getShort(subPos).toInt() and 0xFFFF
            val subSize = buf.getInt(subPos + 4)
            if (subSize < 8) break
            if (subType == RES_TABLE_TYPE_TYPE) {
                val typeEntry = readTypeChunk(buf, subPos, typeNames, keyNames, globalStrings)
                if (typeEntry != null) {
                    val list = entriesByType.getOrPut(typeEntry.typeName) { mutableListOf() }
                    list.addAll(typeEntry.entries)
                }
            }
            subPos += subSize
        }

        val types = entriesByType.map { (typeName, entries) ->
            ArscType(typeName, entries.distinctBy { it.key })
        }
        return ArscPackage(packageId, name, types)
    }

    // -----------------------------------------------------------------------------------
    //  Type chunk (0x0201) — contains actual resource entries
    // -----------------------------------------------------------------------------------

    private data class TypeParseResult(val typeName: String, val entries: List<ArscEntry>)

    private fun readTypeChunk(
        buf: ByteBuffer, chunkStart: Int,
        typeNames: List<String>, keyNames: List<String>,
        globalStrings: List<String>
    ): TypeParseResult? {
        val headerSize = buf.getShort(chunkStart + 2).toInt() and 0xFFFF
        // Type chunk layout after header:
        //   uint8 id (the type index), uint8 flags, uint16 reserved
        //   uint32 entryCount
        //   uint32 entriesStart (offset to entry data, from chunk start)
        //   ResTable_config (the configuration: size + fields…)
        // Then: uint32 entryOffsets[entryCount], then entries.
        val typeId = buf.get(chunkStart + 8).toInt() and 0xFF
        val entryCount = buf.getInt(chunkStart + 12)
        val entriesStart = buf.getInt(chunkStart + 16)

        // The config struct starts at offset 20; its size is variable. The headerSize field
        // tells us where the entry offset array begins.
        val typeName = typeNames.getOrNull(typeId - 1) ?: "type_$typeId"

        val offsetsBase = chunkStart + headerSize
        val entriesBase = chunkStart + entriesStart

        val entries = mutableListOf<ArscEntry>()
        for (i in 0 until entryCount) {
            val entryOffset = buf.getInt(offsetsBase + i * 4)
            if (entryOffset == -1) continue // No entry at this index.
            val entryPos = entriesBase + entryOffset
            if (entryPos + 8 > buf.limit()) continue

            val entrySize = buf.getShort(entryPos).toInt() and 0xFFFF
            val entryFlags = buf.getShort(entryPos + 2).toInt() and 0xFFFF
            val keyIndex = buf.getInt(entryPos + 4)
            val key = keyNames.getOrNull(keyIndex) ?: "key_$keyIndex"

            val value = readEntryValue(buf, entryPos, entrySize, entryFlags, globalStrings)
            entries.add(ArscEntry(key, value.display, value.rawDataType, value.rawData, value.bagItems))
        }
        return TypeParseResult(typeName, entries)
    }

    /**
     * Reads a single resource entry's value. Entries are either simple (FLAG_COMPLEX clear:
     * a single Res_value) or complex/bagged (FLAG_COMPLEX set: a map of key→Res_value pairs).
     *
     * Returns an [EntryValue] carrying both the display string and the raw typed-value data
     * needed for round-trip encoding.
     */
    private fun readEntryValue(
        buf: ByteBuffer, entryPos: Int, entrySize: Int, entryFlags: Int,
        globalStrings: List<String>
    ): EntryValue {
        val isComplex = (entryFlags and 0x0001) != 0
        if (!isComplex) {
            if (entryPos + 16 > buf.limit()) return EntryValue("<invalid>")
            val dataType = buf.get(entryPos + 8 + 3).toInt() and 0xFF
            val data = buf.getInt(entryPos + 8 + 4)
            val display = formatTypedValue(dataType, data, globalStrings)
            return EntryValue(display, dataType, data, null)
        }
        // Complex (bag) entry: uint32 parentRef, then uint32 count, then (uint32 key | Res_value)*.
        if (entryPos + 16 > buf.limit()) return EntryValue("<invalid bag>")
        val parentRef = buf.getInt(entryPos + 8)
        val count = buf.getInt(entryPos + 12)
        val sb = StringBuilder()
        if (parentRef != 0) sb.append("(parent=0x${parentRef.toString(16)}) ")
        sb.append('{')
        val bagItems = mutableListOf<BagItem>()
        var bagPos = entryPos + 16
        for (i in 0 until count) {
            if (bagPos + 12 > buf.limit()) break
            val bagKey = buf.getInt(bagPos)
            val itemDataType = buf.get(bagPos + 4 + 3).toInt() and 0xFF
            val itemData = buf.getInt(bagPos + 4 + 4)
            val valStr = formatTypedValue(itemDataType, itemData, globalStrings)
            if (i > 0) sb.append(", ")
            sb.append("0x${bagKey.toString(16)}=$valStr")
            bagItems.add(BagItem(bagKey, itemDataType, itemData))
            bagPos += 12
        }
        sb.append('}')
        return EntryValue(sb.toString(), 0, 0, bagItems)
    }

    /**
     * Formats a (dataType, data) pair into a human-readable string.
     */
    private fun formatTypedValue(dataType: Int, data: Int, globalStrings: List<String>): String =
        when (dataType) {
            0x00 -> "" // TYPE_NULL
            0x01 -> "@0x${data.toString(16)}" // TYPE_REFERENCE
            0x02 -> "@android:0x${data.toString(16)}" // TYPE_ATTRIBUTE
            0x03 -> globalStrings.getOrNull(data) ?: "<string #$data>" // TYPE_STRING
            0x04 -> Float.fromBits(data).toString() // TYPE_FLOAT
            0x05 -> dimensionToString(data) // TYPE_DIMENSION
            0x06 -> fractionToString(data) // TYPE_FRACTION
            0x10 -> data.toString() // TYPE_INT_DEC
            0x11 -> "0x${data.toLong().and(0xFFFFFFFFL).toString(16)}" // TYPE_INT_HEX
            0x12 -> if (data != 0) "true" else "false" // TYPE_INT_BOOLEAN
            0x1c -> "#${data.toLong().and(0xFFFFFFFFL).toString(16)}" // TYPE_INT_COLOR_ARGB8
            0x1d -> "#${(data and 0xFFFF).toString(16)}" // TYPE_INT_COLOR_RGB4
            else -> "0x${data.toLong().and(0xFFFFFFFFL).toString(16)} <type $dataType>"
        }

    /** Decodes a TYPE_DIMENSION value (complex unit + mantissa/exponent). */
    private fun dimensionToString(data: Int): String {
        val unit = data and 0x0F
        val value = complexToFloat(data)
        val suffix = when (unit) {
            0 -> "px"; 1 -> "dip"; 2 -> "sp"; 3 -> "pt"; 4 -> "in"; 5 -> "mm"
            else -> ""
        }
        return "$value$suffix"
    }

    /** Decodes a TYPE_FRACTION value. */
    private fun fractionToString(data: Int): String {
        val unit = data and 0x0F
        val value = complexToFloat(data)
        val suffix = when (unit) { 0 -> "%"; 1 -> "%p"; else -> "" }
        return "$value$suffix"
    }

    /**
     * Converts a COMPLEX (mantissa+exponent) encoded int to a float string.
     * ResourceTypes.h: value = mantissa * 2^exponent * (1/256), rounded to 2 decimal places.
     */
    private fun complexToFloat(data: Int): String {
        val mantissa = (data shr 8) and 0xFFFFFF
        val exponent = (data shr 4) and 0x07
        val value = mantissa.toFloat() * Math.pow(2.0, exponent.toDouble()).toFloat() / 256f
        return String.format("%.2f", value)
    }

    // -----------------------------------------------------------------------------------
    //  String pool reader (shared with AXML but simpler to keep a local copy)
    // -----------------------------------------------------------------------------------

    private fun readStringPool(buf: ByteBuffer, chunkStart: Int): List<String> {
        if (chunkStart + 28 > buf.limit()) return emptyList()
        val chunkType = buf.getShort(chunkStart).toInt() and 0xFFFF
        if (chunkType != RES_STRING_POOL_TYPE) return emptyList()
        val stringCount = buf.getInt(chunkStart + 8)
        val flags = buf.getInt(chunkStart + 16)
        val stringsStart = buf.getInt(chunkStart + 20)
        val isUtf8 = (flags and (1 shl 8)) != 0

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = buf.getInt(chunkStart + 28 + i * 4)
        }

        val result = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val strOffset = chunkStart + stringsStart + offsets[i]
            result.add(
                if (isUtf8) readUtf8(buf, strOffset) else readUtf16(buf, strOffset)
            )
        }
        return result
    }

    private fun readUtf8(buf: ByteBuffer, offset: Int): String {
        var pos = offset
        // Two length varints (chars, bytes). Skip both, then read to NUL.
        pos += if ((buf.get(pos).toInt() and 0x80) != 0) 2 else 1 // char count
        pos += if ((buf.get(pos).toInt() and 0x80) != 0) 2 else 1 // byte count
        var p = pos
        while (p < buf.limit() && buf.get(p).toInt() != 0) p++
        val bytes = ByteArray(p - pos)
        buf.position(pos)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteBuffer, offset: Int): String {
        val len = buf.getShort(offset).toInt() and 0xFFFF
        val skip = if (len and 0x8000 != 0) 4 else 2
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            sb.append(buf.getChar(offset + skip + i * 2))
        }
        return sb.toString()
    }

    private fun readFixedUtf16String(buf: ByteBuffer, offset: Int, maxChars: Int): String {
        val sb = StringBuilder()
        for (i in 0 until maxChars) {
            val ch = buf.getChar(offset + i * 2)
            if (ch.toInt() == 0) break
            sb.append(ch)
        }
        return sb.toString()
    }
}

/**
 * Internal result of parsing one entry's value: the display string plus the raw typed-value data
 * needed for round-trip encoding.
 */
internal data class EntryValue(
    val display: String,
    val rawDataType: Int = 0x03,
    val rawData: Int = 0,
    val bagItems: List<BagItem>? = null
)

/** Root model: a list of [ArscPackage]s found in the table. */
data class ArscTable(val packages: List<ArscPackage>)

/** A package (identified by its Java-style name). */
data class ArscPackage(val id: Int, val name: String, val types: List<ArscType>)

/** A resource type within a package (e.g. "string", "drawable", "layout"). */
data class ArscType(val name: String, val entries: List<ArscEntry>)

/**
 * A single resource entry: key name + resolved value string for display, plus the raw typed value
 * (data type + data) for round-trip encoding. [rawDataType] and [rawData] are only set for simple
 * (non-bag) entries; bag entries keep [value] as their formatted representation and are written
 * back verbatim by the encoder using [rawBagItems].
 */
data class ArscEntry(
    val key: String,
    val value: String,
    val rawDataType: Int = 0x03, // TYPE_STRING by default
    val rawData: Int = 0,
    /** For bag (complex) entries: the list of (bagKey, dataType, data) tuples. Null for simple. */
    val rawBagItems: List<BagItem>? = null
)

/** One item in a bag (complex) resource entry. */
data class BagItem(val key: Int, val dataType: Int, val data: Int)
