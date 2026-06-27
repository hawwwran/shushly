package com.hawwwran.shushly.di

import com.hawwwran.shushly.core.data.ApiKeyStore
import com.hawwwran.shushly.core.data.DecisionHistoryRepository
import com.hawwwran.shushly.core.data.DecisionHistoryRepositoryImpl
import com.hawwwran.shushly.core.data.EncryptedApiKeyStore
import com.hawwwran.shushly.core.data.InstalledAppRepository
import com.hawwwran.shushly.core.data.InstalledAppRepositoryImpl
import com.hawwwran.shushly.core.data.SeenAppsRepository
import com.hawwwran.shushly.core.data.SeenAppsRepositoryImpl
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.data.SettingsRepositoryImpl
import com.hawwwran.shushly.service.ai.AiClassifier
import com.hawwwran.shushly.service.ai.RoutingAiClassifier
import com.hawwwran.shushly.service.alerting.CriticalAlertSounder
import com.hawwwran.shushly.service.alerting.CriticalAlertSounderImpl
import com.hawwwran.shushly.service.quietmode.LockStateProvider
import com.hawwwran.shushly.service.quietmode.LockStateProviderImpl
import com.hawwwran.shushly.service.quietmode.QuietModeController
import com.hawwwran.shushly.service.quietmode.ZenRuleQuietModeController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    /** Selects direct-OpenAI-or-fake per call (direct when key set + verified; fake in debug otherwise). */
    @Binds
    abstract fun bindAiClassifier(impl: RoutingAiClassifier): AiClassifier

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun bindApiKeyStore(impl: EncryptedApiKeyStore): ApiKeyStore

    @Binds
    abstract fun bindSeenAppsRepository(impl: SeenAppsRepositoryImpl): SeenAppsRepository

    @Binds
    abstract fun bindDecisionHistoryRepository(impl: DecisionHistoryRepositoryImpl): DecisionHistoryRepository

    @Binds
    abstract fun bindCriticalAlertSounder(impl: CriticalAlertSounderImpl): CriticalAlertSounder

    @Binds
    abstract fun bindQuietModeController(impl: ZenRuleQuietModeController): QuietModeController

    @Binds
    abstract fun bindLockStateProvider(impl: LockStateProviderImpl): LockStateProvider

    @Binds
    abstract fun bindInstalledAppRepository(impl: InstalledAppRepositoryImpl): InstalledAppRepository
}
