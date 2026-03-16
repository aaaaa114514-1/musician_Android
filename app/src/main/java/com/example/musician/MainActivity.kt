package com.example.musician

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Song(
    val uri: Uri,
    val title: String,
    val modified: Long
)

enum class PlayMode {
    cycle,
    single,
    random
}

enum class ThemeMode {
    light,
    dark
}

val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkButton = Color(0xFFB0B0B0)
val DarkButtonText = Color(0xFF000000)
val DarkModeSelected = Color(0xFF3A3A3A)
val DarkSliderTrack = Color(0xFFBBBBBB)
val DarkSliderPlayed = Color(0xFFE0E0E0)
val DarkText = Color.White

val LightPrimary = Color(0xFF6750A4)
val LightPrimaryContainer = Color(0xFFEADDFF)
val LightBackground = Color(0xFFFFFBFE)
val LightSurface = Color(0xFFFFFBFE)
val LightText = Color.Black

class MainActivity : ComponentActivity() {

    lateinit var player: ExoPlayer

    var playlist by mutableStateOf(listOf<Song>())
    var playOrder by mutableStateOf(listOf<Song>())

    var currentIndex by mutableStateOf(0)
    var currentSongUri: Uri? = null

    var progress by mutableFloatStateOf(0f)
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)

    var playMode by mutableStateOf(PlayMode.cycle)
    var themeMode by mutableStateOf(ThemeMode.light)

    lateinit var prefs: android.content.SharedPreferences

    private val folderPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                prefs.edit().putString("folder", uri.toString()).apply()
                scanFolder(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = ExoPlayer.Builder(this).build()

        prefs = getSharedPreferences("musician", Context.MODE_PRIVATE)

        val theme = prefs.getString("theme", "light")
        themeMode = if (theme == "dark") ThemeMode.dark else ThemeMode.light

        val folder = prefs.getString("folder", null)

        if (folder != null) {
            scanFolder(Uri.parse(folder))
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    nextSongAuto()
                }
            }
        })

        lifecycleScope.launch {
            while (true) {
                if (player.duration > 0) {
                    duration = player.duration
                    position = player.currentPosition
                    progress = player.currentPosition.toFloat() / player.duration.toFloat()
                }
                delay(300)
            }
        }

        setContent {
            val navController = rememberNavController()

            val scheme =
                if (themeMode == ThemeMode.dark)
                    darkColorScheme(
                        background = DarkBackground,
                        surface = DarkSurface,
                        onBackground = DarkText,
                        onSurface = DarkText,
                        primary = DarkModeSelected,
                        onPrimary = DarkText,
                        primaryContainer = DarkModeSelected
                    )
                else
                    lightColorScheme(
                        primary = LightPrimary,
                        onPrimary = Color.White,
                        primaryContainer = LightPrimaryContainer,
                        background = LightBackground,
                        surface = LightSurface,
                        onBackground = LightText,
                        onSurface = LightText
                    )

            MaterialTheme(colorScheme = scheme) {
                NavHost(
                    navController = navController,
                    startDestination = "main"
                ) {
                    composable("main") {
                        MainScreen(
                            navController,
                            playOrder,
                            currentSongUri,
                            playMode,
                            progress,
                            duration,
                            position,
                            isPlaying,
                            themeMode,
                            onPlayPause = {
                                if (player.isPlaying) {
                                    player.pause()
                                    isPlaying = false
                                } else {
                                    player.play()
                                    isPlaying = true
                                }
                            },
                            onNext = { nextSongManual() },
                            onPrev = { prevSongManual() },
                            onSeek = {
                                val pos = (it * duration).toLong()
                                player.seekTo(pos)
                            },
                            onSelectSong = {
                                playSong(it)
                            },
                            onChangeMode = {
                                changeMode(it)
                            }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            themeMode = themeMode,
                            onThemeChange = {
                                themeMode = it
                                prefs.edit().putString("theme", it.name).apply()
                            },
                            onPickFolder = {
                                folderPicker.launch(null)
                            },
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun scanFolder(uri: Uri) {
        val dir = DocumentFile.fromTreeUri(this, uri) ?: return

        val songs = dir.listFiles()
            .filter {
                val name = it.name?.lowercase()
                name != null && (name.endsWith(".mp3") || name.endsWith(".m4a"))
            }
            .map {
                Song(
                    it.uri,
                    it.name ?: "Unknown",
                    it.lastModified()
                )
            }
            .sortedByDescending { it.modified }

        playlist = songs
        rebuildPlayOrder()

        if (playOrder.isNotEmpty()) {
            loadSong(0)
        }
    }

    private fun rebuildPlayOrder() {
        playOrder =
            if (playMode == PlayMode.random)
                playlist.shuffled()
            else
                playlist
    }

    private fun changeMode(mode: PlayMode) {
        playMode = mode
        val currentUri = currentSongUri
        rebuildPlayOrder()

        if (currentUri != null) {
            val index = playOrder.indexOfFirst { it.uri == currentUri }
            if (index >= 0) currentIndex = index
        }
    }

    private fun loadSong(index: Int) {
        val song = playOrder[index]
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.pause()
        currentIndex = index
        currentSongUri = song.uri
        isPlaying = false
    }

    private fun playSong(index: Int) {
        val song = playOrder[index]
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.play()
        currentIndex = index
        currentSongUri = song.uri
        isPlaying = true
    }

    private fun nextSongManual() {
        val next = (currentIndex + 1) % playOrder.size
        playSong(next)
    }

    private fun prevSongManual() {
        val prev =
            if (currentIndex == 0)
                playOrder.size - 1
            else
                currentIndex - 1
        playSong(prev)
    }

    private fun nextSongAuto() {
        when (playMode) {
            PlayMode.single ->
                playSong(currentIndex)
            else -> {
                val next = (currentIndex + 1) % playOrder.size
                playSong(next)
            }
        }
    }
}

@Composable
fun MainScreen(
    navController: NavHostController,
    playlist: List<Song>,
    currentSongUri: Uri?,
    mode: PlayMode,
    progress: Float,
    duration: Long,
    position: Long,
    isPlaying: Boolean,
    themeMode: ThemeMode,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelectSong: (Int) -> Unit,
    onChangeMode: (PlayMode) -> Unit
) {
    val textColor = MaterialTheme.colorScheme.onBackground
    val listState = rememberLazyListState()

    LaunchedEffect(currentSongUri, playlist) {
        val index = playlist.indexOfFirst { it.uri == currentSongUri }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Musician", color = textColor)
            IconButton(
                onClick = {
                    navController.navigate("settings")
                }
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "settings",
                    tint = textColor
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Slider(
            value = progress,
            onValueChange = onSeek
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTime(position), color = textColor)
            Text(formatTime(duration), color = textColor)
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            ControlButton("⏮") { onPrev() }
            Spacer(Modifier.width(24.dp))
            ControlButton(
                if (isPlaying) "⏸" else "▶"
            ) { onPlayPause() }
            Spacer(Modifier.width(24.dp))
            ControlButton("⏭") { onNext() }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { onChangeMode(PlayMode.cycle) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == PlayMode.cycle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    contentColor = if (mode == PlayMode.cycle) MaterialTheme.colorScheme.onPrimary else textColor
                )
            ) {
                Text("Cycle")
            }

            Button(
                onClick = { onChangeMode(PlayMode.single) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == PlayMode.single) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    contentColor = if (mode == PlayMode.single) MaterialTheme.colorScheme.onPrimary else textColor
                )
            ) {
                Text("Single")
            }

            Button(
                onClick = { onChangeMode(PlayMode.random) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == PlayMode.random) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    contentColor = if (mode == PlayMode.random) MaterialTheme.colorScheme.onPrimary else textColor
                )
            ) {
                Text("Random")
            }
        }

        Spacer(Modifier.height(20.dp))

        LazyColumn(state = listState) {
            itemsIndexed(playlist) { i, song ->
                val highlight = song.uri == currentSongUri
                Text(
                    song.title,
                    color = textColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (highlight)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                        .clickable {
                            onSelectSong(i)
                        }
                        .padding(12.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onPickFolder: () -> Unit,
    onBack: () -> Unit
) {
    val textColor = MaterialTheme.colorScheme.onBackground

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Settings", color = textColor)
            TextButton(onClick = onBack) {
                Text("Back", color = textColor)
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { onThemeChange(ThemeMode.light) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Light Theme")
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = { onThemeChange(ThemeMode.dark) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Dark Theme")
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onPickFolder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Select Music Folder")
        }
    }
}

@Composable
fun ControlButton(
    symbol: String,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 4.dp,
        color = DarkButton
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                symbol,
                color = DarkButtonText,
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}