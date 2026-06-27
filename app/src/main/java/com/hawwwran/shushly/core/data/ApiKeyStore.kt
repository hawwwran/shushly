package com.hawwwran.shushly.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the user's own provider API key (OpenAI) — a credential, kept out of [SettingsRepository]
 * and only ever sent to the provider. Reads/writes are off the main thread.
 */
interface ApiKeyStore {
    suspend fun get(): String?
    suspend fun set(key: String?)
}

/**
 * Keystore-backed implementation using AndroidX `security-crypto` EncryptedSharedPreferences
 * (MasterKey AES256_GCM). The key is encrypted at rest by a hardware-backed key, never logged.
 */
@Singleton
class EncryptedApiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApiKeyStore {

    // Built on first use (touches Keystore + disk), then reused.
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun get(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_API, null)
    }

    override suspend fun set(key: String?) {
        withContext(Dispatchers.IO) {
            val editor = prefs.edit()
            if (key == null) editor.remove(KEY_API) else editor.putString(KEY_API, key)
            editor.commit()
        }
    }

    private companion object {
        const val FILE_NAME = "shushly_secure_prefs"
        const val KEY_API = "openai_api_key"
    }
}
