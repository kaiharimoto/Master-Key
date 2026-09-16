package dev.kaiharimoto.masterkey.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kaiharimoto.masterkey.core.edit.NoteDraft
import dev.kaiharimoto.masterkey.core.edit.SnapGrid
import dev.kaiharimoto.masterkey.core.keyboard.KeyRange
import dev.kaiharimoto.masterkey.core.keyboard.PianoProportions
import dev.kaiharimoto.masterkey.core.midi.Pitch
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.ui.theme.HighwayColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The falling-note highway.
 *
 * Performance rules this file follows deliberately, because breaking any of them
 * is what turns a Compose canvas animation into a slideshow:
 *
 *  1. One [Canvas], never one composable per note.
 *  2. The playhead lives in a [mutableLongStateOf] that is read **only inside the
 *     draw lambda**. Reading it in the composable body would recompose the whole
 *     subtree every frame; reading it in draw invalidates the draw phase alone.
 *  3. Notes are found by binary search over a tick-sorted list, never by scanning
 *     the piece.
 *  4. Text layouts are cached. Measuring "C#" 100 times a frame costs far more
 *     than drawing 100 rectangles.
 *  5. Position comes from the audio clock, not from accumulated frame deltas, so
 *     vsync jitter cannot desynchronise the notes from the sound.
 */
@Composable
fun NoteHighway(
    model: HighwayModel,
    range: KeyRange,
    settings: ScaffoldSettings,
    positionProvider: () -> Long,
    modifier: Modifier = Modifier,
    loopStartTick: Long? = null,
    loopEndTick: Long? = null,
    onSeekToTick: ((Long) -> Unit)? = null,
    onKeyTapped: ((Int) -> Unit)? = null,
    onScrubStart: (() -> Unit)? = null,
    onScrubTo: ((Long) -> Unit)? = null,
    onScrubEnd: (() -> Unit)? = null,
    // ---- edit mode ----
    editing: Boolean = false,
    edit: HighwayEditState? = null,
    snapGrid: SnapGrid = SnapGrid.DEFAULT,
    /** Indices into `model.notes` that are selected, ascending. */
    selectedIndices: List<Int> = emptyList(),
    /** True while taps add to the selection and an empty drag draws a marquee. */
    selectMode: Boolean = false,
    snapAnchorAt: ((Long) -> Long)? = null,
    onSelect: ((Int) -> Unit)? = null,
    onSelectRange: ((List<Int>) -> Unit)? = null,
    onDraftAt: ((Int) -> NoteDraft?)? = null,
    onNewNoteDraft: ((pitch: Int, startTick: Long, lengthTicks: Long) -> NoteDraft?)? = null,
    onCommitDrag: ((index: Int, draft: NoteDraft, minLengthTicks: Long) -> Unit)? = null,
    onInsertNote: ((draft: NoteDraft, minLengthTicks: Long) -> Unit)? = null,
    /** Commits a drag that moved a whole selection, as one offset. */
    onCommitGroupDrag: ((deltaTicks: Long, deltaPitch: Int) -> Unit)? = null,
    onLookAheadCommitted: ((Float) -> Unit)? = null,
    /**
     * Sounds a pitch while a note is being dragged.
     *
     * Separate from [onKeyTapped] because the two want opposite behaviour on a
     * repeat: tapping the drawn keyboard should not re-trigger a key that is
     * already ringing, while dragging a note back into a lane it just left
     * must, or the drag goes quiet exactly when it matters.
     */
    onAudition: ((Int) -> Unit)? = null,
    /** How far before bar 1 the view may scroll, so the empty space can be shown. */
    preRollTicks: Long = 0L,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelCache = remember(textMeasurer) { TextLayoutCache(textMeasurer) }

    // The playhead. Written once per frame, read only in the draw lambda.
    val positionTicks = remember { mutableLongStateOf(0L) }
    LaunchedEffect(positionProvider) {
        while (true) {
            withFrameNanos { positionTicks.longValue = positionProvider() }
        }
    }

    // Read inside the gesture coroutine, which must survive them changing.
    //
    // These used to be `pointerInput` keys. That fixed a real bug — a handler
    // that captured `lookAheadBeats` once went on scaling every tap by a stale
    // number after the slider moved — but it is the costlier of the two fixes,
    // and pinch-to-zoom makes it untenable: zooming writes look-ahead, which
    // would re-key the handler, which cancels the coroutine, which ends the
    // pinch after a single frame. Reading through `rememberUpdatedState` cannot
    // go stale *and* cannot cancel anything.
    val latestSettings by rememberUpdatedState(settings)
    val latestRange by rememberUpdatedState(range)
    val latestGrid by rememberUpdatedState(snapGrid)

    // Turned into a primitive array once per composition, not per frame: the
    // draw phase asks "is this index selected" for every visible note, and
    // boxing an Integer per question is the kind of thing that only shows up as
    // jank once a piece gets long.
    val selection = remember(selectedIndices) { selectedIndices.toIntArray() }
    val latestSelection by rememberUpdatedState(selection)
    val latestSelectMode by rememberUpdatedState(selectMode)

    // Animating the visible span as two floats is what allows a smooth pan
    // between key ranges rather than a jump from one keyboard layout to another.
    val leftWhite = remember { Animatable(Pitch.whiteIndex(range.low).toFloat()) }
    val visibleWhite = remember { Animatable(range.whiteKeyCount.toFloat()) }

    LaunchedEffect(range) {
        val targetLeft = Pitch.whiteIndex(range.low).toFloat()
        val targetCount = range.whiteKeyCount.toFloat()
        if (leftWhite.value == targetLeft && visibleWhite.value == targetCount) return@LaunchedEffect
        // Panning only ever happens at a phrase boundary, never mid-passage —
        // a keyboard that slides sideways while you are reading is worse than
        // one drawn slightly too small.
        val spec = tween<Float>(durationMillis = 550)
        coroutineScope {
            launch { leftWhite.animateTo(targetLeft, spec) }
            launch { visibleWhite.animateTo(targetCount, spec) }
        }
    }

    val minKeyboardPx = with(density) { KEYBOARD_MIN_DEPTH.toPx() }
    val maxKeyboardPx = with(density) { KEYBOARD_MAX_DEPTH.toPx() }
    val cornerPx = with(density) { 3.dp.toPx() }

    // Depth follows key width, so it has to be recomputed whenever the visible
    // range changes — including mid-animation while the view pans between
    // sections. Cheap: two multiplies against values that are already to hand.
    fun keyboardDepth(containerWidth: Float, containerHeight: Float): Float =
        HighwayLayout(leftWhite.value, visibleWhite.value, containerWidth)
            .keyboardDepth(minKeyboardPx, minOf(maxKeyboardPx, containerHeight * KEYBOARD_MAX_SHARE))

    val handlePx = with(density) { GRIP_DEPTH.toPx() }
    val minTouchPx = with(density) { MIN_TOUCH_TARGET.toPx() }
    val stripDepthPx = with(density) { EDGE_STRIP_WIDTH.toPx() }

    Canvas(
        modifier = modifier.pointerInput(model, editing, onSeekToTick, onKeyTapped, onScrubTo) {
            // One handler for every interaction the pane has, because they all
            // begin the same way — a finger going down — and only diverge once
            // it has moved, or not, or been joined by a second one.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val keyboardHeightPx = keyboardDepth(size.width.toFloat(), size.height.toFloat())
                val keyLineY = size.height - keyboardHeightPx

                // The drawn keyboard is a separate control; a touch that starts
                // there plays a key and never scrubs. Unchanged in edit mode —
                // hearing a pitch while placing notes is exactly what you want.
                if (down.position.y >= keyLineY) {
                    down.consume()
                    onKeyTapped?.let { tapped ->
                        val layout =
                            HighwayLayout(leftWhite.value, visibleWhite.value, size.width.toFloat())
                        val isBlackRow = down.position.y <
                            keyLineY + keyboardHeightPx * PianoProportions.BLACK_TO_WHITE_LENGTH
                        layout.pitchAt(down.position.x, isBlackRow, latestRange.low, latestRange.high)
                            ?.let(tapped)
                    }
                    return@awaitEachGesture
                }

                if (keyLineY <= 0f) return@awaitEachGesture

                val layout = HighwayLayout(leftWhite.value, visibleWhite.value, size.width.toFloat())
                val ticksPerBeat = beatTicks(model)
                val lookAhead = (effectiveLookAheadBeats(latestSettings, edit) * ticksPerBeat)
                    .coerceAtLeast(1f)
                val pixelsPerTick = keyLineY / lookAhead

                val hitIndex = if (editing) {
                    HighwayHitTest.noteAt(
                        model = model,
                        layout = layout,
                        x = down.position.x,
                        y = down.position.y,
                        playheadTick = positionTicks.longValue,
                        keyLineY = keyLineY,
                        pixelsPerTick = pixelsPerTick,
                        minTouchPx = minTouchPx,
                        preferIndex = latestSelection.firstOrNull() ?: HighwayHitTest.NONE,
                    )
                } else {
                    HighwayHitTest.NONE
                }

                // A narrow strip down each edge, where a vertical drag covers
                // the whole piece rather than a paneful of it.
                //
                // Claimed only where nothing else wants the touch. A note under
                // the finger always wins — `hitIndex` is already resolved here,
                // so the outermost lanes stay as editable as any other — and the
                // lasso wins too, so a marquee can still be swept from the very
                // edge of the pane.
                val stripPx = min(stripDepthPx, size.width * MAX_STRIP_SHARE)
                val inStrip = hitIndex == HighwayHitTest.NONE &&
                    !latestSelectMode &&
                    (down.position.x <= stripPx || down.position.x >= size.width - stripPx)

                // Resolving what the gesture *is* has to be a hand-rolled loop.
                // The stock helpers each wait for exactly one outcome and give no
                // chance to bail out; here a second finger, the touch slop and the
                // long-press clock are all in the running at once.
                val intent = awaitIntent(
                    down = down,
                    editing = editing,
                    overANote = hitIndex != HighwayHitTest.NONE,
                    selectMode = latestSelectMode,
                    inStrip = inStrip,
                )

                when (intent) {
                    Intent.TRANSFORM -> runTransform(
                        pixelsPerTick = pixelsPerTick,
                        startTick = positionTicks.longValue,
                        preRollTicks = preRollTicks,
                        edit = edit,
                        settings = latestSettings,
                        onScrubStart = onScrubStart,
                        onScrubTo = onScrubTo,
                        onScrubEnd = onScrubEnd,
                        onLookAheadCommitted = onLookAheadCommitted,
                    )

                    Intent.TAP -> if (editing) {
                        // Tap-to-seek is off while editing: it is the gesture
                        // that collides hardest with tapping a note, and the
                        // scrubber and two-finger pan both still move the
                        // playhead.
                        onSelect?.invoke(hitIndex)
                    } else {
                        onSeekToTick?.let { seek ->
                            val fraction = (keyLineY - down.position.y) / keyLineY
                            val target = positionTicks.longValue + (fraction * lookAhead).toLong()
                            seek(target.coerceAtLeast(0L))
                        }
                    }

                    // Dragging any note of a multi-note selection moves the
                    // whole selection; resizing a group is not an operation this
                    // editor has, so a group drag is always a move.
                    Intent.EDIT_DRAG -> if (
                        latestSelection.size > 1 && latestSelection.contains(hitIndex)
                    ) {
                        runGroupDrag(
                            pointerId = down.id,
                            down = down.position,
                            layout = layout,
                            range = latestRange,
                            grid = latestGrid,
                            ticksPerQuarter = model.piece.tempoMap.ticksPerQuarter,
                            snapAnchorAt = snapAnchorAt,
                            anchorDraft = onDraftAt?.invoke(hitIndex),
                            pixelsPerTick = pixelsPerTick,
                            edit = edit,
                            minTick = -preRollTicks,
                            onCommitGroupDrag = onCommitGroupDrag,
                        )
                    } else runNoteDrag(
                        pointerId = down.id,
                        index = hitIndex,
                        down = down.position,
                        layout = layout,
                        range = latestRange,
                        grid = latestGrid,
                        ticksPerQuarter = model.piece.tempoMap.ticksPerQuarter,
                        snapAnchorAt = snapAnchorAt,
                        playheadTick = positionTicks.longValue,
                        keyLineY = keyLineY,
                        pixelsPerTick = pixelsPerTick,
                        handlePx = handlePx,
                        edit = edit,
                        minTick = -preRollTicks,
                        onSelect = onSelect,
                        onDraftAt = onDraftAt,
                        onCommitDrag = onCommitDrag,
                        onAudition = onAudition ?: onKeyTapped,
                    )

                    Intent.CREATE -> runNoteDraw(
                        pointerId = down.id,
                        down = down.position,
                        layout = layout,
                        range = latestRange,
                        grid = latestGrid,
                        ticksPerQuarter = model.piece.tempoMap.ticksPerQuarter,
                        snapAnchorAt = snapAnchorAt,
                        playheadTick = positionTicks.longValue,
                        keyLineY = keyLineY,
                        pixelsPerTick = pixelsPerTick,
                        edit = edit,
                        minTick = -preRollTicks,
                        onNewNoteDraft = onNewNoteDraft,
                        onInsertNote = onInsertNote,
                        onKeyTapped = onAudition ?: onKeyTapped,
                    )

                    Intent.SCRUB -> {
                        val scrubTo = onScrubTo ?: return@awaitEachGesture
                        // Held locally as a Float so slow drags accumulate
                        // sub-tick movement instead of rounding it away.
                        var tick = positionTicks.longValue.toFloat()
                        onScrubStart?.invoke()
                        verticalDrag(down.id) { change ->
                            // Notes fall downwards, so dragging the sheet down
                            // pulls earlier music back into view — the same
                            // direction the music travels, and the opposite sign
                            // to the drag.
                            tick -= change.positionChange().y / pixelsPerTick
                            scrubTo(tick.toLong().coerceAtLeast(-preRollTicks))
                            change.consume()
                        }
                        onScrubEnd?.invoke()
                    }

                    Intent.STRIP_SCRUB -> {
                        val scrubTo = onScrubTo ?: return@awaitEachGesture
                        val endTick = model.piece.endTick
                        var tick = positionTicks.longValue.toFloat()
                        // Sounds what it passes, like the ordinary scrub. The
                        // preview bounds itself to a handful of notes nearest
                        // where the finger is, which is what keeps a sweep this
                        // fast from arriving as one enormous chord.
                        onScrubStart?.invoke()
                        verticalDrag(down.id) { change ->
                            tick += HighwayHitTest.fastScrubDelta(
                                dy = change.positionChange().y,
                                keyLineY = keyLineY,
                                endTick = endTick,
                            )
                            // Clamped as it accumulates, not only where it is
                            // read: at this gain an overshoot past either end
                            // would otherwise have to be dragged back through
                            // before anything moved again.
                            tick = tick.coerceIn(-preRollTicks.toFloat(), endTick.toFloat())
                            scrubTo(tick.toLong())
                            change.consume()
                        }
                        onScrubEnd?.invoke()
                    }

                    Intent.MARQUEE -> runMarquee(
                        pointerId = down.id,
                        down = down.position,
                        model = model,
                        layout = layout,
                        playheadTick = positionTicks.longValue,
                        keyLineY = keyLineY,
                        pixelsPerTick = pixelsPerTick,
                        edit = edit,
                        onSelectRange = onSelectRange,
                    )

                    Intent.NONE -> Unit
                }
            }
        },
    ) {
        // ---- everything below runs in the DRAW phase only ----
        val position = positionTicks.longValue
        val width = size.width
        val height = size.height
        val keyboardHeightPx = keyboardDepth(width, height)
        val keyLineY = height - keyboardHeightPx
        if (keyLineY <= 0f || width <= 0f) return@Canvas

        val layout = HighwayLayout(leftWhite.value, visibleWhite.value, width)
        val ticksPerBeat = beatTicks(model)
        // Reading the pinch here, in the draw phase, is what makes zooming
        // smooth without recomposing: the gesture writes one float and only the
        // drawing is invalidated.
        val lookAheadTicks = (effectiveLookAheadBeats(settings, edit) * ticksPerBeat)
            .coerceAtLeast(1f)
        val pixelsPerTick = keyLineY / lookAheadTicks

        // Read once per frame, like the playhead above it, and never in the
        // composable body — that is the whole reason this state exists.
        val dragIndex = edit?.dragIndex?.intValue ?: HighwayEditState.NO_DRAG
        val groupDragging = editing && edit != null && edit.groupDragging.value
        val groupDeltaTicks = if (groupDragging) edit!!.groupDeltaTicks.longValue else 0L
        val groupDeltaPitch = if (groupDragging) edit!!.groupDeltaPitch.intValue else 0
        val ghost = if (editing && edit != null && edit.ghosting) {
            GhostNote(
                pitch = edit.ghostPitch.intValue,
                startTick = edit.ghostStart.longValue,
                endTick = edit.ghostEnd.longValue,
            )
        } else {
            null
        }

        drawRect(HighwayColors.background)

        drawLanes(layout, range, keyLineY)

        // The space before the piece starts, drawn as a distinct region so the
        // emptiness reads as room you may use rather than as a failure to draw.
        if (preRollTicks > 0L) {
            val startY = HighwayHitTest.yAt(0L, position, keyLineY, pixelsPerTick)
            // Anything above the key line is pre-roll, whether or not bar 1 is
            // still on screen — scroll far enough back and it fills the pane, and
            // a guard that also demanded a visible bar line turned the tint off
            // at exactly the point there was nothing else to say where you were.
            if (startY < keyLineY) {
                val top = startY.coerceAtLeast(0f)
                drawRect(
                    color = HighwayColors.editPreRoll,
                    topLeft = Offset(0f, top),
                    size = Size(width, keyLineY - top),
                )
                if (startY > 0f) {
                    drawRect(HighwayColors.barLine, Offset(0f, startY), Size(width, 2f))
                }
            }
        }

        // The fast-seek strips. Under the notes, the grid and the loop bracket —
        // painted on top they would dim the outermost lanes, which are a whole
        // white key wide on a two-octave view — but over the pre-roll tint,
        // which covers the full width and would otherwise swallow them exactly
        // where a long seek back is most likely to start.
        drawEdgeStrips(
            width = width,
            keyLineY = keyLineY,
            stripPx = min(stripDepthPx, width * MAX_STRIP_SHARE),
            position = position,
            endTick = model.piece.endTick,
            // Inset in edit mode so the strip and the edit border read as two
            // things rather than one thick frame.
            inset = if (editing) EDIT_BORDER_PX else 0f,
        )

        if (settings.showBeatGrid) {
            drawGrid(model, position, lookAheadTicks, pixelsPerTick, keyLineY, width, labelCache)
        }

        // The subdivisions the snap grid will round to, so you can see where a
        // note is about to land rather than discovering it afterwards.
        if (editing) {
            drawSnapLines(
                model = model,
                grid = snapGrid,
                position = position,
                lookAheadTicks = lookAheadTicks.toLong(),
                pixelsPerTick = pixelsPerTick,
                keyLineY = keyLineY,
                width = width,
            )
        }

        drawLoopBracket(loopStartTick, loopEndTick, position, pixelsPerTick, keyLineY, width)

        val sounding = ArrayList<Note>(16)
        clipRect(0f, 0f, width, keyLineY) {
            drawNotes(
                model = model,
                position = position,
                lookAheadTicks = lookAheadTicks.toLong(),
                pixelsPerTick = pixelsPerTick,
                keyLineY = keyLineY,
                layout = layout,
                settings = settings,
                cornerPx = cornerPx,
                labelCache = labelCache,
                soundingOut = sounding,
                editing = editing,
                selection = selection,
                dragIndex = dragIndex,
                handlePx = handlePx,
                groupDragging = groupDragging,
                groupDeltaTicks = groupDeltaTicks,
                groupDeltaPitch = groupDeltaPitch,
            )

            ghost?.let {
                drawGhost(
                    ghost = it,
                    position = position,
                    pixelsPerTick = pixelsPerTick,
                    keyLineY = keyLineY,
                    layout = layout,
                    cornerPx = cornerPx,
                )
            }

            if (editing && edit != null && edit.marqueeActive.value) {
                val left = edit.marqueeLeft.floatValue
                val top = edit.marqueeTop.floatValue
                val boxWidth = edit.marqueeRight.floatValue - left
                val boxHeight = edit.marqueeBottom.floatValue - top
                drawRect(HighwayColors.editMarqueeFill, Offset(left, top), Size(boxWidth, boxHeight))
                drawRect(
                    color = HighwayColors.editSelection,
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight),
                    style = Stroke(width = 1.5f),
                )
            }
        }

        // Hit line: where a note must be played. Drawn after notes so it reads as
        // a surface the notes land on rather than a line they pass behind.
        drawRect(
            color = HighwayColors.hitLine.copy(alpha = 0.85f),
            topLeft = Offset(0f, keyLineY - 1.5f),
            size = Size(width, 3f),
        )

        drawKeyboard(
            layout = layout,
            range = range,
            keyLineY = keyLineY,
            keyboardHeight = keyboardHeightPx,
            sounding = sounding,
            settings = settings,
            labelCache = labelCache,
        )

        // An unmistakable border, because the one thing worse than not being in
        // edit mode is being in it without realising.
        if (editing) {
            drawRect(
                color = HighwayColors.editBorder,
                topLeft = Offset(0f, 0f),
                size = Size(width, height),
                style = Stroke(width = EDIT_BORDER_PX),
            )
        }
    }
}

/**
 * The fast-seek strips, and where in the piece you are.
 *
 * Subtle on purpose: a shade lifted from the background, which is enough to say
 * the edges are a control without competing with the notes. The marker is what
 * makes it a map of the song rather than a border — without it there is nothing
 * to say how far a sweep would have to go.
 */
private fun DrawScope.drawEdgeStrips(
    width: Float,
    keyLineY: Float,
    stripPx: Float,
    position: Long,
    endTick: Long,
    inset: Float,
) {
    val top = inset
    val depth = stripPx - inset
    if (depth <= 0f || keyLineY <= top) return

    val band = Size(depth, keyLineY - top)
    drawRect(HighwayColors.edgeStrip, Offset(inset, top), band)
    drawRect(HighwayColors.edgeStrip, Offset(width - stripPx, top), band)

    if (endTick <= 0L) return
    val progress = (position.toFloat() / endTick).coerceIn(0f, 1f)
    val markerY = (top + progress * (keyLineY - top) - EDGE_STRIP_MARKER_PX / 2f)
        .coerceIn(top, keyLineY - EDGE_STRIP_MARKER_PX)
    val marker = Size(depth, EDGE_STRIP_MARKER_PX)
    drawRect(HighwayColors.edgeStripMarker, Offset(inset, markerY), marker)
    drawRect(HighwayColors.edgeStripMarker, Offset(width - stripPx, markerY), marker)
}

/** A note as the finger currently has it, before the edit is committed. */
private class GhostNote(val pitch: Int, val startTick: Long, val endTick: Long)

/** What a gesture on the highway turned out to be. */
private enum class Intent {
    /** Never moved far enough to be a drag. */
    TAP,

    /** One finger, dragging the music past the key line. */
    SCRUB,

    /** One finger in an edge strip: the whole piece in half a paneful. */
    STRIP_SCRUB,

    /** One finger, dragging a note it landed on. */
    EDIT_DRAG,

    /** Held still on empty space long enough to mean "put a note here". */
    CREATE,

    /** A second finger arrived: pinch to zoom, drag to scroll. */
    TRANSFORM,

    /** Select mode, dragging across empty space: sweep up everything it touches. */
    MARQUEE,

    /** Nothing to do. */
    NONE,
}

/**
 * Works out what the gesture is.
 *
 * Four things are racing: the finger moving past the touch slop, a second finger
 * arriving, the long-press clock running out, and the finger lifting. Compose's
 * stock helpers each wait for one of those and swallow the rest, so this watches
 * all four itself. The slop test is the standard one — accumulated distance
 * against `viewConfiguration.touchSlop` — so a drag starts feeling exactly as it
 * did before edit mode existed.
 */
private suspend fun AwaitPointerEventScope.awaitIntent(
    down: PointerInputChange,
    editing: Boolean,
    overANote: Boolean,
    selectMode: Boolean,
    inStrip: Boolean,
): Intent {
    var travelled = Offset.Zero
    val longPressAt = System.nanoTime() + LONG_PRESS_NANOS

    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }

        // A second finger always wins, in both modes: today it is simply
        // ignored, so nothing can regress by claiming it.
        if (pressed.size >= 2) return Intent.TRANSFORM

        val change = pressed.firstOrNull { it.id == down.id } ?: return Intent.TAP
        if (change.changedToUpIgnoreConsumed()) return Intent.TAP

        travelled += change.positionChange()
        // Dragging a note moves it sideways as well as along, so that one needs
        // two-dimensional slop. Everything else scrolls the music, which is a
        // vertical gesture — and measuring it vertically is what stops a
        // sideways swipe from being read as a scrub, exactly as before.
        val slopped = if (inStrip) {
            // Half the usual threshold, vertical only. The strip is a deliberate
            // place to put a finger, so it should engage at once — and a thumb
            // rolling sideways off the edge of a tablet must not defeat it.
            abs(travelled.y) > viewConfiguration.touchSlop / 2f
        } else if (editing && (overANote || selectMode)) {
            // A note drag moves sideways as well as along, and a marquee is
            // drawn in both directions, so both want two-dimensional slop.
            travelled.getDistance() > viewConfiguration.touchSlop
        } else {
            // Everything else scrolls the music, which is a vertical gesture —
            // and measuring it vertically is what stops a sideways swipe being
            // read as a scrub, exactly as before edit mode existed.
            abs(travelled.y) > viewConfiguration.touchSlop
        }
        if (slopped) {
            return when {
                inStrip -> Intent.STRIP_SCRUB
                !editing -> Intent.SCRUB
                overANote -> Intent.EDIT_DRAG
                // With the lasso on, an empty-lane drag sweeps a selection
                // instead of scrolling. Two-finger pan still scrolls, so
                // navigation never disappears — which is the whole reason this
                // is a mode rather than a gesture stolen from somewhere.
                selectMode -> Intent.MARQUEE
                // An empty-lane drag still scrolls while editing. It is the only
                // one-finger way through the piece now that tap-to-seek is gone,
                // and the alternative — a drag that does nothing — is worse.
                else -> Intent.SCRUB
            }
        }

        // Not in a strip: resting a finger on one for 400 ms should do nothing,
        // not spawn a note in the outermost lane.
        if (editing && !overANote && !inStrip && System.nanoTime() >= longPressAt) {
            return Intent.CREATE
        }

        change.consume()
    }
}

/**
 * Two fingers: pinch to zoom the timeline, drag to scroll it.
 *
 * Zoom *divides* the look-ahead — pinching apart should show less music more
 * closely, which is fewer beats, not more. The result is kept local and
 * committed once on release, because `setLookAheadBeats` writes the database and
 * the on-disk manifest; committing per frame would be a few hundred file writes
 * for one pinch. The tempo slider does the same thing for the same reason.
 */
private suspend fun AwaitPointerEventScope.runTransform(
    pixelsPerTick: Float,
    startTick: Long,
    preRollTicks: Long,
    edit: HighwayEditState?,
    settings: ScaffoldSettings,
    onScrubStart: (() -> Unit)?,
    onScrubTo: ((Long) -> Unit)?,
    onScrubEnd: (() -> Unit)?,
    onLookAheadCommitted: ((Float) -> Unit)?,
) {
    // Navigation, not listening: a two-finger scroll should not sound the notes
    // it passes the way a one-finger scrub does.
    onScrubStart?.invoke()
    var tick = startTick.toFloat()
    var zoomed = false

    do {
        val event = awaitPointerEvent()
        val zoom = event.calculateZoom()
        if (zoom != 1f && zoom > 0f && edit != null) {
            val next = (edit.zoom.floatValue / zoom)
            val beats = (settings.lookAheadBeats * next)
                .coerceIn(MIN_LOOK_AHEAD_BEATS, MAX_LOOK_AHEAD_BEATS)
            edit.zoom.floatValue = beats / settings.lookAheadBeats
            zoomed = true
        }

        val pan = event.calculatePan()
        if (pan.y != 0f && pixelsPerTick > 0f) {
            tick -= pan.y / pixelsPerTick
            onScrubTo?.invoke(tick.toLong().coerceAtLeast(-preRollTicks))
        }

        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })

    onScrubEnd?.invoke()

    if (zoomed && edit != null) {
        val committed = (settings.lookAheadBeats * edit.zoom.floatValue)
            .coerceIn(MIN_LOOK_AHEAD_BEATS, MAX_LOOK_AHEAD_BEATS)
        edit.zoom.floatValue = 1f
        // Rounded to a half beat so the number the pinch produced and the number
        // the look-ahead slider shows are the same one.
        onLookAheadCommitted?.invoke(Math.round(committed * 2f) / 2f)
    }
}

/**
 * Sweeping a box across empty space to select what it touches.
 *
 * The selection is published continuously rather than on release, so you can see
 * what the box has caught while you are still drawing it — a marquee that only
 * reports at the end is a guess until you let go.
 */
private suspend fun AwaitPointerEventScope.runMarquee(
    pointerId: PointerId,
    down: Offset,
    model: HighwayModel,
    layout: HighwayLayout,
    playheadTick: Long,
    keyLineY: Float,
    pixelsPerTick: Float,
    edit: HighwayEditState?,
    onSelectRange: ((List<Int>) -> Unit)?,
) {
    var corner = down
    var lastHits = emptyList<Int>()
    edit?.showMarquee(down.x, down.y, down.x, down.y)

    drag(pointerId) { change ->
        corner += change.positionChange()
        edit?.showMarquee(down.x, down.y, corner.x, corner.y)

        val hits = HighwayHitTest.notesIn(
            model = model,
            layout = layout,
            left = minOf(down.x, corner.x),
            top = minOf(down.y, corner.y),
            right = maxOf(down.x, corner.x),
            bottom = maxOf(down.y, corner.y).coerceAtMost(keyLineY),
            playheadTick = playheadTick,
            keyLineY = keyLineY,
            pixelsPerTick = pixelsPerTick,
        )
        // Only republish when the catch actually changed; a sweep across empty
        // space would otherwise rebuild the selection on every frame.
        if (hits != lastHits) {
            lastHits = hits
            onSelectRange?.invoke(hits)
        }
        change.consume()
    }

    edit?.clearMarquee()
}

/** Dragging an existing note: move it, or take one of its ends. */
private suspend fun AwaitPointerEventScope.runNoteDrag(
    pointerId: PointerId,
    index: Int,
    down: Offset,
    layout: HighwayLayout,
    range: KeyRange,
    grid: SnapGrid,
    ticksPerQuarter: Int,
    snapAnchorAt: ((Long) -> Long)?,
    playheadTick: Long,
    keyLineY: Float,
    pixelsPerTick: Float,
    handlePx: Float,
    edit: HighwayEditState?,
    /** Earliest tick the note may be dragged to: negative, out in the pre-roll. */
    minTick: Long,
    onSelect: ((Int) -> Unit)?,
    onDraftAt: ((Int) -> NoteDraft?)?,
    onCommitDrag: ((Int, NoteDraft, Long) -> Unit)?,
    onAudition: ((Int) -> Unit)?,
) {
    val original = onDraftAt?.invoke(index) ?: return
    onSelect?.invoke(index)

    val bottomY = HighwayHitTest.yAt(original.startTick, playheadTick, keyLineY, pixelsPerTick)
    val topY = HighwayHitTest.yAt(original.endTick, playheadTick, keyLineY, pixelsPerTick)
    val zone = HighwayHitTest.zoneAt(down.y, topY, bottomY, handlePx)
    val minLength = NoteDrag.minLengthTicks(grid, ticksPerQuarter)
    val anchor = snapAnchorAt?.invoke(original.startTick) ?: 0L

    var current = original
    // What was last auditioned. Tracked as the whole snapped shape, not just the
    // pitch: a note dragged straight up keeps its pitch, and a note resized by a
    // grip cannot change pitch at all, so a pitch-only gate left both of those —
    // which is most of what editing is — completely silent.
    var soundedPitch = -1
    var soundedStart = Long.MIN_VALUE
    var travelled = Offset.Zero

    drag(pointerId) { change ->
        travelled += change.positionChange()
        // Ticks increase upwards, pixels downwards.
        val deltaTicks = (-travelled.y / pixelsPerTick).toLong()
        val pitch = if (zone == DragZone.BODY) {
            HighwayHitTest.laneAt(layout, down.x + travelled.x, range.low, range.high)
                ?: current.pitch
        } else {
            original.pitch
        }

        current = NoteDrag.apply(
            original = original,
            zone = zone,
            deltaTicks = deltaTicks,
            pitch = pitch,
            grid = grid,
            ticksPerQuarter = ticksPerQuarter,
            anchor = anchor,
            minTick = minTick,
        )
        edit?.showGhost(index, current)

        // Sound the note whenever it lands somewhere new — a different lane or a
        // different beat. Gated on the *snapped* values rather than on frames, so
        // a slow drag within one grid step stays quiet instead of machine-gunning,
        // and every crossing of a grid line is heard.
        if (current.pitch != soundedPitch || current.startTick != soundedStart) {
            soundedPitch = current.pitch
            soundedStart = current.startTick
            onAudition?.invoke(current.pitch)
        }
        change.consume()
    }

    edit?.clearGhost()
    if (current != original) onCommitDrag?.invoke(index, current, minLength)
}

/**
 * Dragging a whole selection.
 *
 * The snap is taken from the note actually under the finger and the resulting
 * offset applied to every other note unchanged, so the passage keeps its internal
 * rhythm. Snapping each note independently would quantise the group flat.
 */
private suspend fun AwaitPointerEventScope.runGroupDrag(
    pointerId: PointerId,
    down: Offset,
    layout: HighwayLayout,
    range: KeyRange,
    grid: SnapGrid,
    ticksPerQuarter: Int,
    snapAnchorAt: ((Long) -> Long)?,
    anchorDraft: NoteDraft?,
    pixelsPerTick: Float,
    edit: HighwayEditState?,
    /** Earliest tick the dragged note may land on; the rest follow it. */
    minTick: Long,
    onCommitGroupDrag: ((Long, Int) -> Unit)?,
) {
    val anchorNote = anchorDraft ?: return
    val gridAnchor = snapAnchorAt?.invoke(anchorNote.startTick) ?: 0L
    val startLane = HighwayHitTest.laneAt(layout, down.x, range.low, range.high)

    var travelled = Offset.Zero
    var deltaTicks = 0L
    var deltaPitch = 0

    drag(pointerId) { change ->
        travelled += change.positionChange()
        // Snap where the dragged note lands, then keep the difference.
        val wanted = anchorNote.startTick + (-travelled.y / pixelsPerTick).toLong()
        deltaTicks = grid.snap(wanted, ticksPerQuarter, gridAnchor, minTick) - anchorNote.startTick

        val lane = HighwayHitTest.laneAt(layout, down.x + travelled.x, range.low, range.high)
        if (lane != null && startLane != null) deltaPitch = lane - startLane

        edit?.showGroupDrag(deltaTicks, deltaPitch)
        change.consume()
    }

    edit?.clearGroupDrag()
    if (deltaTicks != 0L || deltaPitch != 0) onCommitGroupDrag?.invoke(deltaTicks, deltaPitch)
}

/** Long-pressed on empty space: draw a new note, dragging upwards to lengthen it. */
private suspend fun AwaitPointerEventScope.runNoteDraw(
    pointerId: PointerId,
    down: Offset,
    layout: HighwayLayout,
    range: KeyRange,
    grid: SnapGrid,
    ticksPerQuarter: Int,
    snapAnchorAt: ((Long) -> Long)?,
    playheadTick: Long,
    keyLineY: Float,
    pixelsPerTick: Float,
    edit: HighwayEditState?,
    /** Earliest tick a note may be drawn at: negative, out in the pre-roll. */
    minTick: Long,
    onNewNoteDraft: ((Int, Long, Long) -> NoteDraft?)?,
    onInsertNote: ((NoteDraft, Long) -> Unit)?,
    onKeyTapped: ((Int) -> Unit)?,
) {
    val pitch = HighwayHitTest.laneAt(layout, down.x, range.low, range.high) ?: return
    val startTick = HighwayHitTest.tickAt(down.y, playheadTick, keyLineY, pixelsPerTick)
    val anchor = snapAnchorAt?.invoke(startTick) ?: 0L
    val length = NoteDrag.newNoteLength(grid, ticksPerQuarter)

    val seed = onNewNoteDraft?.invoke(pitch, startTick, length) ?: return
    var current = NoteDrag.drawn(
        pitch = pitch,
        startTick = startTick,
        toTick = startTick + length,
        velocity = seed.velocity,
        hand = seed.hand,
        grid = grid,
        ticksPerQuarter = ticksPerQuarter,
        anchor = anchor,
        minTick = minTick,
    )
    edit?.showGhost(HighwayEditState.NO_DRAG, current)
    onKeyTapped?.invoke(pitch)

    var travelled = Offset.Zero
    drag(pointerId) { change ->
        travelled += change.positionChange()
        val toTick = startTick + (-travelled.y / pixelsPerTick).toLong() + length
        current = NoteDrag.drawn(
            pitch = pitch,
            startTick = startTick,
            toTick = toTick,
            velocity = seed.velocity,
            hand = seed.hand,
            grid = grid,
            ticksPerQuarter = ticksPerQuarter,
            anchor = anchor,
            minTick = minTick,
        )
        edit?.showGhost(HighwayEditState.NO_DRAG, current)
        change.consume()
    }

    edit?.clearGhost()
    onInsertNote?.invoke(current, NoteDrag.minLengthTicks(grid, ticksPerQuarter))
}

/** Look-ahead with any live pinch folded in, in beats. */
private fun effectiveLookAheadBeats(settings: ScaffoldSettings, edit: HighwayEditState?): Float {
    val zoom = edit?.zoom?.floatValue ?: 1f
    return (settings.lookAheadBeats * zoom).coerceIn(MIN_LOOK_AHEAD_BEATS, MAX_LOOK_AHEAD_BEATS)
}

private fun beatTicks(model: HighwayModel): Float {
    val ppq = model.piece.tempoMap.ticksPerQuarter
    val signature = model.piece.timeSignatureAt(0)
    return signature.ticksPerBeat(ppq).coerceAtLeast(1L).toFloat()
}

/** Faint vertical bands behind black keys, so the lanes read as a keyboard. */
private fun DrawScope.drawLanes(layout: HighwayLayout, range: KeyRange, keyLineY: Float) {
    for (pitch in range.low..range.high) {
        if (!Pitch.isBlack(pitch)) continue
        if (!layout.isVisible(pitch)) continue
        drawRect(
            color = HighwayColors.laneBlack,
            topLeft = Offset(layout.leftOf(pitch), 0f),
            size = Size(layout.widthOf(pitch), keyLineY),
        )
    }
}

private fun DrawScope.drawGrid(
    model: HighwayModel,
    position: Long,
    lookAheadTicks: Float,
    pixelsPerTick: Float,
    keyLineY: Float,
    width: Float,
    labelCache: TextLayoutCache,
) {
    val horizon = position + lookAheadTicks.toLong()

    for (tick in model.beatTicks) {
        if (tick < position) continue
        if (tick > horizon) break
        val y = keyLineY - (tick - position) * pixelsPerTick
        drawRect(HighwayColors.beatLine, Offset(0f, y), Size(width, 1f))
    }

    model.barTicks.forEachIndexed { index, tick ->
        if (tick < position || tick > horizon) return@forEachIndexed
        val y = keyLineY - (tick - position) * pixelsPerTick
        drawRect(HighwayColors.barLine, Offset(0f, y), Size(width, 1.6f))

        // Measure number in the gutter — this is what makes "loop bars 17–24"
        // something you can actually see rather than guess at.
        val label = labelCache.get(
            model.barNumbers.getOrElse(index) { index + 1 }.toString(),
            BAR_LABEL_STYLE,
        )
        drawText(label, topLeft = Offset(6f, y + 3f))
    }
}

private fun DrawScope.drawLoopBracket(
    loopStartTick: Long?,
    loopEndTick: Long?,
    position: Long,
    pixelsPerTick: Float,
    keyLineY: Float,
    width: Float,
) {
    if (loopStartTick == null || loopEndTick == null) return
    val startY = keyLineY - (loopStartTick - position) * pixelsPerTick
    val endY = keyLineY - (loopEndTick - position) * pixelsPerTick
    val top = min(startY, endY).coerceAtLeast(0f)
    val bottom = max(startY, endY).coerceAtMost(keyLineY)
    if (bottom <= top) return

    drawRect(
        color = Color.White.copy(alpha = 0.05f),
        topLeft = Offset(0f, top),
        size = Size(width, bottom - top),
    )
    for (y in listOf(startY, endY)) {
        if (y in 0f..keyLineY) {
            drawRect(Color.White.copy(alpha = 0.5f), Offset(0f, y - 1f), Size(width, 2f))
        }
    }
}

private fun DrawScope.drawNotes(
    model: HighwayModel,
    position: Long,
    lookAheadTicks: Long,
    pixelsPerTick: Float,
    keyLineY: Float,
    layout: HighwayLayout,
    settings: ScaffoldSettings,
    cornerPx: Float,
    labelCache: TextLayoutCache,
    soundingOut: MutableList<Note>,
    editing: Boolean = false,
    selection: IntArray = IntArray(0),
    dragIndex: Int = HighwayEditState.NO_DRAG,
    handlePx: Float = 0f,
    groupDragging: Boolean = false,
    groupDeltaTicks: Long = 0L,
    groupDeltaPitch: Int = 0,
) {
    val horizon = position + lookAheadTicks
    val notes = model.notes
    var index = model.firstVisibleIndex(position)

    while (index < notes.size) {
        val note = notes[index]
        if (note.startTick > horizon) break
        val here = index
        index++

        if (note.endTick <= position) continue
        if (!layout.isVisible(note.pitch)) continue

        val isSounding = note.startTick <= position && note.endTick > position
        if (isSounding) soundingOut += note

        val bottom = (keyLineY - (note.startTick - position) * pixelsPerTick)
            .coerceAtMost(keyLineY)
        val top = keyLineY - (note.endTick - position) * pixelsPerTick
        val noteHeight = bottom - top
        if (noteHeight <= 0f) continue

        val left = layout.leftOf(note.pitch)
        val noteWidth = layout.widthOf(note.pitch)

        // The note being dragged is drawn faintly where it *was*, so the ghost
        // reads as a move rather than as a second note appearing.
        val beingDragged = editing && here == dragIndex
        if (beingDragged) {
            drawRoundRect(
                color = HighwayColors.editGhostOrigin,
                topLeft = Offset(left, top),
                size = Size(noteWidth, noteHeight),
                cornerRadius = CornerRadius(cornerPx),
            )
            continue
        }

        val color = NoteColors.forNote(note.pitch, note.hand, settings.colorMode, isSounding)

        // A sounding note gets a soft halo so the eye is drawn to what is
        // happening *now*, which is the moment that matters when playing along.
        if (isSounding) {
            drawRoundRect(
                color = color.copy(alpha = 0.28f),
                topLeft = Offset(left - 4f, top - 4f),
                size = Size(noteWidth + 8f, noteHeight + 8f),
                cornerRadius = CornerRadius(cornerPx + 2f),
            )
        }

        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(noteWidth, noteHeight),
            cornerRadius = CornerRadius(cornerPx),
        )

        // A brighter cap on the leading edge makes long held notes read as one
        // object rather than a featureless bar.
        if (noteHeight > 8f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(left, bottom - 3f),
                size = Size(noteWidth, 3f),
                cornerRadius = CornerRadius(1.5f),
            )
        }

        if (editing && selection.contains(here)) {
            if (groupDragging) {
                // The whole selection is on the move: show where it came from,
                // faintly, and where it is going, brightly — the same
                // origin-and-ghost reading a single note gets.
                drawRoundRect(
                    color = HighwayColors.editGhostOrigin,
                    topLeft = Offset(left, top),
                    size = Size(noteWidth, noteHeight),
                    cornerRadius = CornerRadius(cornerPx),
                )
                drawGhost(
                    ghost = GhostNote(
                        pitch = note.pitch + groupDeltaPitch,
                        startTick = note.startTick + groupDeltaTicks,
                        endTick = note.endTick + groupDeltaTicks,
                    ),
                    position = position,
                    pixelsPerTick = pixelsPerTick,
                    keyLineY = keyLineY,
                    layout = layout,
                    cornerPx = cornerPx,
                )
            } else {
                // Grips only make sense on a lone note: two of them on each of a
                // dozen selected notes is clutter, and resizing a group is not an
                // operation this editor has.
                drawSelection(
                    left, top, noteWidth, noteHeight, cornerPx,
                    handlePx = if (selection.size == 1) handlePx else 0f,
                )
            }
        }

        drawNoteAnnotations(
            note = note,
            left = left,
            top = top,
            noteWidth = noteWidth,
            noteHeight = noteHeight,
            settings = settings,
            labelCache = labelCache,
        )
    }
}

/**
 * The selected note's outline and its two grips.
 *
 * The grips are only drawn when the note is tall enough for them to be aimable —
 * the same threshold [HighwayHitTest.zoneAt] uses to decide whether they exist
 * at all, so what is drawn and what responds to a finger cannot disagree.
 */
private fun DrawScope.drawSelection(
    left: Float,
    top: Float,
    noteWidth: Float,
    noteHeight: Float,
    cornerPx: Float,
    handlePx: Float,
) {
    drawRoundRect(
        color = HighwayColors.editSelection,
        topLeft = Offset(left - 1.5f, top - 1.5f),
        size = Size(noteWidth + 3f, noteHeight + 3f),
        cornerRadius = CornerRadius(cornerPx + 1.5f),
        style = Stroke(width = SELECTION_STROKE_PX),
    )

    if (!HighwayHitTest.hasGrips(top, top + noteHeight, handlePx)) return
    val grip = handlePx.coerceAtMost(noteHeight / 3f)
    val inset = noteWidth * 0.25f
    for (y in listOf(top + grip / 2f, top + noteHeight - grip / 2f)) {
        drawRoundRect(
            color = HighwayColors.editSelection,
            topLeft = Offset(left + inset, y - GRIP_BAR_PX / 2f),
            size = Size(noteWidth - inset * 2f, GRIP_BAR_PX),
            cornerRadius = CornerRadius(GRIP_BAR_PX / 2f),
        )
    }
}

/** The note as the finger currently has it, drawn over everything else. */
private fun DrawScope.drawGhost(
    ghost: GhostNote,
    position: Long,
    pixelsPerTick: Float,
    keyLineY: Float,
    layout: HighwayLayout,
    cornerPx: Float,
) {
    if (!layout.isVisible(ghost.pitch)) return
    val bottom = keyLineY - (ghost.startTick - position) * pixelsPerTick
    val top = keyLineY - (ghost.endTick - position) * pixelsPerTick
    val height = bottom - top
    if (height <= 0f) return

    val left = layout.leftOf(ghost.pitch)
    val width = layout.widthOf(ghost.pitch)

    drawRoundRect(
        color = HighwayColors.editGhost,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(cornerPx),
    )
    drawRoundRect(
        color = HighwayColors.editSelection,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(cornerPx),
        style = Stroke(width = SELECTION_STROKE_PX),
    )
}

/**
 * The subdivisions a note will snap to.
 *
 * Drawn only between the beat lines the grid already draws, and skipped
 * entirely when the steps would be closer together than a couple of pixels —
 * a solid grey wash is not a grid, it is just a darker background.
 */
private fun DrawScope.drawSnapLines(
    model: HighwayModel,
    grid: SnapGrid,
    position: Long,
    lookAheadTicks: Long,
    pixelsPerTick: Float,
    keyLineY: Float,
    width: Float,
) {
    if (grid.isFree) return
    val ppq = model.piece.tempoMap.ticksPerQuarter
    val unit = grid.unitTicks(ppq)
    if (unit <= 0L) return
    if (unit * pixelsPerTick < MIN_SNAP_LINE_GAP_PX) return

    val horizon = position + lookAheadTicks
    var tick = (position / unit) * unit
    if (tick < position) tick += unit
    var drawn = 0
    while (tick <= horizon && drawn < MAX_SNAP_LINES) {
        val y = keyLineY - (tick - position) * pixelsPerTick
        drawRect(HighwayColors.editSnapLine, Offset(0f, y), Size(width, 1f))
        tick += unit
        drawn++
    }
}

private fun DrawScope.drawNoteAnnotations(
    note: Note,
    left: Float,
    top: Float,
    noteWidth: Float,
    noteHeight: Float,
    settings: ScaffoldSettings,
    labelCache: TextLayoutCache,
) {
    if (!settings.noteNamesOnHighway && !settings.showFingering) return

    val text = buildString {
        if (settings.noteNamesOnHighway && settings.noteNameStyle != NoteNameStyle.OFF) {
            append(settings.noteNameStyle.format(note.pitch))
        }
        if (settings.showFingering && note.finger != null) {
            if (isNotEmpty()) append(' ')
            append(note.finger)
        }
    }
    if (text.isEmpty()) return

    val style = if (Pitch.isBlack(note.pitch)) NOTE_LABEL_LIGHT else NOTE_LABEL_DARK
    val label = labelCache.get(text, style)
    // Only annotate when it will actually be legible; a squeezed half-glyph is
    // worse than nothing.
    if (label.size.width + 4 > noteWidth || label.size.height + 4 > noteHeight) return

    drawText(
        label,
        topLeft = Offset(
            left + (noteWidth - label.size.width) / 2f,
            top + noteHeight - label.size.height - 3f,
        ),
    )
}

private fun DrawScope.drawKeyboard(
    layout: HighwayLayout,
    range: KeyRange,
    keyLineY: Float,
    keyboardHeight: Float,
    sounding: List<Note>,
    settings: ScaffoldSettings,
    labelCache: TextLayoutCache,
) {
    val activeByPitch = HashMap<Int, Hand>(sounding.size)
    for (note in sounding) activeByPitch[note.pitch] = note.hand

    // White keys first; black keys sit on top of them.
    for (pitch in range.low..range.high) {
        if (!Pitch.isWhite(pitch)) continue
        if (!layout.isVisible(pitch)) continue
        val left = layout.leftOf(pitch)
        val width = layout.widthOf(pitch)
        val hand = activeByPitch[pitch]
        val color = if (hand != null) {
            NoteColors.bright(pitch, hand, settings.colorMode)
        } else {
            HighwayColors.keyWhite
        }
        drawRect(color, Offset(left, keyLineY), Size(width - 1f, keyboardHeight))
    }

    val blackHeight = keyboardHeight * PianoProportions.BLACK_TO_WHITE_LENGTH
    for (pitch in range.low..range.high) {
        if (!Pitch.isBlack(pitch)) continue
        if (!layout.isVisible(pitch)) continue
        val left = layout.leftOf(pitch)
        val width = layout.widthOf(pitch)
        val hand = activeByPitch[pitch]
        val color = if (hand != null) {
            NoteColors.forNote(pitch, hand, settings.colorMode, sounding = true)
        } else {
            HighwayColors.keyBlack
        }
        drawRect(color, Offset(left, keyLineY), Size(width, blackHeight))
    }

    if (!settings.showOctaveLabels) return

    // Octave labels anchor reading — "that's C4" is the landmark everything else
    // is measured from. Middle C gets a distinct marker for the same reason.
    for (pitch in range.octaveMarkers()) {
        if (!layout.isVisible(pitch)) continue
        val label = labelCache.get(Pitch.nameWithOctave(pitch), OCTAVE_LABEL_STYLE)
        val left = layout.leftOf(pitch)
        val width = layout.widthOf(pitch)
        if (label.size.width + 2 > width) continue
        drawText(
            label,
            topLeft = Offset(
                left + (width - label.size.width) / 2f,
                keyLineY + keyboardHeight - label.size.height - 4f,
            ),
        )
        if (pitch == Pitch.MIDDLE_C) {
            drawCircle(
                color = HighwayColors.middleCMarker,
                radius = min(width * 0.14f, 5f),
                center = Offset(left + width / 2f, keyLineY + keyboardHeight - label.size.height - 12f),
            )
        }
    }
}

/**
 * Caches laid-out text.
 *
 * Note names and measure numbers come from a tiny fixed alphabet, so a plain map
 * keyed on the string is enough and the cache cannot grow without bound in
 * practice. Without it, text measurement dominates the frame budget — it costs
 * far more than the rectangles everyone assumes are the expensive part.
 */
private class TextLayoutCache(private val measurer: TextMeasurer) {
    private val cache = HashMap<Pair<String, TextStyle>, TextLayoutResult>()

    fun get(text: String, style: TextStyle): TextLayoutResult =
        cache.getOrPut(text to style) { measurer.measure(text, style) }
}

/**
 * Bounds on how deep the drawn keyboard gets.
 *
 * The floor keeps narrow keys from collapsing into a strip you cannot aim at;
 * the ceiling — and the share of the pane — keep a wide-key layout from eating
 * the runway the notes need to fall down and be read on the way.
 */
private val KEYBOARD_MIN_DEPTH = 78.dp
private val KEYBOARD_MAX_DEPTH = 168.dp
private const val KEYBOARD_MAX_SHARE = 0.34f

/**
 * How deep a note's start and end grips are.
 *
 * Roughly a fingertip. A note shorter than three of these has no grips at all —
 * see [HighwayHitTest.zoneAt]; a four-pixel grip is not a control, it is a coin
 * toss between moving the note and resizing it.
 */
private val GRIP_DEPTH = 20.dp

/** Smallest a note may be drawn and still be reliably hittable. */
private val MIN_TOUCH_TARGET = 24.dp

/**
 * How far the pinch may take the look-ahead.
 *
 * One beat over the height of the pane makes a 1/32 note about eighty pixels
 * tall, which is as fine as this editor ever needs to be aimed.
 */
private const val MIN_LOOK_AHEAD_BEATS = 1f
private const val MAX_LOOK_AHEAD_BEATS = 16f

/** Long enough not to fire on a slow tap, short enough not to feel stuck. */
private const val LONG_PRESS_NANOS = 400_000_000L

private const val SELECTION_STROKE_PX = 2.5f
private const val GRIP_BAR_PX = 3f
private const val EDIT_BORDER_PX = 3f

/**
 * How wide the fast-seek strips are, and how much of the pane they may take.
 *
 * The share is the guard that matters: two fixed-width strips would eat a large
 * fraction of a narrow highway in a split pane, and a highway you cannot edit at
 * the edges is a worse trade than a strip that is slightly harder to hit.
 */
private val EDGE_STRIP_WIDTH = 28.dp
private const val MAX_STRIP_SHARE = 0.08f
private const val EDGE_STRIP_MARKER_PX = 3f

/** Below this, subdivision lines stop being a grid and become a grey wash. */
private const val MIN_SNAP_LINE_GAP_PX = 6f
private const val MAX_SNAP_LINES = 400

private val NOTE_LABEL_DARK = TextStyle(
    color = Color(0xFF14161C),
    fontSize = 10.sp,
    fontWeight = FontWeight.SemiBold,
)

private val NOTE_LABEL_LIGHT = TextStyle(
    color = Color(0xFFF2F4F8),
    fontSize = 10.sp,
    fontWeight = FontWeight.SemiBold,
)

private val BAR_LABEL_STYLE = TextStyle(
    color = Color(0xFF6F7A90),
    fontSize = 10.sp,
)

private val OCTAVE_LABEL_STYLE = TextStyle(
    color = Color(0xFF7A8496),
    fontSize = 9.sp,
    fontWeight = FontWeight.Medium,
)
