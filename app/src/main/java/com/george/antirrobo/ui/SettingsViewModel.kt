package com.george.antirrobo.ui

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.data.AppStatus
import com.george.antirrobo.data.StatusSnapshot
import com.george.antirrobo.fakeoff.FakeOffController
import com.george.antirrobo.location.LocationLogger
import com.george.antirrobo.policy.DeviceOwnerHelper
import com.george.antirrobo.service.AntirroboForegroundService
import com.george.antirrobo.util.EventLog
import com.george.antirrobo.util.PermissionChecker
import com.george.antirrobo.util.PermissionReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.george.antirrobo.nubia.LightsDiagnostics
import com.george.antirrobo.nubia.NubiaHardwareController

data class SettingsUiState(
    val status: StatusSnapshot = StatusSnapshot(),
    val permissions: PermissionReport? = null,
    val timeoutSeconds: Int = AppPreferences.DEFAULT_TIMEOUT_SECONDS,
    val intervalSeconds: Int = AppPreferences.DEFAULT_LOC_INTERVAL,
    val webhookDraft: String = "",
    val diagnostic: Boolean = false,
    val screenOffTrigger: Boolean = false,
    val fingerprintSilent: Boolean = false,
    val decoyMenu: Boolean = true,
    val ghostGuides: Boolean = false,
    val decoyStyle: String = "system",
    val lockPin: Boolean = true,
    val lockActive: Boolean = false,
    val stripOnFakeOff: Boolean = false,
    val stripOnLock: Boolean = false,
    val stripLabels: String = "",
    val ghostGuidesReal: Boolean = false,
    val lightsOnLock: Boolean = true,
    val rgbKeyLabel: String = "",
    val scanASummary: String = "",
    val scanBSummary: String = "",
    val lightDiffs: List<LightsDiagnostics.Diff> = emptyList(),
    val lightsMessage: String = "",
    val deviceOwnerText: String = "—",
    val eventLogPath: String = "",
    val locationLogPath: String = "",
    val policyMessage: String = "",
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPreferences.get(app)
    private val policy = DeviceOwnerHelper(app)

    private val _ui = MutableStateFlow(
        SettingsUiState(
            timeoutSeconds = prefs.autoUnlockSeconds,
            intervalSeconds = prefs.locationIntervalSeconds,
            webhookDraft = prefs.webhookUrl,
            diagnostic = prefs.diagnosticWindows,
            screenOffTrigger = prefs.screenOffTrigger,
            fingerprintSilent = prefs.fingerprintSilentAuth,
            eventLogPath = EventLog.filePath(),
            locationLogPath = java.io.File(app.filesDir, LocationLogger.LOG_FILE).absolutePath,
        ),
    )
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            AppStatus.state.collect { snap ->
                _ui.update { it.copy(status = snap) }
            }
        }
    }

    fun refresh(context: Context) {
        AppStatus.refreshFromPrefs(prefs)
        AppStatus.setDeviceOwner(policy.isDeviceOwner())
        _ui.update {
            it.copy(
                permissions = PermissionChecker.report(context),
                timeoutSeconds = prefs.autoUnlockSeconds,
                intervalSeconds = prefs.locationIntervalSeconds,
                webhookDraft = if (it.webhookDraft.isEmpty()) prefs.webhookUrl else it.webhookDraft,
                diagnostic = prefs.diagnosticWindows,
                screenOffTrigger = prefs.screenOffTrigger,
                fingerprintSilent = prefs.fingerprintSilentAuth,
                decoyMenu = prefs.decoyPowerMenu,
                ghostGuides = prefs.ghostGuides,
                decoyStyle = prefs.decoyStyle,
                lockPin = prefs.lockModeAllowPin,
                lockActive = prefs.lockModeActive,
                stripOnFakeOff = prefs.stripOffOnFakeOff,
                stripOnLock = prefs.stripOffOnLockMode,
                stripLabels = prefs.stripLabels,
                ghostGuidesReal = prefs.ghostGuidesReal,
                lightsOnLock = prefs.rgbOffOnLock,
                rgbKeyLabel = rgbLabel(),
                deviceOwnerText = policy.statusText(),
                eventLogPath = EventLog.filePath(),
            )
        }
    }

    fun setArmed(context: Context, armed: Boolean) {
        val report = PermissionChecker.report(context)
        if (armed && !report.accessibility) {
            toast("Primero activa el servicio de accesibilidad en Ajustes.")
            return
        }
        if (armed && !report.fineLocation) {
            toast("Concede el permiso de ubicación antes de armar.")
            return
        }
        prefs.armed = armed
        AppStatus.setArmed(armed)
        if (armed) {
            AntirroboForegroundService.start(context)
            EventLog.i(TAG, "Modo ARMADO")
            toast("Modo armado. Solo el menú de apagado (pulsación larga) entra a apagado falso.")
        } else {
            FakeOffController.exit("desarmado desde ajustes")
            // Sigue corriendo si "Apagar luces RGB al bloquear" lo necesita.
            AntirroboForegroundService.sync(context)
            EventLog.i(TAG, "Modo DESARMADO")
            toast("Modo desarmado.")
        }
        refresh(context)
    }

    fun setTimeout(value: Int) {
        _ui.update { it.copy(timeoutSeconds = value) }
    }

    fun commitTimeout(value: Int) {
        prefs.autoUnlockSeconds = value
        _ui.update { it.copy(timeoutSeconds = prefs.autoUnlockSeconds) }
        EventLog.i(TAG, "Timeout de auto-desbloqueo = ${prefs.autoUnlockSeconds}s")
    }

    fun setInterval(value: Int) {
        _ui.update { it.copy(intervalSeconds = value) }
    }

    fun commitInterval(value: Int) {
        prefs.locationIntervalSeconds = value
        _ui.update { it.copy(intervalSeconds = prefs.locationIntervalSeconds) }
        if (prefs.armed) {
            AntirroboForegroundService.restartLocation(getApplication())
        }
        EventLog.i(TAG, "Intervalo de ubicación = ${prefs.locationIntervalSeconds}s")
    }

    fun setWebhookDraft(value: String) {
        _ui.update { it.copy(webhookDraft = value) }
    }

    fun commitWebhook(context: Context) {
        prefs.webhookUrl = _ui.value.webhookDraft
        toast("Webhook guardado.")
        EventLog.i(TAG, "Webhook actualizado (len=${prefs.webhookUrl.length})")
        if (prefs.armed) AntirroboForegroundService.restartLocation(context)
    }

    fun testFakeOff(context: Context) {
        if (!prefs.armed) {
            toast("Arma el modo primero.")
            return
        }
        if (!PermissionChecker.isAccessibilityEnabled(context)) {
            toast("El servicio de accesibilidad no está activo.")
            return
        }
        if (FakeOffController.boundService() == null) {
            toast("El servicio de accesibilidad aún no está enlazado. Ábrelo en Ajustes y vuelve.")
            return
        }
        toast("Entrando a apagado falso de prueba. Auto-desbloqueo en ${prefs.autoUnlockSeconds}s.")
        // v0.1.3: en la prueba se dibujan tenuemente las zonas de los gestos.
        FakeOffController.enter("prueba desde ajustes", demo = true)
    }

    fun toggleDiagnostic() {
        val next = !prefs.diagnosticWindows
        prefs.diagnosticWindows = next
        _ui.update { it.copy(diagnostic = next) }
        toast(
            if (next) "Diagnóstico ON: se registran las ventanas en ${EventLog.filePath()}"
            else "Diagnóstico OFF",
        )
    }

    fun testPowerMenu(context: Context) {
        if (!prefs.armed) {
            toast("Arma el modo primero.")
            return
        }
        if (FakeOffController.boundService() == null) {
            toast("El servicio de accesibilidad aún no está enlazado.")
            return
        }
        if (prefs.decoyPowerMenu) {
            FakeOffController.showDecoy("prueba desde ajustes", demo = true)
        } else {
            FakeOffController.enter("prueba de menú (directo a negro)", demo = true)
        }
    }

    /** v0.1.4: estilo del menú falso y "Bloqueo con luces". */
    fun onAction(context: Context, action: String) {
        when (action) {
            "style_system" -> prefs.decoyStyle = "system"
            "style_classic" -> prefs.decoyStyle = "classic"
            "lock_pin" -> prefs.lockModeAllowPin = !prefs.lockModeAllowPin
            "lock_now" -> com.george.antirrobo.lockmode.LockModeController.activate(context, demo = false)
            "lock_test" -> com.george.antirrobo.lockmode.LockModeController.activate(context, demo = true)
            "strip_fakeoff" -> prefs.stripOffOnFakeOff = !prefs.stripOffOnFakeOff
            "strip_lock" -> prefs.stripOffOnLockMode = !prefs.stripOffOnLockMode
            "strip_now" -> com.george.antirrobo.lightstrip.LightStripToggler.turnOffIfOn("botón en ajustes")
            else -> if (action.startsWith("strip_labels:")) {
                prefs.stripLabels = action.removePrefix("strip_labels:").ifBlank {
                    com.george.antirrobo.lightstrip.LightStripLogic.DEFAULT_LABELS
                }
            }
        }
        _ui.update {
            it.copy(
                decoyStyle = prefs.decoyStyle,
                lockPin = prefs.lockModeAllowPin,
                lockActive = prefs.lockModeActive,
                stripOnFakeOff = prefs.stripOffOnFakeOff,
                stripOnLock = prefs.stripOffOnLockMode,
                stripLabels = prefs.stripLabels,
            )
        }
    }

    fun toggleGhost() {
        prefs.ghostGuides = !prefs.ghostGuides
        _ui.update { it.copy(ghostGuides = prefs.ghostGuides) }
    }

    fun toggleGhostReal() {
        prefs.ghostGuidesReal = !prefs.ghostGuidesReal
        _ui.update { it.copy(ghostGuidesReal = prefs.ghostGuidesReal) }
    }

    fun toggleDecoy() {
        prefs.decoyPowerMenu = !prefs.decoyPowerMenu
        _ui.update { it.copy(decoyMenu = prefs.decoyPowerMenu) }
    }

    fun toggleScreenOffTrigger() {
        prefs.screenOffTrigger = !prefs.screenOffTrigger
        _ui.update { it.copy(screenOffTrigger = prefs.screenOffTrigger) }
    }

    fun toggleFingerprintSilent() {
        prefs.fingerprintSilentAuth = !prefs.fingerprintSilentAuth
        _ui.update { it.copy(fingerprintSilent = prefs.fingerprintSilentAuth) }
    }

    // ---------- v0.1.2: luces RGB ----------

    private fun rgbLabel(): String =
        if (prefs.rgbKeyName.isBlank()) "" else
            "${prefs.rgbKeyTable}/${prefs.rgbKeyName} (encendidas=${prefs.rgbOnValue}, apagadas=${prefs.rgbOffValue})"

    fun toggleLightsOnLock(context: Context) {
        prefs.rgbOffOnLock = !prefs.rgbOffOnLock
        _ui.update { it.copy(lightsOnLock = prefs.rgbOffOnLock) }
        AntirroboForegroundService.sync(context)
    }

    fun scanLights(label: String) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { LightsDiagnostics.scan(getApplication(), label) }
            val summary = "${r.entries.size} claves" + if (r.errors.isNotEmpty()) " · ${r.errors.size} errores" else ""
            val diffs = LightsDiagnostics.diff()
            _ui.update {
                if (label == "A") it.copy(scanASummary = summary, lightDiffs = diffs, lightsMessage = "Guardado: ${r.file}")
                else it.copy(scanBSummary = summary, lightDiffs = diffs, lightsMessage = "Guardado: ${r.file}")
            }
            if (label == "B" && diffs.isEmpty()) {
                toast("Ninguna clave cambió. Puede que Game Space no use Settings (ver Ayuda).")
            }
        }
    }

    fun useLightKey(context: Context, d: LightsDiagnostics.Diff) {
        if (!com.george.antirrobo.FeatureFlags.FAN_CONTROL &&
            com.george.antirrobo.nubia.NubiaHardwareController.isFanKey(d.key)
        ) {
            _ui.update { it.copy(lightsMessage = "Esa clave es del ventilador; el ventilador está desactivado") }
            return
        }
        prefs.rgbKeyTable = d.table
        prefs.rgbKeyName = d.key
        prefs.rgbOnValue = d.before ?: "1"
        prefs.rgbOffValue = d.after ?: "0"
        prefs.rgbSavedValue = ""
        prefs.rgbOffByLock = false
        _ui.update { it.copy(rgbKeyLabel = rgbLabel(), lightsMessage = "Clave guardada: ${d.table}/${d.key}") }
        AntirroboForegroundService.sync(context)
    }

    fun clearLightKey(context: Context) {
        prefs.rgbKeyTable = ""
        prefs.rgbKeyName = ""
        prefs.rgbSavedValue = ""
        prefs.rgbOffByLock = false
        _ui.update { it.copy(rgbKeyLabel = "", lightsMessage = "Clave borrada") }
        AntirroboForegroundService.sync(context)
    }

    fun testLights(off: Boolean) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                NubiaHardwareController(getApplication()).setRgbConfigured(off)
            }
            _ui.update { it.copy(lightsMessage = r) }
        }
    }

    fun applyDeviceOwner() {
        val msg = policy.applyArmedRestrictions()
        _ui.update { it.copy(policyMessage = msg, deviceOwnerText = policy.statusText()) }
        toast(msg.take(80))
    }

    fun clearDeviceOwner() {
        val msg = policy.clearRestrictions()
        _ui.update { it.copy(policyMessage = msg, deviceOwnerText = policy.statusText()) }
        toast(msg.take(80))
    }

    private fun toast(msg: String) {
        Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "SettingsVM"
    }
}

fun formatTimeMexico(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("es", "MX"))
    fmt.timeZone = TimeZone.getTimeZone("America/Mexico_City")
    return fmt.format(Date(epochMs)) + " (hora de México)"
}
