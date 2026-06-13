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
import com.jcraft.jsch.SocketFactory as JSchSocketFactory
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
        @Volatile var lastNetworkStatus = ""
    }

    @Volatile private var shouldRun = false

    // ── Network state ────────────────────────────────────────────────
    // wifiNetwork and cellNetwork are the currently active Network objects
    // for each transport type. preferredNetwork() returns wifi > cell.
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var cellNetwork: Network? = null
    @Volatile private var networkAvailable = false

    private fun preferredNetwork(): Network? = wifiNetwork ?: cellNetwork

    private var session: Session? = null
    private var proxyServer: Socks5ProxyServer? = null
    private var connectionThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var backoffSec = 1
    @Volatile private var consecutiveFailures = 0

    // ── Network callbacks ────────────────────────────────────────────
    // One callback per transport type so Android does the classification
    // for us. onAvailable fires with type already guaranteed — no need to
    // call getNetworkCapabilities() here (it returns null before
    // onCapabilitiesChanged fires anyway, which was the stuck-on-
    // "No internet" bug when using a single unfiltered callback).

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val hadNetwork = networkAvailable
            val hadWifi = wifiNetwork != null
            wifiNetwork = network
            networkAvailable = true
            consecutiveFailures = 0
            updateNetworkStatus()
            if (shouldRun) {
                when {
                    !hadNetwork -> {
                        sendStatus("Network available — reconnecting…")
                        connectionThread?.interrupt()
                    }
                    !hadWifi -> {
                        // Cellular was active; switch to the preferred WiFi
                        sendStatus("WiFi available — switching from cellular…")
                        connectionThread?.interrupt()
                    }
                }
            }
        }

        override fun onLost(network: Network) {
            if (network == wifiNetwork) wifiNetwork = null
            networkAvailable = preferredNetwork() != null
            updateNetworkStatus()
            if (shouldRun) {
                if (!networkAvailable) {
                    isRunning = false
                    sendStatus("Network lost — waiting…")
                    updateNotification("Waiting for network…")
                    connectionThread?.interrupt()
                } else {
                    // Cellular still up — reconnect through it
                    sendStatus("WiFi lost — switching to cellular…")
                    connectionThread?.interrupt()
                }
            }
        }
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val hadNetwork = networkAvailable
            cellNetwork = network
            networkAvailable = true
            consecutiveFailures = 0
            updateNetworkStatus()
            // Wake the thread only when coming up from no-network; if WiFi
            // is already active we don't switch to cellular.
            if (shouldRun && !hadNetwork) {
                sendStatus("Network available — reconnecting…")
                connectionThread?.interrupt()
            }
        }

        override fun onLost(network: Network) {
            if (network == cellNetwork) cellNetwork = null
            networkAvailable = preferredNetwork() != null
            updateNetworkStatus()
            // Only interrupt when there's no WiFi fallback
            if (shouldRun && !networkAvailable) {
                isRunning = false
                sendStatus("Network lost — waiting…")
                updateNotification("Waiting for network…")
                connectionThread?.interrupt()
            }
            // WiFi still active: session keeps running, nothing to do
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // Refresh cell generation label (4G/5G/…) when it becomes known
            if (network == cellNetwork) updateNetworkStatus()
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

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
        initNetworkState()

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

                // Capture the preferred network at the moment we begin connecting
                // so the socket is bound to that specific interface.
                val connectVia = preferredNetwork()

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

                    // Bind to the preferred network so the OS routes through
                    // WiFi when both WiFi and cellular are active.
                    if (connectVia != null) {
                        sess.setSocketFactory(NetworkBoundSocketFactory(connectVia))
                    }

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

                    // Session died but shouldRun still true — reconnect
                    if (shouldRun) {
                        isRunning = false
                        sendStatus("Connection lost — reconnecting…")
                        updateNotification("Reconnecting…")
                    }
                } catch (_: InterruptedException) {
                    // Woken by stop(), network callback, or backoff interrupt
                    // Loop re-evaluates shouldRun and networkAvailable
                } catch (e: JSchException) {
                    consecutiveFailures++
                    if (SshTunnelLogic.isFatalSshError(e.message)) {
                        shouldRun = false
                    }
                    if (shouldRun) {
                        val msg = SshTunnelLogic.describeError(e.message, host, consecutiveFailures)
                        sendStatus(msg); updateNotification(msg)
                    }
                } catch (e: UnknownHostException) {
                    consecutiveFailures++
                    if (shouldRun) {
                        val msg = SshTunnelLogic.describeError(e.message, host, consecutiveFailures)
                        sendStatus(msg); updateNotification(msg)
                    }
                } catch (e: SocketTimeoutException) {
                    consecutiveFailures++
                    if (shouldRun) {
                        val msg = SshTunnelLogic.describeError(e.message, host, consecutiveFailures)
                        sendStatus(msg); updateNotification(msg)
                    }
                } catch (e: Exception) {
                    consecutiveFailures++
                    if (shouldRun) {
                        val msg = SshTunnelLogic.describeError(e.message, host, consecutiveFailures)
                        sendStatus(msg); updateNotification(msg)
                    }
                } finally {
                    isRunning = false
                    proxy?.stop()
                    sess?.disconnect()
                    session = null
                    proxyServer = null
                }

                // Backoff sleep before next reconnect attempt
                if (shouldRun && networkAvailable) {
                    val result = SshTunnelLogic.backoff(backoffSec)
                    backoffSec = result.nextBackoffSec
                    try { Thread.sleep(result.delayMs) } catch (_: InterruptedException) { }
                }
            }

            stopSelf()
        }.also { it.start() }

        return START_REDELIVER_INTENT
    }

    // ── Network state helpers ─────────────────────────────────────────

    private fun initNetworkState() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifiNetwork = network
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellNetwork = network
                }
            }
        }
        networkAvailable = preferredNetwork() != null
        updateNetworkStatus()
    }

    private fun waitForNetwork() {
        while (shouldRun && !networkAvailable) {
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { /* re-check condition */ }
        }
    }

    private fun updateNetworkStatus() {
        val wifi = wifiNetwork
        val cell = cellNetwork
        lastNetworkStatus = when {
            wifi != null && cell != null ->
                "📶 WiFi • 📡 Cell${getCellGeneration(cell)}"
            wifi != null -> "📶 WiFi"
            cell != null -> "📡 Cell${getCellGeneration(cell)}"
            else -> "⛔ No internet"
        }
    }

    private fun getCellGeneration(network: Network): String = runCatching {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching ""
        SshTunnelLogic.getCellularGenerationName(caps.linkDownstreamBandwidthKbps)
    }.getOrDefault("")

    // ── Network callback registration ─────────────────────────────────

    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                wifiCallback
            )
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build(),
                cellCallback
            )
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        runCatching { cm.unregisterNetworkCallback(cellCallback) }
    }

    // ── Locks ─────────────────────────────────────────────────────────

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

    // ── Broadcast & Notification ───────────────────────────────────────

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
            .setSmallIcon(R.drawable.ic_tunnel_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    // ── Standard overrides ─────────────────────────────────────────────

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

// ── Socket factory bound to a specific Android Network ────────────────
// JSch uses this to open its TCP connection through the chosen interface
// (WiFi or cellular) rather than letting the OS pick arbitrarily.

private class NetworkBoundSocketFactory(
    private val network: Network
) : JSchSocketFactory {
    override fun createSocket(host: String, port: Int): Socket =
        network.socketFactory.createSocket(host, port)
    override fun getInputStream(socket: Socket): InputStream = socket.inputStream
    override fun getOutputStream(socket: Socket): OutputStream = socket.outputStream
}

// ── SOCKS5 proxy server (RFC 1928) ────────────────────────────────────

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
            while (!stopped.get()) {
                val n = src.read(buf)
                if (n == -1) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) { }
    }
}
