package com.hawwwran.shushly.service.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hawwwran.shushly.di.ServiceEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit

/**
 * Deletes decision-history rows older than 30 days (spec §10.2). Scheduled ~daily from
 * [com.hawwwran.shushly.ShushlyApp]. Reaches the repository via [EntryPointAccessors] (the same
 * pattern as the notification listener), so no hilt-work dependency is needed.
 */
class HistoryPurgeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors
            .fromApplication(applicationContext, ServiceEntryPoint::class.java)
            .decisionHistoryRepository()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return try {
            val deleted = repository.purgeOlderThan(cutoff)
            Log.i(TAG, "purged $deleted decision-history rows older than 30 days")
            Result.success()
        } catch (t: Throwable) {
            // doWork must return a Result, never throw; retry on the next backoff.
            Log.w(TAG, "history purge failed", t)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "history_purge"
        private const val TAG = "ShushlyPurge"
        private val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(30)
    }
}
