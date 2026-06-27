package com.hawwwran.shushly

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hawwwran.shushly.service.alerting.NotificationChannels
import com.hawwwran.shushly.service.work.HistoryPurgeWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class ShushlyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureBaseChannels(this)
        schedulePurge()
    }

    /** Daily 30-day decision-history purge (spec §10.2). KEEP so an existing schedule isn't reset. */
    private fun schedulePurge() {
        val request = PeriodicWorkRequestBuilder<HistoryPurgeWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            HistoryPurgeWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
