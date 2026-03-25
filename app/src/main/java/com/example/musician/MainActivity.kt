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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
enum class AppLanguage { CN, EN }

// --- 本地化字符串管理 ---
fun getStr(id: String, lang: AppLanguage): String {
    val isCN = lang == AppLanguage.CN
    return when (id) {
        "app_name" -> if (isCN) "Musician" else "Musician"
        "search_hint" -> if (isCN) "搜索歌曲或艺术家..." else "Search songs or artists..."
        "no_music" -> if (isCN) "未找到音乐" else "No music found"
        "settings" -> if (isCN) "设置" else "Settings"
        "appearance" -> if (isCN) "外观" else "Appearance"
        "light_theme" -> if (isCN) "浅色模式" else "Light Theme"
        "dark_theme" -> if (isCN) "深色模式" else "Dark Theme"
        "language" -> if (isCN) "语言选择" else "Language"
        "lang_cn" -> if (isCN) "中文" else "Chinese"
        "lang_en" -> if (isCN) "英文" else "English"
        "show_artist" -> if (isCN) "显示艺术家" else "Show Artist"
        "theme_hue" -> if (isCN) "主题调色板" else "Theme Palette"
        "hue_hint" -> if (isCN) "滑动以改变主题颜色" else "Slide to change theme accent"
        "library" -> if (isCN) "媒体库" else "Library"
        "change_folder" -> if (isCN) "更改音乐文件夹" else "Change Music Folder"
        "help" -> if (isCN) "帮助" else "Help"
        "back" -> if (isCN) "返回" else "Back"
        "mode_list" -> if (isCN) "列表模式" else "List Mode"
        "mode_list_desc" -> if (isCN) "顺序播放文件夹内所有歌曲。" else "Sequential playback through all songs."
        "mode_cycle" -> if (isCN) "循环模式" else "Cycle Mode"
        "mode_cycle_desc" -> if (isCN) "选择多首歌曲进行循环播放。" else "Select multiple songs to loop them."
        "mode_shuffle" -> if (isCN) "随机播放" else "Shuffle"
        "mode_shuffle_desc" -> if (isCN) "在当前播放队列中随机乱序。" else "Randomize order within current set."
        else -> id
    }
}

class MainActivity : ComponentActivity() {

    lateinit var player: ExoPlayer
    lateinit var prefs: android.content.SharedPreferences

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
    var appLanguage by mutableStateOf(AppLanguage.CN)
    var showArtist by mutableStateOf(true)
    var searchQuery by mutableStateOf("")

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
        showArtist = prefs.getBoolean("show_artist", true)
        appLanguage = AppLanguage.valueOf(prefs.getString("lang", "CN")!!)

        val folder = prefs.getString("folder", null)
        if (folder != null) { lifecycleScope.launch { scanFolder(Uri.parse(folder)) } }

        lifecycleScope.launch {
            snapshotFlow { themeHue }.collect { hue ->
                delay(800)
                withContext(Dispatchers.IO) { prefs.edit().putFloat("hue", hue).apply() }
            }
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) nextSongAuto() }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
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
                            appLanguage, showArtist,
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
                            themeMode, themeHue, appLanguage, showArtist,
                            onThemeChange = { themeMode = it; prefs.edit().putString("theme", it.name).apply() },
                            onHueChange = { themeHue = it },
                            onLangChange = { appLanguage = it; prefs.edit().putString("lang", it.name).apply() },
                            onShowArtistChange = { showArtist = it; prefs.edit().putBoolean("show_artist", it).apply() },
                            onPickFolder = { folderPicker.launch(null) },
                            onHelp = { navController.navigate("help") },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("help") { HelpScreen(appLanguage) { navController.popBackStack() } }
                }
            }
        }
    }

    private fun handleSongSelection(uri: Uri) {
        if (playMode == PlayMode.cycle) {
            val newSelection = selectedUris.toMutableSet()
            if (newSelection.contains(uri)) {
                newSelection.remove(uri)
                if (newSelection.isEmpty()) changeMode(PlayMode.list) else { selectedUris = newSelection; rebuildPlayOrder() }
            } else { selectedUris = newSelection.apply { add(uri) }; rebuildPlayOrder() }
        } else {
            val idx = playOrder.indexOfFirst { it.uri == uri }
            if (idx >= 0) playSong(idx)
        }
    }

    private suspend fun scanFolder(uri: Uri) {
        withContext(Dispatchers.IO) {
            val dir = DocumentFile.fromTreeUri(this@MainActivity, uri) ?: return@withContext
            val files = dir.listFiles().filter { it.name?.lowercase()?.let { n -> n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav") } == true }
            if (files.isEmpty()) return@withContext
            val fast = files.map { Song(it.uri, it.name?.substringBeforeLast(".") ?: "Unknown", "...", it.lastModified()) }.sortedByDescending { it.modified }
            withContext(Dispatchers.Main) { playlist = fast; rebuildPlayOrder(); if (currentSongUri == null) loadSong(0) }
            val retriever = MediaMetadataRetriever()
            val full = files.map { f ->
                var a = "Unknown Artist"
                try { retriever.setDataSource(this@MainActivity, f.uri); a = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist" } catch (e: Exception) {}
                Song(f.uri, f.name?.substringBeforeLast(".") ?: "Unknown", a, f.lastModified())
            }.sortedByDescending { it.modified }
            retriever.release()
            withContext(Dispatchers.Main) { playlist = full; rebuildPlayOrder() }
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
        playMode = mode; rebuildPlayOrder()
    }

    private fun loadSong(idx: Int) { if (idx in playOrder.indices) { player.setMediaItem(MediaItem.fromUri(playOrder[idx].uri)); player.prepare(); currentIndex = idx; currentSongUri = playOrder[idx].uri } }
    private fun playSong(idx: Int) { if (idx in playOrder.indices) { player.setMediaItem(MediaItem.fromUri(playOrder[idx].uri)); player.prepare(); player.play(); currentIndex = idx; currentSongUri = playOrder[idx].uri } }
    private fun nextSongManual() = if(playOrder.isNotEmpty()) playSong((currentIndex + 1) % playOrder.size) else Unit
    private fun prevSongManual() = if(playOrder.isNotEmpty()) playSong(if (currentIndex <= 0) playOrder.size - 1 else currentIndex - 1) else Unit
    private fun nextSongAuto() = nextSongManual()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavHostController, playlist: List<Song>, currentSongUri: Uri?,
    mode: PlayMode, isRandom: Boolean, selectedUris: Set<Uri>,
    progress: Float, duration: Long, position: Long, isPlaying: Boolean,
    themeMode: ThemeMode, themeHue: Float, searchQuery: String,
    lang: AppLanguage, showArtist: Boolean,
    onSearchChange: (String) -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit,
    onPrev: () -> Unit, onSeek: (Float) -> Unit, onSelectSong: (Uri) -> Unit,
    onChangeMode: (PlayMode) -> Unit, onToggleRandom: () -> Unit
) {
    val filtered = playlist.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) }
    val listState = rememberLazyListState()
    var sliderDraggingValue by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(currentSongUri) {
        val idx = filtered.indexOfFirst { it.uri == currentSongUri }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(getStr("app_name", lang), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { navController.navigate("settings") }) { Icon(Icons.Default.Settings, null) }
                }
                OutlinedTextField(
                    value = searchQuery, onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(getStr("search_hint", lang)) },
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
                        onValueChangeFinished = { sliderDraggingValue?.let { onSeek(it) }; sliderDraggingValue = null },
                        modifier = Modifier.height(20.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val dPos = if (sliderDraggingValue != null) (sliderDraggingValue!! * duration).toLong() else position
                        Text(formatTime(dPos), style = MaterialTheme.typography.labelSmall)
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
                Text(getStr("no_music", lang), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline)
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(filtered) { song ->
                        SongItem(song, song.uri == currentSongUri, selectedUris.contains(song.uri), themeMode, themeHue, showArtist) { onSelectSong(song.uri) }
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
fun SongItem(song: Song, isCurrent: Boolean, isSelected: Boolean, themeMode: ThemeMode, themeHue: Float, showArtist: Boolean, onClick: () -> Unit) {
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
            if (showArtist) {
                Text(song.artist, color = if (isSelected) textColor.copy(0.7f) else MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        if (isCurrent) Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun SettingsScreen(
    themeMode: ThemeMode, themeHue: Float, lang: AppLanguage, showArtist: Boolean,
    onThemeChange: (ThemeMode) -> Unit, onHueChange: (Float) -> Unit,
    onLangChange: (AppLanguage) -> Unit, onShowArtistChange: (Boolean) -> Unit,
    onPickFolder: () -> Unit, onHelp: () -> Unit, onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                Text(getStr("settings", lang), style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text(getStr("appearance", lang), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().clickable { onThemeChange(ThemeMode.light) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(themeMode == ThemeMode.light, { onThemeChange(ThemeMode.light) }); Text(getStr("light_theme", lang)) }
                    Row(modifier = Modifier.fillMaxWidth().clickable { onThemeChange(ThemeMode.dark) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(themeMode == ThemeMode.dark, { onThemeChange(ThemeMode.dark) }); Text(getStr("dark_theme", lang)) }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(getStr("language", lang), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().clickable { onLangChange(AppLanguage.CN) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(lang == AppLanguage.CN, { onLangChange(AppLanguage.CN) }); Text(getStr("lang_cn", lang)) }
                    Row(modifier = Modifier.fillMaxWidth().clickable { onLangChange(AppLanguage.EN) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(lang == AppLanguage.EN, { onLangChange(AppLanguage.EN) }); Text(getStr("lang_en", lang)) }
                }
            }

            Spacer(Modifier.height(24.dp))
            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(getStr("show_artist", lang))
                    Switch(checked = showArtist, onCheckedChange = onShowArtistChange)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(getStr("theme_hue", lang), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            HueSlider(themeHue, lang, onHueChange)

            Spacer(Modifier.height(32.dp))
            Text(getStr("library", lang), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onPickFolder, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text(getStr("change_folder", lang)) }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onHelp, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.HelpOutline, null); Spacer(Modifier.width(8.dp)); Text(getStr("help", lang)) }
        }
    }
}

@Composable
fun HueSlider(hue: Float, lang: AppLanguage, onHueChange: (Float) -> Unit) {
    var draggingHue by remember { mutableStateOf<Float?>(null) }
    val dHue = draggingHue ?: hue
    Column {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp))
                .background(Brush.horizontalGradient(List(36) { Color.hsv(it * 10f, 0.8f, 1f) }))
                .pointerInput(Unit) { detectTapGestures { onHueChange((it.x / size.width).coerceIn(0f, 1f) * 360f) } }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { draggingHue = (it.x / size.width).coerceIn(0f, 1f) * 360f },
                        onDragEnd = { draggingHue?.let(onHueChange); draggingHue = null },
                        onDragCancel = { draggingHue = null },
                        onDrag = { change, _ ->
                            change.consume()
                            val nh = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                            draggingHue = nh
                            onHueChange(nh)
                        }
                    )
                }
        ) {
            Box(
                Modifier.size(44.dp).offset { IntOffset(((dHue / 360f * maxWidth.toPx()) - (44.dp.toPx() / 2)).roundToInt(), 0) }
                    .background(Color.White, CircleShape).padding(4.dp)
            ) {
                Box(Modifier.fillMaxSize().background(Color.hsv(dHue, 0.8f, 1f), CircleShape))
            }
        }
        Text(getStr("hue_hint", lang), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun HelpScreen(lang: AppLanguage, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                Text(getStr("help", lang), style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            HelpCard(getStr("mode_list", lang), getStr("mode_list_desc", lang), Icons.Default.List)
            HelpCard(getStr("mode_cycle", lang), getStr("mode_cycle_desc", lang), Icons.Default.Repeat)
            HelpCard(getStr("mode_shuffle", lang), getStr("mode_shuffle_desc", lang), Icons.Default.Shuffle)
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