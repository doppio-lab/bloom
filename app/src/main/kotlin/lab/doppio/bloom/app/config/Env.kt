package lab.doppio.bloom.app.config

/** Values copied from .env.example that must never reach a running server. */
private val placeholders = setOf("change-me", "une-longue-chaine-aleatoire")

private const val MIN_SECRET_LENGTH = 16

/**
 * A boolean flag such as DRY_RUN. Only "true" and "false" are accepted: "yes", "1" or a trailing space
 * must stop the server rather than silently switch to real sending.
 */
internal fun parseFlag(name: String, value: String?, default: Boolean): Boolean = when (value) {
    null, "" -> default
    "true" -> true
    "false" -> false
    else -> error("$name doit valoir true ou false, pas « $value »")
}

/** A required secret: present, not blank, not an example value, and long enough to resist guessing. */
internal fun requireSecret(name: String, value: String?): String {
    require(!value.isNullOrBlank()) { "Variable d'environnement manquante : $name" }
    require(value !in placeholders) { "$name a encore sa valeur d'exemple : la remplacer, par exemple par openssl rand -hex 32" }
    require(value.length >= MIN_SECRET_LENGTH) { "$name doit faire au moins $MIN_SECRET_LENGTH caractères" }
    return value
}
