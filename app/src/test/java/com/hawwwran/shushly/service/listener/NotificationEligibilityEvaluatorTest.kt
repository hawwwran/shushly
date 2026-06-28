/*
 * JVM unit tests for the pure decision units (spec §16.1). Pipeline-level tests (alert threshold,
 * fail-to-silent on classify error, backstop integration) are deferred:
 * NotificationPipeline depends on the concrete SettingsRepository (DataStore) and other concretes,
 * so it isn't cleanly unit-testable without a small refactor (extract a SettingsRepository interface,
 * or use Robolectric). Out of scope for this increment.
 */
package com.hawwwran.shushly.service.listener

import com.hawwwran.shushly.core.model.EligibilityMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEligibilityEvaluatorTest {

    private val evaluator = NotificationEligibilityEvaluator()
    private val pkg = "com.example.app"

    @Test
    fun selectedApps_packageInSet_isEligible() {
        assertTrue(evaluator.isEligible(pkg, EligibilityMode.SELECTED_APPS, setOf(pkg)))
    }

    @Test
    fun selectedApps_packageNotInSet_isNotEligible() {
        assertFalse(evaluator.isEligible(pkg, EligibilityMode.SELECTED_APPS, setOf("com.other")))
    }

    @Test
    fun allExceptSelected_packageInSet_isNotEligible() {
        assertFalse(evaluator.isEligible(pkg, EligibilityMode.ALL_APPS_EXCEPT_SELECTED, setOf(pkg)))
    }

    @Test
    fun allExceptSelected_packageNotInSet_isEligible() {
        assertTrue(
            evaluator.isEligible(pkg, EligibilityMode.ALL_APPS_EXCEPT_SELECTED, setOf("com.other")),
        )
    }

    @Test
    fun emptySelection_selectedApps_nothingEligible() {
        assertFalse(evaluator.isEligible(pkg, EligibilityMode.SELECTED_APPS, emptySet()))
    }

    @Test
    fun emptySelection_allExceptSelected_everythingEligible() {
        assertTrue(evaluator.isEligible(pkg, EligibilityMode.ALL_APPS_EXCEPT_SELECTED, emptySet()))
    }
}
