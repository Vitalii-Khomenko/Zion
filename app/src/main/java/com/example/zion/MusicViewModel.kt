package com.example.zion

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.zion.model.Track
import com.example.zion.util.CueParser
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("zion_prefs", Context.MODE_PRIVATE)

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _completedTracks = MutableStateFlow<Set<String>>(emptySet())
    val completedTracks: StateFlow<Set<String>> = _completedTracks

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private var isRestoring = false

    private val supportedExtensions = setOf(
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "aiff", "wma", "alac", 
        "pcm", "amr", "mid", "midi", "mka", "wv", "ape", "au", "dsf", "dff", 
        "mpc", "ra", "rm"
    )

    init {
        // 1. Load persistence data immediately
        val savedCompleted = prefs.getStringSet("completed_tracks_paths", emptySet()) ?: emptySet()
        _completedTracks.value = savedCompleted

        // 2. Setup MediaController
        val sessionToken = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        controllerFuture?.addListener({
            setupController()
            restoreLastState()
        }, MoreExecutors.directExecutor())

        // 3. FAST LOAD last used location
        viewModelScope.launch {
            val lastPath = prefs.getString("last_directory_path", null)
            val lastUri = prefs.getString("last_directory_uri", null)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() && lastPath != null) {
                scanLocalPath(File(lastPath))
            } else if (lastUri != null) {
                loadDirectory(Uri.parse(lastUri), isAutoLoad = true)
            }
        }

        // 4. Background Progress Tracking
        viewModelScope.launch {
            while (isActive) {
                controller?.let {
                    if (it.isPlaying) {
                        _currentPosition.value = it.currentPosition
                        savePlaybackState(it.currentPosition)
                        checkTrackCompletion(it.currentPosition, it.duration)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun setupController() {
        val controller = this.controller ?: return
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (!isPlaying) {
                    savePlaybackState(controller.currentPosition)
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentTrackFromMediaItem(mediaItem)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _duration.value = controller.duration
                    if (isRestoring) {
                        val lastPosition = prefs.getLong("last_track_position", 0L)
                        controller.seekTo(lastPosition)
                        _currentPosition.value = lastPosition
                        isRestoring = false
                    }
                }
                if (playbackState == Player.STATE_ENDED) {
                    _currentTrack.value?.let { markTrackAsCompleted(it) }
                    playNextTrack()
                }
            }
        })
    }

    private fun checkTrackCompletion(current: Long, duration: Long) {
        if (duration > 0) {
            val progress = current.toFloat() / duration.toFloat()
            if (progress >= 0.9f) {
                _currentTrack.value?.let { markTrackAsCompleted(it) }
            }
        }
    }

    private fun markTrackAsCompleted(track: Track) {
        val path = track.uri.toString()
        val currentSet = _completedTracks.value.toMutableSet()
        if (currentSet.add(path)) {
            _completedTracks.value = currentSet
            prefs.edit().putStringSet("completed_tracks_paths", currentSet).apply()
        }
    }

    fun toggleTrackCompleted(track: Track) {
        val path = track.uri.toString()
        val currentSet = _completedTracks.value.toMutableSet()
        if (currentSet.contains(path)) {
            currentSet.remove(path)
        } else {
            currentSet.add(path)
        }
        _completedTracks.value = currentSet
        prefs.edit().putStringSet("completed_tracks_paths", currentSet).apply()
    }

    fun markTrackAsUnplayed(track: Track) {
        val path = track.uri.toString()
        val currentSet = _completedTracks.value.toMutableSet()
        if (currentSet.remove(path)) {
            _completedTracks.value = currentSet
            prefs.edit().putStringSet("completed_tracks_paths", currentSet).apply()
        }
    }

    private fun restoreLastState() {
        val lastTrackUri = prefs.getString("last_track_uri", null) ?: return
        
        viewModelScope.launch {
            // Wait for tracks to be loaded (max 5 seconds)
            var attempts = 0
            while (_tracks.value.isEmpty() && attempts < 25) {
                delay(250)
                attempts++
            }
            
            val track = _tracks.value.find { it.uri.toString() == lastTrackUri }
            if (track != null && _currentTrack.value == null) {
                isRestoring = true
                prepareTrack(track)
            }
        }
    }

    private fun savePlaybackState(position: Long) {
        val current = _currentTrack.value ?: return
        if (position > 0) {
            prefs.edit()
                .putString("last_track_uri", current.uri.toString())
                .putLong("last_track_position", position)
                .apply()
        }
    }

    private fun updateCurrentTrackFromMediaItem(mediaItem: MediaItem?) {
        if (mediaItem == null) return
        val uri = mediaItem.localConfiguration?.uri?.toString() ?: return
        val normalizedUri = uri.substringBefore("_") 
        _currentTrack.value = _tracks.value.find { it.uri.toString().contains(normalizedUri) }
    }

    fun scanAllMusic() {
        val musicFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        // Persist the root path so it auto-loads next time
        prefs.edit().putString("last_directory_path", musicFolder.absolutePath).apply()
        
        viewModelScope.launch {
            _isLoading.value = true
            val scannedTracks = withContext(Dispatchers.IO) {
                recursiveScan(musicFolder)
            }
            _tracks.value = scannedTracks
            _isLoading.value = false
        }
    }

    fun scanLocalPath(file: File) {
        prefs.edit().putString("last_directory_path", file.absolutePath).apply()
        viewModelScope.launch {
            _isLoading.value = true
            val scannedTracks = withContext(Dispatchers.IO) {
                recursiveScan(file)
            }
            _tracks.value = scannedTracks
            _isLoading.value = false
        }
    }

    private fun recursiveScan(file: File, depth: Int = 0): List<Track> {
        // Prevent infinite recursion on deeply nested directories
        if (depth > 20) return emptyList()
        
        val trackList = mutableListOf<Track>()
        val files = file.listFiles() ?: return emptyList()
        
        val cueFiles = mutableListOf<File>()
        val audioFiles = mutableMapOf<String, File>()

        files.forEach { f ->
            if (f.isDirectory) {
                trackList.addAll(recursiveScan(f, depth + 1))
            } else {
                val ext = f.extension.lowercase()
                if (supportedExtensions.contains(ext)) {
                    audioFiles[f.name] = f
                } else if (ext == "cue") {
                    cueFiles.add(f)
                }
            }
        }

        val handledFiles = mutableSetOf<String>()
        cueFiles.forEach { cueFile ->
            try {
                val content = cueFile.readText()
                val cueSheet = CueParser.parse(content)
                cueSheet?.let { sheet ->
                    if (audioFiles.containsKey(sheet.file)) {
                        val audioFile = audioFiles[sheet.file]!!
                        handledFiles.add(sheet.file)
                        sheet.tracks.forEach { cueTrack ->
                            trackList.add(
                                Track(
                                    title = cueTrack.title,
                                    artist = cueTrack.performer,
                                    uri = Uri.fromFile(audioFile),
                                    isCueTrack = true,
                                    startTimeMs = cueTrack.startTimeMs,
                                    parentFileName = sheet.file,
                                    artwork = null 
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        audioFiles.forEach { (name, f) ->
            if (!handledFiles.contains(name)) {
                trackList.add(
                    Track(
                        title = f.nameWithoutExtension,
                        artist = "Unknown Artist",
                        uri = Uri.fromFile(f),
                        artwork = null 
                    )
                )
            }
        }
        return trackList
    }

    fun loadDirectory(directoryUri: Uri, isAutoLoad: Boolean = false) {
        val context = getApplication<Application>().applicationContext
        
        val lastUriString = prefs.getString("last_directory_uri", null)
        if (!isAutoLoad && lastUriString != null && lastUriString != directoryUri.toString()) {
            _completedTracks.value = emptySet()
            prefs.edit().remove("completed_tracks_paths").apply()
        }

        try {
            if (directoryUri.scheme == "content") {
                context.contentResolver.takePersistableUriPermission(
                    directoryUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            prefs.edit().putString("last_directory_uri", directoryUri.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        viewModelScope.launch {
            _isLoading.value = true
            val scannedTracks = withContext(Dispatchers.IO) {
                scanFolder(directoryUri)
            }
            _tracks.value = scannedTracks
            _isLoading.value = false
        }
    }

    private fun Track.toMediaItem(context: Context, includeArtwork: Boolean): MediaItem {
        val builder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
        
        if (includeArtwork) {
            val art = getArtworkDataFromUri(context, uri)
            if (art != null) {
                builder.setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }

        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString() + "_" + startTimeMs)
            .setMediaMetadata(builder.build())
            .build()
    }

    @OptIn(UnstableApi::class)
    fun playTrack(track: Track) {
        val controller = this.controller ?: return
        val context = getApplication<Application>().applicationContext
        isRestoring = false
        _currentTrack.value = track
        
        viewModelScope.launch {
            val mediaItems = _tracks.value.map { it.toMediaItem(context, it == track) }
            val index = _tracks.value.indexOf(track)
            if (index != -1) {
                controller.setMediaItems(mediaItems, index, if (track.isCueTrack) track.startTimeMs else 0L)
                controller.prepare()
                controller.play()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun prepareTrack(track: Track) {
        val controller = this.controller ?: return
        val context = getApplication<Application>().applicationContext
        _currentTrack.value = track
        
        viewModelScope.launch {
            val mediaItems = _tracks.value.map { it.toMediaItem(context, it == track) }
            val index = _tracks.value.indexOf(track)
            if (index != -1) {
                controller.setMediaItems(mediaItems, index, 0L)
                controller.prepare()
            }
        }
    }

    fun playNextTrack() {
        controller?.seekToNextMediaItem()
    }

    fun playPreviousTrack() {
        controller?.seekToPreviousMediaItem()
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Stop playback if this track is currently playing
                if (_currentTrack.value == track) {
                    controller?.stop()
                    _currentTrack.value = null
                }

                val context = getApplication<Application>()
                val deleted = when (track.uri.scheme) {
                    "file" -> {
                        val file = File(track.uri.path ?: "")
                        if (file.exists()) file.delete() else false
                    }
                    "content" -> {
                        try {
                            context.contentResolver.delete(track.uri, null, null) > 0
                        } catch (e: Exception) {
                            false
                        }
                    }
                    else -> false
                }

                if (deleted) {
                    // Remove from tracks list
                    val updatedTracks = _tracks.value.filter { it.uri.toString() != track.uri.toString() }
                    _tracks.value = updatedTracks

                    // Remove from completed tracks if it exists
                    val currentSet = _completedTracks.value.toMutableSet()
                    currentSet.remove(track.uri.toString())
                    _completedTracks.value = currentSet
                    prefs.edit().putStringSet("completed_tracks_paths", currentSet).apply()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun shuffle() {
        val currentList = _tracks.value
        if (currentList.isNotEmpty()) {
            val randomIndex = Random.nextInt(currentList.size)
            playTrack(currentList[randomIndex])
        }
    }

    fun seekTo(position: Long) {
        controller?.seekTo(position)
        _currentPosition.value = position
        savePlaybackState(position)
    }

    fun togglePlayPause() {
        controller?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        controller?.playbackParameters = PlaybackParameters(speed)
    }

    fun stopAndExit() {
        controller?.stop()
        val intent = Intent(getApplication<Application>(), PlaybackService::class.java)
        getApplication<Application>().stopService(intent)
        // Request activity termination instead of forcefully exiting
        try {
            val context = getApplication<Application>() as? android.app.Activity
            context?.finishAffinity()
        } catch (e: Exception) {
            // Fallback for non-activity context
            Runtime.getRuntime().exit(0)
        }
    }

    override fun onCleared() {
        super.onCleared()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }

    private fun scanFolder(directoryUri: Uri): List<Track> {
        val context = getApplication<Application>().applicationContext
        val rootDoc = DocumentFile.fromTreeUri(context, directoryUri) ?: return emptyList()
        val trackList = mutableListOf<Track>()
        val cueFiles = mutableListOf<DocumentFile>()
        val audioFiles = mutableMapOf<String, DocumentFile>()

        rootDoc.listFiles().forEach { file ->
            val name = file.name ?: ""
            val ext = name.substringAfterLast(".", "").lowercase()
            if (supportedExtensions.contains(ext)) {
                audioFiles[name] = file
            } else if (ext == "cue") {
                cueFiles.add(file)
            }
        }

        cueFiles.forEach { cueDoc ->
            try {
                val content = context.contentResolver.openInputStream(cueDoc.uri)?.use { it.bufferedReader().readText() } ?: ""
                val cueSheet = CueParser.parse(content)
                cueSheet?.let { sheet ->
                    if (audioFiles.containsKey(sheet.file)) {
                        val audioDoc = audioFiles[sheet.file]!!
                        sheet.tracks.forEach { cueTrack ->
                            trackList.add(Track(cueTrack.title, cueTrack.performer, 0, audioDoc.uri, true, cueTrack.startTimeMs, sheet.file, null))
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        audioFiles.forEach { (name, doc) ->
            if (trackList.none { it.parentFileName == name }) {
                trackList.add(Track(name.substringBeforeLast("."), "Unknown Artist", 0, doc.uri, false, 0, null, null))
            }
        }
        return trackList
    }

    private fun getArtworkDataFromUri(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val art = retriever.embeddedPicture
            if (art != null) {
                val bitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 200, 200, true)
                val out = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                out.toByteArray()
            } else null
        } catch (e: Exception) { null } finally { try { retriever.release() } catch (e: Exception) {} }
    }
}
