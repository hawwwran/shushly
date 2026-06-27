package com.hawwwran.shushly.di

import com.hawwwran.shushly.core.data.InstalledAppRepository
import com.hawwwran.shushly.core.data.InstalledAppRepositoryImpl
import com.hawwwran.shushly.service.ai.AiClassifier
import com.hawwwran.shushly.service.ai.FakeAiClassifier
import com.hawwwran.shushly.service.alerting.CriticalAlertSounder
import com.hawwwran.shushly.service.alerting.CriticalAlertSounderImpl
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
    abstract fun bindCriticalAlertSounder(impl: CriticalAlertSounderImpl): CriticalAlertSounder

    @Binds
    abstract fun bindQuietModeController(impl: ZenRuleQuietModeController): QuietModeController

    @Binds
    abstract fun bindInstalledAppRepository(impl: InstalledAppRepositoryImpl): InstalledAppRepository
}
