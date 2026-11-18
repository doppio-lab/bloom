package lab.doppio.bloom.core.model

/**
 * One email address, as a recipient: never a list to expand nor a header to inject. A non-blank value that is not a
 * single plain address is refused, so `a@x.fr, b@y.fr` cannot send to two people and escape the suppression list.
 */
object EmailAddress {
    private val local = Regex("""[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*""")
    private val domain = Regex("""([A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?""")

    /** The address trimmed and lowercased, or null when [raw] is not exactly one plain address. */
    fun normalize(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.count { it == '@' } != 1) return null
        val (user, host) = s.split('@')
        if (!local.matches(user) || !domain.matches(host)) return null
        return s.lowercase()
    }

    /** What the dashboard shows when an address is refused. */
    fun invalidMessage(raw: String) = "Adresse email invalide : « ${raw.trim()} »"

    /** Null for a blank value (no email known), the normalized address for a valid one, [IllegalArgumentException] for anything else. */
    fun orNull(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return normalize(raw) ?: throw IllegalArgumentException(invalidMessage(raw))
    }

    /** A subject fits on one header line: a line break would start another header. */
    fun singleLine(subject: String): String = subject.replace(Regex("[\r\n]+"), " ")
}
