package lab.doppio.bloom.data.sources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeHostsTest {
    @Test
    fun `les hôtes publics passent`() {
        listOf(
            "velomoov.fr", "www.example.com", "jobs.lever.co", "Velomoov.FR", "velomoov.fr.",
            "8.8.8.8", "93.184.216.34", "172.32.0.1", "172.15.255.255", "192.167.1.1", "100.63.0.1", "100.128.0.1",
            "2001:4860:4860::8888", "[2606:4700::1111]", "internal-tools.example.com", "localhost.example.com",
        ).forEach { assertTrue(SafeHosts.isPublic(it), it) }
    }

    @Test
    fun `les adresses IP privées, locales ou de boucle sont refusées`() {
        listOf(
            "127.0.0.1", "127.1.2.3", "0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "169.254.169.254", "169.254.0.1", "100.64.0.1", "100.127.255.255", "224.0.0.1", "255.255.255.255",
            "::1", "[::1]", "::", "fe80::1", "fc00::1", "fd12:3456::1", "FD00::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "ff02::1",
        ).forEach { assertFalse(SafeHosts.isPublic(it), it) }
    }

    @Test
    fun `les noms internes sont refusés`() {
        listOf(
            "localhost", "LOCALHOST", "localhost.", "app.localhost", "printer.local", "db.internal", "metadata.google.internal",
            "host.docker.internal", "", " ", "velomoov.fr/",
        ).forEach { assertFalse(SafeHosts.isPublic(it), "'$it'") }
    }
}
