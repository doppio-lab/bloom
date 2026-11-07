package lab.doppio.bloom.core

import lab.doppio.bloom.core.guard.GuardInput
import lab.doppio.bloom.core.guard.GuardLimits
import lab.doppio.bloom.core.guard.MessageGuard
import lab.doppio.bloom.core.guard.Severity
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import kotlin.test.Test
import kotlin.test.assertEquals

/** The guard keeps its limits and its messages byte for byte while the numbers come from GuardLimits. */
class GuardLimitsTest {
    private fun check(channel: Channel, step: Int, subject: String?, body: String) = MessageGuard.check(
        GuardInput(channel, step, subject, body, emptyList(), emptyList(), null, Sector.OTHER, Offer.DISCOVERY, "fr"),
    ).map { it.toString() }

    private fun words(n: Int) = List(n) { "mot" }.joinToString(" ") + "?"

    @Test
    fun `limits keep their documented values`() {
        assertEquals(120, GuardLimits.FIRST_EMAIL_WORDS)
        assertEquals(80, GuardLimits.FOLLOW_UP_WORDS)
        assertEquals(300, GuardLimits.LINKEDIN_CHARS)
        assertEquals(2, GuardLimits.SUBJECT_MIN_WORDS)
        assertEquals(6, GuardLimits.SUBJECT_MAX_WORDS)
        assertEquals(7, GuardLimits.SUBJECT_WARN_ABOVE)
        assertEquals(2, GuardLimits.MAX_QUESTIONS)
        assertEquals(setOf("1", "2", "3", "15", "20", "30"), GuardLimits.ALWAYS_ALLOWED_NUMBERS)
    }

    @Test
    fun `word limits per step`() {
        val noProof = "[BLOCK] sans-preuve : le premier email doit s'appuyer sur au moins un fait sourcé"
        assertEquals(
            listOf("[BLOCK] longueur : 121 mots, maximum 120 pour cette étape", noProof),
            check(Channel.EMAIL, 0, "sujet court", words(121)),
        )
        assertEquals(listOf(noProof), check(Channel.EMAIL, 0, "sujet court", words(120)))
        assertEquals(listOf("[BLOCK] longueur : 81 mots, maximum 80 pour cette étape"), check(Channel.EMAIL, 1, "sujet court", words(81)))
        assertEquals(emptyList(), check(Channel.EMAIL, 1, "sujet court", words(80)))
    }

    @Test
    fun `subject limits`() {
        assertEquals(emptyList(), check(Channel.EMAIL, 1, "un deux trois quatre cinq six sept", "Bonjour ?"))
        assertEquals(
            listOf("[WARN] objet : objet de 8 mots, viser 2 à 6"),
            check(Channel.EMAIL, 1, "un deux trois quatre cinq six sept huit", "Bonjour ?"),
        )
    }

    @Test
    fun `linkedin and question limits`() {
        val ok = "a".repeat(299) + "?"
        assertEquals(emptyList(), check(Channel.LINKEDIN, 1, null, ok))
        assertEquals(
            listOf("[BLOCK] longueur : 301 caractères, maximum 300 pour une note LinkedIn"),
            check(Channel.LINKEDIN, 1, null, "a".repeat(300) + "?"),
        )
        assertEquals(emptyList(), check(Channel.LINKEDIN, 1, null, "Ça va ? Oui ?"))
        val v = MessageGuard.check(GuardInput(Channel.LINKEDIN, 1, null, "a ? b ? c ?", emptyList(), emptyList(), null, Sector.OTHER, Offer.DISCOVERY, "fr"))
        assertEquals(listOf("[WARN] appel-à-l'action : trop de questions, en garder une"), v.map { it.toString() })
        assertEquals(Severity.WARN, v.single().severity)
    }
}
