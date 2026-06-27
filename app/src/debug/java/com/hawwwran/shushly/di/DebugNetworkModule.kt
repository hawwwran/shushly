package com.hawwwran.shushly.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Singleton

/**
 * Debug-only HTTP logging. Lives in the `debug` source set and `okhttp-logging` is a
 * `debugImplementation`, so neither this module nor the interceptor is compiled into release.
 * Level is HEADERS with the Authorization header redacted, so the device token is never logged.
 */
@Module
@InstallIn(SingletonComponent::class)
object DebugNetworkModule {

    @Provides
    @Singleton
    @IntoSet
    fun provideLoggingInterceptor(): Interceptor =
        HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
            redactHeader("Authorization")
        }
}
