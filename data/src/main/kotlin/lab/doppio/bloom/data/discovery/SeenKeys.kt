package lab.doppio.bloom.data.discovery

import lab.doppio.bloom.data.sources.discovery.CodeurSource

/** The keys under which discovery remembers what it already examined. Stored in discovery_seen, so the formats never change. */
object SeenKeys {
    /** A France Travail job offer. */
    fun franceTravail(offerId: String) = "ft:$offerId"

    /** An App Store app. */
    fun app(appId: Long) = "app:$appId"

    /** A Codeur project, also the key of its stored demand. */
    fun codeur(projectId: Any) = "${CodeurSource.KEY}:$projectId"

    /** A piece of evidence found by the source whose key is [sourceKey]. */
    fun of(sourceKey: String, id: Any) = "$sourceKey:$id"
}
