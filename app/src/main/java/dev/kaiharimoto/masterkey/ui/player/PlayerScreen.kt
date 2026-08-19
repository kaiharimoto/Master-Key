package dev.kaiharimoto.masterkey.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
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
import dev.kaiharimoto.masterkey.data.ScorePlacement
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

    // Shortcuts are live only while the player is on screen; the dispatcher sits
    // in the activity because a focused WebView would otherwise eat the keys.
    val dispatcher = LocalKeyDispatcher.current
    DisposableEffect(dispatcher, viewModel, onBack) {
        val handler: (android.view.KeyEvent) -> Boolean = { event ->
            handleShortcut(event, viewModel, onBack)
        }
        dispatcher?.register(handler)
        onDispose { dispatcher?.unregister(handler) }
    }

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

        if (state.showShortcuts) ShortcutLegend(onDismiss = viewModel::hideShortcuts)
    }
}

/**
 * Maps a hardware key to a transport action.
 *
 * Returns true when the key was ours, so it goes no further. Auto-repeat is
 * allowed through for seeking and tempo — holding an arrow should scan — but
 * suppressed for the toggles, where a held key would flap the setting.
 */
private fun handleShortcut(
    event: android.view.KeyEvent,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
): Boolean {
    val state = viewModel.state.value
    val repeated = event.repeatCount > 0
    val shifted = event.isShiftPressed

    fun once(action: () -> Unit): Boolean {
        if (!repeated) action()
        return true
    }

    // With something on top, nothing behind it should fire — pressing a key to
    // read what it does, and having it happen out of sight, is a nasty surprise.
    if (state.showViewerSettings) {
        return when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_V,
            android.view.KeyEvent.KEYCODE_ESCAPE,
            -> once(viewModel::hideViewerSettings)
            else -> true
        }
    }
    if (state.showShortcuts) {
        return when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_I,
            android.view.KeyEvent.KEYCODE_ESCAPE,
            -> once(viewModel::hideShortcuts)
            else -> true
        }
    }

    return when (event.keyCode) {
        android.view.KeyEvent.KEYCODE_SPACE -> once(viewModel::togglePlay)
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
            viewModel.seekByBars(if (shifted) -4 else -1); true
        }
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
            viewModel.seekByBars(if (shifted) 4 else 1); true
        }
        android.view.KeyEvent.KEYCODE_MOVE_HOME -> once(viewModel::restart)
        android.view.KeyEvent.KEYCODE_MOVE_END -> once(viewModel::seekToEnd)
        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
            viewModel.setTempoScale(state.tempoScale + TEMPO_STEP); true
        }
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
            viewModel.setTempoScale(state.tempoScale - TEMPO_STEP); true
        }
        android.view.KeyEvent.KEYCODE_R -> once { viewModel.toggleHand(Hand.RIGHT) }
        android.view.KeyEvent.KEYCODE_L -> once { viewModel.toggleHand(Hand.LEFT) }
        android.view.KeyEvent.KEYCODE_C -> once {
            if (state.loop != null) viewModel.clearLoop() else viewModel.loopCurrentBar()
        }
        android.view.KeyEvent.KEYCODE_M -> once(viewModel::toggleMetronome)
        android.view.KeyEvent.KEYCODE_N -> once(viewModel::toggleCountIn)
        android.view.KeyEvent.KEYCODE_S -> once(viewModel::toggleScore)
        android.view.KeyEvent.KEYCODE_H -> once(viewModel::toggleHighway)
        android.view.KeyEvent.KEYCODE_V -> once(viewModel::toggleViewerSettings)
        android.view.KeyEvent.KEYCODE_EQUALS,
        android.view.KeyEvent.KEYCODE_PLUS,
        -> {
            viewModel.setScoreZoom(state.scoreZoom + SCORE_ZOOM_STEP); true
        }
        android.view.KeyEvent.KEYCODE_MINUS -> {
            viewModel.setScoreZoom(state.scoreZoom - SCORE_ZOOM_STEP); true
        }
        android.view.KeyEvent.KEYCODE_LEFT_BRACKET -> once(viewModel::moveScoreLeft)
        android.view.KeyEvent.KEYCODE_RIGHT_BRACKET -> once(viewModel::moveScoreRight)
        android.view.KeyEvent.KEYCODE_D -> once(viewModel::advanceTempoDrill)
        android.view.KeyEvent.KEYCODE_A -> once {
            val levels = ScaffoldLevel.entries
            val next = levels[(levels.indexOf(state.settings.level) + 1) % levels.size]
            viewModel.setScaffoldLevel(next)
        }
        android.view.KeyEvent.KEYCODE_I -> once(viewModel::toggleShortcuts)
        android.view.KeyEvent.KEYCODE_ESCAPE -> once(onBack)
        else -> false
    }
}

/** 5% a press: fine enough to creep up on a passage, coarse enough to be felt. */
private const val TEMPO_STEP = 0.05f

/** Big enough that one press visibly changes how many bars fit on a line. */
private const val SCORE_ZOOM_STEP = 10

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
    val showHighway = state.showHighway || !showScore
    val bothPanes = showScore && showHighway
    val placement = state.scorePlacement

    // movableContentOf, not plain lambdas: moving the score from above the
    // highway to beside it swaps a Column for a Row, and an ordinary composable
    // called from a different place in the tree is a *new* composable — its
    // remembered state is thrown away and rebuilt. For the score pane that means
    // destroying the WebView and reloading seven megabytes of Verovio every time
    // the layout is nudged. This keeps both panes alive across the move.
    val score = remember {
        movableContentOf { paneModifier: Modifier ->
            Box(paneModifier) {
                ScorePane(
                    scoreXml = state.scoreXml,
                    piece = model.piece,
                    scaffold = state.settings,
                    positionProvider = viewModel::positionTicks,
                    modifier = Modifier.fillMaxSize(),
                    zoom = state.scoreZoom,
                    loadError = state.scoreError,
                    onEvent = viewModel::onScoreEvent,
                )
            }
        }
    }
    val highway = remember {
        movableContentOf { paneModifier: Modifier ->
            NoteHighway(
                model = model,
                range = state.range,
                settings = state.settings,
                positionProvider = viewModel::positionTicks,
                loopStartTick = state.loop?.startTick,
                loopEndTick = state.loop?.endTick,
                onSeekToTick = viewModel::seekTo,
                modifier = paneModifier,
            )
        }
    }
    val clampedScore = scoreWeight.coerceIn(0.15f, 0.75f)
    val clampedHighway = (1f - scoreWeight).coerceIn(0.25f, 0.85f)
    val onSplitDrag = { delta: Float -> scoreWeight = (scoreWeight + delta).coerceIn(0.15f, 0.75f) }

    // Status bar is handled here; the transport bar consumes the navigation bar
    // itself so it can keep its background running to the bottom edge.
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        TopBar(state, viewModel, onBack)

        Box(Modifier.weight(1f)) {
            when {
                !bothPanes && showScore -> score(Modifier.fillMaxSize())

                !bothPanes -> highway(Modifier.fillMaxSize())

                placement == ScorePlacement.TOP -> Column(Modifier.fillMaxSize()) {
                    score(Modifier.fillMaxWidth().weight(clampedScore))
                    SplitHandle(vertical = true, onDrag = onSplitDrag)
                    highway(Modifier.fillMaxWidth().weight(clampedHighway))
                }

                else -> Row(Modifier.fillMaxSize()) {
                    // Side by side, the drag runs the other way — and on the
                    // right the score is after the handle, so widening it means
                    // dragging left. Negating keeps "drag towards the score to
                    // make it bigger" true in both arrangements.
                    if (placement == ScorePlacement.LEFT) {
                        score(Modifier.fillMaxHeight().weight(clampedScore))
                        SplitHandle(vertical = false, onDrag = onSplitDrag)
                        highway(Modifier.fillMaxHeight().weight(clampedHighway))
                    } else {
                        highway(Modifier.fillMaxHeight().weight(clampedHighway))
                        SplitHandle(vertical = false, onDrag = { onSplitDrag(-it) })
                        score(Modifier.fillMaxHeight().weight(clampedScore))
                    }
                }
            }
        }

        TransportBar(state, viewModel, model)
    }

    if (state.showViewerSettings) {
        ViewerSettingsSheet(state, viewModel, onDismiss = viewModel::hideViewerSettings)
    }
}

/**
 * The draggable divider between the two panes.
 *
 * [vertical] describes the split, not the handle: a vertical split stacks the
 * panes and is dragged up and down, a horizontal one sits them side by side.
 */
@Composable
private fun SplitHandle(vertical: Boolean, onDrag: (Float) -> Unit) {
    Box(
        Modifier
            .then(if (vertical) Modifier.fillMaxWidth().height(14.dp) else Modifier.fillMaxHeight().width(14.dp))
            .background(Color(0xFF11141A))
            .pointerInput(vertical) {
                // Converted to a fraction of a nominal container so the drag
                // tracks the finger at roughly the same rate on any screen.
                if (vertical) {
                    detectVerticalDragGestures { _, dragAmount -> onDrag(dragAmount / 900f) }
                } else {
                    detectHorizontalDragGestures { _, dragAmount -> onDrag(dragAmount / 1400f) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(if (vertical) Modifier.width(44.dp).height(3.dp) else Modifier.width(3.dp).height(44.dp))
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

        IconButton(onClick = viewModel::toggleViewerSettings) {
            Icon(
                Icons.Default.Tune,
                contentDescription = "View settings",
                tint = if (state.showViewerSettings) HandColors.amber else Color(0xFFB6BDCA),
            )
        }

        // The shortcuts exist whether or not a keyboard is attached, so they
        // need a way in that does not require already knowing the key.
        IconButton(onClick = viewModel::toggleShortcuts) {
            Text(
                "⌘",
                color = if (state.showShortcuts) HandColors.amber else Color(0xFF7A8496),
                fontSize = 17.sp,
            )
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
                    Text(if (state.countInEnabled) "Count-in: one bar" else "Count-in: off")
                },
                onClick = {
                    viewModel.toggleCountIn()
                    expanded = false
                },
                trailingIcon = {
                    if (state.countInEnabled) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                    }
                },
            )

            if (state.hasScore) {
                Text(
                    "Sheet music",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ScorePlacement.entries.forEach { placement ->
                    DropdownMenuItem(
                        text = { Text(placement.label) },
                        onClick = {
                            viewModel.setScorePlacement(placement)
                            expanded = false
                        },
                        trailingIcon = {
                            if (state.scorePlacement == placement) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                            }
                        },
                    )
                }
            }

            DropdownMenuItem(
                text = { Text("Keyboard shortcuts") },
                onClick = {
                    viewModel.toggleShortcuts()
                    expanded = false
                },
            )

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
private fun TransportBar(state: PlayerUiState, viewModel: PlayerViewModel, model: HighwayModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF11141A))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Scrubber(
            model = model,
            positionProvider = viewModel::positionTicks,
            endTick = model.piece.endTick,
            loopStartTick = state.loop?.startTick,
            loopEndTick = state.loop?.endTick,
            onScrubStart = viewModel::beginScrub,
            onScrub = viewModel::updateScrub,
            onScrubEnd = viewModel::endScrub,
            modifier = Modifier.padding(bottom = 4.dp),
        )

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
