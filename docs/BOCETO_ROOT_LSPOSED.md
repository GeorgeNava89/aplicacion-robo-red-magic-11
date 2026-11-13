# Boceto de ruta root (Magisk / LSPosed)

**Esto NO es un módulo funcional.** Es un apunte para quien ya tiene root en su
propio Red Magic 11 Pro y quiere investigar cómo interceptar `KEYCODE_POWER`
antes que el sistema. No se incluye código compilable de hook, ni un módulo
listo para instalar, ni un procedimiento de ataque.

## Por qué hace falta root (o un hook de sistema)

En Android, la tecla de encendido la consume el sistema en

```
com.android.server.policy.PhoneWindowManager.interceptKeyBeforeQueueing(
    KeyEvent event, int policyFlags)
```

Ese método corre en el proceso `system_server`. Una app normal, un
`AccessibilityService` con `flagRequestFilterKeyEvents` y un Device Owner
**no lo alcanzan**. Por eso `onKeyEvent` casi nunca ve `KEYCODE_POWER`.

## Idea general de un hook (solo boceto)

Un módulo Xposed / LSPosed (o un parche de Magisk sobre el framework) podría
enganchar ese método y, si la app del dueño está armada, **no** dejar que la
tecla abra el menú de apagado ni apague la pantalla. En pseudocódigo:

```
// SOLO BOCETO — no es un módulo, no está probado, no se instala así.
// PhoneWindowManager.interceptKeyBeforeQueueing(KeyEvent event, int policyFlags)

if (event.getKeyCode() == KeyEvent.KEYCODE_POWER) {
    if (ownerAppIsArmed()) {          // consultar de forma explícita a TU app
        // Devolver el código que signifique "ya se manejó, no seguir"
        // (el valor exacto depende de la versión de AOSP / del OEM).
        notifyOwnerAppToEnterFakeOff();
        return 0; // valor ilustrativo, NO verificado en Red Magic 11 Pro
    }
}
// Si no, llamar al método original.
```

Detalles que habría que resolver en el dispositivo real y que **no** están
resueltos aquí:

- El nombre y la firma del método pueden cambiar con el firmware de Nubia.
- El valor de retorno que significa “consumido” hay que leerlo del AOSP de
  esa API (no se afirma aquí).
- Cómo comunica el módulo con `com.george.antirrobo` (ContentProvider
  firmado, socket protegido, etc.) sin abrir un agujero de seguridad.
- El hook tiene que respetar el reinicio forzado de hardware
  (encendido + volumen ~10 s): ese camino es de más bajo nivel y **no**
  se debe intentar bloquear; es la vía de escape del teléfono.

## Lo que este prototipo no hace

- No incluye un módulo `.apk` / `.zip` de Magisk o LSPosed.
- No incluye smali, parches de `services.jar` ni scripts de instalación.
- No se da una guía paso a paso para rootea r el Red Magic 11 Pro.

Si en el futuro implementas un módulo para tu propio teléfono, mantenlo
fuera de este repositorio de la app de usuario y no lo publiques como
“listo para usar” sin probarlo en tu firmware exacto.
