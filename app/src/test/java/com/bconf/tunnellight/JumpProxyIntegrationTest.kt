package com.bconf.tunnellight

import com.jcraft.jsch.JSch
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.net.InetSocketAddress

/**
 * Integration test for SSH jump-host chaining via JumpProxy.
 *
 * Requires two Docker containers started by the Makefile target `test-jump`:
 *   ssh-jump   → localhost:2222
 *   ssh-target → reachable from the jump container as ssh-target:22
 *
 * Run locally:
 *   make test-jump                   # starts containers
 *   ./gradlew testDebugUnitTest \
 *       --tests "*.JumpProxyIntegrationTest" \
 *       -PjumpTestKey=/tmp/ssh-jump-test/test_key \
 *       -PjumpTestTargetIp=<TARGET_IP>
 */
class JumpProxyIntegrationTest {

    private val keyPath   = System.getProperty("jumpTestKey",     "/tmp/ssh-jump-test/test_key")
    private val jumpHost  = System.getProperty("jumpTestJumpHost","localhost")
    private val jumpPort  = System.getProperty("jumpTestJumpPort","2222").toInt()
    private val targetIp  = System.getProperty("jumpTestTargetIp","")
    private val targetPort = System.getProperty("jumpTestTargetPort","22").toInt()
    private val user      = System.getProperty("jumpTestUser",    "tunnel")

    private fun jumpAvailable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(jumpHost, jumpPort), 1000) }
        true
    } catch (_: Exception) { false }

    @Test
    fun `direct single-server connection and command execution`() {
        assumeTrue("jump container not running on $jumpHost:$jumpPort", jumpAvailable())

        val jsch = JSch()
        jsch.addIdentity(keyPath)
        val sess = jsch.getSession(user, jumpHost, jumpPort)
        sess.setConfig("StrictHostKeyChecking", "no")
        try {
            sess.connect(10_000)
            assertTrue("Session must be connected", sess.isConnected)

            val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand("echo hello-direct")
            val out = java.io.ByteArrayOutputStream()
            ch.outputStream = out
            ch.connect(5_000)
            Thread.sleep(300)
            ch.disconnect()
            val reply = out.toString(Charsets.UTF_8).trim()
            assertTrue("Expected 'hello-direct' but got '$reply'", reply == "hello-direct")
        } finally {
            sess.disconnect()
        }
    }

    @Test
    fun `jump proxy connects through jump to target`() {
        assumeTrue("jump container not running on $jumpHost:$jumpPort", jumpAvailable())
        assumeTrue("jumpTestTargetIp system property not set", targetIp.isNotEmpty())

        val jsch = JSch()
        jsch.addIdentity(keyPath)

        // 1. Open session to jump host
        val jumpSess = jsch.getSession(user, jumpHost, jumpPort)
        jumpSess.setConfig("StrictHostKeyChecking", "no")
        jumpSess.connect(10_000)
        assertTrue("Jump session must be connected", jumpSess.isConnected)

        // 2. Connect to target through jump using JumpProxy (same code path as the app)
        val targetSess = jsch.getSession(user, targetIp, targetPort)
        targetSess.setConfig("StrictHostKeyChecking", "no")
        targetSess.setProxy(JumpProxy(jumpSess))
        try {
            targetSess.connect(10_000)
            assertTrue("Target session must be connected via jump", targetSess.isConnected)

            // 3. Run a simple command on the target to prove the tunnel carries traffic
            val ch = targetSess.openChannel("exec")
            ch as com.jcraft.jsch.ChannelExec
            ch.setCommand("echo hello-from-target")
            val out = java.io.ByteArrayOutputStream()
            ch.outputStream = out
            ch.connect(5_000)
            Thread.sleep(500)
            ch.disconnect()
            val reply = out.toString(Charsets.UTF_8).trim()
            assertTrue("Expected 'hello-from-target' but got '$reply'",
                       reply == "hello-from-target")
        } finally {
            targetSess.disconnect()
            jumpSess.disconnect()
        }
    }
}
