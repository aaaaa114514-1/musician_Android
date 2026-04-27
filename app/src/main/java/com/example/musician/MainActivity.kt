package com.example.musician

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

data class Song(
    val uri: Uri,
    val title: String,
    val artist: String = "Unknown Artist",
    val modified: Long
)

enum class PlayMode { list, cycle }
enum class ThemeMode { light, dark }
enum class AppLanguage { CN, EN }

fun getStr(id: String, lang: AppLanguage): String {
    val isCN = lang == AppLanguage.CN
    return when (id) {
        "app_name" -> "Musician"
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
        "sleep_timer" -> if (isCN) "定时关闭" else "Sleep Timer"
        "timer_set_time" -> if (isCN) "按时刻停止" else "At Time"
        "timer_set_duration" -> if (isCN) "按时长停止" else "After Duration"
        "timer_cancel" -> if (isCN) "取消定时" else "Cancel"
        "timer_running" -> if (isCN) "将于 %s 停止播放" else "Stops at %s"
        "timer_status" -> if (isCN) "当前计划：%s" else "Scheduled: %s"
        else -> id
    }
}

class MusicService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        super.onDestroy()
    }
}

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var player: Player? by mutableStateOf(null)
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

    var sleepTimerTime by mutableLongStateOf(0L)

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("folder", uri.toString()).apply()
            lifecycleScope.launch { scanFolder(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("musician", Context.MODE_PRIVATE)

        themeMode = if (prefs.getString("theme", "light") == "dark") ThemeMode.dark else ThemeMode.light
        themeHue = prefs.getFloat("hue", 260f)
        showArtist = prefs.getBoolean("show_artist", true)
        appLanguage = AppLanguage.valueOf(prefs.getString("lang", "CN")!!)

        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val controller = controllerFuture?.get()
            player = controller
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentSongUri = mediaItem?.localConfiguration?.uri
                    val idx = playOrder.indexOfFirst { it.uri == currentSongUri }
                    if (idx >= 0) currentIndex = idx
                }
            })
            val folder = prefs.getString("folder", null)
            if (folder != null) { lifecycleScope.launch { scanFolder(Uri.parse(folder)) } }
        }, MoreExecutors.directExecutor())

        lifecycleScope.launch {
            snapshotFlow { themeHue }.collect { hue ->
                delay(800)
                withContext(Dispatchers.IO) { prefs.edit().putFloat("hue", hue).apply() }
            }
        }

        lifecycleScope.launch {
            while (true) {
                player?.let { p ->
                    if (p.duration > 0) {
                        duration = p.duration
                        position = p.currentPosition
                        progress = p.currentPosition.toFloat() / p.duration.toFloat()
                    }
                }
                if (sleepTimerTime > 0 && System.currentTimeMillis() >= sleepTimerTime) {
                    player?.pause()
                    sleepTimerTime = 0
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
                NavHost(
                    navController = navController,
                    startDestination = "main",
                    modifier = Modifier.background(MaterialTheme.colorScheme.background),
                    enterTransition = { fadeIn(tween(250)) + slideInHorizontally(tween(300, easing = EaseOutQuart)) { it / 8 } },
                    exitTransition = { fadeOut(tween(250)) + slideOutHorizontally(tween(300, easing = EaseOutQuart)) { -it / 8 } },
                    popEnterTransition = { fadeIn(tween(250)) + slideInHorizontally(tween(300, easing = EaseOutQuart)) { -it / 8 } },
                    popExitTransition = { fadeOut(tween(250)) + slideOutHorizontally(tween(300, easing = EaseOutQuart)) { it / 8 } }
                ) {
                    composable("main") {
                        MainScreen(
                            navController, playlist, currentSongUri, playMode, isRandom, selectedUris,
                            progress, duration, position, isPlaying, themeMode, themeHue, searchQuery,
                            appLanguage, showArtist, sleepTimerTime,
                            onSearchChange = { searchQuery = it },
                            onPlayPause = { player?.let { if (it.isPlaying) it.pause() else it.play() } },
                            onNext = { player?.seekToNext() },
                            onPrev = { player?.seekToPrevious() },
                            onSeek = { player?.seekTo((it * duration).toLong()) },
                            onSelectSong = { handleSongSelection(it) },
                            onChangeMode = { changeMode(it) },
                            onToggleRandom = { isRandom = !isRandom; rebuildPlayOrder() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            themeMode, themeHue, appLanguage, showArtist, sleepTimerTime,
                            onThemeChange = { themeMode = it; prefs.edit().putString("theme", it.name).apply() },
                            onHueChange = { themeHue = it },
                            onLangChange = { appLanguage = it; prefs.edit().putString("lang", it.name).apply() },
                            onShowArtistChange = { showArtist = it; prefs.edit().putBoolean("show_artist", it).apply() },
                            onPickFolder = { folderPicker.launch(null) },
                            onHelp = { navController.navigate("help") },
                            onBack = { navController.popBackStack() },
                            onSetTimer = { sleepTimerTime = it }
                        )
                    }
                    composable("help") { HelpScreen(appLanguage) { navController.popBackStack() } }
                }
            }
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
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
            withContext(Dispatchers.Main) {
                playlist = fast; rebuildPlayOrder()
                if (currentSongUri == null && playOrder.isNotEmpty()) loadSong(0)
            }
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
        if (base.isEmpty()) return

        val activeUri = currentSongUri ?: playOrder.getOrNull(currentIndex)?.uri

        if (isRandom) {
            val current = base.find { it.uri == activeUri }
            val others = base.filter { it.uri != activeUri }.shuffled()
            playOrder = if (current != null) listOf(current) + others else others
            currentIndex = if (current != null) 0 else -1
        } else {
            playOrder = base
            val idx = playOrder.indexOfFirst { it.uri == activeUri }
            currentIndex = if (idx >= 0) idx else 0
        }

        syncPlayerQueue()
    }

    private fun syncPlayerQueue() {
        player?.let { p ->
            val mediaItems = playOrder.map { song ->
                MediaItem.Builder()
                    .setUri(song.uri)
                    .setMediaId(song.uri.toString())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build())
                    .build()
            }

            val lastPos = p.currentPosition
            p.setMediaItems(mediaItems, currentIndex, lastPos)
            p.repeatMode = if (playMode == PlayMode.cycle) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
        }
    }

    private fun changeMode(mode: PlayMode) {
        if (mode == PlayMode.list) selectedUris = emptySet()
        else if (selectedUris.isEmpty() && currentSongUri != null) selectedUris = setOf(currentSongUri!!)
        playMode = mode; rebuildPlayOrder()
    }

    private fun loadSong(idx: Int) {
        if (idx in playOrder.indices) {
            currentIndex = idx
            currentSongUri = playOrder[idx].uri
            player?.seekTo(idx, 0)
        }
    }

    private fun playSong(idx: Int) {
        if (idx in playOrder.indices) {
            currentIndex = idx
            currentSongUri = playOrder[idx].uri
            player?.seekTo(idx, 0)
            player?.play()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavHostController, playlist: List<Song>, currentSongUri: Uri?,
    mode: PlayMode, isRandom: Boolean, selectedUris: Set<Uri>,
    progress: Float, duration: Long, position: Long, isPlaying: Boolean,
    themeMode: ThemeMode, themeHue: Float, searchQuery: String,
    lang: AppLanguage, showArtist: Boolean, sleepTimerTime: Long,
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

                AnimatedVisibility(visible = sleepTimerTime > 0, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Timer, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(getStr("timer_running", lang).format(formatClockTime(sleepTimerTime)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
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
                    items(filtered, key = { it.uri.toString() }) { song ->
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
    themeMode: ThemeMode, themeHue: Float, lang: AppLanguage, showArtist: Boolean, sleepTimerTime: Long,
    onThemeChange: (ThemeMode) -> Unit, onHueChange: (Float) -> Unit,
    onLangChange: (AppLanguage) -> Unit, onShowArtistChange: (Boolean) -> Unit,
    onPickFolder: () -> Unit, onHelp: () -> Unit, onBack: () -> Unit,
    onSetTimer: (Long) -> Unit
) {
    var selectedHour by remember { mutableIntStateOf(0) }
    var selectedMin by remember { mutableIntStateOf(30) }

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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(getStr("sleep_timer", lang), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (sleepTimerTime > 0) {
                    Text(getStr("timer_status", lang).format(formatClockTime(sleepTimerTime)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), tonalElevation = 4.dp) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier.height(160.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        WheelPicker(count = 24, initialValue = selectedHour, onValueChange = { selectedHour = it })
                        Text(":", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
                        WheelPicker(count = 60, initialValue = selectedMin, onValueChange = { selectedMin = it })
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val now = Calendar.getInstance()
                                val target = Calendar.getInstance().apply {
                                    set(Calendar.HOUR_OF_DAY, selectedHour)
                                    set(Calendar.MINUTE, selectedMin)
                                    set(Calendar.SECOND, 0)
                                }
                                if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
                                onSetTimer(target.timeInMillis)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(getStr("timer_set_time", lang), style = MaterialTheme.typography.labelSmall) }

                        Button(
                            onClick = {
                                val target = System.currentTimeMillis() + (selectedHour.toLong() * 3600000L) + (selectedMin.toLong() * 60000L)
                                onSetTimer(target)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(getStr("timer_set_duration", lang), style = MaterialTheme.typography.labelSmall) }
                    }
                    if (sleepTimerTime > 0) {
                        TextButton(onClick = { onSetTimer(0L) }) {
                            Text(getStr("timer_cancel", lang), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(count: Int, initialValue: Int, onValueChange: (Int) -> Unit) {
    val itemHeight = 48.dp
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % count) + initialValue - 1)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val centerIndex = listState.firstVisibleItemIndex + 1
            onValueChange(centerIndex % count)
        }
    }

    Box(modifier = Modifier.width(80.dp).height(itemHeight * 3), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxWidth().height(itemHeight).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)))
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 0.dp)
        ) {
            items(Int.MAX_VALUE) { index ->
                val value = index % count
                val offset = remember { derivedStateOf {
                    val layoutInfo = listState.layoutInfo
                    val visibleItem = layoutInfo.visibleItemsInfo.find { it.index == index }
                    if (visibleItem != null) {
                        val viewCenter = layoutInfo.viewportEndOffset / 2f
                        val itemCenter = visibleItem.offset + visibleItem.size / 2f
                        abs(viewCenter - itemCenter) / (layoutInfo.viewportEndOffset / 2f)
                    } else 1f
                }}.value

                Box(
                    modifier = Modifier.fillMaxWidth().height(itemHeight).graphicsLayer {
                        alpha = 1f - (offset * 0.6f).coerceIn(0f, 0.7f)
                        scaleX = 1f - (offset * 0.3f).coerceIn(0f, 0.3f)
                        scaleY = 1f - (offset * 0.3f).coerceIn(0f, 0.3f)
                        rotationX = offset * 45f
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "%02d".format(value),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = if (offset < 0.2f) FontWeight.Bold else FontWeight.Normal,
                            fontSize = if (offset < 0.2f) 28.sp else 22.sp
                        ),
                        color = if (offset < 0.2f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
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

fun formatClockTime(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}