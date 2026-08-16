package dev.kaiharimoto.masterkey.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kaiharimoto.masterkey.core.keyboard.KeyRange
import dev.kaiharimoto.masterkey.core.midi.Pitch
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.ui.theme.HighwayColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

    val keyboardHeightPx = with(density) { KEYBOARD_HEIGHT.toPx() }
    val cornerPx = with(density) { 3.dp.toPx() }

    Canvas(
        modifier = modifier.pointerInput(model, onSeekToTick, onKeyTapped) {
            detectTapGestures { offset ->
                val keyLineY = size.height - keyboardHeightPx
                if (offset.y >= keyLineY) {
                    onKeyTapped ?: return@detectTapGestures
                    val layout = HighwayLayout(leftWhite.value, visibleWhite.value, size.width.toFloat())
                    val isBlackRow = offset.y < keyLineY + keyboardHeightPx * 0.62f
                    layout.pitchAt(offset.x, isBlackRow, range.low, range.high)
                        ?.let(onKeyTapped)
                } else {
                    onSeekToTick ?: return@detectTapGestures
                    val ticksPerBeat = beatTicks(model)
                    val lookAhead = settings.lookAheadBeats * ticksPerBeat
                    val fraction = (keyLineY - offset.y) / keyLineY
                    val target = positionTicks.longValue + (fraction * lookAhead).toLong()
                    onSeekToTick(target.coerceAtLeast(0L))
                }
            }
        },
    ) {
        // ---- everything below runs in the DRAW phase only ----
        val position = positionTicks.longValue
        val width = size.width
        val height = size.height
        val keyLineY = height - keyboardHeightPx
        if (keyLineY <= 0f || width <= 0f) return@Canvas

        val layout = HighwayLayout(leftWhite.value, visibleWhite.value, width)
        val ticksPerBeat = beatTicks(model)
        val lookAheadTicks = (settings.lookAheadBeats * ticksPerBeat).coerceAtLeast(1f)
        val pixelsPerTick = keyLineY / lookAheadTicks

        drawRect(HighwayColors.background)

        drawLanes(layout, range, keyLineY)

        if (settings.showBeatGrid) {
            drawGrid(model, position, lookAheadTicks, pixelsPerTick, keyLineY, width, labelCache)
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
            )
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
    }
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
) {
    val horizon = position + lookAheadTicks
    val notes = model.notes
    var index = model.firstVisibleIndex(position)

    while (index < notes.size) {
        val note = notes[index]
        if (note.startTick > horizon) break
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

    val blackHeight = keyboardHeight * 0.62f
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

private val KEYBOARD_HEIGHT = 96.dp

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
