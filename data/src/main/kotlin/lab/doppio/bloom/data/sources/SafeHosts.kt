package lab.doppio.bloom.data.sources

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Hosts the sources may fetch from. A domain comes from a job offer, a registry record or a page we read,
 * so a crafted one could point the fetch at this machine, the private network or a cloud metadata
 * endpoint. IP literals and internal names are refused; public names are fetched as they are.
 */
internal object SafeHosts {
    private val internalSuffixes = listOf(".internal", ".local", ".localhost")
    private val internalNames = setOf("localhost", "host.docker.internal", "metadata.google.internal")
    private val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun isPublic(host: String): Boolean {
        val name = host.trim().lowercase().removeSurrounding("[", "]").removeSuffix(".")
        if (name.isEmpty() || name.any { it == '/' || it.isWhitespace() }) return false
        if (name in internalNames || internalSuffixes.any { name.endsWith(it) }) return false
        if (name.matches(ipv4) || ':' in name) return isPublicAddress(name)
        return true
    }

    /** Only literals reach here, so no DNS lookup happens. Unparseable literals are refused. */
    private fun isPublicAddress(literal: String): Boolean {
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        return when (address) {
            // "This network" (0/8), the shared address space (100.64/10) and the broadcast address are not public either
            is Inet4Address -> bytes[0].toInt() != 0 &&
                !((bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 0x40) && literal != "255.255.255.255"
            // Unique local addresses (fc00::/7)
            is Inet6Address -> (bytes[0].toInt() and 0xfe) != 0xfc
            else -> false
        }
    }
}
