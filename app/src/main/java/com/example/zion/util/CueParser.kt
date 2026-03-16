package com.example.zion.util

import com.example.zion.model.CueSheet
import com.example.zion.model.CueTrack

object CueParser {
    fun parse(content: String): CueSheet? {
        val lines = content.lines().map { it.trim() }
        var fileName = ""
        val tracks = mutableListOf<CueTrack>()
        
        var currentTrackNumber: Int? = null
        var currentTitle: String? = null
        var currentPerformer: String? = null

        for (line in lines) {
            when {
                line.startsWith("FILE") -> {
                    fileName = line.substringAfter("\"").substringBeforeLast("\"")
                }
                line.startsWith("TRACK") -> {
                    currentTrackNumber = line.split(" ")[1].toIntOrNull()
                }
                line.startsWith("TITLE") && currentTrackNumber != null -> {
                    currentTitle = line.substringAfter("\"").substringBeforeLast("\"")
                }
                line.startsWith("PERFORMER") && currentTrackNumber != null -> {
                    currentPerformer = line.substringAfter("\"").substringBeforeLast("\"")
                }
                line.startsWith("INDEX 01") && currentTrackNumber != null -> {
                    val timeStr = line.split(" ").last()
                    val timeMs = parseTimestamp(timeStr)
                    tracks.add(CueTrack(currentTrackNumber, currentTitle ?: "Unknown", currentPerformer, timeMs))
                    currentTrackNumber = null
                    currentTitle = null
                    currentPerformer = null
                }
            }
        }
        return if (fileName.isNotEmpty()) CueSheet(fileName, tracks) else null
    }

    private fun parseTimestamp(ts: String): Long {
        val parts = ts.split(":")
        if (parts.size != 3) return 0
        val min = parts[0].toLongOrNull() ?: 0
        val sec = parts[1].toLongOrNull() ?: 0
        val frames = parts[2].toLongOrNull() ?: 0
        return (min * 60 * 1000) + (sec * 1000) + (frames * 1000 / 75)
    }
}
