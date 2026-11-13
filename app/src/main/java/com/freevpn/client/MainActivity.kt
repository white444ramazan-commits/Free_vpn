package com.freevpn.client

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

class MainActivity : Activity() {
    private lateinit var backend: GoBackend
    private lateinit var profileList: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button

    private var profiles: MutableList<File> = mutableListOf()
    private var selectedProfile: File? = null
    private var activeTunnel: Tunnel? = null
    private var connected = false
    private var connecting = false

    companion object {
        private const val REQUEST_VPN_PERMISSION = 7101
        private const val REQUEST_CONFIG_FILE = 7102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(applicationContext)
        buildScreen()
        loadProfiles()
        updateConnectionUi()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
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
        addText(page, "WireGuard client", 15f, Color.rgb(157, 180, 205), false, 4)
        addText(
            page,
            "Подключение к VPN-серверам через конфигурации вашего провайдера.",
            14f,
            Color.rgb(196, 210, 226),
            false,
            18
        )

        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(Color.rgb(25, 46, 69), 18)
        }
        statusText = TextView(this).apply {
            text = "Не подключено"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusPanel.addView(statusText)
        statusPanel.addView(TextView(this).apply {
            text = "Трафик будет идти через выбранный WireGuard-сервер"
            textSize = 12f
            setTextColor(Color.rgb(161, 185, 207))
            setPadding(0, dp(5), 0, 0)
        })
        page.addView(statusPanel, params(top = 18))

        connectButton = Button(this).apply {
            text = "Подключиться"
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(0, 174, 160), Color.rgb(45, 113, 222))
            ).apply { cornerRadius = dp(16).toFloat() }
            setOnClickListener { onConnectButtonClick() }
        }
        page.addView(connectButton, params(height = 58, top = 14))

        addText(page, "ЛОКАЦИИ", 13f, Color.rgb(144, 181, 211), true, 25)
        addText(
            page,
            "В списке отображаются только добавленные вами серверные профили.",
            13f,
            Color.rgb(177, 196, 215),
            false,
            6
        )

        val importButton = Button(this).apply {
            text = "+ Добавить конфигурацию .conf"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(37, 66, 98), 14)
            setOnClickListener { openConfigPicker() }
        }
        page.addView(importButton, params(height = 52, top = 14))

        profileList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        page.addView(profileList, params(top = 12))

        addText(
            page,
            "Конфигурации хранятся в приватной памяти приложения. Не импортируйте файлы от неизвестных VPN-провайдеров.",
            11f,
            Color.rgb(139, 160, 184),
            false,
            24
        )
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
        ).apply {
            topMargin = dp(top)
        }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun profilesDirectory(): File {
        return File(noBackupFilesDir, "wireguard-profiles").apply { mkdirs() }
    }

    private fun loadProfiles() {
        profiles = profilesDirectory()
            .listFiles { file -> file.isFile && file.extension.equals("conf", true) }
            ?.sortedBy { it.name.lowercase() }
            ?.toMutableList()
            ?: mutableListOf()

        if (selectedProfile == null || profiles.none { it == selectedProfile }) {
            selectedProfile = profiles.firstOrNull()
        }
        renderProfiles()
    }

    private fun renderProfiles() {
        profileList.removeAllViews()

        if (profiles.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Пока нет серверов. Добавьте файл WireGuard .conf от вашего провайдера."
                textSize = 14f
                setTextColor(Color.rgb(174, 194, 215))
                setPadding(dp(15), dp(18), dp(15), dp(18))
                background = rounded(Color.rgb(20, 38, 59), 14)
            }
            profileList.addView(empty, params())
            return
        }

        profiles.forEach { file ->
            val isSelected = file == selectedProfile
            val item = TextView(this).apply {
                text = (if (isSelected) "✓  " else "") + profileTitle(file)
                textSize = 15f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(Color.WHITE)
                setPadding(dp(15), dp(16), dp(15), dp(16))
                background = rounded(
                    if (isSelected) Color.rgb(28, 83, 111) else Color.rgb(20, 38, 59),
                    14
                )
                setOnClickListener {
                    selectedProfile = file
                    renderProfiles()
                    updateConnectionUi()
                }
            }
            profileList.addView(item, params(height = 54, top = 8))
        }
    }

    private fun profileTitle(file: File): String {
        val raw = file.nameWithoutExtension.substringAfter("_", file.nameWithoutExtension)
        return raw.replace('_', ' ').ifBlank { "WireGuard server" }
    }

    private fun openConfigPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_CONFIG_FILE)
    }

    @Deprecated("Legacy activity result API is used to keep the project dependency-light.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == Activity.RESULT_OK) {
                connectSelectedProfile()
            } else {
                connecting = false
                setStatus("Разрешение VPN не предоставлено")
                updateConnectionUi()
            }
            return
        }

        if (requestCode == REQUEST_CONFIG_FILE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Не удалось прочитать выбранный файл")

                val parsed = Config.parse(ByteArrayInputStream(bytes))
                if (parsed.peers.isEmpty()) {
                    throw IllegalArgumentException("В конфигурации нет секции [Peer]")
                }

                val originalName = uri.lastPathSegment
                    ?.substringAfterLast(':')
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.take(32)
                    ?.ifBlank { "WireGuard server" }
                    ?: "WireGuard server"

                val safeName = originalName.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_")
                    .trim()
                    .ifBlank { "WireGuard server" }

                val destination = File(
                    profilesDirectory(),
                    System.currentTimeMillis().toString() + "_" + safeName + ".conf"
                )
                destination.writeBytes(bytes)
                selectedProfile = destination
                loadProfiles()
                setStatus("Профиль добавлен: " + safeName)
                Toast.makeText(this, "Конфигурация добавлена", Toast.LENGTH_SHORT).show()
                updateConnectionUi()
            } catch (error: Exception) {
                Toast.makeText(
                    this,
                    "Ошибка конфигурации: " + (error.localizedMessage ?: "неизвестная ошибка"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun onConnectButtonClick() {
        if (connected || connecting) {
            disconnect()
            return
        }

        if (selectedProfile == null) {
            Toast.makeText(this, "Сначала добавьте файл .conf", Toast.LENGTH_SHORT).show()
            return
        }

        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            connecting = true
            updateConnectionUi()
            startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
        } else {
            connectSelectedProfile()
        }
    }

    private fun connectSelectedProfile() {
        val file = selectedProfile
        if (file == null) {
            connecting = false
            updateConnectionUi()
            return
        }

        connecting = true
        setStatus("Подключение…")
        updateConnectionUi()

        Thread {
            var tunnel: Tunnel? = null
            try {
                val config = FileInputStream(file).use { Config.parse(it) }
                val name = profileTitle(file)
                    .replace(Regex("[^A-Za-z0-9_=+.-]"), "_")
                    .take(15)
                    .ifBlank { "FreeVPN" }

                tunnel = object : Tunnel {
                    override fun getName(): String = name

                    override fun onStateChange(newState: Tunnel.State) {
                        runOnUiThread {
                            if (activeTunnel === this) {
                                connected = newState == Tunnel.State.UP
                                connecting = false
                                setStatus(if (connected) "Подключено: " + profileTitle(file) else "Отключено")
                                updateConnectionUi()
                            }
                        }
                    }
                }

                activeTunnel = tunnel
                backend.setState(tunnel, Tunnel.State.UP, config)

                runOnUiThread {
                    if (activeTunnel === tunnel) {
                        connected = true
                        connecting = false
                        setStatus("Подключено: " + profileTitle(file))
                        updateConnectionUi()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (activeTunnel === tunnel) {
                        activeTunnel = null
                    }
                    connected = false
                    connecting = false
                    setStatus("Не удалось подключиться")
                    updateConnectionUi()
                    Toast.makeText(
                        this,
                        error.localizedMessage ?: "Ошибка запуска VPN",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun disconnect() {
        val tunnel = activeTunnel
        if (tunnel == null) {
            connected = false
            connecting = false
            updateConnectionUi()
            return
        }

        connecting = true
        setStatus("Отключение…")
        updateConnectionUi()

        Thread {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                runOnUiThread {
                    if (activeTunnel === tunnel) activeTunnel = null
                    connected = false
                    connecting = false
                    setStatus("Не подключено")
                    updateConnectionUi()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    connecting = false
                    setStatus("Ошибка отключения")
                    updateConnectionUi()
                    Toast.makeText(
                        this,
                        error.localizedMessage ?: "Не удалось отключить VPN",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun setStatus(value: String) {
        if (::statusText.isInitialized) statusText.text = value
    }

    private fun updateConnectionUi() {
        if (!::connectButton.isInitialized) return

        connectButton.text = when {
            connecting -> "Подождите…"
            connected -> "Отключиться"
            else -> "Подключиться"
        }
        connectButton.isEnabled = !connecting && (connected || selectedProfile != null)
        connectButton.alpha = if (connectButton.isEnabled) 1f else 0.55f
    }
}
