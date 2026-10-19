package de.jfricker.activityviewer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class NetworkInfo(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** true im WLAN (bzw. allgemein in einem nicht volumenbegrenzten Netz). */
    fun isUnmetered(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
