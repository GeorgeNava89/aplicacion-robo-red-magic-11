package com.george.antirrobo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.george.antirrobo.service.AntirroboForegroundService
import com.george.antirrobo.ui.AntirroboTheme
import com.george.antirrobo.ui.SettingsScreen
import com.george.antirrobo.ui.SettingsViewModel
import com.george.antirrobo.util.EventLog
import com.george.antirrobo.util.PermissionChecker

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        viewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        setContent {
            AntirroboTheme {
                val ui by viewModel.ui.collectAsStateWithLifecycle()

                val notifLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { viewModel.refresh(this) }

                val fineLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { viewModel.refresh(this) }

                val bgLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { viewModel.refresh(this) }

                LaunchedEffect(Unit) {
                    viewModel.refresh(this@MainActivity)
                }

                SettingsScreen(
                    ui = ui,
                    onRefresh = { viewModel.refresh(this) },
                    onArmChange = { armed -> viewModel.setArmed(this, armed) },
                    onTimeoutChange = { viewModel.setTimeout(it) },
                    onTimeoutCommit = { viewModel.commitTimeout(it) },
                    onIntervalChange = { viewModel.setInterval(it) },
                    onIntervalCommit = { viewModel.commitInterval(it) },
                    onWebhookChange = { viewModel.setWebhookDraft(it) },
                    onWebhookSave = { viewModel.commitWebhook(this) },
                    onOpenAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestLocation = {
                        fineLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                    onRequestBackgroundLocation = {
                        if (Build.VERSION.SDK_INT >= 29) {
                            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    },
                    onOpenDnd = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    },
                    onOpenBattery = { requestBatteryExemption() },
                    onOpenWriteSettings = {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(packageUri()),
                        )
                    },
                    onOpenOverlay = {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(packageUri()),
                        )
                    },
                    onOpenAppInfo = { openAppInfo() },
                    onTestFakeOff = { viewModel.testFakeOff(this) },
                    onToggleDiagnostic = { viewModel.toggleDiagnostic() },
                    onToggleScreenOffTrigger = { viewModel.toggleScreenOffTrigger() },
                    onToggleFingerprintSilent = { viewModel.toggleFingerprintSilent() },
                    onApplyDeviceOwner = { viewModel.applyDeviceOwner() },
                    onClearDeviceOwner = { viewModel.clearDeviceOwner() },
                    onRestartFgs = {
                        AntirroboForegroundService.start(this)
                        viewModel.refresh(this)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh(this)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri()),
            )
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri()),
        )
    }

    private fun packageUri(): Uri = Uri.fromParts("package", packageName, null)

    @Suppress("unused")
    private fun permissionSnapshot() = PermissionChecker.report(this)
}
