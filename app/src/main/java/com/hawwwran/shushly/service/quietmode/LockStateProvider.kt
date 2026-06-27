package com.hawwwran.shushly.service.quietmode

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the user is actively using the phone — a testable seam over PowerManager/KeyguardManager.
 * "In use" = screen interactive AND keyguard unlocked. Screen-off ⇒ not in use (avoids the
 * lock-after-screen-off timing trap); lock screen showing ⇒ interactive but locked ⇒ not in use.
 */
interface LockStateProvider {
    fun isInUse(): Boolean
}

@Singleton
class LockStateProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : LockStateProvider {

    // Nullable: getSystemService is @Nullable, and a null here must not NPE while Hilt constructs
    // this @Singleton (that would crash provisioning instead of yielding the safe default below).
    private val powerManager: PowerManager? =
        context.getSystemService(PowerManager::class.java)
    private val keyguardManager: KeyguardManager? =
        context.getSystemService(KeyguardManager::class.java)

    override fun isInUse(): Boolean = runCatching {
        val interactive = powerManager?.isInteractive ?: return@runCatching false
        val locked = keyguardManager?.isKeyguardLocked ?: return@runCatching false
        interactive && !locked
    }.getOrDefault(false) // On error/null, treat as not-in-use (away) — the safe side keeps Shushly active.
}
