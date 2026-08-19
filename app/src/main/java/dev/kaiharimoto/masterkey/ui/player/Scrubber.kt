package dev.kaiharimoto.masterkey.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import dev.kaiharimoto.masterkey.ui.theme.HandColors

/**
 * The position scrubber.
 *
 * Drawn on a [Canvas] rather than built from a `Slider` for the same reason the
 * highway is: the playhead is deliberately not Compose state. Routing it through
 * one would recompose this row sixty times a second for the whole length of a
 * piece. Here the position is written into a snapshot long once per frame and
 * read only inside the draw lambda, so the composable never recomposes while
 * playing at all.
 *
 * Dragging does not seek. [PlayerViewModel.updateScrub] moves a position the
 * highway and the score cursor also read, so the whole player scrolls under the
 * finger, and the audio engine — which discards and rebuilds its event schedule
 * on every seek — is asked exactly once, when the finger lifts.
 */
@Composable
fun Scrubber(
    model: HighwayModel,
    positionProvider: () -> Long,
    endTick: Long,
    loopStartTick: Long?,
    loopEndTick: Long?,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = endTick.coerceAtLeast(1L)

    // Written once per frame, read only in the draw lambda and the readouts.
    val positionTicks = remember { mutableLongStateOf(0L) }
    LaunchedEffect(positionProvider) {
        while (true) {
            withFrameNanos { positionTicks.longValue = positionProvider() }
        }
    }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(TRACK_TOUCH_HEIGHT)
                // One gesture handler rather than a tap detector plus a drag
                // detector, which would fight over the same pointer. Touching
                // down jumps immediately and any movement keeps scrubbing, so a
                // tap and a drag are the same gesture with different lengths.
                .pointerInput(span) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        onScrubStart()
                        onScrub(tickAt(down.position.x, size.width.toFloat(), span))

                        while (true) {
                            val change = awaitPointerEvent().changes
                                .firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                break
                            }
                            if (change.positionChanged()) {
                                onScrub(tickAt(change.position.x, size.width.toFloat(), span))
                                change.consume()
                            }
                        }
                        onScrubEnd()
                    }
                },
        ) {
            val width = size.width
            val midY = size.height / 2f
            val trackHeight = 5f.dp.toPx()
            val top = midY - trackHeight / 2f
            val radius = CornerRadius(trackHeight / 2f)

            drawRoundRect(
                color = Color(0xFF262C38),
                topLeft = Offset(0f, top),
                size = Size(width, trackHeight),
                cornerRadius = radius,
            )

            // The loop region, so it is obvious at a glance which slice of the
            // piece is repeating — and where you are inside it.
            if (loopStartTick != null && loopEndTick != null && loopEndTick > loopStartTick) {
                val left = width * (loopStartTick.toFloat() / span)
                val right = width * (loopEndTick.toFloat() / span)
                drawRoundRect(
                    color = HandColors.teal.copy(alpha = 0.30f),
                    topLeft = Offset(left, top),
                    size = Size((right - left).coerceAtLeast(2f), trackHeight),
                    cornerRadius = radius,
                )
            }

            // Bar ticks, thinned out so a long piece does not turn the track into
            // a solid block. Every bar on a short study, every eighth on a sonata.
            val bars = model.barTicks
            if (bars.size > 1) {
                val stride = ((bars.size * TICK_MIN_SPACING_PX) / width.coerceAtLeast(1f))
                    .toInt().coerceAtLeast(1)
                var index = 0
                while (index < bars.size) {
                    val x = width * (bars[index].toFloat() / span)
                    drawRect(
                        color = Color(0x33FFFFFF),
                        topLeft = Offset(x, top),
                        size = Size(1f, trackHeight),
                    )
                    index += stride
                }
            }

            val position = positionTicks.longValue.coerceIn(0L, span)
            val playedWidth = width * (position.toFloat() / span)
            drawRoundRect(
                color = HandColors.amber,
                topLeft = Offset(0f, top),
                size = Size(playedWidth, trackHeight),
                cornerRadius = radius,
            )
            drawCircle(
                color = HandColors.amber,
                radius = 7f.dp.toPx(),
                center = Offset(playedWidth, midY),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val position = positionTicks.longValue.coerceIn(0L, span)
            Text(
                formatDuration(model.piece.tempoMap.tickToMicros(position)),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFB6BDCA),
            )
            Text(
                "  Bar ${model.barNumberAt(position)} of ${model.barCount}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6B7385),
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDuration(model.piece.durationMicros),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6B7385),
            )
        }
    }
}

private fun tickAt(x: Float, width: Float, span: Long): Long =
    ((x / width.coerceAtLeast(1f)).coerceIn(0f, 1f) * span).toLong()

/** `m:ss`, shared with the library list so a song reads the same in both places. */
fun formatDuration(micros: Long): String {
    val totalSeconds = micros / 1_000_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Touch target, deliberately taller than the 5 dp track it draws. */
private val TRACK_TOUCH_HEIGHT = 26.dp

/** Below this spacing, bar ticks stop reading as ticks and start reading as fill. */
private const val TICK_MIN_SPACING_PX = 9f
