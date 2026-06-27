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
 * Stores the relay device token — a credential, kept separate from [SettingsRepository] (spec §10.2).
 * Reads/writes are off the main thread.
 */
interface DeviceTokenStore {
    suspend fun get(): String?
    suspend fun set(token: String?)
}

/**
 * Keystore-backed implementation using AndroidX `security-crypto` EncryptedSharedPreferences
 * (MasterKey AES256_GCM). The token is encrypted at rest by a hardware-backed key.
 */
@Singleton
class EncryptedDeviceTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceTokenStore {

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
        prefs.getString(KEY_TOKEN, null)
    }

    override suspend fun set(token: String?) {
        withContext(Dispatchers.IO) {
            val editor = prefs.edit()
            if (token == null) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, token)
            editor.commit()
        }
    }

    private companion object {
        const val FILE_NAME = "shushly_secure_prefs"
        const val KEY_TOKEN = "device_token"
    }
}
