package com.freevpn.client.data.remote

import android.content.Context
import android.net.Uri
import android.util.Log
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
import org.json.JSONObject

/** IP-derived location estimate for a VPN Gate relay. */
data class ServerRegion(
    val region: String,
    val city: String
)

/**
 * Resolves relay IPs to an approximate state/region and city.
 * Results are cached on-device; lookups are done only when a country is opened.
 */
class RegionGeolocationRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("freevpn_region_cache", Context.MODE_PRIVATE)

    private val client = HttpClient(Android) {
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 3_500
            requestTimeoutMillis = 4_500
        }
    }

    fun cachedLocation(ip: String): ServerRegion? {
        val raw = preferences.getString(ip, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val region = json.optString("region").trim()
            val city = json.optString("city").trim()
            if (region.isBlank()) null else ServerRegion(region, city)
        } catch (_: Exception) {
            null
        }
    }

    /** Cache a region already supplied by a trusted enriched server directory. */
    fun cacheLocation(ip: String, region: String, city: String) {
        val cleanRegion = region.trim()
        if (cleanRegion.isBlank()) return
        preferences.edit().putString(
            ip,
            JSONObject().put("region", cleanRegion).put("city", city.trim()).toString()
        ).apply()
    }

    /**
     * Pre-resolve regions in background after the directory has been shown.
     * Bounded concurrency avoids a long serial wait when the user opens a country.
     */
    suspend fun prewarm(servers: List<Pair<String, String>>, concurrency: Int = 12) =
        withContext(Dispatchers.IO) {
            val pending = servers.distinctBy { it.first }
                .filter { cachedLocation(it.first) == null }
            val limiter = Semaphore(concurrency.coerceIn(1, 20))
            coroutineScope {
                pending.map { (ip, countryCode) ->
                    async {
                        limiter.withPermit { lookup(ip, countryCode) }
                    }
                }.awaitAll()
            }
        }

    suspend fun lookup(ip: String, expectedCountryCode: String): ServerRegion? =
        withContext(Dispatchers.IO) {
            cachedLocation(ip)?.let { return@withContext it }

            try {
                val response = client.get("https://ipaddress.to/api/lookup/${Uri.encode(ip)}") {
                    header("User-Agent", "FreeVPN-Android/3.2")
                    header("Accept", "application/json")
                }
                if (response.status.value !in 200..299) {
                    Log.w(TAG, "Geo lookup for $ip returned HTTP ${response.status.value}")
                    return@withContext null
                }

                val root = JSONObject(response.bodyAsText())
                if (!root.optBoolean("success", false)) return@withContext null

                val location = root.optJSONObject("location") ?: return@withContext null
                val responseCountry = location.optString("country_code").trim()
                if (responseCountry.isNotBlank() &&
                    !responseCountry.equals(expectedCountryCode, ignoreCase = true)
                ) {
                    Log.w(TAG, "Geo country mismatch for $ip: $responseCountry != $expectedCountryCode")
                    return@withContext null
                }

                val state = location.optString("state").trim()
                val city = location.optString("city").trim()
                val region = state.ifBlank { city }
                if (region.isBlank()) return@withContext null

                val result = ServerRegion(region = region, city = city)
                preferences.edit().putString(
                    ip,
                    JSONObject()
                        .put("region", result.region)
                        .put("city", result.city)
                        .toString()
                ).apply()
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Geo lookup failed for $ip", e)
                null
            }
        }

    companion object {
        private const val TAG = "FreeVPN_Geo"
    }
}
