package com.george.antirrobo.unlock

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.george.antirrobo.util.EventLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Red de seguridad: si el gesto de desbloqueo falla o cualquier cosa sale mal,
 * se sale del apagado falso al cabo de [timeoutMs] (default 10 s).
 *
 * Dos temporizadores independientes:
 *  1) Handler del hilo principal.
 *  2) HandlerThread de respaldo que dispara un poco después, por si el
 *     looper principal está bloqueado.
 *
 * El servicio en primer plano también consulta [deadlineElapsedRealtime]
 * cada pocos segundos (triple red de seguridad).
 *
 * [fire] es idempotente: solo el primer disparo cuenta.
 */
class AutoUnlockWatchdog(
    private val onTimeout: () -> Unit,
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
        running.set(true)
        val ms = timeoutMs.coerceAtLeast(1_000L)
        val due = SystemClock.elapsedRealtime() + ms
        deadline.set(due)
        EventLog.i(TAG, "Watchdog iniciado: ${ms}ms (red de seguridad)")

        mainHandler.postDelayed(mainRunnable, ms)
        // El respaldo dispara 1.5 s después, solo si el principal no lo hizo.
        backupHandler.postDelayed(backupRunnable, ms + BACKUP_SLACK_MS)
    }

    fun cancel() {
        running.set(false)
        deadline.set(0L)
        mainHandler.removeCallbacksAndMessages(null)
        backupHandler.removeCallbacksAndMessages(null)
    }

    /** Lo llama el FGS periódicamente. */
    fun checkFromService() {
        if (!running.get() || fired.get()) return
        val due = deadline.get()
        if (due > 0L && SystemClock.elapsedRealtime() >= due) {
            EventLog.w(TAG, "Watchdog disparado desde el servicio en primer plano")
            fire("fgs")
        }
    }

    fun shutdown() {
        cancel()
        thread.quitSafely()
    }

    private val mainRunnable = Runnable {
        EventLog.i(TAG, "Watchdog disparado desde el hilo principal")
        fire("main")
    }

    private val backupRunnable = Runnable {
        if (fired.get()) return@Runnable
        EventLog.w(TAG, "Watchdog de respaldo disparado (el principal no respondió)")
        mainHandler.post { fire("backup") }
    }

    private fun fire(source: String) {
        if (!fired.compareAndSet(false, true)) return
        running.set(false)
        EventLog.i(TAG, "Auto-desbloqueo ejecutado (fuente=$source)")
        try {
            onTimeout()
        } catch (t: Throwable) {
            EventLog.e(TAG, "Error en onTimeout", t)
        }
    }

    companion object {
        private const val TAG = "AutoUnlock"
        private const val BACKUP_SLACK_MS = 1_500L
    }
}
