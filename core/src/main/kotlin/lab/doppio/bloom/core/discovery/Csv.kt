package lab.doppio.bloom.core.discovery

/** Lecture CSV tolérante : séparateur virgule ou point-virgule (Excel français), guillemets, en-tête obligatoire. */
object Csv {
    fun parse(text: String): List<Map<String, String>> {
        val clean = text.removePrefix("﻿")
        val firstLine = clean.lineSequence().firstOrNull().orEmpty()
        val sep = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        var field = StringBuilder()
        var row = mutableListOf<String>()
        var quoted = false
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            when {
                quoted && c == '"' && i + 1 < clean.length && clean[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { row += field.toString().trim(); field = StringBuilder() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < clean.length && clean[i + 1] == '\n') i++
                    row += field.toString().trim(); field = StringBuilder()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        row += field.toString().trim()
        if (row.any { it.isNotEmpty() }) rows += row
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.lowercase().trim() }
        return rows.drop(1).map { r -> header.mapIndexed { idx, h -> h to r.getOrElse(idx) { "" } }.toMap() }
    }
}
