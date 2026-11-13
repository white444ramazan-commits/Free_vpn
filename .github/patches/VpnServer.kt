package com.freevpn.client.data.model

/**
 * A public OpenVPN endpoint. Metrics may be unknown for supplementary sources:
 * -1 sessions means "not published", 0 means the feed reported no active sessions.
 */
data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val ping: Int,
    val speed: Long,
    val countryLong: String,
    val countryShort: String,
    val numVpnSessions: Long,
    val uptime: Long,
    val totalUsers: Long,
    val totalTraffic: Long,
    val logType: String,
    val operator: String,
    val message: String,
    val openVpnConfigDataBase64: String,
    val provider: String = "VPN Gate",
    val availability: String = "active",
    val regionName: String = "",
    val cityName: String = ""
)
