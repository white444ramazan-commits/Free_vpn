package com.freevpn.client.data.remote

import android.content.Context
import android.util.Base64
import android.util.Log
import com.freevpn.client.data.model.VpnServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.Locale

sealed interface VpnGateResult {
    data class Success(
        val servers: List<VpnServer>,
        val duplicatesRemoved: Int = 0
    ) : VpnGateResult
    data object EmptyFeed : VpnGateResult
    data class NetworkError(val cause: Throwable?) : VpnGateResult
}

/**
 * Combines VPN Gate with two maintained public profile indexes.
 * The app caches full profiles on-device and renders that cache before refreshing.
 */
class VpnGateApi(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("freevpn_directory_cache", Context.MODE_PRIVATE)
    private val cacheFile = File(appContext.filesDir, "freevpn_servers_v3.json")
    private val extraCacheFile = File(appContext.filesDir, "freevpn_supplement_v3.json")

    private val client = HttpClient(Android) {
        install(HttpTimeout) {
            connectTimeoutMillis = 6_000
            socketTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
    }
    private val largeClient = HttpClient(Android) {
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 20_000
            requestTimeoutMillis = 60_000
        }
    }

    private val vpngateSources = listOf(
        "https://www.vpngate.net/api/iphone/",
        "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/data/vpngate.csv",
        "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@main/data/vpngate.csv",
        "https://fastly.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@main/data/vpngate.csv"
    )
    private val autoOvpnReadme =
        "https://raw.githubusercontent.com/9xN/auto-ovpn/main/README.md"
    private val autoOvpnRawBase =
        "https://raw.githubusercontent.com/9xN/auto-ovpn/main/"
    private val meridianSnapshot =
        "https://raw.githubusercontent.com/6Kmfi6HP/vpngate-meridian/gh-pages/json/data.maxmind.json"

    private val countryCodes = mapOf(
        "Japan" to "JP", "Korea Republic of" to "KR", "Russian Federation" to "RU",
        "Thailand" to "TH", "United States" to "US", "United States of America" to "US",
        "Viet Nam" to "VN", "Vietnam" to "VN", "Romania" to "RO", "Argentina" to "AR",
        "China" to "CN", "Mexico" to "MX", "Indonesia" to "ID", "India" to "IN",
        "Ukraine" to "UA", "United Arab Emirates" to "AE", "Croatia" to "HR",
        "Croatia (LOCAL Name: Hrvatska)" to "HR", "Canada" to "CA", "United Kingdom" to "GB",
        "Taiwan" to "TW", "Belarus" to "BY", "Peru" to "PE", "Malaysia" to "MY",
        "Brazil" to "BR", "France" to "FR", "Germany" to "DE", "Netherlands" to "NL",
        "Italy" to "IT", "Spain" to "ES", "Portugal" to "PT", "Latvia" to "LV",
        "Venezuela" to "VE", "Philippines" to "PH", "Myanmar" to "MM", "Sweden" to "SE",
        "Finland" to "FI", "Poland" to "PL", "Turkey" to "TR", "Türkiye" to "TR",
        "South Africa" to "ZA", "Australia" to "AU", "Singapore" to "SG", "Hong Kong" to "HK"
    )

    suspend fun loadCachedServers(): VpnGateResult = withContext(Dispatchers.IO) {
        runCatching { readServerFile(cacheFile) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { VpnGateResult.Success(it) }
            ?: VpnGateResult.EmptyFeed
    }

    suspend fun saveCachedServers(servers: List<VpnServer>) = withContext(Dispatchers.IO) {
        writeServerFile(cacheFile, servers)
    }

    suspend fun parseVpnGate(): VpnGateResult {
        val csv = try {
            fetchVpnGateCsv()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "VPN Gate directory refresh failed", e)
            return VpnGateResult.NetworkError(e)
        }
        val servers = parseCsv(csv)
        return if (servers.isEmpty()) VpnGateResult.EmptyFeed else VpnGateResult.Success(servers)
    }

    /**
     * Merge fresh VPN Gate data with public indexes. Supplementary feeds are
     * cached for six hours; a failed refresh retains their last good snapshot.
     */
    suspend fun mergeSupplementalServers(base: List<VpnServer>): VpnGateResult {
        val now = System.currentTimeMillis()
        val cachedSupplement = withContext(Dispatchers.IO) {
            runCatching { readServerFile(extraCacheFile) }.getOrDefault(emptyList())
        }
        val freshUntil = prefs.getLong(KEY_EXTRA_CACHE_TIME, 0L)
        val extras = if (cachedSupplement.isNotEmpty() && now - freshUntil < EXTRA_CACHE_TTL) {
            cachedSupplement
        } else {
            val known = base.mapTo(HashSet()) { it.ip }
            val fetched = coroutineScope {
                val auto = async {
                    runCatching { fetchAutoOvpn(known) }
                        .onFailure { Log.w(TAG, "auto-ovpn source failed", it) }
                        .getOrDefault(emptyList())
                }
                val meridian = async {
                    runCatching { fetchMeridian(known) }
                        .onFailure { Log.w(TAG, "Meridian source failed", it) }
                        .getOrDefault(emptyList())
                }
                auto.await() + meridian.await()
            }.distinctBy { it.ip }

            if (fetched.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    writeServerFile(extraCacheFile, fetched)
                    prefs.edit().putLong(KEY_EXTRA_CACHE_TIME, now).apply()
                }
                fetched
            } else cachedSupplement
        }

        val combined = base + extras
        val merged = combined.groupBy { it.ip }.mapNotNull { (_, candidates) ->
            candidates.maxByOrNull { server ->
                (if (server.availability == "active") 1_000_000L else 0L) + server.score
            }
        }.sortedWith(
            compareBy<VpnServer> { it.availability != "active" }
                .thenByDescending { it.score }
                .thenBy { it.ping.takeIf { ping -> ping > 0 } ?: Int.MAX_VALUE }
        )

        withContext(Dispatchers.IO) { writeServerFile(cacheFile, merged) }
        return if (merged.isEmpty()) VpnGateResult.EmptyFeed
        else VpnGateResult.Success(merged, combined.size - merged.size)
    }

    private suspend fun fetchVpnGateCsv(): String {
        var lastError: Throwable? = null
        for (url in vpngateSources) {
            val sourceHost = url.substringAfter("://").substringBefore("/")
            try {
                val response = client.get(url) {
                    header("User-Agent", "FreeVPN-Android/3.3")
                    header("Accept", "text/csv,text/plain,*/*")
                }
                if (response.status.value !in 200..299) {
                    throw IOException("$sourceHost HTTP ${response.status.value}")
                }
                val body = response.bodyAsText()
                val headerFound = body.lineSequence().any {
                    it.contains("HostName") && it.contains("OpenVPN_ConfigData_Base64")
                }
                val rowFound = body.lineSequence().any {
                    val line = it.trim()
                    line.isNotEmpty() && !line.startsWith("*") && !line.startsWith("#") &&
                        line.count { char -> char == ',' } >= 14
                }
                if (!headerFound || !rowFound) throw IOException("$sourceHost invalid CSV")
                Log.i(TAG, "VPN Gate directory loaded from $sourceHost")
                return body
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "VPN Gate source failed: $sourceHost", e)
            }
        }
        throw IOException("All VPN Gate sources failed", lastError)
    }

    /** Keep profiles even if the VPN Gate feed has not measured ping/speed yet. */
    private fun parseCsv(csv: String): List<VpnServer> {
        val parsed = mutableListOf<VpnServer>()
        try {
            val reader = BufferedReader(StringReader(csv))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val row = line!!.trim()
                if (row.isEmpty() || row.startsWith("*") || row.startsWith("#")) continue
                val fields = row.split(",").map { it.trim().trim('"') }
                if (fields.size < 15) continue
                val config = fields.last()
                val ip = fields[1]
                if (config.length < MIN_CONFIG_LENGTH || !isValidIpv4(ip)) continue
                val parsedPing = fields[3].toIntOrNull() ?: -1
                val ping = parsedPing.takeIf { it in 1..600 } ?: -1
                val speed = fields[4].toLongOrNull()?.takeIf { it > 0L } ?: 0L
                parsed.add(
                    VpnServer(
                        hostName = fields[0],
                        ip = ip,
                        score = fields[2].toLongOrNull() ?: 0L,
                        ping = ping,
                        speed = speed,
                        countryLong = fields[5].ifBlank { "Unknown" },
                        countryShort = fields[6].ifBlank { "ZZ" },
                        numVpnSessions = fields[7].toLongOrNull() ?: -1L,
                        uptime = fields[8].toLongOrNull() ?: 0L,
                        totalUsers = fields[9].toLongOrNull() ?: 0L,
                        totalTraffic = fields[10].toLongOrNull() ?: 0L,
                        logType = fields[11],
                        operator = fields[12],
                        message = fields.subList(13, fields.size - 1).joinToString(","),
                        openVpnConfigDataBase64 = config,
                        provider = "VPN Gate",
                        availability = "active"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN Gate CSV parse failed", e)
        }
        return parsed.groupBy { it.ip }.mapNotNull { (_, rows) -> rows.maxByOrNull { it.score } }
    }

    /** Five-minute GitHub-generated index of public OpenVPN profiles. */
    private suspend fun fetchAutoOvpn(existingIps: Set<String>): List<VpnServer> {
        val response = client.get(autoOvpnReadme) {
            header("User-Agent", "FreeVPN-Android/3.3")
            header("Accept", "text/plain")
        }
        if (response.status.value !in 200..299) throw IOException("auto-ovpn HTTP ${response.status.value}")
        val readme = response.bodyAsText()
        val updated = Regex("(?i)last updated on:\\s*([^\\n]+)")
            .find(readme)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        data class Candidate(
            val host: String, val ip: String, val ping: Int, val speed: Long,
            val country: String, val code: String, val score: Double, val configPath: String
        )

        val candidates = readme.lineSequence().mapNotNull { line ->
            if (!line.trimStart().startsWith("|") || !line.contains("Download")) return@mapNotNull null
            val columns = line.trim().split("|").map { it.trim() }.drop(1).dropLast(1)
            if (columns.size < 7) return@mapNotNull null
            val ip = columns[1]
            if (!isValidIpv4(ip) || ip in existingIps) return@mapNotNull null
            val country = columns[4]
            val code = countryCodes[country] ?: return@mapNotNull null
            val path = Regex("\\((?:\\./)?(configs/[^)]+\\.ovpn)\\)")
                .find(columns[5])?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val speed = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*Mbps", RegexOption.IGNORE_CASE)
                .find(columns[3])?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?.let { (it * 1_000_000.0).toLong() } ?: 0L
            Candidate(
                host = columns[0],
                ip = ip,
                ping = columns[2].toIntOrNull()?.takeIf { it in 1..600 } ?: -1,
                speed = speed,
                country = country,
                code = code,
                score = columns[6].toDoubleOrNull() ?: 0.0,
                configPath = path
            )
        }.toList()

        val limiter = Semaphore(8)
        return coroutineScope {
            candidates.map { item ->
                async {
                    limiter.withPermit {
                        try {
                            val profile = client.get(autoOvpnRawBase + item.configPath) {
                                header("User-Agent", "FreeVPN-Android/3.3")
                                header("Accept", "text/plain")
                            }
                            if (profile.status.value !in 200..299) return@withPermit null
                            val config = profile.bodyAsText()
                            if (config.length < MIN_CONFIG_LENGTH ||
                                !config.contains("remote ") || !config.contains("<ca>")
                            ) return@withPermit null
                            VpnServer(
                                hostName = item.host,
                                ip = item.ip,
                                score = (item.score * 1_000).toLong(),
                                ping = item.ping,
                                speed = item.speed,
                                countryLong = item.country,
                                countryShort = item.code,
                                numVpnSessions = -1L,
                                uptime = 0L,
                                totalUsers = 0L,
                                totalTraffic = 0L,
                                logType = "auto-ovpn GitHub index",
                                operator = "Public VPN Gate volunteer relay",
                                message = if (updated.isBlank()) "Source supplied profile."
                                    else "Source index updated $updated",
                                openVpnConfigDataBase64 = Base64.encodeToString(
                                    config.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                                ),
                                provider = "Auto OVPN",
                                availability = "active"
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Profile fetch failed for ${item.ip}", e)
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * VPN Meridian snapshot retains recent profiles and adds GeoIP city/subdivision.
     * Only currently active nodes and nodes missed at most twice in the last 72 h
     * are eligible. The latter are explicitly labeled RECENTLY SEEN in the UI.
     */
    private suspend fun fetchMeridian(existingIps: Set<String>): List<VpnServer> {
        val response = largeClient.get(meridianSnapshot) {
            header("User-Agent", "FreeVPN-Android/3.3")
            header("Accept", "application/json")
        }
        if (response.status.value !in 200..299) throw IOException("Meridian HTTP ${response.status.value}")
        val root = JSONObject(response.bodyAsText())
        val rows: JSONArray = root.optJSONObject("data")?.optJSONArray("servers") ?: return emptyList()
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        val result = ArrayList<VpnServer>()

        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val ip = row.optString("ip").trim()
            if (!isValidIpv4(ip) || ip in existingIps) continue
            val missCount = row.optInt("missCount", Int.MAX_VALUE)
            val lastSeen = row.optLong("lastSeen", 0L)
            if (lastSeen < cutoff || missCount > 2) continue

            val config = row.optString("openvpn_configdata_base64")
            if (config.length < MIN_CONFIG_LENGTH) continue
            val country = row.optString("countrylong").ifBlank { "Unknown" }
            val countryCode = row.optString("countryshort").ifBlank { countryCodes[country] ?: "ZZ" }
            val geo = row.optJSONObject("maxmind")
            val city = geo?.optJSONObject("city")?.optString("name").orEmpty().trim()
            val subdivision = geo?.optJSONObject("subdivision")?.optString("name").orEmpty().trim()
            val state = row.optString("status") == "active" && missCount == 0

            result.add(
                VpnServer(
                    hostName = row.optString("hostname").ifBlank { ip },
                    ip = ip,
                    score = 0L,
                    ping = row.optString("ping").toIntOrNull()?.takeIf { it in 1..600 } ?: -1,
                    speed = row.optString("speed").toLongOrNull()?.takeIf { it > 0L } ?: 0L,
                    countryLong = country,
                    countryShort = countryCode,
                    numVpnSessions = -1L,
                    uptime = 0L,
                    totalUsers = 0L,
                    totalTraffic = 0L,
                    logType = "VPN Meridian snapshot",
                    operator = "Public VPN Gate volunteer relay",
                    message = if (state) "Active in last directory snapshot."
                        else "Recently seen; availability can no longer be guaranteed.",
                    openVpnConfigDataBase64 = config,
                    provider = "Meridian",
                    availability = if (state) "active" else "recent",
                    regionName = subdivision.ifBlank { city },
                    cityName = city
                )
            )
        }
        return result
    }

    private suspend fun readServerFile(file: File): List<VpnServer> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) return@withContext emptyList()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val rows = root.optJSONArray("servers") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val config = row.optString("config")
                if (config.length < MIN_CONFIG_LENGTH) continue
                add(
                    VpnServer(
                        hostName = row.optString("host"),
                        ip = row.optString("ip"),
                        score = row.optLong("score"),
                        ping = row.optInt("ping", -1),
                        speed = row.optLong("speed"),
                        countryLong = row.optString("country"),
                        countryShort = row.optString("countryCode", "ZZ"),
                        numVpnSessions = row.optLong("sessions", -1L),
                        uptime = row.optLong("uptime"),
                        totalUsers = row.optLong("users"),
                        totalTraffic = row.optLong("traffic"),
                        logType = row.optString("logType"),
                        operator = row.optString("operator"),
                        message = row.optString("message"),
                        openVpnConfigDataBase64 = config,
                        provider = row.optString("provider", "VPN Gate"),
                        availability = row.optString("availability", "active"),
                        regionName = row.optString("region"),
                        cityName = row.optString("city")
                    )
                )
            }
        }
    }

    private suspend fun writeServerFile(file: File, servers: List<VpnServer>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(
                JSONObject()
                    .put("host", server.hostName)
                    .put("ip", server.ip)
                    .put("score", server.score)
                    .put("ping", server.ping)
                    .put("speed", server.speed)
                    .put("country", server.countryLong)
                    .put("countryCode", server.countryShort)
                    .put("sessions", server.numVpnSessions)
                    .put("uptime", server.uptime)
                    .put("users", server.totalUsers)
                    .put("traffic", server.totalTraffic)
                    .put("logType", server.logType)
                    .put("operator", server.operator)
                    .put("message", server.message)
                    .put("config", server.openVpnConfigDataBase64)
                    .put("provider", server.provider)
                    .put("availability", server.availability)
                    .put("region", server.regionName)
                    .put("city", server.cityName)
            )
        }
        val json = JSONObject().put("schema", 1).put("savedAt", System.currentTimeMillis())
            .put("servers", array).toString()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json, Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            file.writeText(json, Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split(".")
        return parts.size == 4 && parts.all {
            val number = it.toIntOrNull()
            number != null && number in 0..255
        }
    }

    companion object {
        private const val TAG = "FreeVPN_Directory"
        private const val MIN_CONFIG_LENGTH = 150
        private const val EXTRA_CACHE_TTL = 6 * 60 * 60 * 1000L
        private const val RECENT_WINDOW_MS = 72 * 60 * 60 * 1000L
        private const val KEY_EXTRA_CACHE_TIME = "extra_cache_updated"
    }
}
