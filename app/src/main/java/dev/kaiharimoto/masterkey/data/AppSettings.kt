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

    private companion object {
        const val KEY_PLACEMENT = "scorePlacement"
        const val KEY_COUNT_IN = "countInEnabled"
    }
}
