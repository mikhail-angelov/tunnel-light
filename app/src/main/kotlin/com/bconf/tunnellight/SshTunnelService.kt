package com.bconf.tunnellight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.ServiceCompat
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import kotlin.math.min

class SshTunnelService : Service() {

    companion object {
        const val ACTION_STATUS = "com.bconf.tunnellight.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LOGS = "logs"

        const val EXTRA_SERVER_ADDRESS = "server_address"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_UUID = "uuid"
        const val EXTRA_PUBLIC_KEY = "public_key"
        const val EXTRA_SNI = "sni"
        const val EXTRA_SHORT_ID = "short_id"
        const val EXTRA_XHTTP_PATH = "xhttp_path"

        @Volatile var isRunning = false
        @Volatile var isActive = false
        @Volatile var lastStatus = ""
        @Volatile var lastNetworkStatus = ""
        @Volatile var lastLogs = ""

        private const val CHANNEL_ID = "xray"
        private const val LOCAL_SOCKS_PORT = 1080
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val MAX_LOG_LINES = 250
        private val logLines = ArrayDeque<String>()
    }

    @Volatile private var shouldRun = false
    @Volatile private var controller: CoreController? = null
    private var workerThread: Thread? = null

    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var cellNetwork: Network? = null
    @Volatile private var networkAvailable = false
    @Volatile private var networkGoodEnough = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            onNetworkChanged()
        }

        override fun onLost(network: Network) {
            if (network == wifiNetwork) wifiNetwork = null
            onNetworkChanged()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == wifiNetwork) onNetworkChanged()
        }
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cellNetwork = network
            onNetworkChanged()
        }

        override fun onLost(network: Network) {
            if (network == cellNetwork) cellNetwork = null
            onNetworkChanged()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == cellNetwork) onNetworkChanged()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tunnel Light",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (shouldRun) return START_REDELIVER_INTENT

        val config = readConfig(intent) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        shouldRun = true
        isActive = true
        isRunning = false
        appendLog("Service starting")

        initNetworkState()
        registerNetworkCallback()
        acquireLocks()

        sendStatus("Connecting to ${config.server}:${config.port}")
        updateNotification("Connecting to ${config.server}:${config.port}")

        workerThread = Thread {
            var reconnectAttempt = 0
            try {
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                val version = Libv2ray.checkVersionX()
                Log.i("XrayService", version)
                appendLog(version)

                while (shouldRun) {
                    waitForGoodNetwork()
                    if (!shouldRun) break

                    sendStatus("Connecting to ${config.server}:${config.port}")
                    updateNotification("Connecting to ${config.server}:${config.port}")

                    val core = newCoreController()
                    controller = core

                    try {
                        core.startLoop(buildXrayConfig(config), 0)
                        reconnectAttempt = 0

                        while (shouldRun && core.isRunning && networkGoodEnough) {
                            Thread.sleep(1_000)
                        }
                    } catch (_: InterruptedException) {
                        // Re-check shouldRun/network state below.
                    } catch (e: Exception) {
                        Log.e("XrayService", "Xray failed", e)
                        sendStatus("Error: ${e.message ?: e.javaClass.simpleName}")
                        updateNotification("Error; reconnecting")
                    } finally {
                        stopCore()
                        isRunning = false
                    }

                    if (!shouldRun) break

                    if (!networkGoodEnough) {
                        sendStatus("Network weak; waiting to reconnect")
                        updateNotification("Waiting for better network")
                        continue
                    }

                    reconnectAttempt += 1
                    val delay = min(1_000L shl min(reconnectAttempt - 1, 5), MAX_RECONNECT_DELAY_MS)
                    sendStatus("Disconnected; reconnecting in ${delay / 1_000}s")
                    updateNotification("Reconnecting in ${delay / 1_000}s")
                    sleepInterruptibly(delay)
                }
            } catch (_: InterruptedException) {
                // Stop or network transition.
            } catch (e: Exception) {
                Log.e("XrayService", "Xray failed", e)
                sendStatus("Error: ${e.message ?: e.javaClass.simpleName}")
                updateNotification("Error")
            } finally {
                stopCore()
                isRunning = false
                isActive = false
                if (!shouldRun && lastStatus.isNotEmpty() && !lastStatus.startsWith("Error")) {
                    sendStatus("Disconnected")
                }
                stopSelf()
            }
        }.also { it.start() }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        shouldRun = false
        appendLog("Service stopping")
        workerThread?.interrupt()
        stopCore()
        isRunning = false
        isActive = false
        lastStatus = ""
        unregisterNetworkCallback()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun readConfig(intent: Intent?): XrayConfig? {
        val server = intent?.getStringExtra(EXTRA_SERVER_ADDRESS)?.trim().orEmpty()
        val port = intent?.getIntExtra(EXTRA_SERVER_PORT, 443) ?: 443
        val uuid = intent?.getStringExtra(EXTRA_UUID)?.trim().orEmpty()
        val publicKey = intent?.getStringExtra(EXTRA_PUBLIC_KEY)?.trim().orEmpty()
        val sni = intent?.getStringExtra(EXTRA_SNI)?.trim().orEmpty()
        val shortId = intent?.getStringExtra(EXTRA_SHORT_ID)?.trim().orEmpty()
        val path = intent?.getStringExtra(EXTRA_XHTTP_PATH)?.trim().orEmpty()
        if (server.isEmpty() || uuid.isEmpty() || publicKey.isEmpty() || sni.isEmpty() || path.isEmpty()) {
            sendStatus("Error: missing Xray config")
            return null
        }
        return XrayConfig(server, port, uuid, publicKey, sni, shortId, path)
    }

    private fun buildXrayConfig(config: XrayConfig): String {
        return """
            {
              "log": { "loglevel": "warning" },
              "inbounds": [
                {
                  "tag": "local-socks",
                  "listen": "127.0.0.1",
                  "port": $LOCAL_SOCKS_PORT,
                  "protocol": "socks",
                  "settings": {
                    "auth": "noauth",
                    "udp": true
                  }
                }
              ],
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      {
                        "address": "${config.server.json()}",
                        "port": ${config.port},
                        "users": [
                          {
                            "id": "${config.uuid.json()}",
                            "encryption": "none"
                          }
                        ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "xhttp",
                    "xhttpSettings": {
                      "path": "${config.path.json()}",
                      "mode": "auto"
                    },
                    "security": "reality",
                    "realitySettings": {
                      "serverName": "${config.sni.json()}",
                      "publicKey": "${config.publicKey.json()}",
                      "fingerprint": "chrome",
                      "shortId": "${config.shortId.json()}"
                    }
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private fun stopCore() {
        val core = controller ?: return
        controller = null
        runCatching { core.stopLoop() }
            .onFailure { Log.e("XrayService", "Error stopping Xray", it) }
    }

    private fun newCoreController(): CoreController {
        return Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long {
                isRunning = true
                sendStatus("Running (SOCKS5 127.0.0.1:$LOCAL_SOCKS_PORT)")
                updateNotification("SOCKS5 on 127.0.0.1:$LOCAL_SOCKS_PORT")
                return 0
            }

            override fun shutdown(): Long {
                isRunning = false
                sendStatus("Disconnected")
                return 0
            }

            override fun onEmitStatus(code: Long, message: String): Long {
                Log.i("XrayService", "core status $code $message")
                appendLog("Core status $code: $message")
                return 0
            }
        })
    }

    private fun preferredNetwork(): Network? = wifiNetwork ?: cellNetwork

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

    private fun waitForGoodNetwork() {
        while (shouldRun) {
            updateNetworkStatus()
            if (networkGoodEnough) return

            val status = if (networkAvailable) {
                "Network weak: $lastNetworkStatus"
            } else {
                "Network unavailable; waiting"
            }
            sendStatus(status)
            updateNotification("Waiting for network")
            sleepInterruptibly(1_000)
        }
    }

    private fun onNetworkChanged() {
        val previousStatus = lastNetworkStatus
        updateNetworkStatus()
        if (!shouldRun) return

        if (previousStatus != lastNetworkStatus) {
            appendLog("Network changed: $lastNetworkStatus")
        }
        sendStatus(lastNetworkStatus)

        if (!networkGoodEnough) {
            updateNotification("Waiting for better network")
            stopCore()
        } else if (!isRunning) {
            updateNotification("Network ready; reconnecting")
        }
    }

    private fun updateNetworkStatus() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = wifiNetwork
        val cell = cellNetwork

        val wifiQuality = wifi?.let { networkQuality(cm, it, "WiFi") }
        val cellQuality = cell?.let { networkQuality(cm, it, "Cell") }
        val bestQuality = listOfNotNull(wifiQuality, cellQuality).maxByOrNull { it.score }

        networkAvailable = bestQuality != null
        networkGoodEnough = bestQuality?.goodEnough == true
        lastNetworkStatus = when {
            wifiQuality != null && cellQuality != null ->
                "${wifiQuality.label} ${wifiQuality.summary}; ${cellQuality.label} ${cellQuality.summary}"
            wifiQuality != null -> "${wifiQuality.label} ${wifiQuality.summary}"
            cellQuality != null -> "${cellQuality.label} ${cellQuality.summary}"
            else -> "No internet"
        }
    }

    private fun networkQuality(
        cm: ConnectivityManager,
        network: Network,
        label: String
    ): NetworkQuality {
        val caps = cm.getNetworkCapabilities(network)
            ?: return NetworkQuality(label, false, 0, "lost")

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val notSuspended = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        val downKbps = caps.linkDownstreamBandwidthKbps
        val upKbps = caps.linkUpstreamBandwidthKbps
        val isCell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val verySlowCell = isCell && downKbps in 1 until 64
        val goodEnough = hasInternet && notSuspended && !verySlowCell
        val score = listOf(
            if (hasInternet) 10 else 0,
            if (validated) 10 else 0,
            if (notSuspended) 5 else 0,
            min(downKbps / 512, 10),
            min(upKbps / 128, 5)
        ).sum()
        val flags = mutableListOf<String>()
        if (validated) flags += "validated" else flags += "unvalidated"
        if (!hasInternet) flags += "no internet"
        if (!notSuspended) flags += "suspended"
        if (verySlowCell) flags += "slow"
        if (downKbps > 0 || upKbps > 0) flags += "${downKbps}/${upKbps}kbps"

        return NetworkQuality(
            label = label,
            goodEnough = goodEnough,
            score = score,
            summary = if (goodEnough) "ok (${flags.joinToString(", ")})" else "weak (${flags.joinToString(", ")})"
        )
    }

    private fun sleepInterruptibly(delayMs: Long) {
        val endAt = System.currentTimeMillis() + delayMs
        while (shouldRun) {
            val remaining = endAt - System.currentTimeMillis()
            if (remaining <= 0) return
            Thread.sleep(min(remaining, 1_000L))
        }
    }

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

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TunnelLight::Xray")
            .also { it.acquire() }

        val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
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

    private fun sendStatus(message: String) {
        val changed = message != lastStatus
        lastStatus = message
        if (changed) appendLog(message)
        sendBroadcast(
            Intent(ACTION_STATUS)
                .putExtra(EXTRA_STATUS, message)
                .putExtra(EXTRA_LOGS, lastLogs)
        )
    }

    private fun appendLog(message: String) {
        val time = DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString()
        synchronized(logLines) {
            logLines.addLast("$time  $message")
            while (logLines.size > MAX_LOG_LINES) {
                logLines.removeFirst()
            }
            lastLogs = logLines.joinToString("\n")
        }
    }

    private fun updateNotification(message: String) {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tunnel Light")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_tunnel_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private fun String.json(): String = buildString {
        for (ch in this@json) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private data class XrayConfig(
        val server: String,
        val port: Int,
        val uuid: String,
        val publicKey: String,
        val sni: String,
        val shortId: String,
        val path: String
    )

    private data class NetworkQuality(
        val label: String,
        val goodEnough: Boolean,
        val score: Int,
        val summary: String
    )
}
