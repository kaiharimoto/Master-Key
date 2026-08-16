package dev.kaiharimoto.masterkey.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.ui.score.ScorePane
import dev.kaiharimoto.masterkey.ui.theme.HandColors
import dev.kaiharimoto.masterkey.ui.theme.HighwayColors
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    songId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(songId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(HighwayColors.background),
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null -> ErrorState(state.error!!, onBack)

            else -> PlayerContent(state, viewModel, onBack)
        }
    }
}

@Composable
private fun ErrorState(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            FilledTonalIconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
        }
    }
}

@Composable
private fun PlayerContent(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val model = state.model ?: return

    // The score/highway split is draggable. On a tablet in landscape both panes
    // are comfortable at once; in portrait the highway wants most of the height.
    var scoreWeight by remember { mutableFloatStateOf(0.42f) }
    val showScore = state.showScore && state.hasScore

    // Status bar is handled here; the transport bar consumes the navigation bar
    // itself so it can keep its background running to the bottom edge.
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        TopBar(state, viewModel, onBack)

        Column(Modifier.weight(1f)) {
            if (showScore) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(scoreWeight.coerceIn(0.15f, 0.75f)),
                ) {
                    ScorePane(
                        scoreXml = state.scoreXml,
                        piece = model.piece,
                        scaffold = state.settings,
                        positionProvider = viewModel::positionTicks,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                SplitHandle(
                    onDrag = { delta -> scoreWeight = (scoreWeight + delta).coerceIn(0.15f, 0.75f) },
                )
            }

            NoteHighway(
                model = model,
                range = state.range,
                settings = state.settings,
                positionProvider = viewModel::positionTicks,
                loopStartTick = state.loop?.startTick,
                loopEndTick = state.loop?.endTick,
                onSeekToTick = viewModel::seekTo,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (showScore) (1f - scoreWeight).coerceIn(0.25f, 0.85f) else 1f),
            )
        }

        TransportBar(state, viewModel)
    }
}

@Composable
private fun SplitHandle(onDrag: (Float) -> Unit) {
    var height by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .background(Color(0xFF11141A))
            .pointerInput(Unit) {
                height = size.height.toFloat()
                detectVerticalDragGestures { _, dragAmount ->
                    // Convert to a fraction of the container so the drag tracks
                    // the finger regardless of screen size.
                    onDrag(dragAmount / 900f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(44.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF3A4152)),
        )
    }
}

@Composable
private fun TopBar(state: PlayerUiState, viewModel: PlayerViewModel, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF11141A))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFFB6BDCA))
        }

        Column(Modifier.weight(1f)) {
            Text(
                state.song?.title.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE3E6EC),
                maxLines = 1,
            )
            Text(
                "Bar ${state.currentBar} of ${state.model?.barCount ?: 1}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF7A8496),
            )
        }

        if (state.hasScore) {
            IconButton(onClick = viewModel::toggleScore) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = "Sheet music",
                    tint = if (state.showScore) HandColors.amber else Color(0xFF7A8496),
                )
            }
        }

        ScaffoldMenu(state, viewModel)
    }
}

@Composable
private fun ScaffoldMenu(state: PlayerUiState, viewModel: PlayerViewModel) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.Visibility,
                contentDescription = "Reading aids",
                tint = Color(0xFFB6BDCA),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                "Reading aids",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ScaffoldLevel.entries.forEach { level ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(level.label)
                            Text(
                                level.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        viewModel.setScaffoldLevel(level)
                        expanded = false
                    },
                    trailingIcon = {
                        if (state.settings.level == level) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                        }
                    },
                )
            }

            Text(
                "Note names",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NoteNameStyle.entries.forEach { style ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (style) {
                                NoteNameStyle.OFF -> "Off"
                                NoteNameStyle.LETTER -> "Letters (C D E)"
                                NoteNameStyle.GERMAN -> "German (C D E … H)"
                                NoteNameStyle.SOLFEGE -> "Solfège (Do Re Mi)"
                            },
                        )
                    },
                    onClick = {
                        viewModel.setNoteNameStyle(style)
                        expanded = false
                    },
                    trailingIcon = {
                        if (state.settings.noteNameStyle == style) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                        }
                    },
                )
            }

            DropdownMenuItem(
                text = {
                    Text(
                        if (state.settings.colorMode == ColorMode.BY_HAND) {
                            "Colour by pitch instead"
                        } else {
                            "Colour by hand instead"
                        },
                    )
                },
                onClick = {
                    viewModel.setColorMode(
                        if (state.settings.colorMode == ColorMode.BY_HAND) {
                            ColorMode.BY_PITCH_CLASS
                        } else {
                            ColorMode.BY_HAND
                        },
                    )
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun TransportBar(state: PlayerUiState, viewModel: PlayerViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF11141A))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilledTonalIconButton(
                onClick = viewModel::togglePlay,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = HandColors.amber,
                    contentColor = Color(0xFF241800),
                ),
                modifier = Modifier.size(46.dp),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                )
            }

            IconButton(onClick = viewModel::restart) {
                Icon(Icons.Default.Replay, contentDescription = "Restart", tint = Color(0xFFB6BDCA))
            }

            // Hand toggles carry the hand colour so the mapping to the falling
            // notes is immediate and needs no legend.
            HandChip(
                label = "R",
                muted = state.rightHandMuted,
                color = HandColors.amber,
                onClick = { viewModel.toggleHand(Hand.RIGHT) },
            )
            HandChip(
                label = "L",
                muted = state.leftHandMuted,
                color = HandColors.teal,
                onClick = { viewModel.toggleHand(Hand.LEFT) },
            )

            IconButton(onClick = viewModel::toggleMetronome) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = "Metronome",
                    tint = if (state.metronomeEnabled) HandColors.amber else Color(0xFF7A8496),
                )
            }

            IconButton(
                onClick = {
                    if (state.loop != null) viewModel.clearLoop() else viewModel.loopCurrentBar()
                },
            ) {
                Icon(
                    if (state.loop != null) Icons.Default.Close else Icons.Default.Repeat,
                    contentDescription = "Loop this bar",
                    tint = if (state.loop != null) HandColors.amber else Color(0xFF7A8496),
                )
            }

            Spacer(Modifier.weight(1f))

            TempoControl(state, viewModel)
        }
    }
}


@Composable
private fun HandChip(label: String, muted: Boolean, color: Color, onClick: () -> Unit) {
    FilterChip(
        selected = !muted,
        onClick = onClick,
        label = {
            Text(
                label,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (muted) Color(0xFF6B7385) else Color(0xFF14161C),
            )
        },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = color,
            containerColor = Color(0xFF1E222B),
        ),
        modifier = Modifier.height(34.dp),
    )
}

@Composable
private fun TempoControl(state: PlayerUiState, viewModel: PlayerViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Speed,
            contentDescription = null,
            tint = Color(0xFF7A8496),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))

        Text(
            "${(state.tempoScale * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFFE3E6EC),
            modifier = Modifier.width(46.dp),
        )

        Slider(
            value = state.tempoScale,
            onValueChange = viewModel::setTempoScale,
            valueRange = 0.25f..1.25f,
            // 20 steps of 5% — fine enough to be useful, coarse enough to hit.
            steps = 19,
            modifier = Modifier.width(180.dp),
        )

        // The drill deliberately alternates fast and slow rather than ramping up:
        // the research says alternating tempi beat a monotonic ladder.
        IconButton(onClick = viewModel::advanceTempoDrill) {
            Text(
                "↕",
                color = if (state.drillStep != null) HandColors.amber else Color(0xFF7A8496),
                fontSize = 18.sp,
            )
        }
    }
}
