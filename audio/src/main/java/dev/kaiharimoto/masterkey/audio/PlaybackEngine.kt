package dev.kaiharimoto.masterkey.audio

import android.content.Context
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.core.model.Piece
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.roundToLong

/** Loop region in musical time, so it survives tempo changes unchanged. */
data class LoopRegion(val startTick: Long, val endTick: Long)

data class PlaybackState(
    val isPlaying: Boolean = false,
    val positionTick: Long = 0L,
    val tempoScale: Float = 1.0f,
    val loop: LoopRegion? = null,
    val rightHandMuted: Boolean = false,
    val leftHandMuted: Boolean = false,
    val metronomeEnabled: Boolean = false,
    /** Bars of clicks before the music starts. 0 disables it. */
    val countInBars: Int = 1,
    /** True while the count-in is running and no notes have sounded yet. */
    val countingIn: Boolean = false,
    val isReady: Boolean = false,
)

/**
 * Turns a [Piece] into scheduled synth events and owns the transport.
 *
 * The important design point: **the audio callback is the clock master**. This
 * class never runs its own timer for position — it asks the native engine how
 * many frames it has rendered. A UI-side timer would drift against the audio and
 * the falling notes would gradually stop matching what you hear.
 *
 * Tempo scaling costs nothing here. Because notes are dispatched as events
 * rather than played back as audio, halving the speed just means placing the
 * same events twice as far apart — no time-stretching, so no pitch change and no
 * artefacts.
 */
class PlaybackEngine(private val context: Context) {

    private val synth = NativeSynth()

    // The native ring buffer is single-producer, so every scheduling call is
    // funnelled onto this one thread.
    private val engineThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MasterKey-Engine").apply { priority = Thread.NORM_PRIORITY + 2 }
    }
    private val engineDispatcher = engineThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(engineDispatcher)

    private var schedulerJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var piece: Piece = Piece.EMPTY
    private var sampleRate = 48_000

    /** Index of the next note to schedule; reset on seek. */
    private var nextNoteIndex = 0

    /** Tick of the next metronome click; reset on seek. */
    private var nextMetronomeTick = 0L

    /**
     * Longest note in the piece.
     *
     * [notes] is sorted by *start* tick, so finding what is still sounding at a
     * given moment means looking back at least this far.
     */
    private var maxNoteDurationTicks = 0L

    /**
     * Frame at which the current tick-to-frame mapping was anchored, and the tick
     * it corresponds to. Re-anchoring on every tempo change keeps the conversion
     * a single multiply instead of an integral over the tempo scale history.
     */
    private var anchorFrame = 0L
    private var anchorTick = 0L
    private var tempoScale = 1.0f

    /** Frame at which the music actually begins; earlier frames are count-in. */
    private var countInUntilFrame = 0L

    suspend fun initialise(soundFontAsset: String = DEFAULT_SOUND_FONT): Boolean =
        withContext(engineDispatcher) {
            if (synth.isCreated) return@withContext true
            val bytes = runCatching {
                context.assets.open(soundFontAsset).use { it.readBytes() }
            }.getOrElse { return@withContext false }

            val ok = synth.create(bytes, sampleRate)
            if (ok) {
                sampleRate = synth.sampleRate().takeIf { it > 0 } ?: sampleRate
                _state.value = _state.value.copy(isReady = true)
            }
            ok
        }

    suspend fun load(piece: Piece) = withContext(engineDispatcher) {
        this@PlaybackEngine.piece = piece
        maxNoteDurationTicks = piece.notes.maxOfOrNull { it.durationTicks } ?: 0L
        stopInternal()
        _state.value = _state.value.copy(positionTick = 0L)
        seekInternal(0L)
    }

    fun play() {
        scope.launch {
            if (!synth.isCreated || piece.notes.isEmpty()) return@launch
            startPlaying(withCountIn = true)
        }
    }

    /**
     * Starts playing without a count-in.
     *
     * For picking up where something left off — after scrubbing, say. A count-in
     * answers "get me ready to play from here", which is a question you asked
     * when you pressed play, not one you asked by letting go of the scrubber.
     */
    fun resume() {
        scope.launch {
            if (!synth.isCreated || piece.notes.isEmpty()) return@launch
            startPlaying(withCountIn = false)
        }
    }

    /**
     * Starts the transport, optionally after a bar of clicks.
     *
     * The count-in is implemented by anchoring the tick-to-frame mapping ahead of
     * the current frame. Every note then lands that much later automatically, and
     * the gap is filled with metronome clicks — no separate scheduling path, and
     * nothing to keep in sync with the music that follows.
     */
    private fun startPlaying(withCountIn: Boolean) {
        val tick = _state.value.positionTick.coerceIn(0L, piece.endTick)
        val bars = if (withCountIn) _state.value.countInBars else 0
        val countInFrames = if (bars > 0) countInFrames(bars, tick) else 0L

        // Always re-seek, even when resuming from a pause at the same position.
        // The seek bumps the native event generation, which discards whatever
        // was queued for the old anchor — otherwise the notes scheduled in the
        // last 400 ms before the pause would all fire the moment play resumed.
        val base = frameForTickAbsolute(tick)
        synth.seek(base)
        resetCursors(tick)

        anchorTick = tick
        anchorFrame = base + countInFrames
        countInUntilFrame = anchorFrame

        applyHandVolumes(base)
        if (countInFrames > 0) scheduleCountIn(bars, base)
        scheduleNotesAlreadySounding(tick)

        synth.setPlaying(true)
        _state.value = _state.value.copy(isPlaying = true, countingIn = countInFrames > 0)
        startScheduler()
    }

    private fun countInFrames(bars: Int, startTick: Long): Long {
        val signature = piece.timeSignatureAt(startTick)
        val ticksPerBar = signature.ticksPerBar(piece.tempoMap.ticksPerQuarter).coerceAtLeast(1L)
        val micros = piece.tempoMap.tickToMicros(startTick + ticksPerBar * bars) -
            piece.tempoMap.tickToMicros(startTick)
        return ((micros / tempoScale) * sampleRate / 1_000_000.0).roundToLong()
    }

    /**
     * Sounds the notes that are already ringing at [tick].
     *
     * [nextNoteIndex] points at the first note that *begins* at or after the
     * playhead, so starting inside a held chord — which is what looping a bar or
     * scrubbing into the middle of a phrase does — would otherwise be silent
     * until the next attack. These are struck at the anchor and released where
     * they were written to end.
     */
    private fun scheduleNotesAlreadySounding(tick: Long) {
        if (tick <= 0L) return
        val limit = _state.value.loop?.endTick ?: piece.endTick
        var index = firstNoteIndexAtOrAfter(tick - maxNoteDurationTicks)
        while (index < piece.notes.size) {
            val note = piece.notes[index]
            index++
            if (note.startTick >= tick) break
            val endTick = note.endTick.coerceAtMost(limit)
            if (endTick <= tick) continue
            scheduleNote(note, onFrame = anchorFrame, endTick = endTick)
        }
    }

    private fun scheduleCountIn(bars: Int, fromFrame: Long) {
        val signature = piece.timeSignatureAt(anchorTick)
        val beats = signature.numerator * bars
        if (beats <= 0) return
        val span = anchorFrame - fromFrame
        val perBeat = span / beats
        for (beat in 0 until beats) {
            synth.scheduleMetronome(
                frame = fromFrame + perBeat * beat,
                accent = beat % signature.numerator == 0,
                // A count-in has to be audible over a real piano, so it is
                // deliberately louder than the running metronome.
                gain = 0.5f,
            )
        }
    }

    fun pause() {
        scope.launch {
            val tick = currentTick()
            stopInternal()
            _state.value = _state.value.copy(isPlaying = false, positionTick = tick)
        }
    }

    fun stop() {
        scope.launch {
            stopInternal()
            _state.value = _state.value.copy(isPlaying = false, positionTick = 0L)
            seekInternal(0L)
        }
    }

    fun seek(tick: Long) {
        scope.launch {
            val target = tick.coerceIn(0L, piece.endTick)
            val wasPlaying = _state.value.isPlaying
            if (wasPlaying) stopInternal()
            _state.value = _state.value.copy(positionTick = target)
            // No count-in when scrubbing: a click burst on every drag of the
            // playhead would be maddening. startPlaying() re-seeks itself, so
            // the paused case is the only one that has to do it here.
            if (wasPlaying) startPlaying(withCountIn = false) else seekInternal(target)
        }
    }

    /**
     * Changes playback speed without changing pitch.
     *
     * Implemented by re-anchoring at the current musical position, so the notes
     * already sounding are unaffected and everything after them is simply spaced
     * differently.
     */
    fun setTempoScale(scale: Float) {
        scope.launch {
            val clamped = scale.coerceIn(MIN_TEMPO_SCALE, MAX_TEMPO_SCALE)
            val tick = currentTick()
            val wasPlaying = _state.value.isPlaying
            if (wasPlaying) stopInternal()
            tempoScale = clamped
            _state.value = _state.value.copy(tempoScale = clamped, positionTick = tick)
            if (wasPlaying) startPlaying(withCountIn = false) else seekInternal(tick)
        }
    }

    fun setLoop(region: LoopRegion?) {
        scope.launch { _state.value = _state.value.copy(loop = region) }
    }

    fun setHandMuted(hand: Hand, muted: Boolean) {
        scope.launch {
            _state.value = when (hand) {
                Hand.RIGHT -> _state.value.copy(rightHandMuted = muted)
                Hand.LEFT -> _state.value.copy(leftHandMuted = muted)
            }
            applyHandVolumes()
        }
    }

    fun setMetronomeEnabled(enabled: Boolean) {
        scope.launch { _state.value = _state.value.copy(metronomeEnabled = enabled) }
    }

    fun setMasterVolume(volume: Float) {
        scope.launch { synth.setMasterGain(volume.coerceIn(0f, 1f)) }
    }

    /**
     * Current musical position, read from the audio clock.
     *
     * Safe to call from the UI thread every frame — it is one atomic read plus
     * arithmetic, no locks and no allocation.
     */
    fun positionTickNow(): Long {
        if (!_state.value.isPlaying) return _state.value.positionTick
        val frame = synth.transportFrames()
        // During the count-in the playhead holds still. Letting it run backwards
        // from before the first note would scroll the highway the wrong way.
        if (frame < countInUntilFrame) return anchorTick
        return tickAtFrame(frame).coerceIn(0L, piece.endTick)
    }

    fun setCountInBars(bars: Int) {
        scope.launch { _state.value = _state.value.copy(countInBars = bars.coerceIn(0, 2)) }
    }

    fun release() {
        schedulerJob?.cancel()
        scope.launch { synth.destroy() }
        engineThread.shutdown()
    }

    // ---- internals -------------------------------------------------------

    private fun startScheduler() {
        schedulerJob?.cancel()
        schedulerJob = scope.launch {
            while (_state.value.isPlaying) {
                pump()
                delay(SCHEDULER_INTERVAL_MS)
            }
        }
    }

    private fun pump() {
        val nowTick = currentTick()
        val loop = _state.value.loop

        if (_state.value.countingIn && synth.transportFrames() >= countInUntilFrame) {
            _state.value = _state.value.copy(countingIn = false)
        }

        if (loop != null && nowTick >= loop.endTick) {
            wrapLoop(loop)
            return
        }

        if (loop == null && nowTick >= piece.endTick) {
            synth.setPlaying(false)
            _state.value = _state.value.copy(isPlaying = false, positionTick = piece.endTick)
            return
        }

        val horizonTick = nowTick + lookaheadTicks()
        val limit = loop?.endTick ?: piece.endTick

        while (nextNoteIndex < piece.notes.size) {
            val note = piece.notes[nextNoteIndex]
            if (note.startTick >= horizonTick || note.startTick >= limit) break
            scheduleNote(
                note,
                onFrame = frameAtTick(note.startTick),
                endTick = note.endTick.coerceAtMost(limit),
            )
            nextNoteIndex++
        }

        scheduleMetronome(horizonTick, limit, audible = _state.value.metronomeEnabled)

        _state.value = _state.value.copy(positionTick = nowTick)
    }

    /**
     * Restarts the loop without touching the transport.
     *
     * Re-anchoring rather than seeking is what keeps the wrap gapless: the audio
     * clock keeps running and the stream is never restarted, only the
     * tick-to-frame mapping moves back to the top of the loop.
     */
    private fun wrapLoop(loop: LoopRegion) {
        val frame = synth.transportFrames()
        // Also drops the releases queued for the notes being cut. Left in place
        // they would land a moment into the next pass and chop the same keys
        // struck again at the top of the loop.
        synth.flush()
        anchorTick = loop.startTick
        anchorFrame = frame
        // No count-in on a loop wrap — the whole point is that it repeats
        // without a gap.
        countInUntilFrame = frame
        resetCursors(loop.startTick)
        scheduleNotesAlreadySounding(loop.startTick)
        _state.value = _state.value.copy(positionTick = loop.startTick)
    }

    /**
     * Queues one note's pair of events.
     *
     * The release is forced at least one frame after the attack. Events sharing
     * a frame are applied note-off first, so that a key released and re-struck on
     * the same beat is not killed by its own predecessor — which would silence a
     * note that rounds to a zero-frame length.
     */
    private fun scheduleNote(note: Note, onFrame: Long, endTick: Long) {
        val channel = note.hand.synthChannel
        synth.scheduleNoteOn(onFrame, channel, note.pitch, note.velocity)
        synth.scheduleNoteOff(maxOf(frameAtTick(endTick), onFrame + 1), channel, note.pitch)
    }

    /**
     * Walks the beat grid up to the horizon.
     *
     * The cursor advances whether or not [audible] is set, so switching the
     * metronome on mid-piece starts clicking at the next beat rather than firing
     * off every beat it missed while it was silent.
     */
    private fun scheduleMetronome(horizonTick: Long, limitTick: Long, audible: Boolean) {
        val ticksPerQuarter = piece.tempoMap.ticksPerQuarter
        while (nextMetronomeTick < horizonTick && nextMetronomeTick < limitTick) {
            val signature = piece.timeSignatureAt(nextMetronomeTick)
            val ticksPerBeat = signature.ticksPerBeat(ticksPerQuarter).coerceAtLeast(1L)
            // Counted from where the signature itself starts, not from the top of
            // the piece: after a change of metre the accent has to land on the
            // new beat one, and the bar lengths either side rarely divide evenly.
            val beatInBar = (nextMetronomeTick - signature.tick) / ticksPerBeat % signature.numerator
            if (audible) {
                synth.scheduleMetronome(
                    frameAtTick(nextMetronomeTick),
                    accent = beatInBar == 0L,
                    gain = 0.35f,
                )
            }
            nextMetronomeTick += ticksPerBeat
        }
    }

    private fun stopInternal() {
        schedulerJob?.cancel()
        schedulerJob = null
        synth.setPlaying(false)
        synth.flush()
    }

    /**
     * Repositions the transport and resets the scheduling cursors.
     *
     * The native side bumps its event generation on seek, so anything already
     * queued for the old position is discarded rather than firing late.
     */
    private fun seekInternal(tick: Long) {
        anchorTick = tick
        anchorFrame = frameForTickAbsolute(tick)
        countInUntilFrame = anchorFrame
        synth.seek(anchorFrame)
        resetCursors(tick)
    }

    /** Rewinds the scheduling cursors to [tick]. Does not touch the transport. */
    private fun resetCursors(tick: Long) {
        nextNoteIndex = firstNoteIndexAtOrAfter(tick)
        nextMetronomeTick = firstBeatAtOrAfter(tick)
    }

    /** Lower bound in [Piece.notes], which is sorted by start tick. */
    private fun firstNoteIndexAtOrAfter(tick: Long): Int {
        var low = 0
        var high = piece.notes.size
        while (low < high) {
            val mid = (low + high) / 2
            if (piece.notes[mid].startTick < tick) low = mid + 1 else high = mid
        }
        return low
    }

    private fun firstBeatAtOrAfter(tick: Long): Long {
        val signature = piece.timeSignatureAt(tick)
        val ticksPerBeat = signature.ticksPerBeat(piece.tempoMap.ticksPerQuarter).coerceAtLeast(1L)
        val beatsIn = Math.floorDiv(tick - signature.tick, ticksPerBeat)
        val beat = signature.tick + beatsIn * ticksPerBeat
        return if (beat >= tick) beat else beat + ticksPerBeat
    }

    private fun applyHandVolumes(frame: Long = synth.transportFrames()) {
        synth.setChannelVolume(
            frame,
            Hand.RIGHT.synthChannel,
            if (_state.value.rightHandMuted) 0f else 1f,
        )
        synth.setChannelVolume(
            frame,
            Hand.LEFT.synthChannel,
            if (_state.value.leftHandMuted) 0f else 1f,
        )
    }

    private fun currentTick(): Long {
        val frame = synth.transportFrames()
        if (frame < countInUntilFrame) return anchorTick
        return tickAtFrame(frame).coerceIn(0L, piece.endTick)
    }

    private fun lookaheadTicks(): Long {
        val micros = LOOKAHEAD_MS * 1000L * tempoScale
        val startMicros = piece.tempoMap.tickToMicros(anchorTick)
        return piece.tempoMap.microsToTick(startMicros + micros.toLong()) - anchorTick
    }

    /** Frame offset of [tick] relative to the current anchor. */
    private fun frameAtTick(tick: Long): Long {
        val deltaMicros = piece.tempoMap.tickToMicros(tick) - piece.tempoMap.tickToMicros(anchorTick)
        val scaledMicros = deltaMicros / tempoScale
        return anchorFrame + (scaledMicros * sampleRate / 1_000_000.0).roundToLong()
    }

    private fun frameForTickAbsolute(tick: Long): Long {
        val micros = piece.tempoMap.tickToMicros(tick) / tempoScale
        return (micros * sampleRate / 1_000_000.0).roundToLong()
    }

    private fun tickAtFrame(frame: Long): Long {
        val deltaFrames = frame - anchorFrame
        val micros = (deltaFrames * 1_000_000.0 / sampleRate) * tempoScale
        val anchorMicros = piece.tempoMap.tickToMicros(anchorTick)
        return piece.tempoMap.microsToTick(anchorMicros + micros.toLong())
    }

    companion object {
        const val DEFAULT_SOUND_FONT = "soundfonts/UprightPianoKW-small.sf2"
        const val MIN_TEMPO_SCALE = 0.25f
        const val MAX_TEMPO_SCALE = 1.25f

        /** How far ahead events are queued. Comfortably longer than one pump. */
        private const val LOOKAHEAD_MS = 400L
        private const val SCHEDULER_INTERVAL_MS = 50L
    }
}
