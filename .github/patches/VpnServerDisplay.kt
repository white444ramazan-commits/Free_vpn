package com.freevpn.client.data.model

import androidx.compose.runtime.Immutable
import com.freevpn.client.ui.util.countryCodeToFlag
import com.freevpn.client.ui.util.formatScore
import com.freevpn.client.ui.util.formatSpeed

/**
 * Immutable display model. Missing source metrics are rendered as em dashes,
 * not as fabricated zero measurements.
 */
@Immutable
data class VpnServerDisplay(
    val hostName: String,
    val ip: String,
    val score: Long,
    val ping: Int,
    val speed: Long,
    val countryLong: String,
    val countryShort: String,
    val numVpnSessions: Long,
    val flagEmoji: String = countryCodeToFlag(countryShort),
    val formattedSpeed: String = if (speed > 0) formatSpeed(speed) else "—",
    val formattedScore: String = if (score > 0) formatScore(score) else "—",
    val pingText: String = if (ping > 0) "${ping}ms" else "—",
    val qualityScore: Float,
    val overallRankScore: Double,
    val residentialScore: Float,
    val isResidential: Boolean,
    val provider: String = "VPN Gate",
    val availability: String = "active",
    val regionName: String = "",
    val cityName: String = ""
)

private const val PING_MAX_POINTS = 30.0
private const val SPEED_MAX_POINTS = 30.0
private const val SESSIONS_MAX_POINTS = 20.0

private fun sat(value: Double, halfPoint: Double): Double = value / (value + halfPoint)

private fun pingPoints(ping: Int): Double {
    if (ping <= 0) return 0.0
    if (ping < 15) return (ping * 0.5).coerceAtMost(8.0)
    val plausibility = ((450 - ping.coerceIn(15, 450)).toDouble() / 435.0)
    return PING_MAX_POINTS * Math.pow(plausibility, 0.7)
}

private fun speedPoints(speed: Long, totalTraffic: Long): Double {
    val mbps = speed / 1_000_000.0
    var points = SPEED_MAX_POINTS * sat(mbps, 60.0)
    if (mbps > 400 && totalTraffic <= 0) points *= 0.6
    return points
}

/** -1 means this source does not publish concurrent-session counts. */
private fun sessionPoints(sessions: Long): Double {
    if (sessions < 0) return 6.0
    if (sessions == 0L) return 0.0
    val healthy = SESSIONS_MAX_POINTS * sat(sessions.toDouble(), 20.0)
    val overload = sat((sessions - 60).coerceAtLeast(0).toDouble(), 80.0) * 0.5
    return healthy * (1.0 - overload)
}

private fun trackRecordPoints(uptimeMs: Long, totalUsers: Long, totalTrafficBytes: Long): Double {
    val uptimeHours = uptimeMs / 3_600_000.0
    val uptimePoints = 10.0 * sat(uptimeHours, 24.0)
    val trafficGb = totalTrafficBytes / 1_073_741_824.0
    var servedPoints = 10.0 * sat(trafficGb, 100.0)
    if (totalUsers > 0 && totalTrafficBytes <= 0) servedPoints *= 0.3
    return uptimePoints + servedPoints
}

private fun computeScores(
    ping: Int,
    speed: Long,
    numVpnSessions: Long,
    uptime: Long,
    totalUsers: Long,
    totalTraffic: Long
): Pair<Float, Double> {
    var points = pingPoints(ping) +
        speedPoints(speed, totalTraffic) +
        sessionPoints(numVpnSessions) +
        trackRecordPoints(uptime, totalUsers, totalTraffic)

    if (numVpnSessions == 0L) points *= 0.45
    if (ping in 1 until 15) points *= 0.8
    if (uptime in 1 until 3_600_000L) points *= 0.85
    if (speed > 800_000_000L) points *= 0.9

    points = points.coerceIn(2.0, 97.0)
    return points.toFloat() / 100f to points
}

private val RESIDENTIAL_RDNS_TOKENS = listOf(
    "dynamic", "dyn-ip", "pppoe", "adsl", "vdsl", "dsl-", ".dsl", "fios", "cable",
    "cpe", "pool", "home", "client-", "modem", "fiber", "fibre", "broadband",
    "res-", ".res.", "wireless", "wimax", "mobile", "gprs", "hsd", "wan-"
)

private val DATACENTER_TOKENS = listOf(
    "vps", "dedicated", "hosting", "hosted", "datacenter", "data-center", "colocation",
    "cloud", "amazonaws", "ec2", "compute", "googleusercontent", "azure", "ovh",
    "hetzner", "digitalocean", "linode", "vultr", "contabo", "scaleway", "leaseweb",
    "alibaba", "tencent", "oraclecloud", "choopa", "m247", "datacamp", "server",
    "rack", "node", "vds", "iaas", "paas", "cdn"
)

private fun calculateResidentialScore(
    hostName: String,
    ip: String,
    operator: String,
    speedBps: Long,
    numVpnSessions: Long,
    totalTrafficBytes: Long
): Float {
    val host = hostName.lowercase()
    val opAndHost = "$operator $hostName".lowercase()
    var points = 0.0

    if (RESIDENTIAL_RDNS_TOKENS.any { host.contains(it) }) points += 0.35
    if (host.isBlank() || host == ip || !host.contains('.')) points += 0.15
    if (DATACENTER_TOKENS.any { host.contains(it) || opAndHost.contains(" $it") }) points -= 0.50

    val mbps = speedBps / 1_000_000.0
    points += when {
        mbps in 1.0..100.0 -> 0.20
        mbps <= 250.0 -> 0.05
        speedBps <= 0L -> 0.0
        else -> -0.15
    }
    points += when {
        numVpnSessions in 1..8 -> 0.15
        numVpnSessions < 0 -> 0.0
        numVpnSessions <= 25 -> 0.05
        numVpnSessions > 60 -> -0.15
        else -> 0.0
    }
    val trafficGb = totalTrafficBytes / 1_073_741_824.0
    if (trafficGb in 0.01..500.0) points += 0.10
    if (trafficGb > 5_000) points -= 0.15
    return points.coerceIn(0.0, 1.0).toFloat()
}

private const val RESIDENTIAL_THRESHOLD = 0.55f

fun VpnServer.toDisplay(): VpnServerDisplay {
    val residentialScore = calculateResidentialScore(
        hostName, ip, operator, speed, numVpnSessions, totalTraffic
    )
    val (quality, rank) = computeScores(
        ping, speed, numVpnSessions, uptime, totalUsers, totalTraffic
    )
    return VpnServerDisplay(
        hostName = hostName,
        ip = ip,
        score = score,
        ping = ping,
        speed = speed,
        countryLong = countryLong,
        countryShort = countryShort,
        numVpnSessions = numVpnSessions,
        qualityScore = quality,
        overallRankScore = rank,
        residentialScore = residentialScore,
        isResidential = residentialScore >= RESIDENTIAL_THRESHOLD,
        provider = provider,
        availability = availability,
        regionName = regionName,
        cityName = cityName
    )
}
