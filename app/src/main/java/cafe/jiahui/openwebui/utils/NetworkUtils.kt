package cafe.jiahui.openwebui.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log

object NetworkUtils {
    private const val DTAG = "OWUIDBG"

    enum class NetworkType {
        WIFI,
        MOBILE,
        OTHER,
        OFFLINE
    }

    data class NetworkState(
        val type: NetworkType,
        val wifiSsid: String? = null,
        val isVpn: Boolean = false
    )

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isVpnActive(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        return false
    }

    fun getNetworkState(context: Context): NetworkState {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: run {
            return NetworkState(NetworkType.OFFLINE)
        }
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: run {
            return NetworkState(NetworkType.OFFLINE)
        }
        val isVpn = isVpnActive(context)

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val ssid = getCurrentWifiSsid(context, capabilities)
            return NetworkState(NetworkType.WIFI, ssid, isVpn)
        }

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return NetworkState(NetworkType.MOBILE, isVpn = isVpn)
        }

        return NetworkState(NetworkType.OTHER, isVpn = isVpn)
    }

    private fun getCurrentWifiSsid(context: Context, capabilities: NetworkCapabilities): String? {
        try {
            val transportInfo = capabilities.transportInfo
            if (transportInfo is WifiInfo) {
                val result = sanitizeSsid(transportInfo.ssid)
                if (result != null) return result
            }
        } catch (_: Exception) {}

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val connInfo = wifiManager?.connectionInfo
            if (connInfo != null) {
                val result = sanitizeSsid(connInfo.ssid)
                if (result != null) return result
            }
        } catch (_: Exception) {}

        return null
    }

    private fun sanitizeSsid(rawSsid: String?): String? {
        if (rawSsid.isNullOrBlank()) return null
        val normalized = rawSsid.removePrefix("\"").removeSuffix("\"")
        if (normalized.equals("<unknown ssid>", ignoreCase = true)) return null
        return normalized
    }
}
