# Antirrobo Red Magic 11 Pro

Prototipo en Kotlin para **tu** Red Magic 11 Pro (Nubia), Android 14/15. El teléfono **parece apagado** pero sigue encendido para que puedas rastrearlo.

Paquete: `com.george.antirrobo`  
`minSdk 29` · `targetSdk 35` · un solo módulo · Gradle Kotlin DSL · UI en Jetpack Compose.

> **Esto es un prototipo del dueño del aparato, no un candado absoluto.** Sin root, Android no deja que una app atrape el botón de encendido *antes* que el sistema. El menú de apagado se detecta y se cierra lo más rápido posible, pero puede verse un instante, y alguien podría alcanzar a tocar “Apagar”. El reinicio forzado de hardware (encendido + volumen alrededor de 10 segundos) **no lo bloquea nadie**.

> **Versión 0.1.6.** Ver *Cambios en v0.1.6*, *Cambios en v0.1.5*, *Cambios en v0.1.4*, *Cambios en v0.1.3*, *Cambios en v0.1.2*, *Cambios en v0.1.1* y *Huella digital en apagado falso* más abajo.

Hay una **red de seguridad**: si el gesto de desbloqueo falla o algo sale mal, el teléfono se desbloquea solo a los **10 segundos** (configurable). El overlay se quita también si el servicio de accesibilidad se interrumpe o se destruye. El teléfono **nunca** queda bloqueado de forma permanente.

---

## 1. Qué hace

- Servicio en primer plano (`location` + `specialUse`) para que Android no mate el proceso, más un `AccessibilityService`.
- Con el modo **armado**:
  - Si aparece el menú de apagado (systemui / clases tipo `GlobalActionsDialog`; desde v0.1.1 el bloqueo normal de pantalla / `SCREEN_OFF` **ya no** lo dispara, salvo que actives la opción), se cierra con `GLOBAL_ACTION_BACK` y `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE`, y entra el **apagado falso**.
  - Overlay negro a pantalla completa (`TYPE_ACCESSIBILITY_OVERLAY`, sin root), brillo al mínimo, audio en silencio (mejor si concediste No Molestar), vibración cancelada.
  - Intento **no verificado** de apagar las luces RGB laterales y el ventilador trasero de Nubia (no hay API pública; ver más abajo).
  - Se registra la ubicación de forma periódica en un archivo local y, si configuraste una URL, se hace POST al webhook.
- **Apagado real** solo con un gesto oculto en el overlay, que abre el diálogo verdadero del sistema (`GLOBAL_ACTION_POWER_DIALOG`) y lo deja pasar. Los botones físicos no apagan el teléfono mientras está armado, *en la medida de lo posible*.
- **Desbloqueo**: volumen mantenido (el servicio de accesibilidad **sí** recibe las teclas de volumen) **más** un deslizamiento en la pantalla, al mismo tiempo.
- **Auto-desbloqueo** con `Handler` en el hilo principal, un `HandlerThread` de respaldo y una revisión periódica del servicio en primer plano.
- Pantalla de ajustes para armar/desarmar, el timeout, el webhook, el intervalo de ubicación y el estado de permisos, servicios y última ubicación.

---

## 2. Gestos (apréndetelos *antes* de armar)

### Gestos (desde v0.1.6)

Con el modo **armado**:

| Dónde | Gesto | Qué hace |
|---|---|---|
| Teléfono normal | Mantener el botón de encendido | Se cierra el menú real y sale el **menú falso** |
| Menú falso | Tocar **Apagar** / **Reiniciar** | Animación → **apagado falso** (no se apaga de verdad) |
| Menú falso | Mantener **Volumen + o −** y deslizar hacia arriba desde la mitad inferior | Se abre el **menú real** del sistema |
| Menú falso | Tocar fuera / Atrás | Se cierra el menú falso (sigue armado) |
| Apagado falso | **5 toques**: sup-izq → sup-der → inf-der → inf-izq → centro (hasta 3 s entre toques, esquinas de 25 %) | **Desbloquear** (salir del apagado falso) |
| Apagado falso | Volumen + deslizar hacia arriba (mitad inferior izquierda) | Se abre el **menú real** del sistema |
| Apagado falso | Nada / falla | Se quita solo en el tiempo configurado (10 s por defecto) |

Empieza el deslizamiento un poco **arriba del borde inferior**, porque el borde lo usa la navegación por gestos. “Mostrar gestos fantasma” dibuja las zonas exactas durante las pruebas.

### Cómo probarlo sin miedo

En la app: arma el modo y pulsa **“Probar apagado falso”**. Espera el auto-desbloqueo o usa el gesto. Empieza con 10 segundos.

---

## 3. Instalación y permisos

1. Compila el APK (`./gradlew :app:assembleDebug`) o instala el que acompaña a este proyecto, si se generó.
2. Instálalo en el teléfono (`adb install -r app/build/outputs/apk/debug/app-debug.apk`). Es una app sideload.

### 3.1 Ajustes restringidos (Android 13, 14 y 15) — importante

En apps instaladas fuera de Play Store, Android esconde servicios sensibles (accesibilidad, entre otros) detrás de **ajustes restringidos**.

1. Ajustes → Apps → Antirrobo Red Magic.  
2. Menú de **tres puntos** (arriba a la derecha) → **Permitir ajustes restringidos**.  
3. Si no ves esa opción, abre la app una vez, intenta activar la accesibilidad, y vuelve a la info de la app.

Sin este paso, el interruptor de accesibilidad puede aparecer gris o rechazarse.

### 3.2 Permisos, en este orden

Desde la pantalla de la app (cada botón abre el ajuste correspondiente):

1. **Accesibilidad** — activa “Antirrobo Red Magic”. Debes hacerlo tú, a mano.  
2. **Notificaciones** (Android 13+).  
3. **Ubicación** (precisa) y después **ubicación en segundo plano** (Android 10+ abre una pantalla aparte: “Permitir todo el tiempo”).  
4. **No Molestar / acceso a la política de notificaciones**, para silenciar de verdad todos los streams.  
5. **Excluir de la optimización de batería**.  
6. **Modificar ajustes del sistema**, opcional, para bajar el brillo del sistema (el del overlay se baja igual sin este permiso).  
7. **Superposición sobre otras apps**, opcional: el overlay principal usa `TYPE_ACCESSIBILITY_OVERLAY` y no lo necesita.

### 3.3 Ajustes típicos de Nubia / Red Magic (imprescindibles en la práctica)

Las ROMs de Nubia, como casi todos los OEM chinos, son agresivas matando procesos en segundo plano. Los nombres exactos de los menús **varían con el firmware** y aquí no se inventan rutas como si estuvieran verificadas. Busca, en Ajustes y en las herramientas del sistema, cosas equivalentes a:

- **Inicio automático** / autostart para esta app (si no, el `BOOT_COMPLETED` no arranca el servicio).  
- **Consumo de batería en segundo plano** / “no restringir” / “permitir actividad en segundo plano”.  
- **Cerrar al quitar de recientes**: desactívalo, o **bloquea** la app en recientes (candado en la pantalla de apps recientes).  
- Gestión de arranque, “ahorro inteligente”, “congelar apps”, etc.: deja esta app fuera.  
- En **Game Space / Game Genie**, revisa si las luces RGB y el ventilador tienen interruptores propios. Esta app **no** tiene una API oficial para apagarlos.

Si encuentras el nombre exacto de un menú en tu firmware, anótalo; el modo diagnóstico de la app te ayuda con el menú de apagado.

---

## 4. Pasos de prueba

1. Concede los permisos de la sección 3 y arma el modo. Debes ver la notificación permanente del servicio (silenciosa).  
2. Pulsa **“Probar apagado falso”**. La pantalla se pone negra y casi sin brillo. A los N segundos (10 por defecto) debe volver sola. Comprueba que el sonido y la vibración regresan.  
3. Entra otra vez a la prueba. Usa el **gesto de desbloqueo** (volumen + swipe). Debe salir de inmediato y cancelar el timer.  
4. Entra otra vez y haz un swipe **sin** volumen: no debe desbloquear. Espera el auto-desbloqueo.  
5. Entra otra vez y completa el **gesto de cinco toques**. Debe aparecer el diálogo real de apagado. Cancélalo.  
6. Con el modo armado, una **pulsación corta** del botón de encendido o el tiempo de espera de pantalla deben **bloquear el teléfono normal**, SIN apagado falso. (Solo si activas “También al apagar la pantalla (SCREEN_OFF)” se comporta como en v0.1.0.)  
7. Con el modo armado, una **pulsación larga** debe cerrar el menú real y mostrar el **menú falso** (o ir directo a negro si apagaste “Menú falso + animación”). Desliza para apagar → animación → apagado falso. **A los 10 s (o tu valor) la pantalla debe volver sola**, con brillo, sonido y No Molestar restaurados. Si no se cierra el menú, activa **“Registrar ventanas”**, repite y revisa `files/antirrobo_events.log` para ver el paquete y la clase reales de Nubia.  
7b. Dentro del apagado falso, mantén volumen arriba/abajo: **no** debe cambiar el volumen, ni salir el panel, ni sonar, ni vibrar (ver límites de vibración abajo).  
7c. Huella: dentro del apagado falso pon el dedo en el sensor. Con la opción experimental apagada, lo esperado es que **no pase nada** (si el teléfono estaba desbloqueado, el sistema no está escuchando la huella). Si la pantalla de bloqueo del sistema está activa y desbloqueas con huella, el apagado falso se quita solo (`USER_PRESENT`).  
8. Si configuraste un webhook HTTPS, muévete un poco y confirma que llega un JSON con lat/lon, y que `files/location_log.txt` tiene líneas nuevas.  
9. (Opcional, consciente) Prueba el reinicio forzado encendido + volumen ~10 s: el teléfono **sí** debe reiniciarse. Es un límite de hardware, no un fallo de la app.

---

## 5. Qué funciona sin root, qué necesita Device Owner y qué necesita root

| Capacidad | Sin root (esta app) | Device Owner (adb, tu teléfono) | Root (Magisk / LSPosed) |
|---|---|---|---|
| Detectar el menú de apagado y cerrarlo | Sí, con un destello posible; no es 100 % | Igual. En *algunos* firmwares, lock-task o restricciones extra pueden limitar el menú; **hay que verificarlo**, no está garantizado | Un hook puede evitar que el menú se abra |
| Interceptar `KEYCODE_POWER` *antes* que el sistema | **No.** `onKeyEvent` no recibe esa tecla | **No.** No hay API pública fiable tipo “prohibir apagar” | **Sí, en principio**, enganchando `PhoneWindowManager.interceptKeyBeforeQueueing`. Hay un *boceto* en `docs/BOCETO_ROOT_LSPOSED.md`, no un módulo |
| Recibir teclas de **volumen** | Sí, con `flagRequestFilterKeyEvents` | Sí | Sí |
| Overlay negro a pantalla completa | Sí, `TYPE_ACCESSIBILITY_OVERLAY` | Sí | Sí |
| Brillo mínimo | Sí, en la ventana del overlay. El brillo del sistema pide `WRITE_SETTINGS` | Igual, o políticas extra | Igual, o sysfs |
| Silenciar audio y vibración | Sí, mejor con acceso a No Molestar | Sí | Sí |
| Luces RGB laterales y ventilador trasero Nubia | **Sin API pública.** Hay intentos no verificados (claves de `Settings` y rutas sysfs). Casi seguro no bastan | Probablemente igual de limitado | sysfs *podría* funcionar **si las rutas existen** en tu firmware; **no está verificado** |
| Ubicación periódica (archivo + webhook) | Sí, con permisos de ubicación (incluye segundo plano) | Sí | Sí |
| Apagado real solo con gesto oculto | Sí, vía `GLOBAL_ACTION_POWER_DIALOG` y una bandera para no cerrarlo | Sí | Sí, o apagado directo de sistema (fuera de alcance de este prototipo) |
| Impedir el reinicio forzado (encendido + volumen ~10 s) | **No** | **No** | **No** de forma fiable: es hardware / bootloader |
| Sobrevivir un reinicio y rearmar | Depende del **inicio automático** de Nubia | Algo mejor, no mágico | Mejor con un servicio de sistema, no incluido |
| Evitar que te desactiven la accesibilidad desde Ajustes | **No** (el dueño, o quien desbloquee el teléfono, puede apagarla) | Se pueden poner trabas, no es absoluto | Se pueden poner más trabas |

---

## 6. Restricciones de Android 14 y 15

- **Tipos de Foreground Service.** A partir de Android 14 hay que declarar el tipo en el manifiesto, pedir el permiso (`FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_SPECIAL_USE`) y pasarlo a `startForeground()`. El tipo `specialUse` lleva una propiedad `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justificando el uso (antirrobo en el dispositivo del dueño). En Play Store te pedirían una declaración extra; este prototipo está pensado para sideload.
- **Ubicación en segundo plano.** Primero se pide la ubicación “mientras se usa”, después “todo el tiempo”, en una pantalla del sistema. Sin eso, el registro se corta cuando la app no es visible (aunque el FGS ayuda).
- **Notificaciones.** Android 13+ exige `POST_NOTIFICATIONS`. Sin ella, el FGS puede fallar o no verse.
- **Accesibilidad en apps sideload.** Ver § 3.1, “ajustes restringidos”. No hay un intent oficial estable para abrir exactamente esa casilla.
- **`onKeyEvent` y el botón de encendido.** Sigue sin entregarse a las apps. Esto no es nuevo de Android 14, y en 15 sigue igual.
- **`GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE`** existe desde API 31; en este proyecto (min 29, objetivo 14/15) se usa con comprobación de versión. El `BACK` global existe desde antes.
- **Inicio al arrancar.** `RECEIVE_BOOT_COMPLETED` no basta en muchos OEM. Nubia suele exigir la lista de inicio automático.
- **Almacenamiento de copias.** `allowBackup` está en `false` y las reglas de extracción excluyen preferencias y archivos (hay registros de ubicación).

---

## 7. Particularidades Nubia / Red Magic 11 Pro

Lo que **sí** se puede decir con prudencia (comportamiento típico de Nubia/ZTE y de OEM similares), y lo que es conjetura:

- **Batería e inicio automático:** es un problema real y habitual. Si no dejas la app en esas listas, el prototipo “funciona” un rato y luego el sistema lo mata. Ver § 3.3. Los nombres exactos de los menús en Red Magic OS 9/10 **no se afirman** aquí.
- **Game Space / Game Genie** controla, en estos teléfonos, las **luces RGB laterales** y el **ventilador trasero**. No hay API pública documentada para apps de terceros. El código de `NubiaHardwareController` prueba claves de `Settings` y rutas `sysfs` **etiquetadas como no verificadas**. Si la clave no existe, **no se escribe a ciegas**. Las rutas sysfs casi siempre piden **root**. Los intents hacia Game Space **no se envían** con acciones inventadas, para no provocar efectos raros. **Debes verificarlo en tu aparato**, por ejemplo:
  ```
  adb shell settings list system
  adb shell settings list global
  adb shell ls -l /sys/class/leds/
  ```
- **Menú de apagado:** puede usar una clase o un paquete propio, no necesariamente `GlobalActionsDialog`. Por eso hay un **modo diagnóstico** que registra `TYPE_WINDOW_STATE_CHANGED` (paquete, clase, texto) en `antirrobo_events.log`. Con esa información se puede afinar `AntirroboAccessibilityService.matchesPowerMenu`. Las entradas “nubia/zte/redmagic” en el código son **hipótesis**, no hechos comprobados en un 11 Pro.
- **Brillo cero:** en algunos paneles OLED/AMOLED sigue habiendo un resplandor mínimo en la oscuridad total. Es límite del hardware, no de la app.
- **GMS:** si el firmware trae Play Services, se usa Fused Location; si no, `LocationManager`.

---

## 8. Ruta Device Owner (opcional, con adb)

Solo en **tu** teléfono. Convierte a esta app en “propietaria del dispositivo”. En la práctica, si el teléfono ya tiene una cuenta de Google u otro Device Owner, `dpm` lo rechaza y casi siempre hay que **restablecer de fábrica** antes. Es una decisión seria: algunas funciones de consumo (ciertas cuentas, algunos reseteos) quedan limitadas.

```bash
# 1. Instala la app
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Aprovisiona (el teléfono normalmente debe estar recién configurado, sin owner previo)
adb shell dpm set-device-owner com.george.antirrobo/.policy.AntirroboDeviceAdminReceiver
```

Para quitarlo (suele exigir fábrica, o `dpm remove-active-admin` si el firmware lo permite):

```bash
adb shell dpm remove-active-admin com.george.antirrobo/.policy.AntirroboDeviceAdminReceiver
```

La app tiene botones “Aplicar / Quitar restricciones” que, **solo si ya eres Device Owner**, intentan `DISALLOW_SAFE_BOOT`, `DISALLOW_FACTORY_RESET` y registrar el paquete para lock-task. **Esto no intercepta el botón de encendido.** En algunos firmwares el lock-task limita el menú de apagado; hay que probarlo. `setGlobalSetting` está restringido o deprecado para casi todas las claves de terceros.

---

## 9. Ruta root (solo boceto)

Ver [`docs/BOCETO_ROOT_LSPOSED.md`](docs/BOCETO_ROOT_LSPOSED.md). Es un apunte sobre enganchar `PhoneWindowManager.interceptKeyBeforeQueueing`. **No** hay un módulo Magisk/LSPosed listo, ni instrucciones para rootea r el teléfono.

---

## 10. Compilar

Requisitos: JDK 17, Android SDK con `platforms;android-35` y build-tools 35, Gradle 8.11.1 (el wrapper lo baja).

```bash
export JAVA_HOME=/ruta/a/jdk-17
export ANDROID_HOME=/ruta/a/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

Minify está desactivado en este prototipo.

---

## 11. Estructura

```
app/src/main/java/com/george/antirrobo/
  AntirroboApp.kt                         # Restaura audio/brillo si el proceso murió a medias
  MainActivity.kt
  data/AppPreferences.kt                  # Timeout default 10 s, webhook, etc.
  data/AppStatus.kt
  service/AntirroboAccessibilityService.kt
  service/AntirroboForegroundService.kt
  fakeoff/FakeOffController.kt
  fakeoff/FakeOffOverlay.kt               # Gestos de desbloqueo y apagado real + gestos fantasma
  fakeoff/DecoyPowerMenu.kt               # Menú de apagado falso
  fakeoff/ShutdownAnimation.kt            # Animaciones Apagando / Reiniciando / arranque
  unlock/GestureLogic.kt                  # Lógica pura de gestos (con pruebas unitarias)
  fakeoff/AudioAndLightsSilencer.kt
  unlock/UnlockComboDetector.kt           # Volumen + gesto
  unlock/AutoUnlockWatchdog.kt            # elapsedRealtime + 2 hilos + alarma exacta
  unlock/FingerprintUnlockActivity.kt     # Huella experimental sin BiometricPrompt
  receiver/AutoUnlockAlarmReceiver.kt     # Alarma que despierta la CPU
  location/LocationLogger.kt              # Fused Location o LocationManager + webhook
  nubia/NubiaHardwareController.kt        # Intentos NO VERIFICADOS de RGB/ventilador + clave elegida
  nubia/LightsDiagnostics.kt              # Escaneo A/B de claves de luces
  nubia/LockLightsController.kt           # Luces OFF al bloquear, restaurar al desbloquear
  policy/DeviceOwnerHelper.kt             # Ruta adb, documentada
  policy/AntirroboDeviceAdminReceiver.kt
  receiver/ScreenStateReceiver.kt
  receiver/BootReceiver.kt
  ui/SettingsScreen.kt
  util/EventLog.kt                        # Bitácora para el menú de apagado de Nubia
```

---

## Cambios en v0.1.6

- **Gestos reasignados** (ver la tabla de *Gestos*):
  - Los **5 toques** ahora **solo desbloquean** el apagado falso; ya no abren el menú real.
  - **Volumen + deslizar hacia arriba** ahora abre el **menú real** del sistema, tanto en el menú falso como en el apagado falso; ya no desbloquea.
  - Apagar y Reiniciar en el menú falso siguen yendo a la animación y al apagado falso.
  - Con el menú falso visible, las teclas de volumen ya no suben ni bajan el volumen.
- **Bloqueo con luces + llamadas:** al activarlo se pone No Molestar y el timbre en silencio; se restaura al salir. No toca el brillo ni las luces. No se contestan ni rechazan llamadas: solo No Molestar.
- **Ventilador desactivado:** la app ya no escribe claves ni rutas del ventilador (`FeatureFlags.FAN_CONTROL = false`), no lo busca en el Diagnóstico de luces y no deja guardar una clave del ventilador.
- **Franja de luz (v0.1.5) desactivada** (`FeatureFlags.LIGHT_STRIP = false`): sin opciones en Ajustes, sin acción en la notificación y sin ganchos en el apagado falso ni en el bloqueo. El código se queda, pero no corre.
- Lo demás sigue igual: auto-desbloqueo de 10 s, gestos fantasma, “Apagando…” de 5.6 s, menú estilo Red Magic con desenfoque fuerte, logo, bloqueo con luces con 5 toques + huella.

## Cambios en v0.1.5 — Franja de luz

### Qué hay detrás del mosaico “Franja de luz” (investigación)
- **No hay fuente pública** que diga qué clave o servicio usa ese mosaico en el Red Magic 11 Pro. Nubia no lo documenta y no encontré SystemUI descompilado para este modelo.
- **Red Magic 9 Pro** (repo GitHub DoganM95/Red-Magic): en la tabla **Global**, `switch_video_malp_enable` (0/1) enciende o apaga los LED en el modo “Media Atmosphere LED”, y `nubia_media_lamp_mode` elige el color. Escribir en Global exige `WRITE_SECURE_SETTINGS` concedido con adb. **No verificado en el 11 Pro**, y no está claro que sea el interruptor general de la franja.
- **Red Magic 9 Pro**, ventilador (Reddit): tabla **System**, `fan_colorful_light_preview`, `fan_colorful_light_color_select`, `fan_colorful_light_effect_select`. Son claves de OEM: con solo `WRITE_SETTINGS`, Android no deja escribir claves no públicas (targetSdk ≥ 23).
- **Red Magic 11 Pro** (guía de Reddit para ROMs personalizadas): los LED del logo, los hombros y el ventilador se controlan por sysfs (`/sys/class/leds/aw22xxx_led/effect` + `cfg`, más `/sys/kernel/fan/fan_enable`). **Requiere root**, así que aquí no se usa.
- **Conclusión:** la clave exacta del mosaico es **desconocida**. El Diagnóstico de luces (ahora también busca `lamp|malp|strip|atmosphere`) sirve para encontrarla con el método A/B: escanear, cambiar el mosaico, volver a escanear.

### Qué se implementó
Regla dura: **solo se apaga si está ENCENDIDA**. Siempre se lee el estado primero.
- Si está apagada → no se hace nada.
- Si el estado es **desconocido** → no se hace nada.
- **Nunca** se enciende. **No hay** “restaurar al desbloquear”.
- Tras el único toque se vuelve a leer. Si sigue encendida, **no** se toca otra vez; solo se registra en el log.

Dos caminos:
1. **Clave directa:** si el Diagnóstico de luces guardó una clave con sus valores de encendido y apagado, se lee la clave. Solo si vale “encendido” se escribe “apagado” y se vuelve a leer. Si vale otra cosa, se intenta el camino 2.
2. **Accesibilidad:**
   - Se abre Ajustes rápidos y se busca el mosaico por su nombre (configurable; por defecto “Franja de luz, Light strip, Lightstrip, Tira de luz, Tira LED”).
   - Se lee su estado: marcado/no marcado, descripción de estado (“Activado/Desactivado”, “On/Off”) o descripción.
   - Solo si está encendido se toca una vez, se verifica y se cierra el panel.
   - Límite total de 5 s; nunca hay más de un intento a la vez.
   - No corre con la pantalla apagada ni durante una llamada. Mientras corre, el panel no se confunde con el menú de apagado.

Dónde se usa (las dos opciones vienen apagadas):
- “Apagar al entrar en apagado falso”.
- “Apagar al activar Bloqueo con luces”.
- Botón **“Apagar franja de luz ahora”** en Ajustes.
- Acción **“Apagar luces”** en la notificación del servicio.

**Límites:**
- El panel puede verse un instante; en apagado falso o en el bloqueo queda debajo de nuestra ventana negra.
- El nombre del mosaico puede variar con el idioma o la versión.
- Si el mosaico está en otra página del panel, no se encuentra (se registra y no se toca).
- Necesita la pantalla encendida.
- Si Nubia no expone el estado del mosaico a la accesibilidad, el estado será “desconocido” y nunca se tocará.

## Cambios en v0.1.4

### Menú falso “Igual al sistema (Red Magic)” (nuevo estilo por defecto)
Copia el menú real del Red Magic 11 Pro, tomado de una captura del dueño:
- Fondo de la pantalla actual muy desenfocado.
- Dos botones circulares (~74 dp, blanco translúcido) a 33 % y 67 % del ancho y ~45 % del alto: **Apagar** (ícono de encendido) y **Reiniciar** (ícono de 8 rayitas).
- Abajo, en gris: “Mantenga presionado el botón de encendido por 10 segundos para reiniciar el teléfono.”
- Un toque en **Apagar** → animación → apagado falso. **Reiniciar** → “Reiniciando…”, negro, arranque falso → apagado falso. Tocar fuera o Atrás lo cierra.
- El estilo de v0.1.3 sigue disponible como **“Clásico (v0.1.3)”**.
- **Emergencia:** este estilo no tiene botón de emergencia (el real tampoco). Las llamadas de emergencia siguen siendo posibles: el apagado falso se quita solo en ≤10 s (auto-desbloqueo) y la pantalla de bloqueo del sistema tiene su botón de **Emergencia**. El estilo clásico conserva su botón.

### Desenfoque mucho más fuerte
- Android 12+ con desenfoque entre ventanas activo: `FLAG_BLUR_BEHIND` con radio de 250 px más un velo oscuro de ~60 %.
- Sin desenfoque (no compatible o apagado por ahorro de batería): velo de ~85 %. Si el sistema activa o desactiva el desenfoque, el velo se ajusta solo.
- `backgroundBlurRadius` solo existe para ventanas de actividad, no para overlays agregados con `WindowManager`, así que no aplica aquí.

### Animación de apagado
- **Logo real:** “Redmagic official logo.png” de Wikimedia Commons (CC BY-SA 4.0, autor Albert12mfjfr). Se le quitó el fondo y las letras se pusieron en blanco (`res/drawable-nodpi/redmagic_logo.png`), escalado al 60 % del ancho. Si no carga, se dibuja el texto “RED MAGIC” medido para caber.
- **Texto cortado (“R”, “Apagando…” cortado en la “g”):** antes eran TextViews en cursiva condensada con espaciado entre letras, dentro de una columna `WRAP_CONTENT`. No lo pude reproducir sin el teléfono. Se reescribió para dibujar todo en un Canvas: el texto se **mide** con `Paint.measureText` y se reduce si no cabe (`TextFit`, con prueba unitaria). En una simulación a 1216 × 2688 px, “Apagando…” ocupa ~340 px y “Reiniciando…” ~380 px, de 1094 disponibles.
- **Duración de “Apagando…” al doble:** antes 2.8 s (entrada 0.4 + visible 2.2 + salida 0.6). Ahora **5.6 s** (entrada 0.8, visible 4.4, salida 1.2). El auto-desbloqueo de 10 s empieza cuando inicia el negro.

### Bloqueo con luces (nuevo)
Ajustes → **“Bloquear ahora”** o **“Probar (30 s)”**.
- Una ventana de accesibilidad negra cubre toda la pantalla, que sigue **encendida** (se atenúa a los 15 s; un toque la vuelve a encender). Como el sistema **no** entra a su bloqueo, las luces RGB no se apagan por eso.
- **Única salida:** la secuencia de 5 toques (las mismas zonas que el apagado real) y después la **huella**, con un BiometricPrompt visible. Si “Permitir PIN como respaldo” está activado (por defecto), también sirve el PIN o patrón del teléfono. Si falla o se cancela, se vuelve a bloquear al instante.
- Si el teléfono no tiene huella ni PIN, el gesto solo desbloquea, para no dejarte afuera.
- Consume volumen y Atrás. Con el modo armado apagado, el menú de apagado real solo se cierra.
- Botón **Emergencia** abajo: abre el marcador de emergencia y vuelve a bloquear a los 90 s (espera si hay llamada en curso).
- Sobrevive reinicios: al reconectarse el servicio de accesibilidad, vuelve a bloquear.
- Funciona con “Mostrar gestos fantasma”.
- **Límites:**
  - No se puede reemplazar el bloqueo del sistema sin root.
  - Una pulsación corta del botón de encendido apaga la pantalla. Ahí entra el bloqueo del sistema y su manejo de luces; al encender, nuestra ventana sigue encima.
  - La navegación por gestos puede abrir Inicio debajo, pero nuestra ventana se queda encima.
  - Mientras aparece la huella, nuestra ventana se quita y queda una actividad negra.
  - El reinicio forzado (encendido + volumen ~10 s) no se puede bloquear.
  - La pantalla encendida gasta batería.
- **¿Quién apaga las luces al bloquear?** Nuestra app solo toca las luces si activaste “Apagar luces RGB al bloquear” **y** guardaste una clave con el Diagnóstico de luces. Sin clave, nunca escribe nada, así que el apagado en el bloqueo normal es del sistema Nubia (no verificado en el aparato). Con el bloqueo con luces activo, la app nunca apaga las luces, y si las había apagado, las regresa. Forzarlas encendidas durante el bloqueo del sistema con esa clave (con `WRITE_SECURE_SETTINGS`) no está verificado.

## Cambios en v0.1.3

### Gestos (arreglados; no verificado aún en el aparato)

- **Causa más probable:** desde Android 11 una ventana nueva trae `fitInsetsTypes` = barras del sistema. El overlay negro **no cubría la barra de estado ni la de navegación**: los toques de las esquinas superiores caían en la barra de estado (y abrían el panel), y el borde inferior lo usaba la navegación por gestos. Ahora el overlay usa `fitInsetsTypes = 0`, recorte de cámara `ALWAYS`, y las zonas se calculan con coordenadas absolutas (`rawX/rawY`) contra el tamaño real de la pantalla.
- **Deslizamiento perdido:** si la navegación por gestos “robaba” el toque llegaba `ACTION_CANCEL` y se descartaba. Ahora se evalúa con la última posición conocida.
- **Volumen demasiado estricto:** antes el volumen tenía que seguir presionado al levantar el dedo (margen de 120 ms). Ahora basta con que haya estado presionado durante el deslizamiento (margen de 800 ms), y tecla y toque usan el mismo reloj.
- **Apagado real:** el diálogo del sistema se pedía en el mismo instante en que se quitaba el overlay. Ahora espera 300 ms, reintenta si el sistema responde que no, y la ventana para dejarlo pasar dura 15 s.
- **Zonas más grandes:** esquinas de 25 % de ancho y alto, centro de 25 %, hasta 3 s entre toques. Un toque fuera de lugar ya no borra el avance. Deslizamiento desde la mitad inferior izquierda, 30 % de la altura, hasta 4 s.
- Cada toque se registra en `antirrobo_events.log` (coordenadas absolutas y locales, tamaño de vista y pantalla, estado del volumen).
- Pruebas unitarias en `app/src/test/.../GestureLogicTest.kt`.

### Gestos fantasma

Ajuste **“Mostrar gestos fantasma”** (apagado por defecto). En las pruebas (“Probar apagado falso” / “Probar menú de apagado”) dibuja sobre el negro las zonas **exactas** de los 5 toques (números 1–5 en verde translúcido; cada una se marca ✓ al completarse y se reinicia si vence el tiempo), la zona del deslizamiento con una flecha y “Mantén volumen + desliza”, y un indicador “Volumen detectado”. Sub-opción **“También en modo real”** (apagada por defecto).

### Menú de apagado falso + animación

Con el modo armado, al mantener el botón de encendido se cierra el menú real y aparece uno **falso** (ajuste “Menú falso + animación”, activado por defecto; si lo apagas, va directo a negro):

- **Desliza para apagar** → animación “RED MAGIC” + “Apagando…” (~2.5 s) → apagado falso. El auto-desbloqueo empieza **cuando inicia el negro**.
- **Reiniciar** → “Reiniciando…”, ~3 s en negro, arranque falso “RED MAGIC” → apagado falso.
- **Emergencia** → quita todo y abre el **marcador de emergencia real** (`com.android.phone.EmergencyDialer.DIAL`; si no existe, el marcador con 911). Nunca se bloquean las llamadas de emergencia.
- **Tocar fuera o Atrás** lo cierra, como el real; la protección sigue armada. Se cierra solo a los 60 s o si se apaga la pantalla.
- Botón **“Probar menú de apagado”** en la app.

**Referencia de diseño:** el manual del Red Magic 10 Pro dice “mantén presionado Encendido y toca ‘Deslizar para apagar’”; usuarios del Red Magic 11 Pro describen las opciones “Apagar” y “Reiniciar”, y el menú del panel rápido incluye Emergencia. **No encontré capturas fiables** del menú de Red Magic OS 10, así que el aspecto es una aproximación (fondo oscuro translúcido, píldora “Desliza para apagar”, botones redondos). El logo es texto “RED MAGIC” dibujado por la app, sin imágenes del fabricante.

**Límites:** el menú real puede verse un instante antes que el falso. Las animaciones duran a lo mucho ~9 s y hay un respaldo de 20 s que entra a negro si algo se atora.

## Cambios en v0.1.2

1. **Pantalla principal rediseñada.** Arriba, una tarjeta de estado con el interruptor grande Armado/Desarmado, chips de “Servicio” y “Accesibilidad”, la última ubicación en una línea y el botón **Probar apagado falso**. Después, **Permisos** como lista (palomita verde / tache rojo, toca la fila para abrir el ajuste) con un resumen “N/7 listos”. Luego **Ajustes** (auto-desbloqueo, intervalo de ubicación, webhook, interruptores), **Luces RGB** y al final **Ayuda** con secciones plegables (gestos, huella, cuándo se activa, luces, límites, avanzado). La explicación general está en el botón ⓘ.
2. **Apagar luces RGB al bloquear** (encendido por defecto, pero **no hace nada hasta que elijas una clave** en el diagnóstico).
   - **Detección de bloqueo:** el servicio en primer plano registra en tiempo de ejecución `SCREEN_OFF`, `SCREEN_ON` y `USER_PRESENT`. Tras `SCREEN_OFF` revisa `KeyguardManager.isKeyguardLocked()` / `isDeviceLocked()` a los 0.5, 3, 10 y 30 s (por el “bloquear después de X s”). Si está bloqueado, apaga las luces. Con `USER_PRESENT` (desbloqueo por huella/PIN) o `SCREEN_ON` sin bloqueo, las devuelve al valor que tenían. El servicio sigue corriendo aunque el modo antirrobo esté desarmado, si esta función está activa y hay clave.
   - **Control de luces:** Nubia no publica una API. Solo se escribe la clave que **tú** confirmas en el diagnóstico; nunca se escribe a ciegas.
3. **Diagnóstico de luces.** Lee las tablas `system`, `secure` y `global` y filtra claves con light/led/rgb/breath/ambient/fan/nubia/redmagic. Guarda cada escaneo en `files/lights_scan_*.txt`. Pasos: luces encendidas en Game Space → **A**; apágalas → **B**; la app muestra qué claves cambiaron y con **Usar** guardas una (valor encendido = el de A, apagado = el de B). Botones **Apagar / Encender** para probarla.

**Límites de las luces (no verificado en el aparato):**

- Android 12+ puede ocultar claves no públicas a apps de terceros; el escaneo puede salir incompleto. Alternativa por adb:
  ```
  adb shell settings list system | grep -iE 'light|led|rgb|breath|ambient|fan|nubia|redmagic'
  adb shell settings list secure | grep -iE 'light|led|rgb|breath|ambient|fan|nubia|redmagic'
  adb shell settings list global | grep -iE 'light|led|rgb|breath|ambient|fan|nubia|redmagic'
  ```
- Escribir claves de OEM normalmente **no** se permite solo con “Modificar ajustes del sistema”. Concede una vez:
  ```
  adb shell pm grant com.george.antirrobo android.permission.WRITE_SECURE_SETTINGS
  ```
- Game Space podría guardar el estado de las luces en su propia base de datos o aplicarlo solo cuando él mismo cambia; entonces cambiar la clave no apaga las luces y este método no sirve sin root.

## Cambios en v0.1.1

1. **Auto-desbloqueo que no disparaba (corregido).** Causa raíz: tras `addView()` del overlay se comprobaba `isAttachedToWindow`, que todavía es `false` hasta el siguiente frame; el código lo tomaba como error, no marcaba el estado como activo y **nunca arrancaba el temporizador**, dejando el overlay negro puesto. Además, `Handler.postDelayed` usa `uptimeMillis`, que se detiene si la CPU se duerme. Ahora: el temporizador se arranca antes que todo, con fecha límite en `elapsedRealtime`, ticks cada 500 ms en dos hilos, alarma exacta `setExactAndAllowWhileIdle` (ELAPSED_REALTIME_WAKEUP) que despierta la CPU, WakeLock parcial mientras dura el apagado falso, revisión del servicio en primer plano cada segundo, nunca se apilan overlays, y al salir se restaura brillo/audio/No Molestar/hápticos y se enciende la pantalla si estaba apagada.
2. **Vibración al mantener volumen (corregido en lo posible).** Causa raíz: por el bug 1 el estado “activo” quedaba en falso y la tecla no se consumía, así que el sistema cambiaba el volumen y vibraba. Ahora se consumen DOWN, repeticiones y UP mientras dura el apagado falso (la combinación de desbloqueo se registra antes de consumir), el overlay tiene `isHapticFeedbackEnabled=false`, y se ponen en 0 `HAPTIC_FEEDBACK_ENABLED` y `SOUND_EFFECTS_ENABLED` (con permiso “Modificar ajustes del sistema”), además de modo silencio y No Molestar total; todo se restaura al salir.
3. **Huella.** Ver la sección siguiente.
4. **El bloqueo normal ya no dispara el apagado falso.** Solo el menú de apagado (pulsación larga). `SCREEN_OFF` queda como opción, apagada por defecto.

## Huella digital en apagado falso

Lo que hace la app:

- **No usa `BiometricPrompt`**, porque dibuja su propia hoja/animación.
- Si el teléfono estaba **desbloqueado** al entrar al apagado falso (lo normal con la pulsación larga), el sistema **no está escuchando la huella**: poner el dedo no debe mostrar el círculo ni vibrar. La forma de salir es el gesto de volumen + swipe o el auto-desbloqueo.
- Si la **pantalla de bloqueo del sistema** está activa y la desbloqueas con huella o PIN, la app recibe `ACTION_USER_PRESENT` y quita el apagado falso sola.
- **Opción experimental “Huella silenciosa”** (apagada por defecto): abre una actividad negra debajo del overlay y escucha con la API antigua `FingerprintManager.authenticate()`, que no tiene ventana propia. Si la huella es válida, sale del apagado falso.

Lo que **no se puede garantizar sin root** (no verificado en el Red Magic 11 Pro):

- En sensores **bajo pantalla**, el **círculo/iluminación** del sensor y la **vibración** de éxito/fallo los dibuja y dispara **SystemUI / el servicio biométrico del sistema** (o la capa del fabricante), fuera de la ventana de la app. Si la pantalla de bloqueo o la opción experimental están escuchando la huella, esa animación y esa vibración **pueden aparecer** y una app normal no las puede ocultar.
- `FingerprintManager` está deprecado (API 28) y algunos firmwares lo rechazan o lo redirigen a la UI del sistema.
- El sensor bajo pantalla suele necesitar la pantalla encendida (aunque esté negra) para funcionar.
- `HAPTIC_FEEDBACK_ENABLED=0` apaga hápticos de toque, pero **no** necesariamente la vibración del sensor de huella ni hápticos que el fabricante dispare desde el HAL.

## 12. Límites que debes tener claros

1. Sin root **no** se intercepta `KEYCODE_POWER` antes que el sistema.  
2. El menú de apagado puede destellar; “Apagar” se podría alcanzar a tocar.  
3. El reinicio forzado de ~10 s (encendido + volumen) es de hardware.  
4. Si te desactivan la accesibilidad o matan la app (batería de Nubia, no estar en autostart), la protección se cae.  
5. El arranque en modo seguro desactiva apps de terceros.  
6. Las luces RGB y el ventilador **no** están verificados.  
7. El auto-desbloqueo (10 s por defecto) es a propósito para no dejarte fuera; un timeout corto reduce el valor antirrobo, uno largo te deja más tiempo “fuera” si te equivocas de gesto. Tú lo eliges.  
8. El webhook envía coordenadas: usa HTTPS y una URL tuya.  
9. Esta app es para el dueño del teléfono, no para instalarse en un aparato ajeno.  
10. Consumir las teclas de volumen desde accesibilidad evita el cambio de volumen; si el firmware de Nubia vibra en el paso de volumen antes de entregar la tecla (a nivel de política de sistema), esa vibración no se puede quitar sin root. No verificado.  
11. Sin el permiso de alarmas exactas (en Android 14 una app sideload lo recibe con `USE_EXACT_ALARM`), la alarma de respaldo puede retrasarse; los ticks con WakeLock siguen funcionando.

---

## 13. Privacidad

`android:allowBackup="false"`. Los registros de ubicación y eventos viven en el almacenamiento interno de la app (`files/`). El POST al webhook solo ocurre si **tú** pones una URL.
