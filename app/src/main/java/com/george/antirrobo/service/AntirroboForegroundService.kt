package com.george.antirrobo.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.george.antirrobo.MainActivity
import com.george.antirrobo.R
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.data.AppStatus
import com.george.antirrobo.fakeoff.AudioAndLightsSilencer
import com.george.antirrobo.fakeoff.FakeOffController
import com.george.antirrobo.location.LocationLogger
import com.george.antirrobo.nubia.LockLightsController
import com.george.antirrobo.receiver.ScreenStateReceiver
import com.george.antirrobo.util.PermissionChecker
import com.george.antirrobo.util.EventLog

/**
 * Servicio en primer plano (tipos location + specialUse) que mantiene el
 * proceso vivo, registra ubicación y actúa como tercera red de seguridad
 * del auto-desbloqueo.
 *
 * En Android 14/15 hay que declarar el tipo en el manifiesto, pedir el
 * permiso correspondiente y pasar el tipo a startForeground().
 */
class AntirroboForegroundService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var locationLogger: LocationLogger? = null
    private var lockLights: LockLightsController? = null
    private var screenReceiver: ScreenStateReceiver? = null
    private val watchdogTick = object : Runnable {
        override fun run() {
            FakeOffController.checkWatchdogFromService()
            main.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        EventLog.i(TAG, "FGS onCreate")
        val prefs = AppPreferences.get(this)
        if (prefs.needsRestore && !FakeOffController.isActive) {
            AudioAndLightsSilencer(this).restoreIfNeeded()
        }
        startInForeground()
        AppStatus.setFgsRunning(true)
        syncLocation()
        val lights = LockLightsController(this)
        lockLights = lights
        val recv = ScreenStateReceiver { action ->
            // v0.1.2: luces RGB al bloquear / desbloquear (armado o no).
            lights.onBroadcast(action)
            // v0.1.1: solo si la opción (apagada por defecto) está activa.
            if (action == Intent.ACTION_SCREEN_OFF && prefs.armed && prefs.screenOffTrigger &&
                !FakeOffController.letPowerDialogThrough
            ) {
                // El AccessibilityService también lo escucha y es quien puede
                // mostrar el overlay. Aquí solo registramos y, si el a11y está
                // vivo, el controlador ya está bindeado.
                FakeOffController.enter("FGS recibió SCREEN_OFF")
            }
        }
        screenReceiver = recv
        recv.register(this)
        main.post(watchdogTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        EventLog.i(TAG, "onStartCommand action=$action")
        when (action) {
            ACTION_STOP -> {
                stopInternal()
                return START_NOT_STICKY
            }
            ACTION_RESTART_LOCATION -> {
                locationLogger?.stop()
                locationLogger = null
                syncLocation()
            }
            else -> syncLocation()
        }
        return START_STICKY
    }

    /**
     * Mismo comportamiento de ubicación que v0.1.1 (registro con el modo armado).
     * Si el servicio corre solo por las luces RGB, no se pide ubicación.
     */
    private fun syncLocation() {
        val armed = AppPreferences.get(this).armed
        if (armed && locationLogger == null) {
            locationLogger = LocationLogger(this).also { it.start() }
        } else if (!armed && locationLogger != null) {
            locationLogger?.stop()
            locationLogger = null
        }
    }

    override fun onDestroy() {
        EventLog.i(TAG, "FGS onDestroy")
        lockLights?.release()
        lockLights = null
        main.removeCallbacks(watchdogTick)
        try {
            screenReceiver?.unregister(this)
        } catch (_: Throwable) {
        }
        screenReceiver = null
        locationLogger?.stop()
        locationLogger = null
        AppStatus.setFgsRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopInternal() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fgs_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.fgs_channel_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm?.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // v0.1.5: acción "Apagar luces" (solo apaga si están encendidas).
        val stripOff = PendingIntent.getBroadcast(
            this,
            1,
            Intent(com.george.antirrobo.lightstrip.LightStripActionReceiver.ACTION)
                .setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_text))
            .setContentIntent(open)
            .apply { if (com.george.antirrobo.FeatureFlags.LIGHT_STRIP) addAction(0, "Apagar luces", stripOff) }
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFFE10600.toInt())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        // v0.1.2: tipo location solo si hay permiso (si no, Android 14 lanza
        // SecurityException en el modo "solo luces").
        val hasLoc = PermissionChecker.granted(this, Manifest.permission.ACCESS_FINE_LOCATION) ||
            PermissionChecker.granted(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val types = if (Build.VERSION.SDK_INT >= 34) {
            (if (hasLoc) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notification, types)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            EventLog.e(TAG, "startForeground falló; reintento con un solo tipo", t)
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(
                        NOTIF_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                    )
                } else {
                    startForeground(NOTIF_ID, notification)
                }
            } catch (t2: Throwable) {
                EventLog.e(TAG, "startForeground también falló en el reintento", t2)
            }
        }
    }

    companion object {
        private const val TAG = "AntirroboFGS"
        private const val CHANNEL_ID = "antirrobo_fgs"
        private const val NOTIF_ID = 41
        private const val WATCHDOG_TICK_MS = 1_000L
        const val ACTION_STOP = "com.george.antirrobo.action.STOP_FGS"
        const val ACTION_RESTART_LOCATION = "com.george.antirrobo.action.RESTART_LOCATION"

        fun start(context: Context) {
            val i = Intent(context, AntirroboForegroundService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        /** Corre si hace falta (armado o luces al bloquear); si no, se detiene. */
        fun sync(context: Context) {
            val prefs = AppPreferences.get(context)
            if (prefs.armed || prefs.lightsFeatureActive) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AntirroboForegroundService::class.java),
                )
            } else {
                stop(context)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, AntirroboForegroundService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(i)
            } catch (t: Throwable) {
                EventLog.w(TAG, "stop vía intent falló, se intenta stopService", t)
                context.stopService(Intent(context, AntirroboForegroundService::class.java))
            }
        }

        fun restartLocation(context: Context) {
            val i = Intent(context, AntirroboForegroundService::class.java)
                .setAction(ACTION_RESTART_LOCATION)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
