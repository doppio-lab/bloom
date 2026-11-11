package lab.doppio.bloom.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Mechanical checks for the rules in AGENTS.md that an agent would otherwise have to remember. They read files
 * only (no server, no database) so they run with `./gradlew :app:test` in a couple of seconds.
 *
 * - Design: the interface only uses Thermos tokens. Colours live in the `:root` and `.dark` blocks of index.html;
 *   anywhere else a colour literal or a font name means someone added a token the design does not have.
 * - Claims: sentences that marketing/claims-audit.md marks False must not come back in the interface or the README.
 *   To add one, put its exact words in [falseClaims]. To lift one after the claim became true, delete it here.
 */
class ProjectRulesTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val indexHtml: String =
        checkNotNull(javaClass.classLoader.getResource("static/index.html")) { "static/index.html missing" }.readText()

    /** The CSS of index.html without the token blocks (`:root { ... }` and `.dark { ... }`, closed by a `}` at column 0). */
    private val cssOutsideTokens: String = Regex("(?s)<style>(.*?)</style>").find(indexHtml)!!.groupValues[1]
        .replace(Regex("(?s)(?m)^(:root|\\.dark)\\s*\\{.*?^\\}"), "")

    @Test
    fun `index html has no colour literal outside the token blocks except white and black overlays`() {
        val literals = Regex("#[0-9a-fA-F]{3,8}\\b|\\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch)\\(")
            .findAll(cssOutsideTokens).map { it.value }
            .filterNot { it.equals("#fff", true) || it.equals("#ffffff", true) }
            .toList()
        // The only function literal allowed is the scrim: rgb(0 0 0 / alpha), black at some opacity.
        val unexpected = literals.filter { it != "rgb(" } +
            Regex("rgba?\\((?!0[ ,]+0[ ,]+0)").findAll(cssOutsideTokens).map { it.value }
        assertTrue(unexpected.isEmpty(), "Colour literals outside the token blocks: $unexpected. Use a Thermos token (AGENTS.md, rule 4).")
    }

    @Test
    fun `index html only sets fonts through the font tokens`() {
        val literal = Regex("font-family\\s*:\\s*+(?!var\\()[^;}]+").findAll(cssOutsideTokens).map { it.value.trim() }.toList()
        assertTrue(literal.isEmpty(), "font-family must be var(--font-sans) or var(--font-mono), found: $literal")
    }

    private val falseClaims = listOf(
        "chaque affirmation est reliée à un fait sourcé", // C3: the guard checks markers, not every sentence
        "pour votre équipe", // C21: Bloom is not configurable by another team
        "ne quittent jamais votre machine", // C22: SMTP, Dropcontact and the default LLM provider still see data
        "12 catégories", // C24: the code reads 21 genres
    )

    @Test
    fun `interface and README do not repeat claims the audit marks False`() {
        val texts = mapOf("index.html" to indexHtml, "README.md" to File(root, "README.md").readText())
        val found = texts.flatMap { (name, text) -> falseClaims.filter { text.contains(it, ignoreCase = true) }.map { "$name: \"$it\"" } }
        if (found.isNotEmpty()) fail("Claims marked False in marketing/claims-audit.md: $found")
    }
}
