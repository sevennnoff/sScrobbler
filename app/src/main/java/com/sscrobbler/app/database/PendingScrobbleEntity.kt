package com.sscrobbler.app.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_scrobbles",
    indices = [Index(value = ["fingerprint"], unique = true)]
)
data class PendingScrobbleEntity(
    @PrimaryKey
    val id: String,
    val artist: String,
    val title: String,
    val album: String?,
    val albumArtist: String?,
    val durationSeconds: Int?,
    val timestamp: Long,
    val sourcePackage: String,
    val createdAt: Long,
    val fingerprint: String
)
