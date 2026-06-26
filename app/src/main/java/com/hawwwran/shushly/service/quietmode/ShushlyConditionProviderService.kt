package com.hawwwran.shushly.service.quietmode

import android.net.Uri
import android.service.notification.ConditionProviderService

/**
 * Owner component for Shushly's AutomaticZenRule. The rule is driven by the app via
 * NotificationManager.setAutomaticZenRuleState, so this service holds no logic; it exists
 * so the rule has a valid, declared owner and survives reboot.
 */
@Suppress("DEPRECATION") // Still the supported owner component for an app-driven AutomaticZenRule.
class ShushlyConditionProviderService : ConditionProviderService() {
    override fun onConnected() = Unit
    override fun onSubscribe(conditionId: Uri?) = Unit
    override fun onUnsubscribe(conditionId: Uri?) = Unit
}
