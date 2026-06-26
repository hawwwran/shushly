package com.hawwwran.shushly.service.listener

import com.hawwwran.shushly.core.model.EligibilityMode
import javax.inject.Inject

/** Decides whether a package is AI-eligible under the active selection mode (spec §3.2). */
class NotificationEligibilityEvaluator @Inject constructor() {
    fun isEligible(
        packageName: String,
        mode: EligibilityMode,
        selectedPackages: Set<String>,
    ): Boolean {
        val inList = packageName in selectedPackages
        return when (mode) {
            EligibilityMode.SELECTED_APPS -> inList
            EligibilityMode.ALL_APPS_EXCEPT_SELECTED -> !inList
        }
    }
}
