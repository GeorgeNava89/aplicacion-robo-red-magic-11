package com.george.antirrobo.service

/**
 * v0.1.7 — Clasificador del menú de apagado REAL del sistema.
 *
 * En v0.1.6 (y antes) bastaba con que el paquete fuera SystemUI y el texto
 * mencionara "Apagar" / "Reiniciar" / "Emergencia". Eso también ocurre en el
 * panel de notificaciones y en Ajustes rápidos de Nubia/Red Magic (el pie del
 * panel trae un botón de apagado). Por eso bajar el dedo desde la esquina
 * superior izquierda (notificaciones) mostraba el menú falso.
 *
 * Ahora se exige un nombre de clase de la lista blanca (GlobalActions y
 * equivalentes) y se rechazan de forma explícita el shade, Ajustes rápidos y
 * el botón de apagado del pie. El texto SOLO no basta.
 *
 * Los nombres exactos del diálogo en Red Magic OS no están verificados; si
 * el menú real deja de detectarse, en la bitácora queda paquete + clase.
 */
object PowerMenuClassifier {

    data class Verdict(val match: Boolean, val reason: String)

    fun classify(pkg: String, cls: String, text: String = "", title: String = ""): Verdict {
        val pkgL = pkg.lowercase()
        val clsL = cls.lowercase()
        val blob = "$cls $title $text"

        if (pkgL.startsWith("com.george.antirrobo")) {
            return no("ventana propia de la app")
        }
        if (!isSystemPackage(pkgL)) {
            return no("paquete no es SystemUI/sistema ($pkg)")
        }
        shadeOrQsMarker(blob)?.let { marker ->
            return no("es el panel de notificaciones o Ajustes rápidos (marcador='$marker')")
        }
        if (cls.isBlank() && title.isBlank()) {
            return no("sin clase ni título de ventana; el texto solo no basta")
        }
        val classHit = whitelistHit(cls)
        val titleHit = if (title.isNotBlank() && !looksLikeGenericTitle(title)) whitelistHit(title) else null
        if (classHit != null) return yes("clase en lista blanca ('$classHit') pkg=$pkg class=$cls")
        if (titleHit != null) return yes("título de ventana en lista blanca ('$titleHit') pkg=$pkg title=$title")
        return no("clase/título fuera de la lista blanca pkg=$pkg class=$cls title=$title")
    }

    fun isMatch(pkg: String, cls: String, text: String = "", title: String = ""): Boolean =
        classify(pkg, cls, text, title).match

    fun isSystemPackage(pkgL: String): Boolean {
        if (pkgL.isEmpty()) return false
        return SYSTEM_PACKAGES.any { pkgL == it || pkgL.startsWith("$it.") } ||
            pkgL.contains("systemui")
    }

    internal fun shadeOrQsMarker(blob: String): String? {
        val s = blob.lowercase()
        return SHADE_OR_QS_MARKERS.firstOrNull { s.contains(it) }
    }

    internal fun whitelistHit(name: String): String? {
        val s = name.lowercase()
        // "powermenubutton" / "powerbutton" del pie de Ajustes rápidos: no cuenta.
        if (FOOTER_POWER_MARKERS.any { s.contains(it) }) return null
        return CLASS_WHITELIST.firstOrNull { hint ->
            s.contains(hint.lowercase())
        }
    }

    private fun looksLikeGenericTitle(title: String): Boolean {
        // Un título que solo dice "Apagar" podría ser el botón del pie, no el diálogo.
        val t = title.trim().lowercase()
        return t in GENERIC_TITLES
    }

    private fun yes(reason: String) = Verdict(true, reason)
    private fun no(reason: String) = Verdict(false, reason)

    /**
     * Paquetes donde suele vivir el menú de apagado. Las entradas
     * nubia/zte/redmagic son hipótesis, no hechos verificados.
     */
    val SYSTEM_PACKAGES = setOf(
        "com.android.systemui",
        "android",
        "com.android.server.policy",
        "com.nubia.systemui",
        "cn.nubia.systemui",
        "com.zte.systemui",
        "cn.nubia.poweroff",
        "com.nubia.poweroff",
    )

    /**
     * Lista blanca de clases (y títulos de ventana) del diálogo GlobalActions.
     * Se evitan palabras sueltas como "PowerOff" o "PowerMenu": en Nubia el
     * pie de Ajustes rápidos usa vistas con esos nombres.
     */
    val CLASS_WHITELIST = listOf(
        "GlobalActionsDialogLite",
        "GlobalActionsDialog",
        "GlobalActionsPopup",
        "MiuiGlobalActions",
        "NubiaGlobalActions",
        "RedMagicGlobalActions",
        "ZteGlobalActions",
        "GlobalActions",
        "ShutdownDialog",
        "ShutdownMenu",
        "ShutdownUi",
        "PowerOffDialog",
        "PowerOffMenu",
        "PowerMenuDialog",
        "PowerDialog",
        "ActionsDialog",
        "NubiaPowerMenu",
        "NubiaPowerOff",
        "RedMagicPowerMenu",
    )

    /** Panel de notificaciones, Ajustes rápidos y piezas del StatusBar. */
    val SHADE_OR_QS_MARKERS = listOf(
        "notificationshade",
        "notification_shade",
        "notificationpanel",
        "notification_panel",
        "notificationshadewindow",
        "notificationstack",
        "quicksettings",
        "quick_settings",
        "qspanel",
        "qs_panel",
        "qscontainer",
        "qs_container",
        "qsfragment",
        "qscustomizer",
        "qsfooter",
        "qs_footer",
        "footeractions",
        "footer_actions",
        "shadeheader",
        "shade_header",
        "shadewindow",
        "expandedpanel",
        "collapsedstatusbar",
        "statusbarwindow",
        "status_bar_window",
        "notificationexpander",
        "volumedialog",
        "fgscontroller",
    )

    /** Botón/ícono de apagado del pie de Ajustes rápidos: no es el diálogo. */
    val FOOTER_POWER_MARKERS = listOf(
        "powerbutton",
        "power_button",
        "powermenubutton",
        "power_menu_button",
        "poweractionbutton",
        "powerfooter",
        "footerpower",
        "shutdownbutton",
        "qspower",
        "qs_power",
    )

    private val GENERIC_TITLES = setOf(
        "apagar", "reiniciar", "emergencia",
        "power off", "shutdown", "restart", "reboot", "emergency",
    )
}
