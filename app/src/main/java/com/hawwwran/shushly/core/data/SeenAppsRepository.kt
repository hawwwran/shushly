package com.hawwwran.shushly.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.seenAppsDataStore: DataStore<Preferences> by preferencesDataStore(name = "seen_apps")

/**
 * Tracks which packages have actually posted notifications to Shushly's listener, as a per-package
 * hit count. This is the data source for the picker's "Most used apps" group. Backed by its own
 * DataStore Preferences file; not Room (a later increment).
 */
@Singleton
class SeenAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** package name → number of notifications seen from it. */
    val seenCounts: Flow<Map<String, Int>> = context.seenAppsDataStore.data.map { prefs ->
        prefs.asMap().entries.associate { (key, value) -> key.name to ((value as? Int) ?: 0) }
    }

    /**
     * Fire-and-forget increment on an IO scope so the listener thread is never blocked. The read +
     * write happen inside one DataStore transaction, so concurrent records don't lose updates.
     */
    fun record(packageName: String) {
        if (packageName.isBlank()) return
        scope.launch {
            context.seenAppsDataStore.edit { prefs ->
                val key = intPreferencesKey(packageName)
                prefs[key] = (prefs[key] ?: 0) + 1
            }
        }
    }
}
