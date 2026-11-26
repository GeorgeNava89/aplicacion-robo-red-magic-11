package com.george.antirrobo.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerMenuClassifierTest {

    private fun hit(pkg: String, cls: String, text: String = "", title: String = "") =
        PowerMenuClassifier.classify(pkg, cls, text, title)

    // ---------- Debe DETECTAR el diálogo real (AOSP / hipótesis Nubia) ----------

    @Test fun aospGlobalActionsDialog() {
        assertTrue(hit("com.android.systemui", "com.android.systemui.globalactions.GlobalActionsDialog").match)
        assertTrue(hit("com.android.systemui", "com.android.systemui.globalactions.GlobalActionsDialogLite").match)
        assertTrue(hit("android", "com.android.server.policy.GlobalActions").match)
        assertTrue(hit("com.android.systemui", "com.android.systemui.globalactions.GlobalActionsPopup").match)
    }

    @Test fun guessedNubiaAndOemClasses() {
        assertTrue(hit("com.android.systemui", "com.nubia.systemui.globalactions.NubiaGlobalActions").match)
        assertTrue(hit("cn.nubia.systemui", "cn.nubia.systemui.power.NubiaPowerMenu").match)
        assertTrue(hit("com.android.systemui", "com.zte.systemui.RedMagicGlobalActions").match)
        assertTrue(hit("com.android.systemui", "com.android.systemui.ShutdownDialog").match)
        assertTrue(hit("com.android.systemui", "com.android.systemui.powerui.PowerOffDialog").match)
    }

    @Test fun windowTitleWhitelistWhenClassGeneric() {
        assertTrue(
            hit("com.android.systemui", "android.widget.FrameLayout", title = "GlobalActionsDialog").match,
        )
    }

    // ---------- NO debe detectar el shade / Ajustes rápidos ----------

    @Test fun notificationShadeWindow() {
        assertFalse(
            hit("com.android.systemui", "com.android.systemui.shade.NotificationShadeWindowView").match,
        )
        assertFalse(
            hit("com.android.systemui", "com.android.systemui.statusbar.phone.NotificationPanelView").match,
        )
        assertFalse(
            hit("com.android.systemui", "com.android.systemui.statusbar.phone.StatusBarWindowView").match,
        )
    }

    @Test fun quickSettingsPanel() {
        assertFalse(hit("com.android.systemui", "com.android.systemui.qs.QSPanel").match)
        assertFalse(hit("com.android.systemui", "com.android.systemui.qs.QuickSettingsContainerView").match)
        assertFalse(hit("com.android.systemui", "com.android.systemui.shade.QuickSettingsController").match)
        assertFalse(hit("com.android.systemui", "com.android.systemui.qs.QSFooterView").match)
    }

    @Test fun textApagarInShadeIsNotEnough() {
        // Causa del bug en v0.1.6: SystemUI + texto "Apagar"/"Reiniciar" (pie de QS o una notificación).
        val v = hit(
            "com.android.systemui",
            "com.android.systemui.shade.NotificationShadeWindowView",
            text = "Apagar Reiniciar Emergencia",
        )
        assertFalse(v.match)
        assertTrue(v.reason.contains("panel"))
    }

    @Test fun textOnlyWithGenericClassIsNotEnough() {
        assertFalse(
            hit("com.android.systemui", "android.widget.FrameLayout", text = "Apagar").match,
        )
        assertFalse(
            hit("com.android.systemui", "", text = "Reiniciar el teléfono").match,
        )
        assertFalse(
            hit("com.android.systemui", "android.widget.FrameLayout", title = "Apagar").match,
        )
    }

    @Test fun qsFooterPowerButtonIsExcluded() {
        assertFalse(
            hit(
                "com.android.systemui",
                "com.android.systemui.qs.footer.FooterActionsPowerButton",
                text = "Apagar",
            ).match,
        )
        assertFalse(
            hit("com.android.systemui", "com.android.systemui.statusbar.phone.PowerMenuButton").match,
        )
    }

    @Test fun ownWindowsNeverMatch() {
        assertFalse(
            hit("com.george.antirrobo", "com.george.antirrobo.fakeoff.DecoyPowerMenu", text = "Apagar").match,
        )
    }

    @Test fun userAppsNeverMatch() {
        assertFalse(hit("com.whatsapp", "com.android.systemui.globalactions.GlobalActionsDialog").match)
    }

    @Test fun shadeMarkerWinsEvenIfNameContainsGlobalActions() {
        // Defensa: un contenedor del shade no debe pasar aunque el nombre sea raro.
        assertFalse(
            hit(
                "com.android.systemui",
                "com.android.systemui.shade.NotificationShadeWindowView.GlobalActions",
            ).match,
        )
    }

    @Test fun reasonsAreExplicit() {
        val ok = hit("com.android.systemui", "com.android.systemui.globalactions.GlobalActionsDialog")
        assertEquals(true, ok.match)
        assertTrue(ok.reason.contains("lista blanca"))
        val no = hit("com.android.systemui", "com.android.systemui.qs.QSPanel")
        assertTrue(no.reason.contains("Ajustes rápidos") || no.reason.contains("panel"))
    }
}
