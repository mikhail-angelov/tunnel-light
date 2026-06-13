package com.bconf.tunnellight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshTunnelLogicTest {

    // ── parseServer ─────────────────────────────────────────────────

    @Test
    fun `parseServer simple user@host`() {
        val r = SshTunnelLogic.parseServer("alice@example.com")
        assertNotNull(r)
        assertEquals("alice", r!!.user)
        assertEquals("example.com", r.host)
        assertEquals(22, r.port)
    }

    @Test
    fun `parseServer with custom port`() {
        val r = SshTunnelLogic.parseServer("root@myserver.org:2222")
        assertNotNull(r)
        assertEquals("root", r!!.user)
        assertEquals("myserver.org", r.host)
        assertEquals(2222, r.port)
    }

    @Test
    fun `parseServer with ipv4`() {
        val r = SshTunnelLogic.parseServer("admin@192.168.1.1:8022")
        assertNotNull(r)
        assertEquals("admin", r!!.user)
        assertEquals("192.168.1.1", r.host)
        assertEquals(8022, r.port)
    }

    @Test
    fun `parseServer missing user returns null`() {
        assertNull(SshTunnelLogic.parseServer("@host.com"))
    }

    @Test
    fun `parseServer missing host returns null`() {
        assertNull(SshTunnelLogic.parseServer("user@"))
    }

    @Test
    fun `parseServer no at-sign returns null`() {
        assertNull(SshTunnelLogic.parseServer("justtext"))
    }

    @Test
    fun `parseServer empty string returns null`() {
        assertNull(SshTunnelLogic.parseServer(""))
    }

    @Test
    fun `parseServer invalid port falls back to 22`() {
        val r = SshTunnelLogic.parseServer("u@h:abc")
        assertNotNull(r)
        assertEquals(22, r!!.port)
    }

    // ── isFatalSshError ─────────────────────────────────────────────

    @Test
    fun `fatal auth fail`() {
        assertTrue(SshTunnelLogic.isFatalSshError("Auth fail"))
    }

    @Test
    fun `fatal auth fail lowercase`() {
        assertTrue(SshTunnelLogic.isFatalSshError("auth fail"))
    }

    @Test
    fun `fatal USERAUTH fail`() {
        assertTrue(SshTunnelLogic.isFatalSshError("USERAUTH fail"))
    }

    @Test
    fun `fatal USERAUTH fail lowercase`() {
        assertTrue(SshTunnelLogic.isFatalSshError("userauth fail"))
    }

    @Test
    fun `fatal invalid privatekey`() {
        assertTrue(SshTunnelLogic.isFatalSshError("invalid privatekey file"))
    }

    @Test
    fun `fatal key was rejected`() {
        assertTrue(SshTunnelLogic.isFatalSshError("key was rejected by the server"))
    }

    @Test
    fun `fatal key was rejected mixed case`() {
        assertTrue(SshTunnelLogic.isFatalSshError("Key was Rejected by the server"))
    }

    @Test
    fun `not fatal connection refused`() {
        assertFalse(SshTunnelLogic.isFatalSshError("Connection refused"))
    }

    @Test
    fun `not fatal timeout`() {
        assertFalse(SshTunnelLogic.isFatalSshError("connect timed out"))
    }

    @Test
    fun `not fatal null message`() {
        assertFalse(SshTunnelLogic.isFatalSshError(null))
    }

    // ── isLikelyTransient ───────────────────────────────────────────

    @Test
    fun `transient connection refused`() {
        assertTrue(SshTunnelLogic.isLikelyTransient("Connection refused"))
    }

    @Test
    fun `transient timeout`() {
        assertTrue(SshTunnelLogic.isLikelyTransient("connect timed out"))
    }

    @Test
    fun `transient econnreset`() {
        assertTrue(SshTunnelLogic.isLikelyTransient("Connection reset by peer (ECONNRESET)"))
    }

    @Test
    fun `transient network unreachable`() {
        assertTrue(SshTunnelLogic.isLikelyTransient("Network is unreachable"))
    }

    @Test
    fun `transient no route`() {
        assertTrue(SshTunnelLogic.isLikelyTransient("No route to host"))
    }

    @Test
    fun `not transient auth fail`() {
        assertFalse(SshTunnelLogic.isLikelyTransient("Auth fail"))
    }

    @Test
    fun `transient null is transient`() {
        assertTrue(SshTunnelLogic.isLikelyTransient(null))
    }

    // ── describeError ───────────────────────────────────────────────

    @Test
    fun `describe auth error`() {
        val msg = SshTunnelLogic.describeError("Auth fail", "srv.io", 1)
        assertTrue(msg.contains("Authentication"))
        assertTrue(msg.contains("key"))
    }

    @Test
    fun `describe connection refused`() {
        val msg = SshTunnelLogic.describeError("Connection refused", "srv.io", 1)
        assertTrue(msg.contains("refused"))
        assertTrue(msg.contains("srv.io"))
    }

    @Test
    fun `describe unknown host via UnknownHostException message format`() {
        // UnknownHostException.message is the bare hostname (JVM behavior)
        val msg = SshTunnelLogic.describeError("srv.io", "srv.io", 1)
        assertTrue(msg.contains("Cannot resolve"))
    }

    @Test
    fun `describe unknown host with colon suffix`() {
        // Some JVM versions produce "hostname: cause"
        val msg = SshTunnelLogic.describeError("srv.io: Name or service not known", "srv.io", 1)
        assertTrue(msg.contains("Cannot resolve"))
    }

    @Test
    fun `describe unknown host legacy UnknownHost string`() {
        val msg = SshTunnelLogic.describeError("UnknownHost: srv.io", "srv.io", 1)
        assertTrue(msg.contains("Cannot resolve"))
    }

    @Test
    fun `describe timeout`() {
        val msg = SshTunnelLogic.describeError("connect timed out", "srv.io", 1)
        assertTrue(msg.contains("timed out"))
    }

    @Test
    fun `describe key exchange`() {
        val msg = SshTunnelLogic.describeError("Key exchange was not finished", "srv.io", 1)
        assertTrue(msg.contains("Key exchange"))
        assertTrue(msg.contains("Ed25519"))
    }

    @Test
    fun `describe unreachable`() {
        val msg = SshTunnelLogic.describeError("Network is unreachable", "srv.io", 1)
        assertTrue(msg.contains("unreachable"))
    }

    @Test
    fun `describe many attempts`() {
        val msg = SshTunnelLogic.describeError("Some transient error", "srv.io", 5)
        assertTrue(msg.contains("5 attempts"))
    }

    @Test
    fun `describe unknown error`() {
        val msg = SshTunnelLogic.describeError("Something weird happened", "srv.io", 1)
        assertTrue(msg.contains("Error"))
    }

    // ── backoff ─────────────────────────────────────────────────────

    @Test
    fun `backoff initial returns 1 second`() {
        val r = SshTunnelLogic.backoff(1)
        assertEquals(1000L, r.delayMs)
        assertEquals(2, r.nextBackoffSec)
    }

    @Test
    fun `backoff doubles`() {
        val r = SshTunnelLogic.backoff(4)
        assertEquals(4000L, r.delayMs)
        assertEquals(8, r.nextBackoffSec)
    }

    @Test
    fun `backoff caps at 60 seconds`() {
        val r = SshTunnelLogic.backoff(60)
        assertEquals(60_000L, r.delayMs)
        assertEquals(60, r.nextBackoffSec)
    }

    @Test
    fun `backoff does not exceed 60`() {
        val r = SshTunnelLogic.backoff(100)
        assertEquals(60_000L, r.delayMs)
        assertEquals(60, r.nextBackoffSec)
    }

    @Test
    fun `backoff sequence`() {
        var sec = 1
        val delays = mutableListOf<Long>()
        repeat(8) {
            val r = SshTunnelLogic.backoff(sec)
            delays.add(r.delayMs)
            sec = r.nextBackoffSec
        }
        assertEquals(60_000L, delays.last())
        // Should cap after ~6 steps: 1, 2, 4, 8, 16, 32, 60, 60
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L), delays)
    }

    // ── getCellularGenerationName ───────────────────────────────────

    @Test
    fun `cell 5G speed`() {
        assertEquals(" (5G)", SshTunnelLogic.getCellularGenerationName(200_000))
    }

    @Test
    fun `cell 4G speed`() {
        assertEquals(" (4G)", SshTunnelLogic.getCellularGenerationName(50_000))
    }

    @Test
    fun `cell 3G speed`() {
        assertEquals(" (3G)", SshTunnelLogic.getCellularGenerationName(5_000))
    }

    @Test
    fun `cell 2G speed`() {
        assertEquals(" (2G)", SshTunnelLogic.getCellularGenerationName(100))
    }

    @Test
    fun `cell zero speed`() {
        assertEquals("", SshTunnelLogic.getCellularGenerationName(0))
    }

    // ── parseServer — chain (jump host) ─────────────────────────────

    @Test
    fun `parse chain with unicode arrow`() {
        val r = SshTunnelLogic.parseServer("user1@jump.com → user2@target.com")
        assertNotNull(r)
        assertEquals("user2", r!!.user)
        assertEquals("target.com", r.host)
        assertEquals(22, r.port)
        assertNotNull(r.jump)
        assertEquals("user1", r.jump!!.user)
        assertEquals("jump.com", r.jump.host)
        assertEquals(22, r.jump.port)
    }

    @Test
    fun `parse chain with ASCII arrow`() {
        val r = SshTunnelLogic.parseServer("u1@jump.io -> u2@target.io:443")
        assertNotNull(r)
        assertEquals("u2", r!!.user)
        assertEquals("target.io", r.host)
        assertEquals(443, r.port)
        assertEquals("u1", r.jump!!.user)
        assertEquals("jump.io", r.jump.host)
        assertEquals(22, r.jump.port)
    }

    @Test
    fun `parse chain with gt arrow`() {
        val r = SshTunnelLogic.parseServer("me@vps.me > root@box.io:2222")
        assertNotNull(r)
        assertEquals("root", r!!.user)
        assertEquals("box.io", r.host)
        assertEquals(2222, r.port)
        assertEquals("me", r.jump!!.user)
        assertEquals("vps.me", r.jump.host)
        assertEquals(22, r.jump.port)
    }

    @Test
    fun `parse chain both with custom ports`() {
        val r = SshTunnelLogic.parseServer("jane@gw.example.com:444 → bob@db.internal:3306")
        assertNotNull(r)
        assertEquals("bob", r!!.user)
        assertEquals("db.internal", r.host)
        assertEquals(3306, r.port)
        assertEquals("jane", r.jump!!.user)
        assertEquals("gw.example.com", r.jump.host)
        assertEquals(444, r.jump.port)
    }

    @Test
    fun `parse chain invalid jump returns null`() {
        assertNull(SshTunnelLogic.parseServer("@jump -> user@target"))
    }

    @Test
    fun `parse chain invalid target returns null`() {
        assertNull(SshTunnelLogic.parseServer("user@jump -> @target"))
    }

    @Test
    fun `parse direct still works same as before`() {
        val r = SshTunnelLogic.parseServer("admin@server.org:8080")
        assertNotNull(r)
        assertNull(r!!.jump)
        assertEquals("admin", r.user)
        assertEquals("server.org", r.host)
        assertEquals(8080, r.port)
    }
}
