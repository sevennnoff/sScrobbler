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
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.lastfm.LastFmResult
import com.sscrobbler.app.util.DebugLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.sscrobbler.app.util.NetworkDetector
import java.io.IOException
import java.util.concurrent.TimeUnit

class ScrobbleWorker @JvmOverloads constructor(
    appContext: Context,
    params: WorkerParameters,
    private val pendingScrobbleDaoOverride: PendingScrobbleDao? = null,
    private val lastFmClientOverride: LastFmClient? = null,
    private val authRepositoryOverride: LastFmAuthRepository? = null,
    private val networkDetectorOverride: NetworkDetector? = null
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = uploadMutex.withLock {
        val app = applicationContext as? SScrobblerApplication
        val dao = pendingScrobbleDaoOverride ?: app?.database?.pendingScrobbleDao() ?: return Result.failure()
        val client = lastFmClientOverride ?: app?.lastFmClient ?: return Result.failure()
        val auth = authRepositoryOverride ?: app?.authRepository ?: return Result.failure()
        val network = networkDetectorOverride ?: app?.networkDetector
        if (network?.isOnline() == false) return Result.retry()

        try {
            val key = auth.getSessionKey()
            if (key.isNullOrBlank()) return Result.success() // Signing in schedules another upload.
            while (true) {
                val batch = dao.getBatch(BATCH_SIZE)
                if (batch.isEmpty()) break
                when (val result = client.scrobbleBatch(batch, key)) {
                    is LastFmResult.Success -> {
                        if (result.data.size != batch.size) return Result.retry()
                        val accepted = mutableListOf<String>()
                        val rejected = mutableListOf<String>()
                        batch.zip(result.data).forEach { (item, code) ->
                            when (code) {
                                0 -> accepted.add(item.id)
                                1, 2, 3 -> rejected.add(item.id)
                            }
                        }
                        if (accepted.isNotEmpty() || rejected.isNotEmpty()) dao.completeBatch(accepted, rejected)
                        // Future timestamps, daily limits and unknown outcomes stay queued.
                        if (accepted.size + rejected.size < batch.size) return Result.retry()
                    }
                    is LastFmResult.Error -> {
                        DebugLog.log("WORKER", "Upload deferred: ${result.message}")
                        return if (result.code in setOf(4, 9, 10, 14, 26)) Result.failure() else Result.retry()
                    }
                }
            }
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    companion object {
        private val uploadMutex = Mutex()
        const val WORK_NAME_PERIODIC = "ScrobbleWorkerPeriodic"
        const val WORK_NAME_ONE_TIME = "ScrobbleWorkerOneTime"
        const val BATCH_SIZE = 50

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
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
