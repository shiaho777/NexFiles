/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.axml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parses human-readable XML text (as edited by the user or produced by [BinaryXmlPrinter]) back
 * into a [BinaryXmlNode] tree. This is the front-end of the AXML round-trip: text XML → tree →
 * [BinaryXmlEncoder] → binary AXML.
 *
 * Uses the platform's [XmlPullParser] (via [XmlPullParserFactory]) rather than a hand-rolled
 * parser, so we get correct XML entity handling and error reporting for free.
 *
 * Namespace prefixes are stripped during parsing — the binary AXML format stores attributes
 * without namespace prefixes in most cases, and we re-add the `android:` convention in the
 * encoder's string pool only where the attribute name itself contains it.
 */
internal object TextXmlParser {

    /**
     * Parses [xml] into a [BinaryXmlNode] tree. Throws on malformed XML.
     */
    fun parse(xml: String): BinaryXmlNode {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val rootStack = ArrayDeque<BinaryXmlNode.Element>()
        var root: BinaryXmlNode.Element? = null
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    val attrs = mutableListOf<BinaryXmlAttribute>()
                    for (i in 0 until parser.attributeCount) {
                        // attributeValue(i) returns the resolved value string; ignore namespace.
                        attrs.add(BinaryXmlAttribute(parser.getAttributeName(i), null, parser.getAttributeValue(i)))
                    }
                    val element = BinaryXmlNode.Element(name, mutableListOf(), attrs)
                    if (rootStack.isEmpty()) {
                        root = element
                    } else {
                        rootStack.last().children.add(element)
                    }
                    rootStack.addLast(element)
                }
                XmlPullParser.END_TAG -> {
                    if (rootStack.isNotEmpty()) rootStack.removeLast()
                }
            }
            eventType = parser.next()
        }
        return root ?: throw IllegalStateException("XML has no root element")
    }
}
