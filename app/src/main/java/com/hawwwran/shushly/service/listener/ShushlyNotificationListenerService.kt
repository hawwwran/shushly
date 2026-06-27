package com.hawwwran.shushly.service.listener

import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.di.ServiceEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Receives posted notifications (spec §7.1). Returns from the binder callback immediately and
 * processes on a supervised scope; a bounded semaphore drops excess work rather than queueing.
 * Uses EntryPointAccessors (not @AndroidEntryPoint) for robust DI into a system-bound service.
 */
class ShushlyNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val semaphore = Semaphore(MAX_CONCURRENT)

    private var pipeline: NotificationPipeline? = null
    private var settings: SettingsRepository? = null
    private var packageManagerRef: PackageManager? = null

    override fun onCreate() {
        super.onCreate()
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            ServiceEntryPoint::class.java,
        )
        pipeline = entryPoint.pipeline()
        settings = entryPoint.settingsRepository()
        packageManagerRef = applicationContext.packageManager
    }

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected")
        val store = settings ?: return
        scope.launch { runCatching { store.setListenerConnectedSince(System.currentTimeMillis()) } }
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "listener disconnected; requesting rebind")
        settings?.let { store ->
            scope.launch { runCatching { store.setListenerConnectedSince(null) } }
        }
        // Ask the system to rebind (best-effort recovery from an OEM background-kill).
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(this, ShushlyNotificationListenerService::class.java),
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        // Never analyse our own notifications (spec §7.2).
        if (sbn.packageName == applicationContext.packageName) return
        val activePipeline = pipeline ?: return
        scope.launch {
            if (!semaphore.tryAcquire()) {
                Log.i(TAG, "dropping ${sbn.packageName}: pipeline saturated")
                return@launch
            }
            try {
                activePipeline.process(sbn, appLabel(sbn.packageName))
            } catch (t: Throwable) {
                Log.w(TAG, "process failed", t)
            } finally {
                semaphore.release()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun appLabel(packageName: String): String {
        val pm = packageManagerRef ?: return packageName
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private companion object {
        const val TAG = "ShushlyListener"
        const val MAX_CONCURRENT = 3
    }
}
