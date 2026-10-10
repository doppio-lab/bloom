package lab.doppio.bloom.core.references

import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector

/**
 * Références Doppio utilisables en prospection.
 *
 * Règle : on ne cite jamais un client sans raison précise. Une référence n'est autorisée
 * que si le secteur du prospect ou l'offre proposée figure dans [relevantFor] / [relevantOffers].
 * Les preuves chiffrées viennent des études de cas publiques de doppio-lab.com (octobre 2026).
 * À faire valider par Doppio : certains clients n'autorisent qu'une citation du nom.
 */
@Serializable
data class Reference(
    val key: String,
    val client: String,
    val proof: String,
    val caseStudyUrl: String?,
    val relevantFor: Set<Sector>,
    val relevantOffers: Set<Offer> = emptySet(),
    /** Si faux, le nom seul est citable, sans détail de projet. */
    val detailsPublic: Boolean = true,
)

object ReferenceLibrary {
    val all: List<Reference> = listOf(
        Reference(
            key = "routine",
            client = "Routine",
            proof = "apps iOS et Android natives sur un cœur OCaml, synchronisation CRDT et fonctionnement hors ligne complet, 100 000+ professionnels",
            caseStudyUrl = "https://doppio-lab.com/case-studies/routine",
            relevantFor = setOf(Sector.PRODUCTIVITY_SAAS, Sector.PRIVACY),
            relevantOffers = setOf(Offer.FORFAIT, Offer.DISCOVERY),
        ),
        Reference(
            key = "quelproduit",
            client = "UFC Que Choisir (QuelProduit)",
            proof = "370 000+ produits notés, 4,7/5 sur l'App Store avec plus de 6 600 notes",
            caseStudyUrl = "https://doppio-lab.com/case-studies/quelproduit",
            relevantFor = setOf(Sector.CONSUMER_ASSOCIATION, Sector.RETAIL, Sector.HEALTH),
            relevantOffers = setOf(Offer.FORFAIT),
        ),
        Reference(
            key = "rythme",
            client = "Cogepart (Rythme)",
            proof = "70 000 produits commandés par scan de code-barres, livrés par un réseau de 5 000 chauffeurs",
            caseStudyUrl = "https://doppio-lab.com/case-studies/rythme",
            relevantFor = setOf(Sector.LOGISTICS, Sector.RETAIL, Sector.MOBILITY_TRANSPORT),
            relevantOffers = setOf(Offer.FORFAIT),
        ),
        Reference(
            key = "decathlon",
            client = "Decathlon",
            proof = "suivi GPS 24/7 et mode vol du vélo électrique LD 940E, en Bluetooth direct avec l'app",
            caseStudyUrl = "https://doppio-lab.com/case-studies/decathlon",
            relevantFor = setOf(Sector.RETAIL, Sector.IOT_HARDWARE, Sector.MOBILITY_TRANSPORT),
            relevantOffers = setOf(Offer.FORFAIT, Offer.REGIE),
        ),
        Reference(
            key = "musicwork",
            client = "Music Work",
            proof = "15 ans de catalogue migrés sans coupure, lecteur Compose Multiplatform cité en vitrine officielle par JetBrains",
            caseStudyUrl = "https://doppio-lab.com/case-studies/music-work",
            relevantFor = setOf(Sector.MUSIC_MEDIA, Sector.GAMING),
            relevantOffers = setOf(Offer.TRAINING_KMP, Offer.DISCOVERY, Offer.FORFAIT),
        ),
        Reference(
            key = "playguide",
            client = "PlayGuide",
            proof = "app Android native réécrite, 55 000 aires de jeux dans quatre pays",
            caseStudyUrl = "https://doppio-lab.com/case-studies/playguide",
            relevantFor = setOf(Sector.DATING_SOCIAL, Sector.CONSUMER_ASSOCIATION, Sector.OTHER),
            relevantOffers = setOf(Offer.DISCOVERY, Offer.FORFAIT),
        ),
        Reference(
            key = "creditagricole",
            client = "Crédit Agricole",
            proof = "banque de détail, environnement régulé",
            caseStudyUrl = null,
            relevantFor = setOf(Sector.BANK_INSURANCE),
            relevantOffers = setOf(Offer.REGIE, Offer.FORFAIT),
            detailsPublic = false,
        ),
        Reference(
            key = "proton",
            client = "Proton",
            proof = "produits privacy-first",
            caseStudyUrl = null,
            relevantFor = setOf(Sector.PRIVACY),
            detailsPublic = false,
        ),
        Reference(
            key = "netatmo",
            client = "Netatmo",
            proof = "objets connectés",
            caseStudyUrl = null,
            relevantFor = setOf(Sector.IOT_HARDWARE),
            detailsPublic = false,
        ),
        Reference(
            key = "happn",
            client = "Happn",
            proof = "app de rencontre grand public",
            caseStudyUrl = null,
            relevantFor = setOf(Sector.DATING_SOCIAL),
            detailsPublic = false,
        ),
    )

    fun byKey(key: String): Reference? = all.firstOrNull { it.key == key }

    /** Références autorisées pour un prospect donné, la plus spécifique en premier. */
    fun eligible(sector: Sector, offer: Offer): List<Reference> =
        all.filter { sector in it.relevantFor || offer in it.relevantOffers }
            .sortedByDescending { (if (sector in it.relevantFor) 2 else 0) + (if (offer in it.relevantOffers) 1 else 0) }

    fun isAllowed(key: String, sector: Sector, offer: Offer): Boolean =
        eligible(sector, offer).any { it.key == key }
}
