# MateLink fixed-SHA remote-review evidence matrix

编制日：2026-10-12。候选：`df36d2beb5aadc661025790152c48d03c9f994db`。

本文件是只读证据复核，不是新的测试执行，也不改变 Owner 记录、Git、设备或运行数据。以下 PASS 指已经存在的本地合成/JVM/Robolectric 结果；不代表物理 Android 通知、Tesla/Fleet provider 或生产验收。按 Jovi 最新要求，本次仅交接当前证据，不启动补充测试、源码修改或下一阶段实施。

## 1. SHA 与执行产物绑定

- `git show -s` 确认固定 commit 为上述 SHA，commit 时间 `2026-10-11T23:39:21+08:00`。
- 当前 `android/app/src`、`android/app/build.gradle.kts`、`deploy/jourvolt-dev-mock` 与该 commit 的 `git diff --name-only` 为空。Owner 文档/tasks dirty 仍保留，没有在本次证据整理中改动。
- `E:\Claude_allow\Download\matelink-final-source-file-hashes-20261011.json` 的 30 项文件 SHA-256 与当前文件逐项匹配，mismatch=0。
- 读取当前 Gradle XML：Debug 共 848，failures=0/errors=0/skipped=0；Release 共 848，failures=0/errors=0/skipped=8，即 840 执行通过、8 SKIPPED。下面重点 suite 在两变体均 skipped=0。
- 重点 XML 最后写入时间为 `2026-10-11 23:49:48`，晚于固定 commit；XML 内 timestamp 为 UTC `2026-10-11T15:49:xx`。原完整 Android 日志 `matelink-final-full-android-20261011.log` 另记录 tests/lint/assemble 和 `BUILD SUCCESSFUL in 11m 10s`，配套 `.exit` 为 0。不要将这些不同时间的产物混为一次命令。
- Go JSON 执行记录 `matelink-go-pg-green-20261011.jsonl` 中下列 TPMS warning 测试均为 Action=pass，时间约 `2026-10-11T22:13:43+08:00`；`matelink-go-race-green-20261011.log` 记录 package `ok`。Go 日志未内嵌 Git SHA；其源码当前与固定候选一致，此文不把日志单独当作不可变构建收据。

固定源码入口：[GitHub commit](https://github.com/Jovifei/tesla-master-mimo/commit/df36d2beb5aadc661025790152c48d03c9f994db)。

## 2. XML 定位规则与测试层级

每个下表标识对应两个实际 XML：

`E:\project\tesla_master\app_mimo\android\app\build\test-results\testDebugUnitTest\TEST-<完整class>.xml`

`E:\project\tesla_master\app_mimo\android\app\build\test-results\testReleaseUnitTest\TEST-<完整class>.xml`

精确 testcase 使用 XPath：`/testsuite/testcase[@name='<下表方法名>']`。该节点没有 failure/error/skipped；suite 的 failures/errors/skipped 均为 0。不能用 source-text suite 代替下面的行为结果。

| 标识 | 完整 class | Debug / Release tests | 实际层级 |
|---|---|---:|---|
| A7 | `com.matelink.data.local.HistoryConsumerIdentityRecoveryTest` | 7 / 7 | 生产 VehicleContextStore/VCR resolver 与 codec/delivery，SharedPreferences I/O double；重建 store/repository，不是实际 Android encrypted prefs |
| A11 | `com.matelink.data.local.HistoryScopeAbaRecoveryTest` | 11 / 11 | 实际 Session save/clear、Mode/Settings setter、组合 snapshot、生产消费者；存储 I/O double，generation 本来就是进程内状态 |
| P21 | `com.matelink.data.local.TpmsPersistentRecoveryTest` | 21 / 21 | Robolectric SDK28，真实文件 Preferences DataStore 和 Room/SQLite，真实 repository/生产 admission/drain/process 函数；通知端为可计数/故障注入 callback |
| U21 | `com.matelink.data.repository.UnifiedHistoryDiscoveryRecoveryTest` | 21 / 21 | 真实 Unified load/resolve orchestration；API、DAO ports 为受控 double |
| CJ12 | `com.matelink.data.sync.CompletionJournalFlowTest` | 12 / 12 | 实际 Moshi journal codec/publication/delivery，受控 I/O |
| W5 | `com.matelink.data.sync.TpmsWarningRecoveryEvidenceTest` | 5 / 5 | 实际 Moshi adapter、共享 Go HTTP fixture、生产压力/警告资格 helper |
| PW17 | `com.matelink.data.sync.TpmsPressureWorkerTest` | 17 / 17 | 生产 `processSuccessfulTpmsStatus` 行为；callbacks 为受控 double，部分方法是 supplemental wiring |
| O6 | `com.matelink.data.sync.TpmsObservationEvidenceTest` | 6 / 6 | 实际 Moshi/receipt projection/change evaluation |
| T5 | `com.matelink.ui.screens.tpms.TpmsTrendIdentityRecoveryTest` | 5 / 5 | 实际异步 trend controller、悬挂最后检查、发布与失败 callback |

P21 的真实存储构造在固定源码 `android/app/src/test/java/com/matelink/data/local/TpmsPersistentRecoveryTest.kt:445`：`PreferenceDataStoreFactory.create` 写 temp directory；`:466` 用 `Room.databaseBuilder(..., File(temp.root,"history.db").absolutePath)`。测试关闭 jobs/database 后重新打开相同文件。不存在生产包/真实用户数据库迁移运行。

## 3. A：来源 ABA、离线命名空间隔离

| 要求 | 精确 testcase / 固定源码 | 核心断言 | 结论与边界 |
|---|---|---|---|
| Session A→B→A，旧请求不重新有效，稳定 key 不变 | A11 `sessionAccountAbaInvalidatesOldRequestWithoutChangingStableHistoryOrJournalIdentity`, `HistoryScopeAbaRecoveryTest.kt:216` | old `isHistoryContextCurrent` false；new true；completionScopeKey/local ID 相同；恢复匹配 UID 不调用 fresh discovery | PASS，实际 save API 和生产 guard，I/O double |
| clear→恢复同账号 | A11 `clearedAndRestoredSessionCannotReuseOldRequestGuardOrDeleteVehicleHistory`, `:200` | 旧 guard false，mapping/journal key 保留 | PASS，未 clear 真实凭证 |
| Mode ABA | A11 `connectionModeAbaInvalidatesOldRequestWithoutChangingStableJournalKey`, `:238` | CLOUD→SELF→CLOUD 后旧 guard false、新 guard true、key 相同 | PASS |
| SELF URL/认证 ABA | A11 `selfHostedServerUrlAbaInvalidatesOldRequestAndPreservesStableHistoryIdentity`, `:166`; `selfHostedAuthenticationAbaInvalidatesOldRequestWithoutPrintingOrMigratingCredentials`, `:183` | 同 URL 文本恢复仍旧 guard false；synthetic token 变化同样 false；key 不变 | PASS，无真实 credentialstorage 写 |
| Mode snapshot 后 Settings await，组合不一致不得授权 | A11 `modeSwitchWhileSettingsSnapshotSuspendsCannotAuthorizeOldSelfHostedRequest`, `:79`; `combinedSnapshotCannotReturnOldModeAfterSuspendedSettingsRead`, `:107` | 实际 Settings data collection 挂起时切 Mode；旧 guard false；失效组合 snapshot 不返回旧 scope | PASS，真实 RED 后保留的 async negative |
| token validator await 期间 ABA，notify 前阻止 | A11 `sourceAbaDuringTokenValidationCannotPostAnAlreadyClaimedCustomAlert`, `:25` | 实际 custom store claim +生产 process；validator 挂起期间 Mode ABA；posts=0、取消传播、pending 保留、claim token 释放 | PASS，不是 source-text；通知是 callback，不是 OS |
| origin cache 重建、legacy 不自动认可 | A7 `notificationCacheRestoresOnlyTheRequestedOriginAfterStoreAndRepositoryRecreation`, `:48`; `unknownOriginLegacyMappingCannotAuthorizeForegroundNotificationRecovery`, `:61` | 重建后 origin A/B 返回各自 local ID；unknown origin/account null；旧 legacy mapping 仍存在且不能资格认证 | PASS，实际 mapping store，memory persistence double |
| 同 numeric ID/receipt 不跨 origin 加载 | A7 `restoredNotificationResolverLoadsOnlyItsOwnOriginRoomKeyWithIdenticalReceiptIds`, `:71` | 实际 resolver→journal codec→delivery；A 解析成功，B 解析 null 且 wrong-origin DAO load count=0 | PASS；此方法的 row lookup 为 map double，不是 Room |
| 离线真实 load 行为和原始证据保留 | U21 `actualOfflineHistoryLoadProjectsUnknownYetPersistsRawSourceReceipt`, `:78`; `offlineLoadRetainsVerifiedDetailInDisplayAndOriginalFleetScalarInRawReceipt`, `:105` | display unknown 不污染 apiEvidence；当前 scope 的缓存 detail 保留 | PASS，真实 orchestration + controlled DAO ports |
| proof 不能跨 origin/mode/car/account | U21 `proofCannotBeReusedAfterOriginModeVehicleOrAccountChange`, `:276`; `accountOrOriginChangeDuringPageFailsClosedWithoutPersisting`, `:345` | proof current=false；跨 scope 页不能 persist/return | PASS，controlled source/API ports |
| 无身份不能凭数字访问历史 | U21 `noPreviouslyVerifiedMappingNeverFetchesByNumericId`; `failedIdentityCannotAuthorizeHistoryEvenWithOriginScopedCache`, `:306` | remote history calls 不被授权；404-only 兼容另有用例 | PASS |

A 的 guard、key、namespace、离线 load 分支有已经执行的行为证据，不能统称“只有字符串测试”。但上述分解测试不等于一个同时使用真实文件身份映射+Session 配置+Room+完整网络 Worker+Compose prompt 的单一端到端测试。更严格的该组合场景在这个 SHA 未提供，见缺口 G1。

## 4. C / E：取消、有效期、恢复、并发

下表 P21 路径均为固定源码 `android/app/src/test/java/com/matelink/data/local/TpmsPersistentRecoveryTest.kt`。

| 要求 | testcase / 起始行 | 精确结果断言 | 状态 |
|---|---|---|---|
| 同 receipt 重启后执行租约可恢复 | `sameReceiptRecoversCustomLeaseAfterRestartAndExecutionTimeAdvance`:55; `sameReceiptRecoversTeslaLeaseAfterRestartAndExecutionTimeAdvance`:70 | synthetic death 后重建文件 store；collector time 固定、execution +360001；deliveries=1；custom 已 commit 不重复 | PASS，Tesla 此用例的 warning DTO 是 legacy complete flags，不是 Go fixture qualified warning 全链 |
| 无新 pressure 的 durable pending drain | `durableCustomPendingDrainsAfterRestartWithoutFreshPressure`:83 | 拒绝通知→重建→details=null；deliveries=1，pending=null | PASS，生产 process +实际文件 store |
| 旧 token / 时钟回退 | `executionClockRollbackTakesOverAndOldCustomTokenCannotCommitOrRelease`:183 | token replacement；旧 commit/release 不改变新 token；新 commit 消费 pending | PASS |
| legacy unqualified 与其他 identity 拒绝 | `legacyUnqualifiedPendingSurvivesRestartWithoutIndependentDelivery`:198; `anotherHistoryIdentityCannotConsumeQualifiedCustomPending`:210 | delivered=0；原 pending 保留 | PASS |
| 原 pending expiry 不借 fresh timestamp | `freshSameStateCannotBorrowItsTimestampForExpiredOriginalPending`:221 | qualified same-state fresh reading 不能 claim 已过期旧 pending；stored observedAt 仍原值 | PASS |
| fresh FR=false 不授权过期 FL=true | `freshUnrelatedWheelCannotResurrectExpiredPendingWarning`:145 | 真实 repository metadata adapter→真实 state store；NoTransition、没有 warning started | PASS |
| 批量 cancellation 不 ack、不遗留未发送 lease | `cancellationBeforeFirstNotificationReleasesEntireDurableCustomClaimBatch`:248 | 两轮 pending 保留、所有 claim/token 清空；马上可重试两轮 | PASS |
| post 后 scope 改变不能 commit | `scopeChangeAfterDeliveryDoesNotCommitCustomClaim`:267 | pending 保留、claim 释放 | PASS；不是跨 post→ack 崩溃“OS严格恰好一次”证明 |
| pressure cancellation release / 重试 | `pressureChangeScopeCancellationKeepsEventAndReleasesLease`:302 | cancellation 后 queue event=1；same source retry delivered=1、queue empty | PASS |
| release 写失败不掩盖 cancellation | `failedReleaseWritePreservesCancellationAndLeaseExpiryCanRecoverUnackedEvent`:378 | assertSame 原 CancellationException；未 ack；live lease 不发；到期后 delivered=1 | PASS |
| future intent 等待时钟恢复 | `futureIntentAfterClockRollbackWaitsBeforeRoomAdmissionAndQueuePromotion`:336 | clock rollback：Room=null、queue empty、intent 留存；时钟有效后同 sample 进入 Room/queue且保留 observedAt | PASS |
| Room→queue 中断恢复 | `admittedRoomReceiptRecoversChangeIntentAfterQueueWriteInterruption`:97 | DAO 已真实写盘后 synthetic death；重新打开同 Room/store；queue=1、original observedAt；ack 后 repeated poll 不 rearm | PASS |
| 并发 producer/consumer 去重 | `concurrentProductionAdmissionsAndConsumersDeliverEachReceiptOnce`:282 | actual production admit/drain、真实 Room/DataStore；queue=1、delivered=1、ack后empty；后续 FR 不重新报警已 ack FL | PASS，所测并发/中断窗口 |
| 不匹配 Room receipt 不造 queue | `unmatchedRoomReceiptKeepsIntentWithoutInventingQueueEvent`:322 | queue empty，intent=1，原 observedAt 保留 | PASS |
| rule disable 期间 claim 悬挂 | `disablingRuleWhileFinalScopeGuardSuspendsPreventsStalePressurePost`:359 | actual rule save 清 event/token；posted=0 | PASS |
| 有界 state、不以 size 丢 valid pending | `ownedRecoveryStateStaysBoundedAcrossLongNaturalSequenceWithoutEvictingValidPending`:404 | synthetic 100-day sequence；31 valid pending，keys<=34；全部过期后 pending empty、仅一个 watermark key | PASS，“NaturalSequence”是测试名称，实际输入仍 synthetic |

补充实际生产函数取消回归：PW17 `cancellationOfTeslaDeliveryCannotCommitOrSwallowAStateTransition`、`cancellationOfCustomDeliveryReleasesClaimAndPropagatesToWorker`、`failedTeslaNotificationDoesNotCommitStateBeforeRetry`；CJ12 `realCancellationIsPropagatedWithoutAcknowledgingOrResettingJournal`、`permissionDenialForegroundConsumeRestartThenSystemDeliveryAreIndependent`。这些 XML 都已 PASS，但不伪装成真实系统权限操作。

## 5. D：Go→Android parser→worker→notify，现有证据与真正缺口

| 层级 | 精确测试/产物 | 已证明 | 状态 |
|---|---|---|---|
| Go actual HTTP→共享 Android fixture | `deploy/jourvolt-dev-mock/tpms_warning_recovery_test.go:12`，`TestTPMSWarningRecoveryHTTPMatchesAndroidMoshiFixture` | actual `api.status`/httptest Code=200；实际 `tpms_details` JSON map 与 `android/app/src/test/resources/tpms_warning_collector_contract.json` DeepEqual | PASS，Go JSON pass事件存在；合成 provider |
| Go warning-only HTTP | 同文件 `:66`，`TestTPMSWarningRecoveryWarningOnlyActualHTTPProvenance` | actual HTTP保留 FL true、FR false、独立 warning source/basis/time；pressure 和未知轮胎 receipt 不制造 | PASS |
| Go parser/store/排序与非法类型 | `TestTPMSWarningRecoveryActualParserStoreAndOutOfOrder`、`PartialRefreshPreservesKnownWheelReceipt`、`RejectsConflictingAliases`、`RejectsUnqualifiedTimesSourcesAndTypes` | 实际 parser/store；旧 true 不覆盖新 false；冲突别名/0/文本 false/缺 source/time/future/stale 不资格认证 | PASS，上述名字按前缀 `TestTPMSWarningRecovery` 定位 Go JSON |
| 同一个 fixture→Moshi→生产资格 helper | W5 `fourDifferentPressureReceiptsMustNotEraseIndependentQualifiedWarnings`, `TpmsWarningRecoveryEvidenceTest.kt:31` | 实际 resource/Moshi；pressure四个时间切片不借 warning time；独立 warning true/false保留、original timestamp 03:59:05Z，pressure字段不填入 warning | PASS，非字符串检查 |
| Android warning严格 negative | W5其余4项 | missing/source/basis/per-wheel time/future/expired拒绝；numeric 0/text false不coerce布尔；null轮胎无 receipt | PASS |
| qualified warning journal重建与identity/expiry | P21 `warningJournalReconstructionRetainsIndependentReceiptsAndCannotCrossIdentity`:162 | 真文件 store 重建，FL/FR各自原时间/true/false保留；其他ID/expired返回null | PASS |
| worker生产函数→claim/release/notify callback | PW17 +P21上述租约/取消/expiry方法 | 生产process与真实文件 state store操作，合成 callback是否调用及是否 ack | PASS，部分warning测试输入是直接DTO/legacy完整flags |
| **同 fixture/Moshi decoded warning一路进入实际 process与 qualified state adapter，最终 warning notify callback** | 现有 W5在helper层终止；P21 `warning`常量是手工DTO且未带warning source/time；journal/expiry测试分别直接调用store/repository | **没有找到该单个连续自动化测试** | **NOT_RUN / 真实接缝缺口 G2** |
| Android真实NotificationManager/permission/channel/用户可见结果 | `TpmsTrendNotificationManagerTest`只测格式/spec/ID；P21通知端为callback | **不能证明 Android OS实际发送、真实权限恢复、锁屏可见、真机点击或真实provider事件** | **NOT_RUN / G3** |

因此 D 已有真实 Go HTTP fixture 等价、真实 Moshi/helper、真实 persisted consumers 分段证据；不能说这条线完全未测试，也不能把分段拼接当作已经执行的完整 worker→OS 通知链。

## 6. 确实未提供的证据及最小补充范围

| 缺口 | 最小准确补充 | 不可声称的验收 |
|---|---|---|
| G1 更强的 A 整合场景 | 在隔离 Robolectric/temp环境：真实持久 identity prefs +Room A/B不同origin同remote/session IDs；A→B→A再离线；真实 Unified load和prompt/system adapter只读A，旧unknown namespace仍存在。现有 A7/U21/P21是分段覆盖，没有这一个连续case。 | 无此单case不能声称“实际文件身份映射+Room+完整UI/Worker离线端到端已跑”；但已有guard/namespace离线分支 PASS 不应重标为未测试。 |
| G2 D parser→qualified worker接缝 | 直接读取**同**共享JSON，用实际Moshi；verifiedTpmsWarnings→真实warning journal/state repository→生产process；固定execution clock；assert warning notify exactly once、原warning time保留、无pressure/未知轮胎发明，拒绝通知后重建可恢复。通知callback仍应明确synthetic。 | 目前不能声称“同一 Go输出经Android parser进入完整warning worker notify已执行”。这个source-only测试不需要生产凭证/车辆/设备。 |
| G3 OS/设备/provider交付 | 需要另行明确的数据保留安装/签名/设备/真实provider验收门；现有本轮任务没有执行。 | 物理设备、真实POST_NOTIFICATIONS/channel toggles、真实Tesla/Fleet事件、生产部署全部 NOT_RUN，不因848/21/11或Go HTTP200转为PASS。 |

另一个诚实的语义边界：现有“once”断言覆盖所测 Room→queue中断、并发claim、正常ack后重复poll；没有为外部OS notify与DataStore ack之间的断电窗口证明全局严格恰好一次。不要扩大这些测试结论。

## 7. 供一次集中回传的结论

固定候选的 A 来源ABA/namespace离线分支和 C cancel/expiry/recovery有明确、已执行的生产逻辑行为/XML证据；P21真实文件Room/DataStore与A11进程scope测试在两变体均全部执行通过。D 的Go HTTP与Android parser共享fixture也有真实执行记录。实际尚缺的是 G2 同payload连续parser→qualified worker→notify callback接缝以及 G1 更强组合整合场景；物理OS/设备/provider是独立 G3 NOT_RUN 门。此文件不改变远端代码方向 APPROVED 或交付 CHANGES_REQUIRED 的决定，只给其精确可复核依据。
