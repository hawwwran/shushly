package com.hawwwran.shushly.service.listener

import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
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
    private var packageManagerRef: PackageManager? = null

    override fun onCreate() {
        super.onCreate()
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            ServiceEntryPoint::class.java,
        )
        pipeline = entryPoint.pipeline()
        packageManagerRef = applicationContext.packageManager
    }

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // Never act on our own notifications (spec §7.2); mirror only third-party source dismissals.
        if (sbn.packageName == applicationContext.packageName) return
        pipeline?.onSourceRemoved(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        // Never analyse our own notifications, including the critical re-alerts we post (spec §7.2).
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
