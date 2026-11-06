package ember.subs

import java.util.Base64

/**
 * An OpenVPN `.ovpn` profile as Ember keeps it: one self-contained text (certificates and keys inside), cleaned of the
 * options that would run programs on the computer, because OpenVPN is started with administrator rights.
 */
object OpenVpnProfile {

    /** Stored form of a server: `ovpn:<name, base64>:<profile, base64>`. */
    const val PREFIX = "ovpn:"

    /** Options that load code or run programs, take over OpenVPN's own control channel or logging, or pull in other files. */
    private val FORBIDDEN = setOf(
        "plugin", "up", "down", "route-up", "route-pre-down", "ipchange", "learn-address", "client-connect",
        "client-disconnect", "tls-verify", "auth-user-pass-verify", "up-restart", "down-pre", "script-security",
        "log", "log-append", "daemon", "service", "writepid", "config", "cd", "chroot", "syslog", "askpass",
        "pkcs11-providers", "engine", "management",
    )

    /** `directive file` options whose file is moved inside the profile as `<directive>…</directive>`. */
    private val FILE_OPTIONS = setOf("ca", "cert", "key", "dh", "tls-auth", "tls-crypt", "tls-crypt-v2", "crl-verify", "extra-certs")

    /**
     * Returns [text] cleaned for use. Files the profile points to are read with [readFile] (given the name as written)
     * and put inside; a missing file is an error that names it.
     */
    fun prepare(text: String, readFile: (String) -> String?): String {
        val out = StringBuilder()
        var block: String? = null
        var dropping = false
        for (line in text.removePrefix("﻿").lines()) {
            val trimmed = line.trim()
            val open = block
            if (open != null) {
                if (!dropping) out.append(line).append('\n')
                if (trimmed.equals("</$open>", ignoreCase = true)) { block = null; dropping = false }
                continue
            }
            if (trimmed.startsWith("<") && trimmed.endsWith(">") && !trimmed.startsWith("</")) {
                block = trimmed.trim('<', '>').lowercase()
                dropping = block in FORBIDDEN
                out.append(if (dropping) "# removed <$block>\n" else line + "\n")
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                out.append(line).append('\n')
                continue
            }
            val tokens = trimmed.split(Regex("\\s+"))
            val directive = tokens[0].removePrefix("--").lowercase()
            when {
                directive in FORBIDDEN || directive.startsWith("management") -> out.append("# removed: $directive\n")
                directive == "pkcs12" -> error("The OpenVPN profile uses a .p12 certificate bundle, which Ember cannot read yet. Export the certificate and key as text (.crt and .key) or ask your provider for an embedded profile.")
                // Credentials are asked for when connecting; a file of them next to the profile is not read.
                directive == "auth-user-pass" -> out.append("auth-user-pass\n")
                directive in FILE_OPTIONS && tokens.size >= 2 && tokens[1] != "[inline]" -> {
                    val name = tokens[1].trim('"', '\'')
                    val content = readFile(name)?.trim()
                        ?: error("The OpenVPN profile uses the file \"$name\". Add the profile with the file button so Ember can read the files next to it.")
                    out.append("<$directive>\n").append(content).append("\n</$directive>\n")
                    if (directive == "tls-auth" && tokens.size >= 3) out.append("key-direction ${tokens[2]}\n")
                }
                else -> out.append(line).append('\n')
            }
        }
        return out.toString().trimEnd() + "\n"
    }

    /** Directives of the profile (name lower-cased, then its arguments), leaving out comments and certificate blocks. */
    private fun directives(profile: String): List<List<String>> {
        var block: String? = null
        val result = mutableListOf<List<String>>()
        for (line in profile.lines()) {
            val trimmed = line.trim()
            val open = block
            if (open != null) {
                if (trimmed.equals("</$open>", ignoreCase = true)) block = null
            } else if (trimmed.startsWith("<") && trimmed.endsWith(">") && !trimmed.startsWith("</")) {
                block = trimmed.trim('<', '>').lowercase()
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith(";")) {
                val tokens = trimmed.split(Regex("\\s+"))
                result += listOf(tokens[0].removePrefix("--").lowercase()) + tokens.drop(1)
            }
        }
        return result
    }

    /** The server names or addresses of every `remote` line. */
    fun remotes(profile: String): List<String> = directives(profile).filter { it[0] == "remote" && it.size >= 2 }.map { it[1] }.distinct()

    /**
     * [profile] with each `remote` host replaced by the address in [addresses]. Used while the kill switch is on, which
     * only lets those addresses through, so OpenVPN must not need a name lookup of its own.
     */
    fun pinRemotes(profile: String, addresses: Map<String, String>): String {
        var block: String? = null
        return profile.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            val open = block
            when {
                open != null -> {
                    if (trimmed.equals("</$open>", ignoreCase = true)) block = null
                    line
                }
                trimmed.startsWith("<") && trimmed.endsWith(">") && !trimmed.startsWith("</") -> {
                    block = trimmed.trim('<', '>').lowercase()
                    line
                }
                else -> {
                    val tokens = trimmed.split(Regex("\\s+"))
                    val ip = if (tokens.size >= 2 && tokens[0].equals("remote", ignoreCase = true)) addresses[tokens[1]] else null
                    if (ip != null) (listOf("remote", ip) + tokens.drop(2)).joinToString(" ") else line
                }
            }
        }
    }

    /** True when connecting needs a user name and password from the user. */
    fun needsCredentials(profile: String): Boolean = directives(profile).any { it[0] == "auth-user-pass" }

    /** The server for [text]: its first `remote` line names the host and port. [nameHint] (the file name) names it. */
    fun node(text: String, nameHint: String): ProxyNode {
        val profile = prepare(text) { null }
        val lines = directives(profile)
        val remote = lines.firstOrNull { it[0] == "remote" && it.size >= 2 }
            ?: error("The OpenVPN profile has no remote server line.")
        val port = remote.getOrNull(2)?.toIntOrNull()
            ?: lines.firstOrNull { it[0] == "port" }?.getOrNull(1)?.toIntOrNull()
            ?: 1194
        val host = remote[1]
        return ShareLinks.node(encode(nameHint.ifBlank { host }, profile), "openvpn", nameHint.ifBlank { host }, host, port)
    }

    fun encode(name: String, profile: String): String {
        val b64 = Base64.getEncoder()
        return PREFIX + b64.encodeToString(name.toByteArray()) + ":" + b64.encodeToString(profile.toByteArray())
    }

    /** Rebuilds a stored server (same raw text, so the same id). */
    fun fromRaw(raw: String): ProxyNode? {
        val (name, profile) = decode(raw) ?: return null
        val remote = directives(profile).firstOrNull { it[0] == "remote" && it.size >= 2 } ?: return null
        val port = remote.getOrNull(2)?.toIntOrNull()
            ?: directives(profile).firstOrNull { it[0] == "port" }?.getOrNull(1)?.toIntOrNull() ?: 1194
        return ShareLinks.node(raw, "openvpn", name, remote[1], port)
    }

    /** The name and profile text inside a stored server. */
    fun decode(raw: String): Pair<String, String>? = runCatching {
        val parts = raw.removePrefix(PREFIX).split(':')
        val b64 = Base64.getDecoder()
        String(b64.decode(parts[0])) to String(b64.decode(parts[1]))
    }.getOrNull()
}
