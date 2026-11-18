package com.george.antirrobo.fakeoff

import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Parámetros comunes para ventanas TYPE_ACCESSIBILITY_OVERLAY a pantalla completa REAL. */
internal fun fullScreenOverlayParams(title: String, opaque: Boolean): WindowManager.LayoutParams =
    WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = if (opaque) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.TOP or Gravity.START
        this.title = title
        flags = (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            fitInsetsTypes = 0
            fitInsetsSides = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

internal fun WindowManager.safeRemove(view: View?) {
    if (view == null) return
    try {
        removeViewImmediate(view)
    } catch (_: Throwable) {
        try {
            removeView(view)
        } catch (_: Throwable) {
        }
    }
}
