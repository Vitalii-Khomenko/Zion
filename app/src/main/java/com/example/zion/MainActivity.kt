package com.example.zion

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zion.model.Track
import com.example.zion.ui.theme.CyanAccent
import com.example.zion.ui.theme.VioletAccent
import com.example.zion.ui.theme.ZionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZionTheme {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as android.app.Activity).window
                        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
                    }
                }
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MusicViewModel = viewModel()) {
    val context = LocalContext.current
    val tracks by viewModel.tracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val completedTracks by viewModel.completedTracks.collectAsState()

    val hasFullAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadDirectory(it) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { launcher.launch(null) },
                containerColor = CyanAccent,
                contentColor = Color.Black,
                modifier = Modifier.navigationBarsPadding() 
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Open Folder")
            }
        },
        bottomBar = {
            currentTrack?.let { track ->
                PlayerBar(
                    track = track,
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    onTogglePlay = { viewModel.togglePlayPause() },
                    onNext = { viewModel.playNextTrack() },
                    onPrevious = { viewModel.playPreviousTrack() },
                    onShuffle = { viewModel.shuffle() },
                    onSeek = { viewModel.seekTo(it) },
                    onExit = { viewModel.stopAndExit() },
                    viewModel = viewModel
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (tracks.isEmpty()) {
                EmptyState(
                    hasFullAccess = hasFullAccess,
                    isLoading = isLoading,
                    onGrantAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    },
                    onRefresh = { viewModel.scanAllMusic() }
                )
            } else {
                TrackList(
                    tracks = tracks, 
                    currentTrack = currentTrack,
                    completedTracks = completedTracks,
                    onTrackClick = { viewModel.playTrack(it) },
                    onTrackLongClick = { 
                        vibrate(context)
                        viewModel.toggleTrackCompleted(it)
                    }
                )
                
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        color = CyanAccent,
                        trackColor = Color.Transparent
                    )
                }
            }
        }
    }
}

private fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
}

@Composable
fun PlayerBar(
    track: Track,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onSeek: (Long) -> Unit,
    onExit: () -> Unit,
    viewModel: MusicViewModel
) {
    val barColor = MaterialTheme.colorScheme.surfaceVariant
    val context = LocalContext.current
    val view = LocalView.current
    
    val speeds = listOf(0.5f, 1.0f, 1.5f, 2.0f)
    var currentSpeedIndex by remember { mutableStateOf(1) }

    var currentVolumePercent by remember { mutableStateOf(0) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    LaunchedEffect(Unit) {
        while (true) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            currentVolumePercent = (current.toFloat() / max.toFloat() * 100).toInt()
            delay(500)
        }
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            window.navigationBarColor = android.graphics.Color.argb(255, (barColor.red * 255).toInt(), (barColor.green * 255).toInt(), (barColor.blue * 255).toInt())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(barColor.copy(alpha = 0.9f), barColor)
                ),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .navigationBarsPadding() 
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onShuffle, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Shuffle, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
                }

                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = track.title.uppercase(), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Color.White), textAlign = TextAlign.Center, maxLines = 1)
                    Text(text = (track.artist ?: "UNKNOWN").uppercase(), style = MaterialTheme.typography.labelMedium.copy(color = CyanAccent, letterSpacing = 1.2.sp), textAlign = TextAlign.Center, maxLines = 1)
                }

                IconButton(onClick = onExit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = formatTime(currentPosition), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f)), modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                Slider(value = if (duration > 0) currentPosition.toFloat() else 0f, onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f), modifier = Modifier.weight(1f).height(28.dp), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = CyanAccent, inactiveTrackColor = Color.White.copy(alpha = 0.1f)))
                Text(text = formatTime(duration), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f)), modifier = Modifier.width(40.dp), textAlign = TextAlign.Start)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "$currentVolumePercent%",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.4f)),
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.Center
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val scope = rememberCoroutineScope()
                    
                    // PREVIOUS / REWIND - SECURE LOGIC
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        var isLongPressRunning = false
                                        val job = scope.launch {
                                            delay(500)
                                            isLongPressRunning = true
                                            while (isActive) {
                                                val nextPos = (viewModel.currentPosition.value - 10000).coerceAtLeast(0)
                                                onSeek(nextPos)
                                                if (nextPos == 0L) break
                                                delay(200)
                                            }
                                        }
                                        tryAwaitRelease()
                                        job.cancel()
                                        if (!isLongPressRunning) {
                                            onPrevious()
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    
                    Spacer(modifier = Modifier.width(24.dp))

                    Surface(
                        onClick = onTogglePlay,
                        shape = RoundedCornerShape(percent = 50),
                        color = Color.White,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(36.dp))
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(24.dp))

                    // NEXT / FAST FORWARD - SECURE LOGIC
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        var isLongPressRunning = false
                                        val job = scope.launch {
                                            delay(500)
                                            isLongPressRunning = true
                                            while (isActive) {
                                                val nextPos = (viewModel.currentPosition.value + 10000).coerceAtMost(viewModel.duration.value)
                                                onSeek(nextPos)
                                                if (nextPos == viewModel.duration.value) break
                                                delay(200)
                                            }
                                        }
                                        tryAwaitRelease()
                                        job.cancel()
                                        if (!isLongPressRunning) {
                                            onNext()
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                TextButton(
                    onClick = {
                        currentSpeedIndex = (currentSpeedIndex + 1) % speeds.size
                        viewModel.setPlaybackSpeed(speeds[currentSpeedIndex])
                    },
                    modifier = Modifier.width(48.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(text = "${speeds[currentSpeedIndex]}X", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.4f)))
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

@Composable
fun TrackList(
    tracks: List<Track>, 
    currentTrack: Track?,
    completedTracks: Set<String>,
    onTrackClick: (Track) -> Unit,
    onTrackLongClick: (Track) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 240.dp, start = 24.dp, end = 24.dp, top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(text = "LIBRARY", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 4.sp, color = CyanAccent), modifier = Modifier.padding(bottom = 16.dp))
        }
        items(tracks) { track ->
            TrackItem(
                track = track, 
                isCurrent = track == currentTrack,
                isCompleted = completedTracks.contains(track.uri.toString()),
                onClick = { onTrackClick(track) },
                onLongClick = { onTrackLongClick(track) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackItem(track: Track, isCurrent: Boolean, isCompleted: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    var artworkBitmap by remember(track.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(track.uri) {
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, track.uri)
                val art = retriever.embeddedPicture
                if (art != null) {
                    val bitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
                    artworkBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, 150, 150, true)
                }
            } catch (e: Exception) {
                artworkBitmap = null
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush = if (isCurrent) Brush.linearGradient(listOf(Color.White, Color.White)) else Brush.linearGradient(listOf(CyanAccent, VioletAccent)), alpha = if (isCurrent) 1.0f else 0.1f),
            contentAlignment = Alignment.Center
        ) {
            if (artworkBitmap != null) {
                Image(
                    bitmap = artworkBitmap!!.asImageBitmap(), 
                    contentDescription = null, 
                    modifier = Modifier.fillMaxSize(), 
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.MusicNote, 
                    contentDescription = null, 
                    tint = if (isCurrent) Color.Black else if (track.isCueTrack) VioletAccent else CyanAccent, 
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column {
            Text(
                text = track.title.uppercase(), 
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium, 
                    letterSpacing = 1.sp, 
                    color = if (isCurrent) CyanAccent 
                            else if (isCompleted) Color.White.copy(alpha = 0.35f) 
                            else MaterialTheme.colorScheme.onBackground
                ), 
                maxLines = 1
            )
            Text(
                text = (track.artist ?: "UNKNOWN").uppercase(), 
                style = MaterialTheme.typography.bodySmall.copy(
                    letterSpacing = 1.sp, 
                    color = if (isCurrent) CyanAccent.copy(alpha = 0.7f) 
                            else if (isCompleted) Color.White.copy(alpha = 0.25f) 
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                ), 
                maxLines = 1
            )
        }
    }
}

@Composable
fun EmptyState(hasFullAccess: Boolean, isLoading: Boolean, onGrantAccess: () -> Unit, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(brush = Brush.linearGradient(listOf(CyanAccent, VioletAccent)), shape = RoundedCornerShape(28.dp), alpha = 0.15f),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Z", style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Black, color = CyanAccent, fontSize = 80.sp))
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "ZION", style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.ExtraLight, letterSpacing = 12.sp, color = Color.White))
        Text(text = "PREMIUM OFFLINE AUDIO", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 4.sp, color = CyanAccent.copy(alpha = 0.6f)))
        Spacer(modifier = Modifier.height(48.dp))
        if (isLoading) {
            CircularProgressIndicator(color = CyanAccent)
        } else {
            if (!hasFullAccess) {
                Button(onClick = onGrantAccess, colors = ButtonDefaults.buttonColors(containerColor = CyanAccent), shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GRANT FULL STORAGE ACCESS", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = CyanAccent.copy(alpha = 0.2f)), shape = RoundedCornerShape(8.dp)) {
                    Text("SCAN MUSIC LIBRARY", color = CyanAccent)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "OR SELECT A FOLDER MANUALLY", style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)))
        }
    }
}
