package com.buwrt.dingclock.engine

import android.graphics.Rect
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

data class UiNode(
    val text: String,
    val desc: String,
    val className: String,
    val packageName: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val bounds: Rect
) {
    val label: String get() = text.ifBlank { desc }.trim()
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
    val area: Long get() = bounds.width().toLong() * bounds.height().toLong()
}

object UiParser {
    private val boundsRegex = Regex("""\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")

    fun parse(xml: String): List<UiNode> {
        val nodes = ArrayList<UiNode>(160)
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "node") {
                    val bounds = parseBounds(parser.getAttributeValue(null, "bounds"))
                    if (bounds != null) {
                        nodes += UiNode(
                            text = parser.getAttributeValue(null, "text") ?: "",
                            desc = parser.getAttributeValue(null, "content-desc") ?: "",
                            className = parser.getAttributeValue(null, "class") ?: "",
                            packageName = parser.getAttributeValue(null, "package") ?: "",
                            clickable = parser.getAttributeValue(null, "clickable") == "true",
                            enabled = parser.getAttributeValue(null, "enabled") != "false",
                            bounds = bounds
                        )
                    }
                }
                event = parser.next()
            }
        } catch (_: Throwable) {
            // 截断或非 XML 输出时返回已解析部分
        }
        return nodes
    }

    private fun parseBounds(raw: String?): Rect? {
        if (raw == null) return null
        val match = boundsRegex.find(raw) ?: return null
        val (x1, y1, x2, y2) = match.destructured
        return runCatching { Rect(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt()) }.getOrNull()
    }
}
