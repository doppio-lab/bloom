package lab.doppio.bloom.app.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * Local development convenience: a run from the IDE or from `./gradlew :app:run` gets the variables of the
 * project's `.env` without `set -a; source .env`. Real environment variables always win over the file.
 *
 * Production is not affected: `.env` is excluded from the Docker image and Compose passes the variables
 * itself, so there is no file to find there.
 */
object DotEnv {
    const val FILE_NAME = ".env"

    /** The environment to configure the server with: the file's variables under the real ones. */
    fun merged(env: Map<String, String>, file: Path?): Map<String, String> =
        if (file == null) env else parse(Files.readAllLines(file)) + env

    /**
     * Looks for `.env` from [start] upwards, stopping at the Gradle root (the directory holding
     * `settings.gradle.kts`): the IDE may start the server from the project root or from `app/`. Outside a
     * Gradle checkout only [start] itself is checked, so a `.env` higher up is never picked by accident.
     */
    fun find(start: Path): Path? {
        val dirs = generateSequence(start.toAbsolutePath().normalize()) { it.parent }.toList()
        val root = dirs.indexOfFirst { Files.exists(it.resolve("settings.gradle.kts")) }
        val candidates = if (root < 0) dirs.take(1) else dirs.take(root + 1)
        return candidates.map { it.resolve(FILE_NAME) }.firstOrNull { Files.isRegularFile(it) }
    }

    private val line = Regex("""^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$""")

    /**
     * Reads the subset of the dotenv format the project uses, as `source` and Compose read it: `KEY=value`,
     * optional `export`, comment and blank lines, values in double or single quotes (kept verbatim, so they
     * may hold spaces, `#` and `:`), and a ` #` comment after an unquoted value.
     */
    fun parse(lines: List<String>): Map<String, String> = buildMap {
        for (raw in lines) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val (key, rest) = line.matchEntire(raw)?.destructured ?: continue
            put(key, value(rest.trim()))
        }
    }

    private fun value(rest: String): String {
        val quote = rest.firstOrNull()
        if (quote == '"' || quote == '\'') {
            val end = rest.indexOf(quote, startIndex = 1)
            if (end > 0) return rest.substring(1, end)
        }
        val comment = Regex("""\s#""").find(rest)?.range?.first
        return (if (comment == null) rest else rest.substring(0, comment)).trim()
    }
}
