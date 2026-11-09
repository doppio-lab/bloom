package lab.doppio.bloom.core.discovery

import java.time.Instant

data class CodeurProject(
    val id: String,
    val title: String,
    val url: String,
    val publishedAt: Instant?,
    /** Budget band as written by Codeur ("1 000 € à 10 000 €"). */
    val budget: String?,
    val categories: List<String>,
)

object Codeur {
    private val header = Regex("Budget\\s*:\\s*(.+?)\\s+-\\s+Cat[ée]gories?\\s*:\\s*(.+?)\\s*</p>", RegexOption.IGNORE_CASE)
    private val mobile = Regex("(?i)appli(cation)?s?\\s+(mobiles?|ios|android|smartphone)|\\bapp\\s+mobile|\\b(ios|android|swift|kotlin|flutter|react native)\\b")
    private val amount = Regex("(\\d[\\d\\s\\u00a0\\u202f]*)\\s*€")

    /** The feed gives the budget band and the categories in the first paragraph of the description. */
    fun parseFeed(xml: String): List<CodeurProject> = Rss.items(xml).map { item ->
        val head = item.description?.let { header.find(it) }
        CodeurProject(
            id = item.guid ?: item.link.substringAfterLast('/').substringBefore('-'),
            title = item.title,
            url = item.link,
            publishedAt = item.publishedAt,
            budget = head?.groupValues?.get(1)?.trim(),
            categories = head?.groupValues?.get(2)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        )
    }

    /** Lowest amount of the budget band, in euros: "10 000 € et plus" gives 10000, "Moins de 500 €" gives 0. */
    fun budgetFloor(budget: String?): Int? {
        if (budget == null) return null
        if (budget.contains("moins de", ignoreCase = true)) return 0
        return amount.find(budget)?.groupValues?.get(1)?.filter(Char::isDigit)?.toIntOrNull()
    }

    /** A project that asks for a mobile app, judged on its title and categories, with a budget worth an answer. */
    fun isMobileProject(project: CodeurProject, minBudgetEur: Int): Boolean =
        mobile.containsMatchIn(project.title + " " + project.categories.joinToString(" ")) && (budgetFloor(project.budget) ?: 0) >= minBudgetEur
}
