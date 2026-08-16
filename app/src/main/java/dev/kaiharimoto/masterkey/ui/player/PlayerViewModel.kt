package dev.kaiharimoto.masterkey.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.kaiharimoto.masterkey.MasterKeyApp
import dev.kaiharimoto.masterkey.audio.LoopRegion
import dev.kaiharimoto.masterkey.audio.PlaybackEngine
import dev.kaiharimoto.masterkey.core.keyboard.KeyRange
import dev.kaiharimoto.masterkey.core.keyboard.KeyRangeSelector
import dev.kaiharimoto.masterkey.core.keyboard.RangeSection
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.core.model.Piece
import dev.kaiharimoto.masterkey.core.score.ScoreDocument
import dev.kaiharimoto.masterkey.data.SongEntity
import dev.kaiharimoto.masterkey.data.SongRepository
import dev.kaiharimoto.masterkey.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class PlayerUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val song: SongEntity? = null,
    val model: HighwayModel? = null,
    val range: KeyRange = KeyRange.FULL_PIANO,
    val sections: List<RangeSection> = emptyList(),
    val settings: ScaffoldSettings = ScaffoldSettings.DEFAULT,
    val isPlaying: Boolean = false,
    val tempoScale: Float = 0.9f,
    val loop: LoopRegion? = null,
    val rightHandMuted: Boolean = false,
    val leftHandMuted: Boolean = false,
    val metronomeEnabled: Boolean = false,
    val countInBars: Int = 1,
    val countingIn: Boolean = false,
    val showScore: Boolean = true,
    val hasScore: Boolean = false,
    val scoreDocument: ScoreDocument? = null,
    /** Raw MusicXML handed to the engraver. Null when no score is linked. */
    val scoreXml: String? = null,
    val currentBar: Int = 1,
    /** Set while the tempo drill is running; null otherwise. */
    val drillStep: Int? = null,
)

class PlayerViewModel(
    application: Application,
    private val songId: String,
) : AndroidViewModel(application) {

    private val graph = (application as MasterKeyApp).graph
    private val repository: SongRepository = graph.songRepository
    private val engine: PlaybackEngine = graph.playbackEngine

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var piece: Piece = Piece.EMPTY

    /**
     * Read by the highway once per frame, in its draw phase.
     *
     * Deliberately a plain function rather than a StateFlow: routing the playhead
     * through Compose state would recompose the player on every frame. The value
     * comes from the audio clock, so it cannot drift from what is being heard.
     */
    fun positionTicks(): Long = engine.positionTickNow()

    init {
        viewModelScope.launch {
            engine.initialise()
            load()
        }
        viewModelScope.launch {
            engine.state.collect { playback ->
                _state.value = _state.value.copy(
                    isPlaying = playback.isPlaying,
                    tempoScale = playback.tempoScale,
                    loop = playback.loop,
                    rightHandMuted = playback.rightHandMuted,
                    leftHandMuted = playback.leftHandMuted,
                    metronomeEnabled = playback.metronomeEnabled,
                    countInBars = playback.countInBars,
                    countingIn = playback.countingIn,
                    currentBar = _state.value.model?.barNumberAt(playback.positionTick) ?: 1,
                )
            }
        }
        // Driven by transitions, not by every emission. The engine rewrites
        // positionTick every 50 ms, so collecting the whole state here fired
        // ~20 startForegroundService() calls per second while playing.
        viewModelScope.launch {
            engine.state
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { playing ->
                    if (playing) {
                        PlaybackService.start(
                            getApplication(),
                            _state.value.song?.title.orEmpty(),
                        )
                    } else {
                        PlaybackService.stop(getApplication())
                    }
                }
        }
        // Adaptive key range. Checked a few times a second rather than per frame:
        // the range only changes at section boundaries, and the highway animates
        // the transition itself.
        viewModelScope.launch {
            while (true) {
                val sections = _state.value.sections
                if (sections.size > 1) {
                    val target = sections.rangeAt(engine.positionTickNow(), _state.value.range)
                    if (target != _state.value.range) {
                        _state.value = _state.value.copy(range = target)
                    }
                }
                delay(RANGE_POLL_MS)
            }
        }
    }

    private suspend fun load() {
        val song = repository.find(songId) ?: run {
            _state.value = PlayerUiState(loading = false, error = "That song is no longer in your library.")
            return
        }

        val loaded = repository.loadPiece(song).getOrElse { error ->
            _state.value = PlayerUiState(
                loading = false,
                error = error.message ?: "That MIDI file couldn't be read.",
            )
            return
        }

        // When a score is linked it is the better source for two things the MIDI
        // can only guess at: which hand plays a note, and what fingering is
        // written. Both come straight from the notation.
        val score = repository.loadScore(song)
        val scoreXml = if (score != null) repository.scoreXml(song) else null
        piece = if (score != null) enrichWithScore(loaded, score) else loaded

        engine.load(piece)

        val level = runCatching { ScaffoldLevel.valueOf(song.scaffoldLevel) }
            .getOrDefault(ScaffoldLevel.MINIMAL)
        val settings = level.defaults().copy(
            noteNameStyle = runCatching { NoteNameStyle.valueOf(song.noteNameStyle) }
                .getOrDefault(NoteNameStyle.OFF),
            colorMode = runCatching { ColorMode.valueOf(song.colorMode) }
                .getOrDefault(ColorMode.BY_HAND),
            lookAheadBeats = song.lookAheadBeats,
        ).let { base ->
            // A note-name style set explicitly implies wanting them on the highway.
            base.copy(noteNamesOnHighway = base.noteNameStyle != NoteNameStyle.OFF)
        }

        val sections = KeyRangeSelector.adaptiveSections(piece)
        val pinned = if (song.pinnedRangeLow != null && song.pinnedRangeHigh != null) {
            KeyRangeSelector.forPitchesClamped(listOf(song.pinnedRangeLow, song.pinnedRangeHigh))
        } else {
            null
        }

        engine.setTempoScale(song.tempoScale)
        engine.setHandMuted(Hand.RIGHT, song.rightHandMuted)
        engine.setHandMuted(Hand.LEFT, song.leftHandMuted)
        engine.setMetronomeEnabled(song.metronomeEnabled)
        engine.setCountInBars(song.countInBars)

        _state.value = PlayerUiState(
            loading = false,
            song = song,
            model = HighwayModel(piece),
            range = pinned ?: sections.firstOrNull()?.range
                ?: KeyRangeSelector.forPiece(piece),
            sections = if (pinned != null) emptyList() else sections,
            settings = settings,
            tempoScale = song.tempoScale,
            rightHandMuted = song.rightHandMuted,
            leftHandMuted = song.leftHandMuted,
            metronomeEnabled = song.metronomeEnabled,
            countInBars = song.countInBars,
            showScore = song.showScore && song.scoreFileName != null,
            hasScore = song.scoreFileName != null,
            scoreDocument = score,
            scoreXml = scoreXml,
        )

        repository.touch(songId)
    }

    /**
     * Overlays score-derived hand assignment and fingering onto the MIDI notes.
     *
     * Matching is by pitch within a small time window rather than by index: the
     * two files rarely have identical note counts, because ornaments and grace
     * notes are one notehead but several MIDI events. Anything that fails to
     * match simply keeps what the MIDI said.
     */
    private fun enrichWithScore(piece: Piece, score: ScoreDocument): Piece {
        if (score.soundingNotes.isEmpty()) return piece
        if (!score.hasTwoStaves && !score.hasFingering) return piece

        // Map both sides onto a common musical grid so onsets are comparable.
        val ticksPerQuarter = piece.tempoMap.ticksPerQuarter.toDouble()
        val divisions = score.divisions.toDouble().coerceAtLeast(1.0)
        val scale = ticksPerQuarter / divisions

        val byPitch = HashMap<Int, MutableList<Pair<Long, dev.kaiharimoto.masterkey.core.score.ScoreNote>>>()
        for (note in score.soundingNotes) {
            val pitch = note.pitch ?: continue
            byPitch.getOrPut(pitch) { mutableListOf() } += (note.onset * scale).toLong() to note
        }
        byPitch.values.forEach { it.sortBy { entry -> entry.first } }

        val tolerance = (ticksPerQuarter / 2).toLong().coerceAtLeast(1L)

        val enriched = piece.notes.map { note ->
            val candidates = byPitch[note.pitch] ?: return@map note
            val match = candidates.minByOrNull { kotlin.math.abs(it.first - note.startTick) }
                ?.takeIf { kotlin.math.abs(it.first - note.startTick) <= tolerance }
                ?: return@map note

            note.copy(
                hand = if (score.hasTwoStaves) {
                    if (match.second.staff >= 2) Hand.LEFT else Hand.RIGHT
                } else {
                    note.hand
                },
                finger = match.second.finger ?: note.finger,
            )
        }

        return piece.copy(notes = enriched)
    }

    fun togglePlay() {
        if (_state.value.isPlaying) engine.pause() else engine.play()
    }

    fun restart() = engine.stop()

    fun seekTo(tick: Long) = engine.seek(tick)

    fun setTempoScale(scale: Float) {
        engine.setTempoScale(scale)
        persist { it.copy(tempoScale = scale) }
    }

    fun toggleHand(hand: Hand) {
        val muted = when (hand) {
            Hand.RIGHT -> !_state.value.rightHandMuted
            Hand.LEFT -> !_state.value.leftHandMuted
        }
        engine.setHandMuted(hand, muted)
        persist {
            when (hand) {
                Hand.RIGHT -> it.copy(rightHandMuted = muted)
                Hand.LEFT -> it.copy(leftHandMuted = muted)
            }
        }
    }

    fun toggleMetronome() {
        val enabled = !_state.value.metronomeEnabled
        engine.setMetronomeEnabled(enabled)
        persist { it.copy(metronomeEnabled = enabled) }
    }

    fun toggleCountIn() {
        val bars = if (_state.value.countInBars > 0) 0 else 1
        engine.setCountInBars(bars)
        persist { it.copy(countInBars = bars) }
    }

    fun setScaffoldLevel(level: ScaffoldLevel) {
        val current = _state.value.settings
        _state.value = _state.value.copy(
            settings = level.defaults().copy(
                colorMode = current.colorMode,
                lookAheadBeats = current.lookAheadBeats,
            ),
        )
        persist { it.copy(scaffoldLevel = level.name) }
    }

    fun setNoteNameStyle(style: NoteNameStyle) {
        _state.value = _state.value.copy(
            settings = _state.value.settings.copy(
                noteNameStyle = style,
                noteNamesOnHighway = style != NoteNameStyle.OFF,
            ),
        )
        persist { it.copy(noteNameStyle = style.name) }
    }

    fun setColorMode(mode: ColorMode) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(colorMode = mode))
        persist { it.copy(colorMode = mode.name) }
    }

    fun setLookAheadBeats(beats: Float) {
        _state.value = _state.value.copy(
            settings = _state.value.settings.copy(lookAheadBeats = beats),
        )
        persist { it.copy(lookAheadBeats = beats) }
    }

    fun toggleScore() {
        val show = !_state.value.showScore
        _state.value = _state.value.copy(showScore = show)
        persist { it.copy(showScore = show) }
    }

    /** Loops the given inclusive bar range. */
    fun setLoopBars(startBar: Int, endBar: Int) {
        val model = _state.value.model ?: return
        val start = model.tickOfBar(startBar)
        val end = if (endBar >= model.barCount) piece.endTick else model.tickOfBar(endBar + 1)
        if (end <= start) return
        engine.setLoop(LoopRegion(start, end))
        engine.seek(start)
    }

    fun clearLoop() = engine.setLoop(null)

    /** Loops the bar the playhead is currently in — the fastest way to drill. */
    fun loopCurrentBar() {
        val model = _state.value.model ?: return
        val bar = model.barNumberAt(engine.positionTickNow())
        setLoopBars(bar, bar)
    }

    /**
     * Steps the tempo through an alternating fast/slow cycle rather than a
     * monotonic ramp.
     *
     * Allingham & Wollner (2022) found alternating tempi produced more efficient
     * learning of piano passages than the traditional slow-to-fast ladder, and
     * that slow practice alone does not improve fast performance. Same effort,
     * better outcome.
     */
    fun advanceTempoDrill() {
        val step = ((_state.value.drillStep ?: -1) + 1) % DRILL_TEMPI.size
        _state.value = _state.value.copy(drillStep = step)
        setTempoScale(DRILL_TEMPI[step])
    }

    fun stopTempoDrill() {
        _state.value = _state.value.copy(drillStep = null)
    }

    private fun persist(transform: (SongEntity) -> SongEntity) {
        val song = _state.value.song ?: return
        val updated = transform(song)
        _state.value = _state.value.copy(song = updated)
        viewModelScope.launch { repository.update(updated) }
    }

    override fun onCleared() {
        engine.pause()
        PlaybackService.stop(getApplication())
        super.onCleared()
    }

    companion object {
        val DRILL_TEMPI = listOf(0.6f, 0.9f, 0.7f, 1.0f, 0.8f, 1.0f)

        private const val RANGE_POLL_MS = 300L

        fun factory(songId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as Application
                return PlayerViewModel(app, songId) as T
            }
        }
    }
}

/** Range in effect at [tick] when the piece is too wide for one fixed window. */
fun List<RangeSection>.rangeAt(tick: Long, fallback: KeyRange): KeyRange =
    lastOrNull { it.startTick <= tick }?.range ?: firstOrNull()?.range ?: fallback

/** Notes sounding right now, for anything that needs the current chord. */
fun HighwayModel.chordAt(tick: Long): List<Note> =
    ArrayList<Note>(8).also { soundingAt(tick, it) }
