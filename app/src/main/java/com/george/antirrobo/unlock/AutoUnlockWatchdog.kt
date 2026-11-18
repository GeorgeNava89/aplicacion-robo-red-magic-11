package com.george.antirrobo.unlock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.george.antirrobo.receiver.AutoUnlockAlarmReceiver
import com.george.antirrobo.util.EventLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Red de seguridad v0.1.1: sale del apagado falso al cumplirse el timeout
 * (default 10 s), pase lo que pase.
 *
 * Causa raíz del bug de v0.1.0: Handler.postDelayed() mide con
 * SystemClock.uptimeMillis(), que SE DETIENE cuando la CPU entra en
 * suspensión (pantalla apagada). Además el watchdog ni siquiera se
 * arrancaba (ver FakeOffController). Ahora hay cuatro capas:
 *
 *  1. Fecha límite absoluta en elapsedRealtime() (sí avanza dormida).
 *  2. Tick cada 500 ms en el hilo principal que compara contra esa fecha.
 *  3. Tick en un HandlerThread de respaldo (por si el principal se bloquea).
 *  4. AlarmManager ELAPSED_REALTIME_WAKEUP exacta (setExactAndAllowWhileIdle)
 *     que despierta la CPU aunque esté en Doze y dispara
 *     AutoUnlockAlarmReceiver. Si no se permiten alarmas exactas, se usa
 *     setAndAllowWhileIdle (inexacta) como último recurso.
 *
 * Mientras el apagado falso está activo, FakeOffController mantiene un
 * WakeLock parcial, así que las capas 2 y 3 corren aunque la pantalla esté apagada.
 * [fire] es idempotente.
 */
class AutoUnlockWatchdog(
    private val context: Context,
    private val onTimeout: (source: String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("antirrobo-watchdog").apply { start() }
    private val backupHandler = Handler(thread.looper)

    private val fired = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val deadline = AtomicLong(0L)

    val deadlineElapsedRealtime: Long
        get() = deadline.get()

    val isRunning: Boolean
        get() = running.get() && !fired.get()

    fun start(timeoutMs: Long) {
        cancel()
        fired.set(false)
        val ms = timeoutMs.coerceAtLeast(1_000L)
        val due = SystemClock.elapsedRealtime() + ms
        deadline.set(due)
        running.set(true)
        EventLog.i(TAG, "Watchdog iniciado: ${ms}ms (deadline elapsedRealtime=$due)")
        mainHandler.post(mainTick)
        backupHandler.postDelayed(backupTick, BACKUP_TICK_MS)
        scheduleAlarm(due)
    }

    fun cancel() {
        running.set(false)
        deadline.set(0L)
        mainHandler.removeCallbacks(mainTick)
        backupHandler.removeCallbacks(backupTick)
        cancelAlarm()
    }

    /** Lo llaman el FGS y el receptor de la alarma. */
    fun checkNow(source: String) {
        if (!running.get() || fired.get()) return
        val due = deadline.get()
        if (due > 0L && SystemClock.elapsedRealtime() >= due) {
            fire(source)
        }
    }

    fun shutdown() {
        cancel()
        thread.quitSafely()
    }

    private val mainTick = object : Runnable {
        override fun run() {
            if (!running.get() || fired.get()) return
            if (SystemClock.elapsedRealtime() >= deadline.get()) {
                fire("tick-principal")
            } else {
                mainHandler.postDelayed(this, TICK_MS)
            }
        }
    }

    private val backupTick = object : Runnable {
        override fun run() {
            if (!running.get() || fired.get()) return
            // Margen de 1.5 s para dejar actuar primero al hilo principal.
            if (SystemClock.elapsedRealtime() >= deadline.get() + BACKUP_SLACK_MS) {
                EventLog.w(TAG, "Tick de respaldo: el hilo principal no disparó a tiempo")
                fire("tick-respaldo")
            } else {
                backupHandler.postDelayed(this, BACKUP_TICK_MS)
            }
        }
    }

    private fun fire(source: String) {
        if (!fired.compareAndSet(false, true)) return
        running.set(false)
        mainHandler.removeCallbacks(mainTick)
        backupHandler.removeCallbacks(backupTick)
        cancelAlarm()
        EventLog.i(TAG, "Auto-desbloqueo ejecutado (fuente=$source)")
        // onTimeout puede ejecutarse desde cualquier hilo; FakeOffController
        // reenvía al hilo principal y además tiene una ruta de emergencia.
        try {
            onTimeout(source)
        } catch (t: Throwable) {
            EventLog.e(TAG, "Error en onTimeout", t)
        }
    }

    private fun alarmIntent(): PendingIntent {
        val i = Intent(context, AutoUnlockAlarmReceiver::class.java)
            .setAction(AutoUnlockAlarmReceiver.ACTION_AUTO_UNLOCK)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleAlarm(dueElapsed: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent()
        try {
            val exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, dueElapsed, pi)
                EventLog.i(TAG, "Alarma exacta programada")
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, dueElapsed, pi)
                EventLog.w(TAG, "Sin permiso de alarma exacta: alarma inexacta (los ticks + WakeLock siguen activos)")
            }
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se pudo programar la alarma", t)
        }
    }

    private fun cancelAlarm() {
        try {
            context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent())
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "AutoUnlock"
        private const val TICK_MS = 500L
        private const val BACKUP_TICK_MS = 1_000L
        private const val BACKUP_SLACK_MS = 1_500L
        private const val ALARM_REQUEST_CODE = 7101
    }
}
