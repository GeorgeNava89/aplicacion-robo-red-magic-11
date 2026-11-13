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

data class SettingsUiState(
    val status: StatusSnapshot = StatusSnapshot(),
    val permissions: PermissionReport? = null,
    val timeoutSeconds: Int = AppPreferences.DEFAULT_TIMEOUT_SECONDS,
    val intervalSeconds: Int = AppPreferences.DEFAULT_LOC_INTERVAL,
    val webhookDraft: String = "",
    val diagnostic: Boolean = false,
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
            toast("Modo armado. El menú de apagado y el SCREEN_OFF entrarán a apagado falso.")
        } else {
            FakeOffController.exit("desarmado desde ajustes")
            AntirroboForegroundService.stop(context)
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
        FakeOffController.enter("prueba desde ajustes")
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
