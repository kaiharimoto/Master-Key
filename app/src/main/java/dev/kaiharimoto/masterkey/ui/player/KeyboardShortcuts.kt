package dev.kaiharimoto.masterkey.ui.player

import android.view.KeyEvent
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Hardware key routing for the player.
 *
 * Compose's `onPreviewKeyEvent` only fires when focus sits inside the Compose
 * tree, and the score pane is a `WebView` — an Android `View` that takes focus
 * and consumes arrow keys and space before Compose is ever consulted. So keys
 * are intercepted one level up, in the activity's `dispatchKeyEvent`, which sees
 * everything before the window dispatches it anywhere.
 *
 * A screen registers a handler while it is on screen and unregisters on the way
 * out, so shortcuts are never live anywhere they are not wanted.
 */
class KeyDispatcher {

    private var handler: ((KeyEvent) -> Boolean)? = null

    fun register(handler: (KeyEvent) -> Boolean) {
        this.handler = handler
    }

    fun unregister(handler: (KeyEvent) -> Boolean) {
        if (this.handler === handler) this.handler = null
    }

    /**
     * Returns true when the key was handled and should go no further.
     *
     * Key-up is swallowed for anything key-down claimed, so a shortcut cannot
     * also reach whatever would have received the release.
     */
    fun dispatch(event: KeyEvent, focused: android.view.View?): Boolean {
        val handle = handler ?: return false
        // Never steal keys from a text field. There are none in the player today,
        // but a rename dialog one day should not silently stop accepting letters.
        if (focused is EditText) return false
        if (event.action == KeyEvent.ACTION_UP) return claimedKeyUp.remove(event.keyCode)
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val handled = handle(event)
        if (handled) claimedKeyUp += event.keyCode
        return handled
    }

    private val claimedKeyUp = mutableSetOf<Int>()
}

val LocalKeyDispatcher = staticCompositionLocalOf<KeyDispatcher?> { null }

/** One row of the legend, and the single source of truth for what is bound. */
data class Shortcut(val keys: String, val action: String)

/**
 * The bindings, grouped the way they are read rather than the way they are
 * implemented.
 *
 * Plain letters, no modifier: there is nothing to type on this screen, so the
 * whole keyboard is free, and a practice shortcut you have to reach for with two
 * hands is one you will not use while sitting at a piano.
 */
val SHORTCUT_GROUPS: List<Pair<String, List<Shortcut>>> = listOf(
    "Transport" to listOf(
        Shortcut("Space", "Play / pause"),
        Shortcut("← →", "Back / forward one bar"),
        Shortcut("⇧ ← →", "Back / forward four bars"),
        Shortcut("Home", "Back to the start"),
        Shortcut("End", "Jump to the end"),
    ),
    "Tempo" to listOf(
        Shortcut("↑ ↓", "Tempo up / down 5%"),
        Shortcut("D", "Step the tempo drill"),
    ),
    "Practice" to listOf(
        Shortcut("R", "Mute / unmute right hand"),
        Shortcut("L", "Mute / unmute left hand"),
        Shortcut("C", "Loop this bar (again to clear)"),
        Shortcut("M", "Metronome"),
        Shortcut("N", "Count-in"),
    ),
    "View" to listOf(
        Shortcut("S", "Show / hide sheet music"),
        Shortcut("[ ]", "Move sheet music left / right"),
        Shortcut("A", "Cycle reading aids"),
        Shortcut("I", "This list"),
        Shortcut("Esc", "Close this list, or go back"),
    ),
)

/**
 * The shortcut legend.
 *
 * Deliberately an overlay rather than a settings page: it is something you check
 * mid-practice without losing your place, and it should go away as easily as it
 * appeared.
 */
@Composable
fun ShortcutLegend(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC05070B))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF161A22))
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            Text(
                "Keyboard shortcuts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE3E6EC),
            )
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                SHORTCUT_GROUPS.chunked(2).forEach { column ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        column.forEach { (heading, shortcuts) ->
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    heading.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF6B7385),
                                    letterSpacing = 1.sp,
                                )
                                shortcuts.forEach { ShortcutRow(it) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "I or Esc to close",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6B7385),
            )
        }
    }
}

@Composable
private fun ShortcutRow(shortcut: Shortcut) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            shortcut.keys,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFFFB020),
            modifier = Modifier.width(72.dp),
        )
        Text(
            shortcut.action,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB6BDCA),
        )
    }
}
