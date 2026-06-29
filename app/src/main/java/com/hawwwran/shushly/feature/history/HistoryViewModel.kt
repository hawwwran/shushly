package com.hawwwran.shushly.feature.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.shushly.BuildConfig
import com.hawwwran.shushly.core.data.AppLearningRepository
import com.hawwwran.shushly.core.data.DecisionHistoryRepository
import com.hawwwran.shushly.core.data.InstalledAppRepository
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.data.db.AppLearningEntity
import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.EligibilityMode
import com.hawwwran.shushly.service.ai.LearningDigester
import com.hawwwran.shushly.service.listener.RecentNotificationContentCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the Decision history list and the Decision detail screen, including behavior-steering. */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: DecisionHistoryRepository,
    private val learnings: AppLearningRepository,
    private val settings: SettingsRepository,
    private val contentCache: RecentNotificationContentCache,
    private val digester: LearningDigester,
    private val installedApps: InstalledAppRepository,
) : ViewModel() {

    /** A history entry decorated for the list: its app icon and whether the app is effectively silenced. */
    data class HistoryRow(
        val entry: DecisionHistoryEntity,
        val icon: ImageBitmap?,
        val alwaysSilenced: Boolean,
    )

    // null = first load not done yet, so the screen can tell "loading" apart from "genuinely empty"
    // (icon loading delays the first emission enough to otherwise flash the empty-state message).
    val entries: StateFlow<List<HistoryRow>?> =
        combine(repository.observeRecent(), settings.settings) { rows, s ->
            // One icon load per distinct package (the repository caches both hits and misses).
            val icons = rows.asSequence()
                .map { it.packageName }
                .distinct()
                .associateWith { installedApps.loadIcon(it) }
            rows.map { entry ->
                HistoryRow(
                    entry = entry,
                    icon = icons[entry.packageName],
                    alwaysSilenced = isAlwaysSilenced(entry.packageName, s),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Everything the detail screen needs to render the steering section for the open row. */
    data class DetailUi(
        val entry: DecisionHistoryEntity? = null,
        val settings: AppSettings = AppSettings(),
        val learning: AppLearningEntity? = null,
        val hasCachedContent: Boolean = false,
        val aiUsable: Boolean = false,
        val busy: Boolean = false,
    )

    var detail by mutableStateOf(DetailUi())
        private set

    /** One-shot snackbar text; the screen calls [consumeMessage] after showing it. */
    var message by mutableStateOf<String?>(null)
        private set

    fun consumeMessage() {
        message = null
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearAll() }
    }

    fun loadDetail(id: Long) {
        viewModelScope.launch { detail = buildDetail(repository.getById(id)) }
    }

    private suspend fun buildDetail(entry: DecisionHistoryEntity?): DetailUi {
        if (entry == null) return DetailUi()
        val s = settings.snapshot()
        return DetailUi(
            entry = entry,
            settings = s,
            learning = learnings.getBySource(entry.id),
            hasCachedContent = contentCache.get(entry.notificationKeyHash.orEmpty(), entry.packageName) != null,
            aiUsable = s.aiConnection.isVerified || BuildConfig.USE_FAKE_CLASSIFIER,
        )
    }

    /**
     * Save (or, if one already exists for this row, flip) the user's correction. A first correction
     * asks the AI to summarise the cached notification into a no-PII digest; a flip reuses that digest
     * so it works even after the 12 h content window.
     */
    fun correct(correction: SmartCorrection) {
        val entry = detail.entry ?: return
        val desired = if (correction == SmartCorrection.SHOULD_ALERT) Decision.ALERT else Decision.SILENT
        viewModelScope.launch {
            val existing = learnings.getBySource(entry.id)
            if (existing != null) {
                learnings.save(existing.copy(desiredDecision = desired.name))
                repository.setFeedback(entry.id, correction.feedback())
                detail = buildDetail(repository.getById(entry.id))
                message = "Updated — Shushly will weigh this for ${entry.appLabel}."
                return@launch
            }
            val cached = contentCache.get(entry.notificationKeyHash.orEmpty(), entry.packageName)
            if (cached == null) {
                message = "This notification's details are no longer available."
                return@launch
            }
            detail = detail.copy(busy = true)
            val digest = try {
                digester.digest(cached.appLabel, cached.title, cached.body, cached.category, desired)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                detail = detail.copy(busy = false)
                message = "Couldn't reach the AI to save this. Try again."
                return@launch
            }
            learnings.save(
                AppLearningEntity(
                    packageName = entry.packageName,
                    appLabel = entry.appLabel,
                    desiredDecision = desired.name,
                    digest = digest,
                    createdAtMs = System.currentTimeMillis(),
                    sourceHistoryId = entry.id,
                ),
            )
            repository.setFeedback(entry.id, correction.feedback())
            detail = buildDetail(repository.getById(entry.id))
            message = "Saved — Shushly will weigh “$digest” for ${entry.appLabel}."
        }
    }

    fun clearCorrection() {
        val entry = detail.entry ?: return
        viewModelScope.launch {
            learnings.deleteBySource(entry.id)
            repository.setFeedback(entry.id, null)
            detail = buildDetail(repository.getById(entry.id))
        }
    }

    fun applyConfig(action: ConfigAction) {
        val entry = detail.entry ?: return
        val pkg = entry.packageName
        viewModelScope.launch {
            val s = settings.snapshot()
            when (action) {
                ConfigAction.ADD_ALWAYS_ALERT -> {
                    settings.setAlwaysAlertPackages(s.alwaysAlertPackages + pkg)
                    message = "${entry.appLabel} will always alert."
                }
                ConfigAction.REMOVE_ALWAYS_ALERT -> {
                    settings.setAlwaysAlertPackages(s.alwaysAlertPackages - pkg)
                    message = "Removed ${entry.appLabel} from always-alert."
                }
                ConfigAction.MAKE_ELIGIBLE -> {
                    setEligible(pkg, eligible = true, s = s)
                    message = "The AI will now decide for ${entry.appLabel}."
                }
                ConfigAction.SILENCE_APP -> {
                    setEligible(pkg, eligible = false, s = s)
                    message = "${entry.appLabel} won't sound anymore."
                }
            }
            detail = buildDetail(repository.getById(entry.id))
        }
    }

    private suspend fun setEligible(pkg: String, eligible: Boolean, s: AppSettings) {
        val selected = s.selectedPackages
        val next = when (s.eligibilityMode) {
            // Whitelist: eligible means present in the set.
            EligibilityMode.SELECTED_APPS -> if (eligible) selected + pkg else selected - pkg
            // Blacklist (default): eligible means ABSENT from the exclusion set.
            EligibilityMode.ALL_APPS_EXCEPT_SELECTED -> if (eligible) selected - pkg else selected + pkg
        }
        settings.setSelectedPackages(next)
    }

    private fun SmartCorrection.feedback(): String = when (this) {
        SmartCorrection.SHOULD_ALERT -> "SHOULD_ALERT"
        SmartCorrection.SHOULD_SILENT -> "SHOULD_SILENT"
    }
}
