package lab.doppio.bloom.data.sources.discovery

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lab.doppio.bloom.data.sources.http.sourcesJson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared by the discovery sources: dates and amounts as the facts print them, and the open data records endpoint. */

internal val frDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
internal val paris: ZoneId = ZoneId.of("Europe/Paris")
internal fun day(iso: String): String = runCatching { LocalDate.parse(iso.take(10)).format(frDate) }.getOrDefault(iso)
internal fun startOfDay(iso: String, fallback: Instant): Instant =
    runCatching { LocalDate.parse(iso.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrDefault(fallback)
/** Groups thousands with a plain space whatever the JVM locale (a French JVM would use a narrow no-break space). */
internal fun euros(amount: Long): String = "%,d".format(Locale.ROOT, amount).replace(',', ' ') + " €"

internal suspend fun HttpClient.records(url: String, where: String, limit: Int, offset: Int = 0, orderBy: String = "dateparution desc"): List<JsonObject> {
    val res = get(url) {
        parameter("where", where)
        parameter("order_by", orderBy)
        parameter("limit", limit)
        parameter("offset", offset)
    }
    if (!res.status.isSuccess()) error("HTTP ${res.status.value} sur $url")
    val body = sourcesJson.parseToJsonElement(res.bodyAsText()).jsonObject
    return (body["results"] as? JsonArray).orEmpty().map { it.jsonObject }
}
