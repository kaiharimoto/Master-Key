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
import dev.kaiharimoto.masterkey.core.edit.EditCommand
import dev.kaiharimoto.masterkey.core.edit.EditSession
import dev.kaiharimoto.masterkey.core.edit.NoteDraft
import dev.kaiharimoto.masterkey.core.edit.NoteId
import dev.kaiharimoto.masterkey.core.edit.SnapGrid
import dev.kaiharimoto.masterkey.core.keyboard.RangeSection
import dev.kaiharimoto.masterkey.core.midi.MidiWriter
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

    // ---- edit mode ----
    val editing: Boolean = false,
    /** Why this song cannot be edited, when it cannot. */
    val editBlocked: String? = null,
    val snapGrid: SnapGrid = SnapGrid.DEFAULT,
    /**
     * Indices into `model.notes` of the selected notes, ascending.
     *
     * A plain list rather than an `IntArray`: an array in a data class compares
     * by identity, so `equals` would call every rebuilt selection a change and
     * recompose the whole player. The highway turns it into an `IntArray` once
     * per composition for its draw-phase lookups.
     */
    val selectedIndices: List<Int> = emptyList(),
    /** The selected notes themselves, for the inspector. */
    val selectedNotes: List<Note> = emptyList(),
    /** True while the lasso is on and taps add to the selection. */
    val selectMode: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** True when there are edits that have not been written to the file. */
    val editDirty: Boolean = false,
    val hasImportedBackup: Boolean = false,
    /** Set when a save or revert failed, for the toolbar to show. */
    val editError: String? = null,
    /** Shown after a successful save, so a silent write is still visible. */
    val editSaved: Boolean = false,
    val confirmLeavingEdit: Boolean = false,
    /**
     * Ticks the piece has been shifted to make room before its start.
     *
     * Subtracted again when driving the score cursor, which cannot shift with it.
     */
    val startOffsetTicks: Long = 0,
) {
    /** The one selected note, when exactly one is. */
    val selectedNote: Note? get() = selectedNotes.singleOrNull()

    val hasSelection: Boolean get() = selectedNotes.isNotEmpty()
}

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
    fun positionTicks(): Long = editViewTick ?: scrubTick ?: engine.positionTickNow()

    /**
     * Where the view is looking while editing, which may be before the piece starts.
     *
     * Edit mode can scroll into the empty space in front of bar 1, so that space
     * can be used — otherwise choosing where a piece begins is a chicken-and-egg
     * problem, since you cannot reach the room you need in order to make it. The
     * engine is never told about a negative tick; playback is paused throughout,
     * and a note dropped out here rebases the piece so the model never holds one.
     */
    @Volatile private var editViewTick: Long? = null

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
                // Frozen while editing. The highway animates a range change by
                // sliding the keyboard sideways over 550 ms; if that happens
                // mid-drag the lanes move out from under the finger and the note
                // lands on a pitch nobody aimed at.
                if (sections.size > 1 && !_state.value.editing) {
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
        piece = if (score != null) enrichWithScore(loaded, score, song.startOffsetTicks) else loaded

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
            startOffsetTicks = song.startOffsetTicks,
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
    private fun enrichWithScore(
        piece: Piece,
        score: ScoreDocument,
        offsetTicks: Long = 0L,
    ): Piece {
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
            // A hand the user set by hand is not up for revision. Without this
            // the score would silently overrule the choice on every load — and
            // only for notes it happened to match, leaving the piece
            // half-reverted, which reads as a bug rather than as a policy.
            if (note.handPinned) return@map note
            val candidates = byPitch[note.pitch] ?: return@map note
            // Compare on the score's own timeline. A piece that has been shifted
            // to make room at the front would otherwise miss *every* match, and
            // lose its hand and fingering overlay without saying anything.
            val onset = note.startTick - offsetTicks
            val match = candidates.minByOrNull { kotlin.math.abs(it.first - onset) }
                ?.takeIf { kotlin.math.abs(it.first - onset) <= tolerance }
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
        if (_state.value.editing && tick < 0L) {
            // Free to go before the start, but only as far as there is pre-roll
            // drawn — scrolling into unbounded blank space is just being lost.
            editViewTick = tick.coerceAtLeast(-preRollTicks())
            return
        }
        // Back inside the piece: hand the view to the ordinary playhead again.
        editViewTick = null
        val target = tick.coerceIn(0L, piece.endTick)
        scrubTick = target
        // Hearing the notes go past is most of what makes scrubbing usable for
        // finding a passage — the highway alone tells you where you are, not
        // what it is. Silent when dragging backwards; see previewTo.
        if (previewingScrub) engine.previewTo(target)
    }

    /** How far before bar 1 the view may scroll: a few bars of room. */
    fun preRollTicks(): Long {
        val signature = piece.timeSignatureAt(0)
        val perBar = signature.ticksPerBar(ticksPerQuarter).coerceAtLeast(1L)
        return perBar * PRE_ROLL_BARS
    }

    fun endScrub() {
        // Released first and unconditionally: a preview left armed would go on
        // polling for releases forever, and a note held at the moment the finger
        // lifted would never be told to stop.
        if (previewingScrub) {
            previewingScrub = false
            engine.endPreview()
        }
        // Left out in the pre-roll: the view stays there, and the transport stays
        // where it was. There is nothing before bar 1 to seek to yet.
        if (editViewTick != null) {
            scrubTick = null
            _state.value = _state.value.copy(scrubbing = false)
            return
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

    /**
     * Sounds a pitch while a note is being dragged.
     *
     * Re-triggers rather than refusing when the pitch is already ringing, which
     * is the opposite of what a tapped key wants — see
     * [PlaybackEngine.restrikeKey].
     */
    fun auditionPitch(pitch: Int) {
        engine.restrikeKey(pitch, handAt(pitch))
    }

    /** Which hand a pitch belongs to, from the piece's own split where there is one. */
    private fun handAt(pitch: Int): Hand =
        session?.handFor(pitch) ?: if (pitch < MIDDLE_C) Hand.LEFT else Hand.RIGHT

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

    // ---- edit mode ------------------------------------------------------------

    /**
     * The editing session, alive only while edit mode is on.
     *
     * Deliberately not in [PlayerUiState]: nothing composes the undo stack, and
     * putting a mutable object in an immutable state class invites someone to
     * assume a copy is a snapshot. Only its derived booleans go into the state.
     */
    private var session: EditSession? = null

    /**
     * The selection, in the order notes were added to it.
     *
     * Ids rather than indices: an index is only meaningful against one version
     * of the note list, and every edit rebuilds it.
     */
    private val selectedIds = LinkedHashSet<NoteId>()

    fun toggleEditMode() {
        if (_state.value.editing) requestLeaveEdit() else enterEditMode()
    }

    private fun enterEditMode() {
        val song = _state.value.song ?: return
        // Refuse before the work rather than after it: a piece whose ticks are
        // absolute time cannot be written back as a normal MIDI division, so
        // letting someone edit it would mean failing at the save.
        if (!MidiWriter.canWrite(piece)) {
            _state.value = _state.value.copy(
                editBlocked = "This song uses SMPTE timing, which Master Key can't write back yet.",
            )
            return
        }

        // Editing notes as they stream past is not editing, it is whack-a-mole.
        engine.pause()

        startSessionOn(piece)
        selectedIds.clear()
        _state.value = _state.value.copy(
            editing = true,
            selectMode = false,
            model = HighwayModel(piece),
            editBlocked = null,
            editError = null,
            editSaved = false,
            selectedIndices = emptyList(),
            selectedNotes = emptyList(),
            canUndo = false,
            canRedo = false,
            editDirty = false,
            hasImportedBackup = repository.hasImportedBackup(song),
            startOffsetTicks = song.startOffsetTicks,
        )
    }

    fun dismissEditBlocked() {
        if (_state.value.editBlocked != null) {
            _state.value = _state.value.copy(editBlocked = null)
        }
    }

    /** Leaves edit mode, asking first when there is unsaved work. */
    fun requestLeaveEdit() {
        if (_state.value.editDirty) {
            _state.value = _state.value.copy(confirmLeavingEdit = true)
        } else {
            leaveEditMode()
        }
    }

    fun cancelLeavingEdit() {
        _state.value = _state.value.copy(confirmLeavingEdit = false)
    }

    private fun leaveEditMode() {
        editViewTick = null
        session = null
        selectedIds.clear()
        _state.value = _state.value.copy(
            editing = false,
            confirmLeavingEdit = false,
            // The lasso is a mode, and a mode left on across sessions is one you
            // will be surprised by next time rather than helped by.
            selectMode = false,
            selectedIndices = emptyList(),
            selectedNotes = emptyList(),
            canUndo = false,
            canRedo = false,
            editDirty = false,
        )
    }

    fun setSnapGrid(grid: SnapGrid) {
        _state.value = _state.value.copy(snapGrid = grid)
    }

    /** Where the grid starts at [tick], which a change of metre moves. */
    fun snapAnchor(tick: Long): Long = SnapGrid.anchorFor(piece, tick)

    val ticksPerQuarter: Int get() = piece.tempoMap.ticksPerQuarter

    /**
     * Handles a tap on the note at [index], or on empty space when it is -1.
     *
     * In select mode a tap toggles membership, so a passage can be built up note
     * by note; otherwise it replaces the selection, which is what you want when
     * you are working on one note at a time.
     */
    fun select(index: Int) {
        val current = session ?: return
        val id = if (index >= 0) current.notes.idAt(index) else null

        if (id == null) {
            selectedIds.clear()
        } else if (_state.value.selectMode) {
            if (!selectedIds.remove(id)) selectedIds += id
        } else {
            selectedIds.clear()
            selectedIds += id
        }

        publishSelection()
        // Only audition a note being added, never one being taken away.
        if (id != null && id in selectedIds) {
            current[id]?.let { engine.strikeKey(it.pitch, it.hand) }
        }
    }

    /** Selects everything the marquee touched, replacing or extending as the mode says. */
    fun selectRange(indices: List<Int>) {
        val current = session ?: return
        if (!_state.value.selectMode) selectedIds.clear()
        indices.forEach { index -> current.notes.idAt(index)?.let { selectedIds += it } }
        publishSelection()
    }

    fun setSelectMode(on: Boolean) {
        _state.value = _state.value.copy(selectMode = on)
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        publishSelection()
    }

    /** Recomputes the indices and notes the UI reads, from the ids that are the truth. */
    private fun publishSelection() {
        val current = session
        if (current == null) {
            _state.value = _state.value.copy(selectedIndices = emptyList(), selectedNotes = emptyList())
            return
        }
        // Drop ids whose notes have since gone, so a stale selection can never
        // leave a control enabled that would then silently do nothing.
        selectedIds.retainAll { current[it] != null }
        val indices = selectedIds.map { current.notes.indexOf(it) }.filter { it >= 0 }.sorted()
        _state.value = _state.value.copy(
            selectedIndices = indices,
            selectedNotes = indices.mapNotNull { current.notes.noteAtIndex(it) },
        )
    }

    /** A new note takes the loudness and the hand the rest of the piece implies. */
    fun draftForNewNote(pitch: Int, startTick: Long, lengthTicks: Long): NoteDraft? =
        session?.newNote(pitch, startTick, lengthTicks)

    fun noteDraftAt(index: Int): NoteDraft? =
        session?.notes?.idAt(index)?.let { id -> session?.get(id) }?.let { NoteDraft.of(it) }

    /** Commits a finished drag on the note at [index]. */
    fun commitDrag(index: Int, draft: NoteDraft, minLengthTicks: Long) {
        val current = session ?: return
        val id = current.notes.idAt(index) ?: return
        applyEdit(EditCommand.Replace(id, draft.toNote(minLengthTicks)))
    }

    /** Commits a drag that moved the whole selection by one offset. */
    fun commitGroupDrag(deltaTicks: Long, deltaPitch: Int) {
        if (deltaTicks == 0L && deltaPitch == 0) return
        nudgeSelected { draft ->
            draft.copy(
                pitch = draft.pitch + deltaPitch,
                startTick = draft.startTick + deltaTicks,
                endTick = draft.endTick + deltaTicks,
            )
        }
    }

    fun insertNote(draft: NoteDraft, minLengthTicks: Long) {
        val current = session ?: return
        // A note dropped in the pre-roll is what turns that empty space into
        // real bars: the piece shifts later so the note lands at or after zero,
        // and the shift and the insert undo together as one step.
        if (draft.startTick < 0L) {
            val perBar = piece.timeSignatureAt(0)
                .ticksPerBar(ticksPerQuarter)
                .coerceAtLeast(1L)
            val bars = (-draft.startTick + perBar - 1) / perBar
            val shift = bars * perBar
            val moved = draft.copy(
                startTick = draft.startTick + shift,
                endTick = draft.endTick + shift,
            )
            applyEdit(
                EditCommand.Batch(
                    listOf(
                        EditCommand.Rebase(shift),
                        EditCommand.Insert(moved.toNote(minLengthTicks)),
                    ),
                ),
            )
            // The view was looking at empty space that is now bar 1.
            editViewTick = null
            _state.value = _state.value.copy(startOffsetTicks = current.offsetTicks)
            return
        }
        applyEdit(EditCommand.Insert(draft.toNote(minLengthTicks)))
    }

    fun deleteSelected() {
        if (selectedIds.isEmpty()) return
        applyEdit(
            EditCommand.Batch(selectedIds.map { EditCommand.Delete(it) }),
            keepSelection = false,
        )
    }

    fun nudgePitch(semitones: Int) = nudgeSelected { draft ->
        draft.copy(pitch = draft.pitch + semitones)
    }

    /** Moves the selection a whole octave, the interval worth its own button. */
    fun transposeOctaves(octaves: Int) = nudgePitch(octaves * 12)

    fun nudgeStart(steps: Int) = nudgeSelected { draft ->
        val step = gridStep()
        draft.copy(
            startTick = draft.startTick + steps * step,
            endTick = draft.endTick + steps * step,
        )
    }

    fun nudgeLength(steps: Int) = nudgeSelected { draft ->
        draft.copy(endTick = draft.endTick + steps * gridStep())
    }

    /** Puts every selected note on [hand], and pins it against the sheet music. */
    fun setSelectedHand(hand: Hand) = nudgeSelected { draft ->
        draft.copy(hand = hand, handPinned = true)
    }

    private fun gridStep(): Long {
        val grid = _state.value.snapGrid
        return if (grid.isFree) 1L else grid.unitTicks(ticksPerQuarter)
    }

    /**
     * Applies [transform] to every selected note as a single undoable step.
     *
     * One press of Undo has to take back one press of a button, however many
     * notes that button moved — hence the batch rather than a loop of commands.
     */
    private fun nudgeSelected(transform: (NoteDraft) -> NoteDraft) {
        val current = session ?: return
        if (selectedIds.isEmpty()) return
        val minLength = if (_state.value.snapGrid.isFree) 1L else gridStep()
        val commands = selectedIds.mapNotNull { id ->
            current[id]?.let { note ->
                EditCommand.Replace(id, transform(NoteDraft.of(note)).toNote(minLength))
            }
        }
        if (commands.isEmpty()) return
        applyEdit(EditCommand.Batch(commands))
    }

    fun undo() {
        val ids = session?.undo().orEmpty()
        if (ids.isEmpty()) return
        selectedIds.clear()
        selectedIds += ids
        afterEdit()
    }

    fun redo() {
        val ids = session?.redo().orEmpty()
        if (ids.isEmpty()) return
        selectedIds.clear()
        selectedIds += ids
        afterEdit()
    }

    /**
     * Starts an editing session over [loaded] and adopts its note ordering.
     *
     * Adopting matters, and forgetting it is a silent bug rather than a loud one.
     * [EditNotes] sorts by `(startTick, pitch)`; the MusicXML path sorts by start
     * tick alone, so a score-derived song can arrive with a chord's notes in a
     * different order from the session's. The highway hit-tests against
     * `model.notes` and the session resolves that index against its own list, so
     * a disagreement means tapping one note of a chord selects a different one —
     * and deleting it then removes something off-screen, which looks exactly like
     * nothing happening. Every path that builds a session goes through here.
     */
    private fun startSessionOn(loaded: Piece) {
        val started = EditSession(loaded)
        session = started
        piece = started.toPiece()
        viewModelScope.launch { engine.updatePiece(piece) }
    }

    private fun applyEdit(command: EditCommand, keepSelection: Boolean = true) {
        val current = session ?: return
        val ids = current.apply(command)
        if (ids.isEmpty()) {
            // The command named notes that are no longer there. Saying nothing
            // makes a live control look dead, which is how this surfaced the
            // first time; drop the stale selection so the button at least
            // disables itself.
            Log.w(TAG, "edit skipped: ${command::class.simpleName} referred to missing notes")
            selectedIds.clear()
            afterEdit()
            return
        }
        selectedIds.clear()
        if (keepSelection) selectedIds += ids
        afterEdit()
    }

    /**
     * Rebuilds everything downstream of an edit, exactly once.
     *
     * Rebuilding [HighwayModel] walks the whole bar grid, so this is a
     * per-gesture cost, never a per-frame one — which is why drags are drawn as
     * a ghost and only committed when the finger lifts.
     */
    private fun afterEdit() {
        val current = session ?: return
        piece = current.toPiece()

        _state.value = _state.value.copy(
            model = HighwayModel(piece),
            canUndo = current.canUndo,
            canRedo = current.canRedo,
            editDirty = current.isDirty,
            editSaved = false,
            editError = null,
        )
        publishSelection()

        viewModelScope.launch { engine.updatePiece(piece) }
    }

    fun saveEdits() {
        val song = _state.value.song ?: return
        val current = session ?: return
        viewModelScope.launch {
            repository.saveEditedPiece(
                song.copy(startOffsetTicks = song.startOffsetTicks + current.offsetTicks),
                current.toPiece(),
            )
                .onSuccess { saved ->
                    // A fresh session over the saved piece: the file on disk is
                    // now the baseline, so "unsaved changes" must start empty
                    // again and undo must not step back across the save.
                    session = EditSession(piece)
                    selectedIds.clear()
                    _state.value = _state.value.copy(
                        song = saved,
                        hasScore = saved.scoreFileName != null,
                        editDirty = false,
                        canUndo = false,
                        canRedo = false,
                        selectedIndices = emptyList(),
                        selectedNotes = emptyList(),
                        hasImportedBackup = repository.hasImportedBackup(saved),
                        editSaved = true,
                        editError = null,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        editError = error.message ?: "Those edits couldn't be saved.",
                    )
                }
        }
    }

    /** Throws away every edit since the last save and leaves edit mode. */
    fun discardEdits() {
        val song = _state.value.song ?: return
        viewModelScope.launch {
            repository.loadPiece(song)
                .onSuccess { loaded ->
                    val score = repository.loadScore(song)
                    piece = if (score != null) {
                        enrichWithScore(loaded, score, song.startOffsetTicks)
                    } else {
                        loaded
                    }
                    engine.updatePiece(piece)
                    _state.value = _state.value.copy(model = HighwayModel(piece))
                    leaveEditMode()
                }
                .onFailure { leaveEditMode() }
        }
    }

    /** Puts the imported file back, undoing every edit ever saved. */
    fun revertToImported() {
        val song = _state.value.song ?: return
        viewModelScope.launch {
            repository.revertToImported(song)
                .onSuccess { restored ->
                    val loaded = repository.loadPiece(restored).getOrNull() ?: return@onSuccess
                    val score = repository.loadScore(restored)
                    startSessionOn(if (score != null) enrichWithScore(loaded, score, restored.startOffsetTicks) else loaded)
                    selectedIds.clear()
                    _state.value = _state.value.copy(
                        song = restored,
                        model = HighwayModel(piece),
                        selectedIndices = emptyList(),
                        selectedNotes = emptyList(),
                        canUndo = false,
                        canRedo = false,
                        editDirty = false,
                        hasImportedBackup = repository.hasImportedBackup(restored),
                        editSaved = false,
                        editError = null,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        editError = error.message ?: "Couldn't go back to the imported version.",
                    )
                }
        }
    }

    fun dismissEditMessage() {
        if (_state.value.editError != null || _state.value.editSaved) {
            _state.value = _state.value.copy(editError = null, editSaved = false)
        }
    }

    /** Saves, then leaves edit mode — the answer to the unsaved-changes prompt. */
    fun saveAndLeaveEdit() {
        val song = _state.value.song ?: return
        val current = session ?: return
        _state.value = _state.value.copy(confirmLeavingEdit = false)
        viewModelScope.launch {
            repository.saveEditedPiece(
                song.copy(startOffsetTicks = song.startOffsetTicks + current.offsetTicks),
                current.toPiece(),
            )
                .onSuccess { saved ->
                    _state.value = _state.value.copy(song = saved, editSaved = true)
                    leaveEditMode()
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        editError = error.message ?: "Those edits couldn't be saved.",
                    )
                }
        }
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

        /**
         * Bars of empty space the view may scroll into before bar 1.
         *
         * Enough to write a pickup or a couple of introductory bars into; the
         * limit exists so that scrolling up does not run on forever into blank
         * space with no way to tell how far you have gone.
         */
        private const val PRE_ROLL_BARS = 4L

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
