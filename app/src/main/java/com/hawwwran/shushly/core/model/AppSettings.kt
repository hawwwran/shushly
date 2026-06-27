package com.hawwwran.shushly.core.model

/**
 * User-controlled settings. Defaults are chosen so the Phase-0 spike is testable out of
 * the box: [ALL_APPS_EXCEPT_SELECTED] with an empty selection makes every app eligible.
 */
data class AppSettings(
    val smartQuietModeEnabled: Boolean = false,
    val vibrateForCriticalAlerts: Boolean = true,
    val simulationModeEnabled: Boolean = false,
    val eligibilityMode: EligibilityMode = EligibilityMode.ALL_APPS_EXCEPT_SELECTED,
    val selectedPackages: Set<String> = emptySet(),
    /** Apps that always sound on every notification, bypassing the AI (beats eligibility). */
    val alwaysAlertPackages: Set<String> = emptySet(),
    val zenRuleId: String? = null,
    val onboardingComplete: Boolean = false,
    val aiConnection: AiConnectionState = AiConnectionState(),
    val customAiInstruction: String? = null,
    /** Set when the AI last failed (ERROR_AI_UNAVAILABLE); cleared on a successful classify. */
    val aiUnavailableSince: Long? = null,
    /** Epoch ms the notification listener last reported connected; null = not connected. */
    val listenerConnectedSinceMs: Long? = null,
)
