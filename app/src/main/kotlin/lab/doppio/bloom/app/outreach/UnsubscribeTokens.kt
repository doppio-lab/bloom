package lab.doppio.bloom.app.outreach

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Jetons de désinscription signés : pas de base à consulter pour vérifier un lien. */
class UnsubscribeTokens(private val secret: String) {
    private fun mac(data: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }.take(TOKEN_CHARS)
    }

    private companion object {
        /** Hex characters of the signature kept in the link: 80 bits, plenty against guessing and short in a URL. */
        const val TOKEN_CHARS = 20
    }

    fun tokenFor(contactId: Long): String = "$contactId.${mac(contactId.toString())}"

    fun verify(token: String): Long? {
        val id = token.substringBefore('.').toLongOrNull() ?: return null
        return id.takeIf { tokenFor(id) == token }
    }
}
