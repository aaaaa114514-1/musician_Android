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
    list,
    cycle,
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
val DarkText = Color.White
val DarkSelectionBg = Color(0xFFb0b0b0)
val DarkSelectionText = Color.Black

val LightPrimary = Color(0xFF6750A4)
val LightPrimaryContainer = Color(0xFFEADDFF)
val LightBackground = Color(0xFFFFFBFE)
val LightSurface = Color(0xFFFFFBFE)
val LightText = Color.Black
val LightSelectionBg = Color(0xFF6750a4)
val LightSelectionText = Color.White

class MainActivity : ComponentActivity() {

    lateinit var player: ExoPlayer

    var playlist by mutableStateOf(listOf<Song>())
    var playOrder by mutableStateOf(listOf<Song>())
    var selectedUris by mutableStateOf(setOf<Uri>())

    var currentIndex by mutableStateOf(0)
    var currentSongUri: Uri? by mutableStateOf(null)

    var progress by mutableFloatStateOf(0f)
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)

    var playMode by mutableStateOf(PlayMode.list)
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
                            playlist,
                            currentSongUri,
                            playMode,
                            selectedUris,
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
                            onSelectSong = { uri ->
                                if (playMode == PlayMode.cycle || (playMode == PlayMode.random && selectedUris.isNotEmpty())) {
                                    val newSelection = selectedUris.toMutableSet()
                                    if (newSelection.contains(uri)) {
                                        newSelection.remove(uri)
                                        if (newSelection.isEmpty()) {
                                            changeMode(PlayMode.list)
                                        } else {
                                            selectedUris = newSelection
                                            rebuildPlayOrder()
                                        }
                                    } else {
                                        newSelection.add(uri)
                                        selectedUris = newSelection
                                        rebuildPlayOrder()
                                    }
                                } else {
                                    val indexInOrder = playOrder.indexOfFirst { it.uri == uri }
                                    if (indexInOrder >= 0) {
                                        playSong(indexInOrder)
                                    }
                                }
                            },
                            onChangeMode = { changeMode(it) }
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
                            onHelp = {
                                navController.navigate("help") { launchSingleTop = true }
                            },
                            onBack = {
                                if (navController.previousBackStackEntry != null) {
                                    navController.popBackStack()
                                }
                            }
                        )
                    }

                    composable("help") {
                        HelpScreen(
                            themeMode = themeMode,
                            onBack = {
                                if (navController.previousBackStackEntry != null) {
                                    navController.popBackStack()
                                }
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
        val baseList = if (selectedUris.isNotEmpty() && playMode != PlayMode.list) {
            playlist.filter { it.uri in selectedUris }
        } else {
            playlist
        }

        playOrder = if (playMode == PlayMode.random) {
            baseList.shuffled()
        } else {
            baseList
        }

        val currentUri = currentSongUri
        if (currentUri != null) {
            val index = playOrder.indexOfFirst { it.uri == currentUri }
            currentIndex = if (index >= 0) index else -1
        } else {
            currentIndex = 0
        }
    }

    private fun changeMode(mode: PlayMode) {
        if (mode == PlayMode.list) {
            selectedUris = emptySet()
        } else if (mode == PlayMode.cycle && playMode != PlayMode.cycle) {
            if (selectedUris.isEmpty() && currentSongUri != null) {
                selectedUris = setOf(currentSongUri!!)
            }
        }
        playMode = mode
        rebuildPlayOrder()
    }

    private fun loadSong(index: Int) {
        if (playOrder.isEmpty() || index < 0 || index >= playOrder.size) return
        val song = playOrder[index]
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.pause()
        currentIndex = index
        currentSongUri = song.uri
        isPlaying = false
    }

    private fun playSong(index: Int) {
        if (playOrder.isEmpty() || index < 0 || index >= playOrder.size) return
        val song = playOrder[index]
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.play()
        currentIndex = index
        currentSongUri = song.uri
        isPlaying = true
    }

    private fun nextSongManual() {
        if (playOrder.isEmpty()) return
        val next = if (currentIndex < 0) 0 else (currentIndex + 1) % playOrder.size
        playSong(next)
    }

    private fun prevSongManual() {
        if (playOrder.isEmpty()) return
        val prev = if (currentIndex <= 0) playOrder.size - 1 else currentIndex - 1
        playSong(prev)
    }

    private fun nextSongAuto() {
        if (playOrder.isEmpty()) return
        val next = if (currentIndex < 0) 0 else (currentIndex + 1) % playOrder.size
        playSong(next)
    }
}

@Composable
fun MainScreen(
    navController: NavHostController,
    playlist: List<Song>,
    currentSongUri: Uri?,
    mode: PlayMode,
    selectedUris: Set<Uri>,
    progress: Float,
    duration: Long,
    position: Long,
    isPlaying: Boolean,
    themeMode: ThemeMode,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelectSong: (Uri) -> Unit,
    onChangeMode: (PlayMode) -> Unit
) {
    val textColor = MaterialTheme.colorScheme.onBackground
    val listState = rememberLazyListState()

    LaunchedEffect(currentSongUri, playlist, mode) {
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
                    navController.navigate("settings") { launchSingleTop = true }
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
                onClick = { onChangeMode(PlayMode.list) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == PlayMode.list) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    contentColor = if (mode == PlayMode.list) MaterialTheme.colorScheme.onPrimary else textColor
                )
            ) {
                Text("List")
            }

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
            itemsIndexed(playlist) { _, song ->
                val highlight = song.uri == currentSongUri
                val isSelected = selectedUris.contains(song.uri)

                val bgColor = when {
                    highlight -> MaterialTheme.colorScheme.primaryContainer
                    isSelected -> if (themeMode == ThemeMode.dark) DarkSelectionBg else LightSelectionBg
                    else -> MaterialTheme.colorScheme.surface
                }

                val itemTextColor = when {
                    highlight -> textColor
                    isSelected -> if (themeMode == ThemeMode.dark) DarkSelectionText else LightSelectionText
                    else -> textColor
                }

                Text(
                    song.title,
                    color = itemTextColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .clickable {
                            onSelectSong(song.uri)
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
    onHelp: () -> Unit,
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

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = onHelp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Help")
        }
    }
}

@Composable
fun HelpScreen(
    themeMode: ThemeMode,
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
            Text(
                "Help",
                color = textColor,
                style = MaterialTheme.typography.titleLarge
            )
            TextButton(onClick = onBack) {
                Text("Back", color = textColor)
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "Playback Modes:",
            color = textColor,
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "• List: Plays all songs in the selected folder sequentially.",
            color = textColor,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "• Cycle: Multi-song cycle mode. Tap songs in the list to select or deselect them. The player will loop only through the selected songs. By default, it automatically selects the currently playing song when you enter this mode.",
            color = textColor,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "• Random: Plays songs in a random order. If you switch to Random while in Cycle mode, it will randomly play only the currently selected songs.",
            color = textColor,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(40.dp))

        Text(
            "By aaaaa, 2026",
            color = textColor,
            style = MaterialTheme.typography.bodyMedium
        )
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