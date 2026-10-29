package ember.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExcludedAddressTest {
    @Test
    fun websiteLinksAreReducedToTheirHostname() {
        assertEquals("open.spotify.com", normalizeExcludedAddress("https://open.spotify.com/"))
        assertEquals("example.com", normalizeExcludedAddress(" Example.com/watch?v=1#top "))
        assertEquals("example.com", normalizeExcludedAddress("https://example.com:443/path"))
    }

    @Test
    fun acceptsDomainsInternationalizedDomainsAndIpv4Ranges() {
        assertEquals("example.com", normalizeExcludedAddress("EXAMPLE.COM"))
        assertEquals("xn--mnich-kva.example", normalizeExcludedAddress("münich.example"))
        assertEquals("192.168.1.20", normalizeExcludedAddress("192.168.1.20"))
        assertEquals("192.168.0.0/24", normalizeExcludedAddress("192.168.0.0/24"))
    }

    @Test
    fun rejectsUnsupportedOrMalformedAddresses() {
        listOf(
            "",
            "999.1.2.3",
            "192.168.0.0/33",
            "ftp://example.com/file",
            "https://user@example.com/",
            "https://example.com:70000/",
            "https://example.com:bad/",
        ).forEach { assertNull("Expected rejection for $it", normalizeExcludedAddress(it)) }
    }
}
