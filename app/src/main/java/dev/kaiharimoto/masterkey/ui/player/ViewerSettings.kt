package dev.kaiharimoto.masterkey.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kaiharimoto.masterkey.data.AppSettings
import dev.kaiharimoto.masterkey.data.ScorePlacement
import kotlin.math.roundToInt

/**
 * Everything about how the player *looks*, in one place you can reach mid-piece.
 *
 * Kept apart from the reading-aids menu on purpose. That menu is pedagogical —
 * it decides how much the app tells you, and it is meant to be turned down over
 * time. This is about eyesight, screen size and how far away the tablet is
 * propped, which is not a thing anyone should have to relearn per song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerSettingsSheet(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161A22),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "View",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE3E6EC),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Heading("Panes")
            SettingSwitch(
                label = "Falling notes",
                // The last remaining pane cannot be switched off, so the switch
                // that would empty the screen is disabled rather than ignored —
                // a control that silently does nothing is worse than one that
                // visibly cannot.
                enabled = state.showScore && state.hasScore,
                checked = state.showHighway,
                onChange = viewModel::setShowHighway,
            )
            if (state.hasScore) {
                SettingSwitch(
                    label = "Sheet music",
                    enabled = state.showHighway,
                    checked = state.showScore,
                    onChange = viewModel::setShowScore,
                )
            }

            if (state.hasScore && state.showScore) {
                Spacer(Modifier.height(10.dp))
                Heading("Sheet music")

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Position",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB6BDCA),
                        modifier = Modifier.width(96.dp),
                    )
                    ScorePlacement.entries.forEach { placement ->
                        FilterChip(
                            selected = state.scorePlacement == placement,
                            onClick = { viewModel.setScorePlacement(placement) },
                            label = { Text(placement.label, fontSize = 13.sp) },
                        )
                    }
                }

                // Not a magnifier: a bigger engraving also fits fewer bars on a
                // line, so this is really "how much of the piece do you want to
                // see at once", which is why the readout says both.
                SettingSlider(
                    label = "Size",
                    value = state.scoreZoom.toFloat(),
                    range = AppSettings.MIN_SCORE_ZOOM.toFloat()..AppSettings.MAX_SCORE_ZOOM.toFloat(),
                    steps = 16,
                    readout = "${state.scoreZoom}%",
                    onChange = { viewModel.setScoreZoom(it.roundToInt()) },
                )
            }

            if (state.showHighway) {
                Spacer(Modifier.height(10.dp))
                Heading("Keyboard")

                val auto = state.keyboardWhiteKeys == AppSettings.KEYBOARD_AUTO
                SettingSwitch(
                    label = "Fit the piece",
                    checked = auto,
                    onChange = { on ->
                        viewModel.setKeyboardWhiteKeys(
                            // Leaving auto starts from what is on screen now, so
                            // the keyboard does not jump the moment you take
                            // manual control of it.
                            if (on) AppSettings.KEYBOARD_AUTO else state.range.whiteKeyCount,
                        )
                    },
                )
                if (!auto) {
                    val keys = state.keyboardWhiteKeys
                    SettingSlider(
                        label = "Keys",
                        value = keys.toFloat(),
                        range = AppSettings.MIN_WHITE_KEYS.toFloat()..AppSettings.MAX_WHITE_KEYS.toFloat(),
                        steps = AppSettings.MAX_WHITE_KEYS - AppSettings.MIN_WHITE_KEYS - 1,
                        readout = "$keys · ${"%.1f".format(keys / 7f)} oct",
                        onChange = { viewModel.setKeyboardWhiteKeys(it.roundToInt()) },
                    )
                    Text(
                        "More keys means narrower ones, closer to the proportions of a real piano.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF6B7385),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Heading("Timing")
            SettingSwitch(
                label = "Count-in before playing",
                checked = state.countInEnabled,
                onChange = { viewModel.toggleCountIn() },
            )
            SettingSlider(
                label = "Look ahead",
                value = state.settings.lookAheadBeats,
                range = 3f..16f,
                steps = 12,
                readout = "${state.settings.lookAheadBeats.roundToInt()} beats",
                onChange = viewModel::setLookAheadBeats,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF6B7385),
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) Color(0xFFB6BDCA) else Color(0xFF5A6273),
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    readout: String,
    onChange: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB6BDCA),
            modifier = Modifier.width(96.dp),
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps.coerceAtLeast(0),
            modifier = Modifier.weight(1f),
        )
        Text(
            readout,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFE3E6EC),
            modifier = Modifier.width(88.dp).padding(start = 12.dp),
        )
    }
}
