package dev.kaiharimoto.masterkey.ui.player

import androidx.compose.ui.graphics.Color
import dev.kaiharimoto.masterkey.core.midi.Pitch
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.ui.theme.HandColors

/** How note names are spelled, for people who learned different systems. */
enum class NoteNameStyle {
    OFF,
    LETTER,   // C D E F G A B
    GERMAN,   // C D E F G A H
    SOLFEGE;  // Do Re Mi ...

    fun format(pitch: Int): String = when (this) {
        OFF -> ""
        LETTER -> Pitch.name(pitch)
        GERMAN -> Pitch.germanName(pitch)
        SOLFEGE -> Pitch.solfege(pitch)
    }
}

/** Colour by which hand plays the note, or by pitch class (Boomwhacker style). */
enum class ColorMode { BY_HAND, BY_PITCH_CLASS }

/**
 * One dial for all the reading aids.
 *
 * The literature is consistent that annotated notation helps beginners, but only
 * when it is an explicit *transitional* scaffold — leave it on permanently and it
 * becomes a crutch that transfer to plain notation suffers for. So the levels are
 * arranged as a visible path from Max to Off rather than as a pile of unrelated
 * toggles, and note names can fade automatically as accuracy improves.
 */
enum class ScaffoldLevel(val label: String, val description: String) {
    MAX("Max", "Everything on — note names, fingering, landmarks and guides"),
    GUIDED("Guided", "Names on ledger lines, fingering, landmark guides"),
    MINIMAL("Minimal", "Fingering and the current-measure highlight"),
    OFF("Off", "Plain notation, no annotations");

    fun defaults(): ScaffoldSettings = when (this) {
        MAX -> ScaffoldSettings(
            level = this,
            noteNameStyle = NoteNameStyle.LETTER,
            noteNamesOnHighway = true,
            showFingering = true,
            showLandmarks = true,
            showIntervalArrows = true,
            showLedgerHelper = true,
            showBeatGrid = true,
            showOctaveLabels = true,
            highlightCurrentMeasure = true,
            showKeyboardMiniMap = true,
        )

        GUIDED -> ScaffoldSettings(
            level = this,
            noteNameStyle = NoteNameStyle.LETTER,
            noteNamesOnHighway = true,
            showFingering = true,
            showLandmarks = true,
            showIntervalArrows = false,
            showLedgerHelper = true,
            showBeatGrid = true,
            showOctaveLabels = true,
            highlightCurrentMeasure = true,
            showKeyboardMiniMap = true,
        )

        MINIMAL -> ScaffoldSettings(
            level = this,
            noteNameStyle = NoteNameStyle.OFF,
            noteNamesOnHighway = false,
            showFingering = true,
            showLandmarks = false,
            showIntervalArrows = false,
            showLedgerHelper = false,
            showBeatGrid = true,
            showOctaveLabels = true,
            highlightCurrentMeasure = true,
            showKeyboardMiniMap = false,
        )

        OFF -> ScaffoldSettings(
            level = this,
            noteNameStyle = NoteNameStyle.OFF,
            noteNamesOnHighway = false,
            showFingering = false,
            showLandmarks = false,
            showIntervalArrows = false,
            showLedgerHelper = false,
            showBeatGrid = true,
            showOctaveLabels = false,
            highlightCurrentMeasure = false,
            showKeyboardMiniMap = false,
        )
    }
}

data class ScaffoldSettings(
    val level: ScaffoldLevel = ScaffoldLevel.MINIMAL,
    val noteNameStyle: NoteNameStyle = NoteNameStyle.OFF,
    val noteNamesOnHighway: Boolean = false,
    val showFingering: Boolean = true,
    val showLandmarks: Boolean = false,
    val showIntervalArrows: Boolean = false,
    val showLedgerHelper: Boolean = false,
    val showBeatGrid: Boolean = true,
    val showOctaveLabels: Boolean = true,
    val highlightCurrentMeasure: Boolean = true,
    val showKeyboardMiniMap: Boolean = false,
    val colorMode: ColorMode = ColorMode.BY_HAND,
    /** Musical distance visible above the key line, in beats. */
    val lookAheadBeats: Float = 8f,
) {
    companion object {
        /** Defaults for an intermediate player: fingering on, note names off. */
        val DEFAULT = ScaffoldLevel.MINIMAL.defaults()
    }
}

/**
 * The shared colour language.
 *
 * Pitch-class colours are OpenSheetMusicDisplay's built-in Boomwhacker-style set,
 * which is tuned to read against both light and dark backgrounds. They are offered
 * as an alternative to hand colouring, never as the default — for a two-hand
 * instrument, "which hand" is more useful than "which letter", and it is also
 * what Synthesia does.
 */
object NoteColors {

    private val pitchClass = listOf(
        Color(0xFFD82C6B), // C
        Color(0xFFE85B8A), // C#
        Color(0xFFF89D15), // D
        Color(0xFFF7B84B), // D#
        Color(0xFFFFE21A), // E
        Color(0xFF4DBD5C), // F
        Color(0xFF6FCF7A), // F#
        Color(0xFF009D96), // G
        Color(0xFF2AB3AC), // G#
        Color(0xFF43469D), // A
        Color(0xFF6366B5), // A#
        Color(0xFF76429C), // B
    )

    fun base(pitch: Int, hand: Hand, mode: ColorMode): Color = when (mode) {
        ColorMode.BY_HAND -> if (hand == Hand.RIGHT) HandColors.amber else HandColors.teal
        ColorMode.BY_PITCH_CLASS -> pitchClass[Math.floorMod(pitch, 12)]
    }

    fun bright(pitch: Int, hand: Hand, mode: ColorMode): Color = when (mode) {
        ColorMode.BY_HAND -> if (hand == Hand.RIGHT) HandColors.amberBright else HandColors.tealBright
        ColorMode.BY_PITCH_CLASS -> base(pitch, hand, mode).lighten(0.35f)
    }

    /** Black-key notes are darkened so hand identity survives the shade change. */
    fun forNote(pitch: Int, hand: Hand, mode: ColorMode, sounding: Boolean): Color {
        val color = if (sounding) bright(pitch, hand, mode) else base(pitch, hand, mode)
        return if (Pitch.isBlack(pitch)) color.darken(0.22f) else color
    }

    private fun Color.lighten(amount: Float) = Color(
        red = red + (1f - red) * amount,
        green = green + (1f - green) * amount,
        blue = blue + (1f - blue) * amount,
        alpha = alpha,
    )

    private fun Color.darken(amount: Float) = Color(
        red = red * (1f - amount),
        green = green * (1f - amount),
        blue = blue * (1f - amount),
        alpha = alpha,
    )
}
