package com.localdoc.scanner.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object LocalLocation {
    suspend fun coordinates(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return withTimeoutOrNull(15000) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(java.lang.String.format(java.util.Locale.ROOT, "纬度 %.6f · 经度 %.6f", location.latitude, location.longitude))
                    }
                    override fun onProviderDisabled(provider: String) { manager.removeUpdates(this); if (continuation.isActive) continuation.resume(null) }
                    override fun onProviderEnabled(provider: String) = Unit
                    @Deprecated("Legacy Android callback")
                    override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle?) = Unit
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                try {
                    if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) continuation.resume(null)
                    else manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, listener, android.os.Looper.getMainLooper())
                } catch (_: Exception) { manager.removeUpdates(listener); if (continuation.isActive) continuation.resume(null) }
            }
        }
    }
}
