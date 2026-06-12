package com.bconf.tunnellight

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class SshTunnelService : Service() {

    companion object {
        const val ACTION_STATUS = "com.bconf.tunnellight.STATUS"
        const val EXTRA_STATUS = "status"
        @Volatile var isRunning = false
        @Volatile var lastStatus = ""
    }

    @Volatile private var shouldRun = false
    private var session: Session? = null
    private var proxyServer: Socks5ProxyServer? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("ssh", "SSH Tunnel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra("host") ?: run { stopSelf(); return START_NOT_STICKY }
        val user = intent.getStringExtra("user") ?: run { stopSelf(); return START_NOT_STICKY }
        val port = intent.getIntExtra("port", 22)

        shouldRun = true
        updateNotification("Connecting to $host…")
        sendStatus("Connecting to $host…")

        Thread {
            val keyFile = File(filesDir, "id_ed25519")
            while (shouldRun) {
                var sess: Session? = null
                var proxy: Socks5ProxyServer? = null
                try {
                    val jsch = JSch()
                    jsch.addIdentity(keyFile.absolutePath)

                    sess = jsch.getSession(user, host, port)
                    sess.setConfig("StrictHostKeyChecking", "no")
                    sess.setConfig("ServerAliveInterval", "30")
                    sess.setConfig("ServerAliveCountMax", "3")
                    sess.connect(15_000)

                    session = sess
                    proxy = Socks5ProxyServer(sess)
                    proxyServer = proxy
                    proxy.start()

                    isRunning = true
                    updateNotification("Connected — SOCKS5 on 127.0.0.1:1080")
                    sendStatus("Connected — SOCKS5 on 127.0.0.1:1080")

                    // Block until session drops or stop is requested
                    while (shouldRun && sess.isConnected()) {
                        Thread.sleep(3_000)
                    }
                } catch (e: Exception) {
                    if (shouldRun) {
                        sendStatus("Error: ${e.message}")
                    }
                } finally {
                    isRunning = false
                    proxy?.stop()
                    sess?.disconnect()
                    session = null
                    proxyServer = null
                }

                if (shouldRun) {
                    sendStatus("Reconnecting in 5s…")
                    updateNotification("Reconnecting…")
                    Thread.sleep(5_000)
                    if (shouldRun) {
                        sendStatus("Connecting to $host…")
                        updateNotification("Connecting to $host…")
                    }
                }
            }
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shouldRun = false
        isRunning = false
        lastStatus = ""
        proxyServer?.stop()
        session?.disconnect()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

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
}

private class Socks5ProxyServer(private val session: Session, private val listenPort: Int = 1080) {
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
        serverSocket?.close()
    }

    private fun handleClient(client: Socket) {
        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            // SOCKS5 greeting: VER(1) NMETHODS(1) METHODS(N)
            if (inp.read() != 5) { client.close(); return }
            val nMethods = inp.read()
            repeat(nMethods) { inp.read() }
            out.write(byteArrayOf(5, 0)) // NO AUTH
            out.flush()

            // Request: VER(1) CMD(1) RSV(1) ATYP(1) ...
            inp.read() // ver
            val cmd = inp.read()
            inp.read() // rsv
            val atype = inp.read()

            val targetHost = when (atype) {
                1 -> { // IPv4
                    val b = ByteArray(4); inp.read(b)
                    b.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                3 -> { // Domain
                    val len = inp.read(); val b = ByteArray(len); inp.read(b); String(b)
                }
                else -> {
                    out.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                    client.close(); return
                }
            }
            val targetPort = (inp.read() shl 8) or inp.read()

            if (cmd != 1) { // Only CONNECT
                out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                client.close(); return
            }

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

            val chIn = ch.inputStream
            val chOut = ch.outputStream
            val t = Thread { pump(inp, chOut) }
            t.start()
            pump(chIn, out)
            t.join(1000)
            ch.disconnect()
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun pump(src: InputStream, dst: OutputStream) {
        try {
            val buf = ByteArray(8192)
            var n: Int
            while (src.read(buf).also { n = it } != -1) {
                dst.write(buf, 0, n); dst.flush()
            }
        } catch (_: Exception) {}
    }
}
