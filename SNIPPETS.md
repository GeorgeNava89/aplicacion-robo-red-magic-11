# Fragmentos clave

Los archivos completos están en `app/src/main/java/com/george/antirrobo/`.

| Pieza | Archivo |
|---|---|
| Detección del menú de apagado y teclas de volumen | `service/AntirroboAccessibilityService.kt` |
| Apagado falso (overlay, silencio, luces/ventilador) | `fakeoff/FakeOffController.kt`, `fakeoff/FakeOffOverlay.kt`, `fakeoff/AudioAndLightsSilencer.kt`, `nubia/NubiaHardwareController.kt` |
| Combinación de desbloqueo (volumen + gesto) | `unlock/UnlockComboDetector.kt` |
| Auto-desbloqueo (red de seguridad, 10 s por defecto) | `unlock/AutoUnlockWatchdog.kt` |
| Registro de ubicación | `location/LocationLogger.kt` |

## Detección del menú de apagado

Sin root, `onKeyEvent` no recibe `KEYCODE_POWER`; se detecta el diálogo de apagado y se cierra al instante.

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    val prefs = AppPreferences.get(this)
    if (!prefs.armed) return
    if (FakeOffController.letPowerDialogThrough) return
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    ) {
        if (looksLikePowerMenu(event) || anyWindowLooksLikePowerMenu()) {
            dismissPowerMenuThenFakeOff("menú de apagado detectado")
        }
    }
}

override fun onKeyEvent(event: KeyEvent): Boolean {
    when (event.keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
            UnlockComboDetector.onVolumeKey(event)
            return FakeOffController.isActive
        }
    }
    return false
}
```

## Apagado falso

```kotlin
fun enter(reason: String) {
    if (!prefs.armed || letPowerDialogThrough) return
    silencer.silence()           // DND + volumen + vibración + brillo
    nubia.tryTurnOffRgbAndFan()  // NO VERIFICADO
    overlay.show()               // TYPE_ACCESSIBILITY_OVERLAY negro
    watchdog.start(prefs.autoUnlockSeconds * 1000L) // 10 s por defecto
}
```

## Combinación de desbloqueo

```kotlin
if (swipeStartedInUnlockZone && longEnough && mostlyVertical) {
    if (UnlockComboDetector.isVolumeHeld()) onUnlockGesture()
    else onFailedUnlockAttempt() // el temporizador sigue corriendo
}
```

## Auto-desbloqueo

```kotlin
fun start(timeoutMs: Long) {
    fired.set(false)
    mainHandler.postDelayed({ fire("main") }, timeoutMs)
    backupHandler.postDelayed({ mainHandler.post { fire("backup") } }, timeoutMs + 1_500L)
}
```
