package com.sscrobbler.app.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.sscrobbler.app.model.ScrobbleStatus

@Entity(tableName = "history_items")
data class HistoryItemEntity(
    @PrimaryKey
    val id: String,
    val artist: String,
    val title: String,
    val album: String?,
    val durationSeconds: Int?,
    val listenedSeconds: Int,
    val timestamp: Long,
    val status: ScrobbleStatus,
    val sourcePackage: String,
    val artworkUrl: String? = null
)
