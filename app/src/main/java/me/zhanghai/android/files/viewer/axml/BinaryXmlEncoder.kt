/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.axml

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes a [BinaryXmlNode] tree back into Android's binary XML (AXML) format — the inverse of
 * [BinaryXmlParser]. Used by the AXML editor to write edited XML back into the APK.
 *
 * The encoding process:
 *  1. **Collect strings**: walk the tree, gather all element names, attribute names, and string
 *     attribute values into a deduplicated string pool (sorted for deterministic output).
 *  2. **Build the string pool chunk**: UTF-16 encoded, with per-string offset table.
 *  3. **Build the resource map chunk**: one uint32 per string, all zeros (we don't track resource
 *     IDs; the Android verifier tolerates a zeroed map).
 *  4. **Emit tag chunks**: depth-first walk producing START/END_ELEMENT pairs. Each START includes
 *     the namespace index (-1 = none), name index, and attribute array. Attribute values are
 *     encoded as typed values, with the data type inferred from the string representation.
 *
 * Attribute type inference (the trickiest part):
 *   - `"true"` / `"false"` → TYPE_INT_BOOLEAN
 *   - `"@..."` → TYPE_REFERENCE
 *   - `"0x..."` → TYPE_INT_HEX
 *   - all-digits → TYPE_INT_DEC
 *   - everything else → TYPE_STRING (value looked up from the string pool)
 *
 * @see <a href="https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h">ResourceTypes.h</a>
 */
internal object BinaryXmlEncoder {

    // Chunk types (must match BinaryXmlParser constants)
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103

    // Typed value data types
    private const val TYPE_NULL = 0x00
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    // Typed value: uint8 size | uint8 res0 | uint8 dataType | uint8 (padding) | uint32 data
    private const val TYPED_VALUE_SIZE = 8

    /**
     * Encodes [root] into binary AXML bytes.
     */
    fun encode(root: BinaryXmlNode): ByteArray {
        // Phase 1: collect all strings (element names, attr names, attr values that are strings).
        val stringList = mutableListOf<String>()
        val stringIndexMap = mutableMapOf<String, Int>()
        fun intern(s: String): Int = stringIndexMap.getOrPut(s) {
            stringList.add(s); stringList.size - 1
        }

        collectStrings(root, ::intern)

        // Phase 2: build the string pool.
        val stringPool = buildStringPool(stringList)

        // Phase 3: build the resource map (zeros).
        val resourceMap = buildResourceMap(stringList.size)

        // Phase 4: emit the tag chunks.
        val tagsOut = ByteArrayOutputStream()
        emitTags(root, tagsOut, ::intern)
        val tagBytes = tagsOut.toByteArray()

        // Assemble the file: header + string pool + resource map + tags.
        val totalSize = 8 + stringPool.size + resourceMap.size + tagBytes.size
        val out = ByteArrayOutputStream(totalSize)
        // File header
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(RES_XML_TYPE.toShort())
        header.putShort(8) // headerSize
        header.putInt(totalSize)
        out.write(header.array())
        out.write(stringPool)
        out.write(resourceMap)
        out.write(tagBytes)
        return out.toByteArray()
    }

    // -----------------------------------------------------------------------------------
    //  String collection
    // -----------------------------------------------------------------------------------

    private fun collectStrings(
        node: BinaryXmlNode, intern: (String) -> Int
    ) {
        when (node) {
            is BinaryXmlNode.Element -> {
                intern(node.name)
                for (attr in node.attributes) {
                    intern(attr.name)
                    // Only intern the value as a string if it's not a recognized typed value
                    // (bool/int/reference). Typed values are encoded inline, not via the pool.
                    if (inferType(attr.value) == TYPE_STRING) {
                        intern(attr.value)
                    }
                }
                for (child in node.children) {
                    collectStrings(child, intern)
                }
            }
            is BinaryXmlNode.Text -> {
                if (node.text.isNotBlank()) intern(node.text)
            }
        }
    }

    // -----------------------------------------------------------------------------------
    //  String pool
    // -----------------------------------------------------------------------------------

    private fun buildStringPool(strings: List<String>): ByteArray {
        // Layout: header(28) + offsetTable[stringCount] + stringData + (styleData = empty)
        val stringsUtf16 = strings.map { it.toCharArray() }
        val stringDataStart = 28 + strings.size * 4

        val offsets = IntArray(strings.size)
        var currentOffset = 0
        // First compute offsets by simulating the write.
        for (i in stringsUtf16.indices) {
            offsets[i] = currentOffset
            currentOffset += 2 + stringsUtf16[i].size * 2 + 2 // len(uint16) + chars + NUL
        }
        val stringDataSize = currentOffset
        val chunkSize = stringDataStart + stringDataSize

        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        // Header
        buf.putShort(RES_STRING_POOL_TYPE.toShort())
        buf.putShort(28) // headerSize
        buf.putInt(chunkSize)
        buf.putInt(strings.size) // stringCount
        buf.putInt(0) // styleCount
        buf.putInt(0) // flags: UTF-16 (no flag set)
        buf.putInt(stringDataStart) // stringsStart
        buf.putInt(0) // stylesStart (none)
        // Offset table
        for (offset in offsets) buf.putInt(offset)
        // String data (UTF-16: uint16 length + chars + uint16 NUL)
        for (chars in stringsUtf16) {
            buf.putShort(chars.size.toShort())
            for (c in chars) buf.putShort(c.code.toShort())
            buf.putShort(0) // NUL terminator
        }
        return buf.array()
    }

    // -----------------------------------------------------------------------------------
    //  Resource map
    // -----------------------------------------------------------------------------------

    private fun buildResourceMap(stringCount: Int): ByteArray {
        if (stringCount == 0) return ByteArray(0)
        val chunkSize = 8 + stringCount * 4
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_XML_RESOURCE_MAP_TYPE.toShort())
        buf.putShort(8) // headerSize
        buf.putInt(chunkSize)
        // All zeros — we don't track resource IDs.
        for (i in 0 until stringCount) buf.putInt(0)
        return buf.array()
    }

    // -----------------------------------------------------------------------------------
    //  Tag chunk emission
    // -----------------------------------------------------------------------------------

    private fun emitTags(
        node: BinaryXmlNode, out: ByteArrayOutputStream, intern: (String) -> Int
    ) {
        if (node !is BinaryXmlNode.Element) return
        val nameIndex = intern(node.name)

        // START_ELEMENT chunk
        val attrCount = node.attributes.size
        val attrStart = 16 // after the common START_ELEMENT fields
        val attrsSize = attrCount * 20
        val startChunkSize = 36 + attrsSize // header(8) + ext(28) + attrs
        val startBuf = ByteBuffer.allocate(startChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        startBuf.putShort(RES_XML_START_ELEMENT_TYPE.toShort())
        startBuf.putShort(36) // headerSize (includes the extension fields)
        startBuf.putInt(startChunkSize)
        startBuf.putInt(0) // lineNumber
        startBuf.putInt(-1) // comment (none)
        startBuf.putInt(-1) // namespace (none)
        startBuf.putInt(nameIndex)
        startBuf.putShort(attrStart.toShort()) // attributeStart
        startBuf.putShort(20) // attributeSize
        startBuf.putShort(attrCount.toShort())
        startBuf.putShort(0) // idIndex
        startBuf.putShort(0) // classIndex
        startBuf.putShort(0) // styleIndex
        // Attributes (each 20 bytes)
        for (attr in node.attributes) {
            val attrNameIndex = intern(attr.name)
            val type = inferType(attr.value)
            val (data, rawValueIndex) = encodeAttributeValue(attr.value, type, intern)
            startBuf.putInt(-1) // namespace (none)
            startBuf.putInt(attrNameIndex)
            startBuf.putInt(rawValueIndex) // rawValue (string index, or -1 if typed)
            startBuf.put(TYPED_VALUE_SIZE.toByte()) // size
            startBuf.put(0) // res0
            startBuf.putShort(type.toShort())
            startBuf.putInt(data)
        }
        out.write(startBuf.array())

        // Children
        for (child in node.children) {
            emitTags(child, out, intern)
        }

        // END_ELEMENT chunk (16 bytes)
        val endBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        endBuf.putShort(RES_XML_END_ELEMENT_TYPE.toShort())
        endBuf.putShort(16) // headerSize
        endBuf.putInt(16) // chunkSize
        endBuf.putInt(0) // lineNumber
        endBuf.putInt(-1) // comment
        endBuf.putInt(-1) // namespace
        endBuf.putInt(nameIndex)
        out.write(endBuf.array())
    }

    // -----------------------------------------------------------------------------------
    //  Attribute value encoding + type inference
    // -----------------------------------------------------------------------------------

    private fun encodeAttributeValue(
        value: String, type: Int, intern: (String) -> Int
    ): Pair<Int, Int> {
        // Returns (data, rawValueIndex). rawValueIndex is the string pool index for TYPE_STRING,
        // or -1 for typed values (no raw string stored).
        return when (type) {
            TYPE_STRING -> {
                val idx = intern(value)
                idx to idx
            }
            TYPE_INT_BOOLEAN -> (if (value == "true") 1 else 0) to -1
            TYPE_INT_DEC -> (value.toIntOrNull() ?: 0) to -1
            TYPE_INT_HEX -> (value.removePrefix("0x").toIntOrNull(16) ?: 0) to -1
            TYPE_REFERENCE -> {
                val refStr = value.removePrefix("@").removePrefix("android:")
                val ref = refStr.removePrefix("0x").toIntOrNull(16)
                    ?: refStr.toIntOrNull() ?: 0
                ref to -1
            }
            else -> 0 to -1
        }
    }

    private fun inferType(value: String): Int = when {
        value == "true" || value == "false" -> TYPE_INT_BOOLEAN
        value.startsWith("0x") && value.length > 2 &&
            value.substring(2).all { it in "0123456789abcdefABCDEF" } -> TYPE_INT_HEX
        value.startsWith("@") -> TYPE_REFERENCE
        value.isNotEmpty() && value.all { it.isDigit() } &&
            value.toIntOrNull() != null -> TYPE_INT_DEC
        value.isNotEmpty() && value.startsWith("-") &&
            value.substring(1).all { it.isDigit() } &&
            value.toIntOrNull() != null -> TYPE_INT_DEC
        else -> TYPE_STRING
    }
}
