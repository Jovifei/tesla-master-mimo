# TPMS 趋势与行程通知 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `subagent-driven-development`; each task uses a fresh Luna/high implementer, then fresh spec and quality reviewers.

**Goal:** 在 Android 本地保存四轮 TPMS 历史，展示 7/30 日曲线、证据化总结和用户确认阈值；同步发现新完成行程后发送本地通知。

**Architecture:** `TpmsPressureWorker` 是唯一采样入口，成功快照写入 Room v17。纯分析器只消费样本、已同步行程和温度；`SyncRepository` 维护每车通知水位，首次同步静默。

**Boundaries:** 仅本地 Android；不改 Adapter、MQTT、iOS、Web、服务端、真实车辆权限或网络配置；不 stage、commit、push、reset、clean、stash、卸载、清数据或运行 instrumentation。

## Fixed contract

- 样本仅在 `matelink_stats.db`，按 `carId + observedAt` 去重，保留 90 天；任何轮缺失时保留其他有效值，图表断线且不填 `0`。
- 7/30 日最多显示 FL/FR/RL/RR 四线；无有效样本显示不可用。
- 总结仅输出有证据的“可能因素”：四轮同向且温度变化、近 6 小时高速后四轮升压、停放至少 24 小时后一轮相对其他轮下降至少 `0.2 bar`；否则明确原因无法判断。
- Tesla `tpms_soft_warning_*` 优先。用户保存门柱冷胎压后才启用自定义阈值；预填可编辑的 Model Y `2.9 bar / 42 psi`，低/高建议 `target - 0.3`、`target + 0.5 bar`，必须标记为 App 自定义提醒。
- 行程通知只在首次同步后的新完成 `driveId` 发出；首次成功同步只建立水位。通知含起点→终点、距离和时长；地址缺失显示本地化未知。

### Task 1: TPMS 本地样本与纯分析

**Files:**

- Create: `android/app/src/main/java/com/matelink/data/local/entity/TpmsPressureSample.kt`
- Create: `android/app/src/main/java/com/matelink/data/local/dao/TpmsPressureSampleDao.kt`
- Create: `android/app/src/main/java/com/matelink/data/repository/TpmsHistoryRepository.kt`
- Create: `android/app/src/main/java/com/matelink/domain/analytics/TpmsTrendAnalyzer.kt`
- Create: `android/app/src/test/java/com/matelink/domain/analytics/TpmsTrendAnalyzerTest.kt`
- Create: `android/app/src/test/java/com/matelink/data/local/TpmsPressureSampleDaoTest.kt`
- Modify: `StatsDatabase.kt`, `DatabaseModule.kt`, `SettingsDataStore.kt`

1. RED tests: missing tire stays null while valid tires persist; ambient/highway/parking evidence; insufficient evidence has no cause.
2. Run `:app:testDebugUnitTest --tests com.matelink.domain.analytics.TpmsTrendAnalyzerTest`; expect failure.
3. Add `tpms_pressure_samples` entity `{carId, observedAt, pressureFl, pressureFr, pressureRl, pressureRr, outsideTempC}`, DAO range/prune queries, `StatsDatabase` v17 and non-destructive `MIGRATION_16_17`.
4. Add per-car DataStore alert profile and pure analyzer. It must reject non-finite input and preserve observed zero.
5. Re-run analyzer and DAO tests; expect all pass.

### Task 2: TPMS worker capture and custom warning transitions

**Files:**

- Create: `android/app/src/main/java/com/matelink/notification/TpmsTrendNotificationManager.kt`
- Create: `android/app/src/test/java/com/matelink/data/sync/TpmsPressureWorkerTest.kt`
- Create: `android/app/src/test/java/com/matelink/notification/TpmsTrendNotificationManagerTest.kt`
- Modify: `TpmsPressureWorker.kt`, both `strings.xml`, `NotificationFormatStringContractTest.kt`

1. RED tests: successful snapshot persists one sample; low/high transition notifies once; Tesla soft warning remains higher priority.
2. Persist sample after successful status response, prune 90-day history, and evaluate custom threshold only after profile confirmation.
3. Reuse TPMS channel with distinct IDs/text. Missing readings must not form custom warnings or logs containing payload/coordinates.
4. Run targeted Worker/notification tests and resource format contract; expect all pass.

### Task 3: 7/30 日趋势 UI and settings

**Files:**

- Create: `ui/screens/tpms/TpmsTrendViewModel.kt`, `ui/screens/tpms/TpmsTrendScreen.kt`, `ui/screens/tpms/TpmsTrendPresentationTest.kt`
- Modify: `DashboardScreen.kt`, `NavGraph.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`, both `strings.xml`

1. RED tests: 7/30 range, null segment breaks, unavailable state, custom threshold label.
2. Add typed `Screen.TpmsTrend(carId, exteriorColor)` and make the existing Dashboard tire section its sole new entry point; no new bottom tab.
3. Render four fixed wheel series with gaps, coverage, deltas, possible-factor evidence and manual-check recommendation. Add validated `0 < low < target < high` settings and door-label explanation.
4. Run presentation tests and locale resource checks; expect all pass.

### Task 4: New completed-trip notification

**Files:**

- Create: `notification/TripNotificationManager.kt`, `data/local/TripNotificationStateStore.kt`
- Create: `test/.../TripNotificationManagerTest.kt`, `test/.../TripNotificationStateStoreTest.kt`
- Modify: `SyncRepository.kt`, both `strings.xml`, `NotificationFormatStringContractTest.kt`

1. RED tests: first sync creates watermark without notification; later completed drive notifies exactly once; missing addresses use localized fallback.
2. In `syncDriveSummaries`, compare fetched summaries with one per-car DataStore watermark. First successful sync writes the maximum ID. Subsequent IDs above watermark post high-importance `trip_updates_channel` notifications and then advance watermark.
3. Tap action routes to existing `Screen.DriveDetail`; no immediate-push claim.
4. Run targeted tests; expect all pass.

### Task 5: integrated verification

**Files:** `tasks/todo.md`

1. Run Debug/Release JVM, Debug/Release assembly, `lintRelease`, Adapter `go test ./... -count=1`, `go vet ./...`, and Compose config.
2. Audit Release for Debug/mock/loopback markers and `MissingTranslation=0`.
3. On only `com.matelink.test.mock`, test TPMS threshold and trip-completed notifications. Keep formal `com.matelink` and its configuration intact; do not instrument, uninstall or clear data.
4. Record exact evidence and any unavailable live-data limit in `tasks/todo.md`.
