package lab.doppio.bloom.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyTextTest {
    @Test
    fun `an explicit opt-out is a hard stop`() {
        assertTrue(ReplyText.isHardStop("Merci de me désinscrire"))
        assertTrue(ReplyText.isHardStop("Please unsubscribe me"))
        assertTrue(ReplyText.isHardStop("Je souhaite ne plus recevoir vos emails"))
        assertTrue(ReplyText.isHardStop("Merci de ne plus m'écrire"))
        assertTrue(ReplyText.isHardStop("Retirez-moi de votre liste"))
    }

    @Test
    fun `the word stop inside an interested reply is not a hard stop`() {
        assertFalse(ReplyText.isHardStop("On travaille non-stop sur l'app, intéressé"))
        assertFalse(ReplyText.isHardStop("Stop, c'est justement notre sujet, on en parle ?"))
    }

    @Test
    fun `the quoted email below an Outlook style header is cut before the hard-stop check`() {
        val body = "Oui, intéressé\n\nDe : Prénom <x@doppio-studio.fr>\nEnvoyé : lundi 13 octobre 2026 09:12\nObjet : app mobile\n\nTexte de notre email avec lien de désinscription"
        val trimmed = ReplyText.stripQuotedHistory(body)
        assertEquals("Oui, intéressé", trimmed)
        assertFalse(ReplyText.isHardStop(trimmed))
    }

    @Test
    fun `every usual quoted-header style is cut`() {
        val reply = "Pas maintenant, revenez en janvier.\n\n"
        listOf(
            "From: x@doppio-studio.fr\nSent: Monday",
            "-----Original Message-----\nFrom: x",
            "-----Message d'origine-----\nDe : x",
            "Le lun. 13 oct. 2026, Prénom a écrit :\n> notre email",
            "On Mon, Oct 13, 2026, Prénom wrote:\n> our email",
            "sent: Monday 13",
            "> notre email cité",
            "--\nSignature\ndésinscription",
        ).forEach { quoted ->
            assertEquals("Pas maintenant, revenez en janvier.", ReplyText.stripQuotedHistory(reply + quoted), "header: $quoted")
        }
    }

    @Test
    fun `the prospect's own words are kept`() {
        val body = "Bonjour,\nde notre côté on est plutôt satisfaits de l'app.\nMerci"
        assertEquals(body, ReplyText.stripQuotedHistory(body))
    }
}
