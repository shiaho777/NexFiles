/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.axml

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses Android's binary XML format (AXML) into a DOM-like tree of [BinaryXmlNode].
 *
 * The format is a sequence of typed chunks (little-endian):
 * ```
 *   RES_XML_TYPE (0x00080003)          — file header
 *   RES_STRING_POOL_TYPE (0x000C0001)  — global string pool
 *   RES_XML_RESOURCE_MAP_TYPE (0x0801) — resource ID array (one uint32 per string)
 *   RES_XML_START_NAMESPACE_TYPE / RES_XML_END_NAMESPACE_TYPE
 *   RES_XML_START_ELEMENT_TYPE (0x0102) / RES_XML_END_ELEMENT_TYPE (0x0103)
 *   RES_XML_CDATA_TYPE (0x0104)
 * ```
 *
 * Each chunk: `uint16 type | uint16 headerSize | uint32 chunkSize | …payload`.
 *
 * String pool strings are either UTF-8 or UTF-16 encoded (flag 1<<8 in `stringCount/flags`).
 * Attribute values in START_ELEMENT may reference a string (raw) or be a typed value
 * (int/float/bool/reference — `uint8 size | uint8 res0 | uint16 dataType | uint32 data`).
 *
 * @see <a href="https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h">ResourceTypes.h</a>
 */
internal object BinaryXmlParser {

    // Chunk types
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104

    // Typed value data types (subset; the full enum is in ResourceTypes.h)
    private const val TYPE_NULL = 0x00
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_ATTRIBUTE = 0x02
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val TYPE_FLOAT = 0x04

    /**
     * Parses [data] into an [BinaryXmlNode] tree. Throws [IOException] if the data is not valid
     * binary XML.
     */
    fun parse(data: ByteArray): BinaryXmlNode {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        // File header: must be RES_XML_TYPE.
        val fileType = buf.short.toInt() and 0xFFFF
        if (fileType != RES_XML_TYPE) {
            throw IOException("Not a binary XML file (expected type 0x0003, got 0x${fileType.toString(16)})")
        }
        buf.position(8) // skip headerSize(2) + fileSize(4)

        var strings: List<String> = emptyList()
        var resourceIds: IntArray = IntArray(0)
        val rootNodes = mutableListOf<BinaryXmlNode>()
        val nodeStack = ArrayDeque<BinaryXmlNode.Element>()

        while (buf.remaining() >= 8) {
            val chunkStart = buf.position()
            val chunkType = buf.short.toInt() and 0xFFFF
            val headerSize = (buf.short.toInt() and 0xFFFF)
            val chunkSize = buf.int
            if (chunkSize < 8 || chunkStart + chunkSize > buf.limit()) break

            when (chunkType) {
                RES_STRING_POOL_TYPE -> {
                    strings = readStringPool(buf, chunkStart, chunkSize)
                }
                RES_XML_RESOURCE_MAP_TYPE -> {
                    resourceIds = readResourceMap(buf, chunkStart, headerSize, chunkSize)
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    val node = readStartElement(buf, chunkStart, headerSize, strings)
                    if (nodeStack.isEmpty()) {
                        rootNodes.add(node)
                    } else {
                        nodeStack.last().children.add(node)
                    }
                    nodeStack.addLast(node)
                }
                RES_XML_END_ELEMENT_TYPE -> {
                    if (nodeStack.isNotEmpty()) nodeStack.removeLast()
                }
                RES_XML_CDATA_TYPE -> {
                    // CDATA is rare in AXML; we append it as a text child of the current element.
                    val stringIndex = buf.getInt(chunkStart + headerSize)
                    val text = strings.getOrNull(stringIndex) ?: ""
                    if (nodeStack.isNotEmpty()) {
                        nodeStack.last().children.add(BinaryXmlNode.Text(text))
                    }
                }
            }
            buf.position(chunkStart + chunkSize)
        }

        if (rootNodes.isEmpty()) throw IOException("Binary XML has no root element")
        if (rootNodes.size == 1) return rootNodes[0]
        // Wrap multiple roots in a synthetic container (unusual but defensive).
        return BinaryXmlNode.Element("__root__", rootNodes)
    }

    // -----------------------------------------------------------------------------------
    //  String pool
    // -----------------------------------------------------------------------------------

    private fun readStringPool(buf: ByteBuffer, chunkStart: Int, chunkSize: Int): List<String> {
        val stringCount = buf.getInt(chunkStart + 8)
        // styleCount at +12, flags at +16, stringsStart at +20, stylesStart at +24.
        val flags = buf.getInt(chunkStart + 16)
        val stringsStart = buf.getInt(chunkStart + 20)
        val isUtf8 = (flags and (1 shl 8)) != 0

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = buf.getInt(chunkStart + 28 + i * 4)
        }

        val result = mutableListOf<String>()
        for (i in 0 until stringCount) {
            val strOffset = chunkStart + stringsStart + offsets[i]
            result.add(if (isUtf8) readUtf8String(buf, strOffset) else readUtf16String(buf, strOffset))
        }
        return result
    }

    private fun readUtf8String(buf: ByteBuffer, offset: Int): String {
        // Two length varints (char count, byte count), then UTF-8 bytes + NUL. Skip both varints
        // and read until NUL — simpler than respecting the lengths and handles edge cases.
        var pos = offset
        pos += lengthOfSize(buf, pos) // char count
        pos += lengthOfSize(buf, pos) // byte count
        val end = findNul(buf, pos)
        val bytes = ByteArray(end - pos)
        buf.position(pos)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** Number of bytes the varint length occupies at [pos]. */
    private fun lengthOfSize(buf: ByteBuffer, pos: Int): Int =
        if ((buf.get(pos).toInt() and 0x80) != 0) 2 else 1

    private fun findNul(buf: ByteBuffer, pos: Int): Int {
        var p = pos
        while (p < buf.limit() && buf.get(p).toInt() != 0) p++
        return p
    }

    private fun readUtf16String(buf: ByteBuffer, offset: Int): String {
        val charLen = buf.getShort(offset).toInt() and 0xFFFF
        // Skip the utf-16 length prefix (2 bytes). If high bit set, it's a 2-field (chars, bytes) prefix.
        val skip = if (charLen and 0x8000 != 0) 4 else 2
        val start = offset + skip
        val sb = StringBuilder(charLen)
        for (i in 0 until charLen) {
            sb.append(buf.getChar(start + i * 2))
        }
        return sb.toString()
    }

    // -----------------------------------------------------------------------------------
    //  Resource map
    // -----------------------------------------------------------------------------------

    private fun readResourceMap(
        buf: ByteBuffer, chunkStart: Int, headerSize: Int, chunkSize: Int
    ): IntArray {
        val count = (chunkSize - headerSize) / 4
        val ids = IntArray(count)
        for (i in 0 until count) {
            ids[i] = buf.getInt(chunkStart + headerSize + i * 4)
        }
        return ids
    }

    // -----------------------------------------------------------------------------------
    //  START_ELEMENT
    // -----------------------------------------------------------------------------------

    private fun readStartElement(
        buf: ByteBuffer, chunkStart: Int, headerSize: Int, strings: List<String>
    ): BinaryXmlNode.Element {
        // After the 8-byte chunk header:
        //   uint32 lineNumber, uint32 comment (string index)
        //   uint32 ns (string index), uint32 name (string index)
        //   uint16 attrStart, uint16 attrSize, uint16 attrCount, uint16 idIdx, uint16 classIdx, uint16 styleIdx
        // Then attrCount attributes, each 20 bytes:
        //   uint32 ns, uint32 name, uint32 rawValue, uint8 size, uint8 res0, uint16 type, uint32 data
        val bodyStart = chunkStart + headerSize
        val nameIndex = buf.getInt(bodyStart + 8) // ns at +4, name at +8
        val name = strings.getOrNull(nameIndex) ?: ""

        val attrStart = (buf.getShort(bodyStart + 12).toInt() and 0xFFFF)
        val attrCount = (buf.getShort(bodyStart + 16).toInt() and 0xFFFF)

        val attrs = mutableListOf<BinaryXmlAttribute>()
        val attrBase = bodyStart + attrStart
        for (i in 0 until attrCount) {
            val off = attrBase + i * 20
            val attrNsIndex = buf.getInt(off)
            val attrNameIndex = buf.getInt(off + 4)
            val rawValueIndex = buf.getInt(off + 8)
            val typedSize = buf.get(off + 12).toInt() and 0xFF
            val typedType = buf.getShort(off + 14).toInt() and 0xFFFF
            val typedData = buf.getInt(off + 16)

            val attrName = strings.getOrNull(attrNameIndex) ?: ""
            val attrNs = strings.getOrNull(attrNsIndex)
            val value = resolveAttributeValue(typedType, typedData, rawValueIndex, strings)
            attrs.add(BinaryXmlAttribute(attrName, attrNs, value))
        }

        return BinaryXmlNode.Element(name, mutableListOf(), attrs)
    }

    /**
     * Converts a typed value (data type + data) into its display string, using the string pool
     * for TYPE_STRING and raw-value references.
     */
    private fun resolveAttributeValue(
        type: Int, data: Int, rawValueIndex: Int, strings: List<String>
    ): String {
        // The low 8 bits of `type` are the actual data type.
        return when (type and 0xFF) {
            TYPE_STRING -> strings.getOrNull(rawValueIndex) ?: ""
            TYPE_NULL -> ""
            TYPE_REFERENCE -> "@${if (data < 0) "android:" else ""}${data and 0x0FFFFFFF}"
            TYPE_ATTRIBUTE -> "?${data and 0x0FFFFFFF}"
            TYPE_INT_DEC -> data.toString()
            TYPE_INT_HEX -> "0x${data.toLong().and(0xFFFFFFFFL).toString(16)}"
            TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
            TYPE_FLOAT -> Float.fromBits(data).toString()
            else -> "0x${data.toLong().and(0xFFFFFFFFL).toString(16)} (type ${type and 0xFF})"
        }
    }
}

/**
 * DOM node for a parsed binary XML element.
 */
sealed class BinaryXmlNode {
    /** An XML element with optional children and attributes. */
    class Element(
        val name: String,
        val children: MutableList<BinaryXmlNode>,
        val attributes: List<BinaryXmlAttribute> = emptyList()
    ) : BinaryXmlNode()

    /** A text/CDATA node. */
    class Text(val text: String) : BinaryXmlNode()
}

/** A single attribute on a [BinaryXmlNode.Element]. */
data class BinaryXmlAttribute(
    val name: String,
    val namespace: String?,
    val value: String
)
