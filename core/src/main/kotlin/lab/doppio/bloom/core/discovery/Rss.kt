package lab.doppio.bloom.core.discovery

import java.io.StringReader
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class RssItem(val title: String, val link: String, val publishedAt: Instant?, val description: String?, val guid: String?)

object Rss {
    fun items(xml: String): List<RssItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The feed is untrusted input: no DTD, no external entity
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i ->
            val item = items.item(i) as? Element ?: return@mapNotNull null
            fun child(tag: String) = item.getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            RssItem(
                title = child("title") ?: return@mapNotNull null,
                link = child("link") ?: return@mapNotNull null,
                publishedAt = child("pubDate")?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() },
                description = child("description"),
                guid = child("guid"),
            )
        }
    }
}
