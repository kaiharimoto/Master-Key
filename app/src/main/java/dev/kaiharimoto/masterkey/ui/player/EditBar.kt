package dev.kaiharimoto.masterkey.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kaiharimoto.masterkey.core.edit.SnapGrid
import dev.kaiharimoto.masterkey.core.midi.Pitch
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.ui.theme.HandColors

/**
 * The editing toolbar, in place of the transport's controls while edit mode is on.
 *
 * Two halves, and the split is deliberate. The left is about *how* an edit
 * lands — the snap grid, undo, redo — and is always available. The right is the
 * inspector for the selected note, and is the answer to the thing touch cannot
 * do: a drag gets a note roughly where it belongs, and the nudge buttons move it
 * by exactly one grid step or exactly one semitone. Without them "very fine"
 * would mean "as fine as a fingertip", which is not fine at all.
 */
@Composable
fun EditBar(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF151A23))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Only the grid chips scroll. Undo, Redo and Delete are pinned on the
        // right: they were previously last in one long scrolling row, after seven
        // chips, so on a narrower screen the controls you reach for most started
        // off the edge entirely.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "SNAP",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF6B7385),
                    letterSpacing = 1.sp,
                )
                SnapGrid.entries.forEach { grid ->
                    FilterChip(
                        selected = state.snapGrid == grid,
                        onClick = { viewModel.setSnapGrid(grid) },
                        label = { Text(grid.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HandColors.amber,
                            selectedLabelColor = Color(0xFF241800),
                            containerColor = Color(0xFF1E222B),
                            labelColor = Color(0xFFB6BDCA),
                        ),
                        modifier = Modifier.height(TOOL_BUTTON_HEIGHT),
                    )
                }
            }

            // The lasso. Its own chip rather than a hidden gesture, because with
            // it on a one-finger drag over empty space sweeps a selection
            // instead of scrolling — a change you want to be able to see.
            FilterChip(
                selected = state.selectMode,
                onClick = { viewModel.setSelectMode(!state.selectMode) },
                label = { Text("Select", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Default.SelectAll, contentDescription = null, Modifier.size(16.dp))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = HandColors.amber,
                    selectedLabelColor = Color(0xFF241800),
                    selectedLeadingIconColor = Color(0xFF241800),
                    containerColor = Color(0xFF1E222B),
                    labelColor = Color(0xFFB6BDCA),
                ),
                modifier = Modifier.height(TOOL_BUTTON_HEIGHT),
            )

            ToolButton(
                label = "Undo",
                enabled = state.canUndo,
                onClick = viewModel::undo,
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, Modifier.size(18.dp))
            }

            ToolButton(
                label = "Redo",
                enabled = state.canRedo,
                onClick = viewModel::redo,
            ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = null, Modifier.size(18.dp))
            }

            ToolButton(
                label = "Delete",
                enabled = state.hasSelection,
                onClick = viewModel::deleteSelected,
            ) { Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(18.dp)) }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NoteInspector(state, viewModel, Modifier.weight(1f))

            if (state.hasImportedBackup) {
                TextButton(onClick = viewModel::revertToImported) {
                    Text("Revert", fontSize = 12.sp, color = Color(0xFF9AA3B4))
                }
            }

            FilledTonalButton(
                onClick = viewModel::saveEdits,
                enabled = state.editDirty,
                modifier = Modifier.height(36.dp),
            ) {
                Text(if (state.editDirty) "Save" else "Saved", fontSize = 13.sp)
            }

            IconButton(onClick = viewModel::requestLeaveEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Leave edit mode",
                    tint = Color(0xFFB6BDCA),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // The stale-notation warning goes here rather than in a menu, because a
        // sheet-music pane that silently stops matching the notes is exactly how
        // this app has looked broken before.
        if (state.hasScore && state.showScore) {
            Text(
                "Sheet music shows the imported score, not your edits.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8A7440),
            )
        }

        state.editError?.let { message ->
            Text(message, style = MaterialTheme.typography.labelSmall, color = Color(0xFFE0574B))
        }

        if (state.editSaved && state.editError == null) {
            Text(
                "Saved to ${state.song?.midiFileName.orEmpty()}.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6FCF7A),
            )
        }
    }

    if (state.confirmLeavingEdit) {
        AlertDialog(
            onDismissRequest = viewModel::cancelLeavingEdit,
            title = { Text("Save your edits?") },
            text = {
                Text(
                    "Master Key rewrites the song's MIDI from the notes on the highway. " +
                        "Anything the original file carried that Master Key doesn't read — " +
                        "sustain pedal, other instruments, text — isn't kept. The file you " +
                        "imported is stored, and Revert brings it back.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::saveAndLeaveEdit) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::discardEdits) { Text("Discard") }
                    TextButton(onClick = viewModel::cancelLeavingEdit) { Text("Keep editing") }
                }
            },
        )
    }
}

/**
 * The selected note, and the buttons that move it by an exact amount.
 *
 * Every nudge goes through the same edit command a drag does, so undo cannot
 * tell the two apart — which is what you want when you have dragged a note
 * roughly into place and then tapped the start button four times.
 */
@Composable
private fun NoteInspector(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    if (!state.hasSelection) {
        Text(
            if (state.selectMode) {
                "Drag across the notes you want, or tap them one by one."
            } else {
                "Tap a note to select it. Press and hold an empty lane to add one."
            },
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF6B7385),
            modifier = modifier,
        )
        return
    }

    val note = state.selectedNote
    val model = state.model
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // With one note selected the readouts say exactly where it is; with
        // several there is no single answer, so the count takes their place and
        // the buttons keep working on all of them.
        NudgeField(
            label = note?.let { Pitch.nameWithOctave(it.pitch) } ?: "${state.selectedNotes.size} notes",
            caption = "pitch",
            onDown = { viewModel.nudgePitch(-1) },
            onUp = { viewModel.nudgePitch(1) },
        )
        NudgeField(
            label = "8ve",
            caption = "transpose",
            onDown = { viewModel.transposeOctaves(-1) },
            onUp = { viewModel.transposeOctaves(1) },
        )
        NudgeField(
            label = if (note != null && model != null) positionLabel(model, note) else "—",
            caption = "start",
            onDown = { viewModel.nudgeStart(-1) },
            onUp = { viewModel.nudgeStart(1) },
        )
        NudgeField(
            label = note?.let { lengthLabel(it, state.snapGrid, viewModel.ticksPerQuarter) } ?: "—",
            caption = "length",
            onDown = { viewModel.nudgeLength(-1) },
            onUp = { viewModel.nudgeLength(1) },
        )
        HandPicker(state, viewModel)
    }
}

/**
 * Which hand plays the selection.
 *
 * Hands drive colour, the synth channel and whether hand-muting silences a note,
 * so getting one wrong is more than cosmetic. Setting it here also *pins* it, so
 * a linked MusicXML cannot quietly overrule the choice next time the song opens.
 */
@Composable
private fun HandPicker(state: PlayerUiState, viewModel: PlayerViewModel) {
    val hands = state.selectedNotes.map { it.hand }.distinct()
    val current = hands.singleOrNull()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Hand.entries.forEach { hand ->
            val selected = current == hand
            FilterChip(
                selected = selected,
                onClick = { viewModel.setSelectedHand(hand) },
                label = { Text(if (hand == Hand.RIGHT) "R" else "L", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor =
                        if (hand == Hand.RIGHT) HandColors.amber else HandColors.teal,
                    selectedLabelColor = Color(0xFF14161C),
                    containerColor = Color(0xFF1E222B),
                    labelColor = Color(0xFFB6BDCA),
                ),
                modifier = Modifier.height(36.dp),
            )
        }
    }
}

@Composable
private fun NudgeField(
    label: String,
    caption: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDown, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Default.Remove,
                contentDescription = "$caption down",
                tint = Color(0xFF9AA3B4),
                modifier = Modifier.size(16.dp),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE3E6EC),
            )
            Text(caption, style = MaterialTheme.typography.labelSmall, color = Color(0xFF6B7385))
        }
        IconButton(onClick = onUp, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = "$caption up",
                tint = Color(0xFF9AA3B4),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * A labelled button whose whole pill is the touch target.
 *
 * It previously wrapped an `IconButton` and a sibling `Text`, so it *looked* like
 * one control roughly 80 dp wide while only the leading 32 dp square actually
 * dispatched the click — and the parent's fixed 32 dp height constrained away
 * Material's usual expansion to a 48 dp target as well. Tapping the word did
 * nothing, which made Delete look broken rather than unreachable. One
 * `clickable` on the row, sized to the accessibility minimum, is the fix.
 */
@Composable
private fun ToolButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Color(0xFF232935) else Color(0xFF181C24))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .heightIn(min = TOOL_BUTTON_HEIGHT)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            CompositionLocalProvider(
                LocalContentColor provides
                    if (enabled) Color(0xFFB6BDCA) else Color(0xFF4E5666),
            ) { icon() }
        }
        Text(
            label,
            fontSize = 12.sp,
            color = if (enabled) Color(0xFFB6BDCA) else Color(0xFF4E5666),
        )
    }
}

/** The accessibility minimum, so every control in this bar is aimable. */
private val TOOL_BUTTON_HEIGHT = 48.dp

/** "14:3" — the bar and beat a note starts on, which is how music is talked about. */
private fun positionLabel(model: HighwayModel, note: Note): String {
    val bar = model.barNumberAt(note.startTick)
    val barTick = model.tickOfBar(bar)
    val ppq = model.piece.tempoMap.ticksPerQuarter
    val perBeat = model.piece.timeSignatureAt(note.startTick)
        .ticksPerBeat(ppq)
        .coerceAtLeast(1L)
    val beat = (note.startTick - barTick) / perBeat + 1
    return "$bar:$beat"
}

/**
 * A note's length in grid steps.
 *
 * Steps rather than ticks: "6" against a 1/16 grid says more about what the note
 * is than "720" does, and it is also the unit the buttons beside it move in.
 */
private fun lengthLabel(note: Note, grid: SnapGrid, ticksPerQuarter: Int): String {
    val unit = if (grid.isFree) 1L else grid.unitTicks(ticksPerQuarter)
    if (unit <= 0L) return "${note.durationTicks}"
    val steps = note.durationTicks.toDouble() / unit
    return if (grid.isFree) {
        "${note.durationTicks}t"
    } else if (steps == steps.toLong().toDouble()) {
        "${steps.toLong()}×${grid.label}"
    } else {
        String.format("%.1f×%s", steps, grid.label)
    }
}
