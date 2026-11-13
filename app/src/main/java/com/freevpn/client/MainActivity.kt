package com.freevpn.client

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private data class VpnGateServer(
    val host: String,
    val ip: String,
    val country: String,
    val countryCode: String,
    val pingMs: Int,
    val speedBps: Long,
    val sessions: Int,
    val logPolicy: String,
    val config: String
) {
    val speedMbps: String
        get() = String.format(Locale.US, "%.1f", speedBps / 1_000_000.0)
}

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var serverList: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var connectButton: Button
    private lateinit var refreshButton: Button

    private val servers = mutableListOf<VpnGateServer>()
    private var selectedServer: VpnGateServer? = null
    private var openVpnService: IOpenVPNAPIService? = null
    private var serviceBound = false
    private var apiPermissionGranted = false
    private var vpnConnected = false
    private var vpnConnecting = false
    private var pendingServer: VpnGateServer? = null

    companion object {
        private const val VPN_GATE_API = "https://www.vpngate.net/api/iphone/"
        private const val OPENVPN_PACKAGE = "de.blinkt.openvpn"
        private const val REQUEST_OPENVPN_API_ACCESS = 8201
        private const val REQUEST_VPN_PERMISSION = 8202
        private const val MAX_RENDERED_SERVERS = 100
    }

    private val statusCallback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String, state: String, message: String, level: String) {
            runOnUiThread {
                val normalized = state.uppercase(Locale.ROOT)
                when {
                    normalized.contains("CONNECTED") -> {
                        vpnConnected = true
                        vpnConnecting = false
                        setStatus("Подключено: " + (selectedServer?.country ?: "VPN Gate"))
                    }
                    normalized.contains("CONNECTING") ||
                        normalized.contains("WAIT") ||
                        normalized.contains("AUTH") -> {
                        vpnConnecting = true
                        setStatus("Подключение: " + message.ifBlank { state })
                    }
                    normalized.contains("NOPROCESS") ||
                        normalized.contains("EXITING") ||
                        normalized.contains("DISCONNECTED") -> {
                        vpnConnected = false
                        vpnConnecting = false
                        setStatus("Не подключено")
                    }
                    else -> {
                        if (message.isNotBlank()) setStatus(message)
                    }
                }
                updateConnectionUi()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            openVpnService = IOpenVPNAPIService.Stub.asInterface(binder)
            val service = openVpnService ?: return

            try {
                val permissionIntent = service.prepare(packageName)
                if (permissionIntent != null) {
                    startActivityForResult(permissionIntent, REQUEST_OPENVPN_API_ACCESS)
                } else {
                    onOpenVpnApiAccessGranted()
                }
            } catch (error: RemoteException) {
                showOpenVpnError(error.message ?: "Не удалось получить доступ к OpenVPN")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            openVpnService = null
            serviceBound = false
            apiPermissionGranted = false
            if (vpnConnected || vpnConnecting) {
                vpnConnected = false
                vpnConnecting = false
                setStatus("OpenVPN engine отключился")
                updateConnectionUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        updateConnectionUi()
        loadServerDirectory()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(26))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 15, 31), Color.rgb(14, 37, 62), Color.rgb(20, 28, 51))
            )
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }
        setContentView(scroll)

        addText(page, "FREE VPN", 30f, Color.WHITE, true, 0)
        addText(page, "Глобальный каталог VPN Gate", 15f, Color.rgb(157, 180, 205), false, 4)
        addText(
            page,
            "Бесплатные публичные серверы из каталога VPN Gate. Страны и серверы обновляются автоматически.",
            13f,
            Color.rgb(196, 210, 226),
            false,
            14
        )

        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(Color.rgb(25, 46, 69), 17)
        }
        statusText = TextView(this).apply {
            text = "Загрузка каталога…"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusPanel.addView(statusText)
        statusPanel.addView(TextView(this).apply {
            text = "VPN Gate / OpenVPN"
            textSize = 12f
            setTextColor(Color.rgb(161, 185, 207))
            setPadding(0, dp(5), 0, 0)
        })
        page.addView(statusPanel, params(top = 16))

        connectButton = Button(this).apply {
            text = "Подключиться к выбранному серверу"
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(0, 174, 160), Color.rgb(45, 113, 222))
            ).apply { cornerRadius = dp(15).toFloat() }
            setOnClickListener {
                if (vpnConnected || vpnConnecting) disconnectVpn()
                else selectedServer?.let { connectToServer(it) }
            }
        }
        page.addView(connectButton, params(height = 56, top = 12))

        refreshButton = Button(this).apply {
            text = "Обновить список серверов"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(37, 66, 98), 13)
            setOnClickListener { loadServerDirectory(force = true) }
        }
        page.addView(refreshButton, params(height = 48, top = 10))

        countText = TextView(this).apply {
            text = "Получение списка стран…"
            textSize = 13f
            setTextColor(Color.rgb(163, 191, 215))
        }
        page.addView(countText, params(top = 20))

        searchInput = EditText(this).apply {
            hint = "Поиск страны, региона или IP…"
            textSize = 14f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(147, 170, 194))
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = rounded(Color.rgb(22, 42, 64), 13)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderServers()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        page.addView(searchInput, params(height = 50, top = 12))

        addText(page, "ДОСТУПНЫЕ ЛОКАЦИИ", 12f, Color.rgb(144, 181, 211), true, 19)
        serverList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(serverList, params(top = 8))

        addText(
            page,
            "Источник: VPN Gate (Университет Цукубы). Серверы работают добровольно: доступность и скорость могут меняться. Оператор выбранного сервера может видеть сетевые метаданные и применять собственную политику журналирования.",
            11f,
            Color.rgb(139, 160, 184),
            false,
            22
        )

        val engineButton = Button(this).apply {
            text = "Установить OpenVPN for Android"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(37, 66, 98), 13)
            setOnClickListener { openOpenVpnStoreListing() }
        }
        page.addView(engineButton, params(height = 46, top = 12))
    }

    private fun addText(
        parent: LinearLayout,
        value: String,
        size: Float,
        color: Int,
        bold: Boolean,
        top: Int
    ) {
        val view = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        parent.addView(view, params(top = top))
    }

    private fun params(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (height == ViewGroup.LayoutParams.WRAP_CONTENT) height else dp(height)
        ).apply { topMargin = dp(top) }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun setStatus(value: String) {
        if (::statusText.isInitialized) statusText.text = value
    }

    private fun updateConnectionUi() {
        if (!::connectButton.isInitialized) return
        connectButton.text = when {
            vpnConnecting -> "Подключение…"
            vpnConnected -> "Отключиться"
            selectedServer != null -> "Подключиться · " + selectedServer!!.country
            else -> "Выберите сервер"
        }
        connectButton.isEnabled = !vpnConnecting && (vpnConnected || selectedServer != null)
        connectButton.alpha = if (connectButton.isEnabled) 1f else 0.58f
    }

    private fun loadServerDirectory(force: Boolean = false) {
        refreshButton.isEnabled = false
        refreshButton.text = "Обновление…"
        setStatus("Получение актуального списка VPN Gate…")

        Thread {
            try {
                val cache = File(filesDir, "vpngate-directory.csv")
                val csv = if (!force && cache.exists() && cache.length() > 100) {
                    val age = System.currentTimeMillis() - cache.lastModified()
                    if (age < 5 * 60 * 1000L) cache.readText(Charsets.UTF_8)
                    else downloadDirectory(cache)
                } else {
                    downloadDirectory(cache)
                }

                val parsed = parseVpnGateCsv(csv)
                runOnUiThread {
                    servers.clear()
                    servers.addAll(parsed)
                    if (selectedServer == null || servers.none { it.host == selectedServer?.host }) {
                        selectedServer = servers.firstOrNull()
                    }
                    val countryCount = servers.map { it.countryCode }.distinct().size
                    countText.text = servers.size.toString() + " серверов · " + countryCount + " стран/регионов"
                    setStatus("Каталог обновлён")
                    renderServers()
                    updateConnectionUi()
                    refreshButton.isEnabled = true
                    refreshButton.text = "Обновить список серверов"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    refreshButton.isEnabled = true
                    refreshButton.text = "Повторить обновление"
                    setStatus("Не удалось обновить каталог")
                    Toast.makeText(
                        this,
                        error.localizedMessage ?: "Ошибка загрузки VPN Gate",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun downloadDirectory(cache: File): String {
        val connection = (URL(VPN_GATE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "FreeVPN-Android/2.0")
            setRequestProperty("Accept", "text/csv,text/plain,*/*")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("VPN Gate API вернул HTTP " + code)
            val csv = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!csv.contains("OpenVPN_ConfigData_Base64") || csv.length < 100) {
                throw IllegalStateException("VPN Gate вернул неполный список серверов")
            }
            cache.writeText(csv, Charsets.UTF_8)
            return csv
        } finally {
            connection.disconnect()
        }
    }

    private fun parseVpnGateCsv(csv: String): List<VpnGateServer> {
        val result = mutableListOf<VpnGateServer>()
        val rows = parseCsv(csv)
        for (row in rows) {
            if (row.size < 15) continue
            if (row[0].startsWith("*") || row[0].startsWith("#")) continue

            val host = row[0].trim()
            val ip = row[1].trim()
            val country = row[5].trim()
            val countryCode = row[6].trim()
            val speed = row[4].trim().toLongOrNull() ?: 0L
            val ping = row[3].trim().toIntOrNull() ?: -1
            val sessions = row[7].trim().toIntOrNull() ?: 0
            val logPolicy = row[11].trim()
            val encodedConfig = row[14].trim()

            if (host.isBlank() || ip.isBlank() || country.isBlank() || encodedConfig.isBlank()) continue

            try {
                val decodedConfig = Base64.decode(encodedConfig, Base64.DEFAULT).toString(Charsets.UTF_8)
                if (!decodedConfig.contains("remote ", ignoreCase = true) ||
                    !decodedConfig.contains("<ca>", ignoreCase = true)
                ) continue

                result.add(
                    VpnGateServer(
                        host = host,
                        ip = ip,
                        country = country,
                        countryCode = countryCode,
                        pingMs = ping,
                        speedBps = speed,
                        sessions = sessions,
                        logPolicy = logPolicy,
                        config = withVpnGateCredentials(decodedConfig)
                    )
                )
            } catch (_: IllegalArgumentException) {
                // Ignore malformed Base64 entries and keep valid public relay records.
            }
        }
        return result.distinctBy { it.host }
            .sortedWith(compareByDescending<VpnGateServer> { it.speedBps }.thenBy { it.pingMs })
    }

    private fun withVpnGateCredentials(raw: String): String {
        if (raw.contains("<auth-user-pass>", ignoreCase = true)) return raw

        val credentialBlock = "<auth-user-pass>\\nvpn\\nvpn\\n</auth-user-pass>"
        val authDirective = Regex("(?im)^\\\\s*auth-user-pass(?:\\\\s+[^\\\\r\\\\n]+)?\\\\s*$")
        val normalized = if (authDirective.containsMatchIn(raw)) {
            authDirective.replaceFirst(raw, credentialBlock)
        } else {
            raw.trimEnd() + "\\nauth-user-pass\\n"
        }

        return if (normalized.contains("<auth-user-pass>", ignoreCase = true)) {
            normalized
        } else {
            normalized + credentialBlock + "\\nauth-nocache\\n"
        }
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    row.add(field.toString())
                    field.setLength(0)
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row.add(field.toString())
                    field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row.toList())
                    row.clear()
                }
                else -> field.append(char)
            }
            index++
        }

        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotBlank() }) rows.add(row.toList())
        }
        return rows
    }

    private fun renderServers() {
        if (!::serverList.isInitialized) return
        serverList.removeAllViews()

        val query = if (::searchInput.isInitialized) searchInput.text.toString().trim() else ""
        val filtered = servers.filter {
            query.isBlank() ||
                it.country.contains(query, ignoreCase = true) ||
                it.countryCode.contains(query, ignoreCase = true) ||
                it.ip.contains(query, ignoreCase = true) ||
                it.host.contains(query, ignoreCase = true)
        }

        if (filtered.isEmpty()) {
            val emptyText = if (servers.isEmpty()) {
                "Серверы ещё загружаются. Нажмите «Обновить список серверов»."
            } else {
                "По вашему запросу серверов не найдено."
            }
            val empty = TextView(this).apply {
                text = emptyText
                textSize = 13f
                setTextColor(Color.rgb(174, 194, 215))
                setPadding(dp(14), dp(18), dp(14), dp(18))
                background = rounded(Color.rgb(20, 38, 59), 13)
            }
            serverList.addView(empty, params())
            return
        }

        countText.text = filtered.size.toString() + " результатов · " +
            servers.map { it.countryCode }.distinct().size + " стран/регионов"

        filtered.take(MAX_RENDERED_SERVERS).forEach { server ->
            val selected = selectedServer?.host == server.host
            val ping = if (server.pingMs >= 0) server.pingMs.toString() + " ms" else "ping н/д"
            val label = TextView(this).apply {
                text = (if (selected) "✓  " else "") +
                    server.country + " (" + server.countryCode + ")\\n" +
                    server.ip + " · " + server.speedMbps + " Mbps · " + ping + "\\n" +
                    "Сессий: " + server.sessions + " · журнал: " +
                    (server.logPolicy.ifBlank { "не указан" })
                textSize = 13f
                setLineSpacing(dp(2).toFloat(), 1.0f)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = rounded(
                    if (selected) Color.rgb(28, 83, 111) else Color.rgb(20, 38, 59),
                    13
                )
                setOnClickListener {
                    selectedServer = server
                    renderServers()
                    updateConnectionUi()
                }
            }
            serverList.addView(label, params(top = 7))
        }

        if (filtered.size > MAX_RENDERED_SERVERS) {
            val more = TextView(this).apply {
                text = "Показаны первые " + MAX_RENDERED_SERVERS +
                    " серверов. Уточните поиск по стране или IP."
                textSize = 12f
                setTextColor(Color.rgb(163, 191, 215))
                setPadding(dp(10), dp(12), dp(10), dp(12))
            }
            serverList.addView(more, params(top = 4))
        }
    }

    private fun connectToServer(server: VpnGateServer) {
        selectedServer = server
        pendingServer = server
        updateConnectionUi()

        if (!isOpenVpnInstalled()) {
            AlertDialog.Builder(this)
                .setTitle("Нужен OpenVPN engine")
                .setMessage(
                    "Список VPN Gate использует протокол OpenVPN. Установите бесплатный OpenVPN for Android, затем вернитесь в Free VPN и повторно нажмите «Подключиться»."
                )
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Установить") { _, _ -> openOpenVpnStoreListing() }
                .show()
            return
        }

        setStatus("Подготовка OpenVPN…")
        vpnConnecting = true
        updateConnectionUi()

        if (openVpnService != null && apiPermissionGranted) {
            requestVpnPermissionAndStart()
            return
        }

        if (!serviceBound) {
            val intent = Intent(IOpenVPNAPIService::class.java.name).setPackage(OPENVPN_PACKAGE)
            serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!serviceBound) {
                vpnConnecting = false
                updateConnectionUi()
                setStatus("Не удалось связаться с OpenVPN")
                showOpenVpnError("Откройте OpenVPN for Android один раз и повторите подключение.")
            }
        }
    }

    private fun isOpenVpnInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(OPENVPN_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun openOpenVpnStoreListing() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + OPENVPN_PACKAGE))
        try {
            startActivity(market)
        } catch (_: Exception) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + OPENVPN_PACKAGE)
                )
            )
        }
    }

    private fun onOpenVpnApiAccessGranted() {
        apiPermissionGranted = true
        try {
            openVpnService?.registerStatusCallback(statusCallback)
        } catch (error: RemoteException) {
            showOpenVpnError(error.message ?: "Не удалось подписаться на статус VPN")
        }
        if (pendingServer != null) requestVpnPermissionAndStart()
    }

    private fun requestVpnPermissionAndStart() {
        val service = openVpnService ?: return
        try {
            val permissionIntent = service.prepareVPNService()
            if (permissionIntent != null) {
                startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
            } else {
                startPendingServer()
            }
        } catch (error: RemoteException) {
            vpnConnecting = false
            updateConnectionUi()
            showOpenVpnError(error.message ?: "Не удалось запросить разрешение VPN")
        }
    }

    private fun startPendingServer() {
        val server = pendingServer ?: return
        val service = openVpnService ?: return
        setStatus("Подключение к " + server.country + "…")

        Thread {
            try {
                service.startVPN(server.config)
                runOnUiThread {
                    selectedServer = server
                    vpnConnecting = true
                    updateConnectionUi()
                    setStatus("Запрос подключения отправлен: " + server.country)
                }
            } catch (error: RemoteException) {
                runOnUiThread {
                    vpnConnecting = false
                    updateConnectionUi()
                    showOpenVpnError(error.message ?: "OpenVPN не смог запустить сервер")
                }
            }
        }.start()
    }

    private fun disconnectVpn() {
        val service = openVpnService
        if (service == null) {
            vpnConnected = false
            vpnConnecting = false
            updateConnectionUi()
            setStatus("OpenVPN engine не подключён")
            return
        }

        vpnConnecting = true
        setStatus("Отключение…")
        updateConnectionUi()

        Thread {
            try {
                service.disconnect()
                runOnUiThread {
                    vpnConnected = false
                    vpnConnecting = false
                    pendingServer = null
                    setStatus("Не подключено")
                    updateConnectionUi()
                }
            } catch (error: RemoteException) {
                runOnUiThread {
                    vpnConnecting = false
                    updateConnectionUi()
                    showOpenVpnError(error.message ?: "Не удалось отключить VPN")
                }
            }
        }.start()
    }

    private fun showOpenVpnError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Legacy activity result API is used for the external OpenVPN permission flow.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OPENVPN_API_ACCESS -> {
                if (resultCode == Activity.RESULT_OK) {
                    onOpenVpnApiAccessGranted()
                } else {
                    vpnConnecting = false
                    setStatus("Доступ к OpenVPN не разрешён")
                    updateConnectionUi()
                }
            }
            REQUEST_VPN_PERMISSION -> {
                if (resultCode == Activity.RESULT_OK) {
                    startPendingServer()
                } else {
                    vpnConnecting = false
                    setStatus("Разрешение VPN не предоставлено")
                    updateConnectionUi()
                }
            }
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            try {
                openVpnService?.unregisterStatusCallback(statusCallback)
            } catch (_: Exception) {
                // OpenVPN may have shut down before the Activity.
            }
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {
                // Ignore an already unbound service.
            }
            serviceBound = false
        }
        super.onDestroy()
    }
}
