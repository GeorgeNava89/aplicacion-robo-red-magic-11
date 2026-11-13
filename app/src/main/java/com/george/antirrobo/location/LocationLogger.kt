package com.george.antirrobo.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.data.AppStatus
import com.george.antirrobo.util.EventLog
import com.george.antirrobo.util.PermissionChecker
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Registra ubicación periódica en un archivo local y, si hay URL, hace POST
 * al webhook. Usa Fused Location si Play Services está disponible; si no,
 * LocationManager (más portable en ROMs sin GMS; Nubia a veces las trae).
 */
class LocationLogger(private val context: Context) {

    private val prefs = AppPreferences.get(context)
    private val io = Executors.newSingleThreadExecutor()
    private val logFile = File(context.applicationContext.filesDir, LOG_FILE)
    private var fusedCallback: LocationCallback? = null
    private var managerListener: LocationListener? = null
    private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!PermissionChecker.granted(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !PermissionChecker.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            EventLog.w(TAG, "Sin permiso de ubicación; no se inicia el logger")
            return
        }
        running = true
        val intervalMs = prefs.locationIntervalSeconds * 1_000L
        val usedFused = tryStartFused(intervalMs)
        if (!usedFused) {
            startLocationManager(intervalMs)
        }
        EventLog.i(TAG, "LocationLogger iniciado (intervalo=${prefs.locationIntervalSeconds}s, fused=$usedFused)")
    }

    fun stop() {
        running = false
        try {
            fusedCallback?.let {
                LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(it)
            }
        } catch (t: Throwable) {
            EventLog.w(TAG, "Error al detener Fused Location", t)
        }
        fusedCallback = null
        try {
            val lm = context.getSystemService(LocationManager::class.java)
            managerListener?.let { lm?.removeUpdates(it) }
        } catch (t: Throwable) {
            EventLog.w(TAG, "Error al detener LocationManager", t)
        }
        managerListener = null
    }

    fun logFilePath(): String = logFile.absolutePath

    @SuppressLint("MissingPermission")
    private fun tryStartFused(intervalMs: Long): Boolean {
        return try {
            val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            if (gms != ConnectionResult.SUCCESS) {
                EventLog.i(TAG, "Play Services no disponible (code=$gms); se usa LocationManager")
                return false
            }
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis((intervalMs / 2).coerceAtLeast(5_000L))
                .setWaitForAccurateLocation(false)
                .build()
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { onLocation(it, "fused") }
                }
            }
            fusedCallback = cb
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) onLocation(loc, "fused-last")
            }
            true
        } catch (t: Throwable) {
            EventLog.w(TAG, "Fused Location no se pudo iniciar", t)
            fusedCallback = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationManager(intervalMs: Long) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(location, location.provider ?: "lm")
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        managerListener = listener
        val providers = mutableListOf<String>()
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers += LocationManager.GPS_PROVIDER
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers += LocationManager.NETWORK_PROVIDER
        if (providers.isEmpty()) {
            EventLog.w(TAG, "Ningún proveedor de ubicación está activo")
        }
        for (p in providers) {
            try {
                lm.requestLocationUpdates(p, intervalMs, 5f, listener, Looper.getMainLooper())
                lm.getLastKnownLocation(p)?.let { onLocation(it, "$p-last") }
            } catch (t: Throwable) {
                EventLog.w(TAG, "No se pudo suscribir a $p", t)
            }
        }
    }

    private fun onLocation(location: Location, provider: String) {
        val iso = isoFmt.format(Date(location.time.let { if (it > 0) it else System.currentTimeMillis() }))
        val text = String.format(
            Locale.US,
            "%s  lat=%.6f lon=%.6f acc=%.1fm provider=%s",
            iso,
            location.latitude,
            location.longitude,
            location.accuracy,
            provider,
        )
        prefs.lastLocationText = text
        prefs.lastLocationTimeMs = System.currentTimeMillis()
        AppStatus.setLastLocation(text, prefs.lastLocationTimeMs)
        EventLog.i(TAG, "Ubicación: $text")
        io.execute {
            try {
                logFile.appendText(text + "\n", Charsets.UTF_8)
                if (logFile.length() > MAX_LOG_BYTES) {
                    rotate()
                }
            } catch (t: Throwable) {
                EventLog.w(TAG, "No se escribió el archivo de ubicación", t)
            }
            val url = prefs.webhookUrl
            if (url.isNotBlank()) {
                postWebhook(url, location, provider, iso)
            }
        }
    }

    private fun rotate() {
        val bak = File(logFile.parentFile, "$LOG_FILE.1")
        if (bak.exists()) bak.delete()
        logFile.renameTo(bak)
    }

    private fun postWebhook(url: String, location: Location, provider: String, iso: String) {
        var conn: HttpURLConnection? = null
        try {
            val body = JSONObject()
                .put("timestamp", iso)
                .put("lat", location.latitude)
                .put("lon", location.longitude)
                .put("accuracy", location.accuracy.toDouble())
                .put("altitude", location.altitude)
                .put("provider", provider)
                .put("speed", location.speed.toDouble())
                .put("app", "com.george.antirrobo")
                .toString()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "AntirroboRedMagic/1.0")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            EventLog.i(TAG, "Webhook POST $url → HTTP $code")
        } catch (t: Throwable) {
            EventLog.w(TAG, "Fallo el POST al webhook", t)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "LocationLog"
        const val LOG_FILE = "location_log.txt"
        private const val MAX_LOG_BYTES = 1024 * 1024
        private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
