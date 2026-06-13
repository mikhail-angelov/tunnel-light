package com.bconf.tunnellight

/**
 * Pure functions for SSH tunnel logic — no Android dependencies, fully testable.
 */
object SshTunnelLogic {

    data class ServerInfo(val user: String, val host: String, val port: Int)

    data class BackoffResult(val delayMs: Long, val nextBackoffSec: Int)

    /**
     * Parse "user@host" or "user@host:port" into its components.
     */
    fun parseServer(input: String): ServerInfo? {
        val atIdx = input.indexOf('@')
        if (atIdx < 1) return null
        val user = input.substring(0, atIdx)
        val hostPart = input.substring(atIdx + 1)
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
        return ServerInfo(user, host, port)
    }

    /**
     * Errors that should stop the tunnel rather than retry.
     */
    fun isFatalSshError(message: String?): Boolean {
        val msg = message ?: return false
        return msg.contains("Auth fail") ||
                msg.contains("USERAUTH fail") ||
                msg.contains("invalid privatekey") ||
                msg.contains("invalid privatekey file") ||
                (msg.contains("key") && msg.contains("rejected"))
    }

    /**
     * Errors that are likely transient (network flakiness, server busy, etc.)
     * and justify an automatic retry.
     */
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

    /**
     * Human-readable error description for the user.
     *
     * @param message the raw error message (or null)
     * @param host the server hostname being connected to
     * @param attempts number of consecutive failed attempts so far
     */
    fun describeError(message: String?, host: String, attempts: Int): String {
        val msg = message ?: "Unknown error"
        return when {
            msg.contains("Auth fail", ignoreCase = true) ||
            msg.contains("USERAUTH fail", ignoreCase = true) ->
                "Authentication failed \u2014 check username and public key on server"
            msg.contains("Connection refused", ignoreCase = true) ->
                "Connection refused \u2014 is SSH running on $host?"
            msg.contains("UnknownHost", ignoreCase = true) ->
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

    /**
     * Compute next exponential backoff.
     * Starts at 1 second, doubles each call, caps at 60 seconds.
     */
    fun backoff(currentBackoffSec: Int): BackoffResult {
        val delayMs = (currentBackoffSec * 1000L).coerceAtMost(60_000L)
        val nextBackoffSec = (currentBackoffSec * 2).coerceAtMost(60)
        return BackoffResult(delayMs, nextBackoffSec)
    }

    /**
     * Best-effort cellular generation label from link downstream speed.
     */
    fun getCellularGenerationName(downSpeedKbps: Int): String {
        return when {
            downSpeedKbps >= 100_000 -> " (5G)"
            downSpeedKbps >= 20_000 -> " (4G)"
            downSpeedKbps >= 1_000 -> " (3G)"
            downSpeedKbps > 0 -> " (2G)"
            else -> ""
        }
    }
}
