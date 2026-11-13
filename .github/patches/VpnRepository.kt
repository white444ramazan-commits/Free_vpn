package com.freevpn.client.data.repository

import android.content.Context
import com.freevpn.client.data.model.VpnServer
import com.freevpn.client.data.remote.VpnGateApi
import com.freevpn.client.data.remote.VpnGateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VpnRepository(context: Context) {
    private val api = VpnGateApi(context.applicationContext)

    suspend fun getCachedServers(): VpnGateResult = api.loadCachedServers()

    /** Fetch the official VPN Gate CSV on an IO dispatcher. */
    suspend fun getServers(): VpnGateResult = withContext(Dispatchers.IO) {
        api.parseVpnGate()
    }

    /** Add deduplicated public sources and atomically persist the merged directory. */
    suspend fun mergeSupplementalServers(base: List<VpnServer>): VpnGateResult =
        api.mergeSupplementalServers(base)

    suspend fun saveCachedServers(servers: List<VpnServer>) {
        api.saveCachedServers(servers)
    }
}
