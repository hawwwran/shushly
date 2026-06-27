package com.hawwwran.shushly

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hawwwran.shushly.di.ServiceEntryPoint
import com.hawwwran.shushly.service.alerting.NotificationChannels
import com.hawwwran.shushly.service.quietmode.SmartQuietModeManager
import com.hawwwran.shushly.service.work.HistoryPurgeWorker
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class ShushlyApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureBaseChannels(this)
        schedulePurge()
        registerLockStateReceiver()
        // Match the zen state to the current lock state after a process (re)start.
        appScope.launch { runCatching { quietModeManager().reconcile() } }
    }

    private fun quietModeManager(): SmartQuietModeManager =
        EntryPointAccessors.fromApplication(this, ServiceEntryPoint::class.java).smartQuietModeManager()

    /**
     * Lock/unlock transitions can't be manifest-declared, so register at runtime. On each, reconcile
     * the zen rule (e.g. unlock → in use → rule off; lock/screen-off → away → rule on). Resilient: a
     * reconcile failure must never crash.
     */
    private fun registerLockStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val pending = goAsync()
                appScope.launch {
                    try {
                        runCatching { quietModeManager().reconcile() }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
