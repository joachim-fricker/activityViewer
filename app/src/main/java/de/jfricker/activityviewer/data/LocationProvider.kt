package de.jfricker.activityviewer.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import de.jfricker.activityviewer.domain.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Aktueller Standort ueber den System-LocationManager (keine Google-Play-Abhaengigkeit).
 * Wird fuer die Track-Karte und spaeter fuer die Umkreissuche verwendet.
 */
class LocationProvider(private val context: Context) {

    private val lm = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun bestProvider(): String? = listOf(
        LocationManager.FUSED_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    ).firstOrNull { lm.hasProvider(it) && lm.isProviderEnabled(it) }

    /** Letzter bekannter Standort aller Provider (sofort verfuegbar, evtl. etwas alt). */
    @SuppressLint("MissingPermission")
    fun lastKnown(): GeoPoint? {
        if (!hasPermission()) return null
        return lm.allProviders
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }

    /**
     * Laufende Standort-Updates (alle 5 s bzw. 10 m). Erster Wert ist der letzte bekannte Standort.
     * Ohne Berechtigung oder bei ausgeschalteter Ortung endet der Flow ohne Wert.
     */
    @SuppressLint("MissingPermission")
    fun updates(): Flow<GeoPoint> = callbackFlow {
        val provider = bestProvider()
        if (!hasPermission() || provider == null) {
            close()
            return@callbackFlow
        }
        lastKnown()?.let { trySend(it) }
        val listener = LocationListener { loc -> trySend(GeoPoint(loc.latitude, loc.longitude)) }
        lm.requestLocationUpdates(provider, 5_000L, 10f, listener, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(listener) }
    }
}
