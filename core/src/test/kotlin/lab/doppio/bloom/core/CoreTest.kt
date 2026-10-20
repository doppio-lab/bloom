package lab.doppio.bloom.core

import lab.doppio.bloom.core.guard.GuardInput
import lab.doppio.bloom.core.guard.MessageGuard
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Market
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.scoring.Scoring
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.core.sequence.NextAction
import lab.doppio.bloom.core.sequence.SequenceEngine
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScoringTest {
    private val now = Instant.parse("2026-10-12T08:00:00Z")
    private val account = Account(
        id = 1, name = "Velo&Co", domain = "veloco.fr", sector = Sector.MOBILITY_TRANSPORT,
        market = Market.FR, headcount = 120, iosAppId = 123, androidPackage = "fr.veloco",
    )

    @Test
    fun `un compte sans signal reste en veille`() {
        val s = Scoring.score(account, emptyList(), now)
        assertEquals(40, s.fit)
        assertEquals(0, s.intent)
        assertEquals(Scoring.Tier.WATCH, Scoring.tier(s.total))
    }

    @Test
    fun `des plaintes qualité récentes recommandent l'audit`() {
        val signals = listOf(
            Signal(1, SignalType.QUALITY_COMPLAINTS, 0.9, "2026-10-10T08:00:00Z", listOf("f1")),
            Signal(1, SignalType.RATING_DROP, 0.8, "2026-10-01T08:00:00Z", listOf("f2")),
        )
        val s = Scoring.score(account, signals, now)
        assertEquals(Offer.DISCOVERY, s.recommendedOffer)
        assertTrue(s.total >= 65, "score ${s.total}")
    }

    @Test
    fun `un signal ancien perd la moitié de son poids à sa demi-vie`() {
        val d = Scoring.decay(Instant.parse("2026-09-12T08:00:00Z"), now, 30.0)
        assertTrue(d in 0.49..0.51)
    }
}

class GuardTest {
    private val facts = listOf(
        Fact("a1", 1, FactKind.APP_METADATA, "Note moyenne App Store : 3,1 sur 412 notes", "https://apps.apple.com/fr/app/id123", "2026-10-10T08:00:00Z"),
        Fact("a2", 1, FactKind.APP_REVIEW, "11 avis sur 30 depuis la version 5.2 mentionnent un crash au paiement", "https://itunes.apple.com/fr/rss/customerreviews/id=123/json", "2026-10-10T08:00:00Z"),
    )

    private fun input(body: String, subject: String? = "Crash au paiement", ref: String? = null) = GuardInput(
        channel = Channel.EMAIL, stepIndex = 0, subject = subject, body = body,
        citedFactIds = emptyList(), availableFacts = facts, referenceKey = ref,
        sector = Sector.MOBILITY_TRANSPORT, offer = Offer.DISCOVERY, language = "fr",
    )

    @Test
    fun `un email sourcé et sobre passe`() {
        val body = "Bonjour Claire, depuis la version 5.2, 11 avis sur 30 parlent d'un crash au paiement [F:a2]. " +
            "On a vu ce symptôme sur des apps de commande. On peut auditer l'app en 2 jours, à prix fixe. Ça vaut un échange de 15 minutes ?"
        val v = MessageGuard.check(input(body))
        assertTrue(MessageGuard.isPublishable(v), v.joinToString())
    }

    @Test
    fun `un chiffre inventé est bloqué`() {
        val body = "Bonjour, 40 % de vos utilisateurs subissent des crashs [F:a2]. Un échange ?"
        val v = MessageGuard.check(input(body))
        assertTrue(v.any { it.rule == "chiffre-non-sourcé" })
        assertFalse(MessageGuard.isPublishable(v))
    }

    @Test
    fun `un chiffre contenu dans un autre chiffre du fait reste inventé`() {
        val fact = Fact("a3", 1, FactKind.APP_METADATA, "Note App Store", "https://apps.apple.com/fr/app/id123", "2026-10-10T08:00:00Z", excerpt = "note moyenne 3,1 sur 412 notes")
        val blocked = MessageGuard.check(input("Bonjour, 12 crashs par jour sur l'app [F:a3]. Un échange ?").copy(availableFacts = facts + fact))
        assertTrue(blocked.any { it.rule == "chiffre-non-sourcé" }, blocked.joinToString())
        val allowed = MessageGuard.check(input("Bonjour, 412 notes sur l'app [F:a3]. Un échange ?").copy(availableFacts = facts + fact))
        assertTrue(allowed.none { it.rule == "chiffre-non-sourcé" }, allowed.joinToString())
    }

    @Test
    fun `l'espace insécable des citations françaises est un séparateur de milliers`() {
        val fact = Fact("a4", 1, FactKind.WEB_PAGE, "Base installée", "https://example.fr/presse", "2026-10-10T08:00:00Z", excerpt = "100\u00A0000 utilisateurs")
        listOf("100 000 utilisateurs", "100\u202F000 utilisateurs", "100\u00A0000 utilisateurs").forEach { phrase ->
            val v = MessageGuard.check(input("Bonjour, $phrase [F:a4]. Un échange ?").copy(availableFacts = facts + fact))
            assertTrue(v.none { it.rule == "chiffre-non-sourcé" }, "$phrase : $v")
        }
    }

    @Test
    fun `l'objet est soumis aux mêmes règles de chiffres et de liens que le corps`() {
        val body = "Bonjour, crash au paiement [F:a2]. Un échange ?"
        val number = MessageGuard.check(input(body, subject = "40 % de crashs au paiement"))
        assertTrue(number.any { it.rule == "chiffre-non-sourcé" }, number.joinToString())
        val link = MessageGuard.check(input(body, subject = "Voir https://x.y"))
        assertTrue(link.any { it.rule == "lien" }, link.joinToString())
    }

    @Test
    fun `seul le domaine doppio-lab point com est autorisé dans les liens`() {
        fun followUp(body: String) = MessageGuard.check(input(body).copy(stepIndex = 1))
        val evil = followUp("Bonjour, crash au paiement [F:a2]. Voir https://evil.io/?ref=doppio-lab.com et dites-moi ?")
        assertTrue(evil.any { it.rule == "lien" }, evil.joinToString())
        val own = followUp("Bonjour, crash au paiement [F:a2]. Voir https://www.doppio-lab.com/cas et dites-moi ?")
        assertTrue(own.none { it.rule == "lien" }, own.joinToString())
    }

    @Test
    fun `un premier email sans preuve est bloqué`() {
        val v = MessageGuard.check(input("Bonjour, on fait des apps natives. Un échange ?"))
        assertTrue(v.any { it.rule == "sans-preuve" })
    }

    @Test
    fun `le jargon et le tiret cadratin sont bloqués`() {
        val body = "Nous accompagnons les leaders — un échange ? [F:a1]"
        val rules = MessageGuard.check(input(body)).map { it.rule }
        assertTrue("vocabulaire" in rules)
        assertTrue("tiret-cadratin" in rules)
    }

    @Test
    fun `une référence hors secteur est bloquée`() {
        val body = "Bonjour, crash au paiement [F:a2]. Nous l'avons vu chez Happn. Un échange ?"
        val v = MessageGuard.check(input(body, ref = "happn"))
        assertTrue(v.any { it.rule == "référence" })
    }

    @Test
    fun `les citations sont retirées avant envoi`() {
        assertEquals("Crash au paiement.", MessageGuard.stripCitations("Crash au paiement [F:a2]."))
        assertEquals("crash au paiement.", MessageGuard.stripCitations("crash au paiement [F:ab12]."))
    }

    @Test
    fun `retirer les citations ne touche pas à l'espace avant la ponctuation française`() {
        assertEquals("Ça vaut un échange de 15 minutes ?", MessageGuard.stripCitations("Ça vaut un échange de 15 minutes ? [F:ab12]"))
        assertEquals("Un crash : au paiement ! Vraiment ?", MessageGuard.stripCitations("Un crash : au paiement ! Vraiment ?"))
    }
}

class SequenceEngineTest {
    private val policy = SendingPolicy()
    private val seq = DefaultSequences.audit()

    @Test
    fun `le premier email est préparé la veille du créneau`() {
        // Lundi 12 octobre 2026, 9h Paris : créneau mardi 8h30, préparation possible dès lundi 8h30
        val now = Instant.parse("2026-10-12T07:00:00Z")
        val e = Enrollment(1, 1, 1, EnrollmentStatus.ACTIVE, 0, null, "2026-10-12T06:00:00Z")
        assertIs<NextAction.PrepareDraft>(SequenceEngine.next(e, seq, policy, now))
    }

    @Test
    fun `le créneau saute le week-end et les jours hors fenêtre`() {
        val friday = Instant.parse("2026-10-16T12:00:00Z")
        val slot = SequenceEngine.nextSendingSlot(friday, policy)
        assertEquals(Instant.parse("2026-10-20T06:30:00Z"), slot) // mardi 8h30 Paris (UTC+2)
    }

    @Test
    fun `une séquence terminée renvoie COMPLETED`() {
        val e = Enrollment(1, 1, 1, EnrollmentStatus.ACTIVE, 3, "2026-10-01T08:00:00Z", "2026-09-20T08:00:00Z")
        assertEquals(NextAction.Finish(EnrollmentStatus.COMPLETED), SequenceEngine.next(e, seq, policy, Instant.now()))
    }

    @Test
    fun `les jours ouvrés ignorent le week-end`() {
        val d = SequenceEngine.addBusinessDays(java.time.LocalDate.parse("2026-10-16"), 2, policy)
        assertEquals(java.time.LocalDate.parse("2026-10-20"), d)
    }
}
