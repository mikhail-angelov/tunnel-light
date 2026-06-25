package com.bconf.tunnellight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var networkStatusView: TextView
    private lateinit var serverInput: EditText
    private lateinit var portInput: EditText
    private lateinit var uuidInput: EditText
    private lateinit var publicKeyInput: EditText
    private lateinit var sniInput: EditText
    private lateinit var shortIdInput: EditText
    private lateinit var pathInput: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnToggleSettings: Button
    private lateinit var settingsPanel: ScrollView
    private lateinit var btnToggleLogs: Button
    private lateinit var logsPanel: ScrollView
    private lateinit var logsText: TextView

    private val prefs by lazy { getSharedPreferences("tunnel", MODE_PRIVATE) }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(SshTunnelService.EXTRA_STATUS) ?: return
            statusView.text = msg
            intent.getStringExtra(SshTunnelService.EXTRA_LOGS)?.let { updateLogs(it) }
            updateNetworkStatusView()
            syncTunnelUi(msg)
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkBatteryOptimization()
        } else {
            statusView.text = "Notification permission denied; tunnel may not start on Android 14+"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        networkStatusView = findViewById(R.id.networkStatus)
        serverInput = findViewById(R.id.serverInput)
        portInput = findViewById(R.id.portInput)
        uuidInput = findViewById(R.id.uuidInput)
        publicKeyInput = findViewById(R.id.publicKeyInput)
        sniInput = findViewById(R.id.sniInput)
        shortIdInput = findViewById(R.id.shortIdInput)
        pathInput = findViewById(R.id.pathInput)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnToggleSettings = findViewById(R.id.btnToggleSettings)
        settingsPanel = findViewById(R.id.settingsPanel)
        btnToggleLogs = findViewById(R.id.btnToggleLogs)
        logsPanel = findViewById(R.id.logsPanel)
        logsText = findViewById(R.id.logsText)

        loadSavedConfig()
        btnToggleSettings.text = "Settings"
        btnToggleLogs.text = "Logs"
        restoreRuntimeUi(savedInstanceState)
        handleImportIntent(intent)
        requestPermissionsIfNeeded(showBatteryDialog = savedInstanceState == null)

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
            val config = readConfigFromUi() ?: return@setOnClickListener
            saveConfig(config)
            startForegroundService(
                Intent(this, SshTunnelService::class.java)
                    .putExtra(SshTunnelService.EXTRA_SERVER_ADDRESS, config.server)
                    .putExtra(SshTunnelService.EXTRA_SERVER_PORT, config.port)
                    .putExtra(SshTunnelService.EXTRA_UUID, config.uuid)
                    .putExtra(SshTunnelService.EXTRA_PUBLIC_KEY, config.publicKey)
                    .putExtra(SshTunnelService.EXTRA_SNI, config.sni)
                    .putExtra(SshTunnelService.EXTRA_SHORT_ID, config.shortId)
                    .putExtra(SshTunnelService.EXTRA_XHTTP_PATH, config.path)
            )
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, SshTunnelService::class.java))
            SshTunnelService.isActive = false
            SshTunnelService.isRunning = false
            statusView.text = "Stopped"
            statusView.setTextColor(0xFFAAAAAA.toInt())
            syncTunnelUi("Stopped")
        }

        btnToggleSettings.setOnClickListener {
            togglePanel(settingsPanel, btnToggleSettings, "Settings")
        }

        btnToggleLogs.setOnClickListener {
            togglePanel(logsPanel, btnToggleLogs, "Logs")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
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
        restoreRuntimeUi(preserveCurrentStatus = true)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_STATUS_TEXT, statusView.text.toString())
        outState.putBoolean(KEY_SETTINGS_VISIBLE, settingsPanel.visibility == View.VISIBLE)
        outState.putBoolean(KEY_LOGS_VISIBLE, logsPanel.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    private fun requestPermissionsIfNeeded(showBatteryDialog: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        if (showBatteryDialog) checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Battery Optimization")
                .setMessage("Disable battery optimization for this app to keep the proxy running in the background.")
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

    private fun loadSavedConfig() {
        migrateDefaultConfigIfNeeded()

        serverInput.setText(prefs.getString(PREF_SERVER, DEFAULT_SERVER))
        portInput.setText(prefs.getInt(PREF_PORT, DEFAULT_PORT).toString())
        uuidInput.setText(prefs.getString(PREF_UUID, DEFAULT_UUID))
        publicKeyInput.setText(prefs.getString(PREF_PUBLIC_KEY, DEFAULT_PUBLIC_KEY))
        sniInput.setText(prefs.getString(PREF_SNI, DEFAULT_SNI))
        shortIdInput.setText(prefs.getString(PREF_SHORT_ID, DEFAULT_SHORT_ID))
        pathInput.setText(prefs.getString(PREF_XHTTP_PATH, DEFAULT_XHTTP_PATH))
    }

    private fun migrateDefaultConfigIfNeeded() {
        if (prefs.getInt(PREF_CONFIG_VERSION, 0) >= DEFAULT_CONFIG_VERSION) return
        prefs.edit()
            .putInt(PREF_CONFIG_VERSION, DEFAULT_CONFIG_VERSION)
            .putString(PREF_SERVER, DEFAULT_SERVER)
            .putInt(PREF_PORT, DEFAULT_PORT)
            .putString(PREF_UUID, DEFAULT_UUID)
            .putString(PREF_PUBLIC_KEY, DEFAULT_PUBLIC_KEY)
            .putString(PREF_SNI, DEFAULT_SNI)
            .putString(PREF_SHORT_ID, DEFAULT_SHORT_ID)
            .putString(PREF_XHTTP_PATH, DEFAULT_XHTTP_PATH)
            .apply()
    }

    private fun readConfigFromUi(): SshTunnelLogic.XrayConfig? {
        val server = serverInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()
        val uuid = uuidInput.text.toString().trim()
        val publicKey = publicKeyInput.text.toString().trim()
        val sni = sniInput.text.toString().trim()
        val shortId = shortIdInput.text.toString().trim()
        val path = pathInput.text.toString().trim()

        val error = validateXrayConfig(server, port, uuid, publicKey, sni, shortId, path)
        if (error != null) {
            statusView.text = error
            statusView.setTextColor(0xFFCC4444.toInt())
            return null
        }

        val validPort = port ?: return null
        return SshTunnelLogic.XrayConfig(server, validPort, uuid, publicKey, sni, shortId, path)
    }

    private fun saveConfig(config: SshTunnelLogic.XrayConfig) {
        prefs.edit()
            .putInt(PREF_CONFIG_VERSION, DEFAULT_CONFIG_VERSION)
            .putString(PREF_SERVER, config.server)
            .putInt(PREF_PORT, config.port)
            .putString(PREF_UUID, config.uuid)
            .putString(PREF_PUBLIC_KEY, config.publicKey)
            .putString(PREF_SNI, config.sni)
            .putString(PREF_SHORT_ID, config.shortId)
            .putString(PREF_XHTTP_PATH, config.path)
            .apply()
    }

    private fun handleImportIntent(intent: Intent?) {
        val link = intent?.dataString ?: return
        val config = SshTunnelLogic.parseVlessUri(link)
        if (config == null) {
            if (intent.action == Intent.ACTION_VIEW) {
                statusView.text = "Invalid VLESS config link"
                statusView.setTextColor(0xFFCC4444.toInt())
            }
            return
        }

        val error = validateXrayConfig(
            config.server,
            config.port,
            config.uuid,
            config.publicKey,
            config.sni,
            config.shortId,
            config.path
        )
        if (error != null) {
            statusView.text = error
            statusView.setTextColor(0xFFCC4444.toInt())
            return
        }

        saveConfig(config)
        writeConfigToUi(config)
        statusView.text = "Config imported"
        syncTunnelUi(statusView.text.toString())
        statusView.setTextColor(0xFF44AA44.toInt())
    }

    private fun writeConfigToUi(config: SshTunnelLogic.XrayConfig) {
        serverInput.setText(config.server)
        portInput.setText(config.port.toString())
        uuidInput.setText(config.uuid)
        publicKeyInput.setText(config.publicKey)
        sniInput.setText(config.sni)
        shortIdInput.setText(config.shortId)
        pathInput.setText(config.path)
    }

    private fun validateXrayConfig(
        server: String,
        port: Int?,
        uuid: String,
        publicKey: String,
        sni: String,
        shortId: String,
        path: String
    ): String? {
        return when {
            server.isEmpty() -> "Server is required"
            !SERVER_REGEX.matches(server) -> "Invalid server"
            port == null || port !in 1..65535 -> "Port must be between 1 and 65535"
            !UUID_REGEX.matches(uuid) -> "Invalid VLESS UUID"
            !KEY_REGEX.matches(publicKey) -> "Invalid Reality public key"
            !HOST_REGEX.matches(sni) -> "Invalid SNI"
            !HEX_REGEX.matches(shortId) -> "shortId must be hex"
            !path.startsWith("/") -> "XHTTP path must start with /"
            !PATH_REGEX.matches(path) -> "Invalid XHTTP path"
            else -> null
        }
    }

    private fun syncTunnelUi(msg: String = SshTunnelService.lastStatus) {
        val connected = msg.startsWith("Connected") || msg.startsWith("Running")
        val active = SshTunnelService.isActive
        btnStart.isEnabled = !active
        btnStop.isEnabled = active
        statusView.setTextColor(when {
            connected -> 0xFF44AA44.toInt()
            active && !connected && ERROR_WORDS.any { msg.contains(it, ignoreCase = true) } -> 0xFFCC4444.toInt()
            else -> 0xFFAAAAAA.toInt()
        })
    }

    private fun updateNetworkStatusView() {
        val status = SshTunnelService.lastNetworkStatus
        networkStatusView.text = status
        if (status.isNotEmpty()) {
            networkStatusView.setTextColor(
                if (NETWORK_WARNING_WORDS.any { status.contains(it, ignoreCase = true) }) {
                    0xFFCC4444.toInt()
                } else {
                    0xFF44AA44.toInt()
                }
            )
        }
    }

    private fun restoreRuntimeUi(
        savedInstanceState: Bundle? = null,
        preserveCurrentStatus: Boolean = false
    ) {
        val serviceStatus = SshTunnelService.lastStatus
        val restoredStatus = savedInstanceState?.getString(KEY_STATUS_TEXT).orEmpty()
        val currentStatus = statusView.text.toString()
        val status = when {
            serviceStatus.isNotEmpty() -> serviceStatus
            restoredStatus.isNotEmpty() -> restoredStatus
            preserveCurrentStatus && currentStatus.isNotEmpty() -> currentStatus
            else -> "Stopped"
        }

        statusView.text = status
        if (savedInstanceState != null) {
            settingsPanel.visibility = if (savedInstanceState.getBoolean(KEY_SETTINGS_VISIBLE)) {
                View.VISIBLE
            } else {
                View.GONE
            }
            logsPanel.visibility = if (savedInstanceState.getBoolean(KEY_LOGS_VISIBLE)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        updateNetworkStatusView()
        updateLogs(SshTunnelService.lastLogs)
        syncTunnelUi(status)
    }

    private fun updateLogs(logs: String) {
        logsText.text = logs
        if (logsPanel.visibility == View.VISIBLE) {
            logsPanel.post { logsPanel.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun togglePanel(panel: View, button: Button, title: String) {
        val expanded = panel.visibility == View.VISIBLE
        panel.visibility = if (expanded) View.GONE else View.VISIBLE
        button.text = title
    }

    private companion object {
        const val DEFAULT_CONFIG_VERSION = 2
        const val DEFAULT_SERVER = "2.26.65.57"
        const val DEFAULT_PORT = 443
        const val DEFAULT_UUID = "9629c78b-4e77-444c-9747-d4e44488c811"
        const val DEFAULT_PUBLIC_KEY = "MHECfwi2j5Ihm7_KmgoVWxbVYKtYBzI6HIh2UFlk6jA"
        const val DEFAULT_SNI = "github.com"
        const val DEFAULT_SHORT_ID = "0123456789abcdef"
        const val DEFAULT_XHTTP_PATH = "/tunnel-light-xhttp"

        const val PREF_CONFIG_VERSION = "config_version"
        const val PREF_SERVER = "server"
        const val PREF_PORT = "port"
        const val PREF_UUID = "uuid"
        const val PREF_PUBLIC_KEY = "public_key"
        const val PREF_SNI = "sni"
        const val PREF_SHORT_ID = "short_id"
        const val PREF_XHTTP_PATH = "xhttp_path"
        const val KEY_STATUS_TEXT = "status_text"
        const val KEY_SETTINGS_VISIBLE = "settings_visible"
        const val KEY_LOGS_VISIBLE = "logs_visible"

        val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val SERVER_REGEX = Regex("^[A-Za-z0-9.:-]+$")
        val KEY_REGEX = Regex("^[A-Za-z0-9_-]+$")
        val HOST_REGEX = Regex("^[A-Za-z0-9.-]+$")
        val HEX_REGEX = Regex("^[A-Fa-f0-9]+$")
        val PATH_REGEX = Regex("^/[A-Za-z0-9._~/-]*$")
        val ERROR_WORDS = listOf("error", "failed", "refused", "timeout", "lost", "unreachable", "invalid")
        val NETWORK_WARNING_WORDS = listOf("weak", "lost", "suspended", "no internet")
    }
}
