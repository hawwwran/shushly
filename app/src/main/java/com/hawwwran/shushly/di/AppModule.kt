package com.hawwwran.shushly.di

import com.hawwwran.shushly.core.data.DeviceTokenStore
import com.hawwwran.shushly.core.data.EncryptedDeviceTokenStore
import com.hawwwran.shushly.core.data.InstalledAppRepository
import com.hawwwran.shushly.core.data.InstalledAppRepositoryImpl
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.data.SettingsRepositoryImpl
import com.hawwwran.shushly.service.ai.AiClassifier
import com.hawwwran.shushly.service.ai.RoutingAiClassifier
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

    /** Selects relay-or-fake per call (relay when configured; fake in debug otherwise). */
    @Binds
    abstract fun bindAiClassifier(impl: RoutingAiClassifier): AiClassifier

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun bindDeviceTokenStore(impl: EncryptedDeviceTokenStore): DeviceTokenStore

    @Binds
    abstract fun bindCriticalAlertSounder(impl: CriticalAlertSounderImpl): CriticalAlertSounder

    @Binds
    abstract fun bindQuietModeController(impl: ZenRuleQuietModeController): QuietModeController

    @Binds
    abstract fun bindInstalledAppRepository(impl: InstalledAppRepositoryImpl): InstalledAppRepository
}
