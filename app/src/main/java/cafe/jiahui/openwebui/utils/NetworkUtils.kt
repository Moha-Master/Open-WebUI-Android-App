package cafe.jiahui.openwebui.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

object NetworkUtils {
    enum class NetworkType {
        WIFI,
        MOBILE,
        OTHER,
        OFFLINE
    }

    data class NetworkState(
        val type: NetworkType,
        val wifiSsid: String? = null
    )

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getNetworkState(context: Context): NetworkState {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return NetworkState(NetworkType.OFFLINE)
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkState(NetworkType.OFFLINE)

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val ssid = getCurrentWifiSsid(context, capabilities)
            return NetworkState(NetworkType.WIFI, ssid)
        }

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return NetworkState(NetworkType.MOBILE)
        }

        return NetworkState(NetworkType.OTHER)
    }

    private fun getCurrentWifiSsid(context: Context, capabilities: NetworkCapabilities): String? {
        val transportInfo = capabilities.transportInfo
        if (transportInfo is WifiInfo) {
            return sanitizeSsid(transportInfo.ssid)
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return sanitizeSsid(wifiManager?.connectionInfo?.ssid)
    }

    private fun sanitizeSsid(rawSsid: String?): String? {
        if (rawSsid.isNullOrBlank()) {
            return null
        }
        val normalized = rawSsid.removePrefix("\"").removeSuffix("\"")
        if (normalized.equals("<unknown ssid>", ignoreCase = true)) {
            return null
        }
        return normalized
    }
}
