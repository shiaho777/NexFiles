/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.axml

/**
 * Renders a [BinaryXmlNode] tree back to human-readable, indented XML — the reverse of what
 * `aapt` did when it compiled the resources. The output mirrors what `apktool d` produces for
 * AndroidManifest.xml.
 *
 * Namespace prefixes on attributes are stripped for readability (AXML commonly has
 * `http://schemas.android.com/apk/res/android` on every `android:*` attr); the bare attribute
 * name is usually enough to understand the manifest.
 */
internal object BinaryXmlPrinter {

    private const val INDENT = "    "

    fun print(node: BinaryXmlNode): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        printNode(node, sb, 0)
        return sb.toString()
    }

    private fun printNode(node: BinaryXmlNode, sb: StringBuilder, depth: Int) {
        val indent = INDENT.repeat(depth)
        when (node) {
            is BinaryXmlNode.Element -> printElement(node, sb, depth, indent)
            is BinaryXmlNode.Text -> {
                val trimmed = node.text.trim()
                if (trimmed.isNotEmpty()) sb.append(indent).append(escape(trimmed)).append('\n')
            }
        }
    }

    private fun printElement(
        element: BinaryXmlNode.Element, sb: StringBuilder, depth: Int, indent: String
    ) {
        sb.append(indent).append('<').append(element.name)
        // Attributes, one per line if any are present (manifests get unwieldy on a single line).
        for (attr in element.attributes) {
            sb.append('\n').append(indent).append(INDENT)
                .append(attr.name).append("=\"").append(escapeAttr(attr.value)).append('"')
        }
        if (element.children.isEmpty()) {
            sb.append(" />\n")
            return
        }
        sb.append(">\n")
        for (child in element.children) {
            printNode(child, sb, depth + 1)
        }
        sb.append(indent).append("</").append(element.name).append(">\n")
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttr(text: String): String =
        escape(text).replace("\"", "&quot;").replace("\n", "&#10;")
}
