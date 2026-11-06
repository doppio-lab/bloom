package lab.doppio.bloom.data.sources.discovery

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryFormattingTest {
    @Test
    fun `euros groups thousands with a plain space whatever the JVM locale`() {
        val default = Locale.getDefault()
        try {
            for (locale in listOf(Locale.FRANCE, Locale.US, Locale.GERMANY, Locale.ROOT)) {
                Locale.setDefault(locale)
                assertEquals("1 250 000 €", euros(1_250_000), "locale $locale")
                assertEquals("999 €", euros(999), "locale $locale")
            }
        } finally {
            Locale.setDefault(default)
        }
    }
}
