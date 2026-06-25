package com.bconf.tunnellight

import java.net.URI
import java.net.URLDecoder

/**
 * Pure functions for SSH tunnel logic — no Android dependencies, fully testable.
 */
object SshTunnelLogic {

    data class XrayConfig(
        val server: String,
        val port: Int,
        val uuid: String,
        val publicKey: String,
        val sni: String,
        val shortId: String,
        val path: String
    )

    data class HostInfo(val user: String, val host: String, val port: Int)

    data class ServerInfo(
        val user: String,
        val host: String,
        val port: Int,
        /** Optional jump host — if set, connect via this host first. */
        val jump: HostInfo? = null
    )

    data class BackoffResult(val delayMs: Long, val nextBackoffSec: Int)

    /**
     * Parse a server address string.
     *
     * Supported formats:
     * - `"user@host"` — direct (default port 22)
     * - `"user@host:port"` — direct with custom port
     * - `"user@jump:port → user@target:port"` — chain via jump host
     * - `"user@jump → user@target"` — chain (default ports 22)
     *
     * The arrow separator can be ` → `, `->`, or `>`.
     */
    fun parseServer(input: String): ServerInfo? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // Try to split by arrow (→) for chain syntax
        val parts = splitChain(trimmed)
        if (parts.size == 2) {
            val jump = parseSingle(parts[0]) ?: return null
            val target = parseSingle(parts[1]) ?: return null
            return ServerInfo(target.user, target.host, target.port, jump)
        }

        // Single host
        val single = parseSingle(trimmed) ?: return null
        return ServerInfo(single.user, single.host, single.port)
    }

    /**
     * Split input by chain separator: ` → `, ` -> `, or ` > `.
     */
    private fun splitChain(input: String): List<String> {
        // Try Unicode arrow first, then ASCII alternatives (all require surrounding spaces)
        val separators = listOf(" → ", " -> ", " > ")
        for (sep in separators) {
            val parts = input.split(sep, limit = 2)
            if (parts.size == 2) return parts
        }
        return listOf(input)
    }

    /**
     * Parse a single "user@host" or "user@host:port".
     */
    private fun parseSingle(input: String): HostInfo? {
        val s = input.trim()
        val atIdx = s.indexOf('@')
        if (atIdx < 1) return null
        val user = s.substring(0, atIdx)
        val hostPart = s.substring(atIdx + 1)
        val colonIdx = hostPart.lastIndexOf(':')
        val host: String
        val port: Int
        if (colonIdx >= 0) {
            host = hostPart.substring(0, colonIdx)
            port = hostPart.substring(colonIdx + 1).toIntOrNull() ?: 22
        } else {
            host = hostPart
            port = 22
        }
        if (host.isEmpty() || user.isEmpty()) return null
        return HostInfo(user, host, port)
    }

    // ── Error classification ────────────────────────────────────────

    /** Returns true if the error is permanent and retrying is pointless (e.g. auth failure). */
    fun isFatalSshError(message: String?): Boolean {
        val msg = message ?: return false
        return msg.contains("Auth fail", ignoreCase = true) ||
                msg.contains("USERAUTH fail", ignoreCase = true) ||
                msg.contains("invalid privatekey", ignoreCase = true) ||
                (msg.contains("key", ignoreCase = true) && msg.contains("rejected", ignoreCase = true))
    }

    /** Returns true if the error is likely caused by network flakiness and retrying makes sense. */
    fun isLikelyTransient(message: String?): Boolean {
        if (message == null) return true
        val m = message.lowercase()
        return m.contains("connection refused") ||
                m.contains("timeout") ||
                m.contains("timed out") ||
                m.contains("econnrefused") ||
                m.contains("econnreset") ||
                m.contains("econnaborted") ||
                m.contains("network is unreachable") ||
                m.contains("no route to host") ||
                m.contains("key exchange") ||
                m.contains("socket is closed")
    }

    /** Returns a human-readable error string suitable for display to the user. */
    fun describeError(message: String?, host: String, attempts: Int): String {
        val msg = message ?: "Unknown error"
        return when {
            msg.contains("Auth fail", ignoreCase = true) ||
            msg.contains("USERAUTH fail", ignoreCase = true) ->
                "Authentication failed \u2014 check username and public key on server"
            msg.contains("Connection refused", ignoreCase = true) ->
                "Connection refused \u2014 is SSH running on $host?"
            msg == host || msg.startsWith("$host:") || msg.contains("UnknownHost", ignoreCase = true) ->
                "Cannot resolve $host \u2014 check server address or DNS"
            msg.contains("timeout", ignoreCase = true) ->
                "Connection timed out \u2014 check server availability"
            msg.contains("Key exchange", ignoreCase = true) ->
                "Key exchange failed \u2014 server may not support Ed25519"
            msg.contains("Network is unreachable", ignoreCase = true) ->
                "Network unreachable \u2014 are you connected to the internet?"
            msg.contains("invalid privatekey", ignoreCase = true) ->
                "Invalid private key \u2014 try regenerating the key"
            attempts >= 5 ->
                "Could not reach $host after $attempts attempts \u2014 verify server address"
            isLikelyTransient(msg) ->
                "Connection failed \u2014 $msg"
            else ->
                "Error \u2014 $msg"
        }
    }

    // ── Exponential backoff ─────────────────────────────────────────

    /** Computes the next backoff delay: starts at 1 s, doubles each call, caps at 60 s. */
    fun backoff(currentBackoffSec: Int): BackoffResult {
        val delayMs = (currentBackoffSec * 1000L).coerceAtMost(60_000L)
        val nextBackoffSec = (currentBackoffSec * 2).coerceAtMost(60)
        return BackoffResult(delayMs, nextBackoffSec)
    }

    // ── Cellular generation ─────────────────────────────────────────

    /** Best-effort cellular generation label (2G/3G/4G/5G) from link downstream speed. */
    fun getCellularGenerationName(downSpeedKbps: Int): String {
        return when {
            downSpeedKbps >= 100_000 -> " (5G)"
            downSpeedKbps >= 20_000 -> " (4G)"
            downSpeedKbps >= 1_000 -> " (3G)"
            downSpeedKbps > 0 -> " (2G)"
            else -> ""
        }
    }

    fun parseVlessUri(input: String): XrayConfig? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("vless", ignoreCase = true)) return null

        val uuid = uri.rawUserInfo?.urlDecode()?.trim().orEmpty()
        val server = uri.host?.trim().orEmpty()
        val port = if (uri.port > 0) uri.port else 443
        val query = parseQuery(uri.rawQuery.orEmpty())

        if (!query["type"].equals("xhttp", ignoreCase = true)) return null
        if (!query["security"].equals("reality", ignoreCase = true)) return null

        return XrayConfig(
            server = server,
            port = port,
            uuid = uuid,
            publicKey = query["pbk"].orEmpty(),
            sni = query["sni"].orEmpty(),
            shortId = query["sid"].orEmpty(),
            path = query["path"].orEmpty()
        )
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isEmpty()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { part ->
                val separator = part.indexOf("=")
                if (separator < 0) {
                    part.urlDecode() to ""
                } else {
                    part.substring(0, separator).urlDecode() to
                        part.substring(separator + 1).urlDecode()
                }
            }
            .toMap()
    }

    private fun String.urlDecode(): String = URLDecoder.decode(this, "UTF-8")
}
