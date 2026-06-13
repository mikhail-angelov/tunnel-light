package com.bconf.tunnellight

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.Base64

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var networkStatusView: TextView
    private lateinit var serverInput: EditText
    private lateinit var publicKeyView: TextView
    private lateinit var generatingLayout: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnCopyKey: Button
    private lateinit var btnRegenKey: Button

    private val prefs by lazy { getSharedPreferences("tunnel", MODE_PRIVATE) }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(SshTunnelService.EXTRA_STATUS) ?: return
            statusView.text = msg
            when {
                msg.startsWith("Connected") -> setTunnelUi(connected = true)
                msg.startsWith("Connecting") -> setTunnelUi(connecting = true)
                else -> setTunnelUi(connected = false)
            }
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkBatteryOptimization()
        } else {
            statusView.text = "Notification permission denied — tunnel may not start on Android 14+"
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkStatus()
        }
        override fun onLost(network: Network) {
            updateNetworkStatus()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            updateNetworkStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        networkStatusView = findViewById(R.id.networkStatus)
        serverInput = findViewById(R.id.serverInput)
        publicKeyView = findViewById(R.id.publicKey)
        generatingLayout = findViewById(R.id.generatingLayout)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnCopyKey = findViewById(R.id.btnCopyKey)
        btnRegenKey = findViewById(R.id.btnRegenKey)

        btnStart.isEnabled = false
        btnStop.isEnabled = false
        serverInput.setText(prefs.getString("server", ""))

        generateKeyIfNeeded()
        requestPermissionsIfNeeded()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (SshTunnelService.isRunning) {
                    moveTaskToBack(true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        btnStart.setOnClickListener {
            val input = serverInput.text.toString().trim()
            val parsed = SshTunnelLogic.parseServer(input) ?: run {
                statusView.text = "Invalid format — use user@host or user@host:22"
                return@setOnClickListener
            }
            prefs.edit().putString("server", input).apply()
            startForegroundService(
                Intent(this, SshTunnelService::class.java)
                    .putExtra("user", parsed.user)
                    .putExtra("host", parsed.host)
                    .putExtra("port", parsed.port)
            )
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, SshTunnelService::class.java))
            statusView.text = "Stopped"
            setTunnelUi(connected = false)
        }

        btnCopyKey.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("ssh public key", publicKeyView.text))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnRegenKey.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Regenerate key?")
                .setMessage("This will create a new key pair. You will need to add the new public key to ~/.ssh/authorized_keys on your server — the tunnel will stop working until you do.")
                .setPositiveButton("Regenerate") { _, _ -> forceRegenerateKey() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SshTunnelService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        registerNetworkCallback()
        updateNetworkStatus()
        // Sync UI with service state in case we returned from background
        if (publicKeyView.visibility == View.VISIBLE) {
            val running = SshTunnelService.isRunning
            val last = SshTunnelService.lastStatus
            if (last.isNotEmpty()) statusView.text = last
            setTunnelUi(
                connected = running,
                connecting = !running && last.startsWith("Connecting")
            )
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterNetworkCallback()
        unregisterReceiver(statusReceiver)
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Battery Optimization")
                .setMessage("To keep the SSH tunnel running in the background, disable battery optimization for this app.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("Skip", null)
                .show()
        }
    }

    private fun setTunnelUi(connected: Boolean = false, connecting: Boolean = false) {
        btnStart.isEnabled = !connected && !connecting
        btnStop.isEnabled = connected || connecting
    }

    // ── Network status ──

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

    private fun updateNetworkStatus() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: run {
            networkStatusView.text = "\u26D4 No internet"
            networkStatusView.setTextColor(0xFFCC4444.toInt())
            return
        }
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: run {
            networkStatusView.text = "\u26D4 No internet"
            networkStatusView.setTextColor(0xFFCC4444.toInt())
            return
        }
        val (icon, label) = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "\uD83D\uDCF6" to "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val type = when {
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> ""
                    else -> SshTunnelLogic.getCellularGenerationName(caps.linkDownstreamBandwidthKbps)
                }
                "\uD83D\uDCF1" to "Mobile$type"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "\uD83D\uDDA5" to "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "\uD83D\uDD10" to "VPN"
            else -> "\uD83C\uDF10" to "Internet"
        }
        networkStatusView.text = "$icon $label"
        networkStatusView.setTextColor(0xFF44AA44.toInt())
    }

    private fun loadPublicKey() {
        val pub = File(filesDir, "id_ed25519.pub")
        if (pub.exists()) {
            publicKeyView.text = pub.readText().trim()
            publicKeyView.visibility = View.VISIBLE
            generatingLayout.visibility = View.GONE
            btnCopyKey.isEnabled = true
            btnRegenKey.visibility = View.VISIBLE
            setTunnelUi(connected = SshTunnelService.isRunning)
        }
    }

    private fun forceRegenerateKey() {
        btnRegenKey.visibility = View.GONE
        btnCopyKey.isEnabled = false
        publicKeyView.visibility = View.GONE
        generatingLayout.visibility = View.VISIBLE

        File(filesDir, "id_ed25519").delete()
        File(filesDir, "id_ed25519.pub").delete()
        generateKeyIfNeeded()
    }

    private fun generateKeyIfNeeded() {
        Thread {
            val keyFile = File(filesDir, "id_ed25519")
            val pubFile = File(filesDir, "id_ed25519.pub")

            val isValid = keyFile.exists() && pubFile.exists() &&
                keyFile.readText().trimStart().startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")

            if (!isValid) {
                runCatching {
                    generateEd25519KeyPair(keyFile, pubFile, "ssh-tunnel@android")
                }.onFailure { it.printStackTrace() }
            }
            runOnUiThread { loadPublicKey() }
        }.start()
    }

    // --- ED25519 key generation using BouncyCastle directly ---

    private fun generateEd25519KeyPair(privateFile: File, publicFile: File, comment: String) {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val priv = kp.private as Ed25519PrivateKeyParameters
        val pub = kp.public as Ed25519PublicKeyParameters

        val seed = priv.encoded    // 32-byte seed
        val pubBytes = pub.encoded // 32-byte public key point

        writeOpenSshPrivateKey(privateFile, seed, pubBytes, comment)
        writeOpenSshPublicKey(publicFile, pubBytes, comment)
    }

    private fun writeOpenSshPrivateKey(file: File, seed: ByteArray, pubBytes: ByteArray, comment: String) {
        val keyType = "ssh-ed25519".toByteArray()
        val commentBytes = comment.toByteArray()
        val privFull = seed + pubBytes  // OpenSSH stores seed||pubkey (64 bytes) as the private key

        val pubKeyBlob = ByteArrayOutputStream().apply {
            writeU32Bytes(keyType)
            writeU32Bytes(pubBytes)
        }.toByteArray()

        val checkInt = SecureRandom().nextInt()
        val privateBlob = ByteArrayOutputStream().apply {
            writeU32(checkInt)
            writeU32(checkInt)
            writeU32Bytes(keyType)
            writeU32Bytes(pubBytes)
            writeU32Bytes(privFull)
            writeU32Bytes(commentBytes)
            var pad = 1; while (size() % 8 != 0) write(pad++)
        }.toByteArray()

        val keyData = ByteArrayOutputStream().apply {
            write("openssh-key-v1 ".toByteArray())
            writeU32Bytes("none".toByteArray()) // cipher
            writeU32Bytes("none".toByteArray()) // kdf
            writeU32(0)                          // no kdf options
            writeU32(1)                          // 1 key
            writeU32Bytes(pubKeyBlob)
            writeU32Bytes(privateBlob)
        }.toByteArray()

        val b64 = Base64.getEncoder().encodeToString(keyData)
        file.writeText(buildString {
            append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
            b64.chunked(70).forEach { append(it).append('\n') }
            append("-----END OPENSSH PRIVATE KEY-----\n")
        })
    }

    private fun writeOpenSshPublicKey(file: File, pubBytes: ByteArray, comment: String) {
        val keyType = "ssh-ed25519".toByteArray()
        val blob = ByteArrayOutputStream().apply {
            writeU32Bytes(keyType)
            writeU32Bytes(pubBytes)
        }.toByteArray()
        file.writeText("ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)} $comment\n")
    }

    private fun ByteArrayOutputStream.writeU32(v: Int) {
        write(v ushr 24 and 0xFF); write(v ushr 16 and 0xFF)
        write(v ushr 8 and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32Bytes(b: ByteArray) {
        writeU32(b.size); write(b)
    }

    // --- end key generation ---
}
