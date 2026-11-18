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

## Auto-desbloqueo (v0.1.1)

```kotlin
fun start(timeoutMs: Long) {
    val due = SystemClock.elapsedRealtime() + timeoutMs   // avanza aunque la CPU duerma
    deadline.set(due); running.set(true)
    mainHandler.post(mainTick)                             // revisa cada 500 ms
    backupHandler.postDelayed(backupTick, 1_000L)          // hilo de respaldo
    am.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, due, alarmPi) // despierta la CPU
}
// FakeOffController.enter(): WakeLock parcial + watchdog.start() ANTES del overlay;
// el éxito del overlay lo decide addView(), no isAttachedToWindow.
```

## Teclas de volumen en apagado falso (sin vibración)

```kotlin
KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
    UnlockComboDetector.onVolumeKey(event)   // primero la combinación
    return FakeOffController.isActive        // consume DOWN, repeticiones y UP
}
// overlay: isHapticFeedbackEnabled = false; isSoundEffectsEnabled = false
// silencer: HAPTIC_FEEDBACK_ENABLED=0, SOUND_EFFECTS_ENABLED=0, DND NONE, ringer SILENT
```

## Disparadores y huella

```kotlin
Intent.ACTION_SCREEN_OFF -> if (prefs.screenOffTrigger) FakeOffController.enter(...) // apagado por defecto
Intent.ACTION_USER_PRESENT -> if (FakeOffController.isActive) FakeOffController.exit("huella/PIN del sistema")
```

## Luces RGB al bloquear (v0.1.2)

```kotlin
Intent.ACTION_SCREEN_OFF -> for (d in longArrayOf(500, 3_000, 10_000, 30_000))
    main.postDelayed({ if (km.isKeyguardLocked || km.isDeviceLocked) nubia.setRgbConfigured(off = true) }, d)
Intent.ACTION_USER_PRESENT -> nubia.setRgbConfigured(off = false) // devuelve el valor guardado
// La clave sale del escaneo A/B (LightsDiagnostics.diff()); sin clave no se escribe nada.
```

## Gestos v0.1.3 (lógica pura + overlay a pantalla completa real)

```kotlin
// Overlay: cubrir barras de estado/navegación y recorte (antes no las cubría).
if (Build.VERSION.SDK_INT >= 30) { fitInsetsTypes = 0; fitInsetsSides = 0
    layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }

// Toques con coordenadas absolutas contra el tamaño real de la pantalla.
val (w, h) = screenSize()   // currentWindowMetrics.bounds
when (taps.onTap(event.getRawX(i), event.getRawY(i), w, h, event.eventTime)) {
    TapSequenceDetector.Result.Completed -> onShutdownGesture()   // 25 % por esquina, 3 s entre toques
    else -> Unit
}
// Deslizamiento evaluado también en ACTION_CANCEL; volumen "activo durante" el gesto.
if (swipe.onEnd(x, y, h, now) == SWIPE_OK &&
    UnlockComboDetector.volumeActiveDuring(swipe.startTime, now)) onUnlockGesture()
```

## Menú de apagado falso (v0.1.3)

```kotlin
fun onPowerMenuDetected(reason: String) {           // tras cerrar el menú real con BACK
    if (prefs.decoyPowerMenu) showDecoy(reason) else enter(reason)
}
// Deslizar → ShutdownAnimation(SHUTDOWN).play(2_200) { enter("menú falso: apagar") }
// Emergencia → exit(); startActivity(Intent("com.android.phone.EmergencyDialer.DIAL"))
```
