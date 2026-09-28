package com.sscrobbler.app.database

import androidx.room.TypeConverter
import com.sscrobbler.app.model.ScrobbleStatus

class Converters {
    @TypeConverter
    fun fromStatus(status: ScrobbleStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): ScrobbleStatus = runCatching {
        ScrobbleStatus.valueOf(value)
    }.getOrDefault(ScrobbleStatus.Failed)
}
