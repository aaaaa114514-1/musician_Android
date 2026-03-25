package com.example.musician

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// --- 数据模型 ---
data class Song(
    val uri: Uri,
    val title: String,
    val artist: String = "Unknown Artist",
    val modified: Long
)

enum class PlayMode { list, cycle }
enum class ThemeMode { light, dark }

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
    var isRandom by mutableStateOf(false)
    var themeMode by mutableStateOf(ThemeMode.light)

    var themeHue by mutableFloatStateOf(260f)
    var searchQuery by mutableStateOf("")

    lateinit var prefs: android.content.SharedPreferences

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("folder", uri.toString()).apply()
            lifecycleScope.launch { scanFolder(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = ExoPlayer.Builder(this).build()
        prefs = getSharedPreferences("musician", Context.MODE_PRIVATE)

        themeMode = if (prefs.getString("theme", "light") == "dark") ThemeMode.dark else ThemeMode.light
        themeHue = prefs.getFloat("hue", 260f)

        val folder = prefs.getString("folder", null)
        if (folder != null) {
            lifecycleScope.launch { scanFolder(Uri.parse(folder)) }
        }

        // 颜色保存防抖逻辑：避免拖动时频繁写入磁盘导致卡顿
        lifecycleScope.launch {
            snapshotFlow { themeHue }.collect { hue ->
                delay(800) // 停止滑动后延迟保存
                withContext(Dispatchers.IO) {
                    prefs.edit().putFloat("hue", hue).apply()
                }
            }
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) nextSongAuto()
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        })

        lifecycleScope.launch {
            while (true) {
                if (player.duration > 0) {
                    duration = player.duration
                    position = player.currentPosition
                    progress = player.currentPosition.toFloat() / player.duration.toFloat()
                }
                delay(500)
            }
        }

        setContent {
            val baseColor = Color.hsv(themeHue, 0.6f, 0.8f)
            val scheme = if (themeMode == ThemeMode.dark) {
                darkColorScheme(
                    primary = Color.hsv(themeHue, 0.3f, 0.9f),
                    onPrimary = Color.Black,
                    primaryContainer = Color.hsv(themeHue, 0.4f, 0.3f),
                    onPrimaryContainer = Color.White,
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onBackground = Color(0xFFE6E1E5),
                    onSurface = Color(0xFFE6E1E5)
                )
            } else {
                lightColorScheme(
                    primary = baseColor,
                    onPrimary = Color.White,
                    primaryContainer = Color.hsv(themeHue, 0.15f, 0.95f),
                    onPrimaryContainer = Color.hsv(themeHue, 0.9f, 0.4f),
                    background = Color(0xFFFDFBFF),
                    surface = Color.White,
                    onBackground = Color(0xFF1C1B1F),
                    onSurface = Color(0xFF1C1B1F)
                )
            }

            MaterialTheme(colorScheme = scheme) {
                val navController = rememberNavController()
                NavHost(navController, "main") {
                    composable("main") {
                        MainScreen(
                            navController, playlist, currentSongUri, playMode, isRandom, selectedUris,
                            progress, duration, position, isPlaying, themeMode, themeHue, searchQuery,
                            onSearchChange = { searchQuery = it },
                            onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                            onNext = { nextSongManual() },
                            onPrev = { prevSongManual() },
                            onSeek = { player.seekTo((it * duration).toLong()) },
                            onSelectSong = { handleSongSelection(it) },
                            onChangeMode = { changeMode(it) },
                            onToggleRandom = { isRandom = !isRandom; rebuildPlayOrder() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            themeMode, themeHue,
                            onThemeChange = { themeMode = it; prefs.edit().putString("theme", it.name).apply() },
                            onHueChange = { themeHue = it },
                            onPickFolder = { folderPicker.launch(null) },
                            onHelp = { navController.navigate("help") },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("help") { HelpScreen { navController.popBackStack() } }
                }
            }
        }
    }

    private fun handleSongSelection(uri: Uri) {
        if (playMode == PlayMode.cycle) {
            val newSelection = selectedUris.toMutableSet()
            if (newSelection.contains(uri)) {
                newSelection.remove(uri)
                if (newSelection.isEmpty()) changeMode(PlayMode.list) else {
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
            if (indexInOrder >= 0) playSong(indexInOrder)
        }
    }

    private suspend fun scanFolder(uri: Uri) {
        withContext(Dispatchers.IO) {
            val dir = DocumentFile.fromTreeUri(this@MainActivity, uri) ?: return@withContext
            val rawFiles = dir.listFiles().filter {
                val n = it.name?.lowercase() ?: ""
                n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav")
            }
            if (rawFiles.isEmpty()) return@withContext

            val fastSongs = rawFiles.map {
                Song(it.uri, it.name?.substringBeforeLast(".") ?: "Unknown", "Loading...", it.lastModified())
            }.sortedByDescending { it.modified }

            withContext(Dispatchers.Main) {
                playlist = fastSongs
                rebuildPlayOrder()
                if (playOrder.isNotEmpty() && currentSongUri == null) loadSong(0)
            }

            val retriever = MediaMetadataRetriever()
            val fullSongs = rawFiles.map { file ->
                var artist = "Unknown Artist"
                try {
                    retriever.setDataSource(this@MainActivity, file.uri)
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                } catch (e: Exception) {}
                Song(file.uri, file.name?.substringBeforeLast(".") ?: "Unknown", artist, file.lastModified())
            }.sortedByDescending { it.modified }
            retriever.release()

            withContext(Dispatchers.Main) {
                playlist = fullSongs
                rebuildPlayOrder()
            }
        }
    }

    private fun rebuildPlayOrder() {
        val base = if (selectedUris.isNotEmpty() && playMode == PlayMode.cycle) playlist.filter { it.uri in selectedUris } else playlist
        playOrder = if (isRandom) base.shuffled() else base
        currentSongUri?.let { uri -> val idx = playOrder.indexOfFirst { it.uri == uri }; currentIndex = if (idx >= 0) idx else -1 }
    }

    private fun changeMode(mode: PlayMode) {
        if (mode == PlayMode.list) selectedUris = emptySet()
        else if (selectedUris.isEmpty() && currentSongUri != null) selectedUris = setOf(currentSongUri!!)
        playMode = mode
        rebuildPlayOrder()
    }

    private fun loadSong(index: Int) {
        if (playOrder.isEmpty() || index !in playOrder.indices) return
        player.setMediaItem(MediaItem.fromUri(playOrder[index].uri))
        player.prepare()
        currentIndex = index; currentSongUri = playOrder[index].uri
    }

    private fun playSong(index: Int) {
        if (playOrder.isEmpty() || index !in playOrder.indices) return
        player.setMediaItem(MediaItem.fromUri(playOrder[index].uri))
        player.prepare(); player.play()
        currentIndex = index; currentSongUri = playOrder[index].uri
    }

    private fun nextSongManual() = playSong((currentIndex + 1) % playOrder.size)
    private fun prevSongManual() = playSong(if (currentIndex <= 0) playOrder.size - 1 else currentIndex - 1)
    private fun nextSongAuto() = nextSongManual()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavHostController, playlist: List<Song>, currentSongUri: Uri?,
    mode: PlayMode, isRandom: Boolean, selectedUris: Set<Uri>,
    progress: Float, duration: Long, position: Long, isPlaying: Boolean,
    themeMode: ThemeMode, themeHue: Float, searchQuery: String,
    onSearchChange: (String) -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit,
    onPrev: () -> Unit, onSeek: (Float) -> Unit, onSelectSong: (Uri) -> Unit,
    onChangeMode: (PlayMode) -> Unit, onToggleRandom: () -> Unit
) {
    val filteredPlaylist = playlist.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) }
    val listState = rememberLazyListState()

    var sliderDraggingValue by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(currentSongUri) {
        val index = filteredPlaylist.indexOfFirst { it.uri == currentSongUri }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Musician", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { navController.navigate("settings") }) { Icon(Icons.Default.Settings, null) }
                }
                OutlinedTextField(
                    value = searchQuery, onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search songs...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if(searchQuery.isNotEmpty()) IconButton(onClick = {onSearchChange("")}) {Icon(Icons.Default.Close, null)} },
                    shape = RoundedCornerShape(12.dp), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
                )
            }
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp, shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Slider(
                        value = sliderDraggingValue ?: progress,
                        onValueChange = { sliderDraggingValue = it },
                        onValueChangeFinished = {
                            sliderDraggingValue?.let { onSeek(it) }
                            sliderDraggingValue = null
                        },
                        modifier = Modifier.height(20.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val displayPos = if (sliderDraggingValue != null) (sliderDraggingValue!! * duration).toLong() else position
                        Text(formatTime(displayPos), style = MaterialTheme.typography.labelSmall)
                        Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row {
                            ModeIcon(Icons.Default.List, mode == PlayMode.list) { onChangeMode(PlayMode.list) }
                            Spacer(Modifier.width(8.dp))
                            ModeIcon(Icons.Default.Repeat, mode == PlayMode.cycle) { onChangeMode(PlayMode.cycle) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onPrev) { Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(32.dp)) }
                            Spacer(Modifier.width(20.dp))
                            FloatingActionButton(onClick = onPlayPause, containerColor = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(32.dp))
                            }
                            Spacer(Modifier.width(20.dp))
                            IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, null, Modifier.size(32.dp)) }
                        }
                        ModeIcon(Icons.Default.Shuffle, isRandom) { onToggleRandom() }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (playlist.isEmpty()) {
                Text("No music found", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline)
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(filteredPlaylist) { song ->
                        SongItem(song, song.uri == currentSongUri, selectedUris.contains(song.uri), themeMode, themeHue) { onSelectSong(song.uri) }
                    }
                }
            }
        }
    }
}

@Composable
fun ModeIcon(icon: ImageVector, isActive: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, colors = IconButtonDefaults.iconButtonColors(contentColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(if (isActive) 28.dp else 24.dp))
            if (isActive) Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

@Composable
fun SongItem(song: Song, isCurrent: Boolean, isSelected: Boolean, themeMode: ThemeMode, themeHue: Float, onClick: () -> Unit) {
    val bgColor = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
        isSelected -> Color.hsv(themeHue, if(themeMode == ThemeMode.dark) 0.4f else 0.2f, if(themeMode == ThemeMode.dark) 0.5f else 0.9f)
        else -> Color.Transparent
    }
    val textColor = if (isSelected && themeMode == ThemeMode.dark) Color.White else MaterialTheme.colorScheme.onBackground

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(bgColor).clickable { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
            Icon(if (isCurrent && !isSelected) Icons.Default.GraphicEq else Icons.Default.MusicNote, null, tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = textColor, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = if (isSelected) textColor.copy(0.7f) else MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        if (isCurrent) Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun SettingsScreen(themeMode: ThemeMode, themeHue: Float, onThemeChange: (ThemeMode) -> Unit, onHueChange: (Float) -> Unit, onPickFolder: () -> Unit, onHelp: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            Text("Appearance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))

            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { onThemeChange(ThemeMode.light) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(themeMode == ThemeMode.light, { onThemeChange(ThemeMode.light) }); Text("Light Theme")
                    }
                    Row(Modifier.fillMaxWidth().clickable { onThemeChange(ThemeMode.dark) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(themeMode == ThemeMode.dark, { onThemeChange(ThemeMode.dark) }); Text("Dark Theme")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Theme Palette (Hue Slider)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))

            HueSlider(themeHue, onHueChange)

            Spacer(Modifier.height(32.dp))
            Text("Library", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onPickFolder, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Change Music Folder") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onHelp, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.HelpOutline, null); Spacer(Modifier.width(8.dp)); Text("Help") }
        }
    }
}

@Composable
fun HueSlider(hue: Float, onHueChange: (Float) -> Unit) {
    // 使用局部拖动状态，确保拖动瞬间 UI 响应
    var draggingHue by remember { mutableStateOf<Float?>(null) }
    val displayHue = draggingHue ?: hue

    Column {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(brush = Brush.horizontalGradient(colors = List(36) { Color.hsv(it * 10f, 0.8f, 1f) }))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val newHue = (offset.x / size.width).coerceIn(0f, 1f) * 360f
                        onHueChange(newHue)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            draggingHue = (offset.x / size.width).coerceIn(0f, 1f) * 360f
                        },
                        onDragEnd = {
                            draggingHue?.let { onHueChange(it) }
                            draggingHue = null
                        },
                        onDragCancel = { draggingHue = null },
                        onDrag = { change, _ ->
                            change.consume() // 必须消费事件，确保手势流连续
                            val newHue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                            draggingHue = newHue
                            onHueChange(newHue) // 实时更新外部颜色预览
                        }
                    )
                }
        ) {
            val thumbWidth = 44.dp
            // 使用 Lambda 版本的 offset 以获得最高性能（不会触发重排）
            Box(
                Modifier
                    .size(thumbWidth)
                    .offset {
                        val xPos = (displayHue / 360f * maxWidth.toPx()) - (thumbWidth.toPx() / 2)
                        IntOffset(xPos.roundToInt(), 0)
                    }
                    .background(Color.White, CircleShape)
                    .padding(4.dp)
            ) {
                Box(Modifier.fillMaxSize().background(Color.hsv(displayHue, 0.8f, 1f), CircleShape))
            }
        }
        Text("Slide to change theme accent", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                Text("Help", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            HelpCard("List Mode", "Sequential playback through all songs.", Icons.Default.List)
            HelpCard("Cycle Mode", "Select multiple songs to loop them.", Icons.Default.Repeat)
            HelpCard("Shuffle", "Randomize order within current active set.", Icons.Default.Shuffle)
        }
    }
}

@Composable
fun HelpCard(title: String, desc: String, icon: ImageVector) {
    Surface(Modifier.padding(vertical = 8.dp).fillMaxWidth(), RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column { Text(title, fontWeight = FontWeight.Bold); Text(desc, color = MaterialTheme.colorScheme.outline) }
        }
    }
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}