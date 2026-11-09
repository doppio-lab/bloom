package lab.doppio.bloom.core

import lab.doppio.bloom.core.guard.GuardInput
import lab.doppio.bloom.core.guard.MessageGuard
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins every violation the guard can raise, with its exact French detail, as the reviewer sees it. */
class GuardMessagesPinTest {
    private val facts = listOf(Fact("a1", 1, FactKind.APP_METADATA, "Note moyenne : 3,1 sur 412 notes", "https://x.test", "2026-10-10T08:00:00Z"))

    private fun check(
        body: String,
        subject: String? = "Crash au paiement",
        channel: Channel = Channel.EMAIL,
        step: Int = 0,
        ref: String? = null,
        sector: Sector = Sector.MOBILITY_TRANSPORT,
        language: String = "fr",
        cited: List<String> = emptyList(),
    ): List<String> = MessageGuard.check(
        GuardInput(channel, step, subject, body, cited, facts, ref, sector, Offer.DISCOVERY, language),
    ).map { it.toString() }

    private val ok = "Bonjour, une note [F:a1]. Un échange ?"

    @Test
    fun `typography and tone`() {
        assertEquals(
            listOf(
                "[BLOCK] tiret-cadratin : remplacer par une virgule, un point ou des parenthèses",
                "[BLOCK] emoji : aucun emoji en prospection",
                "[BLOCK] vocabulaire : expression interdite : « leader »",
                "[WARN] exclamation : ton trop vendeur",
            ),
            check("Un leader \u2014 \uD83D\uDE00 [F:a1] ! Un échange ?"),
        )
        assertEquals(
            listOf(
                "[BLOCK] vocabulaire : expression interdite : « leverage »",
                "[BLOCK] vocabulaire : expression interdite : « quick question »",
            ),
            check("Leverage this, quick question? [F:a1]", language = "en"),
        )
    }

    @Test
    fun `length of emails and LinkedIn notes`() {
        val long = "mot ".repeat(121) + "[F:a1] ?"
        assertEquals(listOf("[BLOCK] longueur : 121 mots, maximum 120 pour cette étape"), check(long))
        assertEquals(listOf("[BLOCK] longueur : 121 mots, maximum 80 pour cette étape"), check(long, step = 1))
        assertEquals(
            listOf("[BLOCK] longueur : ${"x".repeat(301).length} caractères, maximum 300 pour une note LinkedIn"),
            check("x".repeat(301), channel = Channel.LINKEDIN),
        )
    }

    @Test
    fun `subject rules`() {
        assertEquals(listOf("[BLOCK] objet : objet manquant"), check(ok, subject = null))
        assertEquals(listOf("[WARN] objet : objet de 8 mots, viser 2 à 6"), check(ok, subject = "a b c d e f g h"))
        assertEquals(listOf("[BLOCK] objet : faux « Re: » interdit"), check(ok, subject = "Re: suite"))
    }

    @Test
    fun `evidence and invented numbers`() {
        assertEquals(
            listOf(
                "[BLOCK] fait-inconnu : la citation F:zz ne correspond à aucun fait du dossier",
            ),
            check("Bonjour [F:a1] [F:zz]. Un échange ?"),
        )
        assertEquals(
            listOf("[BLOCK] sans-preuve : le premier email doit s'appuyer sur au moins un fait sourcé"),
            check("Bonjour. Un échange ?"),
        )
        assertEquals(
            listOf("[BLOCK] chiffre-non-sourcé : « 77 » n'apparaît dans aucun fait cité"),
            check("Bonjour, 77 [F:a1]. Un échange ?"),
        )
    }

    @Test
    fun `client references`() {
        assertEquals(listOf("[BLOCK] référence : référence inconnue : nope"), check(ok, ref = "nope"))
        assertEquals(
            listOf("[BLOCK] référence : Happn n'est pas pertinent pour ce secteur et cette offre"),
            check(ok + " Happn", ref = "happn"),
        )
        assertEquals(
            listOf("[BLOCK] référence : Decathlon est cité sans être déclaré comme référence"),
            check(ok + " Decathlon"),
        )
        assertEquals(
            listOf("[WARN] référence : Routine est cité sans être déclaré comme référence"),
            check(ok + " routine"),
        )
        assertEquals(
            listOf("[WARN] référence : Crédit Agricole : vérifier que le détail est citable"),
            check(ok + " banque de détail, environnement régulé", ref = "creditagricole", sector = Sector.BANK_INSURANCE),
        )
    }

    @Test
    fun `links`() {
        assertEquals(
            listOf(
                "[BLOCK] lien : pas de lien dans le premier email (délivrabilité)",
                "[BLOCK] lien : lien externe non autorisé : https://example.com/x",
            ),
            check("Bonjour [F:a1] https://example.com/x \n Un échange ?"),
        )
    }

    @Test
    fun `one message, one question`() {
        assertEquals(
            listOf("[WARN] appel-à-l'action : aucune question : terminer par une demande simple"),
            check("Bonjour [F:a1]."),
        )
        assertEquals(
            listOf("[WARN] appel-à-l'action : trop de questions, en garder une"),
            check("Bonjour [F:a1] ? Et ? Et ?"),
        )
    }
}
