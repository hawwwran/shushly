package com.hawwwran.shushly.feature.picker

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.shushly.core.data.InstalledAppRepository
import com.hawwwran.shushly.core.data.SeenAppsRepository
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.EligibilityMode
import com.hawwwran.shushly.core.model.InstalledApp
import com.hawwwran.shushly.core.policy.ProtectedSourcePolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import javax.inject.Inject

/**
 * Drives the app picker for one [PickerTarget] (eligibility or always-alert), read from the nav
 * argument via [SavedStateHandle]. Combines the installed-app inventory (loaded once), seen-apps
 * counts, the relevant package set, and the search query into a grouped, filtered UI state.
 * Selection persists immediately via [SettingsRepository].
 */
@HiltViewModel
class PickerViewModel @Inject constructor(
    private val installedAppRepository: InstalledAppRepository,
    seenAppsRepository: SeenAppsRepository,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val target: PickerTarget = runCatching {
        PickerTarget.valueOf(savedStateHandle.get<String>(ARG_TARGET) ?: PickerTarget.ELIGIBILITY.name)
    }.getOrDefault(PickerTarget.ELIGIBILITY)

    data class AppRow(
        val packageName: String,
        val label: String,
        val icon: ImageBitmap?,
        val isProtected: Boolean,
        val isAvailable: Boolean,
        val selected: Boolean,
    )

    data class PickerUi(
        val loading: Boolean = true,
        val mostUsed: List<AppRow> = emptyList(),
        val others: List<AppRow> = emptyList(),
        val caption: String = "",
        val query: String = "",
    )

    // null = inventory still loading.
    private val installedApps = MutableStateFlow<List<InstalledApp>?>(null)
    private val query = MutableStateFlow("")

    val uiState: StateFlow<PickerUi> = combine(
        installedApps,
        seenAppsRepository.seenCounts,
        settings.settings,
        query,
    ) { installed, seen, appSettings, q ->
        buildUi(installed, seen, targetSet(appSettings), captionFor(appSettings.eligibilityMode), q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickerUi())

    init {
        viewModelScope.launch {
            installedApps.value = installedAppRepository.getSelectableApps()
        }
    }

    fun setSearch(q: String) {
        query.value = q
    }

    fun toggle(packageName: String) {
        viewModelScope.launch {
            val current = targetSet(settings.snapshot())
            val next = if (packageName in current) current - packageName else current + packageName
            when (target) {
                PickerTarget.ELIGIBILITY -> settings.setSelectedPackages(next)
                PickerTarget.ALWAYS_ALERT -> settings.setAlwaysAlertPackages(next)
            }
        }
    }

    private fun targetSet(s: AppSettings): Set<String> = when (target) {
        PickerTarget.ELIGIBILITY -> s.selectedPackages
        PickerTarget.ALWAYS_ALERT -> s.alwaysAlertPackages
    }

    private fun captionFor(mode: EligibilityMode): String = when (target) {
        PickerTarget.ALWAYS_ALERT ->
            "These apps always sound, without asking the AI — every notification from them."
        PickerTarget.ELIGIBILITY -> when (mode) {
            EligibilityMode.SELECTED_APPS -> "Shushly re-alerts for the apps you check."
            EligibilityMode.ALL_APPS_EXCEPT_SELECTED -> "Shushly re-alerts for every app EXCEPT the ones you check."
        }
    }

    private fun buildUi(
        installed: List<InstalledApp>?,
        seen: Map<String, Int>,
        selected: Set<String>,
        caption: String,
        q: String,
    ): PickerUi {
        if (installed == null) return PickerUi(loading = true, caption = caption, query = q)

        // Selected packages that are no longer installed: still shown (toggleable) so the user can
        // remove them (spec §11.1).
        val installedPkgs = installed.mapTo(HashSet()) { it.packageName }
        val unavailable = selected
            .filter { it !in installedPkgs }
            .map { pkg ->
                InstalledApp(
                    packageName = pkg,
                    label = pkg,
                    icon = null,
                    isProtected = ProtectedSourcePolicy.isProtectedPackage(pkg),
                    isAvailable = false,
                )
            }
        val all = installed + unavailable

        val filtered = if (q.isBlank()) {
            all
        } else {
            all.filter {
                it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }

        fun InstalledApp.toRow() = AppRow(
            packageName = packageName,
            label = label,
            icon = icon,
            isProtected = isProtected,
            isAvailable = isAvailable,
            selected = packageName in selected,
        )

        // "Most used" = installed apps that have actually notified; ordered by count desc, then label.
        val collator = Collator.getInstance()
        val (mostUsed, others) = filtered.partition { it.isAvailable && (seen[it.packageName] ?: 0) > 0 }
        val mostUsedSorted = mostUsed.sortedWith(
            compareByDescending<InstalledApp> { seen[it.packageName] ?: 0 }.thenBy(collator) { it.label },
        )
        val othersSorted = others.sortedWith(compareBy(collator) { it.label })

        return PickerUi(
            loading = false,
            mostUsed = mostUsedSorted.map { it.toRow() },
            others = othersSorted.map { it.toRow() },
            caption = caption,
            query = q,
        )
    }

    companion object {
        const val ARG_TARGET = "target"
    }
}
