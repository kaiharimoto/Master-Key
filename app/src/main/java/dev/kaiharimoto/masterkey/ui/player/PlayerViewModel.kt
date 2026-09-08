package dev.kaiharimoto.masterkey.ui.player

import android.app.Application
import android.util.Log
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
import dev.kaiharimoto.masterkey.data.AppSettings
import dev.kaiharimoto.masterkey.data.ScorePlacement
import dev.kaiharimoto.masterkey.data.SongEntity
import dev.kaiharimoto.masterkey.data.SongRepository
import dev.kaiharimoto.masterkey.playback.PlaybackService
import dev.kaiharimoto.masterkey.ui.score.ScoreEvent
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
    val countInEnabled: Boolean = false,
    val volume: Float = AppSettings.DEFAULT_VOLUME,
    val countingIn: Boolean = false,
    val showScore: Boolean = true,
    val showHighway: Boolean = true,
    val hasScore: Boolean = false,
    val scorePlacement: ScorePlacement = ScorePlacement.TOP,
    val scoreZoom: Int = AppSettings.DEFAULT_SCORE_ZOOM,
    /** White keys to draw, or [AppSettings.KEYBOARD_AUTO] to fit the piece. */
    val keyboardWhiteKeys: Int = AppSettings.KEYBOARD_AUTO,
    val showViewerSettings: Boolean = false,
    val scoreDocument: ScoreDocument? = null,
    /** Raw MusicXML handed to the engraver. Null when it could not be read. */
    val scoreXml: String? = null,
    /** Why there is no [scoreXml] to engrave, when a score is linked but unusable. */
    val scoreError: String? = null,
    val currentBar: Int = 1,
    /** Set while the tempo drill is running; null otherwise. */
    val drillStep: Int? = null,
    /** True while the scrubber is being dragged. */
    val scrubbing: Boolean = false,
    val showShortcuts: Boolean = false,
)

class PlayerViewModel(
    application: Application,
    private val songId: String,
) : AndroidViewModel(application) {

    private val graph = (application as MasterKeyApp).graph
    private val repository: SongRepository = graph.songRepository
    private val engine: PlaybackEngine = graph.playbackEngine
    private val appSettings: AppSettings = graph.settings

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var piece: Piece = Piece.EMPTY

    /**
     * The range the music itself asks for, before the keyboard-size setting.
     *
     * Kept separate so changing the size re-widens from the piece rather than
     * from the last widened result, which would ratchet outwards.
     */
    private var baseRange: KeyRange = KeyRange.FULL_PIANO

    /**
     * Read by the highway once per frame, in its draw phase.
     *
     * Deliberately a plain function rather than a StateFlow: routing the playhead
     * through Compose state would recompose the player on every frame. The value
     * comes from the audio clock, so it cannot drift from what is being heard.
     */
    fun positionTicks(): Long = scrubTick ?: engine.positionTickNow()

    /**
     * Position the scrubber is being dragged to, or null when it is not.
     *
     * Overriding the playhead here rather than seeking on every drag frame is
     * what makes scrubbing cheap: the highway and the score cursor both read
     * [positionTicks], so both follow the finger for free, while the audio engine
     * — which tears down and re-anchors its whole schedule on a seek — is asked
     * exactly once, on release.
     */
    @Volatile private var scrubTick: Long? = null

    /** Whether playback should resume when the current scrub ends. */
    private var resumeAfterScrub = false

    /**
     * Whether the current scrub sounds the notes it passes.
     *
     * Set by the highway, which you drag through the music itself, and not by
     * the seekbar, which is for jumping across the whole piece — a drag that
     * covers four minutes in an inch would be a smear, not a preview.
     */
    private var previewingScrub = false

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
        viewModelScope.launch {
            appSettings.scorePlacement.collect {
                _state.value = _state.value.copy(scorePlacement = it)
            }
        }
        viewModelScope.launch {
            appSettings.scoreZoom.collect { _state.value = _state.value.copy(scoreZoom = it) }
        }
        viewModelScope.launch {
            appSettings.showHighway.collect { _state.value = _state.value.copy(showHighway = it) }
        }
        viewModelScope.launch {
            appSettings.keyboardWhiteKeys.collect {
                _state.value = _state.value.copy(keyboardWhiteKeys = it, range = rangeFor(baseRange, it))
            }
        }
        viewModelScope.launch {
            appSettings.masterVolume.collect { volume ->
                engine.setMasterVolume(volume)
                _state.value = _state.value.copy(volume = volume)
            }
        }
        viewModelScope.launch {
            appSettings.countInEnabled.collect { enabled ->
                engine.setCountInBars(if (enabled) 1 else 0)
                _state.value = _state.value.copy(countInEnabled = enabled)
            }
        }
        // Adaptive key range. Checked a few times a second rather than per frame:
        // the range only changes at section boundaries, and the highway animates
        // the transition itself.
        viewModelScope.launch {
            while (true) {
                val sections = _state.value.sections
                if (sections.size > 1) {
                    val section = sections.rangeAt(positionTicks(), baseRange)
                    val target = rangeFor(section, _state.value.keyboardWhiteKeys)
                    if (target != _state.value.range) {
                        baseRange = section
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

        // Two independent uses of the same file, and they must not be chained.
        //
        // The engraver needs the raw XML and nothing else. Our own parser is only
        // ever a bonus on top — it tells the highway which hand plays a note and
        // what fingering is written. Gating the first on the second is what left
        // a linked score rendering as an unexplained black rectangle: one throw
        // inside a SAX handler and the notation silently never arrived.
        val scoreXml = if (song.scoreFileName != null) repository.scoreXml(song) else null
        val score = repository.loadScore(song)
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

        baseRange = pinned ?: sections.firstOrNull()?.range ?: KeyRangeSelector.forPiece(piece)

        engine.setTempoScale(song.tempoScale)
        engine.setHandMuted(Hand.RIGHT, song.rightHandMuted)
        engine.setHandMuted(Hand.LEFT, song.leftHandMuted)
        engine.setMetronomeEnabled(song.metronomeEnabled)
        engine.setCountInBars(if (appSettings.countInEnabled.value) 1 else 0)
        engine.setMasterVolume(appSettings.masterVolume.value)

        _state.value = PlayerUiState(
            loading = false,
            song = song,
            model = HighwayModel(piece),
            range = rangeFor(baseRange, appSettings.keyboardWhiteKeys.value),
            sections = if (pinned != null) emptyList() else sections,
            settings = settings,
            tempoScale = song.tempoScale,
            rightHandMuted = song.rightHandMuted,
            leftHandMuted = song.leftHandMuted,
            metronomeEnabled = song.metronomeEnabled,
            showScore = song.showScore && song.scoreFileName != null,
            showHighway = appSettings.showHighway.value,
            hasScore = song.scoreFileName != null,
            scorePlacement = appSettings.scorePlacement.value,
            scoreZoom = appSettings.scoreZoom.value,
            keyboardWhiteKeys = appSettings.keyboardWhiteKeys.value,
            countInEnabled = appSettings.countInEnabled.value,
            volume = appSettings.masterVolume.value,
            scoreDocument = score,
            scoreXml = scoreXml?.getOrNull(),
            scoreError = scoreXml?.exceptionOrNull()
                ?.let { describeScoreFailure(song.scoreFileName, it) },
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

    /** Jumps [delta] bars from wherever the playhead is now. */
    fun seekByBars(delta: Int) {
        val model = _state.value.model ?: return
        val bar = (model.barNumberAt(positionTicks()) + delta).coerceIn(1, model.barCount)
        engine.seek(model.tickOfBar(bar))
    }

    fun seekToEnd() = engine.seek(piece.endTick)

    /**
     * Takes the playhead under manual control.
     *
     * Playback stops for the duration. Seeking on every drag frame would ask the
     * engine to discard and rebuild its whole event schedule sixty times a
     * second, which sounds like a machine gun and does no one any good; holding
     * the position here instead lets the highway and the score scroll smoothly
     * under the finger, and the audio picks up wherever it is dropped.
     */
    fun beginScrub() = startScrub(preview = false)

    /** Drag on the highway: tracks the finger *and* sounds what it crosses. */
    fun beginScrubWithPreview() = startScrub(preview = true)

    private fun startScrub(preview: Boolean) {
        resumeAfterScrub = _state.value.isPlaying
        if (resumeAfterScrub) engine.pause()
        val from = engine.positionTickNow()
        scrubTick = from
        previewingScrub = preview
        // Seeded at the current playhead so the first drag update sounds what
        // the finger crosses, not everything from the top of the piece.
        if (preview) engine.beginPreview(from)
        _state.value = _state.value.copy(scrubbing = true)
    }

    fun updateScrub(tick: Long) {
        if (!_state.value.scrubbing) return
        val target = tick.coerceIn(0L, piece.endTick)
        scrubTick = target
        // Hearing the notes go past is most of what makes scrubbing usable for
        // finding a passage — the highway alone tells you where you are, not
        // what it is. Silent when dragging backwards; see previewTo.
        if (previewingScrub) engine.previewTo(target)
    }

    fun endScrub() {
        // Released first and unconditionally: a preview left armed would go on
        // polling for releases forever, and a note held at the moment the finger
        // lifted would never be told to stop.
        if (previewingScrub) {
            previewingScrub = false
            engine.endPreview()
        }
        val target = scrubTick ?: return
        scrubTick = null
        _state.value = _state.value.copy(scrubbing = false)
        engine.seek(target)
        if (resumeAfterScrub) {
            resumeAfterScrub = false
            engine.resume()
        }
    }

    /** Sounds a key tapped on the drawn keyboard. */
    fun strikeKey(pitch: Int) {
        engine.strikeKey(pitch, if (pitch < MIDDLE_C) Hand.LEFT else Hand.RIGHT)
    }

    fun setVolume(volume: Float) = appSettings.setMasterVolume(volume)

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

    fun toggleCountIn() = appSettings.setCountInEnabled(!_state.value.countInEnabled)

    fun setScorePlacement(placement: ScorePlacement) = appSettings.setScorePlacement(placement)

    fun moveScoreLeft() = appSettings.setScorePlacement(_state.value.scorePlacement.movedLeft())

    fun moveScoreRight() = appSettings.setScorePlacement(_state.value.scorePlacement.movedRight())

    /** Applies the keyboard-size setting to a range chosen from the music. */
    private fun rangeFor(range: KeyRange, whiteKeys: Int): KeyRange =
        if (whiteKeys == AppSettings.KEYBOARD_AUTO) range else KeyRangeSelector.widenTo(range, whiteKeys)

    fun setScoreZoom(percent: Int) = appSettings.setScoreZoom(percent)

    fun setKeyboardWhiteKeys(keys: Int) = appSettings.setKeyboardWhiteKeys(keys)

    /**
     * Shows or hides a pane, refusing to hide the last one.
     *
     * An empty player is not a view anyone wants; it just looks broken, which is
     * a thing this screen has done enough of already.
     */
    fun setShowHighway(show: Boolean) {
        if (!show && !(_state.value.showScore && _state.value.hasScore)) return
        appSettings.setShowHighway(show)
    }

    fun setShowScore(show: Boolean) {
        if (!show && !_state.value.showHighway) return
        _state.value = _state.value.copy(showScore = show)
        persist { it.copy(showScore = show) }
    }

    fun toggleViewerSettings() {
        _state.value = _state.value.copy(showViewerSettings = !_state.value.showViewerSettings)
    }

    fun hideViewerSettings() {
        if (_state.value.showViewerSettings) {
            _state.value = _state.value.copy(showViewerSettings = false)
        }
    }

    fun toggleShortcuts() {
        _state.value = _state.value.copy(showShortcuts = !_state.value.showShortcuts)
    }

    fun hideShortcuts() {
        if (_state.value.showShortcuts) _state.value = _state.value.copy(showShortcuts = false)
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

    /**
     * Lifecycle and error reports from the engraver.
     *
     * Worth logging rather than dropping: the pane catches its own exceptions
     * and replaces itself with "this score could not be read", so a broken
     * engraver looks exactly like a missing feature from the outside.
     */
    fun onScoreEvent(event: ScoreEvent) {
        when (event) {
            is ScoreEvent.Failed -> Log.w(TAG, "score pane: ${event.message}")
            is ScoreEvent.Loaded -> Log.i(TAG, "score engraved: ${event.pages} page(s)")
            else -> Unit
        }
    }

    fun toggleScore() = setShowScore(!_state.value.showScore)

    fun toggleHighway() = setShowHighway(!_state.value.showHighway)

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

    /**
     * Turns a read failure into something worth putting on screen.
     *
     * The pane used to show nothing at all when this happened, which is
     * indistinguishable from a feature that was never built — so whatever we say
     * here, it has to name the file and say what went wrong with it.
     */
    private fun describeScoreFailure(fileName: String?, error: Throwable): String {
        val name = fileName ?: "The sheet music file"
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty()
        Log.w(TAG, "couldn't load $name for engraving", error)
        return "$name couldn't be read.\n$reason"
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

        private const val TAG = "MasterKeyPlayer"

        /** Where a tapped key is assumed to change hands, for colour and channel. */
        private const val MIDDLE_C = 60
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
