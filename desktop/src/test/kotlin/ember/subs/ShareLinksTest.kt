package ember.subs

import java.util.Base64
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareLinksTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    @Test
    fun readsVlessReality() {
        val node = ShareLinks.parse("vless://$uuid@vl.example.com:443?type=tcp&security=reality&pbk=KEY&sni=www.example.org&sid=ab&flow=xtls-rprx-vision#UK%20Reality")
        assertNotNull(node)
        assertEquals("vless", node.protocol)
        assertEquals("vl.example.com", node.host)
        assertEquals(443, node.port)
        assertEquals("UK Reality", node.name)
        val outbound = ShareLinks.outbound(node.raw, "203.0.113.7", "proxy")
        assertEquals("203.0.113.7", outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        val reality = outbound.getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertEquals("www.example.org", reality.getString("serverName"))
        assertEquals("KEY", reality.getString("publicKey"))
    }

    @Test
    fun readsVmessTrojanAndShadowsocks() {
        val vmess = """{"v":"2","ps":"Vm","add":"vm.example.com","port":"443","id":"$uuid","aid":"0","net":"ws","host":"vm.example.com","path":"/w","tls":"tls"}"""
        assertEquals("vmess", ShareLinks.parse("vmess://" + Base64.getEncoder().encodeToString(vmess.toByteArray()))?.protocol)
        assertEquals("trojan", ShareLinks.parse("trojan://pw@tr.example.com:443?security=tls#T")?.protocol)
        val ss = "ss://" + Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:pw".toByteArray()) + "@1.2.3.4:8388#S"
        val node = ShareLinks.parse(ss)
        assertNotNull(node)
        assertEquals("1.2.3.4", node.host)
        assertEquals("aes-256-gcm", ShareLinks.outbound(ss, "1.2.3.4", "p").getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("method"))
    }

    @Test
    fun ignoresGarbageAndMarksInformationRows() {
        assertNull(ShareLinks.parse("hello"))
        assertNull(ShareLinks.parse("vless://broken"))
        assertTrue(ShareLinks.parse("vless://$uuid@0.0.0.0:1?type=tcp#99%20GB%20left")!!.isInfo)
    }

    @Test
    fun takesTheFlagFromTheNameAndDropsItFromTheTitle() {
        val node = ShareLinks.parse("vless://$uuid@h.example.com:443?type=tcp#%F0%9F%87%A9%F0%9F%87%AA%20Germany%201")!!
        assertEquals("de", node.flagCode)
        assertEquals("Germany 1", node.title)
        assertNull(ShareLinks.parse("vless://$uuid@h.example.com:443?type=tcp#Plain")!!.flagCode)
    }

    @Test
    fun usesTheLastLeadingFlagWhenThereAreTwo() {
        val node = ShareLinks.parse("vless://$uuid@h.example.com:443?type=tcp#%F0%9F%87%AE%F0%9F%87%B7%20%F0%9F%87%AB%F0%9F%87%AE%202.5x")!!
        assertEquals("fi", node.flagCode)
        assertEquals("2.5x", node.title)
    }

    @Test
    fun readsPlainAndBase64Subscriptions() {
        val links = listOf(
            "vless://$uuid@a.example.com:443?type=tcp#A",
            "trojan://pw@b.example.com:443?security=tls#B",
        )
        assertEquals(2, SubscriptionParser.parseNodes(links.joinToString("\n")).size)
        assertEquals(2, SubscriptionParser.parseNodes(Base64.getEncoder().encodeToString(links.joinToString("\n").toByteArray())).size)
        assertFailsWith<IllegalStateException> { SubscriptionParser.parseNodes("not a subscription") }
    }

    @Test
    fun readsUsageAndTitleHeaders() {
        val usage = SubscriptionParser.parseUserInfo("upload=1; download=2; total=100; expire=1893456000")
        assertEquals(3L, usage?.used)
        assertEquals(100L, usage?.total)
        assertEquals(1893456000L, usage?.expiresAtEpochSeconds)
        assertNull(SubscriptionParser.parseUserInfo(null))
        assertEquals("My VPN", SubscriptionParser.parseTitle("base64:" + Base64.getEncoder().encodeToString("My VPN".toByteArray())))
    }

    @Test
    fun outboundJsonIsValidJson() {
        val node = ShareLinks.parse("vless://$uuid@h.example.com:443?type=ws&security=tls&path=%2Fp&host=h.example.com#W")!!
        JSONObject(ShareLinks.outbound(node.raw, "203.0.113.7", "proxy").toString())
    }

    @Test
    fun readsHysteria2AndSkipsObfuscatedOnes() {
        val node = ShareLinks.parse("hysteria2://secret@hy.example.com:443?sni=hy.example.com&insecure=1#Hy")
        assertNotNull(node)
        assertEquals("hysteria2", node.protocol)
        val outbound = ShareLinks.outbound(node.raw, "203.0.113.7", "proxy")
        assertEquals("hysteria", outbound.getString("protocol"))
        assertEquals("secret", outbound.getJSONObject("streamSettings").getJSONObject("hysteriaSettings").getString("auth"))
        assertNull(ShareLinks.parse("hysteria2://secret@hy.example.com:443?obfs=salamander&obfs-password=x#Hy"))
        assertEquals("hysteria2", ShareLinks.parse("hy2://secret@hy.example.com:443#Short")?.protocol)
    }

    @Test
    fun readsAnXrayJsonSubscription() {
        val json = """[{"remarks":"JSON One","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"j.example.com","port":443,"users":[{"id":"$uuid","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"tls"}},{"protocol":"freedom","tag":"direct"}]}]"""
        val nodes = SubscriptionParser.parseNodes(json)
        assertEquals(1, nodes.size)
        assertEquals("JSON One", nodes[0].name)
        assertEquals("j.example.com", nodes[0].host)
        val outbound = ShareLinks.outbound(nodes[0].raw, "203.0.113.7", "proxy")
        assertEquals("203.0.113.7", outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertEquals("proxy", outbound.getString("tag"))
        // a node read back from storage is the same node
        assertEquals(nodes[0].id, ShareLinks.parse(nodes[0].raw)?.id)
    }

    @Test
    fun sortsFavouritesThenSpeed() {
        val a = ShareLinks.parse("vless://$uuid@a.example.com:443?type=tcp#A")!!
        val b = ShareLinks.parse("vless://$uuid@b.example.com:443?type=tcp#B")!!
        val c = ShareLinks.parse("vless://$uuid@c.example.com:443?type=tcp#C")!!
        val results = mapOf(a.id to NodeResult(50, 10.0, 0), b.id to NodeResult(60, 90.0, 0), c.id to NodeResult(20, null, 0))
        assertEquals(listOf("B", "A", "C"), sortNodes(listOf(a, b, c), results, emptySet()).map { it.name })
        assertEquals(listOf("A", "B", "C"), sortNodes(listOf(a, b, c), results, setOf(a.id)).map { it.name })
    }
}
