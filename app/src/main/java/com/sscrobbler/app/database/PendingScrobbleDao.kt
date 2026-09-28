package com.sscrobbler.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingScrobbleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pendingScrobble: PendingScrobbleEntity): Long

    @Query("SELECT * FROM pending_scrobbles ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<PendingScrobbleEntity>>

    @Query("SELECT * FROM pending_scrobbles ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingScrobbleEntity>

    @Query("SELECT * FROM pending_scrobbles ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getBatch(limit: Int = 50): List<PendingScrobbleEntity>

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    suspend fun count(): Int

    @Query("DELETE FROM pending_scrobbles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_scrobbles WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM pending_scrobbles WHERE fingerprint = :fingerprint")
    suspend fun deleteByFingerprint(fingerprint: String)

    @Query("DELETE FROM pending_scrobbles")
    suspend fun deleteAll()
}
