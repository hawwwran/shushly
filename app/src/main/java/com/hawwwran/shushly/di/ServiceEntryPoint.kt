package com.hawwwran.shushly.di

import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.service.listener.NotificationPipeline
import com.hawwwran.shushly.service.quietmode.QuietModeController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets system-instantiated services (listener, tile) reach Hilt singletons. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServiceEntryPoint {
    fun pipeline(): NotificationPipeline
    fun settingsRepository(): SettingsRepository
    fun quietModeController(): QuietModeController
}
