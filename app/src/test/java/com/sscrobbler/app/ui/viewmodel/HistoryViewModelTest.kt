package com.sscrobbler.app.ui.viewmodel

import com.sscrobbler.app.database.HistoryDao
import com.sscrobbler.app.database.HistoryItemEntity
import com.sscrobbler.app.model.ScrobbleStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var historyDao: HistoryDao
    private val historyFlow = MutableStateFlow<List<HistoryItemEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        historyDao = mockk(relaxed = true)
        coEvery { historyDao.getRecentFlow(500) } returns historyFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testEmptyHistoryState() = runTest(testDispatcher) {
        val viewModel = HistoryViewModel(historyDao)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.isEmpty)
        assertEquals(0, state.items.size)
        assertFalse(state.isLoading)
    }

    @Test
    fun testPopulatedHistoryState() = runTest(testDispatcher) {
        val viewModel = HistoryViewModel(historyDao)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        runCurrent()

        val item1 = HistoryItemEntity(
            id = "1",
            artist = "Lana Del Rey",
            title = "West Coast",
            album = "Ultraviolence",
            durationSeconds = 250,
            listenedSeconds = 250,
            timestamp = 1700000000L,
            status = ScrobbleStatus.Scrobbled,
            sourcePackage = "com.spotify.music"
        )
        val item2 = HistoryItemEntity(
            id = "2",
            artist = "Radiohead",
            title = "Creep",
            album = "Pablo Honey",
            durationSeconds = 240,
            listenedSeconds = 40,
            timestamp = 1700000100L,
            status = ScrobbleStatus.Skipped,
            sourcePackage = "com.spotify.music"
        )

        historyFlow.value = listOf(item2, item1)
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isEmpty)
        assertEquals(2, state.items.size)
        assertEquals("Creep", state.items[0].title)
        assertEquals(ScrobbleStatus.Skipped, state.items[0].status)
        assertEquals("West Coast", state.items[1].title)
        assertEquals(ScrobbleStatus.Scrobbled, state.items[1].status)
    }

    @Test
    fun testClearHistoryCallsDao() = runTest(testDispatcher) {
        val viewModel = HistoryViewModel(historyDao)
        runCurrent()

        viewModel.clearHistory()
        runCurrent()

        coVerify(exactly = 1) { historyDao.deleteAll() }
    }

    @Test
    fun testDeleteItemCallsDao() = runTest(testDispatcher) {
        val viewModel = HistoryViewModel(historyDao)
        runCurrent()

        viewModel.deleteItem("test-id-123")
        runCurrent()

        coVerify(exactly = 1) { historyDao.deleteById("test-id-123") }
    }
}
