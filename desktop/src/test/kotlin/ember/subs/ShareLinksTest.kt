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
    fun recognisesUsageMessagesAndStripsEmojiForPlainTitles() {
        val info = ShareLinks.parse("vless://$uuid@real.example.com:8080?type=tcp#%F0%9F%93%8A96.37%20GB%20%E2%8F%B3%E2%88%9E%20%D8%B1%D9%88%D8%B2%20%D8%A7%D8%B9%D8%AA%D8%A8%D8%A7%D8%B1")!!
        assertTrue(info.isInfo)
        assertEquals(false, ShareLinks.parse("vless://$uuid@real.example.com:8080?type=tcp#Finland%202")!!.isInfo)
        val flagged = ShareLinks.parse("vless://$uuid@h.example.com:443?type=tcp#%D8%A7%D8%B6%D8%B7%D8%B1%D8%A7%D8%B1%DB%8C%202.5x%20%F0%9F%87%AB%F0%9F%87%AE")!!
        assertEquals("اضطراری 2.5x", flagged.plainTitle)
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

    private val wireGuardFile = """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.8.0.2/32, fd00::2/128
        DNS = 1.1.1.1
        MTU = 1280

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = wg.example.com:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun readsAWireGuardFileAndPinsItsEndpoint() {
        val node = SubscriptionParser.parseNodes(wireGuardFile, "Office").single()
        assertEquals("wireguard", node.protocol)
        assertEquals("wg.example.com", node.host)
        assertEquals(51820, node.port)
        assertEquals("Office", node.name)
        val outbound = ShareLinks.outbound(node.raw, "203.0.113.9", "proxy")
        val settings = outbound.getJSONObject("settings")
        assertEquals("203.0.113.9:51820", settings.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
        // the tunnel's own addresses are left alone
        assertEquals("10.8.0.2/32", settings.getJSONArray("address").getString(0))
        assertEquals(1280, settings.getInt("mtu"))
        assertEquals(25, settings.getJSONArray("peers").getJSONObject(0).getInt("keepAlive"))
    }

    @Test
    fun explainsFilesItCannotUse() {
        val noPeer = "[Interface]\nPrivateKey = x\nAddress = 10.0.0.2/32\n"
        assertEquals("The WireGuard file has no [Peer] section.", assertFailsWith<IllegalStateException> { SubscriptionParser.parseNodes(noPeer) }.message)
    }

    private val ovpn = """
        client
        dev tun
        proto udp
        remote vpn.example.com 443 udp
        auth-user-pass creds.txt
        ca ca.crt
        tls-auth ta.key 1
        script-security 2
        up /bin/evil.cmd
        plugin evil.dll
        <cert>
        up not-a-directive-inside-a-block
        </cert>
    """.trimIndent()

    @Test
    fun readsAnOpenVpnProfileAndRemovesWhatRunsPrograms() {
        val files = mapOf("ca.crt" to "CA-PEM", "ta.key" to "TA-KEY")
        val profile = OpenVpnProfile.prepare(ovpn) { files[it] }
        assertTrue("<ca>\nCA-PEM\n</ca>" in profile)
        assertTrue("<tls-auth>\nTA-KEY\n</tls-auth>" in profile)
        assertTrue("key-direction 1" in profile)
        // credentials are asked for, never read from a file next to the profile
        assertTrue("auth-user-pass\n" in profile && "creds.txt" !in profile)
        assertTrue(OpenVpnProfile.needsCredentials(profile))
        for (removed in listOf("script-security 2", "/bin/evil.cmd", "evil.dll")) assertTrue(removed !in profile, removed)
        // text inside a certificate block is left alone
        assertTrue("up not-a-directive-inside-a-block" in profile)
    }

    @Test
    fun anOpenVpnServerKeepsItsNameAndIdWhenStoredAndReadBack() {
        val node = OpenVpnProfile.node(OpenVpnProfile.prepare(ovpn) { "X" }, "Work")
        assertEquals("openvpn", node.protocol)
        assertEquals("vpn.example.com", node.host)
        assertEquals(443, node.port)
        assertEquals("Work", node.name)
        val back = ShareLinks.parse(node.raw)!!
        assertEquals(node.id, back.id)
        assertEquals("Work", back.name)
        assertEquals(true, OpenVpnProfile.decode(node.raw)!!.second.contains("remote vpn.example.com 443 udp"))
    }

    @Test
    fun anOpenVpnProfileNeedsItsFilesAndCannotUseP12() {
        assertTrue(assertFailsWith<IllegalStateException> { SubscriptionParser.parseNodes(ovpn) }.message!!.contains("ca.crt"))
        assertTrue(assertFailsWith<IllegalStateException> { OpenVpnProfile.prepare("pkcs12 x.p12") { null } }.message!!.contains(".p12"))
        assertTrue(assertFailsWith<IllegalStateException> { OpenVpnProfile.node("client\ndev tun\n", "") }.message!!.contains("remote"))
    }

    @Test
    fun aJsonServerKeepsItsNameAfterARestart() {
        val json = """[{"remarks":"Berlin","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"b.example.com","port":443,"users":[{"id":"$uuid","encryption":"none"}]}]}}]}]"""
        val node = SubscriptionParser.parseNodes(json).single()
        val back = ShareLinks.parse(node.raw)!!
        assertEquals("Berlin", back.name)
        assertEquals(node.id, back.id)
        assertEquals(false, ShareLinks.outbound(node.raw, "203.0.113.7", "proxy").has("ember_name"))
    }

    @Test
    fun aSiteRefusingTheServerCountsAsNotWorking() {
        assertEquals(true, SiteResult(200, 120).ok)
        assertEquals(true, SiteResult(401, 90).ok) // an API that wants a key still answered
        assertEquals(false, SiteResult(403, 80).ok)
        assertEquals(false, SiteResult(451, 80).ok)
        assertEquals(false, SiteResult(502, 80).ok)
        assertEquals(false, SiteResult(null, null).ok)
    }

    @Test
    fun readsAWebsiteTheUserTyped() {
        assertEquals("https://open.spotify.com", normalizeSiteUrl("open.spotify.com"))
        assertEquals("https://www.youtube.com/", normalizeSiteUrl("  https://www.youtube.com/ "))
        assertNull(normalizeSiteUrl(""))
        assertNull(normalizeSiteUrl("localhost"))
        assertNull(normalizeSiteUrl("ftp://example.com"))
    }

    @Test
    fun readsABareXrayOutbound() {
        val json = """{"protocol":"vless","tag":"mine","settings":{"vnext":[{"address":"b.example.com","port":443,"users":[{"id":"$uuid","encryption":"none"}]}]}}"""
        val node = SubscriptionParser.parseNodes(json).single()
        assertEquals("b.example.com", node.host)
        assertEquals("mine", node.name)
    }

    @Test
    fun skippedHostnamesGoDirectToTheAddressesLookedUpForThem() {
        val node = ShareLinks.parse("vless://$uuid@h.example.com:443?type=tcp#N")!!
        val config = XrayConfig.single(node, "203.0.113.7", 10808, null, java.io.File("x.log"), false, mapOf("open.spotify.com" to listOf("1.2.3.4")))
        val outbounds = config.getJSONArray("outbounds")
        assertTrue((0 until outbounds.length()).any { outbounds.getJSONObject(it).getString("tag") == "direct" })
        assertEquals("1.2.3.4", config.getJSONObject("dns").getJSONObject("hosts").getJSONArray("full:open.spotify.com").getString(0))
        val first = config.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("full:open.spotify.com", first.getJSONArray("domain").getString(0))
        assertEquals("direct", first.getString("outboundTag"))
        // without a skip list there is no direct outbound and no dns section
        val plain = XrayConfig.single(node, "203.0.113.7", 10808, null, java.io.File("x.log"))
        assertEquals(false, plain.has("dns"))
    }
}
