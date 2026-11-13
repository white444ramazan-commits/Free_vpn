package com.freevpn.client.data.remote

import android.util.Log
import com.freevpn.client.data.model.VpnServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

/**
 * Directory result keeps a real network failure separate from a valid but empty feed.
 */
sealed interface VpnGateResult {
    data class Success(
        val servers: List<VpnServer>,
        val duplicatesRemoved: Int = 0
    ) : VpnGateResult

    data object EmptyFeed : VpnGateResult
    data class NetworkError(val cause: Throwable?) : VpnGateResult
}

class VpnGateApi {
    private val client = HttpClient(Android) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 12_000
            requestTimeoutMillis = 15_000
        }
    }

    /*
     * Keep the official VPN Gate API first. If the domain is blocked or has a
     * temporary outage, use public HTTPS mirrors of the same CSV directory.
     * The mirrors publish VPN Gate's directory; they do not operate the VPN nodes.
     */
    private val directorySources = listOf(
        "https://www.vpngate.net/api/iphone/",
        "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/data/vpngate.csv",
        "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@main/data/vpngate.csv",
        "https://fastly.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@main/data/vpngate.csv"
    )

    /** Fetches a valid CSV from the first reachable source, trying mirrors on failure. */
    suspend fun fetchVpnGate(): String {
        var lastError: Throwable? = null

        for (url in directorySources) {
            val sourceHost = url.substringAfter("://").substringBefore("/")
            try {
                val response = client.get(url) {
                    header("User-Agent", "FreeVPN-Android/3.1")
                    header("Accept", "text/csv,text/plain,*/*")
                }

                if (response.status.value !in 200..299) {
                    throw IOException("$sourceHost returned HTTP ${response.status.value}")
                }

                val rawData = response.bodyAsText()
                val hasExpectedHeader = rawData.lineSequence().any { line ->
                    line.contains("HostName") && line.contains("OpenVPN_ConfigData_Base64")
                }
                val hasRows = rawData.lineSequence().any { line ->
                    val trimmed = line.trim()
                    trimmed.isNotEmpty() &&
                        !trimmed.startsWith("*") &&
                        !trimmed.startsWith("#") &&
                        trimmed.count { it == ',' } >= 14
                }

                if (!hasExpectedHeader || !hasRows) {
                    throw IOException("$sourceHost returned an invalid or empty directory")
                }

                Log.i("VPN_Gate_REQUEST", "Directory loaded from $sourceHost")
                return rawData
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w("VPN_Gate_REQUEST", "Source failed: $sourceHost", e)
            }
        }

        throw IOException("All VPN Gate directory sources failed", lastError)
    }

    suspend fun parseVpnGate(): VpnGateResult {
        val rawCsv = try {
            fetchVpnGate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("VPN_Gate_REQUEST", "All directory sources failed", e)
            return VpnGateResult.NetworkError(e)
        }

        if (rawCsv.isBlank()) return VpnGateResult.EmptyFeed

        val serverList = mutableListOf<VpnServer>()
        try {
            val reader = BufferedReader(StringReader(rawCsv))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!.trim()
                if (currentLine.startsWith("*") || currentLine.startsWith("#")) continue

                val tokens = currentLine.split(",").map { it.trim().trim('"') }
                if (tokens.size < 15) continue

                val rawConfig = tokens.last()
                if (rawConfig.length < 150) continue

                val ip = tokens[1]
                if (ip.isBlank()) continue

                val ping = tokens[3].toIntOrNull() ?: -1
                if (ping <= 0 || ping > 600) continue

                val speed = tokens[4].toLongOrNull() ?: 0L
                if (speed <= 0) continue

                serverList.add(
                    VpnServer(
                        hostName = tokens[0],
                        ip = ip,
                        score = tokens[2].toLongOrNull() ?: 0L,
                        ping = ping,
                        speed = speed,
                        countryLong = tokens[5],
                        countryShort = tokens[6],
                        numVpnSessions = tokens[7].toLongOrNull() ?: 0L,
                        uptime = tokens[8].toLongOrNull() ?: 0L,
                        totalUsers = tokens[9].toLongOrNull() ?: 0L,
                        totalTraffic = tokens[10].toLongOrNull() ?: 0L,
                        logType = tokens[11],
                        operator = tokens[12],
                        message = tokens.subList(13, tokens.size - 1).joinToString(","),
                        openVpnConfigDataBase64 = rawConfig
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("VPN_Gate_Parse", "Error parsing directory CSV", e)
            return VpnGateResult.NetworkError(e)
        }

        if (serverList.isEmpty()) return VpnGateResult.EmptyFeed

        val deduped = serverList
            .groupBy { it.ip }
            .mapNotNull { (_, rows) -> rows.maxByOrNull { it.score } }

        return VpnGateResult.Success(
            servers = deduped,
            duplicatesRemoved = serverList.size - deduped.size
        )
    }
}
