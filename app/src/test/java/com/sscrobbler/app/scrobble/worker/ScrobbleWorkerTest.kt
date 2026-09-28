package com.sscrobbler.app.scrobble.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.sscrobbler.app.database.HistoryDao
import com.sscrobbler.app.database.PendingScrobbleDao
import com.sscrobbler.app.database.PendingScrobbleEntity
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.lastfm.LastFmResult
import com.sscrobbler.app.model.ScrobbleStatus
import com.sscrobbler.app.util.HashUtils
import com.sscrobbler.app.util.NetworkDetector
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ScrobbleWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var pendingScrobbleDao: PendingScrobbleDao
    private lateinit var historyDao: HistoryDao
    private lateinit var lastFmClient: LastFmClient
    private lateinit var authRepository: LastFmAuthRepository
    private lateinit var networkDetector: NetworkDetector

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        pendingScrobbleDao = mockk(relaxed = true)
        historyDao = mockk(relaxed = true)
        lastFmClient = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        networkDetector = mockk(relaxed = true)

        coEvery { authRepository.getSessionKey() } returns "valid_session_key"
        coEvery { networkDetector.isOnline() } returns true
    }

    private fun createSampleEntities(count: Int): List<PendingScrobbleEntity> {
        return (1..count).map { i ->
            PendingScrobbleEntity(
                id = "item-$i",
                artist = "Artist $i",
                title = "Title $i",
                album = "Album $i",
                albumArtist = "Artist $i",
                durationSeconds = 200,
                timestamp = 1700000000L + i,
                sourcePackage = "com.spotify.music",
                createdAt = 1700000000L + i,
                fingerprint = HashUtils.sha256("Artist $i|Title $i|${1700000000L + i}")
            )
        }
    }

    @Test
    fun testOffline_returnsRetry() = runTest {
        coEvery { networkDetector.isOnline() } returns false

        val worker = ScrobbleWorker(
            appContext = context,
            params = workerParams,
            pendingScrobbleDaoOverride = pendingScrobbleDao,
            historyDaoOverride = historyDao,
            lastFmClientOverride = lastFmClient,
            authRepositoryOverride = authRepository,
            networkDetectorOverride = networkDetector
        )

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { lastFmClient.scrobbleBatch(any(), any()) }
    }

    @Test
    fun testEmptyPendingQueue_returnsSuccess() = runTest {
        coEvery { pendingScrobbleDao.getBatch(any()) } returns emptyList()

        val worker = ScrobbleWorker(
            appContext = context,
            params = workerParams,
            pendingScrobbleDaoOverride = pendingScrobbleDao,
            historyDaoOverride = historyDao,
            lastFmClientOverride = lastFmClient,
            authRepositoryOverride = authRepository,
            networkDetectorOverride = networkDetector
        )

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { lastFmClient.scrobbleBatch(any(), any()) }
    }

    @Test
    fun testSuccessfulBatch_deletesFromPendingAndMarksHistoryScrobbled() = runTest {
        val batch = createSampleEntities(5)
        coEvery { pendingScrobbleDao.getBatch(50) } returnsMany listOf(batch, emptyList())
        coEvery { lastFmClient.scrobbleBatch(any(), any()) } returns LastFmResult.Success(Unit)

        val worker = ScrobbleWorker(
            appContext = context,
            params = workerParams,
            pendingScrobbleDaoOverride = pendingScrobbleDao,
            historyDaoOverride = historyDao,
            lastFmClientOverride = lastFmClient,
            authRepositoryOverride = authRepository,
            networkDetectorOverride = networkDetector
        )

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        coVerify(exactly = 1) { lastFmClient.scrobbleBatch(match { it.size == 5 }, "valid_session_key") }
        coVerify(exactly = 1) { pendingScrobbleDao.deleteByIds(batch.map { it.id }) }
        batch.forEach { item ->
            coVerify(exactly = 1) { historyDao.updateStatus(item.id, ScrobbleStatus.Scrobbled) }
        }
    }

    @Test
    fun testNetworkError_returnsRetry() = runTest {
        val batch = createSampleEntities(3)
        coEvery { pendingScrobbleDao.getBatch(50) } returns batch
        coEvery { lastFmClient.scrobbleBatch(any(), any()) } returns LastFmResult.Error(
            code = null,
            message = "Network timeout",
            throwable = IOException("Timeout")
        )

        val worker = ScrobbleWorker(
            appContext = context,
            params = workerParams,
            pendingScrobbleDaoOverride = pendingScrobbleDao,
            historyDaoOverride = historyDao,
            lastFmClientOverride = lastFmClient,
            authRepositoryOverride = authRepository,
            networkDetectorOverride = networkDetector
        )

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.retry(), result)

        // Pending items must NOT be deleted
        coVerify(exactly = 0) { pendingScrobbleDao.deleteByIds(any()) }
        coVerify(exactly = 0) { historyDao.updateStatus(any(), any()) }
    }

    @Test
    fun testRetryableLastFmError_returnsRetry() = runTest {
        // Last.fm error 11: Service Offline
        val batch = createSampleEntities(2)
        coEvery { pendingScrobbleDao.getBatch(50) } returns batch
        coEvery { lastFmClient.scrobbleBatch(any(), any()) } returns LastFmResult.Error(
            code = 11,
            message = "Service Offline"
        )

        val worker = ScrobbleWorker(
            appContext = context,
            params = workerParams,
            pendingScrobbleDaoOverride = pendingScrobbleDao,
            historyDaoOverride = historyDao,
            lastFmClientOverride = lastFmClient,
            authRepositoryOverride = authRepository,
            networkDetectorOverride = networkDetector
        )

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.retry(), result)

        coVerify(exactly = 0) { pendingScrobbleDao.deleteByIds(any()) }
    }

    @Test
    fun testBatchingOver50Items_processesMultipleBatches() = runTest {
        val firstBatch = createSampleEntities(50)
        val secondBatch = (51..70).map { i ->
            PendingScrobbleEntity(
                id = "item-$i",
                artist = "Artist $i",
                title = "Title $i",
                album = "Album $i",
                albumArtist = "Artist $i",
                durationSeconds = 200,
                timestamp = 1700000000L + i,
                sourcePackage = "com.spotify.music",
                createdAt = 1700000000L + i,
                fingerprint = HashUtils.sha256("Artist $i|Title $i|${1700000000L + i}")
            )
        }

        coEvery { pendingScrobbleDao.getBatch(50) } returnsMany listOf(firstBatch, secondBatch, emptyList())
        coEvery { lastFmClient.scrobbleBatch(any(), any()) } returns LastFmResult.Success(Unit)

        val worker = ScrobbleWorker(
            appContext = context,
            params = workerParams,
            pendingScrobbleDaoOverride = pendingScrobbleDao,
            historyDaoOverride = historyDao,
            lastFmClientOverride = lastFmClient,
            authRepositoryOverride = authRepository,
            networkDetectorOverride = networkDetector
        )

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        coVerify(exactly = 2) { lastFmClient.scrobbleBatch(any(), "valid_session_key") }
        coVerify(exactly = 1) { pendingScrobbleDao.deleteByIds(firstBatch.map { it.id }) }
        coVerify(exactly = 1) { pendingScrobbleDao.deleteByIds(secondBatch.map { it.id }) }
    }
}
