package com.hawwwran.shushly.service.quietmode

import com.hawwwran.shushly.core.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for toggling Smart Quiet Mode: it drives the app-owned zen rule via
 * [QuietModeController] and persists the user's intent via [SettingsRepository] together, so the
 * Home toggle and the Quick Settings tile can't drift apart.
 */
@Singleton
class SmartQuietModeManager @Inject constructor(
    private val quietMode: QuietModeController,
    private val settings: SettingsRepository,
) {
    /**
     * Enables or disables Smart Quiet Mode (zen rule + persisted flag). Returns the state it ended
     * up in: enabling can fail to [QuietModeResult.Unavailable] (e.g. no policy access), leaving it
     * off.
     */
    suspend fun setEnabled(on: Boolean): Boolean = if (on) {
        when (quietMode.enable()) {
            is QuietModeResult.Success -> {
                settings.setSmartQuietMode(true)
                true
            }
            is QuietModeResult.Unavailable -> {
                settings.setSmartQuietMode(false)
                false
            }
        }
    } else {
        quietMode.disable()
        settings.setSmartQuietMode(false)
        false
    }

    /** The persisted Smart Quiet Mode flag (the user's intent, kept in sync with the zen rule). */
    suspend fun isEnabled(): Boolean = settings.snapshot().smartQuietModeEnabled
}
