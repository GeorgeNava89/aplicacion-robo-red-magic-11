package com.george.antirrobo.lightstrip

/**
 * v0.1.5 — Lógica PURA (con pruebas unitarias) para apagar la "Franja de luz".
 *
 * Regla dura: SOLO se apaga si está ENCENDIDA. Si está apagada o el estado
 * es DESCONOCIDO → no se hace nada (jamás se arriesga a encenderla). Nunca se
 * toca para encender. Tras tocar, se vuelve a leer; si sigue encendida NO se
 * vuelve a tocar (solo se registra).
 */
object LightStripLogic {

    enum class State { ON, OFF, UNKNOWN }

    enum class Action { TURN_OFF, NOTHING }

    enum class Outcome { TURNED_OFF, STILL_ON_NO_RETRY, UNVERIFIED, NOT_NEEDED_OFF, SKIPPED_UNKNOWN }

    /** Única decisión permitida: TURN_OFF solo con estado ON confirmado. */
    fun decide(state: State): Action = if (state == State.ON) Action.TURN_OFF else Action.NOTHING

    /** Resultado después del (único) toque. Nunca implica reintentar. */
    fun verify(after: State): Outcome = when (after) {
        State.OFF -> Outcome.TURNED_OFF
        State.ON -> Outcome.STILL_ON_NO_RETRY
        State.UNKNOWN -> Outcome.UNVERIFIED
    }

    /** Resultado cuando no se tocó nada. */
    fun skipped(state: State): Outcome =
        if (state == State.OFF) Outcome.NOT_NEEDED_OFF else Outcome.SKIPPED_UNKNOWN

    // Palabras de estado (es/en). Las de APAGADO se revisan PRIMERO porque
    // "desactivado" contiene "activado" e "inactive" contiene "active".
    private val OFF_WORDS = listOf(
        "desactivad", "apagad", "inactiv", "deshabilitad", "no activ",
        "off", "disabled", "inactive", "not active",
    )
    private val ON_WORDS = listOf(
        "activad", "encendid", "habilitad", "activo", "activa",
        "on", "enabled", "active",
    )

    /**
     * Estado de un mosaico de Ajustes rápidos a partir de lo que expone la
     * accesibilidad. Prioridad: checkable/checked → stateDescription →
     * contentDescription/texto. Si nada es claro → UNKNOWN.
     */
    fun parseTileState(
        checkable: Boolean,
        checked: Boolean,
        stateDescription: CharSequence?,
        contentDescription: CharSequence?,
        text: CharSequence?,
        label: String,
    ): State {
        if (checkable) return if (checked) State.ON else State.OFF
        parseWords(stateDescription?.toString(), null)?.let { return it }
        // En la descripción/texto se quita la etiqueta del mosaico para no confundir
        // (p. ej. una etiqueta que contenga "on").
        parseWords(contentDescription?.toString(), label)?.let { return it }
        parseWords(text?.toString(), label)?.let { return it }
        return State.UNKNOWN
    }

    internal fun parseWords(raw: String?, label: String?): State? {
        if (raw.isNullOrBlank()) return null
        var s = raw.lowercase()
        if (!label.isNullOrBlank()) s = s.replace(label.lowercase(), " ")
        val tokens = s.split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }
        val joined = " " + tokens.joinToString(" ") + " "
        val hasOff = OFF_WORDS.any { w -> matches(joined, w) }
        if (hasOff) return State.OFF
        val hasOn = ON_WORDS.any { w -> matches(joined, w) }
        return if (hasOn) State.ON else null
    }

    private fun matches(joined: String, w: String): Boolean =
        if (w.length <= 3) joined.contains(" $w ") else joined.contains(" $w")

    /** Estado desde una clave de Settings ya identificada (Diagnóstico de luces). */
    fun parseKeyState(value: String?, onValue: String, offValue: String): State = when {
        value == null -> State.UNKNOWN
        offValue.isNotEmpty() && value == offValue -> State.OFF
        onValue.isNotEmpty() && value == onValue -> State.ON
        else -> State.UNKNOWN
    }

    /** Etiquetas configurables, separadas por coma. */
    fun labels(config: String): List<String> =
        config.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    const val DEFAULT_LABELS = "Franja de luz, Light strip, Lightstrip, Tira de luz, Tira LED"
}
