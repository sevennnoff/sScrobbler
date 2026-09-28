package com.sscrobbler.app.scrobble.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sscrobbler.app.SScrobblerApplication
import com.sscrobbler.app.database.PendingScrobbleDao
import com.sscrobbler.app.database.HistoryDao
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.lastfm.LastFmResult
import com.sscrobbler.app.model.ScrobbleStatus
import com.sscrobbler.app.util.HashUtils
import com.sscrobbler.app.util.NetworkDetector
import java.io.IOException
import java.util.concurrent.TimeUnit

class ScrobbleWorker(
    appContext: Context,
    params: WorkerParameters,
    private val pendingScrobbleDaoOverride: PendingScrobbleDao? = null,
    private val historyDaoOverride: HistoryDao? = null,
    private val lastFmClientOverride: LastFmClient? = null,
    private val authRepositoryOverride: LastFmAuthRepository? = null,
    private val networkDetectorOverride: NetworkDetector? = null
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SScrobblerApplication
        val pendingDao = pendingScrobbleDaoOverride ?: app?.database?.pendingScrobbleDao() ?: return Result.failure()
        val historyDao = historyDaoOverride ?: app?.database?.historyDao() ?: return Result.failure()
        val lastFmClient = lastFmClientOverride ?: app?.lastFmClient ?: return Result.failure()
        val authRepo = authRepositoryOverride ?: app?.authRepository ?: return Result.failure()
        val networkDetector = networkDetectorOverride ?: app?.networkDetector

        if (networkDetector != null && !networkDetector.isOnline()) {
            return Result.retry()
        }

        val sessionKey = authRepo.getSessionKey()
        if (sessionKey.isNullOrBlank()) {
            // Cannot scrobble without auth session
            return Result.failure()
        }

        try {
            var hasMore = true
            while (hasMore) {
                // Fetch up to 50 items
                val batch = pendingDao.getBatch(BATCH_SIZE)
                if (batch.isEmpty()) {
                    hasMore = false
                    break
                }

                // Verify fingerprints / deduplicate batch before sending
                val uniqueBatch = batch.distinctBy { item ->
                    item.fingerprint.ifBlank {
                        HashUtils.sha256("${item.artist}|${item.title}|${item.timestamp}")
                    }
                }

                val scrobbleResult = lastFmClient.scrobbleBatch(uniqueBatch, sessionKey)
                when (scrobbleResult) {
                    is LastFmResult.Success -> {
                        val ids = batch.map { it.id }
                        pendingDao.deleteByIds(ids)
                        for (id in ids) {
                            historyDao.updateStatus(id, ScrobbleStatus.Scrobbled)
                        }
                        if (batch.size < BATCH_SIZE) {
                            hasMore = false
                        }
                    }
                    is LastFmResult.Error -> {
                        val throwable = scrobbleResult.throwable
                        val isNetworkIssue = throwable is IOException ||
                                (scrobbleResult.message.contains("network", ignoreCase = true)) ||
                                scrobbleResult.code == null ||
                                scrobbleResult.code in RETRYABLE_LASTFM_ERRORS

                        return if (isNetworkIssue) {
                            Result.retry()
                        } else {
                            // Fatal API error for this batch (e.g. invalid auth or invalid params)
                            if (scrobbleResult.code == 4 || scrobbleResult.code == 9 || scrobbleResult.code == 14) {
                                // Invalid session key / token
                                Result.failure()
                            } else {
                                // Mark items failed and discard from pending queue
                                val ids = batch.map { it.id }
                                pendingDao.deleteByIds(ids)
                                for (id in ids) {
                                    historyDao.updateStatus(id, ScrobbleStatus.Failed)
                                }
                                Result.failure()
                            }
                        }
                    }
                }
            }
            return Result.success()
        } catch (e: IOException) {
            return Result.retry()
        } catch (e: Exception) {
            return Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_PERIODIC = "ScrobbleWorkerPeriodic"
        const val WORK_NAME_ONE_TIME = "ScrobbleWorkerOneTime"
        const val BATCH_SIZE = 50

        // Last.fm error codes that indicate temporary/retryable issues
        // 11: Service Offline - This service is temporarily offline. Try again later.
        // 16: There was a temporary error processing your request. Please try again.
        // 29: Rate limit exceeded.
        val RETRYABLE_LASTFM_ERRORS = setOf(11, 16, 29)

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ScrobbleWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ScrobbleWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
