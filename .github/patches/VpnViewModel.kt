package com.freevpn.client.ui.main

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freevpn.client.data.model.VpnServer
import com.freevpn.client.data.model.VpnServerDisplay
import com.freevpn.client.data.model.toDisplay
import com.freevpn.client.data.remote.VpnGateResult
import com.freevpn.client.data.remote.RegionGeolocationRepository
import com.freevpn.client.data.remote.ServerRegion
import com.freevpn.client.data.repository.VpnRepository
import com.freevpn.client.data.vpn.VpnManager
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Connection states the UI renders. */
enum class ConnectionState {
    SEARCHING_NODES, // Initial app open / gateway search state
    IDLE,            // Optimal gateway ready
    CONNECTING,      // Initial connection attempt
    RECONNECTING,    // Resuming from pause or auto-retrying a dropped tunnel
    CONNECTED,       // Secure encrypted tunnel active
    PAUSED,          // Tunnel paused / suspended
    DISCONNECTED,    // Explicitly disconnected
    ERROR            // Connection failed or server unreachable
}

/** What the user last asked the state machine to do. */
private enum class ConnectionIntent { NONE, CONNECT, SWITCH, RESUME_FROM_PAUSE, AUTO_RETRY, PAUSE, STOP }

/** Classified form of an OpenVPN status callback. */
private enum class VpnEvent { CONNECTED, PROGRESS, AUTH_FAILED, TEARDOWN, OTHER }

enum class SortCriteria {
    SCORE,
    PING,
    SPEED,
    SESSIONS, // Most verified active sessions (reliable nodes)
    OVERALL   // Composite: low ping + high speed + verified active sessions vitality
}

/**
 * A timestamped log entry for the Logs tab.
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val event: String,
    val detail: String
)

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VpnRepository(application)
    private val regionRepository = RegionGeolocationRepository(application)
    val vpnManager = VpnManager(application)

    // ── UI State ────────────────────────────────────────────────────────────────
    var vpnStatus by mutableStateOf("Searching best servers...")
    var bestServerConfig by mutableStateOf<String?>(null)
    var pendingConfig: String? = null

    /** User-facing detailed error diagnostics for the main screen. */
    var lastErrorHeadline by mutableStateOf<String?>(null)
        private set
    var lastErrorDetail by mutableStateOf<String?>(null)
        private set

    /** Structured connection state for the UI. */
    var connectionState by mutableStateOf(ConnectionState.SEARCHING_NODES)
        private set

    /** Status shown on the badge above the connect button. */
    var statusLabel by mutableStateOf("SCANNING NODES...")
        private set

    /** Label for the current attempt; survives intermediate progress events. */
    private var attemptLabel: String? = null

    /** Full server objects (heavy Base64 configs); never exposed to Compose. */
    private var fullServers: List<VpnServer> = emptyList()

    /** IP → full VpnServer, used when the user taps a server. */
    private var serverLookup: Map<String, VpnServer> = emptyMap()

    /** Lightweight display list safe for LazyColumn diffing. */
    var displayServers by mutableStateOf<List<VpnServerDisplay>>(emptyList())
        private set

    /** The selected active server for connection and display. */
    var selectedServer by mutableStateOf<VpnServer?>(null)
        private set

    /** True if the active server is the top scored auto-selected default gateway. */
    var isAutoSelectedBest by mutableStateOf(true)
        private set

    /** True if background refresh is active. */
    var isRefreshing by mutableStateOf(false)
        private set

    /** Non-null when the last directory fetch failed (or returned nothing usable). */
    var lastLoadError by mutableStateOf<String?>(null)
        private set

    /** Active sort criteria. */
    var activeSort by mutableStateOf(SortCriteria.OVERALL)
        private set

    /** Search query filter. */
    var searchQuery by mutableStateOf("")

    /** Selected country filter. */
    var selectedCountryFilter by mutableStateOf<String?>(null)

    /** Selected IP-geolocated region inside the selected country. */
    var selectedRegionFilter by mutableStateOf<String?>(null)

    /** Cached approximate region/city for each relay IP. */
    var regionLocations by mutableStateOf<Map<String, ServerRegion>>(emptyMap())
        private set

    var regionLookupCountry by mutableStateOf<String?>(null)
        private set
    var regionLookupProgress by mutableIntStateOf(0)
        private set
    var regionLookupTotal by mutableIntStateOf(0)
        private set
    private var regionLookupJob: Job? = null

    /** Once connected, keep retrying the selected relay until the user stops it. */
    private var autoReconnectAfterDrop = false

    /**
     * Residential-only view: show just the hosts most likely to be a volunteer's
     * home connection (home IPs dodge bot-checks that datacenter IPs attract).
     * When active, entries are ordered most-likely-residential first, best-ranked
     * among equals second.
     */
    var residentialOnly by mutableStateOf(false)

    /** How many of the current nodes are classified as likely-residential. */
    val residentialCount by derivedStateOf {
        displayServers.count { it.isResidential }
    }

    /** Connection logs for the Terminal tab. */
    var connectionLogs by mutableStateOf<List<LogEntry>>(emptyList())
        private set

    /** Cached filter+sort so scrolling stays smooth. */
    val filteredServers by derivedStateOf {
        val query = searchQuery.trim()
        val list = displayServers.filter { server ->
            val location = regionLocations[server.ip]
            val matchesQuery = if (query.isEmpty()) {
                true
            } else {
                server.countryLong.contains(query, ignoreCase = true) ||
                    server.countryShort.contains(query, ignoreCase = true) ||
                    server.ip.contains(query, ignoreCase = true) ||
                    server.hostName.contains(query, ignoreCase = true) ||
                    location?.region?.contains(query, ignoreCase = true) == true ||
                    location?.city?.contains(query, ignoreCase = true) == true
            }
            val matchesCountry = selectedCountryFilter?.let { it == server.countryLong } ?: true
            val matchesRegion = selectedRegionFilter?.let { it == location?.region } ?: true
            (!residentialOnly || server.isResidential) &&
                matchesQuery && matchesCountry && matchesRegion
        }
        if (residentialOnly) {
            return@derivedStateOf list.sortedWith(
                compareByDescending<VpnServerDisplay> { it.residentialScore }
                    .thenByDescending { it.overallRankScore }
            )
        }
        when (activeSort) {
            SortCriteria.SCORE -> list.sortedByDescending { it.score }
            SortCriteria.PING -> list.sortedBy { it.ping }
            SortCriteria.SPEED -> list.sortedByDescending { it.speed }
            SortCriteria.SESSIONS -> list.sortedByDescending { it.numVpnSessions }
            SortCriteria.OVERALL -> list.sortedByDescending { it.overallRankScore }
        }
    }

    /** List of all unique available countries for filtering. */
    val availableCountries by derivedStateOf {
        displayServers.map { it.countryLong }.distinct().sorted()
    }

    /** Country → server count for display badges. */
    val countryServerCounts by derivedStateOf {
        displayServers.groupingBy { it.countryLong }.eachCount()
    }

    /**
     * Resolve approximate regions lazily when a country is opened. The IP database
     * is only a geographic estimate; its results are cached locally and reused.
     */
    fun loadRegionsForCountry(country: String) {
        if (regionLookupCountry == country && regionLookupJob?.isActive == true) return
        regionLookupJob?.cancel()

        val countryNodes = fullServers.filter { it.countryLong == country }
        val cached = countryNodes.mapNotNull { server ->
            regionRepository.cachedLocation(server.ip)?.let { server.ip to it }
        }.toMap()
        if (cached.isNotEmpty()) regionLocations = regionLocations + cached

        regionLookupTotal = countryNodes.size
        regionLookupProgress = cached.size
        val unresolved = countryNodes.filter { regionLocations[it.ip] == null }
        if (unresolved.isEmpty()) {
            regionLookupCountry = null
            return
        }

        regionLookupCountry = country
        regionLookupJob = viewModelScope.launch {
            try {
                // Resolve small parallel batches. The region screen is already visible
                // with cached/inline locations while any unresolved IPs are enriched.
                unresolved.chunked(12).forEach { batch ->
                    val results = coroutineScope {
                        batch.map { server ->
                            async {
                                server to regionRepository.lookup(server.ip, server.countryShort)
                            }
                        }.awaitAll()
                    }
                    val completed = results.mapNotNull { (server, location) ->
                        location?.let { server.ip to it }
                    }.toMap()
                    regionLocations = regionLocations + completed
                    regionLookupProgress += batch.size
                }
            } finally {
                if (regionLookupCountry == country) regionLookupCountry = null
            }
        }
    }

    fun cancelRegionLookups() {
        regionLookupJob?.cancel()
        regionLookupCountry = null
    }

    /**
     * Search query for the quick-switch sheet, independent from the Servers-tab
     * filters so a leftover filter can't narrow the quick-change list.
     */
    var quickSearchQuery by mutableStateOf("")

    /** Unfiltered-by-tab server list for the quick switch sheet, best-ranked first. */
    val quickChangeServers by derivedStateOf {
        val q = quickSearchQuery.trim()
        val list = if (q.isEmpty()) {
            displayServers
        } else {
            displayServers.filter { server ->
                server.countryLong.contains(q, ignoreCase = true) ||
                        server.countryShort.contains(q, ignoreCase = true) ||
                        server.ip.contains(q, ignoreCase = true) ||
                        server.hostName.contains(q, ignoreCase = true)
            }
        }
        val ranked = list.sortedByDescending { it.overallRankScore }
        // Keep the top-ranked node first so it stays tagged BEST SERVER.
        val bestIp = displayServers.maxByOrNull { it.overallRankScore }?.ip
        if (bestIp == null || ranked.firstOrNull()?.ip == bestIp) {
            ranked
        } else {
            ranked.sortedBy { if (it.ip == bestIp) 0 else 1 } // stable sort keeps rank order
        }
    }

    /** IP of the top-ranked node in the directory. */
    val bestServerIp: String?
        get() = displayServers.maxByOrNull { it.overallRankScore }?.ip

    /** System timestamp (ms) when the current connection was established. 0 if not connected. */
    var connectionStartTime by mutableLongStateOf(0L)
        private set

    // Real telemetry readouts from the tunnel's byte counters.
    var uploadSpeed by mutableStateOf("— Mbps")
        private set
    var downloadSpeed by mutableStateOf("— Mbps")
        private set
    var totalSessionMb by mutableFloatStateOf(0f)
        private set

    private var telemetryJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectionWatchdogJob: Job? = null
    private var tunnelHealthJob: Job? = null
    private var retryJob: Job? = null
    private val retryAttemptedIps = linkedSetOf<String>()
    private var loadJob: Job? = null
    private var loadGeneration = 0

    // All state transitions funnel through [transitionTo].
    private var activeIntent = ConnectionIntent.NONE
    private var connectAttempts = 0

    /** True once the current attempt produced its first OpenVPN progress event;
     *  lets us tell a dying previous session's teardown from a real failure. */
    private var attemptSawProgress = false

    /** Delayed launch of a switched-to gateway; cancelled if the user acts first. */
    private var switchLaunchJob: Job? = null
    private var reconciledWithRunningService = false

    var pausedElapsedSeconds by mutableLongStateOf(0L)
        private set

    init {
        loadServers()
    }

    // ── Logging ─────────────────────────────────────────────────────────────────

    /** Add a timestamped log entry, capped at 200 with newest at the bottom. */
    fun addLog(event: String, detail: String) {
        connectionLogs = (connectionLogs + LogEntry(event = event, detail = detail)).takeLast(200)
    }

    /** Clears all terminal logs. */
    fun clearLogs() {
        connectionLogs = emptyList()
        addLog("SYSTEM", "Terminal console cleared")
    }

    // ── State machine core ──────────────────────────────────────────────────────

    /** Single choke point for every state write; owns per-state side effects. */
    private fun transitionTo(newState: ConnectionState, reason: String) {
        val changed = connectionState != newState
        connectionState = newState
        when (newState) {
            ConnectionState.CONNECTED -> {
                connectionWatchdogJob?.cancel()
                retryJob?.cancel()
                connectAttempts = 0
                activeIntent = ConnectionIntent.NONE
                autoReconnectAfterDrop = true
                retryAttemptedIps.clear()
                selectedServer?.ip?.let { retryAttemptedIps.add(it) }
                startTunnelHealthMonitor()
                lastErrorHeadline = null
                lastErrorDetail = null
                if (connectionStartTime <= 0L) {
                    connectionStartTime =
                        System.currentTimeMillis() - (pausedElapsedSeconds * 1000)
                }
                pausedElapsedSeconds = 0L
                startTelemetry()
            }
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                if (changed) startConnectionWatchdog()
            }
            else -> {
                connectionWatchdogJob?.cancel()
                stopTunnelHealthMonitor()
                stopTelemetry()
            }
        }
        // The badge always reflects reality. During an attempt, the specific context
        // label (changing server / retrying n/m) survives progress events; terminal
        // states clear it and show the canonical text for the new state.
        if (newState == ConnectionState.CONNECTING || newState == ConnectionState.RECONNECTING) {
            statusLabel = attemptLabel ?: defaultStatusLabel(newState)
        } else {
            attemptLabel = null
            statusLabel = when (newState) {
                // "Optimal gateway ready" is only honest when the card actually shows
                // the auto-picked best node — not after a manual selection.
                ConnectionState.IDLE ->
                    if (isAutoSelectedBest) "OPTIMAL GATEWAY READY" else "GATEWAY READY"
                else -> defaultStatusLabel(newState)
            }
        }
        if (changed) addLog("STATE", "$newState ← $reason")
    }

    /** Canonical badge text for each coarse state; call sites may refine it. */
    private fun defaultStatusLabel(state: ConnectionState): String = when (state) {
        ConnectionState.SEARCHING_NODES -> "SCANNING NODES..."
        ConnectionState.IDLE -> "GATEWAY READY"
        ConnectionState.CONNECTING -> "CONNECTING..."
        ConnectionState.RECONNECTING -> "RECONNECTING..."
        ConnectionState.CONNECTED -> "SECURE TUNNEL ACTIVE"
        ConnectionState.PAUSED -> "PAUSED · TAP TO RESUME"
        ConnectionState.DISCONNECTED -> "DISCONNECTED"
        ConnectionState.ERROR -> "SERVER UNREACHABLE · TAP TO RETRY"
    }

    private fun logIgnored(action: String) {
        addLog("IGNORED", "$action ignored while $connectionState")
    }

    /** Terminal failure: kill the process, surface honest diagnostics, stop everything. */
    private fun failConnection(headline: String, detail: String, logLine: String) {
        connectionWatchdogJob?.cancel()
        retryJob?.cancel()
        switchLaunchJob?.cancel()
        connectAttempts = 0
        activeIntent = ConnectionIntent.NONE
        autoReconnectAfterDrop = false
        vpnManager.stopVpn()
        stopTelemetry()
        lastErrorHeadline = headline
        lastErrorDetail = detail
        vpnStatus = headline
        transitionTo(ConnectionState.ERROR, headline)
        addLog("ERROR", logLine)
    }

    private fun beginAttempt() {
        attemptSawProgress = false
    }

    /**
     * A connection attempt failed (or the tunnel dropped). Retry the same server a
     * couple of times with visible status before showing the hard error banner —
     * volunteer nodes flap, and one bad moment shouldn't look like "server down".
     */
    /**
     * Keep retrying while the user has not pressed Stop. Two failures on one relay
     * trigger a move to another currently-active relay before the next attempts.
     */
    private fun scheduleConnectRetry(headline: String, detail: String, dropLogLine: String) {
        if (bestServerConfig == null) {
            failConnection(
                headline, detail,
                "$dropLogLine No usable server configuration is available."
            )
            return
        }

        connectAttempts++
        if (connectAttempts % RETRIES_PER_SERVER == 0) {
            rotateRetryServer()
        }
        val config = bestServerConfig ?: return
        stopTelemetry()
        activeIntent = ConnectionIntent.AUTO_RETRY
        vpnStatus = "Reconnecting to ${selectedServer?.countryLong ?: "gateway"}... (attempt $connectAttempts)"
        addLog(
            "RETRY",
            "$dropLogLine Retrying through ${selectedServer?.countryLong ?: "gateway"} " +
                "(${selectedServer?.ip ?: "?"}) — attempt $connectAttempts."
        )
        attemptLabel = "RECONNECTING (ATTEMPT $connectAttempts)..."
        transitionTo(ConnectionState.RECONNECTING, "retry attempt $connectAttempts")

        retryJob?.cancel()
        switchLaunchJob?.cancel()
        val waitMs = (RETRY_BACKOFF_BASE_MS * connectAttempts.coerceAtMost(5))
            .coerceAtMost(MAX_RETRY_BACKOFF_MS)
        retryJob = viewModelScope.launch {
            delay(waitMs)
            if (connectionState == ConnectionState.RECONNECTING &&
                activeIntent == ConnectionIntent.AUTO_RETRY
            ) {
                beginAttempt()
                val target = bestServerConfig ?: config
                addLog(
                    "CONNECT",
                    "Launching retry $connectAttempts → ${selectedServer?.ip ?: "?"}..."
                )
                if (!vpnManager.startVpn(target)) {
                    addLog("LAUNCH", "OpenVPN rejected the profile; trying another active relay.")
                    rotateRetryServer()
                    scheduleConnectRetry(
                        headline,
                        detail,
                        "OpenVPN could not start the selected profile."
                    )
                }
            }
        }
    }

    /** Move to an untried active server; if all have failed, cycle through them again. */
    private fun rotateRetryServer(): Boolean {
        val currentIp = selectedServer?.ip
        val ranked = displayServers
            .asSequence()
            .filter { it.availability.equals("active", ignoreCase = true) }
            .sortedByDescending { it.overallRankScore }
            .mapNotNull { serverLookup[it.ip] }
            .toList()

        if (ranked.isEmpty()) return false
        var next = ranked.firstOrNull { it.ip != currentIp && it.ip !in retryAttemptedIps }
        if (next == null) {
            retryAttemptedIps.clear()
            currentIp?.let { retryAttemptedIps.add(it) }
            next = ranked.firstOrNull { it.ip != currentIp }
        }
        if (next == null) return false

        selectedServer = next
        bestServerConfig = next.openVpnConfigDataBase64
        retryAttemptedIps.add(next.ip)
        val bestIp = displayServers.maxByOrNull { it.overallRankScore }?.ip
        isAutoSelectedBest = next.ip == bestIp
        addLog(
            "SERVER",
            "Relay unavailable; switching automatically to ${next.countryLong} (${next.ip})."
        )
        return true
    }

    /** A real HTTPS 204 check is routed through Android's active VPN by default. */
    private suspend fun hasInternetThroughTunnel(): Boolean = withContext(Dispatchers.IO) {
        val connection = URL(TUNNEL_PROBE_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TUNNEL_PROBE_TIMEOUT_MS
            connection.readTimeout = TUNNEL_PROBE_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.connect()
            connection.responseCode == 204
        } catch (e: Exception) {
            Log.w("FreeVPN_Health", "VPN tunnel connectivity probe failed", e)
            false
        } finally {
            connection.disconnect()
        }
    }

    /** Detects a stale VPN icon: the tunnel must pass real traffic, not just report CONNECTED. */
    private fun startTunnelHealthMonitor() {
        if (tunnelHealthJob?.isActive == true) return
        tunnelHealthJob = viewModelScope.launch {
            delay(TUNNEL_PROBE_INITIAL_DELAY_MS)
            var failures = 0
            while (connectionState == ConnectionState.CONNECTED) {
                val healthy = hasInternetThroughTunnel()
                if (connectionState != ConnectionState.CONNECTED) break
                if (healthy) {
                    failures = 0
                } else {
                    failures++
                    addLog(
                        "HEALTH",
                        "VPN reports connected but the internet probe failed ($failures/2)."
                    )
                    if (failures >= 2) {
                        scheduleConnectRetry(
                            "VPN Tunnel Has No Internet",
                            "OpenVPN reported a connection, but HTTPS traffic stopped passing through the tunnel. Free VPN is reconnecting.",
                            "Tunnel connectivity probe failed twice."
                        )
                        break
                    }
                }
                delay(TUNNEL_PROBE_INTERVAL_MS)
            }
        }
    }

    private fun stopTunnelHealthMonitor() {
        tunnelHealthJob?.cancel()
        tunnelHealthJob = null
    }

    private fun startConnectionWatchdog() {
        if (activeIntent == ConnectionIntent.STOP || activeIntent == ConnectionIntent.PAUSE) return
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = viewModelScope.launch {
            delay(15_000L) // 15 seconds fail-fast timeout for volunteer nodes
            if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.RECONNECTING) {
                scheduleConnectRetry(
                    "Node Unresponsive (Timeout)",
                    "The volunteer host at ${selectedServer?.ip ?: "gateway"} did not respond in time. It is likely offline or blocking UDP/TCP packets.",
                    "Node unreachable (15s timeout)."
                )
            }
        }
    }

    // ── OpenVPN status callbacks ────────────────────────────────────────────────

    /**
     * Called by the state listener in MainActivity. Decides the next state from the
     * library's typed [ConnectionStatus] level plus the user's current intent — raw
     * string matching is used only to classify which error message to display.
     */
    fun updateVpnStatus(state: String?, logMessage: String?, level: ConnectionStatus?) {
        vpnStatus = "$state : $logMessage"

        when (classifyEvent(state, level)) {
            VpnEvent.CONNECTED -> {
                if (activeIntent == ConnectionIntent.STOP) {
                    // Stray CONNECTED replay from the daemon we just told to die.
                    vpnManager.stopVpn()
                    return
                }
                val wasRetrying = connectAttempts > 0 || activeIntent == ConnectionIntent.AUTO_RETRY
                transitionTo(ConnectionState.CONNECTED, "handshake complete ($state)")
                vpnStatus = "Connected · ${selectedServer?.countryLong ?: "Gateway"}"
                addLog(
                    "CONNECTED",
                    "Secure tunnel active → ${selectedServer?.countryLong ?: "?"} (${selectedServer?.ip ?: "?"})" +
                            if (wasRetrying) " — succeeded on retry." else "."
                )
            }

            VpnEvent.PROGRESS -> {
                // Progress events are only meaningful during an active attempt;
                // stray events while idle/paused/stopped are informational noise.
                val attemptInProgress = activeIntent !in listOf(
                    ConnectionIntent.NONE,
                    ConnectionIntent.PAUSE,
                    ConnectionIntent.STOP
                ) || connectionState == ConnectionState.CONNECTING ||
                        connectionState == ConnectionState.RECONNECTING
                if (!attemptInProgress) return
                // While the switch settle delay is running, NOTHING has been launched
                // yet — any progress event here belongs to the dying previous session
                // and must not mark this attempt as started nor cancel the launch.
                if (switchLaunchJob?.isActive == true) return
                // This attempt is demonstrably alive — a teardown from now on is
                // ITS failure, not a previous session's goodbye.
                attemptSawProgress = true
                val target = when {
                    activeIntent == ConnectionIntent.RESUME_FROM_PAUSE ->
                        ConnectionState.RECONNECTING
                    connectionState == ConnectionState.RECONNECTING ->
                        ConnectionState.RECONNECTING
                    else -> ConnectionState.CONNECTING
                }
                // The restart is demonstrably underway — its own failures must surface.
                if (activeIntent == ConnectionIntent.SWITCH ||
                    activeIntent == ConnectionIntent.RESUME_FROM_PAUSE ||
                    activeIntent == ConnectionIntent.AUTO_RETRY
                ) {
                    activeIntent = ConnectionIntent.NONE
                }
                transitionTo(target, "$state ${logMessage ?: ""}".trim())
            }

            VpnEvent.AUTH_FAILED -> {
                if (activeIntent == ConnectionIntent.STOP ||
                    activeIntent == ConnectionIntent.PAUSE
                ) return
                // Handshake failures are a server fault like any other — they go
                // through the SAME retry chain as timeouts, so every connection
                // gets an identical number of attempts before the banner appears.
                scheduleConnectRetry(
                    "Handshake Rejected by Gateway",
                    "The volunteer host at ${selectedServer?.ip ?: "remote"} rejected authentication or the TLS handshake (${logMessage ?: "AUTH_FAILED"}).",
                    "Handshake rejected by remote node: ${logMessage ?: "AUTH_FAILED"}."
                )
            }

            VpnEvent.TEARDOWN -> {
                when (activeIntent) {
                    // Expected consequence of a user action or of the restart/retry in flight.
                    ConnectionIntent.STOP,
                    ConnectionIntent.PAUSE,
                    ConnectionIntent.SWITCH,
                    ConnectionIntent.RESUME_FROM_PAUSE,
                    ConnectionIntent.AUTO_RETRY -> return
                    else -> {}
                }
                when (connectionState) {
                    ConnectionState.CONNECTED -> scheduleConnectRetry(
                        "Tunnel Dropped by Gateway",
                        "The remote relay at ${selectedServer?.ip ?: "gateway"} terminated the tunnel unexpectedly.",
                        "Remote server dropped the connection."
                    )
                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                        if (!attemptSawProgress) {
                            // The current process has reported NOTHING yet — an OpenVPN
                            // process always emits progress before it can die, so this
                            // teardown belongs to the PREVIOUS session (e.g. the gateway
                            // we just disconnected from during a switch). Not evidence
                            // that the new server is unreachable.
                            addLog("IGNORED", "Teardown ($state) before first progress of this attempt — previous session's exit.")
                            return
                        }
                        scheduleConnectRetry(
                            "Gateway Unreachable",
                            "The volunteer host at ${selectedServer?.ip ?: "gateway"} closed the connection before a tunnel could be established (${logMessage ?: state ?: "process exited"}).",
                            "Connection attempt failed: ${logMessage ?: state ?: "process exited"}."
                        )
                    }
                    else -> return // Idle noise; nothing to react to.
                }
            }

            VpnEvent.OTHER -> return
        }
    }

    private fun classifyEvent(state: String?, level: ConnectionStatus?): VpnEvent {
        // Prefer the typed level; fall back to string matching only when absent.
        return when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> VpnEvent.CONNECTED
            ConnectionStatus.LEVEL_AUTH_FAILED -> VpnEvent.AUTH_FAILED
            ConnectionStatus.LEVEL_NOTCONNECTED -> VpnEvent.TEARDOWN
            ConnectionStatus.LEVEL_START,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT,
            ConnectionStatus.LEVEL_NONETWORK -> VpnEvent.PROGRESS
            null -> classifyEventFromString(state)
            else -> VpnEvent.OTHER // LEVEL_VPNPAUSED etc. — library soft-pause, unused here.
        }
    }

    private fun classifyEventFromString(state: String?): VpnEvent {
        if (state == null) return VpnEvent.OTHER
        val upper = state.uppercase()
        return when {
            upper.contains("CONNECTED") && !upper.contains("NOT") -> VpnEvent.CONNECTED
            upper.contains("AUTH_FAILED") -> VpnEvent.AUTH_FAILED
            upper.contains("NOPROCESS") ||
                    upper.contains("EXITING") ||
                    upper.contains("DISCONNECTED") -> VpnEvent.TEARDOWN
            upper.contains("RECONNECTING") ||
                    upper.contains("CONNECTING") ||
                    upper.contains("WAIT") ||
                    upper.contains("AUTH") ||
                    upper.contains("GET_CONFIG") ||
                    upper.contains("ASSIGN_IP") ||
                    upper.contains("RESOLVE") ||
                    upper.contains("TCP_CONNECT") -> VpnEvent.PROGRESS
            else -> VpnEvent.OTHER
        }
    }

    // ── Server directory loading ────────────────────────────────────────────────

    fun loadServers() {
        val gen = ++loadGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val isTunnelActive = connectionState in listOf(
                ConnectionState.CONNECTED,
                ConnectionState.CONNECTING,
                ConnectionState.RECONNECTING,
                ConnectionState.PAUSED
            )

            if (!isTunnelActive && displayServers.isEmpty() &&
                connectionState != ConnectionState.ERROR &&
                connectionState != ConnectionState.DISCONNECTED
            ) {
                transitionTo(ConnectionState.SEARCHING_NODES, "loading saved directory")
            }

            // First paint: show the previous successful directory without waiting for
            // any network request. This also restores server configs after process death.
            if (displayServers.isEmpty()) {
                when (val cached = repository.getCachedServers()) {
                    is VpnGateResult.Success -> {
                        if (gen != loadGeneration) return@launch
                        applyFetchedServers(cached, 0L, isTunnelActive)
                        addLog("CACHE", "Restored ${cached.servers.size} saved VPN profiles instantly.")
                    }
                    else -> Unit
                }
            }

            isRefreshing = true
            addLog("FETCH", "Refreshing VPN Gate and supplemental OpenVPN directories...")

            try {
                val startedAt = System.currentTimeMillis()
                when (val primary = repository.getServers()) {
                    is VpnGateResult.Success -> {
                        if (gen != loadGeneration) return@launch
                        val primaryDuration = System.currentTimeMillis() - startedAt
                        applyFetchedServers(primary, primaryDuration, isTunnelActive)
                        isRefreshing = false

                        // Show the primary feed now, then fill in extra countries/profiles
                        // in the background. The visible list is never blocked by this step.
                        val supplementStartedAt = System.currentTimeMillis()
                        when (val merged = repository.mergeSupplementalServers(primary.servers)) {
                            is VpnGateResult.Success -> {
                                if (gen != loadGeneration) return@launch
                                applyFetchedServers(
                                    merged,
                                    System.currentTimeMillis() - supplementStartedAt,
                                    isTunnelActive
                                )
                                addLog(
                                    "READY",
                                    "Combined directory: ${merged.servers.size} unique profiles from multiple sources."
                                )
                            }
                            is VpnGateResult.EmptyFeed,
                            is VpnGateResult.NetworkError -> {
                                // Keep the already-rendered primary directory; an optional
                                // source failing must never blank or block the working list.
                                addLog("INFO", "Supplementary feeds unavailable; VPN Gate list remains usable.")
                            }
                        }
                    }

                    is VpnGateResult.EmptyFeed,
                    is VpnGateResult.NetworkError -> {
                        val fallbackStartedAt = System.currentTimeMillis()
                        val base = fullServers
                        addLog("INFO", "Primary VPN Gate feed unavailable; checking independent public indexes.")
                        when (val fallback = repository.mergeSupplementalServers(base)) {
                            is VpnGateResult.Success -> {
                                if (gen != loadGeneration) return@launch
                                applyFetchedServers(
                                    fallback,
                                    System.currentTimeMillis() - fallbackStartedAt,
                                    isTunnelActive
                                )
                                addLog("READY", "Loaded ${fallback.servers.size} profiles from available fallback indexes.")
                            }
                            is VpnGateResult.EmptyFeed,
                            is VpnGateResult.NetworkError -> {
                                if (displayServers.isEmpty()) {
                                    lastLoadError = "No public OpenVPN server directory could be reached"
                                    if (!isTunnelActive) transitionTo(ConnectionState.ERROR, "all directory sources unavailable")
                                } else {
                                    addLog("INFO", "Refresh failed; cached servers remain available.")
                                }
                            }
                        }
                    }
                }
                reconcileWithRunningService()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("FreeVPN_Directory", "Directory refresh failed", e)
                if (displayServers.isEmpty()) {
                    lastLoadError = e.message ?: "Could not load VPN directory"
                    if (!isTunnelActive) transitionTo(ConnectionState.ERROR, "directory refresh failed")
                } else {
                    addLog("INFO", "Refresh failed; keeping cached server list.")
                }
            } finally {
                if (gen == loadGeneration) isRefreshing = false
            }
        }
    }

    private fun applyFetchedServers(
        result: VpnGateResult.Success,
        fetchDurationMs: Long,
        isTunnelActive: Boolean
    ) {
        lastLoadError = null
        val fetched = result.servers
        fullServers = fetched.distinctBy { it.ip } // defensive: LazyColumn keys are IPs
        serverLookup = fullServers.associateBy { it.ip }

        // Some supplemental sources already include GeoIP. Seed it locally so
        // country → region opens without waiting for a network lookup.
        fullServers.forEach { server ->
            if (server.regionName.isNotBlank()) {
                regionRepository.cacheLocation(server.ip, server.regionName, server.cityName)
            }
        }
        regionLocations = fullServers.mapNotNull { server ->
            val inline = server.regionName.takeIf { it.isNotBlank() }?.let {
                ServerRegion(it, server.cityName)
            }
            val location = inline ?: regionRepository.cachedLocation(server.ip)
            location?.let { server.ip to it }
        }.toMap()
        displayServers = fullServers.map { it.toDisplay() }

        // Persist complete configs so the next launch paints the list immediately.
        viewModelScope.launch {
            runCatching { repository.saveCachedServers(fullServers) }
                .onFailure { Log.w("FreeVPN_Directory", "Could not save directory cache", it) }
        }

        // GeoIP lookups are lazy: resolving every relay on startup caused
        // unnecessary background traffic and delayed the initial menu.
        val currentActiveIp = selectedServer?.ip
        val currentInFetched = currentActiveIp?.let { serverLookup[it] }

        if (isTunnelActive) {
            // Preserve the running connection's profile exactly: refresh must never
            // swap the config a pause/resume would later reuse mid-tunnel.
            if (currentInFetched != null) {
                selectedServer = currentInFetched
            }
            addLog(
                "READY",
                "Directory updated in ${fetchDurationMs}ms — ${fetched.size} nodes " +
                        "(${result.duplicatesRemoved} duplicate rows skipped). Active tunnel preserved."
            )
        } else {
            if (selectedServer == null || currentInFetched == null) {
                val best = fetched
                    .filter { it.availability.equals("active", ignoreCase = true) }
                    .maxByOrNull { it.score }
                    ?: fetched.maxByOrNull { it.score }
                    ?: fetched.first()
                selectedServer = best
                bestServerConfig = best.openVpnConfigDataBase64
                isAutoSelectedBest = true
            } else {
                selectedServer = currentInFetched
                bestServerConfig = currentInFetched.openVpnConfigDataBase64
                val topScore = fetched.maxOfOrNull { it.score } ?: 0L
                isAutoSelectedBest = (selectedServer?.score == topScore)
            }
            // A successful fetch resolves both the initial search and a prior
            // fetch-error state (e.g. the Servers-tab retry path).
            if (connectionState == ConnectionState.SEARCHING_NODES ||
                connectionState == ConnectionState.ERROR
            ) {
                lastErrorHeadline = null
                lastErrorDetail = null
                transitionTo(ConnectionState.IDLE, "directory ready")
            }
            vpnStatus = "Gateway: ${selectedServer?.countryLong} (${selectedServer?.ip})"
        }
        val best = displayServers.maxByOrNull { it.overallRankScore }
        addLog(
            "READY",
            "${fetched.size} usable nodes analyzed in ${fetchDurationMs}ms " +
                    "(top pick: ${best?.countryLong ?: "?"} · ${best?.ping ?: "?"}ms). Directory updated."
        )
    }

    /**
     * If the service was resurrected by START_STICKY while no UI existed, adopt the
     * live tunnel instead of pretending we're idle. Runs once per process.
     */
    private fun reconcileWithRunningService() {
        if (reconciledWithRunningService) return
        reconciledWithRunningService = true
        if (VpnStatus.isVPNActive() &&
            connectionState in listOf(
                ConnectionState.IDLE,
                ConnectionState.SEARCHING_NODES,
                ConnectionState.DISCONNECTED
            )
        ) {
            attemptLabel = "REATTACHING TO ACTIVE TUNNEL..."
            transitionTo(ConnectionState.RECONNECTING, "reattaching to resurrected service")
            vpnStatus = "Reattaching to active tunnel..."
            addLog("SYSTEM", "Detached VPN service detected — reattaching UI.")
        }
    }

    /** Retrieve the full VpnServer (with config) by IP. Used only on tap. */
    fun getFullServer(ip: String): VpnServer? = serverLookup[ip]

    fun selectServer(server: VpnServer) {
        selectedServer = server
        bestServerConfig = server.openVpnConfigDataBase64
        retryAttemptedIps.clear()
        retryAttemptedIps.add(server.ip)
        val topScore = fullServers.maxOfOrNull { it.score } ?: 0L
        isAutoSelectedBest = (server.score == topScore)

        // Picking a server clears any prior error or paused session bookkeeping.
        if (connectionState == ConnectionState.ERROR) {
            lastErrorHeadline = null
            lastErrorDetail = null
            transitionTo(ConnectionState.IDLE, "error cleared by server selection")
        }

        if (connectionState == ConnectionState.PAUSED) {
            activeIntent = ConnectionIntent.NONE
            pausedElapsedSeconds = 0L
            connectionStartTime = 0L
            transitionTo(ConnectionState.IDLE, "paused session replaced by selection")
            vpnManager.clearActiveConfig()
        }

        vpnStatus = "Selected: ${server.countryLong} (${server.ip})"
        addLog("SELECT", "${server.countryLong} (${server.ip}) — ${server.ping}ms")
    }

    fun setSortCriteria(sort: SortCriteria) {
        activeSort = sort
    }

    /**
     * Dedicated first-class action to switch servers seamlessly,
     * whether currently CONNECTED, CONNECTING, RECONNECTING, PAUSED, or IDLE.
     */
    fun onServerSwitchRequested(newServer: VpnServer, onStartConnection: (String) -> Unit) {
        val wasActive = (connectionState == ConnectionState.CONNECTED ||
                connectionState == ConnectionState.CONNECTING ||
                connectionState == ConnectionState.RECONNECTING ||
                connectionState == ConnectionState.PAUSED)

        if (wasActive && selectedServer?.ip == newServer.ip) {
            logIgnored("switch to already-active node")
            return
        }

        val previousIp = selectedServer?.ip ?: "?"
        selectServer(newServer)

        if (wasActive) {
            autoReconnectAfterDrop = true
            retryJob?.cancel()
            switchLaunchJob?.cancel()
            connectAttempts = 0
            activeIntent = ConnectionIntent.SWITCH
            lastErrorHeadline = null
            lastErrorDetail = null
            connectionStartTime = 0L
            pausedElapsedSeconds = 0L
            totalSessionMb = 0f
            stopTelemetry()

            // Phase 1 — tear down whatever is running. Its exit events land while the
            // SWITCH intent still owns them, so they can never read as a new failure.
            addLog("SWITCH", "Disconnecting current gateway ($previousIp)...")
            vpnManager.stopVpn()

            attemptLabel = "CHANGING SERVER → ${newServer.countryLong.uppercase()}..."
            transitionTo(ConnectionState.CONNECTING, "user switch → ${newServer.ip}")
            vpnStatus = "Switching to ${newServer.countryLong}..."

            // Phase 2 — launch the new gateway only after the old daemon has died.
            switchLaunchJob = viewModelScope.launch {
                delay(SWITCH_SETTLE_MS)
                if (connectionState != ConnectionState.CONNECTING ||
                    activeIntent != ConnectionIntent.SWITCH
                ) {
                    return@launch // user stopped / switched elsewhere meanwhile
                }
                beginAttempt()
                addLog(
                    "SWITCH",
                    "Connecting new gateway → ${newServer.countryLong} (${newServer.ip}) · ${newServer.ping}ms · ${newServer.numVpnSessions} sessions..."
                )
                onStartConnection(newServer.openVpnConfigDataBase64)
            }
        }
    }

    // ── Button Actions ──────────────────────────────────────────────────────────

    /**
     * Arms the next connect as a single-shot retry (error banner's RETRY button):
     * exactly one attempt, no automatic follow-ups — a failure lands straight back
     * on the error banner with its buttons. The flag survives the permission-dialog
     * continuation call to [onConnectRequested] and is cleared by any terminal state.
     */
    fun prepareManualRetry() {
        if (connectionState != ConnectionState.ERROR || bestServerConfig == null) {
            logIgnored("manual retry")
            return
        }
        autoReconnectAfterDrop = true
        retryAttemptedIps.clear()
        selectedServer?.ip?.let { retryAttemptedIps.add(it) }
        attemptLabel = "RETRYING CONNECTION..."
    }

    fun onConnectRequested() {
        val allowed = connectionState in listOf(
            ConnectionState.IDLE,
            ConnectionState.DISCONNECTED,
            ConnectionState.ERROR,
            ConnectionState.PAUSED
        ) || (connectionState == ConnectionState.CONNECTING &&
                activeIntent == ConnectionIntent.CONNECT) // continuation after permission dialog

        if (!allowed) {
            logIgnored("connect")
            return
        }

        if (connectionState == ConnectionState.PAUSED) {
            // A stored pause session is about to be replaced — clear it cleanly.
            vpnManager.stopVpn()
        }

        retryJob?.cancel()
        switchLaunchJob?.cancel()
        activeIntent = ConnectionIntent.CONNECT
        lastErrorHeadline = null
        lastErrorDetail = null
        connectionStartTime = 0L
        pausedElapsedSeconds = 0L
        totalSessionMb = 0f
        // Keep the session active until the user explicitly presses Stop.
        autoReconnectAfterDrop = true
        retryAttemptedIps.clear()
        selectedServer?.ip?.let { retryAttemptedIps.add(it) }
        connectAttempts = 0
        beginAttempt()
        transitionTo(ConnectionState.CONNECTING, "user connect")
        vpnStatus = "Connecting to ${selectedServer?.countryLong ?: "Gateway"}..."
        addLog(
            "CONNECT",
            "Initiating tunnel → ${selectedServer?.countryLong ?: "?"} (${selectedServer?.ip ?: "?"}) · ${selectedServer?.ping ?: "?"}ms..."
        )
    }

    /** Config parse/launch failed inside VpnManager — report immediately, don't wait out the watchdog. */
    fun onLaunchFailed() {
        if (!autoReconnectAfterDrop) {
            failConnection(
                "Failed to Start Tunnel",
                "OpenVPN could not launch the selected profile. Choose another server and try again.",
                "Tunnel launch failed and automatic reconnect is not armed."
            )
            return
        }
        rotateRetryServer()
        scheduleConnectRetry(
            "Failed to Start Tunnel",
            "OpenVPN could not launch the current profile. Free VPN is trying another active relay.",
            "Tunnel launch failed — profile rejected."
        )
    }

    /** The user denied (or dismissed) the system VPN permission dialog. */
    fun onPermissionDenied() {
        pendingConfig = null
        if (connectionState == ConnectionState.CONNECTING) {
            failConnection(
                "Permission Required",
                "OpenGate needs the Android VPN permission to open an encrypted tunnel. Grant it and try again.",
                "VPN permission denied by user."
            )
            statusLabel = "VPN PERMISSION REQUIRED"
        } else {
            addLog("IGNORED", "VPN permission denied outside a connection attempt.")
        }
    }

    fun onPauseClicked() {
        if (connectionState != ConnectionState.CONNECTED) {
            logIgnored("pause")
            return
        }
        activeIntent = ConnectionIntent.PAUSE
        connectionWatchdogJob?.cancel()
        retryJob?.cancel()
        if (connectionStartTime > 0L) {
            pausedElapsedSeconds = (System.currentTimeMillis() - connectionStartTime) / 1000
        }
        vpnManager.pauseVpn()
        transitionTo(ConnectionState.PAUSED, "user pause")
        vpnStatus = "Paused (Normal Internet Active)"
        addLog("PAUSED", "Tunnel paused. Local traffic bypasses VPN.")
    }

    fun onResumeClicked() {
        if (connectionState != ConnectionState.PAUSED) {
            logIgnored("resume")
            return
        }
        retryJob?.cancel()
        connectAttempts = 0
        activeIntent = ConnectionIntent.RESUME_FROM_PAUSE
        beginAttempt()
        attemptLabel = null
        transitionTo(ConnectionState.RECONNECTING, "user resume")
        vpnStatus = "Reconnecting to ${selectedServer?.countryLong}..."
        addLog("RESUME", "Resuming tunnel → ${selectedServer?.countryLong ?: "?"} (${selectedServer?.ip ?: "?"}) after ${pausedElapsedSeconds}s pause...")
        if (!vpnManager.resumeVpn(bestServerConfig)) {
            // No stored session survived (e.g., process death) — fail honestly now
            // instead of showing RECONNECTING until the watchdog fires.
            failConnection(
                "Paused Session Expired",
                "The paused tunnel session was lost and cannot be resumed. Please connect again.",
                "Resume failed: no stored session. Please connect again."
            )
        }
    }

    fun onStopClicked() {
        if (connectionState !in listOf(
                ConnectionState.CONNECTED,
                ConnectionState.CONNECTING,
                ConnectionState.RECONNECTING,
                ConnectionState.PAUSED,
                ConnectionState.ERROR
            )
        ) {
            logIgnored("stop")
            return
        }
        retryJob?.cancel()
        switchLaunchJob?.cancel()
        connectAttempts = 0
        autoReconnectAfterDrop = false
        activeIntent = ConnectionIntent.STOP
        connectionStartTime = 0L
        pausedElapsedSeconds = 0L
        totalSessionMb = 0f
        vpnManager.stopVpn()
        transitionTo(ConnectionState.DISCONNECTED, "user stop")
        vpnStatus = "Disconnected / Stopped"
        addLog("STOP", "VPN stopped by user. Tunnel closed, traffic now bypasses OpenGate.")
    }

    // ── Telemetry (real tunnel counters — never simulated) ──────────────────────

    /**
     * Receives actual per-second byte counts from the library. Registered while
     * connected; before the first report the UI shows "—" rather than fabricated
     * values. Callbacks arrive on a library thread, so hop to the main thread.
     */
    private val byteCountListener = VpnStatus.ByteCountListener { inBytes, outBytes, diffIn, diffOut ->
        mainHandler.post {
            downloadSpeed = String.format("%.1f Mbps", diffIn * 8 / 1_000_000.0)
            uploadSpeed = String.format("%.1f Mbps", diffOut * 8 / 1_000_000.0)
            totalSessionMb = ((inBytes + outBytes) / (1024f * 1024f)).coerceAtLeast(0f)
        }
    }

    private fun startTelemetry() {
        telemetryJob?.cancel()
        // addByteCountListener replays the last counters immediately on registration.
        VpnStatus.addByteCountListener(byteCountListener)
    }

    private fun stopTelemetry() {
        VpnStatus.removeByteCountListener(byteCountListener)
        telemetryJob?.cancel()
        uploadSpeed = "— Mbps"
        downloadSpeed = "— Mbps"
    }

    companion object {
        private const val RETRIES_PER_SERVER = 2
        private const val RETRY_BACKOFF_BASE_MS = 1_500L
        private const val MAX_RETRY_BACKOFF_MS = 8_000L
        private const val TUNNEL_PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"
        private const val TUNNEL_PROBE_TIMEOUT_MS = 5_000
        private const val TUNNEL_PROBE_INITIAL_DELAY_MS = 12_000L
        private const val TUNNEL_PROBE_INTERVAL_MS = 18_000L

        /**
         * After disconnecting one gateway, wait this long before launching the next so
         * the old daemon's exit events land while SWITCH intent still owns them and can
         * never be misread as the new server failing.
         */
        private const val SWITCH_SETTLE_MS = 2_000L
    }
}
