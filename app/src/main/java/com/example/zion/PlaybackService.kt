package com.example.zion

import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null

    companion object {
        const val CUSTOM_COMMAND_STOP = "STOP_APP"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        // Previous Button
        val prevButton = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setIconResId(android.R.drawable.ic_media_previous)
            .setDisplayName("Previous")
            .build()

        // Next Button
        val nextButton = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setIconResId(android.R.drawable.ic_media_next)
            .setDisplayName("Next")
            .build()

        // Stop Button
        val stopButton = CommandButton.Builder()
            .setSessionCommand(SessionCommand(CUSTOM_COMMAND_STOP, Bundle.EMPTY))
            .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
            .setDisplayName("Close")
            .build()

        val callback = object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootMetadata = MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setTitle("ZION_ROOT")
                    .build()
                
                val rootItem = MediaItem.Builder()
                    .setMediaId("root")
                    .setMediaMetadata(rootMetadata)
                    .build()
                
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            }

            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(CUSTOM_COMMAND_STOP, Bundle.EMPTY))
                    .build()
                
                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) // FIX: Important for Triple-Click
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)     // FIX: Important for Double-Click
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .build()

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == CUSTOM_COMMAND_STOP) {
                    session.player.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    System.exit(0)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(pendingIntent)
            .setCustomLayout(ImmutableList.of(prevButton, nextButton, stopButton))
            .build()

        val defaultProvider = DefaultMediaNotificationProvider.Builder(this).build()
        
        setMediaNotificationProvider(object : MediaNotification.Provider {
            override fun createNotification(
                session: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                val mediaNotification = defaultProvider.createNotification(
                    session, ImmutableList.of(prevButton, nextButton, stopButton), actionFactory, onNotificationChangedCallback
                )
                
                val artworkData = session.player.mediaMetadata.artworkData
                if (artworkData != null) {
                    val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
                    mediaNotification.notification.largeIcon = bitmap
                }
                
                mediaNotification.notification.color = 0xFF00E5FF.toInt()
                mediaNotification.notification.extras.putBoolean(android.app.Notification.EXTRA_COLORIZED, true)
                mediaNotification.notification.extras.putIntArray("android.compactActions", intArrayOf(0, 1, 2))
                
                return mediaNotification
            }

            override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean {
                return defaultProvider.handleCustomCommand(session, action, extras)
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    override fun onDestroy() {
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }
}
