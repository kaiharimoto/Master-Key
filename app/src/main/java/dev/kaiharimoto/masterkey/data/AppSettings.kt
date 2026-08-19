package dev.kaiharimoto.masterkey.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the engraved score sits relative to the falling-note highway. */
enum class ScorePlacement(val label: String) {
    TOP("Above"),
    LEFT("Left"),
    RIGHT("Right");

    /** Cycles left along Left → Above → Right, stopping at the ends. */
    fun movedLeft(): ScorePlacement = when (this) {
        RIGHT -> TOP
        TOP -> LEFT
        LEFT -> LEFT
    }

    fun movedRight(): ScorePlacement = when (this) {
        LEFT -> TOP
        TOP -> RIGHT
        RIGHT -> RIGHT
    }
}

/**
 * Preferences that belong to the app rather than to a piece.
 *
 * Everything else here is a column on [SongEntity], because it genuinely differs
 * per piece — how fast you practise it, which hand you mute, how much scaffolding
 * you still need. These two do not: where the score sits is about the tablet and
 * how you hold it, and whether you want a count-in is about how you like to
 * start. Storing them per song would mean setting them again for every import.
 *
 * SharedPreferences rather than DataStore: two values, read once at startup and
 * written on a tap. DataStore would add a coroutine-scoped async layer and a
 * dependency for no benefit at this size.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("master-key-settings", Context.MODE_PRIVATE)

    private val _scorePlacement = MutableStateFlow(
        runCatching { ScorePlacement.valueOf(prefs.getString(KEY_PLACEMENT, null) ?: "") }
            .getOrDefault(ScorePlacement.TOP),
    )
    val scorePlacement: StateFlow<ScorePlacement> = _scorePlacement.asStateFlow()

    /**
     * Bars of metronome clicks before the music starts. Off by default: the
     * overwhelmingly common reason to hit play is to hear the next thing
     * immediately, and waiting out a bar of clicks for that is maddening.
     */
    private val _countInEnabled = MutableStateFlow(prefs.getBoolean(KEY_COUNT_IN, false))
    val countInEnabled: StateFlow<Boolean> = _countInEnabled.asStateFlow()

    /**
     * Engraving size, as a percentage handed to Verovio.
     *
     * Not just magnification: the staff is a fixed size in the page's own units,
     * so a larger number also fits fewer bars on a line. It is the trade between
     * "read it comfortably" and "see more of the piece at once", which is a
     * matter of eyesight and how far away the tablet is — not something that can
     * be decided from here.
     */
    private val _scoreZoom = MutableStateFlow(prefs.getInt(KEY_SCORE_ZOOM, DEFAULT_SCORE_ZOOM))
    val scoreZoom: StateFlow<Int> = _scoreZoom.asStateFlow()

    /**
     * White keys to draw, or [KEYBOARD_AUTO] to fit the piece.
     *
     * Fitting the piece is the right default but it makes a two-octave study
     * fill the screen with keys several times the width of real ones. This is
     * the override: ask for more keys and they get narrower and the keyboard
     * shallower, closer to the shape of the instrument.
     */
    private val _keyboardWhiteKeys = MutableStateFlow(prefs.getInt(KEY_KEYBOARD_KEYS, KEYBOARD_AUTO))
    val keyboardWhiteKeys: StateFlow<Int> = _keyboardWhiteKeys.asStateFlow()

    /** Whether the falling-note highway is shown at all. */
    private val _showHighway = MutableStateFlow(prefs.getBoolean(KEY_SHOW_HIGHWAY, true))
    val showHighway: StateFlow<Boolean> = _showHighway.asStateFlow()

    fun setScorePlacement(placement: ScorePlacement) {
        if (_scorePlacement.value == placement) return
        _scorePlacement.value = placement
        prefs.edit().putString(KEY_PLACEMENT, placement.name).apply()
    }

    fun setCountInEnabled(enabled: Boolean) {
        if (_countInEnabled.value == enabled) return
        _countInEnabled.value = enabled
        prefs.edit().putBoolean(KEY_COUNT_IN, enabled).apply()
    }

    fun setScoreZoom(percent: Int) {
        val clamped = percent.coerceIn(MIN_SCORE_ZOOM, MAX_SCORE_ZOOM)
        if (_scoreZoom.value == clamped) return
        _scoreZoom.value = clamped
        prefs.edit().putInt(KEY_SCORE_ZOOM, clamped).apply()
    }

    fun setKeyboardWhiteKeys(keys: Int) {
        val clamped = if (keys == KEYBOARD_AUTO) KEYBOARD_AUTO else keys.coerceIn(MIN_WHITE_KEYS, MAX_WHITE_KEYS)
        if (_keyboardWhiteKeys.value == clamped) return
        _keyboardWhiteKeys.value = clamped
        prefs.edit().putInt(KEY_KEYBOARD_KEYS, clamped).apply()
    }

    fun setShowHighway(show: Boolean) {
        if (_showHighway.value == show) return
        _showHighway.value = show
        prefs.edit().putBoolean(KEY_SHOW_HIGHWAY, show).apply()
    }

    companion object {
        /** Sentinel for "fit the piece", which is what the app did before this existed. */
        const val KEYBOARD_AUTO = 0

        /** About an octave and a half up to the full 88-key span. */
        const val MIN_WHITE_KEYS = 12
        const val MAX_WHITE_KEYS = 52

        const val MIN_SCORE_ZOOM = 45
        const val MAX_SCORE_ZOOM = 130
        const val DEFAULT_SCORE_ZOOM = 70

        private const val KEY_PLACEMENT = "scorePlacement"
        private const val KEY_COUNT_IN = "countInEnabled"
        private const val KEY_SCORE_ZOOM = "scoreZoom"
        private const val KEY_KEYBOARD_KEYS = "keyboardWhiteKeys"
        private const val KEY_SHOW_HIGHWAY = "showHighway"
    }
}
