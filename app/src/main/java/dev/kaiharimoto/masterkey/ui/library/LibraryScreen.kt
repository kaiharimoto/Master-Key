package dev.kaiharimoto.masterkey.ui.library

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kaiharimoto.masterkey.core.midi.Pitch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kaiharimoto.masterkey.MasterKeyApp
import dev.kaiharimoto.masterkey.R
import dev.kaiharimoto.masterkey.data.ImportResult
import dev.kaiharimoto.masterkey.data.SongEntity
import dev.kaiharimoto.masterkey.data.SongRepository
import dev.kaiharimoto.masterkey.ui.theme.HandColors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class LibraryViewModel(private val repository: SongRepository) : ViewModel() {

    val songs: StateFlow<List<SongEntity>> = repository.observeSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    /** Song awaiting a score attachment, set when the user taps "Add sheet music". */
    var pendingScoreSongId: String? = null
        private set

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            when (val result = repository.import(uris)) {
                is ImportResult.Success -> {
                    val count = result.songs.size
                    val noun = if (count == 1) "song" else "songs"
                    val paired = when {
                        result.pairedScores == 0 -> ""
                        result.pairedScores == count -> " with sheet music"
                        else -> ", ${result.pairedScores} with sheet music"
                    }
                    messages.send("Added $count $noun$paired.")
                }

                is ImportResult.Failure -> messages.send(result.message)
            }
            _importing.value = false
        }
    }

    fun beginAttachScore(songId: String) {
        pendingScoreSongId = songId
    }

    fun attachScore(uri: Uri?) {
        val songId = pendingScoreSongId ?: return
        pendingScoreSongId = null
        if (uri == null) return
        viewModelScope.launch {
            val ok = repository.attachScore(songId, uri)
            messages.send(
                if (ok) "Sheet music linked." else "That file didn't look like MusicXML.",
            )
        }
    }

    fun delete(song: SongEntity) {
        viewModelScope.launch {
            repository.delete(song)
            messages.send("Removed ${song.title}.")
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as Application
                LibraryViewModel((app as MasterKeyApp).graph.songRepository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenSong: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Multi-select is the happy path: picking the MIDI and its MusicXML together
    // pairs them automatically, so the common case needs no second step.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.import(uris) }

    val scoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> viewModel.attachScore(uri) }

    LaunchedEffect(Unit) {
        viewModel.messageFlow.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResourceSafe(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResourceSafe(R.string.add_song)) },
            )
        },
    ) { padding ->
        if (songs.isEmpty()) {
            EmptyLibrary(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(songs, key = { it.id }) { song ->
                    SongCard(
                        song = song,
                        onOpen = { onOpenSong(song.id) },
                        onAttachScore = {
                            viewModel.beginAttachScore(song.id)
                            scoreLauncher.launch(arrayOf("*/*"))
                        },
                        onDelete = { viewModel.delete(song) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResourceSafe(R.string.library_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResourceSafe(R.string.library_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SongCard(
    song: SongEntity,
    onOpen: () -> Unit,
    onAttachScore: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A tiny pitch-range bar: shows at a glance whether this is a
            // two-hand piece and roughly where on the keyboard it sits.
            RangeSwatch(song)

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(if (song.scoreFileName != null) "MIDI + score" else "MIDI only")
                        append(" · ")
                        append(formatDuration(song.durationMicros))
                        song.timeSignature?.let { append(" · $it") }
                        song.keySignature?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (song.scoreFileName == null) {
                IconButton(onClick = onAttachScore) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = stringResourceSafe(R.string.attach_score),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResourceSafe(R.string.delete_song),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A vertical sliver showing where on the 88 keys this piece lives, shaded from
 * teal at the bottom to amber at the top to match the hand colours. It answers
 * "is this a two-hand piece, and roughly where?" without opening it.
 */
@Composable
private fun RangeSwatch(song: SongEntity) {
    val span = (Pitch.HIGHEST_PIANO - Pitch.LOWEST_PIANO).toFloat()
    val low = ((song.pitchLow - Pitch.LOWEST_PIANO) / span).coerceIn(0f, 1f)
    val high = ((song.pitchHigh - Pitch.LOWEST_PIANO) / span).coerceIn(0f, 1f)

    Canvas(
        Modifier
            .width(6.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        drawRect(trackColor)
        // Pitch increases upward, so the low end sits at the bottom.
        val top = size.height * (1f - high)
        val bottom = size.height * (1f - low)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(HandColors.amber, HandColors.teal),
                startY = top,
                endY = bottom,
            ),
            topLeft = Offset(0f, top),
            size = Size(size.width, (bottom - top).coerceAtLeast(3f)),
        )
    }
}

private val trackColor = Color(0xFF262B36)

private fun formatDuration(micros: Long): String {
    val totalSeconds = TimeUnit.MICROSECONDS.toSeconds(micros)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun stringResourceSafe(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
