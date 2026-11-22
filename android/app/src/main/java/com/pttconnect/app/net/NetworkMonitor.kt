package com.pttconnect.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class NetType(val label: String) { WIFI("Wi-Fi"), CELLULAR("Mobile data"), OTHER("Online"), NONE("Offline") }

/**
 * Watches the system default network. LiveKit itself migrates the audio session
 * between Wi-Fi and mobile data (ICE restart); this is used for the UI indicator
 * and to trigger a full reconnect when the network comes back after being offline.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _type = MutableStateFlow(current())
    val type: StateFlow<NetType> = _type

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _type.value = classify(caps) }
            override fun onLost(network: Network) { _type.value = current() }
            override fun onAvailable(network: Network) { _type.value = current() }
        })
    }

    private fun current(): NetType = cm.getNetworkCapabilities(cm.activeNetwork)?.let { classify(it) } ?: NetType.NONE

    private fun classify(c: NetworkCapabilities): NetType = when {
        !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetType.NONE
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetType.WIFI
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.CELLULAR
        else -> NetType.OTHER
    }
}
