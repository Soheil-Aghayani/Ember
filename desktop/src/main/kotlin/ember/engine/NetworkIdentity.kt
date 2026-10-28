package ember.engine

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Names the network this computer is on, so Ember can tell a trusted network (home) from an untrusted one (a cafe).
 * Wi-Fi networks are identified by their name (SSID). Anything wired is simply "wired".
 */
object NetworkIdentity {

    /** "wifi:<name>", "wired", or null when the computer looks offline. */
    fun current(): String? {
        wifiName()?.let { return "wifi:$it" }
        return if (hasWiredLink()) "wired" else null
    }

    /** The user-facing name of an id from [current], for example the Wi-Fi name. */
    fun displayName(id: String): String = if (id.startsWith("wifi:")) id.removePrefix("wifi:") else "Wired network"

    private fun wifiName(): String? = runCatching {
        val process = ProcessBuilder("netsh.exe", "wlan", "show", "interfaces").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(5, TimeUnit.SECONDS)
        // "SSID" (not "BSSID") is only listed while the adapter is connected, and the label is not translated.
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("SSID") && !it.startsWith("SSID name") && it.substringBefore(':').trim() == "SSID" }
            ?.substringAfter(':', "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun hasWiredLink(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { ni ->
            ni.isUp && !ni.isLoopback && !ni.isVirtual &&
                listOf("vethernet", "hyper-v", "vmware", "virtualbox", "wintun", "loopback", "bluetooth", "wi-fi", "wireless", "wlan")
                    .none { ni.displayName.contains(it, ignoreCase = true) } &&
                ni.inetAddresses.toList().any { it is Inet4Address && !it.isLinkLocalAddress && !it.hostAddress.startsWith("10.8.0.") }
        }
    }.getOrDefault(false)
}
