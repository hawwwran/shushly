# Behavior steering — make the decision-detail feedback actionable

## Context

Today the Decision-detail screen has a "Was this right?" section with two `FilterChip`s
("This should have alerted" / "This should have stayed silent"). They persist a local-only
`userFeedback` string and **do nothing** — the screen even admits it: *"Saved on this device only.
Shushly doesn't learn from this yet."* (`DecisionDetailScreen.kt:126`).

This feature turns that dead end into a working steering loop:

1. The feedback buttons become **prominent, filled, purple** and actually change Shushly's behaviour.
2. The buttons are **context-aware**: a notification the AI judged gets an AI-steering button; a
   notification suppressed by *configuration* (not in an allowed list, always-alert, etc.) gets
   **list-management** buttons instead. The section is hidden where steering is meaningless.
3. Tapping an AI-steering button asks the AI to write a short, generalised, **no-PII digest** of that
   notification (e.g. `cz.mafra.idnes → "extreme weather warning"`), saves it as a per-app learning,
   and **injects all of that app's learnings into every future AI request for that app** — so the AI
   improves as the user steers it.
4. The learnings are **listed in the AI-connection tab**, grouped by app, expandable, deletable.

### Decisions locked with the user

- **Digest is generated on button tap** (not pre-computed at classify time), via a fresh, user-initiated AI call.
- The raw notification content needed to write the digest is **kept in memory for 3 h** (`CONTENT_RETENTION_MS`). It is **never written to disk** — the existing "never persist raw title/body" invariant stays literally true. If the process is killed the cache clears early; that is acceptable.
- **If the content is gone, the AI-steering buttons must not show** (it can't make sense to "summarise" a notification whose text no longer exists). Config buttons don't need content and stay.
- Digests **accumulate** as per-app example phrases (deduped + capped), not one rule per app.
- Learnings live in their **own Room table** so the 30-day history purge never deletes them.

---

## 1. Data model & database

**New entity** `core/data/db/AppLearningEntity.kt`:

```kotlin
@Entity(
    tableName = "app_learnings",
    indices = [Index("packageName"), Index(value = ["sourceHistoryId"], unique = true)],
)
data class AppLearningEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val desiredDecision: String,   // "ALERT" | "SILENT"
    val digest: String,            // short, generalised, no-PII topic phrase
    val createdAtMs: Long,
    val sourceHistoryId: Long?,    // = the decision_history row id it was created from
)
```

`sourceHistoryId` is unique so a single corrected row maps to exactly one learning — re-tapping
toggles/undoes it in place instead of piling up duplicates. After the 30-day purge the source row is
gone but the learning is self-contained (it carries packageName/appLabel/digest/decision), so the
dangling id is harmless.

**New DAO** `core/data/db/AppLearningDao.kt`:
- `observeAll(): Flow<List<AppLearningEntity>>` — for the AI tab.
- `forPackage(pkg: String, limit: Int): List<AppLearningEntity>` — for prompt injection (newest first, bounded).
- `getBySource(sourceHistoryId: Long): AppLearningEntity?`
- `@Upsert suspend fun upsert(e: AppLearningEntity): Long`
- `updateDecision(sourceHistoryId: Long, decision: String)` — offline flip (no AI call).
- `deleteById(id: Long)` / `deleteBySource(sourceHistoryId: Long)`

**New repository** `core/data/AppLearningRepository.kt` (interface + `*Impl`), bound with `@Binds` in
`di/AppModule.kt` (same pattern as `DecisionHistoryRepository`).

**Migration** — `ShushlyDatabase.kt`: bump `@Database(version = 2 → 3)`, add `AppLearningEntity` to
`entities`, add `abstract fun appLearningDao()`. Add `MIGRATION_2_3` that only runs
`CREATE TABLE app_learnings (...)` + indices (no change to `decision_history`; we reuse its existing
`userFeedback` column as the "this row was corrected" marker). Register it in
`di/DatabaseModule.kt` (`.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`) and add a
`provideAppLearningDao` `@Provides`.

---

## 2. The 3-hour content cache (in-memory)

**New pipeline collaborator** (interface, so the pipeline test can fake it — per CLAUDE.md):
`service/listener/RecentNotificationContentCache.kt`

```kotlin
interface RecentNotificationContentCache {
    data class Cached(
        val packageName: String, val appLabel: String,
        val title: String?, val body: String?, val category: String?,
    )
    fun put(keyHash: String, packageName: String, appLabel: String, title: String?, body: String?, category: String?)
    fun get(keyHash: String, packageName: String): Cached?  // null if absent, expired (>3h), or pkg mismatch
}
```

- Impl `InMemoryRecentNotificationContentCache` (`@Singleton`, bound via `@Binds`): a
  `ConcurrentHashMap` with a 3 h TTL on read + a size cap (e.g. 256, evict oldest) + lazy purge.
  Constructor takes `nowMs: () -> Long = System::currentTimeMillis` so the TTL is unit-testable.
- **Key** = `notificationKeyHash` (the exact string already stored on the history row at
  `NotificationPipeline.kt:162`). `get()` also verifies `packageName` to neutralise the negligible
  hashCode-collision risk. No DAO/insert signature changes needed — both the pipeline (writer) and
  the detail screen (reader) already have `notificationKeyHash`, and they share one process.

---

## 3. AI layer

### 3a. Generate the digest (write path, on button tap)

**`AiProvider` / `OpenAiProvider`** — add a second, cheap call alongside `classify`:

```kotlin
// AiProvider.kt
suspend fun summarizeForLearning(
    content: RecentNotificationContentCache.Cached,
    desiredDecision: Decision,
    apiKey: String, model: String,
): String
```

`OpenAiProvider.summarizeForLearning` reuses the existing okHttp/json/strict-`json_schema` machinery
(mirror `buildRequestBody`/`parseClassification`). A new `DIGEST_SYSTEM_PROMPT` instructs: *"Return a
3–6 word generalised topic for this notification with NO names, numbers, addresses, amounts or other
specifics — just the kind of notification, so the user can teach per-app preferences. Examples:
'extreme weather warning', 'package delivery update', 'social reaction'."* Strict schema
`{ topic_digest: string }`, `temperature: 0`; sanitise (strip control chars) and bound (`take(80)`).
Throws on any failure (caller surfaces an error, saves nothing).

**New app-facing seam** `service/ai/LearningDigester.kt` (interface) + `LearningDigesterImpl`
(`@Singleton`, `@Binds`). The impl resolves key/model/provider exactly like `DirectAiClassifier`
and routes like `RoutingAiClassifier` (verified key → OpenAI; debug → a deterministic fake phrase;
else throw). The ViewModel depends on the **interface** so it can be faked in tests.

### 3b. Inject learnings into classification (read path, every request for that app)

- `core/model/Classification.kt`: add `val appLearnings: List<AppLearning> = emptyList()` to
  `ClassificationRequest`, where `AppLearning(desiredDecision: Decision, digest: String)` is a tiny new
  model. Default empty keeps every existing construction (incl. pipeline tests) compiling and leaves
  `AiProvider.classify`'s signature unchanged.
- `DirectAiClassifier.classify` loads the learnings (`appLearningRepository.forPackage(request.packageName, cap)`)
  and enriches the request (`request.copy(appLearnings = …)`) before calling `provider.classify(...)`.
  This keeps the pipeline and its pure test untouched on the input path — injection is an AI-layer concern.
- `OpenAiProvider.buildSystemPrompt` appends a bounded **advisory** block *after* the custom
  instruction (same footing as the user instruction):

  ```
  Learned preferences for this app, from the user's own past corrections (advisory; the safety and
  format rules above still win):
  - Usually ALERT: extreme weather warning; service outage
  - Usually SILENT: routine political news; sports score
  ```

  Cap total items/chars to control tokens and the (low, user-curated) prompt-injection surface.

`FakeAiClassifier` is unchanged (debug path ignores learnings).

---

## 4. Pipeline change (one line of intent)

`service/listener/NotificationPipeline.kt`: inject `RecentNotificationContentCache`. Immediately
before the classify call, cache the content so any AI-judged outcome (SILENT / ALERT / ERROR-failsafe)
can later be corrected:

```kotlin
contentCache.put(e.notificationKey.hashCode().toString(), e.packageName, e.appLabel, e.title, e.body, e.category)
val result = try { classifier.classify(e.toRequest()) } ...
```

Nothing else in the pipeline changes. Add a fake cache to `PipelineTestDoubles.kt`.

---

## 5. Decision-detail UI — the steering section

Replace `FeedbackSection`/`FeedbackChip` with a context-aware `SteeringSection`. A **pure** function
decides what to show (fully unit-testable, matching the project's JVM-only test approach):

`feature/history/Steering.kt`:
```kotlin
fun steeringFor(
    entry: DecisionHistoryEntity,
    settings: AppSettings,
    hasCachedContent: Boolean,
    hasExistingLearning: Boolean,
    aiUsable: Boolean,         // verified key present (or debug fake)
): Steering
```

### Scenario → buttons

| Row (decision / reasonCode) | Purple smart button (the single corrective action) | Config buttons (no content needed) |
|---|---|---|
| AI silenced — `SILENT` + `SILENT_*`, aiCalled | **"This should have alerted"** | "Always alert for {app}" |
| AI alerted — `ALERT` + `ALERT_*` (not `ALERT_ALWAYS`), aiCalled | **"This should have stayed silent"** | "Silence {app}" |
| AI unavailable — `ERROR` + `ERROR_AI_UNAVAILABLE`, sounded | **"This should have stayed silent"** | "Silence {app}" |
| Always-alert — `ALERT` + `ALERT_ALWAYS` | — | "Remove {app} from Always-alert" |
| Not eligible — `SKIPPED` + `SKIPPED_NOT_ELIGIBLE` | — | "Always alert for {app}", "Let the AI decide for {app}" |
| Protected source — `SKIPPED_PROTECTED_SOURCE` | — | none — explanation only |
| Other skips — `QUIET_MODE_OFF`, `PHONE_IN_USE`, `NO_USABLE_TEXT`, `SILENT_GROUP_SUMMARY`, `DUPLICATE`, `RATE_LIMIT` | — | "Always alert for {app}" where it makes sense; else explanation |

**Smart-button visibility rule** (the "had a possibility to notify" gate): show the smart button only
when the scenario is one of the three AI rows **and** (`hasCachedContent && aiUsable`) **or**
`hasExistingLearning`. Otherwise hidden. (`hasExistingLearning` keeps an already-saved correction
visible/undoable after the 3 h window.)

**Config buttons are mode-aware** (reuse the read-modify-write set pattern from `PickerViewModel.toggle`,
writing via `SettingsRepository.setSelectedPackages` / `setAlwaysAlertPackages`):
- "Let the AI decide for {app}" = make eligible: blacklist mode (`ALL_APPS_EXCEPT_SELECTED`, default) →
  **remove** from `selectedPackages`; whitelist mode (`SELECTED_APPS`) → **add**.
- "Silence {app}" = make ineligible: the inverse per mode.
- Always-alert button shows "Add"/"Remove" based on current membership in `alwaysAlertPackages`.

### Styling — "prominent, active, purple"

The app uses a bare `MaterialTheme {}`, so `colorScheme.primary` is the Material-3 baseline purple
(`#6750A4`). The smart button is a **filled `Button`** (default = primary container), full-width,
prominent. **Active state**: once tapped it renders "✓ Saved — Shushly will weigh this for {app}" with
the saved digest beneath and an Undo; Undo deletes the learning and clears `userFeedback`. Config
actions are tonal/outlined buttons; a `Snackbar` confirms each.

### ViewModel — `HistoryViewModel`

Inject `AppLearningRepository`, `SettingsRepository`, `RecentNotificationContentCache`,
`LearningDigester`. Add `appSettings` state + the row's existing learning + `hasCachedContent`, feeding
`steeringFor`; `correct(entry, desired)` (offline flip if learning exists, else digest+save);
`clearCorrection`; mode-aware `addAlwaysAlert/removeAlwaysAlert/makeEligible/silenceApp`; a one-shot
`message` for snackbars.

---

## 6. AI-connection tab — list the learnings

- `AiConnectionViewModel`: inject `AppLearningRepository`; expose grouped `learnings` + `deleteLearning(id)`.
- `AiConnectionScreen`: add a section below `StatusCard` — **"What Shushly has learned from you"**.
  Per-app expandable groups (`remember { mutableStateOf(false) }` + `AnimatedVisibility`); each entry
  shows a decision chip (ALERT = primary/purple, SILENT = muted), the digest text, and a delete icon.
  Empty state: *"As you correct decisions in History, Shushly remembers per-app hints here and sends
  them to the AI for that app."* Inline (the list is small/user-curated) — no new route.

---

## 7. Tests (pure-JVM, `app/src/test`)

- **`SteeringTest`** — table-driven over `steeringFor(...)`: every scenario, both eligibility modes,
  the content/AI gating.
- **`RecentNotificationContentCacheTest`** — 3 h TTL expiry, size-cap eviction, packageName-verified `get`.
- **`OpenAiProviderTest`** (extend) — classify with `appLearnings` puts the advisory block in the body;
  `summarizeForLearning` parses/sanitises/bounds and throws on malformed JSON.
- **`NotificationPipelineTest`** (extend) — content **is** cached when the classifier is called and
  **not** for skipped-before-AI branches.

---

## 8. On-device verification (vivo V2145)

1. Tests green; `assembleDebug`; `adb install -r`.
2. Eligible notification the AI silences → History → detail: single **purple "This should have
   alerted"** shows. Tap → snackbar + marked/Undo with the digest.
3. AI-connection tab → app appears under "What Shushly has learned" with the digest; expand; delete works.
4. Next notification from that app → the learning rides along (debug can log the outgoing prompt).
5. **3 h gate**: force-stop the app, reopen the detail → smart button gone; config buttons remain; an
   already-corrected row still shows its marked/Undo state.
6. Not-eligible row → "Always alert"/"Let the AI decide" change the lists (verify in picker).
7. Clear History → learnings survive (separate table; purge worker only touches `decision_history`).

---

## 9. Risks / caveats

- **In-memory cache** clears on process death → smart buttons can vanish before 3 h (accepted).
- Smart steering needs an AI connection; with no key only config steering applies.
- Digests derive from notification text → treated as advisory, bounded, sanitised, injected after the
  hard rules, same footing as the custom instruction.
- Learnings are sent to OpenAI on every request for that app → small, bounded token cost.
- Raw content now lives in RAM for ≤3 h (never on disk); the on-disk "never persist title/body"
  invariant is unchanged.
