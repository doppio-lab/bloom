package lab.doppio.bloom.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Recherche de l'email professionnel d'une personne dont on connaît le nom et l'entreprise.
 * API Dropcontact v1 (developer.dropcontact.com) : POST /enrich/all puis GET /enrich/all/{request_id}.
 * Optionnel et payant : sans clé, le contact est ajouté sans email et reste une tâche LinkedIn.
 * On ne garde qu'un email qualifié « nominative@pro » : pas d'adresse générique, pas d'adresse perso.
 */
class DropcontactSource(private val http: HttpClient, private val apiKey: String) {
    private val base = "https://api.dropcontact.com/v1"

    data class Result(val email: String, val qualification: String)

    suspend fun findEmail(fullName: String, company: String, website: String?): Result? {
        val body = buildJsonObject {
            put("data", buildJsonArray {
                add(buildJsonObject {
                    put("full_name", fullName)
                    put("company", company)
                    website?.let { put("website", it) }
                })
            })
            put("siren", false)
            put("language", "fr")
        }
        val submit = sourcesJson.parseToJsonElement(
            http.post("$base/enrich/all") {
                header("X-Access-Token", apiKey)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.bodyAsText(),
        ).jsonObject
        val requestId = submit["request_id"]?.jsonPrimitive?.contentOrNull ?: return null

        // Le traitement prend de quelques secondes à quelques minutes : 8 tentatives espacées de 20 secondes.
        repeat(8) {
            delay(20_000)
            val res = sourcesJson.parseToJsonElement(
                http.get("$base/enrich/all/$requestId") { header("X-Access-Token", apiKey) }.bodyAsText(),
            ).jsonObject
            if (res["success"]?.jsonPrimitive?.booleanOrNull == true) {
                val contact = res["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
                return bestEmail(contact)
            }
        }
        return null
    }

    private fun bestEmail(contact: JsonObject): Result? {
        val emails = contact["email"] as? kotlinx.serialization.json.JsonArray ?: return null
        return emails.map { it.jsonObject }
            .mapNotNull { e ->
                val email = e["email"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val q = e["qualification"]?.jsonPrimitive?.contentOrNull.orEmpty()
                Result(email, q)
            }
            .firstOrNull { it.qualification.startsWith("nominative@pro") }
    }
}
