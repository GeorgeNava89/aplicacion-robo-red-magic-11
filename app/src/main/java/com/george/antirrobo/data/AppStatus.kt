package com.george.antirrobo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Estado observable en proceso para la pantalla de ajustes.
 * Los servicios lo actualizan; la UI lo observa.
 */
data class StatusSnapshot(
    val armed: Boolean = false,
    val fakeOffActive: Boolean = false,
    val fgsRunning: Boolean = false,
    val accessibilityRunning: Boolean = false,
    val lastLocationText: String = "—",
    val lastLocationTimeMs: Long = 0L,
    val lastEvent: String = "—",
    val deviceOwner: Boolean = false,
    val letPowerDialogThrough: Boolean = false,
)

object AppStatus {
    private val _state = MutableStateFlow(StatusSnapshot())
    val state: StateFlow<StatusSnapshot> = _state.asStateFlow()

    fun refreshFromPrefs(prefs: AppPreferences) {
        _state.update {
            it.copy(
                armed = prefs.armed,
                lastLocationText = prefs.lastLocationText,
                lastLocationTimeMs = prefs.lastLocationTimeMs,
            )
        }
    }

    fun setArmed(value: Boolean) = _state.update { it.copy(armed = value) }
    fun setFakeOff(value: Boolean) = _state.update { it.copy(fakeOffActive = value) }
    fun setFgsRunning(value: Boolean) = _state.update { it.copy(fgsRunning = value) }
    fun setAccessibilityRunning(value: Boolean) = _state.update { it.copy(accessibilityRunning = value) }
    fun setLastLocation(text: String, timeMs: Long) =
        _state.update { it.copy(lastLocationText = text, lastLocationTimeMs = timeMs) }
    fun setLastEvent(text: String) = _state.update { it.copy(lastEvent = text) }
    fun setDeviceOwner(value: Boolean) = _state.update { it.copy(deviceOwner = value) }
    fun setLetPowerDialogThrough(value: Boolean) =
        _state.update { it.copy(letPowerDialogThrough = value) }
}
