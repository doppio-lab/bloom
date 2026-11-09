package lab.doppio.bloom.core.discovery

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class Officer(val fullName: String, val role: String)

object Registry {
    /** People who run the company. Board members, supervisory boards and auditors are listed too, and are left out. */
    private val executive = Regex("(?i)pr[ée]sident|directeur g[ée]n[ée]ral|g[ée]rant|dirigeant")
    private val notExecutive = Regex("(?i)commissaire|conseil de surveillance")

    /**
     * Natural persons who run the company, from a record of the company registry. The date of birth the
     * registry publishes is not kept: it is not needed to write to someone.
     */
    fun officers(company: JsonObject): List<Officer> = (company["dirigeants"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { d ->
        if (d["type_dirigeant"].text() != "personne physique") return@mapNotNull null
        val role = d["qualite"].text()?.takeIf { executive.containsMatchIn(it) && !notExecutive.containsMatchIn(it) } ?: return@mapNotNull null
        val first = d["prenoms"].text()?.split(' ', ',')?.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
        // "SOUAL (DELEVALLEE)": the name in brackets is a birth name, the first one is the name in use
        val last = d["nom"].text()?.substringBefore('(')?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Officer("${capitalise(first)} ${capitalise(last)}", role)
    }.distinct()

    /** The registry writes names in capitals: "JEAN-PIERRE" becomes "Jean-Pierre". */
    private fun capitalise(name: String): String =
        name.lowercase().split(' ').joinToString(" ") { word -> word.split('-').joinToString("-") { it.replaceFirstChar(Char::uppercase) } }
}
