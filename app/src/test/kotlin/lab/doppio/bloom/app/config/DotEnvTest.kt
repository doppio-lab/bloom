package lab.doppio.bloom.app.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DotEnvTest {
    @Test
    fun `parses the format of the env example`() {
        val parsed = DotEnv.parse(
            """
            # LLM
            LLM_PROVIDER=ollama

            export PORT=8080
            DRY_RUN=true # never send in local
            MAILBOXES="prenom@doppio-studio.fr:xxxx xxxx xxxx xxxx:Prénom Nom"
            SENDER_NAME='Prénom, cofondateur # de Doppio Lab'
            EMPTY=
              SPACED = value with spaces
            URL=http://localhost:8080/#anchor
            not a variable line
            """.trimIndent().lines(),
        )
        assertEquals(
            mapOf(
                "LLM_PROVIDER" to "ollama",
                "PORT" to "8080",
                "DRY_RUN" to "true",
                "MAILBOXES" to "prenom@doppio-studio.fr:xxxx xxxx xxxx xxxx:Prénom Nom",
                "SENDER_NAME" to "Prénom, cofondateur # de Doppio Lab",
                "EMPTY" to "",
                "SPACED" to "value with spaces",
                "URL" to "http://localhost:8080/#anchor",
            ),
            parsed,
        )
    }

    @Test
    fun `real environment variables win over the file`() {
        val dir = Files.createTempDirectory("dotenv")
        val file = dir.resolve(".env").apply { writeText("ADMIN_PASSWORD=from-the-file-0000\nDRY_RUN=true\n") }
        val merged = DotEnv.merged(mapOf("DRY_RUN" to "false"), file)
        assertEquals("from-the-file-0000", merged["ADMIN_PASSWORD"])
        assertEquals("false", merged["DRY_RUN"])
        assertEquals(mapOf("A" to "1"), DotEnv.merged(mapOf("A" to "1"), null))
    }

    @Test
    fun `finds the env file of the Gradle root from a module directory`() {
        val root = Files.createTempDirectory("bloom")
        root.resolve("settings.gradle.kts").writeText("")
        root.resolve(".env").writeText("A=1")
        val app = root.resolve("app").createDirectories()
        assertEquals(root.resolve(".env"), DotEnv.find(app))
        assertEquals(root.resolve(".env"), DotEnv.find(root))
    }

    @Test
    fun `never looks above the Gradle root or above the start outside a checkout`() {
        val outer = Files.createTempDirectory("outer")
        outer.resolve(".env").writeText("A=1")
        val root = outer.resolve("bloom").createDirectories()
        root.resolve("settings.gradle.kts").writeText("")
        assertNull(DotEnv.find(root.resolve("app").createDirectories()))

        val notACheckout: Path = outer.resolve("image").createDirectories()
        assertNull(DotEnv.find(notACheckout))
    }
}
