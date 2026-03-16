package com.example.zion.model

import android.net.Uri

data class Track(
    val title: String,
    val artist: String?,
    val durationMs: Long = 0,
    val uri: Uri,
    val isCueTrack: Boolean = false,
    val startTimeMs: Long = 0,
    val parentFileName: String? = null,
    val artwork: ByteArray? = null
)

data class CueSheet(
    val file: String,
    val tracks: List<CueTrack>
)

data class CueTrack(
    val number: Int,
    val title: String,
    val performer: String?,
    val startTimeMs: Long
)
