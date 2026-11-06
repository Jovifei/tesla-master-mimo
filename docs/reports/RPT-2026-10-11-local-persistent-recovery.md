# MateLink 本地实施与聊天复审接力

本文件是当前阶段台账；尚未完成的项目保持 PENDING。分工以 Jovi 最新授权为准：GPT-6 极高普通 Chat 给方案和完整复审；本地 Codex 在 `E:\project\tesla_master\app_mimo` 实施和验证，不使用 Work。

## 1. 最终目标

官方 Tesla 授权后的多人 Fleet 数据按来源、账号、车辆隔离，移动端可可靠查看和接收新增事件；个人 TeslaMate 历史独立保留。本轮交付 A–E 来源隔离、证据时间/执行租约分离、持久 pending 恢复、独立逐轮 warning receipt、Room admission 到队列的崩溃恢复。自然 Fleet、设备和生产验收单列。

## 2. 已完成的本轮证据

- 原 Owner `c9dea6414b25cff0569bbf2a456454f97953be9e` 与远端 `f0629d8f0a7b02b2b865fd83479a8c12be2f1838` 在原 E 目录合并，尚未提交；初始合成树 `cec358764257558a7259b67859ca3b368415726d`。Owner 功能和 dirty 文档保留，481/481 文件备份 SHA256 核对通过。
- 当前 branch `codex/local-persistent-recovery-20261011`。远端 PLANv2 来自普通 Chat `6acb8405-88a8-83ea-b126-8339d81ca57a`，实际通过 app_mimo 读取本地方案及执行包。完整合同见 `PLAN-2026-10-11-local-persistent-recovery.md`。
- 实际 RED：Go true/false warning 丢失；真实编译 Android 的 resolver/prompt/trend/Moshi 共六项失败；磁盘 Room/DataStore 重建的 B/C/E 四项失败；真实 Session/Mode setter ABA 三项失败。不是仅源码文本或 hash 检查。RED 日志与冻结 XML 在 `E:\Claude_allow\Download`。
- 当前未提交 Go 修复：隔离 PG16 全量 320 项 PASS、0 FAIL/SKIP；隔离 Linux 全量 race PASS。HTTP warning-only 与共用 Android JSON fixture 校验通过，矛盾别名先 RED 后拒绝。
- Web13 项及构建 PASS。TLS29 项及策略 PASS：只在临时 Linux 副本转换 CRLF，保留转换前后 hash；无生产网络操作。10 个源码主题检查和真实 DAO SQL 语义检查 PASS。
- 独立 javac 编译 d119 Java/Moshi replay 对当前编译类 PASS：原 receipt、离线投影和同 ID 合并保留距离/SOC/效率。最终源码还需重跑。

## 3. 本地最终验证与待完成门

本轮最终源码的主机验证 PASS：Debug848/0failure/0error/0skip，Release848/0failure/0error/8skip；lint Debug0error/265warning、Release0error/244warning；KSP/Hilt/R8、Debug和 unsigned Release APK构建完成，Gradle实际11m10s，原生Java Wrapper退出0。重复独立javac→Java/Moshi d119 replay PASS，原始receipt/离线/同ID合并断言保持。Go/隔离PG16 320/0fail/0skip、全量Linux race、Web13/构建、TLS29/策略和10主题/DAO SQL检查已真实通过。

Windows现有gradlew.bat不传播失败退出码，因此最终验证直接调用同一个GradleWrapperMain，未改Owner包装脚本；失败历史与XML均保留，未用退出0掩盖失败。新source冻结30文件SHA256记录在下载目录。Robolectric仅testImplementation；未改Room版本或执行真实Owner数据迁移。

已复现并修复追加守门：Session/Mode/Settings ABA、组合snapshot最后挂起点、token validator后的同步source fence、过期pending、批量取消/释放失败、lease takeover/stale token、并发admission/drain、future intent、UID label变化、100day恢复存储有界。旧namespace源码文字断言与新合同冲突，换为真实persisted resolver行为测试，测试数不变；CompletionState和d119原断言未动。

PENDING：远端集中复审、固定 GitHub SHA/tree/parents、同 SHA 实际 CI；构建当前仍为未提交源码验证。不能把本地PASS或旧f062绿CI直接当最终固定版本通过。

## 4. 关键文件

- `VehicleContextRepository.kt`、`HistoryReadScope.kt`、`JourVoltSessionStore.kt`、`ConnectionMode.kt`、`SettingsDataStore.kt`：来源证明、请求身份快照；generation 只用于请求守门，不进入稳定 history/journal identity。
- `SyncRepository.kt`、`UnifiedHistoryRepository.kt`、`CompletedChargePrompt.kt`、`TpmsTrendViewModel.kt`：真实消费者的挂起前后和发布守门。
- `TpmsPressureWorker.kt`、`TpmsChangeRuleStore.kt`、`TpmsCustomAlertStateStore.kt`、`TpmsStateDataStore.kt`：持久 intent、pending、lease、精确 receipt 与消费回执。
- `TpmsObservationEvidence.kt`、`CarModels.kt`、后端 `telemetry_core.go`/`telemetry_service.go`/`main.go`：压力和 warning 各自来源与 collector 时间。
- 新回归 `HistoryConsumerIdentityRecoveryTest`、`HistoryScopeAbaRecoveryTest`、`TpmsTrendIdentityRecoveryTest`、`UnifiedHistoryDuplicateIdentityTest`、`TpmsPersistentRecoveryTest`、`TpmsWarningRecoveryEvidenceTest` 与 `tpms_warning_recovery_test.go`。

## 5. 下一步与复审要求

本地完整GREEN已完成；固定候选后集中形成一次 EXECUTED 证据包交 GPT-6 Chat 审查真实diff/调用链和恢复协议，执行全部有效反馈并复测。沿原Draft PR17发布候选和核对同 SHA CI，不合main或部署。Owner内容保留。任何 UI、设备、provider或生产层未经本轮实测均为NOT_RUN；实际迁移、安装和生产写入遵守独立授权门。

## 本轮实际日志

`matelink-direct-compiled-red-20261011.log`、`matelink-bce-aba-red-20261011.log`、`matelink-real-red-evidence-20261011/`、`matelink-go-pg-green-20261011.jsonl`、`matelink-go-race-green-20261011.log`、`matelink-tls-host-complete-20261011.log`、`matelink-web-tests-20261011.log`、`matelink-web-build-20261011.log`、`matelink-d119-independent-20261011.log`。均位于 `E:\Claude_allow\Download`；不作为 Git 固定提交后的证据。

最终主机证据：`matelink-final-full-android-20261011.log/.exit`、`matelink-final-focused-green-20261011.log`、`matelink-d119-final-20261011.log`、`matelink-final-source-file-hashes-20261011.json`。Android完整XML位于`android/app/build/test-results/`，lint位于`android/app/build/reports/`；发布前需归档，以免后续运行覆盖。
