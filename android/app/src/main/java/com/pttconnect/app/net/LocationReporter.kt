package com.pttconnect.app.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.pttconnect.app.data.Backend
import com.pttconnect.app.data.Settings
import com.pttconnect.app.ptt.ConnState
import com.pttconnect.app.ptt.PttEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Shares this phone's GPS position with the dispatcher map while the user is connected to a channel
 * and "Share my location" is on. Uses Android's built-in LocationManager (no Google Play Services needed).
 * Only the user themselves and admins can see positions (enforced by the database).
 */
class LocationReporter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val backend: Backend,
    private val settings: Settings,
    engine: PttEngine,
) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private var running = false
    private var lastSent = 0L
    @Volatile var lastFix: Location? = null
        private set

    private val listener = LocationListener { loc -> onFix(loc) }

    init {
        scope.launch {
            combine(engine.state, settings.prefs) { s, p -> (s.channel != null && s.conn == ConnState.CONNECTED) to p.shareLocation }
                .distinctUntilChanged()
                .collect { (connected, share) ->
                    when {
                        connected && share -> start()
                        !share && running -> { stop(); runCatching { backend.stopLocationSharing() } }
                        !connected -> stop()
                    }
                }
        }
    }

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Best current position for an emergency: latest fix, else the system's last known one. */
    @SuppressLint("MissingPermission")
    fun bestLocation(): Location? {
        lastFix?.let { if (System.currentTimeMillis() - it.time < 5 * 60_000) return it }
        if (!hasPermission()) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (running || !hasPermission()) return
        running = true
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) {
                runCatching { lm.requestLocationUpdates(p, INTERVAL_MS, 10f, listener, Looper.getMainLooper()) }
                    .onFailure { Log.w(TAG, "provider $p", it) }
            }
        }
        bestLocation()?.let { onFix(it) }
    }

    private fun stop() {
        if (!running) return
        running = false
        runCatching { lm.removeUpdates(listener) }
    }

    private fun onFix(loc: Location) {
        val prev = lastFix
        if (prev == null || loc.time >= prev.time) lastFix = loc
        val now = System.currentTimeMillis()
        if (now - lastSent < INTERVAL_MS - 1_000) return
        lastSent = now
        val battery = runCatching {
            context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()?.takeIf { it in 0..100 }
        scope.launch {
            runCatching {
                backend.reportLocation(loc.latitude, loc.longitude,
                    if (loc.hasAccuracy()) loc.accuracy else null, if (loc.hasSpeed()) loc.speed else null,
                    if (loc.hasBearing()) loc.bearing else null, battery)
            }.onFailure { Log.w(TAG, "report failed", it) }
        }
    }

    companion object {
        private const val TAG = "Location"
        const val INTERVAL_MS = 30_000L
    }
}
