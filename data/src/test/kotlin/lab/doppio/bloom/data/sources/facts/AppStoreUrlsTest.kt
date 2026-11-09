package lab.doppio.bloom.data.sources.facts

import kotlin.test.Test
import kotlin.test.assertEquals

class AppStoreUrlsTest {
    @Test
    fun `review feed url equals the former literal`() {
        assertEquals(
            "https://itunes.apple.com/fr/rss/customerreviews/page=2/id=123/sortBy=mostRecent/json",
            AppStoreSource.reviewsUrl("fr", 123L, 2),
        )
    }
}
