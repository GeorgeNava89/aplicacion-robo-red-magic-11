package com.george.antirrobo.lightstrip

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.data.AppStatus
import com.george.antirrobo.nubia.NubiaHardwareController
import com.george.antirrobo.util.EventLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v0.1.5 — Apaga la "Franja de luz" SOLO si está encendida.
 *
 * 1) Clave directa (si el Diagnóstico de luces guardó una): se lee; solo si
 *    vale el valor "encendido" se escribe el "apagado" y se vuelve a leer.
 * 2) Si no hay clave (o no se puede escribir): accesibilidad. Se abre Ajustes
 *    rápidos, se busca el mosaico por su etiqueta, se LEE su estado y solo si
 *    está ENCENDIDO se toca UNA vez; se vuelve a leer y se cierra el panel.
 *
 * Nunca se enciende nada. Estado desconocido = no se toca. Sin reintentos.
 * No corre con la pantalla apagada ni durante una llamada.
 */
object LightStripToggler {

    private const val TAG = "LightStrip"
    private const val OPEN_WAIT_MS = 700L
    private const val VERIFY_WAIT_MS = 800L
    private const val HARD_TIMEOUT_MS = 5_000L

    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var serviceRef: WeakReference<AccessibilityService>? = null
    private var shadeOpened = false

    /** Mientras corre, el detector del menú de apagado ignora el panel. */
    val isBusy: Boolean get() = running.get()

    fun bind(service: AccessibilityService) {
        serviceRef = WeakReference(service)
    }

    fun turnOffIfOn(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { turnOffIfOn(reason) }
            return
        }
        val service = serviceRef?.get()
        if (service == null) {
            report("sin servicio de accesibilidad ($reason)")
            return
        }
        if (!running.compareAndSet(false, true)) {
            EventLog.i(TAG, "Ya hay un intento en curso; se ignora ($reason)")
            return
        }
        main.postDelayed(hardTimeout, HARD_TIMEOUT_MS)
        try {
            val pm = service.getSystemService(PowerManager::class.java)
            if (pm?.isInteractive == false) return finish("pantalla apagada: no se toca nada ($reason)")
            val am = service.getSystemService(AudioManager::class.java)
            if (am?.mode == AudioManager.MODE_IN_CALL || am?.mode == AudioManager.MODE_IN_COMMUNICATION) {
                return finish("llamada en curso: no se abre el panel ($reason)")
            }
            if (tryDirectKey(service, reason)) return
            viaQuickSettings(service, reason)
        } catch (t: Throwable) {
            EventLog.e(TAG, "Error", t)
            finish("error: ${t.javaClass.simpleName}")
        }
    }

    /** true si la clave resolvió el caso (apagada, ya estaba apagada o sin tocar por seguridad). */
    private fun tryDirectKey(service: AccessibilityService, reason: String): Boolean {
        val prefs = AppPreferences.get(service)
        if (prefs.rgbKeyTable.isBlank() || prefs.rgbKeyName.isBlank()) return false
        val table = prefs.rgbKeyTable
        val key = prefs.rgbKeyName
        val before = LightStripLogic.parseKeyState(
            NubiaHardwareController.readSetting(service, table, key), prefs.rgbOnValue, prefs.rgbOffValue,
        )
        if (LightStripLogic.decide(before) == LightStripLogic.Action.NOTHING) {
            if (before == LightStripLogic.State.OFF) {
                finish("clave $table/$key: ya apagada, nada que hacer ($reason)")
                return true
            }
            EventLog.i(TAG, "Clave $table/$key: estado desconocido; se prueba con el mosaico")
            return false
        }
        val ok = try {
            NubiaHardwareController.writeSetting(service, table, key, prefs.rgbOffValue)
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se pudo escribir $table/$key (¿falta WRITE_SECURE_SETTINGS?)", t)
            false
        }
        if (!ok) return false
        val after = LightStripLogic.parseKeyState(
            NubiaHardwareController.readSetting(service, table, key), prefs.rgbOnValue, prefs.rgbOffValue,
        )
        finish("clave $table/$key: ${LightStripLogic.verify(after)} ($reason)")
        return true
    }

    private fun viaQuickSettings(service: AccessibilityService, reason: String) {
        val labels = LightStripLogic.labels(AppPreferences.get(service).stripLabels)
        EventLog.i(TAG, "Abriendo Ajustes rápidos para buscar ${labels.joinToString("/")} ($reason)")
        shadeOpened = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        main.postDelayed({
            if (!running.get()) return@postDelayed
            val found = findTile(service, labels)
            if (found == null) {
                finish("mosaico no encontrado (¿otra página u otra etiqueta?): no se toca nada")
                return@postDelayed
            }
            val (target, before) = found
            EventLog.i(TAG, "Mosaico '${found.first.text ?: found.first.contentDescription}': estado=$before")
            if (LightStripLogic.decide(before) == LightStripLogic.Action.NOTHING) {
                finish("estado $before → ${LightStripLogic.skipped(before)}: no se toca")
                return@postDelayed
            }
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            EventLog.i(TAG, "Toque único al mosaico: $clicked")
            main.postDelayed({
                if (!running.get()) return@postDelayed
                val after = findTile(service, labels)?.second ?: LightStripLogic.State.UNKNOWN
                val outcome = LightStripLogic.verify(after)
                if (outcome == LightStripLogic.Outcome.STILL_ON_NO_RETRY) {
                    EventLog.w(TAG, "Tras el toque sigue ENCENDIDA; NO se vuelve a tocar")
                }
                finish("después del toque: $after → $outcome")
            }, VERIFY_WAIT_MS)
        }, OPEN_WAIT_MS)
    }

    /** Busca el mosaico y devuelve el nodo a tocar (clicable) y su estado. */
    private fun findTile(
        service: AccessibilityService,
        labels: List<String>,
    ): Pair<AccessibilityNodeInfo, LightStripLogic.State>? {
        val roots = buildList {
            try { service.windows?.forEach { w -> w.root?.let { add(it) } } } catch (_: Throwable) {}
            service.rootInActiveWindow?.let { add(it) }
        }
        for (root in roots) {
            for (label in labels) {
                val hits = try { root.findAccessibilityNodeInfosByText(label) } catch (_: Throwable) { null }
                for (hit in hits.orEmpty()) {
                    if (hit.packageName?.toString() == service.packageName) continue // nunca nuestras ventanas
                    // Sube hasta el nodo clicable (el mosaico) y lee el estado ahí o en sus hijos.
                    var node: AccessibilityNodeInfo? = hit
                    var depth = 0
                    while (node != null && !node.isClickable && depth < 4) {
                        node = node.parent; depth++
                    }
                    val tile = node ?: continue
                    if (!tile.isClickable) continue
                    var state = readState(tile, label)
                    if (state == LightStripLogic.State.UNKNOWN) state = readState(hit, label)
                    return tile to state
                }
            }
        }
        return null
    }

    private fun readState(n: AccessibilityNodeInfo, label: String): LightStripLogic.State {
        val stateDesc = if (Build.VERSION.SDK_INT >= 30) n.stateDescription else null
        var s = LightStripLogic.parseTileState(n.isCheckable, n.isChecked, stateDesc, n.contentDescription, n.text, label)
        if (s != LightStripLogic.State.UNKNOWN) return s
        // Algunos mosaicos ponen el estado en un hijo (Switch o texto "Activado").
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val cd = if (Build.VERSION.SDK_INT >= 30) c.stateDescription else null
            s = LightStripLogic.parseTileState(c.isCheckable, c.isChecked, cd, c.contentDescription, c.text, label)
            if (s != LightStripLogic.State.UNKNOWN) return s
        }
        return LightStripLogic.State.UNKNOWN
    }

    private val hardTimeout = Runnable {
        if (running.get()) finish("tiempo agotado (5 s): no se toca nada más")
    }

    private fun finish(msg: String) {
        main.removeCallbacks(hardTimeout)
        val service = serviceRef?.get()
        if (shadeOpened && service != null) {
            if (Build.VERSION.SDK_INT >= 31) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
        shadeOpened = false
        report(msg)
        // Pequeño margen para que el panel termine de cerrarse antes de volver a vigilar.
        main.postDelayed({ running.set(false) }, 400L)
    }

    private fun report(msg: String) {
        EventLog.i(TAG, msg)
        AppStatus.setLastEvent("Franja de luz: $msg")
    }
}
