package com.hawwwran.shushly.di

import com.hawwwran.shushly.service.ai.OpenAiProvider
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

/** Networking singletons for direct provider calls (OpenAI). */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** The real OpenAI base URL; tests construct OpenAiProvider directly with a MockWebServer URL. */
    @Provides
    @Singleton
    fun provideOpenAiProvider(okHttpClient: OkHttpClient, json: Json): OpenAiProvider =
        OpenAiProvider(okHttpClient, json, "https://api.openai.com")

    @Provides
    @Singleton
    fun provideOkHttpClient(
        interceptors: Set<@JvmSuppressWildcards Interceptor>,
    ): OkHttpClient {
        // Generous read/call timeouts: OpenAI tail latency + first-call cold start can exceed 10s, and
        // a valid late alert must arrive before we give up. Past the call timeout the pipeline still
        // fails safe to silent.
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
