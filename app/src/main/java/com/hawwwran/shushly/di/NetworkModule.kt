package com.hawwwran.shushly.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Networking singletons for the relay client (spec §9.4). */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // The relay requires schema_version (z.literal(1)); without this, default-valued fields
        // like schema_version / locale / default_on_ambiguity are dropped and the relay 400s.
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        interceptors: Set<@JvmSuppressWildcards Interceptor>,
    ): OkHttpClient {
        // Read/call timeouts exceed the relay's own 8s OpenAI-client timeout x maxRetries (~16s worst
        // case), plus first-call cold-start: a valid late alert must arrive before we give up. Past
        // the call timeout the pipeline still fails safe to silent.
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        interceptors.forEach(builder::addInterceptor)
        return builder.build()
    }

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val CALL_TIMEOUT_SECONDS = 25L
}

/**
 * Application interceptors as a multibound set. Release contributes none; the `debug` source set
 * adds a redacted HTTP logging interceptor (see DebugNetworkModule). Declaring it here keeps `main`
 * free of any debug-only dependency, so release still compiles.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkInterceptorsModule {
    @Multibinds
    abstract fun appInterceptors(): Set<@JvmSuppressWildcards Interceptor>
}
