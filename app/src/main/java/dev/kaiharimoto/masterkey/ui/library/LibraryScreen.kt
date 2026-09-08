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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
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
import dev.kaiharimoto.masterkey.ui.player.formatDuration
import dev.kaiharimoto.masterkey.ui.theme.HandColors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Library loading, modelled explicitly so storage problems are recoverable.
 *
 * v1.0.1 shipped a schema change without a version bump; Room threw on the first
 * query, which happens at startup, and because nothing caught it the app died on
 * every launch — with the in-app updater unreachable behind the crash. Failure
 * is a UI state now, not a process exit.
 */
sealed interface LibraryState {
    data object Loading : LibraryState
    data class Ready(val songs: List<SongEntity>) : LibraryState
    data class Failed(val reason: String) : LibraryState
}

class LibraryViewModel(private val repository: SongRepository) : ViewModel() {

    /** Bumped by "Try again" to re-subscribe after a failure. */
    private val retries = MutableStateFlow(0)

    val state: StateFlow<LibraryState> = retries
        .flatMapLatest {
            repository.observeSongs()
                .map<List<SongEntity>, LibraryState> { LibraryState.Ready(it) }
                .catch { error -> emit(LibraryState.Failed(explain(error))) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState.Loading)

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _rebuilding = MutableStateFlow(false)
    val rebuilding: StateFlow<Boolean> = _rebuilding.asStateFlow()

    // Declared before the init block below, which sends through it. Property
    // initialisers run in declaration order.
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    init {
        // After a restore onto a new device the backup brings the song files but
        // not the database, so the index is empty while the folders are not.
        // Rebuilding here means that just works rather than looking like data loss.
        viewModelScope.launch {
            runCatching {
                if (repository.hasUnindexedSongs()) {
                    val recovered = repository.rebuildFromDisk()
                    if (recovered > 0) {
                        messages.send("Recovered $recovered ${songNoun(recovered)} from storage.")
                    }
                }
            }
        }
    }

    fun retry() {
        retries.value += 1
    }

    /** Rescans the song folders and rebuilds the index from what is on disk. */
    fun rebuild() {
        viewModelScope.launch {
            _rebuilding.value = true
            val result = runCatching { repository.rebuildFromDisk() }
            _rebuilding.value = false
            result.fold(
                onSuccess = { count ->
                    messages.send(
                        if (count > 0) {
                            "Recovered $count ${songNoun(count)}."
                        } else {
                            "No songs found in storage to recover."
                        },
                    )
                    retry()
                },
                onFailure = { messages.send(it.message ?: "Couldn't rebuild the library.") },
            )
        }
    }

    private fun songNoun(count: Int) = if (count == 1) "song" else "songs"

    private fun explain(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("Room cannot verify the data integrity", ignoreCase = true) ||
                message.contains("Migration didn't properly handle", ignoreCase = true) ->
                "Your library database was written by a different version of the app " +
                    "and can't be opened. Rebuilding will restore your songs from the " +
                    "files already on this device."

            message.contains("no such table", ignoreCase = true) ||
                message.contains("no such column", ignoreCase = true) ->
                "Your library database is missing part of its structure. Rebuilding " +
                    "will restore your songs from the files already on this device."

            else -> "Couldn't open your library. ${error.message ?: "Unknown error."}"
        }
    }

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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rebuilding by viewModel.rebuilding.collectAsStateWithLifecycle()
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
            // Hidden while the library is broken: importing into a database that
            // won't open would just fail confusingly.
            if (state is LibraryState.Ready) {
                ExtendedFloatingActionButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResourceSafe(R.string.add_song)) },
                )
            }
        },
    ) { padding ->
        when (val current = state) {
            is LibraryState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is LibraryState.Failed -> LibraryRecovery(
                reason = current.reason,
                rebuilding = rebuilding,
                onRetry = viewModel::retry,
                onRebuild = viewModel::rebuild,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(padding),
            )

            is LibraryState.Ready -> if (current.songs.isEmpty()) {
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
                    items(current.songs, key = { it.id }) { song ->
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
}

/**
 * Shown when the library database can't be opened.
 *
 * The "Check for updates" button matters more than it looks: the updater reaches
 * only [dev.kaiharimoto.masterkey.update.UpdateRepository], never the database,
 * so it stays usable when storage is broken. That is what makes a bad release
 * fixable over the air instead of by sideloading — which is exactly what went
 * wrong with v1.0.1.
 */
@Composable
private fun LibraryRecovery(
    reason: String,
    rebuilding: Boolean,
    onRetry: () -> Unit,
    onRebuild: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Library unavailable",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            if (rebuilding) {
                CircularProgressIndicator()
            } else {
                Button(onClick = onRebuild) { Text("Rebuild from my files") }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onRetry) { Text("Try again") }
                TextButton(onClick = onOpenSettings) { Text("Check for updates") }
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
                        append(
                            when {
                                song.midiFileName == SongEntity.NO_MIDI -> "Score only"
                                song.scoreFileName != null -> "MIDI + score"
                                else -> "MIDI only"
                            },
                        )
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

@Composable
private fun stringResourceSafe(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
