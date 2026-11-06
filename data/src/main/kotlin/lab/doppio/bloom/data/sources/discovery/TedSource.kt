package lab.doppio.bloom.data.sources.discovery

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import lab.doppio.bloom.core.discovery.Boamp
import lab.doppio.bloom.core.discovery.Ted
import lab.doppio.bloom.data.discovery.DiscoverySource
import lab.doppio.bloom.data.discovery.Lead
import lab.doppio.bloom.data.sources.http.sourcesJson
import java.time.Instant

/** TED : marchés publics européens passés en France (API de recherche ouverte, sans clé). */
class TedSource(private val http: HttpClient, private val lookbackDays: Long = 60) : DiscoverySource {
    override val key = "ted"
    override val label = "marché européen"

    override suspend fun leads(now: Instant): List<Lead> {
        val since = now.atZone(paris).toLocalDate().minusDays(lookbackDays)
        val res = http.post("https://api.ted.europa.eu/v3/notices/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", Ted.query(since))
                put("fields", JsonArray(Ted.fields.map { JsonPrimitive(it) }))
                put("limit", 100)
                put("scope", "ACTIVE")
                put("paginationMode", "PAGE_NUMBER")
                put("page", 1)
            })
        }
        if (!res.status.isSuccess()) error("TED HTTP ${res.status.value}")
        val notices = (sourcesJson.parseToJsonElement(res.bodyAsText()).jsonObject["notices"] as? JsonArray).orEmpty()
        // The search is full text: only the notices whose subject asks for a mobile app are kept
        return notices.mapNotNull { Ted.parse(it.jsonObject) }.filter { Boamp.isOpenMobileTender(it, now) }.map { tenderLead(it, "ted") }
    }
}
