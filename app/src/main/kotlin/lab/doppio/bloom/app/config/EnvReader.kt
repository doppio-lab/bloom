package lab.doppio.bloom.app.config

/** Reads variables from a map. An empty value for a defaulted variable is kept as is, like System.getenv. */
internal class EnvReader(private val env: Map<String, String>) {
    /** The value, else [default], else an error naming the variable. */
    fun value(name: String, default: String? = null): String =
        env[name] ?: default ?: error("Variable d'environnement manquante : $name")

    /** A strictly positive integer: a malformed number throws NumberFormatException, zero or less is a configuration error. */
    fun positiveInt(name: String, default: Int): Int =
        value(name, default.toString()).toInt().also { check(it > 0) { "$name doit être un entier strictement positif : $it" } }

    fun positiveLong(name: String, default: Long): Long =
        value(name, default.toString()).toLong().also { check(it > 0) { "$name doit être un entier strictement positif : $it" } }

    /** An empty value in .env (KEY=) counts as an absent key. */
    fun optional(name: String): String? = env[name]?.takeIf { it.isNotBlank() }
}
