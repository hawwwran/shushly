package com.hawwwran.shushly.di

import com.hawwwran.shushly.service.ai.AiClassifier
import com.hawwwran.shushly.service.ai.FakeAiClassifier
import com.hawwwran.shushly.service.alerting.CriticalAlertNotifier
import com.hawwwran.shushly.service.alerting.CriticalAlertNotifierImpl
import com.hawwwran.shushly.service.quietmode.QuietModeController
import com.hawwwran.shushly.service.quietmode.ZenRuleQuietModeController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    /** Spike: always the fake classifier. Phase 2 swaps in a relay-or-fake selector. */
    @Binds
    abstract fun bindAiClassifier(impl: FakeAiClassifier): AiClassifier

    @Binds
    abstract fun bindCriticalAlertNotifier(impl: CriticalAlertNotifierImpl): CriticalAlertNotifier

    @Binds
    abstract fun bindQuietModeController(impl: ZenRuleQuietModeController): QuietModeController
}
