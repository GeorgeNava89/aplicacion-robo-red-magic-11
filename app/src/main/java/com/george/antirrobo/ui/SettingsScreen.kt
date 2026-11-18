package com.george.antirrobo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.util.PermissionReport
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    ui: SettingsUiState,
    onRefresh: () -> Unit,
    onArmChange: (Boolean) -> Unit,
    onTimeoutChange: (Int) -> Unit,
    onTimeoutCommit: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onIntervalCommit: (Int) -> Unit,
    onWebhookChange: (String) -> Unit,
    onWebhookSave: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onOpenDnd: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onTestFakeOff: () -> Unit,
    onToggleDiagnostic: () -> Unit,
    onToggleScreenOffTrigger: () -> Unit,
    onToggleFingerprintSilent: () -> Unit,
    onApplyDeviceOwner: () -> Unit,
    onClearDeviceOwner: () -> Unit,
    onRestartFgs: () -> Unit,
) {
    val p = ui.permissions
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Antirrobo Red Magic",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Prototipo para tu Red Magic 11 Pro. Sin root no se puede interceptar el botón de encendido antes que el sistema: se detecta el menú de apagado y se cierra, y luego entra el apagado falso. El teléfono nunca queda bloqueado de forma permanente (auto-desbloqueo).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CardSection(title = "Modo armado") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ui.status.armed) "ARMADO" else "Desarmado",
                    fontWeight = FontWeight.Bold,
                    color = if (ui.status.armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    fontSize = 20.sp,
                )
                Switch(checked = ui.status.armed, onCheckedChange = onArmChange)
            }
            StatusLine("Apagado falso", siNo(ui.status.fakeOffActive), ui.status.fakeOffActive)
            StatusLine("Servicio en primer plano", siNo(ui.status.fgsRunning), ui.status.fgsRunning)
            StatusLine("Accesibilidad", siNo(ui.status.accessibilityRunning || p?.accessibility == true), p?.accessibility == true)
            StatusLine("Device Owner", siNo(ui.status.deviceOwner), ui.status.deviceOwner)
            StatusLine("Último evento", ui.status.lastEvent, null)
            Spacer(Modifier.height(4.dp))
            Text("Última ubicación", fontWeight = FontWeight.SemiBold)
            Text(
                ui.status.lastLocationText.ifBlank { "—" },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                "Hora: ${formatTimeMexico(ui.status.lastLocationTimeMs)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                OutlinedButton(onClick = onTestFakeOff, modifier = Modifier.weight(1f)) {
                    Text("Probar apagado falso")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRestartFgs, modifier = Modifier.weight(1f)) {
                    Text("Reiniciar servicio")
                }
            }
        }

        CardSection(title = "Red de seguridad (auto-desbloqueo)") {
            Text(
                "Si el gesto falla o algo sale mal, el teléfono se desbloquea solo. Por defecto son 10 segundos, para que nunca quede bloqueado de forma permanente. Si lo subes, un ladrón vería el “apagado” durante más tiempo, pero tú tardarías más en recuperar el uso si te equivocas de gesto.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("${ui.timeoutSeconds} segundos", fontWeight = FontWeight.Bold)
            Slider(
                value = ui.timeoutSeconds.toFloat(),
                onValueChange = { onTimeoutChange(it.roundToInt()) },
                onValueChangeFinished = { onTimeoutCommit(ui.timeoutSeconds) },
                valueRange = AppPreferences.MIN_TIMEOUT.toFloat()..300f,
                steps = 0,
            )
            Text(
                "Rango en esta barra: 5–300 s. En preferencias se acepta hasta 1800 s (30 min) si lo escribes a mano en el futuro. El valor se guarda al soltar.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CardSection(title = "Ubicación y webhook") {
            Text("Intervalo de registro: ${ui.intervalSeconds} s")
            Slider(
                value = ui.intervalSeconds.toFloat(),
                onValueChange = { onIntervalChange(it.roundToInt()) },
                onValueChangeFinished = { onIntervalCommit(ui.intervalSeconds) },
                valueRange = AppPreferences.MIN_LOC.toFloat()..600f,
                steps = 0,
            )
            OutlinedTextField(
                value = ui.webhookDraft,
                onValueChange = onWebhookChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("URL del webhook (opcional, HTTPS)") },
                singleLine = true,
                placeholder = { Text("https://ejemplo.com/rastreo") },
            )
            Button(onClick = onWebhookSave, modifier = Modifier.fillMaxWidth()) {
                Text("Guardar webhook")
            }
            Text(
                "La ubicación se guarda siempre en:\n${ui.locationLogPath}\nEventos de diagnóstico:\n${ui.eventLogPath}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CardSection(title = "Permisos y ajustes del sistema") {
            if (p != null) PermissionList(p) else Text("Cargando…")
            Spacer(Modifier.height(4.dp))
            WrapButtons(
                "Activar accesibilidad" to onOpenAccessibility,
                "Notificaciones" to onRequestNotifications,
                "Ubicación" to onRequestLocation,
                "Ubicación en segundo plano" to onRequestBackgroundLocation,
                "No Molestar (DND)" to onOpenDnd,
                "Excluir de batería" to onOpenBattery,
                "Modificar ajustes del sistema" to onOpenWriteSettings,
                "Superposición (opcional)" to onOpenOverlay,
                "Info de la app (ajustes restringidos)" to onOpenAppInfo,
            )
            Text(
                "Android 13+: si la app se instaló fuera de Play Store, el servicio de accesibilidad puede estar en “ajustes restringidos”. En Info de la app, menú de tres puntos, activa “Permitir ajustes restringidos” y luego vuelve a Accesibilidad.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CardSection(title = "Gestos (apréndetelos antes de armar)") {
            Text("Desbloqueo", fontWeight = FontWeight.Bold)
            Text(
                "Mantén presionado VOLUMEN (arriba o abajo) y, al mismo tiempo, desliza hacia ARRIBA desde la esquina inferior izquierda (el tercio de abajo, lado izquierdo), recorriendo por lo menos la mitad de la pantalla.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text("Apagado real (gesto oculto)", fontWeight = FontWeight.Bold)
            Text(
                "Con el apagado falso activo, toca en este orden, con menos de 2 segundos entre toques: esquina superior izquierda → superior derecha → inferior derecha → inferior izquierda → centro. Se abre el diálogo real de apagado del sistema. Los botones físicos no apagan el teléfono mientras está armado (en la medida de lo posible; ver límites).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text("Límite de hardware", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(
                "Mantener encendido + volumen unos 10 segundos fuerza un reinicio a nivel de hardware/bootloader. Eso no lo puede bloquear ninguna app, ni con root, de forma fiable. Es una vía de escape deliberada del teléfono.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        CardSection(title = "Disparadores y huella (v0.1.1)") {
            Text(
                "Por defecto SOLO el menú de apagado (pulsación larga de encendido) activa el apagado falso. El bloqueo normal de pantalla no lo activa.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("También al apagar la pantalla (SCREEN_OFF)", modifier = Modifier.weight(1f))
                Switch(checked = ui.screenOffTrigger, onCheckedChange = { onToggleScreenOffTrigger() })
            }
            Text(
                "Si desbloqueas el teléfono con huella/PIN desde la pantalla de bloqueo del sistema, el apagado falso se quita solo.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Huella silenciosa (EXPERIMENTAL)", modifier = Modifier.weight(1f))
                Switch(checked = ui.fingerprintSilent, onCheckedChange = { onToggleFingerprintSilent() })
            }
            Text(
                "Usa la API antigua de huella sin ventana propia. No verificado en Red Magic: el sistema puede mostrar el círculo del sensor y vibrar, y eso no se puede ocultar sin root.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CardSection(title = "Diagnóstico del menú de apagado (Nubia)") {
            Text(
                "Como el nombre de clase del menú de apagado en Red Magic OS no está documentado, activa esto, abre el menú de apagado una vez y revisa la bitácora. Ahí verás paquete, clase y texto para ajustar la detección.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Registrar ventanas", modifier = Modifier.weight(1f))
                Switch(checked = ui.diagnostic, onCheckedChange = { onToggleDiagnostic() })
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Actualizar estado")
            }
        }

        CardSection(title = "Device Owner (opcional, adb)") {
            Text(ui.deviceOwnerText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(
                "No intercepta el botón de encendido. Ver el README para el comando adb. Úsalo solo en tu propio teléfono; suele exigir un restablecimiento de fábrica si ya hay una cuenta.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                OutlinedButton(onClick = onApplyDeviceOwner, modifier = Modifier.weight(1f)) {
                    Text("Aplicar restricciones")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onClearDeviceOwner, modifier = Modifier.weight(1f)) {
                    Text("Quitar restricciones")
                }
            }
            if (ui.policyMessage.isNotBlank()) {
                Text(ui.policyMessage, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Luces RGB y ventilador: no hay API pública. El código incluye intentos no verificados (Settings y sysfs) que debes comprobar en el aparato. Ver README.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CardSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint) = when (ok) {
            true -> Icons.Filled.CheckCircle to Color(0xFF66BB6A)
            false -> Icons.Filled.Warning to Color(0xFFFFCA28)
            null -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(end = 6.dp))
        Text("$label: ", fontWeight = FontWeight.SemiBold)
        Text(value, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PermissionList(p: PermissionReport) {
    StatusLine("Accesibilidad", siNo(p.accessibility), p.accessibility)
    StatusLine("Ubicación", siNo(p.fineLocation), p.fineLocation)
    StatusLine("Ubicación en segundo plano", siNo(p.backgroundLocation), p.backgroundLocation)
    StatusLine("Notificaciones", siNo(p.notifications), p.notifications)
    StatusLine("No Molestar", siNo(p.dnd), p.dnd)
    StatusLine("Exento de batería", siNo(p.batteryExemption), p.batteryExemption)
    StatusLine("Escribir ajustes (brillo)", siNo(p.writeSettings), p.writeSettings)
    StatusLine("Superposición (opcional)", siNo(p.overlay), p.overlay)
}

@Composable
private fun WrapButtons(vararg items: Pair<String, () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, onClick) ->
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text(label)
            }
        }
    }
}

private fun siNo(v: Boolean) = if (v) "sí" else "no"

