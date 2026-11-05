package ember.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppRoutingConfigTest {

    private fun config(mode: String) = AppRoutingConfig.build(
        mode, listOf("Firefox.exe"), 10808, listOf("10.1.0.0/16"), listOf("open.example.com"), File("sing-box.log"),
    )

    @Test
    fun skippedAppsGoDirectAndEverythingElseUsesTheVpn() {
        val c = config(AppRuleMode.SKIP)
        val route = c.getJSONObject("route")
        assertEquals("proxy", route.getString("final"))
        val rules = route.getJSONArray("rules")
        val app = (0 until rules.length()).map { rules.getJSONObject(it) }
            .first { it.has("process_name") && it.getJSONArray("process_name").toList().contains("Firefox.exe") }
        assertEquals("direct", app.getString("outbound"))
        // both spellings, because Windows names differ in case
        assertTrue(app.getJSONArray("process_name").toList().contains("firefox.exe"))
        assertEquals("remote", c.getJSONObject("dns").getString("final"))
        assertEquals("local", c.getJSONObject("dns").getJSONArray("rules").getJSONObject(0).getString("server"))
    }

    @Test
    fun onlyTheListedAppsUseTheVpnInTheOtherMode() {
        val c = config(AppRuleMode.ONLY)
        assertEquals("direct", c.getJSONObject("route").getString("final"))
        val rules = c.getJSONObject("route").getJSONArray("rules")
        val app = (0 until rules.length()).map { rules.getJSONObject(it) }.last()
        assertEquals("proxy", app.getString("outbound"))
        assertEquals("local", c.getJSONObject("dns").getString("final"))
    }

    @Test
    fun theProgramsThatCarryTheVpnLeaveDirectlyAndTheSkipListIsKept() {
        val rules = config(AppRuleMode.SKIP).getJSONObject("route").getJSONArray("rules")
        val all = (0 until rules.length()).map { rules.getJSONObject(it) }
        val carriers = all.first { it.has("process_name") && it.getJSONArray("process_name").toList().contains("xray.exe") }
        assertEquals("direct", carriers.getString("outbound"))
        assertTrue(all.any { it.has("ip_cidr") && it.getString("outbound") == "direct" })
        assertTrue(all.any { it.has("domain") && it.getString("outbound") == "direct" })
        assertEquals("socks", config(AppRuleMode.SKIP).getJSONArray("outbounds").getJSONObject(0).getString("type"))
    }
}
