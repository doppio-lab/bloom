package lab.doppio.bloom.core

/** Doppio's public identity: the domain it sends mail from and the user agent its HTTP client announces. */
object Brand {
    const val DOMAIN = "doppio-lab.com"

    /** Sent with every outgoing request so site owners can tell who is reading and where to reach us. */
    const val USER_AGENT = "DoppioBloom/0.1 (+https://$DOMAIN)"

    /** Public case study page of Doppio. */
    fun caseStudyUrl(slug: String) = "https://$DOMAIN/case-studies/$slug"
}
