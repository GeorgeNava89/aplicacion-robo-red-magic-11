package com.george.antirrobo.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.george.antirrobo.service.AntirroboAccessibilityService

data class PermissionReport(
    val accessibility: Boolean,
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val notifications: Boolean,
    val dnd: Boolean,
    val batteryExemption: Boolean,
    val writeSettings: Boolean,
    val overlay: Boolean,
) {
    val essentialOk: Boolean
        get() = accessibility && fineLocation
}

object PermissionChecker {

    fun report(context: Context): PermissionReport {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val notifications = if (Build.VERSION.SDK_INT >= 33) {
            granted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        return PermissionReport(
            accessibility = isAccessibilityEnabled(context),
            fineLocation = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(context, Manifest.permission.ACCESS_COARSE_LOCATION),
            backgroundLocation = if (Build.VERSION.SDK_INT >= 29) {
                granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                true
            },
            notifications = notifications,
            dnd = nm?.isNotificationPolicyAccessGranted == true,
            batteryExemption = pm?.isIgnoringBatteryOptimizations(context.packageName) == true,
            writeSettings = Settings.System.canWrite(context),
            overlay = Settings.canDrawOverlays(context),
        )
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = AntirroboAccessibilityService::class.java.name
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@any false
            si.packageName == context.packageName && si.name == expected
        }
    }

    fun granted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
