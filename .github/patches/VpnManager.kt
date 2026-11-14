package com.freevpn.client.data.vpn

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.Preferences
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.ProfileManager.setTemporaryProfile
import de.blinkt.openvpn.core.VPNLaunchHelper.startOpenVpn
import java.io.StringReader
import java.util.Locale

class VpnManager(private val context: Context) {

    private var activeConfig: String? = null

    /**
     * Launch OpenVPN with unlimited protocol-level reconnect attempts.
     * The remote volunteer relay can still become unavailable, but the client
     * keeps retrying instead of exiting after a couple of transient failures.
     */
    fun startVpn(base64ConfigString: String): Boolean {
        return try {
            activeConfig = base64ConfigString

            // Screen-off inactivity pause is unsuitable for a VPN that must stay active.
            Preferences.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean("screenoff", false)
                .apply()

            val decodedBytes = Base64.decode(base64ConfigString, Base64.DEFAULT)
            val originalConfig = String(decodedBytes, Charsets.UTF_8)
            val replacedDirectives = setOf(
                "connect-retry",
                "connect-retry-max",
                "connect-timeout",
                "resolv-retry",
                "ping",
                "ping-restart",
                "ping-exit",
                "inactive",
                "keepalive",
                "explicit-exit-notify",
                "nobind"
            )

            // Replace short fail-fast/idle-exit options supplied by a relay config.
            val preservedLines = originalConfig.lineSequence().filterNot { line ->
                val trimmed = line.trimStart()
                val directive = trimmed.split(Regex("\\s+"), limit = 2).firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
                directive in replacedDirectives
            }.toList()

            val ovpnConfigText = buildString {
                append(preservedLines.joinToString("\n"))
                append("\n")
                appendLine("nobind")
                appendLine("connect-retry 2 10")
                appendLine("connect-timeout 8")
                appendLine("resolv-retry infinite")
                appendLine("ping 10")
                appendLine("ping-restart 45")
                if (!originalConfig.contains("persist-key", ignoreCase = true)) {
                    appendLine("persist-key")
                }
                if (!originalConfig.contains("persist-tun", ignoreCase = true)) {
                    appendLine("persist-tun")
                }
                val explicitlyTcp = Regex("(?im)^\\s*proto\\s+tcp(?:-client)?\\s*$")
                    .containsMatchIn(originalConfig)
                if (!explicitlyTcp) {
                    appendLine("explicit-exit-notify 2")
                }
            }

            val parser = ConfigParser()
            parser.parseConfig(StringReader(ovpnConfigText))
            val profile = parser.convertProfile()

            profile.mUsername = "vpn"
            profile.mPassword = "vpn"
            profile.mProfileCreator = context.packageName

            setTemporaryProfile(context, profile)
            startOpenVpn(profile, context, "VPN Gate Connection", true)
            Log.i(TAG, "OpenVPN launched with unlimited reconnect and screen-off pause disabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed to parse/launch config", e)
            false
        }
    }

    /** @return true if a stop request was dispatched to the service. */
    fun stopVpn(): Boolean {
        return try {
            ProfileManager.setConntectedVpnProfileDisconnected(context)
            val disconnectIntent = Intent(context, OpenVPNService::class.java).apply {
                action = OpenVPNService.DISCONNECT_VPN
            }
            context.startService(disconnectIntent)
            activeConfig = null
            true
        } catch (e: Exception) {
            Log.e(TAG, "stopVpn failed", e)
            false
        }
    }

    fun clearActiveConfig() {
        activeConfig = null
    }

    /** Tears down the tunnel but remembers its config so resume can restart it. */
    fun pauseVpn(): Boolean {
        val lastRunning = activeConfig
        val stopped = stopVpn()
        activeConfig = lastRunning
        return stopped
    }

    fun resumeVpn(fallbackConfig: String? = null): Boolean {
        val configToResume = activeConfig ?: fallbackConfig
        return if (configToResume != null) {
            startVpn(configToResume)
        } else {
            Log.w(TAG, "resumeVpn called with no stored or fallback config")
            false
        }
    }

    companion object {
        private const val TAG = "VpnManager"
    }
}
