package com.bconf.tunnellight

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.ServiceCompat
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

class SshTunnelService : Service() {

    companion object {
        const val ACTION_STATUS = "com.bconf.tunnellight.STATUS"
        const val EXTRA_STATUS = "status"
        @Volatile var isRunning = false
        @Volatile var lastStatus = ""
    }

    @Volatile private var shouldRun = false
    @Volatile private var networkAvailable = true
    private var session: Session? = null
    private var proxyServer: Socks5ProxyServer? = null
    private var connectionThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var backoffSec = 1
    private var consecutiveFailures = 0

    // ── Network callback ────────────────────────────────────────────

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val wasDown = !networkAvailable
            networkAvailable = true
            consecutiveFailures = 0 // reset backoff when network reappears
            if (wasDown && shouldRun) {
                sendStatus("Network available — reconnecting…")
                connectionThread?.interrupt()
            }
        }

        override fun onLost(network: Network) {
            networkAvailable = false
            if (shouldRun) {
                isRunning = false
                sendStatus("Network lost — waiting…")
                updateNotification("Waiting for network…")
                connectionThread?.interrupt()
            }
        }

        override fun onUnavailable() {
            networkAvailable = false
            if (shouldRun) {
                sendStatus("No network available — waiting…")
                connectionThread?.interrupt()
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "ssh", "SSH Tunnel", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra("host") ?: run { stopSelf(); return START_NOT_STICKY }
        val user = intent.getStringExtra("user") ?: run { stopSelf(); return START_NOT_STICKY }
        val port = intent.getIntExtra("port", 22)

        if (shouldRun) return START_REDELIVER_INTENT

        shouldRun = true
        isRunning = false
        backoffSec = 1
        consecutiveFailures = 0
        networkAvailable = true

        acquireLocks()
        registerNetworkCallback()

        updateNotification("Connecting to $host…")
        sendStatus("Connecting to $host…")

        connectionThread = Thread {
            val keyFile = File(filesDir, "id_ed25519")
            var firstAttempt = true

            while (shouldRun) {
                // Guard: no network → wait until it comes back
                if (!networkAvailable && shouldRun) {
                    updateNotification("Waiting for network…")
                    sendStatus("Network unavailable — waiting…")
                    waitForNetwork()
                    if (!shouldRun) break
                }

                var sess: Session? = null
                var proxy: Socks5ProxyServer? = null
                try {
                    if (firstAttempt) {
                        sendStatus("Connecting to $host…")
                    }
                    firstAttempt = false

                    val jsch = JSch()
                    jsch.addIdentity(keyFile.absolutePath)

                    sess = jsch.getSession(user, host, port)
                    sess.setConfig("StrictHostKeyChecking", "no")
                    sess.setConfig("TCPKeepAlive", "yes")
                    sess.setConfig("ServerAliveInterval", "20")
                    sess.setConfig("ServerAliveCountMax", "3")
                    sess.connect(15_000)

                    session = sess
                    proxy = Socks5ProxyServer(sess)
                    proxyServer = proxy
                    proxy.start()

                    isRunning = true
                    consecutiveFailures = 0
                    backoffSec = 1
                    updateNotification("Connected — SOCKS5 on 127.0.0.1:1080")
                    sendStatus("Connected — SOCKS5 on 127.0.0.1:1080")

                    // Block until the session drops or we're asked to stop
                    while (shouldRun && sess.isConnected()) {
                        Thread.sleep(3_000)
                    }

                    // If we get here the session died but we should keep trying
                    if (shouldRun) {
                        isRunning = false
                        sendStatus("Connection lost — reconnecting…")
                        updateNotification("Reconnecting…")
                    }
                } catch (_: InterruptedException) {
                    // Woken by stop(), network callback, or backoff interrupt
                    // Loop re-evaluates shouldRun and networkAvailable
                } catch (e: JSchException) {
                    handleConnectionError(e, host)
                    if (isFatalSshError(e)) {
                        sendStatus("Fatal: ${e.message} — stopping")
                        updateNotification("Error: authentication failed")
                        shouldRun = false
                    }
                } catch (e: UnknownHostException) {
                    handleConnectionError(e, host)
                } catch (e: SocketTimeoutException) {
                    handleConnectionError(e, host)
                } catch (e: Exception) {
                    handleConnectionError(e, host)
                } finally {
                    isRunning = false
                    proxy?.stop()
                    sess?.disconnect()
                    session = null
                    proxyServer = null
                }

                // Backoff sleep before next reconnect attempt
                if (shouldRun && networkAvailable) {
                    val delay = backoffMs()
                    try { Thread.sleep(delay) } catch (_: InterruptedException) { }
                }
            }

            stopSelf()
        }.also { it.start() }

        return START_REDELIVER_INTENT
    }

    // ── Error classification ────────────────────────────────────────

    private fun isFatalSshError(e: JSchException): Boolean {
        val msg = e.message ?: ""
        return msg.contains("Auth fail") ||
               msg.contains("USERAUTH fail") ||
               msg.contains("invalid privatekey") ||
               msg.contains("invalid privatekey file") ||
               (msg.contains("key") && msg.contains("rejected"))
    }

    private fun isLikelyTransient(message: String?): Boolean {
        if (message == null) return true
        val m = message.lowercase()
        return m.contains("connection refused") ||
               m.contains("timeout") ||
               m.contains("econnrefused") ||
               m.contains("econnreset") ||
               m.contains("econnaborted") ||
               m.contains("network is unreachable") ||
               m.contains("no route to host") ||
               m.contains("key exchange") ||
               m.contains("socket is closed")
    }

    // ── Human-readable status ───────────────────────────────────────

    private fun describeError(e: Exception, host: String): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("Auth fail", ignoreCase = true) ||
            msg.contains("USERAUTH fail", ignoreCase = true) ->
                "Authentication failed — check username and public key on server"
            msg.contains("Connection refused", ignoreCase = true) ->
                "Connection refused — is SSH running on $host?"
            msg.contains("UnknownHost", ignoreCase = true) ||
            e is UnknownHostException ->
                "Cannot resolve $host — check server address or DNS"
            msg.contains("timeout", ignoreCase = true) ->
                "Connection timed out — check server availability"
            msg.contains("Key exchange", ignoreCase = true) ->
                "Key exchange failed — server may not support Ed25519"
            msg.contains("Network is unreachable", ignoreCase = true) ->
                "Network unreachable — are you connected to the internet?"
            msg.contains("invalid privatekey", ignoreCase = true) ->
                "Invalid private key — try regenerating the key"
            consecutiveFailures >= 5 ->
                "Could not reach $host after $consecutiveFailures attempts — verify server address"
            else -> {
                val short = msg.take(100)
                if (isLikelyTransient(msg)) "Connection failed — $short"
                else "Error — $short"
            }
        }
    }

    private fun handleConnectionError(e: Exception, host: String) {
        consecutiveFailures++
        val text = describeError(e, host)
        sendStatus(text)
        updateNotification(text)
    }

    // ── Exponential backoff (1s → 2s → 4s → … → 60s cap) ──────────

    private fun backoffMs(): Long {
        val wait = (backoffSec * 1000L).coerceAtMost(60_000L)
        backoffSec = (backoffSec * 2).coerceAtMost(60)
        return wait
    }

    private fun waitForNetwork() {
        while (shouldRun && !networkAvailable) {
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { return }
        }
    }

    // ── Network callback registration ───────────────────────────────

    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
        }
    }

    private fun unregisterNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        }
    }

    // ── Locks ───────────────────────────────────────────────────────

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TunnelLight::SSH")
            .also { it.acquire() }

        val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(wifiLockMode, "TunnelLight::WiFi")
            .also { it.acquire() }
    }

    private fun releaseLocks() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        }
    }

    // ── Broadcast & Notification ────────────────────────────────────

    private fun sendStatus(message: String) {
        lastStatus = message
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, message))
    }

    private fun updateNotification(message: String) {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, "ssh")
            .setContentTitle("SSH Tunnel")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    // ── Standard overrides ──────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shouldRun = false
        isRunning = false
        lastStatus = ""
        connectionThread?.interrupt()
        proxyServer?.stop()
        session?.disconnect()
        unregisterNetworkCallback()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

// ── SOCKS5 proxy server (RFC 1928) ───────────────────────────────────

private class Socks5ProxyServer(
    private val session: Session,
    private val listenPort: Int = 1080
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        val ss = ServerSocket(listenPort, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        Thread {
            while (running) {
                try {
                    val client = ss.accept()
                    Thread { handleClient(client) }.start()
                } catch (e: Exception) {
                    if (running) e.printStackTrace()
                }
            }
        }.start()
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
    }

    private fun handleClient(client: Socket) {
        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            // ── SOCKS5 handshake ──
            if (inp.read() != 5) { client.close(); return }
            val nMethods = inp.read()
            repeat(nMethods) { inp.read() }
            out.write(byteArrayOf(5, 0)) // no auth
            out.flush()

            // ── Request ──
            inp.read() // ver
            val cmd = inp.read()
            inp.read() // rsv
            val atype = inp.read()

            val targetHost = when (atype) {
                1 -> {
                    val b = ByteArray(4); inp.read(b)
                    b.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                3 -> {
                    val len = inp.read()
                    val b = ByteArray(len); inp.read(b)
                    String(b)
                }
                else -> {
                    out.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                    client.close(); return
                }
            }
            val targetPort = (inp.read() shl 8) or inp.read()

            if (cmd != 1) { // only CONNECT is supported
                out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                client.close(); return
            }

            // ── Open SSH channel ──
            val ch = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            ch.setHost(targetHost)
            ch.setPort(targetPort)
            ch.setOrgIPAddress("127.0.0.1")
            ch.setOrgPort(listenPort)

            try {
                ch.connect(10_000)
            } catch (e: Exception) {
                out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                client.close(); return
            }

            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()

            // ── Bidirectional pump ──
            val chIn = ch.inputStream
            val chOut = ch.outputStream
            val stopped = AtomicBoolean(false)
            val t = Thread { pump(inp, chOut, stopped) }
            t.start()
            pump(chIn, out, stopped)
            stopped.set(true)
            t.join(1000)
            ch.disconnect()
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun pump(src: InputStream, dst: OutputStream, stopped: AtomicBoolean) {
        try {
            val buf = ByteArray(8192)
            var n: Int
            while (!stopped.get() && src.read(buf).also { n = it } != -1) {
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) { }
    }
}
