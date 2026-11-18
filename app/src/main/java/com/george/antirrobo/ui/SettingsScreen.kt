package com.george.antirrobo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.nubia.LightsDiagnostics
import kotlin.math.roundToInt

private val Ok = Color(0xFF66BB6A)
private val Bad = Color(0xFFEF5350)

/**
 * v0.1.2: pantalla rediseñada. Arriba el estado y el interruptor grande;
 * luego permisos (una fila por permiso), ajustes y, al final, ayuda plegable.
 * Los textos largos viven en las secciones de "Ayuda".
 */
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
    onToggleLightsOnLock: () -> Unit,
    onScanLights: (String) -> Unit,
    onUseLightKey: (LightsDiagnostics.Diff) -> Unit,
    onClearLightKey: () -> Unit,
    onTestLights: (Boolean) -> Unit,
) {
    var showInfo by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Encabezado
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                "Antirrobo",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showInfo = true }) {
                Icon(Icons.Filled.Info, contentDescription = "Qué hace")
            }
        }

        StatusCard(ui, onArmChange, onTestFakeOff, onRestartFgs)
        PermissionsCard(
            ui,
            onOpenAccessibility, onRequestLocation, onRequestBackgroundLocation,
            onRequestNotifications, onOpenDnd, onOpenBattery, onOpenWriteSettings,
            onOpenOverlay, onOpenAppInfo,
        )
        SettingsCard(
            ui, onTimeoutChange, onTimeoutCommit, onIntervalChange, onIntervalCommit,
            onWebhookChange, onWebhookSave, onToggleScreenOffTrigger, onToggleFingerprintSilent,
        )
        LightsCard(ui, onToggleLightsOnLock, onScanLights, onUseLightKey, onClearLightKey, onTestLights)
        HelpCard(ui, onToggleDiagnostic, onRefresh, onApplyDeviceOwner, onClearDeviceOwner)
        Spacer(Modifier.height(8.dp))
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Entendido") } },
            title = { Text("¿Qué hace?") },
            text = {
                Text(
                    "Con el modo armado, si alguien mantiene presionado el botón de encendido, " +
                        "el teléfono parece apagado pero sigue encendido y guarda su ubicación.\n\n" +
                        "Si no lo desbloqueas, vuelve solo a los segundos que elijas.\n\n" +
                        "Detalles y límites: sección Ayuda.",
                )
            },
        )
    }
}

// ---------- Estado ----------

@Composable
private fun StatusCard(
    ui: SettingsUiState,
    onArmChange: (Boolean) -> Unit,
    onTestFakeOff: () -> Unit,
    onRestartFgs: () -> Unit,
) {
    val s = ui.status
    val accOk = s.accessibilityRunning || ui.permissions?.accessibility == true
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (s.armed) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (s.armed) "Armado" else "Desarmado",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (s.armed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (s.fakeOffActive) {
                        Text("Apagado falso activo", color = MaterialTheme.colorScheme.primary)
                    }
                }
                Switch(checked = s.armed, onCheckedChange = onArmChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateChip("Servicio", s.fgsRunning, onClick = onRestartFgs)
                StateChip("Accesibilidad", accOk)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.LocationOn, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    shortLocation(s.lastLocationText, s.lastLocationTimeMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(onClick = onTestFakeOff, modifier = Modifier.fillMaxWidth()) {
                Text("Probar apagado falso")
            }
        }
    }
}

@Composable
private fun StateChip(label: String, ok: Boolean, onClick: () -> Unit = {}) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                null,
                tint = if (ok) Ok else Bad,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
    )
}

// ---------- Permisos ----------

private data class PermRow(val label: String, val ok: Boolean?, val onClick: () -> Unit)

@Composable
private fun PermissionsCard(
    ui: SettingsUiState,
    onOpenAccessibility: () -> Unit,
    onRequestLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenDnd: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val p = ui.permissions
    val rows = listOf(
        PermRow("Accesibilidad", p?.accessibility, onOpenAccessibility),
        PermRow("Ubicación", p?.fineLocation, onRequestLocation),
        PermRow("Ubicación siempre", p?.backgroundLocation, onRequestBackgroundLocation),
        PermRow("Notificaciones", p?.notifications, onRequestNotifications),
        PermRow("No Molestar", p?.dnd, onOpenDnd),
        PermRow("Batería sin límites", p?.batteryExemption, onOpenBattery),
        PermRow("Ajustes del sistema", p?.writeSettings, onOpenWriteSettings),
    )
    val ready = rows.count { it.ok == true }
    var expanded by rememberSaveable { mutableStateOf(true) }

    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Text("Permisos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            AssistChip(
                onClick = { expanded = !expanded },
                label = { Text("$ready/${rows.size} listos") },
                leadingIcon = {
                    Icon(
                        if (ready == rows.size) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                        null,
                        tint = if (ready == rows.size) Ok else Bad,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                rows.forEach { PermissionRow(it.label, it.ok, it.onClick) }
                PermissionRow("Superposición (opcional)", p?.overlay, onOpenOverlay, optional = true)
                PermissionRow("Ajustes restringidos", null, onOpenAppInfo)
                Text(
                    "Toca una fila para abrir el ajuste.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean?, onClick: () -> Unit, optional: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        val (icon, tint) = when {
            ok == true -> Icons.Filled.CheckCircle to Ok
            ok == false && optional -> Icons.Filled.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
            ok == false -> Icons.Filled.Cancel to Bad
            else -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Ajustes ----------

@Composable
private fun SettingsCard(
    ui: SettingsUiState,
    onTimeoutChange: (Int) -> Unit,
    onTimeoutCommit: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onIntervalCommit: (Int) -> Unit,
    onWebhookChange: (String) -> Unit,
    onWebhookSave: () -> Unit,
    onToggleScreenOffTrigger: () -> Unit,
    onToggleFingerprintSilent: () -> Unit,
) {
    SectionCard {
        Text("Ajustes", style = MaterialTheme.typography.titleMedium)

        LabeledValue("Auto-desbloqueo", "${ui.timeoutSeconds} s")
        Slider(
            value = ui.timeoutSeconds.toFloat(),
            onValueChange = { onTimeoutChange(it.roundToInt()) },
            onValueChangeFinished = { onTimeoutCommit(ui.timeoutSeconds) },
            valueRange = AppPreferences.MIN_TIMEOUT.toFloat()..300f,
        )

        LabeledValue("Guardar ubicación cada", "${ui.intervalSeconds} s")
        Slider(
            value = ui.intervalSeconds.toFloat(),
            onValueChange = { onIntervalChange(it.roundToInt()) },
            onValueChangeFinished = { onIntervalCommit(ui.intervalSeconds) },
            valueRange = AppPreferences.MIN_LOC.toFloat()..600f,
        )

        OutlinedTextField(
            value = ui.webhookDraft,
            onValueChange = onWebhookChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Webhook (opcional)") },
            placeholder = { Text("https://…") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = onWebhookSave) {
                    Icon(Icons.Filled.Save, contentDescription = "Guardar webhook")
                }
            },
        )

        HorizontalDivider()
        SwitchRow("Activar al bloquear pantalla", ui.screenOffTrigger, onToggleScreenOffTrigger)
        SwitchRow("Huella silenciosa (beta)", ui.fingerprintSilent, onToggleFingerprintSilent)
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

// ---------- Luces RGB ----------

@Composable
private fun LightsCard(
    ui: SettingsUiState,
    onToggle: () -> Unit,
    onScan: (String) -> Unit,
    onUse: (LightsDiagnostics.Diff) -> Unit,
    onClear: () -> Unit,
    onTest: (Boolean) -> Unit,
) {
    SectionCard {
        Text("Luces RGB", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Apagar luces RGB al bloquear", ui.lightsOnLock, onToggle)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ok = ui.rgbKeyLabel.isNotBlank()
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel, null,
                tint = if (ok) Ok else Bad, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (ok) ui.rgbKeyLabel else "Sin clave: usa el diagnóstico",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (ui.rgbKeyLabel.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onTest(true) }, modifier = Modifier.weight(1f)) { Text("Apagar") }
                OutlinedButton(onClick = { onTest(false) }, modifier = Modifier.weight(1f)) { Text("Encender") }
                TextButton(onClick = onClear) { Text("Borrar") }
            }
        }
        Collapsible("Diagnóstico de luces") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HelpText("1. Enciende las luces en Game Space y toca A.\n2. Apágalas en Game Space y toca B.\n3. Elige la clave que cambió.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onScan("A") }, modifier = Modifier.weight(1f)) {
                        Text(if (ui.scanASummary.isBlank()) "A: encendidas" else "A ✓ ${ui.scanASummary}", maxLines = 1)
                    }
                    FilledTonalButton(onClick = { onScan("B") }, modifier = Modifier.weight(1f)) {
                        Text(if (ui.scanBSummary.isBlank()) "B: apagadas" else "B ✓ ${ui.scanBSummary}", maxLines = 1)
                    }
                }
                if (ui.scanASummary.isNotBlank() && ui.scanBSummary.isNotBlank() && ui.lightDiffs.isEmpty()) {
                    HelpText("Sin cambios entre A y B.")
                }
                ui.lightDiffs.take(12).forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${d.table}/${d.key}: ${d.before} → ${d.after}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onUse(d) }) { Text("Usar") }
                    }
                }
                if (ui.lightsMessage.isNotBlank()) {
                    Text(
                        ui.lightsMessage, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------- Ayuda ----------

@Composable
private fun HelpCard(
    ui: SettingsUiState,
    onToggleDiagnostic: () -> Unit,
    onRefresh: () -> Unit,
    onApplyDeviceOwner: () -> Unit,
    onClearDeviceOwner: () -> Unit,
) {
    SectionCard {
        Text("Ayuda", style = MaterialTheme.typography.titleMedium)
        Collapsible("Gestos") {
            HelpText(
                "• Desbloquear: mantén VOLUMEN y desliza hacia arriba desde abajo a la izquierda.\n" +
                    "• Apagar de verdad: toca las esquinas sup-izq → sup-der → inf-der → inf-izq → centro " +
                    "(menos de 2 s entre toques).\n" +
                    "• Si fallas, espera: vuelve solo en ${ui.timeoutSeconds} s.",
            )
        }
        Collapsible("Huella") {
            HelpText(
                "• Si desbloqueas desde la pantalla de bloqueo del sistema, el apagado falso se quita solo.\n" +
                    "• \"Huella silenciosa (beta)\" escucha la huella sin ventana propia. No verificado en Red Magic.\n" +
                    "• El círculo del sensor y su vibración los muestra el sistema; sin root no se pueden ocultar.",
            )
        }
        Collapsible("Cuándo se activa") {
            HelpText(
                "• Solo al mantener presionado el botón de encendido (menú de apagado).\n" +
                    "• \"Activar al bloquear pantalla\" también lo activa con la pulsación corta. Viene apagado.",
            )
        }
        Collapsible("Luces RGB") {
            HelpText(
                "• Nubia no publica cómo controlar las luces; la clave se descubre con el diagnóstico.\n" +
                    "• Si el escaneo sale vacío o no cambia nada, Game Space quizá no use Settings.\n" +
                    "• Escribir claves de Nubia suele requerir, una vez por adb:\n" +
                    "  pm grant com.george.antirrobo android.permission.WRITE_SECURE_SETTINGS\n" +
                    "• No verificado en el Red Magic 11 Pro.",
            )
        }
        Collapsible("Límites") {
            HelpText(
                "• Sin root no se puede atrapar el botón de encendido; el menú puede verse un instante.\n" +
                    "• Encendido + volumen ~10 s fuerza el reinicio y no se puede bloquear.\n" +
                    "• Si Nubia cierra la app (batería/inicio automático), la protección se cae.\n" +
                    "• Luces RGB y ventilador: intento no verificado.\n" +
                    "• Android 13+: si accesibilidad sale gris, en \"Ajustes restringidos\" usa ⋮ → Permitir.",
            )
        }
        Collapsible("Avanzado") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow("Registrar ventanas (diagnóstico)", ui.diagnostic, onToggleDiagnostic)
                Text(ui.deviceOwnerText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onApplyDeviceOwner, modifier = Modifier.weight(1f)) {
                        Text("Aplicar DO")
                    }
                    OutlinedButton(onClick = onClearDeviceOwner, modifier = Modifier.weight(1f)) {
                        Text("Quitar DO")
                    }
                }
                if (ui.policyMessage.isNotBlank()) {
                    Text(ui.policyMessage, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Actualizar estado")
                }
                Text(
                    "Ubicaciones: ${ui.locationLogPath}\nEventos: ${ui.eventLogPath}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Collapsible(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 10.dp),
        ) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.padding(bottom = 8.dp)) { content() }
        }
    }
}

@Composable
private fun HelpText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

/** "19.4326, -99.1332 · 21:58" o "Sin ubicación aún". */
private fun shortLocation(text: String, timeMs: Long): String {
    val lat = Regex("lat=(-?[0-9.]+)").find(text)?.groupValues?.get(1)
    val lon = Regex("lon=(-?[0-9.]+)").find(text)?.groupValues?.get(1)
    if (lat == null || lon == null) return "Sin ubicación aún"
    val time = formatTimeMexico(timeMs).substringAfter(' ').substringBefore(' ').take(5)
    return "${lat.take(8)}, ${lon.take(9)} · $time"
}
