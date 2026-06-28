package com.hawwwran.shushly.service.quietmode

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.util.Log
import com.hawwwran.shushly.MainActivity
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.DecisionReasonCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-owned quiet mode via a single [AutomaticZenRule] (spec §4.2). Enabling activates our
 * own rule; it never edits the user's global DND policy, and disabling deactivates our rule
 * so the OS recomputes the effective state from whatever the user themselves configured.
 */
@Singleton
class ZenRuleQuietModeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : QuietModeController {

    private val nm: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private val conditionUri: Uri = Uri.parse("condition://com.hawwwran.shushly/smart-quiet")

    private val state = MutableStateFlow(QuietModeState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var cachedRuleId: String? = null

    init {
        scope.launch {
            cachedRuleId = settings.snapshot().zenRuleId
            recompute()
        }
    }

    override fun observeState(): StateFlow<QuietModeState> = state.asStateFlow()

    override fun refresh() = recompute()

    override suspend fun enable(): QuietModeResult = withContext(Dispatchers.IO) {
        if (!policyGranted()) {
            recompute()
            return@withContext QuietModeResult.Unavailable(DecisionReasonCode.ERROR_PERMISSION_MISSING)
        }
        try {
            val ruleId = ensureRule(settings.snapshot().zenRuleId)
            cachedRuleId = ruleId
            settings.setZenRuleId(ruleId)
            nm.setAutomaticZenRuleState(
                ruleId,
                Condition(conditionUri, "Smart Quiet Mode on", Condition.STATE_TRUE),
            )
            recompute(activeOverride = true)
            QuietModeResult.Success
        } catch (se: SecurityException) {
            Log.w(TAG, "enable: security exception", se)
            QuietModeResult.Unavailable(DecisionReasonCode.ERROR_PERMISSION_MISSING)
        } catch (t: Throwable) {
            Log.w(TAG, "enable: failed", t)
            QuietModeResult.Unavailable(DecisionReasonCode.ERROR_QUIET_MODE_UNAVAILABLE)
        }
    }

    override suspend fun disable(): QuietModeResult = withContext(Dispatchers.IO) {
        val ruleId = settings.snapshot().zenRuleId
        if (ruleId == null) {
            recompute(activeOverride = false)
            return@withContext QuietModeResult.Success
        }
        try {
            nm.setAutomaticZenRuleState(
                ruleId,
                Condition(conditionUri, "Smart Quiet Mode off", Condition.STATE_FALSE),
            )
            recompute(activeOverride = false)
            QuietModeResult.Success
        } catch (t: Throwable) {
            Log.w(TAG, "disable: failed", t)
            QuietModeResult.Unavailable(DecisionReasonCode.ERROR_QUIET_MODE_UNAVAILABLE)
        }
    }

    /**
     * Reuse the persisted rule if the OS still knows it, else create a fresh one. An existing rule
     * has its policy refreshed so changes shipped in an app update (e.g. now allowing calls) reach
     * installs that already created the rule.
     */
    private fun ensureRule(prevId: String?): String {
        @Suppress("DEPRECATION")
        val rule = AutomaticZenRule(
            "Shushly Smart Quiet Mode",
            ComponentName(context, ShushlyConditionProviderService::class.java),
            ComponentName(context, MainActivity::class.java),
            conditionUri,
            buildZenPolicy(),
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true,
        )
        if (prevId != null) {
            val existing = runCatching { nm.getAutomaticZenRule(prevId) }.getOrNull()
            if (existing != null) {
                runCatching { nm.updateAutomaticZenRule(prevId, rule) }
                return prevId
            }
        }
        return nm.addAutomaticZenRule(rule)
    }

    /**
     * Silence everything except alarms and incoming calls; let priority channels (our
     * critical_alerts) through. Calls from anyone — plus repeat callers — always ring, so Quiet
     * Mode never makes you miss a phone call.
     */
    private fun buildZenPolicy(): ZenPolicy {
        val builder = ZenPolicy.Builder()
            .disallowAllSounds()
            .allowAlarms(true)
            .allowMedia(false)
            .allowSystem(false)
            .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
            .allowRepeatCallers(true)
        if (Build.VERSION.SDK_INT >= 35) {
            builder.allowPriorityChannels(true)
        }
        return builder.build()
    }

    private fun policyGranted(): Boolean =
        runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)

    private fun recompute(activeOverride: Boolean? = null) {
        val granted = policyGranted()
        val id = cachedRuleId
        val registered = id != null &&
            runCatching { nm.getAutomaticZenRule(id) != null }.getOrDefault(false)
        state.value = QuietModeState(
            policyAccessGranted = granted,
            ruleRegistered = registered,
            active = activeOverride ?: state.value.active,
        )
    }

    private companion object {
        const val TAG = "ShushlyQuietMode"
    }
}
