package com.hawwwran.shushly.service.tile

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.hawwwran.shushly.MainActivity
import com.hawwwran.shushly.di.ServiceEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that toggles Smart Quiet Mode (spec §16.5). Delegates enable/disable + persist
 * to the shared [com.hawwwran.shushly.service.quietmode.SmartQuietModeManager], reached via
 * [EntryPointAccessors] (the same DI pattern as the notification listener and the purge worker), so
 * the tile and the Home toggle never drift.
 */
class QuietModeTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (!isPolicyAccessGranted()) {
            // A zen rule can't be enabled without policy access — send the user to grant it.
            openApp()
            return
        }
        scope.launch {
            runCatching {
                val manager = entryPoint().smartQuietModeManager()
                val nowOn = manager.setEnabled(!manager.isEnabled())
                applyTileState(available = true, enabled = nowOn)
            }.onFailure {
                Log.w(TAG, "tile toggle failed", it)
                refreshTile()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshTile() {
        if (!isPolicyAccessGranted()) {
            applyTileState(available = false, enabled = false)
            return
        }
        scope.launch {
            val enabled = runCatching { entryPoint().smartQuietModeManager().isEnabled() }
                .getOrDefault(false)
            applyTileState(available = true, enabled = enabled)
        }
    }

    private fun applyTileState(available: Boolean, enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = when {
            !available -> Tile.STATE_UNAVAILABLE
            enabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Smart Quiet Mode"
        tile.subtitle = when {
            !available -> "Needs DND access"
            enabled -> "On"
            else -> "Off"
        }
        tile.updateTile()
    }

    private fun isPolicyAccessGranted(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        return runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)
    }

    private fun entryPoint(): ServiceEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java)

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val TAG = "ShushlyTile"
    }
}
