package lab.doppio.bloom.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BrandTest {
    @Test
    fun `brand values equal the literals they replaced`() {
        assertEquals("doppio-lab.com", Brand.DOMAIN)
        assertEquals("DoppioBloom/0.1 (+https://doppio-lab.com)", Brand.USER_AGENT)
        assertEquals("https://doppio-lab.com/case-studies/routine", Brand.caseStudyUrl("routine"))
    }
}
