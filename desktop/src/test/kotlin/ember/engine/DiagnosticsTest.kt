package ember.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test
    fun removesLinksIdsAndAddressesButKeepsNames() {
        val out = Diagnostics.redact(
            "connect vless://e12466ae-4b09-4951-a0cd-5d262beb81e7@fdh11.example.top:8080?security=reality&sni=x#UK now; " +
                "id e12466ae-4b09-4951-a0cd-5d262beb81e7 to 203.0.113.7; key 3q2-9Ab_cdEFghIJklMNopQRstUVwxYZ012345; " +
                "could not look up sv0.example.ir",
        )
        assertFalse("e12466ae" in out)
        assertFalse("203.0.113.7" in out)
        assertFalse("fdh11" in out)
        assertFalse("3q2-9Ab" in out)
        assertTrue("vless://<removed>" in out)
        assertTrue("sv0.example.ir" in out)
    }

    @Test
    fun reportStartsWithTheVersionAndRedactsTheLog() {
        val out = Diagnostics.report("1.0.101", listOf("a vmess://secret@host:1 b"))
        assertTrue(out.startsWith("Ember 1.0.101"))
        assertFalse("secret" in out)
        assertTrue("last 1 log lines" in out)
    }
}
