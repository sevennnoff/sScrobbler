package com.sscrobbler.app.scrobble

import com.sscrobbler.app.database.HistoryDao
import com.sscrobbler.app.database.HistoryItemEntity
import com.sscrobbler.app.database.PendingScrobbleDao
import com.sscrobbler.app.database.PendingScrobbleEntity
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.lastfm.LastFmResult
import com.sscrobbler.app.model.ScrobbleStatus
import com.sscrobbler.app.model.Track
import com.sscrobbler.app.settings.AppSettings
import com.sscrobbler.app.settings.SettingsRepository
import com.sscrobbler.app.util.Clock
import com.sscrobbler.app.util.NetworkDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScrobbleEngineTest {

    private lateinit var clock: TestClock
    private lateinit var pendingScrobbleDao: FakePendingScrobbleDao
    private lateinit var historyDao: FakeHistoryDao
    private lateinit var lastFmClient: FakeLastFmClient
    private lateinit var authRepository: FakeLastFmAuthRepository
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var networkDetector: FakeNetworkDetector
    private lateinit var engine: ScrobbleEngine

    @Before
    fun setUp() {
        clock = TestClock()
        pendingScrobbleDao = FakePendingScrobbleDao()
        historyDao = FakeHistoryDao()
        lastFmClient = FakeLastFmClient()
        authRepository = FakeLastFmAuthRepository("test_session_key")
        settingsRepository = FakeSettingsRepository()
        networkDetector = FakeNetworkDetector(online = true)

        engine = ScrobbleEngine(
            pendingScrobbleDao = pendingScrobbleDao,
            historyDao = historyDao,
            lastFmClient = lastFmClient,
            authRepository = authRepository,
            settingsRepository = settingsRepository,
            networkDetector = networkDetector,
            clock = clock
        )
    }

    // 1) PLAY 1 min -> NEXT (threshold не достигнут) -> Skipped
    @Test
    fun testScenario1_playBelowThreshold_thenNext_recordsSkipped() = runTest {
        // Track: 4 minutes (240s = 240_000ms), threshold is 50% = 120s
        val track1 = Track(
            artist = "Lana Del Rey",
            title = "West Coast",
            album = "Ultraviolence",
            durationMs = 240_000L,
            sourcePackage = "com.spotify.music"
        )
        val track2 = Track(
            artist = "Lana Del Rey",
            title = "National Anthem",
            album = "Born to Die",
            durationMs = 240_000L,
            sourcePackage = "com.spotify.music"
        )

        engine.onTrackStarted(track1)
        // Play for 1 min (60_000 ms)
        clock.advance(60_000L)
        engine.onTimeTick()

        assertFalse("Session should NOT be eligible after 1 min", engine.currentSession!!.eligible)

        // NEXT track starts
        engine.onTrackStarted(track2)

        // Track 1 should not have been scrobbled to Last.fm
        assertEquals(0, lastFmClient.scrobbledCalls.size)
        // Should be recorded in history as Skipped
        val history = historyDao.items
        assertEquals(1, history.size)
        assertEquals(track1.title, history[0].title)
        assertEquals(ScrobbleStatus.Skipped, history[0].status)
        assertEquals(60, history[0].listenedSeconds)
        // Pending queue must be empty
        assertEquals(0, pendingScrobbleDao.items.size)
    }

    // 2) PLAY до threshold -> Eligible (Last.fm API ещё не вызывается)
    @Test
    fun testScenario2_playToThreshold_marksEligible_noLastFmCallYet() = runTest {
        // Track: 3:20 (200_000 ms), threshold is 50% = 100_000 ms (100 sec)
        val track = Track(
            artist = "Radiohead",
            title = "Karma Police",
            durationMs = 200_000L,
            sourcePackage = "com.spotify.music"
        )

        engine.onTrackStarted(track)
        clock.advance(100_000L)
        engine.onTimeTick()

        val session = engine.currentSession
        assertNotNull(session)
        assertTrue("Session must be eligible once threshold is reached", session!!.eligible)
        assertEquals(ScrobbleStatus.WaitingForEnd, engine.statusFlow.value)

        // Last.fm API must NOT be called at this moment!
        assertEquals("Last.fm scrobble API must NOT be called while track is still playing", 0, lastFmClient.scrobbledCalls.size)
        assertEquals(0, pendingScrobbleDao.items.size)
    }

    // 3) PLAY до threshold -> PAUSE -> Eligible, Not scrobbled
    @Test
    fun testScenario3_playToThreshold_thenPause_staysEligible_notScrobbled() = runTest {
        val track = Track(
            artist = "Radiohead",
            title = "No Surprises",
            durationMs = 200_000L,
            sourcePackage = "com.spotify.music"
        )

        engine.onTrackStarted(track)
        clock.advance(100_000L)
        engine.onTimeTick()

        // Pause playback
        engine.onPlaybackStateChanged(isPlaying = false)

        val session = engine.currentSession
        assertNotNull(session)
        assertTrue("Session must still be eligible during pause", session!!.eligible)
        assertEquals(ScrobbleStatus.Eligible, engine.statusFlow.value)

        // Still NOT scrobbled
        assertEquals("Last.fm scrobble API must not be called upon pause", 0, lastFmClient.scrobbledCalls.size)
        assertEquals(0, pendingScrobbleDao.items.size)
    }

    // 4) PLAY до threshold -> NEXT -> 1 scrobble
    @Test
    fun testScenario4_playToThreshold_thenNext_producesOneScrobble() = runTest {
        val track1 = Track(
            artist = "Lana Del Rey",
            title = "National Anthem",
            album = "Born to Die",
            durationMs = 240_000L,
            sourcePackage = "com.spotify.music"
        )
        val track2 = Track(
            artist = "Lana Del Rey",
            title = "Video Games",
            album = "Born to Die",
            durationMs = 240_000L,
            sourcePackage = "com.spotify.music"
        )

        engine.onTrackStarted(track1)
        // Play 120s (50% threshold)
        clock.advance(120_000L)
        engine.onTimeTick()
        assertTrue(engine.currentSession!!.eligible)

        // User switches to next track
        engine.onTrackStarted(track2)

        // Exactly 1 scrobble call made
        assertEquals(1, lastFmClient.scrobbledCalls.size)
        val scrobble = lastFmClient.scrobbledCalls[0]
        assertEquals("Lana Del Rey", scrobble.artist)
        assertEquals("National Anthem", scrobble.track)

        // History shows Scrobbled
        val history = historyDao.items
        assertEquals(1, history.size)
        assertEquals("National Anthem", history[0].title)
        assertEquals(ScrobbleStatus.Scrobbled, history[0].status)
        // Pending queue is cleaned up after successful online scrobble
        assertEquals(0, pendingScrobbleDao.items.size)
    }

    // 5) PLAY 30s -> seek +3 min -> PLAY 30s -> NEXT -> 60s listened (monotonic clock)
    @Test
    fun testScenario5_seekDoesNotIncreaseListenedTime_monotonicClockEnforced() = runTest {
        // Track: 6 minutes (360_000 ms), threshold is 50% = 180s
        val track1 = Track(
            artist = "Pink Floyd",
            title = "Money",
            durationMs = 360_000L,
            sourcePackage = "com.spotify.music"
        )
        val track2 = Track(
            artist = "Pink Floyd",
            title = "Time",
            durationMs = 360_000L,
            sourcePackage = "com.spotify.music"
        )

        engine.onTrackStarted(track1)
        // Play 30 sec (real elapsed time = 30s, position = 30s)
        clock.advance(30_000L)
        engine.onPositionChanged(30_000L)

        // Seek forward by 3 minutes (position jumps to 3m30s = 210_000ms), but clock elapsed time does not advance!
        engine.onPositionChanged(210_000L)

        // Play another 30 seconds (clock elapsed +30s, position jumps to 240_000ms)
        clock.advance(30_000L)
        engine.onPositionChanged(240_000L)

        val session = engine.currentSession
        assertNotNull(session)
        val listenedMs = session!!.totalListenedMs(clock)
        assertEquals(60_000L, listenedMs)
        assertFalse("Real listened time is only 60s (<180s threshold), must NOT be eligible", session.eligible)

        // NEXT track
        engine.onTrackStarted(track2)

        // Result: Skipped, 60s recorded
        assertEquals(0, lastFmClient.scrobbledCalls.size)
        val history = historyDao.items
        assertEquals(1, history.size)
        assertEquals(ScrobbleStatus.Skipped, history[0].status)
        assertEquals(60, history[0].listenedSeconds)
    }

    // 6) Track A -> Track A again -> 2 отдельных playback session
    @Test
    fun testScenario6_trackRepeated_createsTwoDistinctSessions() = runTest {
        val trackA = Track(
            artist = "Daft Punk",
            title = "Get Lucky",
            durationMs = 240_000L,
            sourcePackage = "com.spotify.music"
        )

        // Session 1: play to threshold (120s)
        engine.onTrackStarted(trackA)
        val session1StartTime = engine.currentSession!!.startedAtUnix
        clock.advance(120_000L)
        engine.onPositionChanged(180_000L) // >70%

        // Repeat detected (position reset back to 2 sec)
        clock.advance(10_000L)
        engine.onPositionChanged(2_000L)

        // Now session 2 is running
        val session2 = engine.currentSession
        assertNotNull(session2)
        assertEquals(trackA, session2!!.track)

        // Advance session 2 by 120s as well
        clock.advance(120_000L)
        engine.onPlaybackStopped()

        // 2 separate scrobbles and 2 separate history records
        assertEquals(2, lastFmClient.scrobbledCalls.size)
        val history = historyDao.items
        assertEquals(2, history.size)
        assertEquals(trackA.title, history[0].title)
        assertEquals(trackA.title, history[1].title)
        assertEquals(ScrobbleStatus.Scrobbled, history[0].status)
        assertEquals(ScrobbleStatus.Scrobbled, history[1].status)
    }

    // 7) Eligible -> offline -> NEXT -> Pending -> онлайн -> Scrobbled
    @Test
    fun testScenario7_eligibleOfflineThenNext_staysPending_thenOnline_scrobbles() = runTest {
        val track1 = Track(
            artist = "Lana Del Rey",
            title = "Venice Bitch",
            album = "Norman Fucking Rockwell",
            durationMs = 570_000L, // 9.5 min, threshold caps at maxRequiredTimeMs = 240s
            sourcePackage = "com.spotify.music"
        )
        val track2 = Track(
            artist = "Lana Del Rey",
            title = "Mariners Apartment Complex",
            album = "Norman Fucking Rockwell",
            durationMs = 240_000L,
            sourcePackage = "com.spotify.music"
        )

        engine.onTrackStarted(track1)
        // Play 240s (maxRequiredTimeMs reached)
        clock.advance(240_000L)
        engine.onTimeTick()
        assertTrue(engine.currentSession!!.eligible)

        // Go offline
        networkDetector.online = false

        // NEXT track starts
        engine.onTrackStarted(track2)

        // Last.fm should NOT have been called directly since we were offline
        assertEquals(0, lastFmClient.scrobbledCalls.size)

        // Room has PendingScrobbleEntity saved
        assertEquals(1, pendingScrobbleDao.items.size)
        val pending = pendingScrobbleDao.items[0]
        assertEquals("Venice Bitch", pending.title)

        // History shows Pending
        assertEquals(1, historyDao.items.size)
        assertEquals(ScrobbleStatus.Pending, historyDao.items[0].status)

        // Now internet comes back online!
        networkDetector.online = true

        // WorkManager / sync processes pending queue
        val processedCount = engine.processPendingQueue()
        assertEquals(1, processedCount)

        // Successfully scrobbled batch
        assertEquals(1, lastFmClient.batchScrobbledCalls.size)
        assertEquals("Venice Bitch", lastFmClient.batchScrobbledCalls[0][0].title)

        // Pending queue is cleared
        assertEquals(0, pendingScrobbleDao.items.size)

        // History status is updated to Scrobbled
        assertEquals(ScrobbleStatus.Scrobbled, historyDao.items[0].status)
    }
}

// ---------------- Test Fakes ----------------

class TestClock(
    var elapsed: Long = 100_000L,
    var epochMs: Long = 1_700_000_000_000L
) : Clock {
    override fun elapsedRealtime(): Long = elapsed
    override fun currentTimeMillis(): Long = epochMs

    fun advance(ms: Long) {
        elapsed += ms
        epochMs += ms
    }
}

class FakeNetworkDetector(var online: Boolean = true) : NetworkDetector {
    override fun isOnline(): Boolean = online
}

class FakePendingScrobbleDao : PendingScrobbleDao {
    val items = mutableListOf<PendingScrobbleEntity>()

    override suspend fun insert(pendingScrobble: PendingScrobbleEntity): Long {
        if (items.any { it.fingerprint == pendingScrobble.fingerprint }) {
            return -1L
        }
        items.add(pendingScrobble)
        return items.size.toLong()
    }

    override fun getAllFlow(): Flow<List<PendingScrobbleEntity>> = flowOf(items)
    override suspend fun getAll(): List<PendingScrobbleEntity> = items.toList()
    override suspend fun getBatch(limit: Int): List<PendingScrobbleEntity> = items.take(limit)
    override fun countFlow(): Flow<Int> = flowOf(items.size)
    override suspend fun count(): Int = items.size
    override suspend fun deleteById(id: String) { items.removeAll { it.id == id } }
    override suspend fun deleteByIds(ids: List<String>) { items.removeAll { it.id in ids } }
    override suspend fun deleteByFingerprint(fingerprint: String) { items.removeAll { it.fingerprint == fingerprint } }
    override suspend fun deleteAll() { items.clear() }
}

class FakeHistoryDao : HistoryDao {
    val items = mutableListOf<HistoryItemEntity>()

    override suspend fun insert(item: HistoryItemEntity) {
        items.removeAll { it.id == item.id }
        items.add(item)
    }

    override fun getRecentFlow(limit: Int): Flow<List<HistoryItemEntity>> = flowOf(items.take(limit))
    override suspend fun getRecent(limit: Int): List<HistoryItemEntity> = items.take(limit)
    override suspend fun updateStatus(id: String, status: ScrobbleStatus) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val old = items[index]
            items[index] = old.copy(status = status)
        }
    }
    override suspend fun updateArtworkUrl(id: String, artworkUrl: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val old = items[index]
            items[index] = old.copy(artworkUrl = artworkUrl)
        }
    }
    override suspend fun prune(keepCount: Int) {
        if (items.size > keepCount) {
            val toKeep = items.take(keepCount)
            items.clear()
            items.addAll(toKeep)
        }
    }
    override suspend fun deleteById(id: String) { items.removeAll { it.id == id } }
    override suspend fun deleteAll() { items.clear() }
}

data class ScrobbleCall(
    val artist: String,
    val track: String,
    val timestamp: Long,
    val album: String?,
    val albumArtist: String?,
    val durationSeconds: Int?,
    val sessionKey: String
)

class FakeLastFmClient : LastFmClient() {
    val scrobbledCalls = mutableListOf<ScrobbleCall>()
    val batchScrobbledCalls = mutableListOf<List<PendingScrobbleEntity>>()
    var updateNowPlayingCalls = 0

    override suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String?,
        albumArtist: String?,
        durationSeconds: Int?,
        sessionKey: String
    ): LastFmResult<Unit> {
        updateNowPlayingCalls++
        return LastFmResult.Success(Unit)
    }

    override suspend fun scrobble(
        artist: String,
        track: String,
        timestamp: Long,
        album: String?,
        albumArtist: String?,
        durationSeconds: Int?,
        sessionKey: String
    ): LastFmResult<Unit> {
        scrobbledCalls.add(
            ScrobbleCall(artist, track, timestamp, album, albumArtist, durationSeconds, sessionKey)
        )
        return LastFmResult.Success(Unit)
    }

    override suspend fun scrobbleBatch(
        items: List<PendingScrobbleEntity>,
        sessionKey: String
    ): LastFmResult<Unit> {
        batchScrobbledCalls.add(items)
        return LastFmResult.Success(Unit)
    }
}

class FakeLastFmAuthRepository(private var key: String? = null) : LastFmAuthRepository() {
    override suspend fun getSessionKey(): String? = key
    override suspend fun getUsername(): String? = "test_user"
    override suspend fun saveSession(username: String, sessionKey: String) {
        key = sessionKey
    }
    override suspend fun clearSession() {
        key = null
    }
}

class FakeSettingsRepository(private var settings: AppSettings = AppSettings()) : SettingsRepository() {
    override suspend fun getSettings(): AppSettings = settings
}
