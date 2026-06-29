package com.hawwwran.shushly.core.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.hawwwran.shushly.core.model.InstalledApp
import com.hawwwran.shushly.core.policy.ProtectedSourcePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inventory of launchable installed apps for the picker. The QUERY_ALL_PACKAGES dependency and the
 * Play-release strategy (spec §18, still open) are isolated here: the inventory never leaves the
 * device (spec §11.2).
 */
interface InstalledAppRepository {
    /** Launchable apps (excluding Shushly), protected-marked, locale-sorted by label. */
    suspend fun getSelectableApps(): List<InstalledApp>

    /**
     * The launcher icon for one package, or null if the package is gone or has no icon. A hit is cached
     * for the process lifetime; a miss is cached only briefly, so an app installed after a failed lookup
     * still gets its icon without a process restart while repeated PackageManager lookups are still spared.
     */
    suspend fun loadIcon(packageName: String): ImageBitmap?
}

@Singleton
class InstalledAppRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstalledAppRepository {

    // A hit is cached for the process lifetime; a miss is cached with its fetch time so it is
    // re-attempted after MISS_RETRY_MS (the package may be installed later) instead of either
    // re-hitting PackageManager on every list emission or being negatively cached forever.
    private val iconCache = ConcurrentHashMap<String, IconHolder>()

    private class IconHolder(val bitmap: ImageBitmap?, val fetchedAtMs: Long)

    override suspend fun getSelectableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val selfPackage = context.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }

        // De-dupe by package (an app may expose more than one launcher activity); first wins.
        val byPackage = LinkedHashMap<String, InstalledApp>()
        for (info in resolved) {
            val pkg = info.activityInfo.packageName
            if (pkg == selfPackage || byPackage.containsKey(pkg)) continue
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            val icon = runCatching { info.loadIcon(pm).toBitmap().asImageBitmap() }.getOrNull()
            byPackage[pkg] = InstalledApp(
                packageName = pkg,
                label = label,
                icon = icon,
                isProtected = ProtectedSourcePolicy.isProtectedPackage(pkg),
            )
        }
        val collator = Collator.getInstance()
        byPackage.values.sortedWith(compareBy(collator) { it.label })
    }

    override suspend fun loadIcon(packageName: String): ImageBitmap? {
        iconCache[packageName]?.let { cached ->
            // A hit is final; a miss is honoured only until its retry window lapses.
            if (cached.bitmap != null || System.currentTimeMillis() - cached.fetchedAtMs < MISS_RETRY_MS) {
                return cached.bitmap
            }
        }
        return withContext(Dispatchers.IO) {
            val bitmap = runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
            }.getOrNull()
            iconCache[packageName] = IconHolder(bitmap, System.currentTimeMillis())
            bitmap
        }
    }

    companion object {
        private const val MISS_RETRY_MS = 60_000L
    }
}
