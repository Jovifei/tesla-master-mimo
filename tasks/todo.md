# 2026-08-30 iOS Apple 重设计（分支 feature/ios-apple-redesign，禁止提交 main）

- [x] Apple 设计系统 + 类型安全导航 + 核心页重写
- [x] 车辆核心逻辑：开口告警、isCharging、详情曲线/轨迹、当前充电轮询、换车持久化（`e9666f7`）
- [x] 列表筛选、snapshot、分析全量、Trips/TPMS/Countries/WhereWasI（`f72a4ec`）
- [ ] Mac：`xcodegen generate` + `pod install` + `xcodebuild`（Windows 卡死，无法证明）
- [ ] Mock 手测：筛选、snapshot 徽章、长途/胎压/国家/位置、充电 7 天默认
- [ ] 将来：Widget target、APNs、高德 SDK、Sentry 真机采集、Watch

交接（完成 / 卡点 / 将来）：`docs/IOS-APPLE-REDESIGN-HANDOFF-2026-08-30.md`

# JourVolt Android rollout implementation - 2026-08-09

## 2026-09-01 真机登录与高德配置回归（当前）

### Plan

- [x] 连接并核对真实 `com.matelink` 包、版本、签名和保留数据。
- [x] 捕获登录页与高德配置页现状，读取 AndroidRuntime/网络错误证据并定位根因。
- [x] 以现有面板风格完成最小 Android 修复，并补回归测试。
- [x] 使用同签名 `adb install -r` 覆盖升级，验证登录入口、高德向导、返回/错误/重试和冷启动无崩溃。
- [x] 记录实际设备证据、未执行的真实 Tesla/Telemetry 边界和下一步。

### Review

- LOCAL PASS：定向回归、Android Debug/Release 单测、AndroidTest 编译、Debug/Release 构建和 Release lint 通过；Debug/Release 均完成 468 项，Release 8 项预期跳过，lint 0 Error/0 MissingTranslation。
- DEVICE PASS：OnePlus 7 Pro `6e4fa92f` 使用同签名 `adb install -r` 从 `com.matelink` 1.4.3 升级至 1.4.4；`firstInstallTime` 保持，v2 签名通过。登录协议地址、官方 Tesla 授权入口、设置返回、高德三步面板和 Key 对话框均已验证。
- DEVICE REVIEW：曾捕获一次 `Input dispatching timed out`；ANR 采样中 MateLink 主线程处于 OnePlus `__refrigerator`，无 Java/Kotlin 阻塞栈。重启后重复设置/登录/高德路径未再复现，记录为设备级偶发 ANR，后续继续观察。
- TELEMETRY PILOT PASS：NOT_PERFORMED；未输入 Tesla 凭据，未完成真实车辆 Telemetry、虚拟钥匙配对或真实行程/充电事件。
- APK：`E:\Claude_allow\Download\matelink-1.4.4-login-amap-panels-final-20260902.apk`，SHA-256 `A5D85DDEFA674353223589694D9CAF1AB03F3A58156AD582FDB050D2187A1AD`。

## 2026-09-02 登录、高德配置与设备回归修复记录

- 登录页原先因 Release 漏传 `MATELINK_PUBLIC_INFO_BASE_URL` 被错误禁用；现在默认指向 `https://auth.teslalink.joviluma.com`，Release 同时要求显式传入并做 host 校验。
- 登录页改为统一面板层级，设置重新授权可返回设置；只点击登录按钮时实际打开官方 `https://auth.tesla.cn/oauth2/v3/authorize`。
- 高德配置页改为进度面板、内容面板、状态面板和统一返回/下一步按钮；无 Key、隐私确认和验证对话框均未崩溃。
- 证据与剩余边界见 `docs/BUG-REPAIR-REPORT-2026-09-02-login-amap-device.md`；本轮源码提交到 `main`，未部署服务器。

## 2026-09-01 主分支同步与下一步 Telemetry Pilot

### Plan

- [x] 完成本轮 Android/Go 修复、测试和文档的范围审查后提交到 `main`，代码提交为 `3e5a769`。
- [x] 推送 `main` 到 `origin/main`，并核对远端与本地完整 SHA 一致。
- [ ] 在真实车辆上完成虚拟钥匙配对、Telemetry 配置同步和位置/胎压/行程/充电事件验证。
- [ ] 若真实验证需要，单独部署并验证 ECS 最新兼容路由；不以本地 Mock 代替真实 Pilot。

### Review

- PASS：`3e5a769` 已推送到 `origin/main`；提交范围无 iOS、无生成 EXE、无 keystore/env/properties。
- PASS：新鲜 Android Debug/Release 测试、构建、AndroidTest 编译、Release lint、Go test/vet、Compose、diff check 和精确敏感扫描均通过。
- NOT_PERFORMED：真实虚拟钥匙配对、Telemetry 配置同步、位置/胎压/行程/充电事件和生产部署仍未执行。
- Boundary：本地生成的 `deploy/jourvolt-dev-mock/jourvolt-dev-api.exe` 未提交；下一阶段不以本地 Mock 代替真实 Pilot。

## 2026-08-31 收尾复核

- [x] MQTT：手动 ACK 仅在持久化成功/持久化重复/永久无效后执行；未知映射、取消、数据库故障和背压保持未确认；连接代次、启动取消、停止排空和 readyz 状态均有回归覆盖。
- [x] Android：数据状态页支持官方 Tesla 虚拟钥匙链接、显式 Telemetry 配置、`config_synced` 三态、30 秒墙钟轮询、切车单飞锁和等待超时后的重试入口。
- [x] 本地门禁：Android Debug/Release 各 447 项（Release 8 项预期跳过）、assembleDebug/Release、lintRelease（0 errors）、androidTest 编译、Go test/vet、Compose config、资源 parity、Release 标记扫描通过。
- [x] 临时 PostgreSQL 集成：`TestTelemetryPostgres` 通过；临时容器已删除，未触碰既有容器和数据。
- [ ] DEVICE PASS / TELEMETRY PILOT PASS：未执行真实设备配对、真实 Tesla/Fleet Telemetry、生产部署；这些不由本地门禁替代。

## 2026-08-31 Task2 server config-sync truth repair (current run)

- [x] PLAN: Restricted changes to JourVolt mock telemetry pairing/store/core/MQTT/service tests and this ledger; preserved unrelated owner changes and performed no Git or deployment mutations.
- [x] RED: `TestTask2MQTTStatusDoesNotOverwriteVerifiedConfigSync`, `TestTask2PairingConfigSyncFalseAndUnknownRemainStoredTruth`, and `TestTask2ConfigureErrorPreservesLastVerifiedConfigTruth` each failed before the repair because status-derived truth became `null` after MQTT/error status writes.
- [x] GREEN: Added nullable `config_synced` to memory and `jourvolt_telemetry_pairing`, including `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`; official configuration GET outcomes and explicitly skipped vehicles write truth, while memory/PostgreSQL status updates preserve it.
- [x] VERIFY: Task2/affected Go tests passed; fresh `go test ./... -count=1` passed (3.136s); `go vet ./...`, both Compose config checks, and full `git diff --check` exited 0. The configured optional PostgreSQL roundtrip test is present but skipped because `JOURVOLT_TEST_DATABASE_URL` is unset. No stage/commit/push/reset/stash/clean/deploy.

### Task2 server-truth review

- PASS (2026-08-31): `config_synced` is now a nullable stored value and remains JSON `null` when unknown. Official GET `synced=true` writes true; official GET false and explicit skipped/missing-key write false; accepted POST and command/query errors preserve the prior stored truth. MQTT `collecting`, `waiting_vehicle`, and error status paths now change only operational status. Scoped review found no secret/VIN response exposure or whitespace errors.
- PASS (2026-08-31): The remaining persistence-error path is fail-closed. `setPairingConfigTruth` now returns the PostgreSQL write error, and `configure` converts it to the existing `telemetry_error` boundary (HTTP 502), rather than returning a successful saved state. A failing injected persistence writer regression preserves the prior verified `config_synced=true` truth and confirms no HTTP 200 is emitted; the normal memory path is unchanged when no writer is injected.

## 2026-08-31 Task2 telemetry configure ownership race

### Final Task2 quality repair (current run)

### Final Task2 deadline and virtual-key URL hardening (current run, 2026-08-31)

- [x] RED: Added observable suspended-request polling coverage; it failed before the timeout was applied because the in-flight request survived the 30-second deadline.
- [x] GREEN: Bound the complete polling/request scope with `withTimeout(30_000)` while retaining the 5-second cadence, cancellation behavior, and generation guards.
- [x] RED/GREEN: Added canonical valid plus duplicate raw-path separator cases; both duplicate-slash URLs failed before direct raw-path validation and now reject.
- [x] VERIFY: Targeted Task2 contract class and fresh `:app:testDebugUnitTest --rerun-tasks` passed (446 JVM tests, 0 failures/errors/skips); string-resource parity is 1265 default/1265 Chinese keys with no differences; `git diff --check` passed. No stage/commit/push/reset/stash/clean/deploy.

- [x] REVIEW: Scoped review covered DataReadinessViewModel, TelemetryPairingPresentation, TelemetryPairingContractTest, and this ledger; no trailing whitespace in the untracked test file.

- [ ] RED: Add a server pairing contract covering authoritative `config_synced=true`, known-pending `false`, and unknown `null`, without VIN/provider-body leakage.
- [ ] RED: Add a DataReadinessViewModel behavioral race test with a controllable suspended configure source: A cancellation must retain its lease until its own `finally`; B and a third attempt cannot overlap while A's POST remains in flight; B may proceed after A exits.
- [ ] GREEN: Return only persisted/verified configuration-sync truth from pairing, and remove early lease release from car changes while preserving stale generation and polling guards.
- [ ] VERIFY: Targeted Task2 JVM/Go, full Debug JVM, full Go/vet, resource parity, `git diff --check`, and scoped-diff review. No stage/commit/push/reset/stash/clean/deploy.

- [x] RED: Replace the source-regex cancellation assertion with a controllable JVM behavior test: cancel car A, start car B before A's delayed `finally`, then prove a third configure is rejected and configure calls never overlap.
- [x] GREEN: Make `TelemetryConfigureGate` issue generation-bound ownership tokens; only a matching token may release the gate. Carry the captured car generation through configure completion and polling writes so stale results cannot change the current UI.
- [x] VERIFY: Run the Task2 readiness JVM tests, fresh `:app:testDebugUnitTest`, and repository `git diff --check`; inspect the scoped diff. No stage/commit/push/reset/stash/clean/deploy.

### Task2 plan and boundaries

- Scope: `android/app/src/main/java/com/matelink/ui/screens/readiness/` and its JVM test(s), plus this ledger only.
- Preserve: 30-second maximum / 5-second polling policy and existing user-owned dirty changes.
- Stop condition: any targeted RED failure unrelated to the new race, or any full Debug failure, is reported with its output rather than being hidden.

### Task2 review

- PASS (2026-08-31): RED first failed solely because `TelemetryConfigureGate` lacked a generation-owned lease API. The behavioral JVM regression uses controllable suspended configure calls: A is cancelled, B starts before A's delayed finally, A's finally cannot release B, a third configure is rejected, and peak active calls stays one. Targeted `TelemetryPairingContractTest` passed 6/6 after GREEN; a fresh `:app:testDebugUnitTest --rerun-tasks` passed 443/443 with 0 failures/errors/skips. The gate now releases only an identical lease; configure writes require matching car, generation and lease; polling keeps its existing generation, 5-second interval and 30-second window checks. `git diff --check` passed. No stage/commit/push/reset/stash/clean/deploy.

## 2026-08-30 数据完整性与首次登录体验

- [x] Task A：服务端补齐位置、四轮胎压和数据就绪状态，保留 null/false/0 语义。
- [x] Task B：Android 修复电量提示、电池健康错误、首次登录数据准备弹窗与永久数据状态入口。
- [x] Task C：引入稳定车辆上下文，隔离旧 Room 历史并提供经确认的安全迁移。
  - [x] RED/GREEN：VehicleContext 持久化在进程重载和并发下不丢失映射/计数，提交失败时失败关闭。
  - [x] RED/GREEN：普通自托管解析不改写旧历史迁移来源或 `MODEL_UNKNOWN`；仅显式用户绑定可记录来源/车型。
  - [x] RED/GREEN：充电通知展示、更新和取消始终使用同一 local history namespace ID。
  - [x] RED/GREEN：统一历史逐字段合并，保留 null、零值和部分远端证据。
  - [x] RED/GREEN：TPMS 单次 Worker 运行固定 VehicleContext/historyCarId，并贯穿 claim/release/evaluation。
  - [x] VERIFY：定向测试、fresh Debug JVM 全量、androidTest 编译与限定范围 diff 审核。
- [x] Task D：实现 Fleet Telemetry 独立服务骨架、持久化、幂等行程/充电状态机和配对状态接口。
  - [x] D1 RED/GREEN：固定官方字段集合锁定 configure/allowlist 的名称、大小写与受支持预警/充电字段。
  - [x] D2 RED/GREEN：QoS1 重投与重启后，相同规范化值不推进 latest、不重复完成 session；Tesla `Time` 不作为 epoch。
  - [x] D3 RED/GREEN：本地与 ECS Compose 将同一 CA/certificate 目录只读挂入 API，默认禁用 telemetry 的配置可解析。
  - [x] D4 RED/GREEN：捕获 OAuth ID-token 校验失败日志，证明不含回调 issuer、未验证 token issuer、token、VIN 或坐标等原始值。
  - [x] VERIFY：定向/完整 Go test 与 vet、Compose config、`git diff --check` 和敏感信息扫描。
- [x] Task D blockers：Android 历史兼容、当前充电、无消息停车收尾、MQTT 单 worker、Compose 证书边界与 HTTP 优雅退出。
  - [x] RED/GREEN：完成会话使用稳定的 Int-safe `public_id`，并保留 Android nullable/list JSON 形状。
  - [x] RED/GREEN：`charges/current` 返回 telemetry 未闭合会话或状态源中的活动充电。
  - [x] RED/GREEN：持久化停车候选，后台 finalizer 在截止后恰好收尾（修复 `telemetryMemoryStore.ingest` 透传 `config.StopDebounce`，此前硬编码 20s 默认值导致到期判定晚 10s）。
  - [x] RED/GREEN：MQTT 回调只入有界队列，单 worker 有时限、背压和可观测健康状态（补测试 fixture 的 `TopicBase`，此前为空导致消息解析失败、worker 不落库）。
  - [x] RED/GREEN：API 仅挂 CA 单文件；官方容器独占 cert/key；渲染配置仅在临时内存卷并以 0600 保存。
  - [x] RED/GREEN：带超时的 `http.Server` 与 SIGTERM/SIGINT 优雅关闭。
  - [x] VERIFY：定向/全量 Go test、vet、fresh PostgreSQL、Compose config、diff 与敏感信息扫描。
- [x] Task E：Debug/Release、Go、Compose、Release 配置与差异复核；不提交、不推送、不部署。
  - [x] Go：`go test ./...` + `go vet ./...` 全绿；fresh `postgres:16-alpine`（127.0.0.1:55433）集成测试 QoS1 重投/重启幂等通过，容器即用即删。
  - [x] Compose：`docker compose config` 校验 docker-compose.yml 与 docker-compose.pilot.ecs.yml（± .env.ecs.example）均通过。
  - [x] Release 配置护栏：android/app/build.gradle.kts 新增 guard，缺 `JOURVOLT_API_BASE_URL` 或 `JOURVOLT_AUTH_HOST≠auth.teslalink.joviluma.com` 时 Release 构建 fail-fast；缺失 / 正确 / 错误 host 三场景实测通过。`build-pilot-apk.ps1` 恒定显式传参，不受影响。
  - [x] Android 门禁：`:app:testDebugUnitTest` + `:app:testReleaseUnitTest` + `:app:lintRelease` 在非沙箱模式后台执行，BUILD SUCCESSFUL（21m26s，70 actionable tasks）；lintRelease 无 error，HTML 报告已生成。

### 边界

- 保留当前 main、登录修复和所有用户未提交服务端改动。
- 不修改 iOS；不 reset、stash、commit、push 或部署。
- 已完成的 TPMS 趋势、自定义预警和行程通知只做回归验证，不重复实现。

## Task C Review

- PASS：同步 SharedPreferences 提交与并发/重建 AndroidTest；普通解析不再绑定旧归档，新增显式确认入口。
- PASS：通知、TPMS 和统一历史均固定 local history namespace；Room v19 保存可验证的 API nullable evidence。
- PASS：定向 JVM、fresh Debug JVM（434/434）及 Debug androidTest 编译通过；未执行 Git 写操作或部署。

## Task D Review

- [x] Task 1 final MQTT quality (2026-08-31): RED/GREEN regression coverage now locks PostgreSQL mappings with latest/event persistence in one transaction, classifies an unmapped zero-accept result as retryable/unacknowledged rather than `durable_duplicate`, blocks a deterministic concurrent mapping delete, prevents stale OnConnect readiness restoration after connection loss, and makes `service.started` atomic. Verified with Task1 targeted tests, fresh local PostgreSQL integration, full Go test/vet, both Compose configs, and diff check; no Android/iOS or Git/deployment action.

- [x] Task 1 control-topic ACK race (2026-08-31): make zero-row status updates and disappeared mappings retryable/unacknowledged; preserve control status semantics; run RED/GREEN plus Go/Compose/diff verification.

- [x] Task 1 MQTT repair (2026-08-31): replace readiness source assertion with behavior/state coverage; repair durable recovery/ACK gate and lifecycle regressions; verify Go, Compose, and diff checks.

- PASS (2026-08-31 repaired): `TestTask1*` 21/21 passes. The readiness checks now exercise explicit connection/subscription/store-schema/persistence states; durable persistence is recorded before the separately guarded ACK attempt. Lifecycle coverage establishes a real connected-client gate before asserting no ACK after connection loss or shutdown, confirms handler options, cancelled startup, nonblocking queue pressure, ordered worker ACK, and reconnect only becoming healthy after a newly durable message. Final Go test/vet, both Compose configs, and `git diff --check` passed. No Android/iOS, Git staging/commit/push/reset/stash/clean, or deployment actions occurred.

- PASS (2026-08-31 control-topic ACK race): RED proved `updateStatusForVIN` lacked an outcome classification; GREEN makes the status update authoritative. In memory, a disappeared mapping is checked under the update lock. In PostgreSQL, `INSERT ... SELECT` must affect at least one row; zero affected rows classify as `unknown_mapping`. Unknown mapping, database failure, and cancellation remain retryable/unacknowledged; valid control updates remain durable. `TestTask1ControlTopicZeroRowStatusUpdateIsRetryableAndUnacknowledged`, `TestTask1*`, fresh `go test ./... -count=1`, `go vet ./...`, both Compose configs, and `git diff --check` passed. No Android/iOS, Git staging/commit/push/reset/stash/clean, or deployment actions occurred.

- PASS (2026-08-31 fresh, Task 1 MQTT repair): RED/GREEN 覆盖 Paho manual ACK/persistent session、durable ACK、retryable persistence/backpressure no-ACK、permanent-invalid counter+ACK、有序单 worker、reconnect health recovery 与 10 秒上限关停后留待重投；`TestTask1*` 10/10 通过，完整 `go test ./... -count=1` 为 120 passed、8 skipped、0 failed，`go vet ./...` 与 `git diff --check` 均退出 0。`docker-compose.yml config -q` 退出 0；`docker-compose.pilot.ecs.yml config -q` 在仅当前进程的非敏感占位必填变量下退出 0（原环境未设置部署变量）。未设置 `JOURVOLT_TEST_DATABASE_URL`，因此现有可选真实 PostgreSQL durability test 本轮跳过；未启动或停止容器。

- PASS (2026-08-30 fresh): `deploy/jourvolt-dev-mock/README.md` requires production `TESLA_FLEET_TELEMETRY_IMAGE=tesla/fleet-telemetry@sha256:<64_lowercase_hex_characters>` and prohibits mutable production tags; `tesla/fleet-telemetry:v0.9.4` is documented only for local development.
- PASS (2026-08-30 fresh): `TestTelemetryPostgresQoS1RedeliverySurvivesRestartWithoutAdvancingOrCompletingTwice` passed with `-count=1` against temporary healthy `postgres:16-alpine` container `jourvolt-taskd-pg-20260830-3f3c2fe2d93a` on `127.0.0.1:55433`; `docker rm -f` exit code was 0 and the container was absent afterward.
- PASS (2026-08-30 fresh): `go test ./...` and `go vet ./...` in `deploy/jourvolt-dev-mock` both exited 0; the README production-image rule check passed.
- PASS (2026-08-30 fresh): repository-wide `git diff --check` exited 0; only existing CRLF conversion warnings were emitted.
- PASS (2026-08-31 fresh): Task D blockers 全部 7 项 RED/GREEN 转绿（停车候选 finalizer 与 MQTT 有界队列单 worker 两处 RED 已由组长修复：前者因 `telemetryMemoryStore.ingest` 硬编码 `defaultDriveStopDebounce` 未透传 `config.StopDebounce`，后者因测试 fixture 缺 `TopicBase` 导致 MQTT 消息解析失败）；`go test ./...`、`go vet ./...` 与两份 Compose `config` 复核均通过。

# 2026-08-27 MateLink 实时状态、充电参数与原创车型图

## TPMS 趋势与行程通知（Jovi 已授权最小 Android 改动）

- [x] Task 1：Room v17 TPMS 本地样本、每车阈值配置和纯趋势/原因分析。
- [x] Task 2：TPMS Worker 样本写入、阈值状态转换和本地通知。
- [x] Task 3：Dashboard 胎压入口、7/30 日四线趋势、证据总结和阈值设置。
- [x] Task 4：同步后新完成行程的首次同步抑制与一次性通知。
- [x] Task 5：全量门禁、Release 边界和独立 Debug 通知设备验证。

## Debug 状态验证（Jovi 已授权独立测试包）

- [x] RED：Debug 状态 fixture 合约测试先以预期断言失败。
- [x] GREEN：新增 Debug-only 状态验证页，复用正式展示组件。
- [x] VERIFY：构建、签名/包名检查，并在 `com.matelink.test.mock` 完成五种状态与 360/412、中英、明暗、100/200% 视觉矩阵。

- [x] Adapter 接入 Paho MQTT v1.5.1、默认/命名空间主题、120 秒来源分类和 PostgreSQL 降级。
- [x] Android 状态模型保留零值/false/小数，Dashboard 按驾驶/开口/胎压状态增量显示。
- [x] 当前充电页增加充电口、相数、 voltage/current、请求电流和计划时间的有观测面板。
- [x] Canvas 绘制 Model 3/Y/S/X 无 Logo 原创轮廓，未知车型保留通用轮廓；未接入官网图片或用户照片。
- [x] Go/Android 全量单测、Go vet、Compose 配置、Go 1.24 镜像、MQTT fresh/retained/reconnect smoke、Release 静态标记扫描。
- [x] 实体手机同签名覆盖安装：`6e4fa92f` 已通过启动检查，`adb install -r` 成功并保留原包数据。
- [x] 实体手机基础 UI 回归：Dashboard 的 Model Y 标题与原创车型图、充电历史及四项底栏均已实际打开验证。
- [ ] 实体手机状态化 UI 回归：当前仅有历史快照，尚未观测驾驶、开口、TPMS 告警或进行中的充电状态。
- [ ] 专用 AVD UI 回归：新建 Android 35 AVD 已启动 Android，但未注册 ADB，属于宿主 Emulator↔ADB 通道阻塞。

## Review

- PASS：TPMS Task 1 完成 Luna/high implementer → 规格 reviewer → 质量 reviewer 闭环；独立 `com.matelink.test.mock` AndroidTest 在 GM1910 运行 `15/15`、`0 failures / 0 errors / 0 skipped`。验证 v16→v17 旧行程/手动充电成本保留、TPMS 复合键/缺失/零/范围/清理、每车阈值 profile 与多车隔离；正式 `com.matelink` 仍保留。
- PASS：TPMS Task 2 完成 Luna/high 实现与规格/质量审核闭环；独立 Debug AndroidTest 在 GM1910 运行 `22/22`、`0 failures / 0 errors / 0 skipped`。验证 Tesla/custom UUID claim、lease takeover/stale token、失败重试、partial-warning 不误清除、profile reset、非有限值与 Worker→repository 集成；正式 `com.matelink` 仍保留并恢复前台。
- PASS：TPMS Task 3 完成 Luna/high 实现与规格/质量审核闭环；`TpmsTrendPresentationTest` 运行 `10/10`、`0 failures / 0 errors / 0 skipped`。验证 7/30 日查询、空值断线、不伪造零值、失败重试、窗口/车辆竞态保护、因素与建议绑定，以及中英文非诊断文案；正式 `com.matelink` 未受影响。
- PASS：TPMS Task 4 完成直接实现后的 Luna/high 规格/质量审核闭环；JVM 定向测试 `15/15`、`0 failures / 0 errors / 0 skipped`，覆盖完整多页后才处理、首次同步静默、通知失败不推进水位、成功逐条推进、地址回退和中英文格式。GM1910 独立 Debug AndroidTest `1/1`、`0 failures / 0 errors / 0 skipped`，验证通知 Intent extras 解析到既有 `DriveDetail`；正式 `com.matelink` 保留，测试包由框架自动移除。
- PASS：TPMS Task 5 全量门禁通过：Debug/Release JVM 各 `376` 项（Debug `0/0/0`、Release `0/0/8` failures/errors/skips），`assembleDebug`、`assembleRelease`、`lintRelease` 通过；lint 为 `0 errors / 204 warnings`，中英文各 `1203` 字符串且零缺失。Adapter `go test ./... -count=1`、`go vet ./...` 与 `docker compose --env-file .env.example config --quiet` 通过。Release 未签名 APK 为 `com.matelink` 1.4.2，SHA-256 `4DD3626CB0EB344077466642FB88C2E9E29F48DBB75A649CDD9852343E66CEE1`，未命中测试包/状态验证/回环应用标记。
- PASS：GM1910 独立 Debug AndroidTest：通知 Intent 深链 `1/1`，实体系统通知（自定义 TPMS 阈值、完成行程）`2/2`，均 `0 failures / 0 errors / 0 skipped`；修复自定义 TPMS 通道在 Worker 尚未运行时不创建而被 Android 丢弃的问题，并使权限、应用通知开关或通道被禁用时 release claim/保留行程水位以便后续重试。测试结束后 Debug 与测试包自动卸载；正式 `com.matelink` 保持 1.4.2，`firstInstallTime=2026-07-26 21:35:02`、`lastUpdateTime=2026-08-27 22:08:21` 未变。
- PASS：最新 Release 候选已与正式包证书 SHA-256 `9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774` 一致，使用 `adb install -r -d` 成功覆盖；正式包数据保留，`firstInstallTime=2026-07-26 21:35:02` 未变，`lastUpdateTime=2026-08-29 02:00:14` 更新。启动后 `MainActivity` 正常前台，Dashboard 实际显示 Model Y/仪表盘/行程/充电，日志无 MateLink Fatal/ANR。
- PASS：最新正式候选在 GM1910 实际从 Dashboard 胎压卡进入 `胎压趋势` 页，显示 `近 7 天`、`近 30 天`、无历史不可用状态和证据不足总结；当前正式手机仍只有历史快照，真实驾驶/充电/告警数据不作伪造。

- PASS：Adapter `go test ./... -count=1`、`go vet ./...`；Docker image `golang:1.24-alpine` 构建成功。
- PASS：隔离 MQTT smoke 验证默认/命名空间主题、fresh `live_mqtt`、启动前 retained `mqtt_latest`、负数/零/小数、无效值不覆盖、断线重连和日志脱敏。
- PASS：Android Debug/Release 各 `314` 个 JVM 用例通过；`assembleDebug`、`assembleRelease`、`lintRelease` 通过；lint `0` Error、`200` Warning、`8` Information，`MissingTranslation=0`，无 baseline。
- PASS：本轮强制重跑状态化 JVM 用例：Debug/Release 各 `16` 项（车型映射、驾驶/开口/TPMS、充电参数、JSON 精度）均为 `0 failures / 0 errors / 0 skipped`；Adapter `go test ./... -count=1` 通过。
- PASS：Release `com.matelink` 未签名候选哈希 `8A0971771261429080E86982837650B936765D110AA4942813F11A50BDE43759`；未命中测试包、Mock、回环、官网图库或 Logo 标记。
- PASS：实体机 `sys.boot_completed=1`；原包与候选均为 `com.matelink` 1.4.2，签名 SHA-256 一致；同签名 `adb install -r` 返回 `Success`，仅更新 `lastUpdateTime`。
- PASS：实体机已解锁；Dashboard 显示本地化 Model Y 标题、无 Logo Canvas 车型图、最近快照时间，且仪表盘/行程/充电/更多四项导航均实际切换后返回仪表盘。
- PARTIAL：实体机手动刷新后仍为历史快照；驾驶、开口、TPMS 告警及进行中的充电参数均没有可观测实体数据，不能把隐藏状态当作该四类 UI 的设备通过证据。
- PASS：独立 Debug `com.matelink.test.mock` 已在实体机启动状态验证页：驾驶回收、开口+TPMS、单相 AC、DC 和全缺失字段均完成视觉与 UI 结构核对；正式 `com.matelink` 已恢复前台，未清除其数据。
- PASS：Debug/Release JVM 各 `315` 项，分别为 `0/0/0` 与 `0/0/1`（失败/错误/跳过）；`assembleDebug`、`assembleRelease`、`lintRelease` 通过，lint `0` Error、`200` Warning、`MissingTranslation=0`。Debug APK 包名为 `com.matelink.test.mock`，Release 不含状态验证 Activity 标记。
- PASS：Luna/high implementer → 规格 reviewer → 质量 reviewer 完成修复闭环，最终 reviewer 为 `Ready: Yes`，无 Critical/Important/Minor。
- PASS：最终强制门禁：Debug/Release JVM 各 `322` 项，分别为 `0 failures / 0 errors / 0 skipped` 与 `0 / 0 / 8`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过，lint `0` Error、`204` Warning、`MissingTranslation=0`；Adapter test/vet 与 Compose config 通过。
- PASS：独立 Debug 完成 360×800 中文浅色100%、360×800 英文深色200%、412×915 中文深色200%、412×915 英文浅色100% 的覆盖矩阵；驾驶、开口+TPMS、AC、DC、缺失字段均有实体截图/UI 结构证据。
- PASS：Debug English preference 在 force-stop/cold-start 后保持；最终 APK SHA-256 `03B734999D70411249D690FD9D5B8BE5B5D71E1C13F742D5AB9FCBB42F6AED15`，Release 未命中 Debug 状态标记。
- PASS：手机已恢复物理 `1440×3120`、`600 dpi`，系统字体仍为 `1.0`、系统浅色/zh-CN 未改，正式 `com.matelink` 已恢复前台；独立测试包保留安装。
- BLOCKED：新建 Pixel 5 API 35 AVD 在 17.9 秒完成 Android boot，但未监听 `5560/5561`、ADB 未注册；WHPX 与系统镜像检查通过，已停止诊断 AVD，未修改旧 AVD。
- NOT_PERFORMED：未执行卸载、清数据或 instrumentation；未执行专用 AVD UI 回归；未 stage/commit/push。
- Boundary：当前工作树保留既有混合修改；本轮没有启动用户数据 Compose、远程服务器、真实 Tesla OAuth/Fleet 或生产配置。

# 2026-08-23 方案 B：车型、地点、充电驻车与待机能耗（已获 Jovi 授权）

- [ ] RED/GREEN：CNY 缺省迁移、充电列表直改总价、地点识别文案与状态
- [ ] RED/GREEN：高德中文地点解析与无 Nominatim 地址回退边界
- [ ] RED/GREEN：充电驻车关联、普通驻车兼容与一跳充电详情
- [ ] RED/GREEN：待机 7/30/365 天窗口、覆盖阈值、空调归因和不足原因
- [ ] RED/GREEN：原创车型图、自动外观色和用户相册覆盖/重置
- [ ] VERIFY：Go/Android 全量门禁、原创素材审查、模拟器回归与受控真机覆盖

## Review

- 规格：`docs/superpowers/specs/2026-08-23-matelink-vehicle-location-energy-design.md`
- 实施计划：`tasks/plans/2026-08-23-matelink-vehicle-location-energy-implementation.md`
- 边界：不得打包 Tesla 官网图片；不得伪造待机原因/能耗；不提交 Git 或改变服务器配置。

# 2026-08-23 珍珠电驱轻量视觉优化（已获 Jovi 授权）

- [x] RED/GREEN：新增底部导航语义图标与动效令牌契约测试，并确认先失败后通过
- [x] GREEN：实现底部导航图标映射与选中轻动效，保持原路由和标签
- [x] GREEN：优化 Telemetry/More/Stats 面板材质、边缘和数字字体，不改布局与数据
- [x] GREEN：增加首页刷新一次性动效和首次有效数据的短促过渡
- [x] VERIFY：运行 Android Debug/Release 测试、构建、lint、差异检查和本地预览

## Review

- 规格：`docs/superpowers/specs/2026-08-23-pearl-drive-motion-design.md`
- 实施计划：`tasks/plans/2026-08-23-pearl-drive-motion-implementation.md`
- 授权边界：Android UI/测试/文档/本地预览；Jovi 后续明确授权同签名 Release 覆盖安装；不改登录/服务器、不 stage/commit/push。
- 结果：`PHONE_SMOKE_PASS`；Debug/Release 合计 570 个 JVM 用例通过，lint 195 项/0 Error/`MissingTranslation=0`/无 baseline；签名 `com.matelink` 已用 `adb install -r` 覆盖，未执行 instrumentation。

> 当前源状态（2026-08-21）：正式 App 为 `com.matelink`；`com.jourvolt.app` 和早期 Consumer/Expert 分叉记录均为历史，不代表当前交付目标。当前本地证据为 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`；以下按时间顺序保留历史执行记录，最新条目在文件末尾。

## Plan

- [x] Review the current Android build, navigation, locale and network boundaries.
- [x] Add consumer/expert flavors and the consumer fail-closed login entry.
- [x] Enforce consumer HTTPS and remove Android `de`/`ja`/`fr` locale exposure.
- [x] Fix the existing release R8 dependency gate without adding a lint baseline.
- [x] Run consumer/expert builds, consumer release lint and APK manifest checks.
- [x] Record the external P0/P1 blockers and the next implementation gate.

## Review

- Consumer release APK is `com.jourvolt.app` / `JourVolt`; expert debug remains `com.matelink`.
- `lintConsumerRelease` passes with `MissingTranslation=0`; 247 non-error warnings remain.
- Consumer and expert JVM unit-test tasks pass.
- Real Tesla OAuth, cloud API, server purchase, domain registration, Tesla review, filing and runtime vehicle proof remain blocked until their external gates are satisfied.
- No Git staging, commit or push was performed.

# JourVolt P0/P1 continuation - 2026-08-09

## Plan

- [x] Re-read the self-hosted adapter boundary and confirm it is not a public OAuth backend.
- [x] Write the exact external P0 action and handoff requirements without collecting secrets.
- [x] Write the P1 cloud API, session rotation, location consent and deletion contract.
- [ ] Wait for Jovi's non-sensitive P0 gate results before implementing real cloud authentication.

## Review

- `deploy/teslamate-home-docker/adapter` remains expert/self-hosted only: database-backed TeslaMate reads, legacy proxy and local API token.
- `docs/JOURVOLT-P0-EXTERNAL-GATE.md` is the required external action checklist; it does not authorize purchases, filings or Tesla account operations.
- `docs/JOURVOLT-CLOUD-API-CONTRACT.md` is a prepared, non-production P1 contract; no real OAuth, Tesla secret, refresh token or vehicle data was added.
- Consumer boundary recheck passes: old coordinate services fail closed, expert-only manifest entries are removed, and the Chinese Consumer label resolves to `JourVolt`.
- Final Consumer lint evidence is `0 errors, 241 warnings`; `MissingTranslation=0`; no lint baseline.
- Next gate: Jovi returns only the non-sensitive P0 summary requested in the checklist. Then implement the smallest simulated OAuth/session package before any real Tesla credential is used.

# JourVolt local Docker debug - 2026-08-09

## Plan

- [x] Add a mock-only local HTTP service with fixed fixture vehicle data.
- [x] Add Consumer Debug-only mock login and visible mock session/vehicle result.
- [x] Keep Consumer Release official-login button disabled and HTTPS-only.
- [x] Document emulator and physical-device local Docker commands.
- [x] Run Docker Compose and Android Debug runtime smoke when Docker/emulator is available.

## Review

- `deploy/jourvolt-dev-mock` is intentionally not a Tesla integration and does not accept credentials.
- `JOURVOLT_MOCK_LOGIN` is enabled only for Debug BuildTypes; Release defaults to false.
- This local path is for UI/session development only and cannot be reported as Tesla OAuth or real vehicle proof.
- Docker Compose health/login/401 checks pass on `127.0.0.1:18090`.
- Emulator `emulator-5554` local Mock UI is only `PARTIAL`: after clicking Mock login, the response remains visible, but the app stays on the login screen and has no vehicle-home navigation.
- Physical device `6e4fa92f` shows the same partial state with the same APK; this proves Mock response rendering only, not a completed Consumer entry flow.
- Root cause: `ConsumerLoginScreen` renders `Success` text locally; `NavGraph` provides no success navigation, and the Mock API is not connected to a Consumer vehicle-home/session shell.

# JourVolt full-app real-login continuation - 2026-08-10

## Plan

- [x] Re-read the parent `AGENTS.md`, current Obsidian project records and mixed worktree state.
- [x] Lock the delivery target as official Tesla OAuth -> original Dashboard/full navigation -> real vehicle status refresh.
- [x] Sync the confirmed plan and corrected Mock evidence boundary to Obsidian and project docs.
- [x] Implement the local JourVolt API compatibility service with Mock/Fleet provider boundaries.
- [x] Connect Consumer session/auth state to the original Dashboard and bottom navigation.
- [x] Add compatibility contract, auth/session, multi-user isolation and truthful-empty-history checks.
- [x] Run Docker, JVM, Android build/lint and emulator full-flow verification before any phone install.
- [x] Record real-Tesla external gates; do not claim real OAuth or vehicle proof before domain/app approval and HTTPS callback exist.

## Execution boundary

- Preserve all existing staged and unstaged changes; no reset, cleanup, staging, commit or push.
- Consumer Debug Mock is development evidence only. The acceptance path is `login -> Dashboard -> vehicle refresh -> original pages -> logout`.
- Supported Android languages remain Chinese and English; `MissingTranslation` must remain 0 without a baseline.

## Review

- Started 2026-08-10 from detached HEAD `1d861b8` with a mixed worktree.
- Parent `E:\project\tesla_master\AGENTS.md` requires code-review-graph preflight; the graph returned no indexed nodes, so targeted file inspection was used as the documented fallback.
- Go API/PostgreSQL local Mock flow is `PASS`; the API enforces 401, refresh rotation, logout invalidation, user-scoped vehicle access and truthful empty history.
- Emulator `emulator-5554` is `LOCAL MOCK PASS`: Mock login reaches the original Dashboard and bottom navigation, Drives/Charges empty states, More/Settings and logout; process restart restores the JourVolt session.
- `:app:testConsumerDebugUnitTest`, `:app:testExpertDebugUnitTest`, both Debug assemblies, `assembleConsumerRelease` and `lintConsumerRelease` pass. Lint reports `0 errors`, `252 warnings`, `MissingTranslation=0`, with no baseline.
- Real Tesla OAuth remains externally blocked by the P0 gate; no phone install or real vehicle claim was made from this Mock evidence.

# Git Commit Review - 2026-07-09

## Plan

- [x] Inspect current git status and repository ignore rules.
- [x] Classify untracked files as source/docs versus local environment or build output.
- [x] Add minimal ignore rules for files that should not be committed.
- [x] Stage only necessary repository files and create one commit.
- [x] Verify staged commit contents before commit.

## Findings

- `android/.gradle/` is Gradle cache/local state and should not be committed.
- `android/.idea/` is local IDE metadata for this checkout and should not be committed.
- `android/app/build/` is Android build output and should not be committed.
- `android/local.properties` is machine-local Android SDK configuration and should not be committed.

## Review

- `git status --short --ignored` shows only `.gitignore` and `tasks/` as untracked commit candidates.
- `git check-ignore -v` confirms `android/.gradle/`, `android/.idea/`, `android/app/build/`, and `android/local.properties` are ignored by the new root `.gitignore`.
- `git diff --cached --stat` shows only `.gitignore` and `tasks/todo.md` staged.

# App Completion Handoff - 2026-07-09

## Plan

- [x] Read docx handling guidance and app_mimo memory boundaries.
- [x] Inventory source documents from `docs/git_ref`, `docs/PLAN`, `docs/PRD`, the architecture doc, and the Word implementation plan.
- [x] Inspect current Android, iOS, shared, and web implementation status.
- [x] Assess feature completion, data configuration flow, and user guidance gaps.
- [x] Write phase handoff documentation under `app_mimo/docs`.
- [x] Verify generated docs and record remaining proof limits.

## Review

- Created `docs/PHASE-HANDOFF-2026-07-09.md`.
- Captured reviewed document list, completed/unfinished scope, platform status, data configuration model, and recommended user onboarding flow.
- Kept `E:/project/tesla_master/docs/git_ref/` read-only.
- Native Android/iOS build proof remains gated by local toolchain availability; this pass is source/document inspection plus documentation output.

# app_mimo Data Setup Implementation - 2026-07-09

## Plan

- [x] Create implementation branch.
- [x] Save implementation plan under `docs/superpowers/plans`.
- [x] Inspect Android/Web/iOS setup code and existing tests.
- [x] Add failing/targeted tests where feasible before behavior changes.
- [x] Implement Android stronger connection testing and first-run guidance.
- [x] Implement Web persisted real-data configuration and `/api/v1` paths.
- [x] Implement iOS source-level multi-instance management.
- [x] Update docs/handoff for completed and remaining work.
- [x] Run available verification and record proof limits.

## Review

- Branch: `codex/app-mimo-data-setup`.
- `E:/project/tesla_master/docs/git_ref/` remains read-only.
- Android unit test target was added for connection URL/outcome rules, but Gradle could not run because `JAVA_HOME`/`java` is unavailable.
- Web `npm run build` and `npm run lint` pass after restoring local `node_modules` with `npm install`.
- iOS multi-instance changes are source-level only on Windows; Xcode build/simulator proof remains required.

# Self-hosted TeslaMate Guidance - 2026-07-09

## Plan

- [x] Inspect current README, Settings, Onboarding, About, and map/key copy.
- [x] Update README with real-data deployment, server, security, TeslaMateApi, and AMap key guidance.
- [x] Update Android Settings/About strings for self-hosted TeslaMateApi-compatible API and AMap key ownership.
- [x] Update iOS Onboarding/Settings/About copy for API root address, server requirement, and AMap key ownership.
- [x] Update Web Onboarding/Settings/About copy for API root address, server requirement, and AMap key ownership.
- [x] Run available verification and record proof limits.
- [x] Report candidate files for Jovi approval before any Git staging or commit.

## Review

- Git staging/commit is explicitly blocked until Jovi approves the candidate file list.
- Implementation was delegated into bounded docs, Android, iOS, and Web slices, then integrated in the parent thread.
- Verification passed: `npm run lint`, `npm run build`, XML resource parsing, Web message JSON parsing, sensitive token/key scan, and `git diff --check`.
- Verification blocked: Android `testDebugUnitTest` cannot start because `JAVA_HOME` is unset and no `java` command is on PATH.
- iOS verification remains source-level on Windows; Xcode build/simulator proof requires Mac/Xcode.

# app_mimo Remaining Implementation Plan Update - 2026-07-09

## Plan

- [x] Read the existing data setup plan and current task record.
- [x] Rewrite the plan under `docs/superpowers/plans/2026-07-09-app-mimo-data-setup.md`.
- [x] Separate completed self-hosted/AMap guidance from unfinished verification and implementation work.
- [x] List remaining P0/P1/P2 tasks, proof boundaries, non-commit artifacts, and recommended commit scope.

## Review

- Updated the plan as a remaining-work handoff, not a stale original task list.
- Android native verification remains blocked until JDK/Gradle can run.
- Web real-data smoke still needs a reachable TeslaMateApi or MateLink-compatible API root address.
- iOS and Widget proof remains gated by Mac/Xcode and device/simulator validation.

# Android Resource Warning Cleanup - 2026-07-09

## Plan

- [x] Inspect the `assembleDebug` warning output and locate the missing default string names.
- [x] Restore default `values/strings.xml` entries for legacy Settings resource names still present in localized files.
- [x] Rebuild or re-run the same Android task to confirm the warnings are gone.

## Review

- The warnings come from legacy Settings string keys that still exist in `values-zh/strings.xml` but no longer have default entries in `values/strings.xml`.
- The fix is compatibility-focused: add default aliases in `values/strings.xml` that point at the current canonical `settings_*` strings where possible.
- Re-running `android\\.\\gradlew.bat :app:assembleDebug` with `JAVA_HOME='D:\\Program Files\\Android\\Android Studio\\jbr'` succeeded and the previous `removing resource ... without required default value` warnings did not reappear.

# User Deployment Guide - 2026-07-09

## Plan

- [x] Inspect current docs wording and existing self-hosted TeslaMate guidance.
- [x] Add a plain-language deployment and configuration guide under `app_mimo/docs`.
- [x] Cover server setup, exact app fields, correct input examples, and common misconfiguration cases.

## Review

- Added `docs/USER-DEPLOYMENT-SETUP-GUIDE.md` as a user-facing guide rather than an engineering handoff.
- The guide explains the full route from self-hosted TeslaMate to MateLink, includes Mermaid diagrams, and highlights the most common mistakes such as using Grafana or TeslaMate Web UI URLs instead of the API root.
- High-level AMap/Gaode guidance is included as optional map enhancement, not a blocker for showing core vehicle data.

# Home Docker TeslaMate Template - 2026-07-09

## Plan

- [x] Check Docker CLI, Docker Compose, active context, and running containers.
- [x] Start Docker Desktop from the installed desktop app when the service was not reachable from the terminal.
- [x] Confirm no TeslaMate-related compose file is present in `app_mimo`.
- [x] Add a home-host Docker Compose template for TeslaMate + TeslaMateApi-compatible access.
- [x] Ensure local `.env` secrets under deploy templates are ignored by Git.

## Review

- Docker Desktop was installed but the `desktop-linux` engine was initially unreachable because `com.docker.service` was stopped.
- Starting Docker Desktop made `docker ps` work; existing running containers are unrelated to TeslaMate.
- Added `deploy/teslamate-home-docker/docker-compose.yml`, `.env.example`, and `README.md`.
- The template uses placeholders only. No real Tesla token, API token, database password, AMap/Gaode key, or personal domain was written.
- `docker compose --env-file .env.example config` passes for the template without starting containers.
- Local API root candidates after startup will be `http://192.168.31.195:8080` on Wi-Fi or `http://192.168.0.104:8080` on Ethernet, depending on the phone's network.

# Home Docker TeslaMate Runtime Setup - 2026-07-09

## Plan

- [x] Create local ignored `.env` with generated secrets.
- [x] Pull TeslaMate, PostgreSQL, Mosquitto, Grafana, and TeslaMateApi images.
- [x] Start the home Docker stack.
- [x] Verify TeslaMate Web UI and TeslaMateApi respond locally.
- [x] Open the TeslaMate sign-in page for user-owned Tesla authorization.

## Review

- `docker compose up -d` started all five services.
- TeslaMate Web UI responds on `http://localhost:4000` and currently shows `Sign in · TeslaMate`.
- TeslaMateApi responds on `http://localhost:8080/api/v1/cars` with `{"data":{"cars":null}}`, which means the API is alive but no Tesla account/vehicle data has been authorized into TeslaMate yet.
- This TeslaMateApi image accepted `/api/v1/cars` without a token in local smoke testing, so public exposure must rely on VPN/Tailscale/HTTPS reverse proxy controls rather than the sample API token alone.

# Android Real Vehicle First-Sync Fallback - 2026-07-09

## Plan

- [x] Probe the live home Docker API endpoints after Tesla authorization.
- [x] Confirm `/api/ping`, `/api/readyz`, and `/api/v1/cars` pass for MateLink connection testing.
- [x] Add a Dashboard fallback for the first-sync window where cars are available but `/api/v1/cars/{id}/status` is not ready.
- [x] Rebuild the Android debug APK for user testing.

## Review

- The home Docker API now returns one real vehicle from `/api/v1/cars`.
- `/api/v1/cars/1/status` currently returns an API-level "no info" response, which can happen before a useful live status snapshot is available.
- Dashboard now shows the detected vehicle and a clear "live status pending" message instead of only `No data` when status is temporarily unavailable.
- `:app:assembleDebug` succeeds with Android Studio JBR. The remaining warning is an unrelated deprecated `CompareArrows` icon in `RangeScreen.kt`.

# Android Settings Entry Flow and WorkManager Fix - 2026-07-09

## Plan

- [x] Inspect the user-reported Settings screenshots and runtime error.
- [x] Fix WorkManager initialization after the manifest disabled the default initializer.
- [x] Add an explicit "Enter App" action after a successful connection test.
- [x] Surface TeslaMateApi status endpoint error text instead of a generic missing-status message.
- [x] Rebuild Android debug APK for retest.

## Review

- Save already intended to navigate to Dashboard, but `triggerImmediateSync()` called WorkManager first and failed because `MateLinkApplication` did not implement `Configuration.Provider`.
- TeslaMateApi currently returns cars and drive/charge data, but `/api/v1/cars/1/status` still returns `{"error":"no info on this car ID"}`; Dashboard can only show live status after that endpoint provides a status snapshot.
- `:app:assembleDebug` now succeeds after the WorkManager fix. Retest APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
- Settings now shows an explicit "Enter App" action after a successful connection test; it saves the server config and navigates to Dashboard.

# Android Dashboard Partial Real Data Fallback - 2026-07-09

## Plan

- [x] Trace Dashboard data flow to confirm which endpoint drives the visible card.
- [x] Add a real-data fallback that uses `/api/v1/cars` vehicle details when `/api/v1/cars/{id}/status` is not ready.
- [x] Keep the backend error visible as a diagnostic detail without making the app look disconnected.
- [x] Rebuild Android debug APK for user retest.

## Review

- Dashboard previously showed a sparse pending card whenever `/status` returned no snapshot, even though `/cars` already contained real vehicle identity and TeslaMate stats.
- The pending state now shows the real vehicle name, connected badge, model/trim, exterior/wheel info, total drives, total charges, data source, and the exact `/status` diagnostic.
- Live battery/location/lock/climate/TPMS cards still require `/api/v1/cars/{id}/status` to return a status snapshot.
- `:app:assembleDebug` succeeds. Retest APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

# Android Dashboard Copy and Trip Timeline Fix - 2026-07-09

## Plan

- [x] Trace Dashboard partial-data state and Trips/Drives history data flow.
- [x] Fix Dashboard partial-data copy so it is localized and does not imply live online status.
- [x] Add parked segments between drive records to the trip history timeline.
- [x] Verify Android debug build and local API assumptions.

## Review

- Dashboard partial-data state now uses localized Chinese resources and a "syncing" badge instead of implying full live online status.
- Drives history now builds a mixed timeline: drive items plus derived parked items between adjacent drive records.
- The local TeslaMateApi sample has 5 drive records and 4 derivable parked gaps, giving 9 timeline items before UI filters.
- `:app:assembleDebug` succeeds. Retest APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

# Vehicle Data and Analytics Delivery - 2026-07-11

## Plan

- [x] Add implementation design and execution plan documents.
- [ ] Add failing Android tests and test dependencies for sync, analytics, timeline, cost, and battery partial states.
- [x] Fix summary/detail synchronization, pagination termination, aggregate persistence, and progress reporting.
- [ ] Implement the Dockerized Go MateLink Adapter with PostgreSQL, MQTT, geocode, cost, and sentry contracts.
- [ ] Integrate adapter capability detection and source-aware Android data repositories.
- [ ] Rebuild Dashboard, drive/parked history, charge costs, Chinese addresses, and analytics screens.
- [ ] Verify unit tests, adapter tests, Docker integration, Android build, lint, and device smoke flow.
- [ ] Report candidate files without staging or committing.

## Review - 2026-07-11

- Added pagination guards for TeslaMateApi repeat-page behavior and persisted drive/charge detail aggregates before sync progress is advanced.
- Fixed manual WorkManager initialization after the manifest disabled WorkManagerInitializer; Dashboard refresh now also schedules `DataSyncWorker`.
- Updated the Dashboard and Battery partial-data paths so missing live MQTT snapshots do not become fake zero values or raw server-English UI.
- Applied the 500 m route threshold to drive display, summaries, and charts; short repositioning is represented by the surrounding parked interval and parked timestamps render in 24-hour form.
- Verified `:app:testDebugUnitTest` and `:app:assembleDebug`; installed the APK on device `6e4fa92f` and confirmed no WorkManager initialization/fatal startup log.
- Still pending: adapter-backed parked power/temperature, charge-cost overrides and Chinese geocoding, sentry events/media, capability-aware Android API client, and final visual calibration.

# P1.2 Device Room Recovery and AMap Runtime Test - 2026-07-26

## Plan

- [x] Audit the v13 identity mismatch and define a non-destructive v13-to-v14 migration.
- [x] Add the v14 schema, migration coverage, and patch version metadata.
- [x] Remove the remaining English AMap placeholder copy from user-visible screens.
- [x] Run targeted and full Android build verification.
- [x] Install the debug APK over the existing phone data and verify startup without clearing data.
- [ ] Verify the live AMap preview after a Key is saved again; current app state is unconfigured and the Key was not read or changed.

## Review

- Root cause of the crash was the v13 Room identity/default mismatch. The app now moves to v14 through a non-destructive migration that preserves drive summaries and detail aggregates; the migration instrumentation suite passed 2/2.
- Debug version is `versionCode 2` / `versionName 1.0.1`. JVM tests passed 116/116; the AMap setup content suite passed 4/4; `assembleDebug` and `assembleDebugAndroidTest` passed.
- The APK was installed with `adb install -r`; no uninstall or data clear was used. The current MateLink process remained foreground with no Room-integrity or fatal-exception marker.
- Setup now distinguishes `尚未保存 Key。` from `Key 已加密保存，内容已隐藏。`, and disables save for an empty input. It never shows the stored value.
- Live AMap verification is intentionally incomplete: the installed app reports `尚未配置高德地图`, so no saved Key is available to the SDK. The Key was neither read nor modified.

# P1.3 Navigation Recovery and AMap Key Verification Flow - 2026-07-26

## Plan

- [x] Remove the test-only runtime navigation handling that can trap the app on setup/preview pages.
- [x] Restore normal Dashboard, Drives, Charges, and More navigation after an app restart.
- [x] Replace direct Key save with a transient SDK test page: visible pending, pass, and failure states; persist only after pass.
- [x] When a Key is verified, hide the input and provide an explicit change-Key action.
- [x] Add focused tests, rebuild, preserve-data install, and exercise the repaired flows without reading a real Key.

## Review

- The app now starts at Dashboard and keeps all primary navigation destinations available.
- AMap candidates remain pending until a real Search SDK request succeeds; a synthetic invalid Key failed on the isolated emulator and was not stored.
- Existing verified Keys remain hidden and can only be replaced through the explicit change-and-test flow.

# P1.4 Configuration Preservation, Foreground Sync Crash, and Key Test - 2026-07-26

## Plan

- [x] Capture the real-device crash signature without reading user configuration.
- [x] Declare WorkManager's `dataSync` foreground service type in the merged manifest.
- [x] Keep the app's initial destination on Dashboard; configuration remains reachable from More.
- [x] Replace direct AMap Key storage with an isolated transient verification activity and explicit pass/fail UI states.
- [x] Exercise a fresh isolated emulator: initial synthetic connection save, Dashboard return, and Key verification failure path.
- [x] Build the final APK, then use only a preserve-data install and read-only crash check on Jovi's phone.

## Review

- The physical-phone foreground crash was `SystemForegroundService` rejecting the requested `dataSync` type because the app manifest had none. The fixed manifest declares `dataSync`.
- A fresh emulator saved synthetic connection settings and returned directly to Dashboard; an invalid AMap Key produced an explicit failure and was not stored.
- No stored Docker address, token, AMap Key, or database row was read or logged.

# P1.5 Room v14 Identity Recovery - 2026-07-26

## Plan

- [x] Capture the device Room identity-hash failure without reading configuration values.
- [x] Identify that a database already at user version 14 cannot re-run the v13-to-v14 migration.
- [x] Add and isolate-test a no-schema-change v14-to-v15 recovery migration.
- [x] Build v1.0.4 and perform a preserve-data installation plus read-only device crash check.

## Review

- The v15 migration performs no table or row mutation. It advances the database version so Room verifies the existing schema and replaces only its stale master-table identity metadata.
- The isolated emulator passed all three migration tests. JVM tests passed 117/117, and both required Debug APK assemblies passed.
- `adb install -r` updated the phone from v1.0.3 to v1.0.4 while preserving the original first-install time and the databases, DataStore, and shared-preferences directories.
- After normal launch and a Drives-history navigation tap, the same application process remained alive for 25 seconds with no new fatal or Room-integrity log marker.

# P1.6 Top-Level UI System and Navigation Polish - 2026-07-27

## Design direction

- UI/UX Pro Max: data-dense telemetry dashboard, restrained motion, semantic cyan primary, 4/8dp spacing, 48dp Android touch targets, explicit selected/disabled states, light/dark parity.
- Anthropic frontend-design: commit to one deliberate “precision vehicle telemetry” direction; avoid generic gradient/card decoration and keep typography, hierarchy, and interaction choices intentional.
- Scope is UI only. Do not change API, persistence, sync, AMap verification, Room, or data calculation behavior.

## Plan

- [x] Unify global semantic colors, shapes, and typography; reserve the mono font for numeric telemetry instead of ordinary headings and labels.
- [x] Give the bottom navigation a clear cyan selected state while retaining four labeled top-level destinations.
- [x] Make Drives and Charges read as top-level destinations: consistent surface top bars and no redundant back arrow.
- [x] Recompose More into compact, touch-safe action groups; keep Settings and system entries easy to reach.
- [x] Add the missing Chinese Reports section resource and remove forced uppercase section labels.
- [x] Preserve accessible text labels for clickable cards and provide content descriptions for the four bottom-navigation icons.
- [x] Validate JVM tests, Debug APKs, light/dark mode, Chinese layout, narrow-phone rendering, and the existing portrait-only orientation policy.
- [x] Install with `adb install -r` on the phone, preserve configuration metadata, and run normal navigation smoke checks without instrumentation.

## Review

- Version advanced to `1.1.0` (`versionCode 6`) on branch `codex/app-mimo-ui-optimization`.
- UI/UX Pro Max and Anthropic frontend-design guided a restrained precision-telemetry system: semantic cyan selection/action color, 4/8dp rhythm, compact cards, explicit hierarchy, and mono type reserved for numeric telemetry.
- Chinese emulator review covered Dashboard, Drives, Charges, all scrollable More sections, dark mode, 130% font scale, and an approximately 343dp-wide viewport. The application is manifest-locked to portrait, so a forced rotation correctly retained the portrait layout.
- `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed; 117 JVM tests completed with 0 failures, 0 errors, and 0 skipped.
- `adb install -r` updated the ARM64 phone from `1.0.4` (`versionCode 5`) to `1.1.0` (`versionCode 6`). `firstInstallTime` and the metadata for `databases`, `files/datastore`, and `shared_prefs` remained unchanged.
- A normal phone launch followed by all four bottom-navigation taps kept the same process alive; fatal and Room-integrity markers were both 0. No instrumentation test ran, and no saved configuration value was read.

# P1.7 Vehicle Telemetry Panel Redesign - 2026-07-29

## Design direction

- Translate the supplied references into a native Android “night-drive telemetry” system rather than reproducing another app pixel-for-pixel.
- Keep one cyan interaction accent, green for healthy/efficient states, orange for time or battery-use warnings, and neutral graphite surfaces.
- Every major panel gets a semantic icon or code-drawn graphic. Numeric telemetry uses `MetricMono`; ordinary Chinese labels remain in the readable UI font.
- Preserve all existing API, Room, sync, map, calculation, filtering, and navigation behavior. Never invent a metric to fill the reference layout.
- Remotion is not used because these are static Compose screens. Reusable Material icons and deterministic Compose `Canvas` graphics are the correct production assets.
- Android’s experimental Compose Styles API is not enabled because it requires compileSdk 37 and alpha Compose dependencies; the stable theme/component architecture remains in place.

## Plan

- [x] Add stable shared telemetry panel, metric, gauge, route, and vehicle-hero components.
- [x] Recompose Dashboard around a vehicle hero, honest battery telemetry, status actions, location, temperature, tire pressure, and real charging data.
- [x] Recompose Drives into icon-led route cards with duration, distance, efficiency, and battery change; keep parked timeline items visually distinct.
- [x] Recompose Charges into icon-led session cards with duration, energy, cost, type, and battery change.
- [x] Strengthen Drive Detail and Charge Detail headers without changing their charts, maps, or calculations.
- [x] Add a prominent battery-health gauge and tighten capacity, degradation, and range hierarchy.
- [x] Keep More as a dense, icon-led launch grid aligned with the same panel system.
- [x] Validate Chinese/light/dark/narrow layouts on the emulator, run JVM/build gates, then preserve-data install and normal navigation checks on the phone.

## Review

- Version advanced to `1.2.0` (`versionCode 7`) on branch `codex/app-mimo-ui-optimization`; HEAD remained `c559d9e` and no file was staged, committed, or pushed.
- Added reusable native Compose telemetry panels, metric strips, route indicators, a health gauge, and a deterministic Canvas vehicle graphic. Remotion and generated raster assets were intentionally unnecessary.
- Dashboard, Drives, Charges, their detail headers, Battery Health, More, theme, typography, and bottom navigation now share one Chinese-first night-drive telemetry hierarchy.
- Removed the fabricated seven-day battery trend. Nullable vehicle states and charging values now render as unavailable instead of false or zero; unprocessed charge type is explicitly unknown.
- Emulator review covered Chinese dark mode, light-mode contrast, and a 900x2000 narrow portrait viewport. The partial-data Dashboard remained readable and scrollable without horizontal clipping.
- `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed.
- `adb install -r` updated the ARM64 phone from `1.1.0` (`versionCode 6`) to `1.2.0` (`versionCode 7`). `firstInstallTime` and the metadata for `databases`, `files/datastore`, and `shared_prefs` remained unchanged.
- A normal phone launch and all four bottom-navigation taps retained PID `13037`; fatal and Room/SQLite error markers were both 0. No instrumentation test ran and no saved configuration value was read.

# P1.8 Crash Elimination and Navigation Audit - 2026-07-29

## Plan

- [x] Reproduce the reported Battery Capacity crash on the emulator and capture only the app exception class, stack frames, and triggering route.
- [x] Build an emulator-only navigation matrix for the four top-level tabs and all 17 non-mutating More action cards; record each route's process/crash result without inspecting saved credentials.
- [x] Trace the failing code path, rank hypotheses, and add the narrowest regression test at the real failure seam before applying a production fix.
- [x] Apply the minimal crash fix without changing user configuration, TeslaMate data, Room rows, map keys, or unrelated UI behavior.
- [x] Re-run the exact emulator reproduction, the complete emulator navigation matrix, JVM tests, and Debug APK builds.
- [x] Perform a preserve-data `adb install -r` update on the real phone, then run normal navigation smoke checks and read-only fatal/Room-log verification. Never use instrumentation on the user phone.

## Review

- Reproduced two independent render-time crashes: Battery Health passed an integer to a resource with a literal `%`, and Software Updates passed an integer to a `%1$.1f` resource.
- Added focused red/green JVM format-contract tests for both resource contracts. The original tests failed first; the final full suite reports 121 tests with 0 failures, errors, or skipped tests.
- Emulator navigation verification covered all four bottom tabs, all 17 More action cards, Settings return, and AMap configuration return. No data-mutating, export, external-app, save, delete, or instrumentation action ran.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest` passed. The v1.2.1 (`versionCode 8`) Debug APK was installed with `adb install -r` on the real phone; `firstInstallTime` and data-directory metadata stayed unchanged. Normal launch and cold-start Battery route verification retained a live process with no app crash marker.

# P1.9 Dense Drive and Charge History UI - 2026-07-30

## Design direction

- Use the published `mobile-android-design` guidance for adaptive Jetpack Compose layout and the `UI/UX Pro Max` guidance for compact telemetry hierarchy and chart grouping.
- Increase information density without shrinking touch targets: compact internal padding and typography, use three- or four-column metric strips when the width supports them, and keep narrow screens readable.
- Keep source data honest. Shorter labels and compact timestamps must not invent duration, cost, address, energy, battery, power, or temperature values.
- Preserve API, Room, sync, stored connection settings, and AMap Key. Persist manual charge prices in an isolated per-car/per-session preference without touching connection credentials.

## Plan

- [x] Audit drive/charge list and detail data contracts, formatting, charts, and cost behavior.
- [x] Compact Drives history route, timestamp, filters, summary, and metric rows; use Chinese-first route text and rename user-visible efficiency to energy consumption.
- [x] Compact Drive Detail route, month/day timestamps, duration, metrics, and group each telemetry value with its corresponding curve.
- [x] Compact Charges history filters, summary, location row, session route, and metric rows without misleading per-day labels.
- [x] Compact Charge Detail time/duration and group energy, battery, power, temperature, and cost; add a clear per-session manual price/cost edit flow.
- [x] Add focused formatting/state tests, then run all JVM tests and both Debug APK build gates.
- [x] Validate the affected navigation paths on an emulator, install with `adb install -r` on the phone, and verify configuration metadata and crash logs without instrumentation.

## Review

- Version advanced to `1.3.0` (`versionCode 9`) on branch `codex/app-mimo-ui-optimization`; HEAD remained `c559d9e` and no file was staged, committed, or pushed.
- Drives and Charges now use compact four-column summaries, tighter panels, Chinese-first addresses, and consistent “能耗” wording. Detail timestamps omit the year, real duration is visible, and each available metric group is placed directly beside its corresponding curve.
- Charge filters use compact 7/30/90/all controls. Manual non-negative unit prices are stored by car/session and one shared resolver keeps list, summary, chart, and detail costs consistent; the dialog also supports returning to the recorded price.
- No distance curve was invented because the current drive samples do not expose a trustworthy cumulative-distance series.
- `git diff --check` and both string-resource XML parses passed. `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed with 130 JVM tests, 0 failures, 0 errors, and 0 skipped.
- Chinese dark-mode emulator review covered the compact Dashboard, Drives, and Charges layouts without a crash.
- `adb install -r` updated the ARM64 phone from `1.2.1` (`versionCode 8`) to `1.3.0` (`versionCode 9`). `firstInstallTime` and the metadata for `databases`, `files/datastore`, and `shared_prefs` remained unchanged.
- Normal phone checks opened Dashboard, Drives, the first Drive Detail, Charges, the first Charge Detail, the price dialog (cancelled without saving), More, and Battery Health. PID `26852` remained alive and final fatal, ANR, Room, and SQLite markers were 0. No instrumentation test ran and no saved configuration value was read.

# P1.10 Embedded AMap Navigation Repair - 2026-08-01

## Root-cause hypothesis

- The saved Key is verified and the standalone preview loads, but Dashboard, Drive Detail, Charge Detail, Where Was I, and Regions still call legacy placeholder composables that discard coordinates and route points.
- Dashboard location navigation is additionally miswired to Drives, and its map card becomes a no-op when `stateSince` is absent.

## Plan

- [x] Reproduce the Dashboard and Drive Detail placeholders on the physical phone without reading the saved Key.
- [x] Verify the settings UI reports `地图已加载` and trace the coordinate/click data flow.
- [x] Add a failing contract test proving legacy wrappers do not delegate to the native AMap renderer.
- [x] Replace point, route, and multi-marker placeholders with one lifecycle-safe native AMap renderer and configuration gate.
- [x] Route the Dashboard location entry to the actual AMap preview and keep embedded maps interactive.
- [x] Run targeted tests, the full JVM suite, Debug APK builds, and static privacy checks.
- [x] Advance the patch version, install with `adb install -r`, and verify Dashboard, Drive Detail, Charge Detail, and configuration preservation on the phone.

## Review

- Root cause: `AmapPointView`, `AmapRouteView`, and `AmapComposeView` were legacy placeholders that ignored every coordinate and route argument. Dashboard location also navigated to Drives, its map card could become a `stateSince`-dependent no-op, and detail map cards redirected to external providers.
- The phone settings UI reported `地图已加载`, proving the saved Key and privacy/verification state were already valid; no Key value was read or logged.
- Added one native, lifecycle-safe AMap renderer for point, route/polyline, and multi-marker views, with a shared setup-state gate and redacted failure logging. Dashboard location now opens the real in-app AMap preview.
- The new contract test first failed 2/2 against the placeholder implementation, then passed after the fix. The complete suite reports 132 JVM tests with 0 failures, 0 errors, and 0 skipped; `assembleDebug` and `assembleDebugAndroidTest` passed.
- Version advanced from `1.3.0` (`versionCode 9`) to `1.3.1` (`versionCode 10`). `adb install -r` preserved `firstInstallTime` and the metadata for `databases`, `files/datastore`, and `shared_prefs`.
- Physical-device checks created native AMap views on Dashboard, Drive Detail, and Charge Detail; Dashboard Location opened `高德地图预览` with `地图已加载`. Legacy placeholder, map-state error, SDK auth, fatal, ANR, Room, and SQLite markers were all 0. No instrumentation, uninstall, data clear, saved-Key read, commit, or push occurred.
## P1.11 Runtime Reliability Regression Audit

- [x] Add failing contracts for notification formatting and AMap verification back handling.
- [x] Align notification resource placeholders with their callers.
- [x] Replace the deprecated verification Activity back override.
- [x] Run targeted tests, full JVM/build verification, and focused lint checks.
- [x] Install the versioned APK with `adb install -r` and repeat safe device smoke tests.

### Review

- Red contracts reproduced all three defects before the fix and passed afterward.
- Notification titles now preserve vehicle/tire context; literal percentage copy is explicitly non-formatting.
- AMap Key verification uses `OnBackPressedDispatcher` and still returns a canceled result without exposing the candidate Key.
- `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed; 135 JVM tests, 0 failures/errors.
- Focused Lint findings for `MissingSuperCall`, `StringFormatInvalid`, and `StringFormatCount`: 0.
- Installed version `1.3.2` (`versionCode 11`) with `adb install -r`; first-install timestamp remained unchanged.
- Physical-device smoke test kept Dashboard, Drives, Charges, and More alive/foreground; critical crash/auth/database log markers: 0.
- No instrumentation, app-data clear, uninstall, commit, or push was performed.

## P1.12 Complete-history analytics and honest report delivery

- [x] Define the unified all-history analysis source, separate long-distance grouping, manual per-session total charge amount, and empty-data semantics.
- [x] Add focused tests for history deduplication/windows, manual totals, percentile direction, standby attribution, drive routing, and annual current/previous years.
- [x] Switch Efficiency, Cost, Range, and Vampire view models to the unified history source; expose compact window/percentile/currency/no-data UI.
- [x] Keep More → 行程历史 on all individual drives and remove the 3D vehicle/current-charge report entries.
- [x] Run full JVM/lint/build gates, bump the app version, install with `adb install -r`, and complete normal physical-device navigation smoke tests.

### Review

- Design and implementation plan committed before code changes; no source data, `.env`, API Key, or vehicle identifier was read or logged.
- 147 JVM tests passed with 0 failures/errors/skips; `assembleDebug` and `assembleDebugAndroidTest` passed.
- Lint remains blocked by the repository's existing 842 missing-translation errors and 254 warnings; no new touched-file format or crash error was found in the report.
- Version advanced to `1.4.0` (`versionCode 12`); `adb install -r` preserved `firstInstallTime` and the configuration directory metadata.
- Normal device checks covered More analysis/report routes, annual 2025/2026 switching, Battery capacity, Dashboard AMap preview, Drives, and Charges. PID remained alive and critical crash/ANR/Room/SQLite/format/AMap markers were 0.

# P1.13 Approved history-analysis completion - 2026-08-01

## Plan

- [x] Add one reusable all/90-day/summer/winter/custom date selector and pass inclusive custom boundaries to Efficiency, Cost, Range, and Standby.
- [x] Make Efficiency show a trend, personal percentile range with bucket counts, per-drive expandable positions, and verified-public-sample-unavailable state; use average speed for speed buckets.
- [x] Make Cost distinguish valid cost/energy coverage from missing values, keep manual total precedence, and show manual-total count in the summary.
- [x] Make Range summarize seasonal and average-speed prediction accuracy; keep missing values as no-data text.
- [x] Make Standby include every computable parked interval, expose kWh/day, location, confidence, and unknown-cause wording without inferring attribution.
- [x] Make Annual report metrics honest when only one data family exists; always show effective cost and standby coverage states.
- [x] Add percentile tie semantics and custom-window boundary tests; bump to version 1.4.1 (versionCode 13).
- [x] Run the full JVM suite and both Debug APK build gates, then preserve-data install and complete physical-device regression across all approved analysis routes.

## Review

- Targeted analytics/charges/reports/domain tests passed after the implementation; compileDebugKotlin passed.
- No verified public same-model aggregate source exists in this repository, so no ranking, sample count, median, or fabricated value is displayed; the UI explicitly reports public-sample insufficiency.
- The final full gate reports 152 JVM tests with 0 failures, errors, or skipped tests; both Debug APK build gates passed after clearing the generated KSP cache.
- Version 1.4.1 (versionCode 13) was installed with `adb install -r -d`; firstInstallTime remained `2026-07-26 21:35:02`, and the process stayed alive through analysis, report, history, and long-distance routes.
- Physical checks covered all four analysis pages, custom date dialog, current/previous annual reports, 行程历史, and 长途旅程. Fatal/app, Room/corruption, format, ANR, and AMap markers were 0. No instrumentation, uninstall, data clear, Key read, commit, or push occurred.

# Repository consolidation and stable publish - 2026-08-09

## Plan

- [x] Publish the verified `app_mimo` stable line to its `main` branch.
- [x] Remove the retired `app_glm` submodule from the parent repository.
- [x] Update the parent `app_mimo` gitlink to the published stable commit.
- [x] Keep local secret files and generated state out of Git.
- [x] Push both repository `main` branches and the stable tag.

## Review

- `app_mimo/main` is `3140b0e`; `testDebugUnitTest`, `assembleDebug`, and `assembleDebugAndroidTest` passed with the Android SDK supplied only to the process environment.
- `v1.4.2` remains the stable release marker and is published to the `app_mimo` remote.
- `tesla_master/main` is `6166b6a`; its commit removes `app_glm` and points `app_mimo` to `3140b0e`.
- `app_glm` was removed from `.gitmodules`, the parent gitlink, the working tree, and local submodule metadata. The separate remote repository was not deleted.
- `android/.kotlin/` and `deploy/*.env` are ignored. No `.env` content, API key, token, or user configuration was read or staged.
- Existing unrelated parent-worktree deletions and the legacy `app_mimo` worktree changes remain uncommitted and preserved.

# P1.14 Branch consolidation - 2026-08-09

## Plan

- [x] Merge `codex/app-mimo-data-setup` into the latest `main` line without discarding unique qualification tests or public-information assets.
- [x] Resolve overlapping UI/resource/build conflicts in favor of the latest `main` implementation and retain the data-setup verification gates.
- [x] Re-run JVM tests, Debug APK builds, AndroidTest APK build, and foreground-service manifest validation.

## Review

- Merge validation passed: `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:verifyDebugForegroundServiceType`.
- `git diff --cached --check` passed and no merge markers remain.
- No `.env` content, API key, token, vehicle identifier, or user configuration was read or staged.

# JourVolt real OAuth/Fleet implementation - 2026-08-11

## Plan

- [x] Re-read AGENTS, current Obsidian plan, repository state and existing Consumer/Mock implementation.
- [x] Verify the current Tesla China authorization-code, scope, domain-key and Fleet vehicle endpoint requirements from official documentation.
- [x] Replace the Go OAuth/Fleet placeholders with configuration-gated official OAuth, encrypted token storage, atomic refresh and user-scoped real vehicle mapping.
- [x] Keep Mock Debug isolated and fail closed when real Tesla configuration is absent or incomplete.
- [x] Open Tesla authorization with Custom Tabs, preserve the original Dashboard/full navigation and make Settings/logout reachable from a populated Dashboard.
- [x] Run Go, PostgreSQL, Docker, Consumer/Expert build/lint and isolated-emulator full-flow verification.
- [x] Sync implementation state, evidence boundary and Jovi's external Pilot actions to repository docs and Obsidian.

## Review

- Go unit tests and vet pass. PostgreSQL integration rejects OAuth state/ticket replay and proves two concurrent refresh requests cause one upstream Tesla token rotation.
- Docker Mock mode passes health, fail-closed OAuth (`503 oauth_not_configured`), session rotation, snapshot, truthful empty history and logout revocation.
- Android Consumer/Expert JVM suites each pass 158 tests; Consumer/Expert Debug and Consumer Release APK builds pass.
- Consumer Release lint passes with `0 errors`, `256 warnings`, `8 information`, `MissingTranslation=0`, and no lint baseline. These are release-gate findings, not runtime bug counts.
- Emulator `emulator-5554` is `LOCAL MOCK PASS`: login -> original Dashboard -> `Development Model 3 / Charging / 76%` -> Drives/Charges empty state -> Settings -> Android calls server logout -> PostgreSQL session becomes revoked -> login page; fatal/ANR markers are 0.
- Real Tesla code is configuration-ready, but `REAL TESLA PILOT` remains `BLOCKED / NOT PERFORMED` until Jovi supplies an approved Tesla application, controlled domain, public HTTPS callback and private local/server configuration.
- No physical-device instrumentation, Git stage, commit or push was performed.

# Jovi 原 MateLink 单一 App 登录改造 - 2026-08-12

## Plan

- [x] 将正式产品边界锁定为原 `com.matelink`；停止构建 `com.jourvolt.app`。
- [x] 将 Debug Mock 隔离为 `com.matelink.test.mock`，不加入正式 APK。
- [x] 保留原 Dashboard、底部导航、本地 Room/DataStore/SecureSettings 和历史数据。
- [x] 增加 `TESLA_CLOUD` / `SELF_HOSTED` 运行时模式，并把旧服务器/Token/Instance 自动迁移到自托管。
- [x] 将官方 OAuth、JourVolt session、Custom Tab、一次性 App Link ticket 和原 Dashboard 路由接回单一 App。
- [x] 将 Tesla 账号状态、重新授权、退出/注销和折叠高级自托管区域整合进原 Settings。
- [x] 将当前包名、Debug 测试边界、Mock 证据和发布门禁同步到项目文档与 Obsidian。
- [x] 补跑全量 Android JVM、Go/vet、Compose 配置、Debug/Release/lint 与独立模拟器回归。
- [ ] 外部 Tesla 应用、域名、HTTPS callback、App Link 和真实车辆 Pilot（外部门禁后）。

## Review

- 当前正式 APK 路径是 `android/app/build/outputs/apk/release/app-release-unsigned.apk`，badging 确认为 `com.matelink`；未签名、未安装。
- `:app:compileDebugKotlin`、连接模式迁移/OAuth/App Link/URL 安全目标测试（31 个目标）、`:app:assembleRelease` 和 `:app:lintRelease` 已通过。
- Release lint：`MissingTranslation=0`、`BaselineFiles=0`、8 Information、250 Warning；这是多语言覆盖/发布门禁统计，不是 883 个运行时 Bug。另有一个 Mock 登录后的 IO 线程导航崩溃已复现并修复，真实车辆数据正确性仍未验证。
- Release APK 二进制未检出 `com.jourvolt.app`、Mock 登录、回环地址或 `JOURVOLT_MOCK_LOGIN` 字符串。
- Go `go test ./...`、`go vet ./...`、`docker compose config --quiet` 和本机 health/fail-closed OAuth 检查：PASS。
- 独立模拟器 `emulator-5554`：`LOCAL MOCK PASS`；首屏、Mock 登录、原 Dashboard 车辆状态、Drives/Charges 空状态、More、Settings 账号控制、退出回登录页均通过；修复前 IO 线程导航崩溃，修复后清空日志无新崩溃。
- 本批未安装、卸载或覆盖实体手机 APK，未运行 connected instrumentation，未执行 Git stage/commit/push。
- 旧 Consumer/Expert 与旧模拟器记录保留为历史证据；当前证据只使用 `com.matelink` Release 和 `com.matelink.test.mock` 独立模拟器回归。

## 待办/证据边界

- 真实 Tesla OAuth 和真实车辆状态仍为 `CONFIG-READY / REAL TESLA PILOT BLOCKED`，不能用本地 Mock 代替。
- 真机覆盖安装、错误 `com.jourvolt.app` 卸载、正式签名和 Git 操作需要分别取得后续授权。
# MateLink P0 界面、导航与车辆分析优化 - 2026-08-13

## Plan

- [x] 完成现状截图、父级规则、任务记录、Obsidian 项目记录与混合工作树核对。
- [x] 新增仅 Debug 可见的两套六页面视觉样稿，供 Jovi 选择。
- [x] 建立统一数据状态组件和指标证据领域类型。
- [x] 修复空历史成功响应导致效率页持续加载，以及分析页的空数据误导与主题硬编码。
- [x] 在独立模拟器完成样稿和三类空状态回归；Release 构建不得包含设计审查入口。
- [x] 写入本轮 Obsidian 记录和本文件 Review，保持 Git 工作树不暂存。

## Boundary

- 正式包保持 `com.matelink`；设计审查 Activity 仅存在于 `src/debug`，不进入 Release。
- 不修改既有 OAuth、自托管迁移、Room 数据或实体手机；不执行 reset、stage、commit、push。
- 本轮 P0 只落地样稿、状态基础和已复现空数据问题。四模块导航和领域算法属于后续 P1/P3。

## Review

- `DesignReviewActivity` 仅位于 `android/app/src/debug`，明确标注 DEBUG ONLY；正式 `com.matelink` Release 不包含此 Activity。
- 两套样稿均已在 `emulator-5554` 交互检查：`PRECISION_TELEMETRY`（青蓝高信息密度）与 `PURE_MINIMAL`（中性灰、大数字），浅色、深色、采集中、不可用状态均可显示且无文字重叠。
- `MetricState` 与 `MetricStatusPanel` 已作为 P0 共享基础；效率页的空成功响应不再持续转圈。续航、待机耗电、成本和里程页在无记录时不再将未知历史显示为零值或金额。
- `:app:assembleDebug --rerun-tasks`、`testDebugUnitTest`（162 tests / 0 failures / 0 errors）、`assembleRelease` 与 `lintRelease` 均通过；lint 为 0 error、251 warning，未出现 `MissingTranslation`。Debug 包仅覆盖安装到 `emulator-5554`；未运行 instrumentation，未操作实体手机。Release 合并清单和 APK 均不含设计审查 Activity。`git diff --check` 通过（仅有现有 CRLF 提示）。
- P0 的视觉选择尚待 Jovi 确认；在选择前不批量替换生产页面主题。固定 75kWh、成本/货币默认值和领域算法重构属于 P3，未在 P0 伪装为已解决。

# 原 MateLink 分析页局部优化 - 2026-08-14

## Plan

- [x] 保留原 MateLink 页面、卡片、导航和信息密度，只在统计页增加综合摘要与证据说明。
- [x] 修正加权效率、额定续航消耗偏差、待机容量缺失和空数据默认零值问题。
- [x] 增加核心分析算法与建议规则的 JVM 测试。
- [x] 完成 Debug/Release、lint、差异检查、本地 Docker Mock 和专用模拟器回归。
- [x] 将建议引擎接入本地历史数据源并完成有历史数据的页面截图验收；真实 Fleet/Telemetry 数据验证仍属于外部门禁。
- [ ] 完成服务器采购、公网 HTTPS、Tesla 真实 OAuth 和 Pilot 验收。

## Review

- PASS：173 个 JVM 测试通过；Debug/Release 构建通过；`MissingTranslation=0`、lint 错误为 0；`git diff --check` 通过。
- PASS：本地 Docker Mock 健康检查、Mock 登录、会话访问和车辆快照通过；专用模拟器保留原 Dashboard、More 和 Stats 入口，未出现崩溃或 ANR。
- PARTIAL：Mock 历史接口按契约返回空集合，因此本轮只能验证真实空状态，不能把空状态冒充为“有历史数据的分析页”视觉证明；建议引擎已完成领域规则和测试，尚未接入页面数据源。
- NOT_PERFORMED：实体手机、服务器购买、真实 Tesla 账号/车辆、Git stage/commit/push。

# MateLink 分析建议与历史 Fixture 落地 - 2026-08-15

## Plan

- [x] 从已保存的行程与充电摘要生成按距离加权、带覆盖期的建议证据。
- [x] 将建议引擎接入原 Stats 页面，展示依据、样本量、覆盖量、可信度、动作和月度影响区间。
- [x] 为本机 Debug Mock 增加显式历史 fixture 与详情响应，正式 Fleet/Release 路径不使用该数据。
- [x] 补齐 Kotlin/Go 测试，并在专用模拟器验证原 Dashboard、同步、完整 Stats 和建议卡。
- [x] 运行 Debug/Release/lint/差异门禁并同步 Obsidian 当前状态。

## Boundary

- 保持原 MateLink UI、`com.matelink`、TESLA_CLOUD/SELF_HOSTED 和真实 OAuth fail-closed 行为。
- Mock 历史只属于 `com.matelink.test.mock` 与本机开发服务，证据标记为 `LOCAL MOCK HISTORY PASS`，不代表真实车辆。
- 不操作实体手机，不购买服务器，不读取或写入任何 Tesla 密钥，不执行 Git stage/commit/push。

## Review

- PASS：建议证据已从同步后的行程/充电摘要生成，并在原 Stats 页面显示阈值、样本、覆盖、可信度、月度影响、动作和方法。
- PASS：Debug Mock 默认提供 18 条行程、5 条充电及详情；`mock-user` 隔离、分页、能量和详情测试通过；关闭历史开关仍可回归空状态。
- PASS：Android 177/177 JVM tests；Go `go test ./...`、`go vet ./...`、Compose 配置、Debug/Release、lint 和 staged/unstaged `git diff --check` 通过。
- PASS：专用模拟器重新安装最新 `com.matelink.test.mock` 后，Mock 登录进入原 Dashboard，刷新触发 DataSyncWorker 并成功完成同步；Stats 读取 420 km、215 Wh/km、23 条来源记录。MateLink 进程无崩溃/ANR；其他包的历史 instrumentation 日志不计入本项目证据。
- PASS：Release lint 为 0 error、255 warning、8 information，`MissingTranslation=0`，无 lint baseline；这些是发布门禁统计，不是运行时 Bug 数。
- NOT_PERFORMED：真实 Tesla OAuth/Fleet、真实车辆、域名/HTTPS、服务器采购、公网部署、实体手机、Git stage/commit/push。

# JourVolt 真实 Pilot 前置工具 - 2026-08-21

## Plan

- [x] 核对真实 OAuth/Fleet 配置、当前 Compose 默认值和正式 App Link 目标。
- [x] 增加不输出密钥的 Windows PowerShell 预检，拒绝 Mock、历史 fixture、示例域名、私网地址和错误密钥长度。
- [x] 增加关闭 Mock 的 Pilot Compose 模板与正式 `com.matelink` App Link 示例。
- [x] 同步仓库文档、任务台账和 Obsidian 接力记录。
- [x] 用 PowerShell 5.1 解析检查、占位 `.env` 失败检查、合成环境 Compose 解析和 Go/vet 验证。

## Boundary

- 预检不读取或输出真实 secret 内容，不调用 Tesla，不访问公网 App Link，除非显式传入 `-VerifyAppLink`。
- Pilot Compose 默认关闭 `JOURVOLT_ENABLE_MOCK` 和 `JOURVOLT_ENABLE_MOCK_HISTORY`，API 默认只绑定回环地址。
- 不购买服务器、不部署公网、不操作实体手机、不执行 Git stage/commit/push。

## Review

- PASS：`preflight.ps1` 在 Windows PowerShell 5.1 解析错误为 0；使用 `.env.example` 时正确报告占位/缺失配置并以失败退出。
- PASS：`docker-compose.pilot.example.yml` 在合成配置变量下 `docker compose config --quiet` 通过。
- PASS：Go `go test ./...`、`go vet ./...` 通过。
- NOT_PERFORMED：真实 Tesla 审核、域名、HTTPS、App Link 公网验证和真实车辆 Pilot。

# JourVolt OAuth 回调安全收口 - 2026-08-21

## Plan

- [x] 将正式 App Link host 写入 Android BuildConfig，并继续用于 Manifest placeholder。
- [x] 在一次性 ticket 交换前校验 HTTPS、绑定 host 和精确 `/oauth/callback` 路径。
- [x] 增加错误 scheme、错误 host、错误 path 和有效 callback 的 JVM 测试。
- [x] 重新运行 Android Debug/Release/lint、Go/vet、Compose 和差异门禁。

## Review

- PASS：Android 179/179 JVM tests；Debug、Release、lint 通过。
- PASS：lint 0 error、255 warning、8 information，`MissingTranslation=0`，无 baseline。
- PASS：本机 Docker health=`ok`、`mock_only`、`mock_history=true`；专用模拟器包 `com.matelink.test.mock` 已安装。
- NOT_PERFORMED：真实域名、正式签名指纹、公网 App Link、Tesla OAuth 和真实车辆。

# JourVolt Pilot 可用性与 HTTPS 入口补齐 - 2026-08-21

## Plan

- [x] 增加 PostgreSQL 感知的 /readyz，与仅报告进程状态的 /healthz 分离。
- [x] 为开发和 Pilot Compose 增加 API healthcheck，避免 API 容器在数据库未就绪时被误判为可用。
- [x] 增加可选 Caddy edge profile，提供公网 HTTPS、App Link 文件托管和到回环 API 的反向代理模板。
- [x] 增加就绪探针单元测试、Go/vet 和两种 Compose profile 解析验证。

## Review

- PASS：Go go test ./...、go vet ./...。
- PASS：普通 Pilot Compose 与 --profile edge 合成配置解析。
- PASS：本机 API 重建后 /healthz 与 /readyz 均返回 status=ok；API/PostgreSQL 容器运行中且 API healthcheck 生效。
- Boundary：Caddy edge 未公网启动；真实域名、正式签名指纹、Tesla 审核和真实车辆仍属于外部 Pilot 门禁。

# Dashboard 来源证据一致性与最终本机门禁 - 2026-08-21

## Plan

- [x] 修复 fleet_api、teslamate_api、live_mqtt、database_latest、mock_fixture 的来源徽章映射。
- [x] 为来源映射增加 JVM 单元测试，避免车辆卡片有状态而顶部错误显示 Unavailable。
- [x] 重新运行 Android 测试、Release、lint、Release 静态标记、Go/vet、Docker 探针和模拟器回归。
- [x] 将最新证据和外部门禁同步到 docs 与 Obsidian。

## Review

- PASS：Android JVM 182/182；Release 包名为 com.matelink；Release 禁止标记为 0。
- PASS：lint 0 errors、255 warnings、8 information，MissingTranslation=0，无 baseline；该统计属于多语言覆盖/发布门禁问题，不是运行时 Bug。
- PASS：本机 API/PostgreSQL healthy，/healthz 与 /readyz 为 ok；模拟器为 LOCAL MOCK PASS，来源徽章显示 Local mock。
- Boundary：没有启动公网 Caddy，没有执行真实 Tesla 登录、真实车辆刷新、实体手机安装或 Git stage/commit/push。

# 中国默认币种与成本显示收口 - 2026-08-21

## Plan

- [x] 新安装默认使用 CNY，同时保留已有显式货币和旧自托管安装的兼容行为。
- [x] 统一 Cost、Tariff Configuration、Annual Report、PDF、Stats 和 Trip/Charge 初始状态的货币来源。
- [x] 通过 JVM、Debug/Release、lint、差异检查和独立模拟器验证。
- [x] 将实际实现边界和验证证据同步到 docs 与 Obsidian。

## Boundary

- 保留原 MateLink UI、`com.matelink`、现有自托管配置和 DataStore 手动费用覆盖。
- 不新增 Room 迁移；旧计划中的 Room-backed 费用覆盖记录为历史路线，不宣称已实现。
- 不操作实体手机，不购买或部署服务器，不读取 Tesla 密钥，不执行 Git stage/commit/push。

## Review

- PASS：184/184 JVM tests，0 failures/errors/skips；Debug、Release、Release lint 和 `git diff --check` 通过。
- PASS：lint 0 errors、255 warnings、8 information，`MissingTranslation=0`，无 lint baseline；这些是多语言覆盖/发布门禁统计，不是运行时 Bug。
- PASS：独立模拟器从原 More 进入原 Settings，显示 `¥ CNY - CNY`，没有 EUR；最近 AndroidRuntime 无 FATAL。
- Boundary：当前证据仍是本机/模拟器门禁，不是 Tesla 官方 OAuth 或真实车辆数据证据。

# 云会话失效自动回登录 - 2026-08-21

## Plan

- [x] 让云 session refresh 失败后的 session 清除能驱动原 App 回到 Tesla 登录页。
- [x] 保证 SELF_HOSTED 模式不因没有 JourVolt session 被误重定向。
- [x] 增加云模式、登录页和自托管边界单测，并运行完整构建门禁。
- [x] 同步 docs 与 Obsidian 接力记录。

## Boundary

- 保持原 `com.matelink`、Dashboard、底部导航和自托管连接；不新增密码输入框。
- 只在 `TESLA_CLOUD` 且当前不在登录页时处理 session 失效；不触碰实体手机，不执行 Git stage/commit/push。
- 单测证明路由决策，真实 Tesla 401/refresh 仍需真实 Pilot 才能证明。

## Review

- PASS：187/187 JVM tests，0 failures/errors/skips；Debug、Release、lint 通过。
- PASS：lint 0 errors，`MissingTranslation=0`，无 lint baseline；最新 Debug 模拟器 Dashboard/车辆/导航可见，最近 AndroidRuntime 无 FATAL。
- Boundary：真实 Tesla OAuth、真实车辆和公网 Pilot 仍未执行。

# Room-backed 手动充电费用 - 2026-08-21

## Plan

- [x] 新增按 `(carId, chargeId)` 隔离的 Room `charge_cost_overrides` 表并升版到 v16。
- [x] 将旧 `SettingsDataStore` 费用 JSON 做一次性、成功写入后清理的升级迁移。
- [x] 让充电详情、充电列表、成本页和年度报告统一使用 Room 费用存储。
- [x] 补齐 15→16 迁移断言并在隔离模拟器执行迁移 instrumentation。
- [x] 重新构建 Debug/Release/lint，并回归独立模拟器原 Dashboard 链路。
- [x] 将当前证据和真实 Tesla/实体手机外部门禁同步到 docs 与 Obsidian。

## Review

- PASS：187/187 JVM tests；`assembleDebug`、`assembleRelease`、`assembleDebugAndroidTest` 和 `lintRelease` 通过。
- PASS：Room 15→16 migration test 在隔离模拟器 4/4 通过；最新 Debug 覆盖安装后本地 Mock 可进入原 Dashboard，车辆状态、`76%` 电量及完整底部导航可见；最近 AndroidRuntime 无 FATAL。
- PASS：`MissingTranslation=0`，未建立 lint baseline。
- Boundary：没有操作实体手机、没有运行实体手机 instrumentation、没有真实 Tesla OAuth/车辆验收、没有服务器采购/公网部署，也没有 stage/commit/push。

# 重新授权入口与完整回归 - 2026-08-21

## Plan

- [x] 在原 Settings 的 Tesla 账号区域提供重新授权入口，不改变原 UI、导航和自托管配置。
- [x] 统一由导航层清除当前 JourVolt session 后进入 Tesla 登录页，避免重复登出和登录页自动回 Dashboard。
- [x] 重新运行 Android JVM、Debug/Release、Release lint 和独立模拟器回归。
- [x] 将真实 Tesla、实体手机和公网 Pilot 的未完成边界同步到 docs 与 Obsidian。

## Review

- PASS：`testDebugUnitTest`、`assembleDebug`、`assembleRelease`、`lintRelease`；187/187 JVM tests，0 failures/errors/skips。
- PASS：独立 `emulator-5554` 实测重新授权进入 Tesla 登录页，再用 Local mock login 返回原 Dashboard；车辆状态、76% 电量、完整底部导航可见。
- PASS：最近 AndroidRuntime 无 FATAL；`MissingTranslation=0`，无 lint baseline。
- Boundary：只覆盖安装独立模拟器 Debug 包；未操作实体手机、未运行 connected instrumentation、未执行真实 Tesla OAuth/车辆验收、未 stage/commit/push。

# JourVolt 注销后 refresh 会话失效 - 2026-08-21

## Plan

- [x] 检查服务端 logout 与 refresh 的会话生命周期。
- [x] 将 logout 从只撤销 access hash 修复为撤销完整 JourVolt session。
- [x] 增加 `TestStoreLogoutRevokesRefreshSession` 数据库集成测试。
- [x] 重建本机 Docker API，并用真实 HTTP 链路验证 access/refresh 均失效。
- [x] 同步云接口契约、实施记录与 Obsidian。

## Review

- PASS：本机 HTTP `mock-login -> logout` 后，原 access 访问返回 401，原 refresh 调用返回 401，`/readyz` 为 ok。
- PASS：`go test ./... -count=1`、`go vet ./...`；使用临时空 PostgreSQL 执行 4 个会话/令牌数据库集成测试并通过。未设置 `JOURVOLT_TEST_DATABASE_URL` 时仍按约定跳过，未伪造为已执行。
- Boundary：只操作本机 Mock Docker 服务；未接触 Tesla 凭据、未公网部署、未操作实体手机、未 stage/commit/push。

# 云模式路由一致性与 Debug HTTPS 例外 - 2026-08-21

## Plan

- [x] 统一 Repository 与 API Factory 的持久化连接模式读取，消除登录切换竞态。
- [x] 保持 Release 云 API HTTPS 门禁，同时允许 Debug 本地 Mock 使用本地 HTTP。
- [x] 让 Debug Mock session refresh 也能使用明确的本地地址例外，拒绝公网 HTTP。
- [x] 清空独立模拟器数据后重新验证 Local Mock -> 原 Dashboard -> 快照轮询。
- [x] 运行 Android、Go、Docker HTTP 和 diff 门禁，并同步 docs/Obsidian。

## Review

- PASS：独立模拟器清空数据后登录进入原 Dashboard，车辆为 `Development Model 3`，状态 `Charging`，电量 `76%`，来源 `Local mock`。
- PASS：Android JVM 191/191；`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- PASS：Release lint `MissingTranslation=0`、无 lint baseline；该指标属于多语言覆盖/发布门禁问题，不描述为运行时 Bug。
- PASS：Go `go test ./... -count=1`、`go vet ./...`；本机 Docker 车辆快照与注销后旧 refresh `401` 验证通过。
- Boundary：真实 Tesla OAuth、域名/公网 HTTPS、真实车辆、实体手机和 Git stage/commit/push 仍未执行。

# 原 MateLink 分析结论层与证据化建议 - 2026-08-21

## Plan

- [x] 在保留原 Stats 页面结构的前提下，扩充顶部综合分析的可解释结论，不改主题和导航。
- [x] 修复首次进入 Statistics 只读 Room、不触发历史同步的逻辑缺口。
- [x] 将派生指标统一标记证据类型，缺失数据保持“不可用/采集中”，不转成零值。
- [x] 让建议卡展示观测周期、基准/比较组和计算依据；继续只生成满足样本门槛的建议。
- [x] 为新增算法和中英文资源补齐 JVM/格式契约测试。
- [x] 运行 Android 全量测试、Debug/Release/lint、独立模拟器原 App 回归，并同步 docs/Obsidian。

## Review

- PASS（本地阶段）：保留原 MateLink Stats 页面和视觉结构，新增 4 个派生结论；首次进入 Statistics 会触发现有历史同步并写入 Room。
- PASS：`testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease lintRelease --no-daemon`，193/193 JVM tests，0 failures/errors/skips；Release lint 0 errors，`MissingTranslation=0`，无 lint baseline。
- PASS：独立 `emulator-5554` 本地 Mock 回归显示 `420 km`、`215 Wh/km`、`23` 条来源记录、Derived conclusions 和带观测天数/置信度的建议；`DataSyncWorker` 完成同步，最近 AndroidRuntime 无 FATAL/ANR。
- PASS：Release badging 为 `com.matelink`；`com.jourvolt.app`、Mock 登录标记、`10.0.2.2` 和 `127.0.0.1` 均未检出；`git diff --check` 退出码 0。
- Boundary：真实 Tesla OAuth、域名/公网 HTTPS callback、服务器采购/部署、实体手机和 Git stage/commit/push 仍未执行；以上本地证据不能升级为 REAL TESLA PILOT PASS。

# 真实 Pilot 配置链路复核 - 2026-08-21

## Review

- PASS：本机 `jourvolt-dev-mock` API 与 PostgreSQL 持续 healthy，`/healthz` 与 `/readyz` 均返回 ok。
- PASS：正式 Android 默认启用云登录，Release 固定使用 HTTPS；Debug Mock 仍由独立包和本地地址隔离。
- PASS：Pilot Compose 在没有私密 `.env` 时拒绝解析，未启动错误的公网/真实配置；这是预期 fail-closed 行为。
- PASS：认证契约已对齐：服务端 `/v1/auth/tesla/callback` 生成一次性 ticket 并回跳 App Link，Android 仅接受配置 host、HTTPS 和 `/oauth/callback` 后交换 session。
- Boundary：仍没有 Tesla client secret、正式域名、公开 callback、assetlinks 正式指纹或真实车辆；不执行真实 Pilot，不把示例域名当作已部署。

# OAuth HTTP 契约集成测试 - 2026-08-21

## Plan

- [x] 用本地 OIDC/JWKS/token 测试服务模拟 Tesla 官方授权端点，不接触真实凭据。
- [x] 验证 state、nonce、ID Token 签名、授权码交换、grant 加密保存、一次性 ticket 和 JourVolt session。
- [x] 验证 ticket 重放被拒绝，并使用隔离 PostgreSQL 执行集成测试。

## Review

- PASS：`go test ./... -count=1`；普通测试通过，OAuth/数据库测试在临时 PostgreSQL 上实际通过。
- PASS：`go vet ./...`。
- Boundary：这是本地官方 OAuth 协议模拟证据，不是 Tesla 服务器、真实账号或真实车辆证据；真实 Pilot 仍需外部应用批准、域名和 callback。

# Pilot API/App Link 双域名配置收口 - 2026-08-21

## Plan

- [x] 将 Caddy 同时接入 API 域名和 Android App Link 域名。
- [x] 让 Tesla redirect URI 只对应 API 域名、App Link URI 只对应 App 域名。
- [x] 让预检拒绝域名缺失、占位域名、私网地址和 URI/域名错配。
- [x] 验证 Caddy 配置解析、PowerShell 预检解析和占位配置 fail-closed。

## Review

- PASS：Caddy `2.8-alpine` 对双域名配置返回 `Valid configuration`。
- PASS：`preflight.ps1` PowerShell 解析通过；示例配置因缺少私密 Tesla 参数、token key 和占位域名而按预期拒绝。
- PASS：Compose 在缺少 `POSTGRES_PASSWORD` 等私密变量时按预期拒绝解析，不会启动错误的 Pilot 配置。
- Boundary：尚未购买/公网部署服务器，尚无正式域名、Tesla 审核、公开 HTTPS callback、正式签名指纹或真实车辆；未操作实体手机、未 stage/commit/push。

# 当前工作树 Android/Docker 全量回归 - 2026-08-21

## Review

- PASS：Android `testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease lintRelease --no-daemon`。
- PASS：独立 `emulator-5554` 清空测试包数据后，登录页 -> Local mock login -> 原 Dashboard；`Development Model 3 / Charging / 76%`、完整底部导航和原 Stats 分析页可见，Stats 显示 `420 km / 215 Wh/km / 90 kWh / 58 kWh / 42.50 ¥` 及证据建议。
- PASS：最近该包 logcat 无 FATAL；本机 Docker `/healthz`、`/readyz` 为 ok，车辆列表 1 台，注销后旧 access/refresh 均 401。
- Boundary：仍未执行真实 Tesla OAuth、正式域名、公网 HTTPS、服务器采购、实体手机和 Git 发布；`MissingTranslation=0` 继续作为多语言覆盖/发布门禁，不描述为运行时 Bug。

# Pilot 一键启动脚本 - 2026-08-21

## Plan

- [x] 修正 `preflight.ps1 -EnvFile`，让 Compose 校验使用同一个私密配置文件。
- [x] 增加 `pilot-up.ps1`：预检、精确 Compose 校验、edge 启动和容器内 readiness 检查串联执行。
- [x] 预检失败时在启动前退出，不打印 secret，也不把 Mock 配置带入 Pilot。

## Review

- PASS：脚本通过 PowerShell 解析检查；示例 `.env` 会在预检阶段 fail-closed，未启动 Pilot 服务。
- PASS：`preflight.ps1` 先将存在的 `-EnvFile` 解析为绝对路径，避免从仓库根目录调用时切换工作目录导致校验错读配置。
- [x] 增加 `android/build-pilot-apk.ps1`，集中执行正式 API/App Link 参数校验、Release lint/build、`com.matelink` 包名校验和 APK SHA-256 输出。
- [x] 增加 `write-assetlinks.ps1`，校验证书 SHA-256、固定正式包名并将输出限制在 `public` 目录。
- [x] 将正式 `assetlinks.json` 缺失、非法或无证书指纹设为 Pilot 预检硬失败，避免启动不可回跳的 edge 服务。
- [x] 让默认 edge Pilot 启动前检查 API/App Link 两个域名的 A/AAAA DNS，避免公网入口未解析时假成功。
- PASS：从仓库根目录执行构建脚本后，脚本在 `android` 目录实际运行 Gradle；`lintRelease`、`assembleRelease`、aapt 包名检查通过，输出 `UNSIGNED_RELEASE` 和 SHA-256 `5D2EA3338DF4B9F3AA64D0BE003945D23D9092D355A9D019F743921BB3698F03`。
- Boundary：脚本已具备真实配置后的可执行入口，但没有域名、Tesla 审核和私密配置时不能宣称公网部署或真实车辆通过。

# 下一阶段本机发布候选收口 - 2026-08-21

## Plan

- [x] 重新执行 Android JVM、Debug、AndroidTest APK、Release 和 Release lint 门禁。
- [x] 重新执行 Go test/vet、Docker health/ready、PowerShell 脚本解析和 `git diff --check`。
- [x] 重新执行正式 `com.matelink` Release 构建入口并固定 APK SHA-256 证据。
- [x] 用示例配置试跑 `pilot-up.ps1`，确认预检失败时不启动或改变本机服务。
- [x] 将本机发布候选和外部阻塞项同步到 docs 与 Obsidian。
- [ ] 取得正式域名 DNS、Tesla 应用批准、公开 HTTPS callback、正式签名指纹和私密 Pilot 配置。
- [ ] 在受控服务器运行真实 Pilot，并用真实 Tesla 单车完成授权、刷新、车辆隔离和退出验收。

## Review

- PASS：Android `testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease lintRelease --no-daemon`；193/193 JVM tests，0 failures/errors/skips。
- PASS：Release lint 0 errors、`MissingTranslation=0`，未建立 lint baseline；MissingTranslation 属于多语言覆盖/发布门禁问题，不描述为运行时 Bug。
- PASS：Go `go test ./... -count=1`、`go vet ./...`；本机 Docker `/healthz` 和 `/readyz` 为 `ok`，模式为 `mock_only`。
- PASS：正式构建入口输出 `com.matelink / UNSIGNED_RELEASE`，APK SHA-256 为 `5D2EA3338DF4B9F3AA64D0BE003945D23D9092D355A9D019F743921BB3698F03`；未签名产物不进入实体手机或公开分发。
- PASS：预检、Pilot 启动脚本、assetlinks 生成脚本和 APK 构建脚本均可解析；示例配置 `pilot-up` 在启动前 fail-closed，未改变本机服务。
- BLOCKED：`api.jourvolt.com` 与 `auth.jourvolt.com` 当前均无 A 记录；真实 Tesla OAuth、服务器、公网 HTTPS、正式 assetlinks、签名 APK、真实车辆和实体手机仍未执行。

# 云模式位置隐私门禁 - 2026-08-21

## Plan

- [x] 将外部 Nominatim 地理编码限制为 `SELF_HOSTED`。
- [x] 云模式和未解析连接模式不读取地理编码队列、不入队、不执行反向地理编码或国家边界请求。
- [x] 用已有 AggregateDao 的待处理坐标查询替换 `SyncRepository` 中未实现的地理编码 TODO；自托管模式保持原能力。
- [x] 增加连接模式 fail-closed 单元测试。

## Review

- PASS：`GeocodingAccessPolicyTest`；云模式和未解析模式均拒绝外部地理编码，自托管模式允许。
- PASS：Android Kotlin 编译与目标 JVM 测试通过。
- Boundary：云模式地图仍需后续接入境内合规地理编码服务或明确关闭精确地址功能；本次没有把 Nominatim 当作公开版方案。

# 云模式位置门禁后的全量回归 - 2026-08-21

## Review

- PASS：Android `testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease lintRelease --no-daemon`；195/195 JVM tests，0 failures/errors/skips。
- PASS：Release lint 成功，`MissingTranslation=0`，未建立 lint baseline；该项继续按多语言覆盖/发布门禁记录。
- PASS：`build-pilot-apk.ps1 -ApiBaseUrl https://api.jourvolt.com/ -AuthHost auth.jourvolt.com -SkipLint`；包名 `com.matelink`，产物 `UNSIGNED_RELEASE`，SHA-256 `CBC65EB9A22A5B08F9A6C5A6F3FAC7D4902624CB643063AF46B62019FD46EA4C`。
- PASS：Go `go test ./... -count=1`、`go vet ./...`、Docker `/healthz` `/readyz`、PowerShell 脚本解析和 `git diff --check`。
- Boundary：仍无正式域名 DNS、Tesla 应用批准、公开 callback、正式签名、服务器和真实车辆；未安装实体手机或执行 Git 发布。

# Fleet API 请求头兼容性收口 - 2026-08-21

## Plan

- [x] 对照 Tesla 中国 Fleet API 官方请求约定检查服务端认证请求。
- [x] 为 Fleet GET 请求补齐 `Content-Type: application/json`。
- [x] 在 401 重试测试中锁定请求头契约。
- [x] 运行 Go 测试和 vet，并将真实 Pilot 边界同步到文档与 Obsidian。

## Review

- PASS：`go test ./... -count=1`、`go vet ./...`。
- PASS：Fleet 401 重试测试确认旧 token、新 token 两次请求均带 `Content-Type: application/json`。
- 依据：[Tesla Fleet API 请求约定](https://developer.tesla.cn/docs/fleet-api/getting-started/conventions)。
- Boundary：仍未使用真实 Tesla 凭据、正式域名或真实车辆；本轮只完成凭证无关的协议兼容收口。

# Tesla 中国 OIDC 默认端点契约 - 2026-08-21

## Plan

- [x] 读取 Tesla 中国官方 OIDC 元数据，不读取任何用户或应用私密凭据。
- [x] 核对 issuer、JWKS、authorize、token 和 Fleet API 默认地址。
- [x] 增加本地默认端点契约测试。

## Review

- PASS：官方元数据返回的默认端点与 `config.go` 完全一致。
- PASS：`TestTeslaChinaDefaultsMatchOfficialOIDCMetadata` 已加入服务端测试。
- Boundary：该核对只证明静态协议配置正确，不代表 Tesla 应用审核、真实 OAuth 或真实车辆已经通过。

# Fleet 车辆状态映射测试 - 2026-08-21

## Plan

- [x] 抽离 Tesla `vehicle_data` 到 MateLink 只读状态的纯映射逻辑。
- [x] 覆盖电量、里程/续航、充电、车门、空调、速度和中控字段。
- [x] 运行 Go 测试和 vet，并同步当前真实 Pilot 边界。

## Review

- PASS：`TestFleetVehicleDataMapsToReadOnlyAndroidStatus`。
- PASS：`go test ./... -count=1`、`go vet ./...`。
- Boundary：这是本地 Fleet 协议/字段证据，不是 Tesla 真实账号或车辆验收。

# 云厂商价格核对与部署门禁 - 2026-08-21

## Plan

- [x] 核对腾讯云大陆轻量服务器官方规格与价格表。
- [x] 核对阿里云轻量服务器产品页及续费规则。
- [x] 核对 Oracle Always Free 的资源和可靠性限制。
- [x] 将首年、续费、固定成本和 Tesla/备案外部门禁同步到 docs 与 Obsidian。
- [ ] 在 P0 外部条件明确后，由 Jovi 确认结算页价格并单独授权采购。

## Review

- PASS：腾讯云官方 2C4G/60GB/5Mbps/500GB 月流量档约 ¥65/月，技术规格满足本项目，但约 ¥780/年不直接满足当前首年≤¥600、续费≤¥700、固定成本≤¥850门禁。
- PASS：阿里云保留为候选；首年与续费价格必须以结算页核对，活动价不视为长期价格。
- PASS：Oracle Always Free 记录为开发/备用候选，不作为真实 Pilot 主服务；容量、区域、信用卡、无 SLA/支持等风险已记录。
- PASS：确认旧 `ApiClient` 未被当前 Repository/DI 正式链路使用；云模式刷新由 `TeslamateApiFactory` + `JourVoltSessionRefresher` 负责，仅清理过期 TODO 注释，不改变自托管行为。
- PASS：确认 `TeslamateApi` 已包含 `GET api/readyz`，清理过期的缺失端点注释。
- Boundary：没有自动下单、没有公网部署，也没有把服务器价格核对描述为 Tesla OAuth 或真实车辆通过。

# 2026-08-21 文档与过期待办收口后的验证

## Review

- PASS：`testDebugUnitTest` 结果 XML 汇总为 195 个 JVM 用例，0 skipped，0 failures/errors。
- PASS：`lintRelease` 成功；`LINT_ERRORS=0`、`MISSING_TRANSLATION=0`，未建立 lint baseline。MissingTranslation 按多语言覆盖/发布门禁问题记录，不描述为运行时 Bug。
- PASS：Go `test ./... -count=1`、`go vet ./...` 和 `git diff --check`。
- PASS：本轮只改文档和过期注释，未改变实体手机、服务器、Tesla 凭据或 Git 发布边界。
- Boundary：真实 Tesla Pilot 仍需正式域名/DNS、Tesla 应用批准、公开 HTTPS callback、release App Link 指纹、签名 APK、服务器和真实车辆授权。

# Pilot 数据备份运维入口 - 2026-08-21

## Plan

- [x] 增加 age 公钥加密 PostgreSQL custom-format 备份脚本。
- [x] 增加需要显式确认、且私钥位于备份目录外的恢复脚本。
- [x] 增加显式 `--prune` 日备份7份、`--weekly --prune` 周备份4份的保留策略。
- [x] 增加 Linux `preflight.sh` / `pilot-up.sh`，并让 PowerShell 预检显式要求 `POSTGRES_PASSWORD`。
- [x] 将备份密钥隔离、7日/4周保留和境内异地对象存储边界同步到 docs 与 Obsidian。
- [x] 将备份目录加入 `.gitignore`，避免误把数据库备份纳入 Git 工作树。
- [x] 增加 Linux systemd 每日/每周备份 service、timer 和安装运行说明。
- [ ] 在实际服务器上配置对象存储上传、生命周期和定时任务，并完成一次独立恢复演练。

## Review

- PASS：使用本机已有 `node:22-bookworm-slim` 容器执行 `bash -n backup-db.sh restore-db.sh`，两个脚本语法通过；使用仅存在于进程环境的 dummy 值执行 Pilot Compose `config --quiet`，模板解析通过。
- PASS：使用同一 Docker Bash 容器执行 `bash -n preflight.sh pilot-up.sh`；未用真实域名、Tesla 凭据或服务器启动。
- PASS：用临时容器内的非生产 HTTPS 域名、32字节测试 key 和正式格式 `assetlinks.json` 执行 `preflight.sh --skip-compose`，返回 `PREFLIGHT=PASS`；测试文件未写入仓库。
- PASS：PowerShell `preflight.ps1 -EnvFile .env.example -SkipCompose` 返回退出码1，示例配置被 fail-closed 拒绝。
- PASS：systemd service/timer 模板静态检查通过；模板只允许 `jourvolt`/`docker` 组和备份目录写入，未在本机启用。
- Boundary：本机 Windows 未安装 `age`，因此没有执行加密备份/恢复运行演练；必须在目标 Linux 服务器安装 `age` 后完成独立恢复测试。
- Boundary：本轮没有执行备份、恢复、删除、服务器操作或对象存储上传；脚本准备不等于生产备份恢复通过。

# 2026-08-21 外部 Pilot 门禁复查

## Review

- PASS：本机 Docker `/healthz` 与 `/readyz` 仍返回 `ok`，Mock 历史状态明确为本地开发数据。
- BLOCKED：`api.jourvolt.com`、`auth.jourvolt.com` 的 A/AAAA/CNAME 查询仍无解析记录；真实公网 callback/App Link 尚未具备启动条件。
- Boundary：没有读取或写入 Tesla 凭据，没有购买服务器、启动公网服务、安装实体手机或执行 Git 发布。

# 2026-08-21 正式签名构建入口

## Plan

- [x] 保持默认 Release 构建未签名，避免没有正式密钥时误生成可发布状态。
- [x] 增加仓库外签名 properties 文件的显式 Gradle 接入。
- [x] 让构建脚本在签名模式下自动校验 `app-release.apk` 的正式包名和 `apksigner verify` 结果。
- [x] 将 keystore/properties 加入 Git 忽略，并同步签名使用边界。
- [ ] 由正式签名持有人提供私密 properties/keystore 后执行签名构建，并核对证书指纹与 assetlinks。

## Review

- PASS：签名入口只接收私密 properties 文件路径，不打印或读取密码到报告；未提供路径时仍保持 `UNSIGNED_RELEASE`。
- PASS：`keystore.properties.example` 只包含字段模板；`android/keystore.properties`、JKS/keystore 文件和 `android/keys/` 已加入忽略。
- PASS：默认执行 `build-pilot-apk.ps1 -ApiBaseUrl https://api.jourvolt.com/ -AuthHost auth.jourvolt.com -SkipLint` 后，Gradle、aapt 包名检查和脚本收口通过，输出 `com.matelink / UNSIGNED_RELEASE`，APK SHA-256 为 `CBC65EB9A22A5B08F9A6C5A6F3FAC7D4902624CB643063AF46B62019FD46EA4C`。
- PASS：首次实现错误已修复并重新构建；PowerShell 解析通过，未提供签名文件时不会调用或伪造 `apksigner` 成功。
- PASS：签名入口改动后重新执行 `testDebugUnitTest` 和 `lintRelease`；195/195 JVM tests，0 failures/errors/skips，lint 0 errors，`MissingTranslation=0`。
- Boundary：本轮没有生成、读取或使用任何签名密钥，因此没有把未签名 APK 描述成可发布包。

# 2026-08-21 独立模拟器当前 Debug 手工回归

## Review

- PASS：仅对 `emulator-5554` 覆盖安装 `com.matelink.test.mock` 并清理该测试包数据；未连接或操作实体手机，未运行 instrumentation。
- PASS：登录页显示 Tesla 官方授权说明和协议确认；Mock 登录后回到原 MateLink Dashboard，显示 `Development Model 3 / Charging / 76% / Local mock`。
- PASS：Dashboard 底部 `Dashboard / Drives / Charges / More` 可见；More → Statistics 显示 `420 km`、`215 Wh/km`、`23` 条来源记录和带阈值/样本/覆盖期/置信度的建议。
- PASS：本次手工回归的最近 AndroidRuntime 错误筛选为空；证据仍属于 `LOCAL MOCK HISTORY PASS`，不是 Tesla OAuth 或真实车辆证明。

# 当前 Obsidian 概览同步 - 2026-08-21

## Plan

- [x] 读取本项目 Obsidian 目录中的概览、计划、关键决策、证据索引、云服务门禁和分析记录。
- [x] 将仍停留在 7 月 Adapter 候选状态的 `00-项目概览.md` 更新为当前 `com.matelink` / JourVolt 本地 Mock 交付边界。
- [x] 保留历史证据，不把本地 Mock 或未签名 APK升级为真实 Tesla Pilot 或可发布包。
- [x] 重新核对 `api.jourvolt.com` 与 `auth.jourvolt.com` 的 A/AAAA/CNAME 状态。

## Review

- PASS：Obsidian 概览已同步为 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。
- PASS：当前 DNS 查询仍无 API/App Link 域名的 A、AAAA 或 CNAME 记录；没有擅自启动公网服务。
- Boundary：Tesla 应用批准、正式域名、公开 HTTPS callback、签名 App Link、服务器和真实车辆仍需外部条件；本轮没有读取或写入任何密钥。

# 2026-08-21 历史接口可用性元数据契约

## Plan

- [x] Android 车辆、行程、充电和电池响应增加可选 `meta`，兼容旧 TeslaMate 响应。
- [x] 将响应来源、可用性、观测时间、采集起点和覆盖率传入 Repository，并由历史状态存储供 Statistics 使用。
- [x] Go 兼容接口区分 `available`、`collecting` 和 `unsupported`，空历史不再被解释为真实零值。
- [x] 补充 Android/Go 单元测试、本地 HTTP 证据和独立模拟器原 Dashboard/Statistics 回归。

## Review

- PASS：Mock 历史开启时 `/api/v1/cars/1/drives` 返回18条、charges返回5条，元数据为 `available / mock_fixture / 100%`。
- PASS：关闭 Mock 历史后两类接口返回空集合和 `collecting / mock_fixture`；服务已恢复为本地 Mock 历史开启状态。
- PASS：Android 当前 JVM 聚合为199/199，0 failures/errors/skips；`lintRelease` 通过且 `MissingTranslation=0`。其余 lint issue 仍属于多语言覆盖/发布门禁问题，不能描述为运行时 Bug 数量。
- PASS：元数据契约改动后重新执行 `testDebugUnitTest`、`assembleDebug`、`assembleRelease` 和 `lintRelease`，Gradle `BUILD SUCCESSFUL`；Go `test ./... -count=1` 与 `go vet ./...` 通过。
- PASS：模拟器 `emulator-5554` 的 Mock 登录进入原 Dashboard；More -> Statistics 显示420 km、215 Wh/km、23条来源记录以及带阈值/样本/覆盖期/置信度的建议；未发现 FATAL/ANR。
- Boundary：旧 TeslaMate 未提供 `meta` 时保持原有兼容行为；真实 Tesla OAuth、公网 HTTPS 和车辆数据仍未验收。

# 2026-08-21 历史与分析可靠性计划状态同步

- PASS：当前工作树已具备 `AnalysisHistory` 的全时/90天/季节/自定义窗口、源 ID 去重、分页读取入口和空数据原因模型。
- PASS：充电详情已使用按车辆/充电记录保存的人工总价覆盖，行程默认筛选为全时，More 已进入完整行程历史；百分位、待机归因和年度报告年份契约均有实现与专项测试。
- PASS：专项测试 `AnalysisHistoryRepositoryTest`、`ManualChargeAmountTest`、`DrivesHistoryContractTest`、`PercentileTest`、`StandbyAttributionTest`、`AnnualReportYearsTest` 全部通过。
- PASS：网络失败后的进程内历史缓存新鲜度已实现；最后成功快照会标记为 `STALE`，效率、费用、续航、待机和年度报告页面显示缓存提示；没有缓存时仍返回错误。
- PASS：缓存改动后的 `assembleDebug` 通过；仅在 `emulator-5554` 覆盖安装测试包并回归 Mock 登录、原 Dashboard、More -> Statistics，仍显示420 km、215 Wh/km、23条记录和分析建议，应用错误筛选为空。
- Boundary：真实 Fleet/Telemetry 历史数据和真实设备回归仍未证明；旧计划中的“服务器、签名、实体手机”条目不因本地测试通过而完成。

# 2026-08-21 Room 历史降级与统计页安全区收口

## Plan

- [x] 复用既有 Room `DriveSummary`/`ChargeSummary`，网络历史接口失败时构造 `STALE` 分析快照。
- [x] 保留未知值与真实零值的区别；占位距离、电量、时长不降级为真实 0，真实充电成本 0 保持可用。
- [x] 增加摘要映射、持久化降级和缓存新鲜度测试。
- [x] 统计页保留原信息结构，仅补充滚动末尾安全区，不重做原有视觉风格。
- [x] 完成本地 Android、Go、Docker 和独立模拟器回归。

## Review

- PASS：`AnalysisHistoryRepository` 网络失败时优先使用当前进程成功快照，否则读取现有 Room 摘要并标记 `STALE`；网络成功结果优先，不新增数据库迁移。
- PASS：独立模拟器 `emulator-5554` 的 Mock 登录进入原 `com.matelink.test.mock` Dashboard；More → Statistics 显示 `420 km`、`215 Wh/km`、`23` 条来源记录和带阈值/样本/覆盖期/置信度的建议；滚动到页面末尾后建议行动完整位于底部导航上方。
- PASS：Android JVM `202` tests，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、`lintRelease` 均通过；`MissingTranslation=0`。Lint 剩余 `256` 项按多语言覆盖/发布门禁记录，不描述为运行时 Bug。
- PASS：未签名 Release 包名为 `com.matelink`，version `1.4.2`，SHA-256 `704593A8EEC463DBABCAF20E9BD338016708C9901CEC9906C9121A69F84972F1`；未把它描述为可发布 APK。
- PASS：Go `test ./... -count=1`、`go vet ./...`、本机 Docker `/healthz`/`/readyz` 通过；未认证车辆接口返回预期 `401 session_required`。
- Boundary：本轮只在独立模拟器验证，未连接实体手机、未运行 instrumentation；没有 Tesla 凭据、真实车辆、公网 DNS/HTTPS、正式签名、服务器采购或 Git 发布证据。

# 2026-08-21 Pilot 部署包可执行性收口

## Review

- PASS：使用仅存在于当前进程的临时假值校验 `docker-compose.pilot.example.yml`，Compose 配置解析通过；没有写入 `.env` 或保存任何密钥。
- PASS：在 `node:22-bookworm-slim` 隔离容器中执行 `bash -n`，`preflight.sh`、`pilot-up.sh`、`backup-db.sh`、`restore-db.sh` 全部通过。
- PASS：示例 `.env` 缺少正式 Tesla 配置时，PowerShell 预检返回 `PREFLIGHT=FAIL`；错误的 assetlinks 指纹长度被拒绝，正确的 64 位测试指纹 `-WhatIf` 预览通过。
- Boundary：Windows 本机没有 Bash，因此没有把 WSL 缺失误报成项目失败；Linux Bash 语法已在隔离容器验证。真实 DNS、HTTPS、Tesla 配置、签名证书和服务器仍未执行。

# 2026-08-21 Release manifest 最终核对

- PASS：未签名 Release APK 包名为 `com.matelink`，App Link 为 `https` + `auth.jourvolt.com` + `/oauth/callback`，并带 `autoVerify=true`。
- PASS：Release manifest 未出现 `10.0.2.2`、设计审查 Activity 或 Debug Mock 包名；APK SHA-256 仍为 `704593A8EEC463DBABCAF20E9BD338016708C9901CEC9906C9121A69F84972F1`。
- Boundary：assetlinks 正式证书指纹和公网 DNS 尚未提供，因此 manifest 核对不等同于 App Link 已在公网验证。

# 2026-08-21 Linux assetlinks 生成入口

- [x] 新增 `deploy/jourvolt-dev-mock/write-assetlinks.sh`，Linux 服务器无需 PowerShell 即可生成正式 `com.matelink` assetlinks。
- [x] 对 64 位指纹、`--what-if`、错误指纹和 public 目录外路径执行隔离容器验证。
- Boundary：只验证脚本和路径安全，没有写入正式证书指纹或公网 assetlinks。

# 2026-08-21 正式 Release 页面验证与产物恢复

- PASS：为验证正式 Release 的实际启动页，曾使用仅存在于当前进程的 Android Debug Keystore 临时签名配置；`apksigner` v2 校验通过，包名为 `com.matelink`，仅安装到隔离模拟器 `emulator-5554`。
- PASS：临时签名 Release 启动后显示原 MateLink 登录页和官方授权文案：`Connect your Tesla`、`Use Tesla login`、`Advanced: connect a self-hosted service`；未显示 Mock 登录、回环地址或设计审查入口，过滤日志未发现 FATAL/ANR。
- Boundary：没有公网 DNS/HTTPS 和 Tesla 应用配置，因此本次只证明正式 Release UI 和启动边界，不证明真实 Tesla OAuth 或真实车辆读取。
- PASS：临时签名属性文件已删除；关闭配置缓存并强制重建后，当前交付目录恢复为未签名 `app-release-unsigned.apk`，包名 `com.matelink`、version `1.4.2`、SHA-256 `7BDF20FED7F2FA4D2193B6C6E1A8CA9A085E92F3C54A04303D342F0E97D90A89`。未签名包被 `apksigner` 拒绝属于预期，不能作为商店或真实 Pilot APK。

# 2026-08-21 正式 Pilot 构建入口复跑（当前权威证据）

- PASS：执行 `android/build-pilot-apk.ps1 -ApiBaseUrl https://api.jourvolt.com/ -AuthHost auth.jourvolt.com`，脚本完成 `lintRelease`、`assembleRelease`、`aapt` 包名校验和未签名状态检查。
- PASS：当前 Android JVM `202/202`，failures/errors/skips 均为 `0`；Go `test ./... -count=1` 与 `go vet ./...` 均为 `0`。
- PASS：本机 Docker/PostgreSQL 服务为 healthy，`/healthz` 与 `/readyz` 均返回 `200`；仅证明本地 Mock 服务健康。
- PASS：Release lint 当前为 `256` 个 Warning、`0` 个 Error、`MissingTranslation=0`、无 lint baseline；该 256 项按多语言覆盖/发布门禁记录，不能描述为运行时 Bug。
- PASS：脚本输出 `UNSIGNED_RELEASE`，包名 `com.matelink`，version `1.4.2`，SHA-256 `7BDF20FED7F2FA4D2193B6C6E1A8CA9A085E92F3C54A04303D342F0E97D90A89`。
- Boundary：`api.jourvolt.com`、`auth.jourvolt.com`、`jourvolt.com`、`jourvolt.cn` 当前仍无 A/AAAA；本次没有读取 Tesla 凭据、正式签名文件或启动公网服务。

# 2026-08-21 服务器预算重新核对（官方当前页面）

- PASS：腾讯云轻量应用服务器中国内地官方价格表显示，2核4G/100GB/7Mbps/1000GB 为 `90 元/月`；同页列出 2核2G/40GB/3Mbps/200GB 为 `40 元/月`，购买 1 年可按 85 折计算。腾讯云购买页当前展示 2核2G 入门套餐为 `459 元/年`。[腾讯云价格总览](https://cloud.tencent.com/document/product/1207/73452/)、[腾讯云购买页](https://cloud.tencent.com/product/lighthouse?Is=sdk-topnav)
- PASS：阿里云官方活动页面当前展示 2核4G/40GB/2Mbps 为约 `1733.04 元/年` 的促销示例；活动价和续费价不能作为长期预算承诺。[阿里云轻量应用服务器](https://promotion.aliyun.com/ntms/act/swas.html)
- PASS：Oracle Always Free 官方资源包含每月 1,500 OCPU 小时/9,000 GB 小时的 Ampere A1 资源，等价上限为 2 OCPU/12GB，但要求在 home region，可能遇到容量不足；官方 FAQ 明确免费账户没有 SLA 和 Oracle 支持。[Oracle Always Free 资源](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)、[Oracle 免费套餐 FAQ](https://www.oracle.com/cn/cloud/free/faq/)
- Decision：若固定成本门槛仍为服务器≤600元/年，预算内唯一可考虑的是腾讯云 2核2G 年付候选；它低于原定 4G 目标，必须先做内存压力试跑，且不启用 Telemetry 持续采集。若坚持 2核4G，应把服务器预算提高到至少约 918元/年（腾讯云按90元/月及85折估算）并重新确认总预算。
- Boundary：未自动下单；正式采购仍需 Jovi 查看结算页、确认续费价格并单独授权。Oracle 不作为中国大陆生产 Pilot 主服务，只保留开发/备用候选。

# 2026-08-21 2GB 候选本地容量烟测

- PASS：本机 Mock Compose 的 API 与 PostgreSQL 容器临时各限制为 `1GiB`（合计 `2GiB`），使用合法 Mock 会话并发请求 `/v1/vehicles`、`/api/v1/cars`、`/api/matelink/v1/capabilities` 共 `1000` 次，全部返回 `200`，耗时约 `100ms`。
- PASS：限制期间 API 峰后约 `20.41MiB/1GiB`，PostgreSQL 约 `76.08MiB/1GiB`；恢复 Compose 后两个容器的 memory/memory-swap 均回到 `0`（无限制）。
- PASS：恢复后 `/healthz`、`/readyz` 正常；Mock 用户仍可读取 `1` 辆车、`18` 条行程和 `5` 条充电记录，证明本次重建未删除本地数据库卷。
- Boundary：这是本地 Mock API 的容量烟测，不覆盖真实 Fleet API、Telemetry、Caddy、备份、网络带宽或生产 SLA；2核2G 只能作为无持续 Telemetry 的邀请制技术公测候选。

# 2026-08-21 隔离 Pilot Caddy Edge 烟测

- PASS：使用独立 Compose 项目 `jourvolt-edge-smoke` 和仅存在于当前进程的假配置启动 Fleet 模式 API、PostgreSQL 与 Caddy；没有写入正式 `.env`、Tesla 凭据或签名文件。
- PASS：通过本地测试域名的 HTTPS 入口验证 `/healthz`、`/readyz` 反向代理返回 `200`，`/.well-known/assetlinks.json` 返回正式包名 `com.matelink`，未携带会话访问车辆接口返回 `401 session_required`。
- PASS：隔离项目容器、网络和卷已按项目名停止并删除；本地 `jourvolt-dev-mock` 未受影响，健康检查仍为 `ok`。
- Boundary：使用 Caddy 内部测试证书、测试域名和占位证书指纹；这不等同于公网 DNS、ACME 正式证书、正式签名 App Link、Tesla OAuth 或真实车辆验收。

# 2026-08-21 统计成本零值保真修复

- PASS：`ChargeSummaryDao` 新增有费用字段记录数查询；`StatsRepository` 不再用 `cost > 0` 丢弃真实 `0` 元费用，同时仍把没有任何费用字段的聚合结果保持为不可用。
- PASS：新增 `observedAggregateCostOrNull` 领域规则及 NaN、Infinity、负数、无来源记录和真实零值测试。
- PASS：`:app:testDebugUnitTest`、`:app:lintRelease`、`:app:assembleRelease` 通过；Release lint `256` 项、`MissingTranslation=0`、`0` Error。
- PASS：当前未签名 Release SHA-256 为 `BC4C282ED34FC30E27853021C336FA63A27A13760AF41CB470FF15C99D217BF7`；包名 `com.matelink`，version `1.4.2`。
- Boundary：该修复覆盖本地统计聚合；真实 Fleet API、服务器、签名和 Tesla Pilot 外部门禁不变。

# 2026-08-21 分析详情未知值保真修复

- [x] `DriveElevationRecord`、温度记录和充电功率记录保留 nullable 数据，不再由 `StatsRepository` 将缺失值写成 `0`/`0.0`。
- [x] `UnitFormatter.formatElevation(null, ...)` 返回不可用标记；统计详情的温度、功率缺失时显示 `N/A`，不伪造观测值。
- [x] 新增缺失海拔和真实海拔格式化测试；与统计详情编译回归一起验证。
- [x] Android JVM `207` tests，failures/errors `0`；Go test/vet、`assembleRelease`、`lintRelease` 全部通过。
- [x] Release lint `256` warnings、`0` errors、`MissingTranslation=0`、无 baseline；256 项继续按多语言覆盖/发布门禁记录，不描述为运行时 Bug。
- [x] 当前未签名 `com.matelink` `1.4.2` Release SHA-256：`52C33114887629FA293D33DF5D07AEA1143816EB4E21F870F092B547A7E91AD6`；`git diff --check` 为 `0`。
- Boundary：该修复覆盖本地统计详情数据诚实性；未操作实体手机、Tesla 凭据、正式签名、服务器、DNS 或 Git 发布。

# 2026-08-21 里程成本零值保真修复

- [x] 年/月/日及生命周期成本聚合统一使用 `observedCostSumOrNull`；真实免费充电 `0` 元保留，没有成本字段时保持不可用。
- [x] 里程页成本卡片不再用 `0.00` 作为缺失占位，改为 `—`；成本来源和统计页规则一致。
- [x] 新增真实零值、缺失值、NaN 和负值回归测试。
- [x] 修复后 Android JVM `207` tests，failures/errors `0`；`lintRelease`、`assembleRelease` 和 `git diff --check` 通过；本机 Mock `/healthz`、`/readyz` 正常。
- [x] 当前未签名 `com.matelink` `1.4.2` Release SHA-256：`4C32A593EF10C988170CD57A55333699D63ED7B9FDBF7984811DE7342552E825`。
- Boundary：本轮没有操作实体手机、Tesla 凭据、正式签名、服务器、DNS 或 Git 发布。

# 2026-08-21 分析报告缺失效率显示收口

- [x] 统计页“最高效率”、年度报告和 PDF 报告对缺失效率统一显示 `N/A`，不再回填 `0` 或输出 `null`。
- [x] 最终 Android JVM `208` tests，failures/errors `0`；Go test/vet、`lintRelease`、`assembleRelease` 和 `git diff --check` 通过。
- [x] Release lint `256` warnings、`0` errors、`MissingTranslation=0`、无 baseline；256 项仍归类为多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- [x] 当前未签名 `com.matelink` `1.4.2` Release SHA-256：`7578E86536462BA639E763243B3DBC58321C21AE7F6076E8F3571A928415A4D6`；本机 Mock `/healthz`、`/readyz` 正常。
- Boundary：本轮只完成本地 UI/分析数据诚实性和构建门禁；没有操作实体手机、Tesla 凭据、正式签名、服务器、DNS 或 Git 发布。
- Clarification：当前 `256` 是 lint findings 的总数，主要为 `UnusedResources=89`、`GradleDependency=52`、`DefaultLocale=26` 等；当前 `MissingTranslation=0`。此前讨论的 `883 MissingTranslation` 属于多语言覆盖/发布门禁，不是运行时 Bug 数量；不能把当前 256 全部称为 MissingTranslation。

# 2026-08-21 费用覆盖显示收口

- [x] 充电汇总记录有价格来源的条数；无来源时不把 SQL/内存默认 `0` 渲染为免费。
- [x] 统计页每百公里成本保留真实免费 `0`，对缺失、负数、NaN、Infinity 返回不可用。
- [x] 新增成本/距离边界测试；Android JVM `209` tests，failures/errors/skips `0`。
- [x] `assembleRelease`、`lintRelease`、Go test/vet、Mock health/ready 和 `git diff --check` 通过。
- [x] 用 SDK `aapt2` 核对未签名 APK：`com.matelink` `1.4.2` / versionCode `14`；SHA-256 `172F73C17ACF5D9BBC61BB34F852FE5D32D393604B109E374340789155B2A204`。
- Boundary：没有操作实体手机、Tesla 凭据、正式签名、服务器、DNS 或 Git 发布；真实 Pilot 仍需外部条件。

## Review

- PASS：Release lint `256` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量按多语言覆盖/发布门禁记录，不描述为运行时 Bug。
- PASS：本机 Docker `/healthz`、`/readyz` 返回 `status=ok`；Mock 历史 fixture 未被本轮改变。

# 2026-08-21 隔离模拟器最新完整链路

- [x] 仅使用 `emulator-5554` 覆盖安装 `com.matelink.test.mock` Debug APK；未操作实体手机，未运行 instrumentation。
- [x] 手工验证协议确认 → Mock 登录 → 原 Dashboard：`Development Model 3 / Charging / 76% / Local mock`，底部 `Dashboard / Drives / Charges / More` 可见。
- [x] More → Statistics 显示 `420 km`、`215 Wh/km`、`23` 条来源记录、派生结论和样本/覆盖/置信度建议。
- [x] 最近 300 条 logcat 未发现 `FATAL EXCEPTION` 或 `ANR in`；证据等级保持 `LOCAL MOCK HISTORY PASS`。
- Boundary：没有 Tesla 官方登录、真实车辆、正式签名、服务器、DNS、实体手机或 Git 发布操作。

# 2026-08-21 授权同意与账号生命周期本地收口

- [x] 登录页拆分用户协议和隐私政策的主动确认；未配置公开 HTTPS 页面时正式云登录 fail-closed。
- [x] 将当前同意版本绑定到 OAuth 授权事务，并在身份验证完成后原子保存；不保存 Tesla 密码或 Tesla token 到 Android。
- [x] 完成账户删除确认和 P1 数据级联删除：用户、JourVolt session、加密 Tesla grant、车辆关联和同意记录。

## 2026-08-24 服务器 staging 部署（当前）

- [x] 使用 `jourvolt` SSH 公钥账户上传无密钥 bundle 到阿里云 ECS，并部署 Compose 项目 `jourvolt-staging`。
- [x] JourVolt Go API 与 PostgreSQL 容器 healthy；`/healthz`、`/readyz` 均返回 `status=ok`。
- [x] 通过 SSH 回环隧道运行 Mock smoke：`LOCAL MOCK PASS`；1 台车辆、18 条行程、5 条充电，logout/revocation 通过。
- [x] API 仅绑定 `127.0.0.1:18090`，未开放公网；原 `star-photo` 主服务和 worker 保持 healthy。
- [x] 记录依赖代理根因：服务器到 `proxy.golang.org` 超时，Docker 构建阶段改用 `goproxy.cn` 后重建成功。
- [ ] 正式 Pilot 仍待：域名 HTTPS、Tesla 应用批准、正式私密配置、App Link/assetlinks 和真实单车 OAuth/Fleet 验收。

### Review

- 服务器 staging PASS 不等于生产部署、Tesla 官方 OAuth 或真实车辆 PASS；当前 `JOURVOLT_ENABLE_MOCK=true`、`JOURVOLT_ENABLE_MOCK_HISTORY=true` 仅用于开发验证。
- 未操作原有摄影容器；未读取或写入 Tesla secret、账号密码、refresh token 或正式 token key；未执行 Git stage/commit/push。
- [x] 修复 Debug Mock 删除账户误走正式 HTTPS 校验的问题；仅 `com.matelink.test.mock` 允许本机回环 HTTP，Release 保持 HTTPS-only。
- [x] 通过 Go test/vet、PostgreSQL 集成测试、本机 Docker HTTP 生命周期和 `emulator-5554` 手工 Mock 回归；未使用实体手机或 instrumentation。
- [x] 更新 Android/云接口/Pilot 文档及 Obsidian 当前进度，明确本地证据不等于真实 Tesla Pilot。

## Review

- LOCAL MOCK PASS：Mock 登录后进入原 Dashboard；设置页删除确认后回到干净登录页，旧 session 返回 401，最近 300 条 logcat 无 FATAL/ANR。
- Pending external: 受控域名和公开 HTTPS 条款/隐私页面、Tesla 中国开发者审核与凭据、正式签名和 App Link、服务器私密配置、真实单车 OAuth/Pilot。未完成这些条件前不得宣称真实车辆交付或公开发布。
- Final local gate: Android `211` tests / 0 failures-errors-skips，Go test/vet，Docker health/ready，`lintRelease`、`assembleRelease` 和 `git diff --check` 均通过；最新 `UNSIGNED_RELEASE` SHA-256 为 `ABE80587FF6328D7D95FEB62E1038386583D4D63200AE3661AACAC06AC96A77A`。

# 2026-08-21 个性化续航模型本地落地

## Plan

- [x] 在独立领域算法中实现近 90 天样本窗口、温度/速度分组和距离加权效率。
- [x] 实现分组模型与全局模型的样本数/累计里程门槛；容量或样本不足时不输出虚假公里数。
- [x] 在原续航分析页增加个性化续航卡片，沿用原 MateLink 卡片、边框和主题，不替换原页面结构。
- [x] 增加分组优先、全局回退、容量缺失、时间窗口、非法值和边界分组测试。
- [ ] 用真实 Fleet/Telemetry 采集数据验证模型；本地 Mock 只能验证算法和 UI 状态。

## Review

- PASS：`PersonalizedRange.kt` 使用温度 `<10/10–25/>25°C`、速度 `<50/50–90/>90 km/h` 分组；分组至少 `5` 次且 `100 km`，全局至少 `10` 次且 `300 km`。
- PASS：个性化公里数只在当前可用电池容量为有效观测值时计算；容量未知时保留 Tesla 额定续航（若有），不生成估算数字。
- PASS：原“额定续航消耗偏差”卡片、影响因素和行程列表保留；新增卡片明确模型来源、有效样本、距离、置信度与当前条件。
- Boundary：本轮没有把简单聚合称为 AI，也没有改变正式 App 包名、登录边界、Mock/Release 边界或外部 Pilot 门禁。

# 2026-08-21 电池健康趋势模型本地落地

## Plan

- [x] 新增独立 `BatteryTrend` 领域算法：SOC 70–100%、温度 15–30°C、至少 10 个有效样本和 30 天覆盖。
- [x] 将额定续航标准化到 100% SOC；使用早期 30 天基线与最近窗口中位数，缺少早期基线时只显示趋势，不输出衰减百分比。
- [x] 将历史行程接入原 `BatteryViewModel`，保留后端容量观测优先级；无容量数据时在原电池页增加小卡片，不重做 UI。
- [x] 补齐英中文案、复数资源、样本/覆盖期/置信评分及无数据状态；无有效样本时不显示 `0` 指标。
- [ ] 用真实 Fleet/Telemetry 数据验证趋势稳定性；本地 Mock 只能验证算法、空状态和页面链路。

## Review

- PASS：`BatteryTrendTest` 覆盖标准化、中位数、分离基线、样本/覆盖门槛、非法值和边界值；全量 Android JVM `221` tests，failures/errors `0`。
- PASS：`assembleDebug`、`assembleRelease`、`lintRelease`、Go test/vet 和 `git diff --check` 通过；lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline。该 258 是发布门禁 findings 总数，不是运行时 Bug 数量。
- PASS：`emulator-5554` 覆盖安装 `com.matelink.test.mock` 后打开原 Battery Health 页面，显示趋势条件说明；截图无重叠/溢出，最近 500 条应用日志无 FATAL/ANR。
- PASS：Release `aapt2` 核对为 `com.matelink` / MateLink / `1.4.2` / versionCode `14`；未签名 APK SHA-256：`81744CA5A06C467583183D7AA882BDDC1C84A22B31F4B1C728CCE51B929BF091`。
- Boundary：没有操作实体手机、Tesla 凭据、正式签名、服务器、DNS、Git 发布；状态仍为 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。

# 2026-08-21 Statistics 数据覆盖摘要本地落地

## Plan

- [x] 在原 Statistics 卡片内增加数据覆盖领域模型，区分记录总数与各指标有效输入数。
- [x] 接入 All Time/年度筛选的 Room 历史聚合，避免重复读取并保持原分析、建议和统计卡片不变。
- [x] 增加里程、行驶能耗、充电能量、费用覆盖率及观测日期范围；无记录时保持不可用，不显示伪造零值。
- [x] 补齐领域边界测试和中英文资源，未建立 lint baseline。
- [x] 在独立模拟器验证原 Dashboard → More → Statistics 链路和新覆盖摘要布局。

## Review

- PASS：`AnalysisCoverageTest` 覆盖有限值、真实零费用、无记录、非法日期和日期范围；Android JVM `224` tests，failures/errors/skips 均为 `0`。
- PASS：`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、`lintRelease`、Go `test`/`vet` 和 `git diff --check` 通过。
- PASS：Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量是多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- PASS：`emulator-5554` 的 `com.matelink.test.mock` Debug 包仍显示原 `Development Model 3 / Charging / 76% / Local mock`；Statistics 保留 `420 km`、`215 Wh/km`、`23` 条记录和派生结论，并新增 `18/18` 行程、`5/5` 充电覆盖和 `56 days` 观测范围；最近 500 条日志无 FATAL/ANR。
- PASS：Release `aapt2` 核对为 `com.matelink` / MateLink / `1.4.2` / versionCode `14`；最终重建未签名 APK SHA-256：`E0016BD9598E5900694BDCEDDBB21C07A00C67BD1A4AE46B2028348A4E9E3DEA`。
- Boundary：仍未执行实体手机、真实 Tesla OAuth/Fleet、服务器/DNS/公网 HTTPS、正式签名、Git 发布或真实数据验证；状态保持 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。

# 2026-08-22 分析历史采集状态与待机证据收口

## Plan

- [x] 保留历史分页响应的可选 `meta`，并在空历史时区分采集中、不支持和普通无记录。
- [x] 将统一历史状态面板接入效率、成本、续航和待机四个原分析页，不重做原 MateLink UI。
- [x] 收紧待机页面证据表达：SOC 下降不再称为待机能耗，无容量/Telemetry 时不显示 kWh 或平均功率。
- [x] 增加状态分类边界测试并保持中英文资源完整。
- [x] 完成本地 Android、Go、Docker 和隔离模拟器验证；不执行实体手机、真实 Tesla、正式签名或 Git 发布。

## Review

- PASS：Android JVM `226` tests，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- PASS：Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- PASS：Go `test`/`vet` 通过；Docker `jourvolt-dev-api` 与 PostgreSQL healthy，`/healthz`、`/readyz` 返回成功。
- PASS：`emulator-5554` 覆盖安装 `com.matelink.test.mock` Debug 后进入原 Standby 页面；无历史时保持诚实空状态，没有 0、默认 kWh 或假功率。
- Boundary：真实 Tesla OAuth/Fleet、域名/DNS、公网 HTTPS、正式签名、实体手机和 Git 发布仍未完成，状态保持 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。

# 2026-08-22 Statistics MetricState 状态呈现继续收口

## Plan

- [x] 将历史 `collecting` 状态从页面级元数据接到原 Statistics 摘要、派生结论和覆盖率卡片。
- [x] 保证已有有效指标不被采集状态覆盖，并区分采集中、不可用、可重试和普通无记录。
- [x] 增加状态保持测试，保留中文/英文资源和现有原 MateLink UI。
- [x] 在隔离模拟器验证原 Dashboard → More → Statistics 链路；不操作实体手机。

## Review

- PASS：Android JVM `228` tests，failures/errors/skips `0`；`assembleDebug`、`assembleRelease`、`lintRelease`、Go `test`/`vet`、Docker health/ready、`git diff --check` 通过。
- PASS：Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；这是多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- PASS：`com.matelink.test.mock` 在 `emulator-5554` 保持原 Dashboard、More 和 Statistics；统计内容、派生结论、覆盖率显示正常，最近 800 条日志无 FATAL/ANR。
- Boundary：未操作实体手机、真实 Tesla OAuth/Fleet、正式签名、服务器、公网 DNS/HTTPS、Git stage/commit/push；状态保持 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。

# 2026-08-22 JourVolt 会话刷新链收口

## Plan

- [x] 修正 Debug Mock 会话刷新使用正式云地址的问题。
- [x] 保留正式云模式 HTTPS、单飞刷新、原子 refresh token 轮换和失败清理边界。
- [x] 增加 Mock/云刷新地址分支契约测试。
- [x] 完成 Android、Go、Docker 和隔离模拟器启动验证，不操作实体手机。

## Review

- PASS：Android JVM `229` tests，failures/errors/skips `0`；Debug/Release 构建、Release lint、Go test/vet、Docker health/ready、`git diff --check` 通过。
- PASS：Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- PASS：隔离模拟器 `emulator-5554` 重新安装 `com.matelink.test.mock` 后启动原 Dashboard，车辆文案可见，最近 500 条日志无 FATAL/ANR。
- Boundary：真实 Tesla OAuth/Fleet、域名/HTTPS、正式签名、实体手机和 Git 发布仍未执行；状态保持 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。

# 2026-08-22 原 Dashboard 云端车辆读取与错误语义收口

## Plan

- [x] 修复云端车辆列表 ID 与旧本地 `currentCarId` 不一致时的迁移和轮询链。
- [x] 为兼容 API 错误增加稳定类别，覆盖授权失效、429 限流、5xx 服务不可用和网络失败。
- [x] 在原 Partial Dashboard 显示对应错误状态，避免把授权/服务错误误报成同步中。
- [x] 补齐中英文资源与单元测试，不改原 MateLink 页面结构，不建立 lint baseline。
- [x] 完成 Android、Go、Docker 和隔离模拟器验证，不操作实体手机。

## Review

- PASS：Android JVM `234` tests，failures/errors/skips `0`；Debug/Release 构建、Release lint、Go test/vet、Docker health/ready、`git diff --check` 通过。
- PASS：Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- PASS：隔离模拟器 `emulator-5554` 重新安装 `com.matelink.test.mock` 后启动原 Dashboard，UI dump 找到 Dashboard 和车辆文案，最近 500 条日志无 FATAL/ANR。
- Boundary：真实 Tesla OAuth/Fleet、域名/HTTPS、正式签名、实体手机、服务器公网 Pilot 和 Git 发布仍未执行；状态保持 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。

# 2026-08-22 Tesla 登录错误语义收口

## Plan

- [x] 将授权开始和一次性 ticket 交换的 400/401/403/429/5xx 转成稳定的登录错误资源。
- [x] 补齐中英文登录错误文案，保持官方 OAuth、Custom Tab、App Link 和原 Dashboard 回流不变。
- [x] 增加登录错误映射测试并完成 Android、Go、Docker、Release/lint 和隔离模拟器门禁。

## Review

- PASS：Android JVM `236` tests，failures/errors/skips `0`；Debug/Release 构建、Release lint、Go test/vet、Docker health/ready、`git diff --check` 通过。
- PASS：Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- PASS：`emulator-5554` 重新安装最新 `com.matelink.test.mock` 后启动原 Dashboard，车辆文案可见，最近 500 条日志无 FATAL/ANR。
- Boundary：真实 Tesla OAuth/Fleet、域名/HTTPS、正式签名、实体手机、服务器公网 Pilot 和 Git 发布仍未执行；状态保持 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。

# 2026-08-22 Release Mock 源集与语言策略收口

## Plan

- [x] 将 Mock 登录入口和文案移出 `src/main`，Debug 使用真实 Mock 入口，Release 使用空实现。
- [x] 保持正式 `com.matelink` 的 Tesla 官方 OAuth、原 Dashboard、原底部导航和自托管连接不变。
- [x] 按已确认的首发语言策略移除不完整的 `values-de`、`values-fr`、`values-ja` 残留资源，保持中文/英文完整。
- [x] 运行 Debug/Release JVM、Release lint/build、Release APK Mock 内容核对和隔离模拟器启动验证。

## Review

- PASS：Android Debug/Release 各 `237` tests，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。
- PASS：Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 lint baseline；`258` 仅记录多语言覆盖/发布门禁，不描述为运行时 Bug。
- PASS：Release `aapt2` 包名为 `com.matelink`，APK 资源和归档类中均无 `debug_mock_login` 或 `DebugMockLogin`；未签名 SHA-256 为 `A72EDDA56A3AE13645E710621682007BA5B881117011993F858E2ECA2F48478E`。
- PASS：`emulator-5554` 覆盖安装 `com.matelink.test.mock` Debug 后仍进入原 Dashboard，显示 `Development Model 3`、`Local mock` 和原四项底部导航，最近 500 条 logcat 无 FATAL/ANR。
- Boundary：未操作实体手机、真实 Tesla 凭据、正式签名、服务器公网配置或 Git stage/commit/push；真实 Tesla Pilot 仍需域名/DNS/HTTPS、Tesla 审核、正式签名和凭据外部门禁。

# 2026-08-22 生产备份异地上传 fail-closed 收口

## Plan

- [x] 为加密 PostgreSQL 备份增加可配置的 `rclone` 对象存储上传。
- [x] 生产 systemd 日/周任务强制 `--require-upload`，上传缺失或失败时任务失败，不把本机备份当作异地备份。
- [x] 恢复脚本拒绝位于备份目录内的 age 私钥，并保留显式数据库覆盖确认。
- [x] 更新部署 README、systemd 环境模板和接力记录；不在本机伪造对象存储或执行恢复覆盖。

## Review

- PASS：`backup-db.sh`、`restore-db.sh`、`preflight.sh`、`pilot-up.sh`、`write-assetlinks.sh` 在隔离 Bash 环境语法通过；PowerShell 预检/启动/构建/assetlinks 脚本解析通过。
- PASS：Go `test ./... -count=1`、`go vet ./...` 通过；本机 Docker API/PostgreSQL 仍 healthy，`/healthz` 和 `/readyz` 返回成功。
- Boundary：实际对象存储 remote、服务端生命周期规则和独立恢复演练必须在正式服务器上配置后才能勾选；本机没有凭据，也未执行服务器、删除或数据库覆盖。

# 2026-08-22 最终本地门禁复核

## Review

- [x] Go `test ./... -count=1`、`go vet ./...` 通过；Docker `/healthz`、`/readyz` 通过。
- [x] Android Debug/Release 各 `237` tests 通过；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。
- [x] Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量标记为多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- [x] Release APK 为 `com.matelink`，无 `DebugMockLogin`/`debug_mock_login`，未签名 SHA-256 为 `A72EDDA56A3AE13645E710621682007BA5B881117011993F858E2ECA2F48478E`。
- [x] 仅在 `emulator-5554` 覆盖安装 Debug 并启动验证；Dashboard、车辆文案、原底部导航可见，最近 500 条日志无 FATAL/ANR。
- Boundary：未操作实体手机、真实 Tesla 凭据、正式签名、服务器公网配置、真实对象存储、数据库覆盖恢复或 Git stage/commit/push。

# 2026-08-22 法律页面静态发布门禁

## Plan

- [x] Caddy edge 独立静态提供 `/terms/` 和 `/privacy/`，避免法律页面请求落到 API。
- [x] Pilot Compose 挂载仓库法律页面和部署专用 `.well-known` 目录。
- [x] Bash/PowerShell 预检检查本地法律页面；公网验证额外检查三个静态入口。

## Review

- PASS：Pilot Compose `config --quiet` 通过。
- PASS：Caddy `2.8-alpine caddy validate` 通过。
- Boundary：正式运营主体、联系方式、第三方 SDK 清单、正式域名和公网内容检查仍未执行。

# 2026-08-22 有历史数据的原分析页回归证据

## Plan

- [x] 在隔离模拟器 `emulator-5554` 进入原 MateLink More -> Statistics。
- [x] 验证本地 Mock history fixture 的总览、派生结论、数据覆盖和建议内容。
- [x] 验证建议展示样本量、距离覆盖、观测天数、置信度、预计节省、动作和计算方法。
- [x] 保存上下滚动截图；证据标记为 `LOCAL MOCK HISTORY PASS`，不升级为真实 Fleet/Telemetry 证明。

## Review

- PASS：统计总览显示 `420 km`、`215 Wh/km`、`90 kWh`、`58 kWh`、`42.50 ¥` 和 `23` 条来源记录。
- PASS：数据覆盖显示距离/行程能量 `18 / 18`、充电能量/成本 `5 / 5`，并显示观测日期范围 `56 days`。
- PASS：建议页显示高速效率、低温效率和充电损耗三条数据驱动建议，包含阈值、样本量、公里数、观测天数、置信度、节省区间、动作和方法。
- Evidence：`C:/Users/Admin/.codex/visualizations/2026/08/22/jourvolt/matelink-stats-populated.png`、`C:/Users/Admin/.codex/visualizations/2026/08/22/jourvolt/matelink-stats-populated-lower.png`。
- Boundary：仅使用隔离模拟器和本地 Mock history fixture；实体手机、真实 Tesla OAuth/Fleet、正式签名、公网 Pilot 和 Git 发布未执行。

# 2026-08-22 More 页数据状态文案收口

## Plan

- [x] 移除把 TeslaMate 写成实时数据唯一前提的固定文案。
- [x] 保持原 More 页结构、卡片样式和导航不变，改为同时适用于云端和自托管连接的状态说明。
- [x] 运行中英文资源、Release lint/build，并在隔离模拟器验证新文案。

## Review

- PASS：More 页显示 `Data status` / `Live vehicle data is available when connected...`，不再出现旧的 `Verification Status` 或 `TeslaMate connection` 文案。
- PASS：Android Debug/Release 测试、Release lint/build 和模拟器覆盖安装均通过；未操作实体手机。

# 2026-08-22 自包含 Pilot bundle

## Plan

- [x] 允许 Pilot Compose 使用默认完整仓库静态根目录或 bundle 的 `./public`。
- [x] 增加无密钥 `package-pilot.ps1`，复制法律页面、样式、App Link 目录和部署脚本。
- [x] bundle 拒绝仓库内输出、覆盖已有目录和源目录私密 `.env`。
- [x] 将 bundle 服务器执行命令写入 README 和 Pilot 预检文档。

## Review

- PASS：PowerShell 解析通过；临时 bundle 生成通过，`terms/`、`privacy/`、样式、`.well-known/`、Caddy、Compose、manifest 均存在。
- PASS：bundle manifest 明确 `secrets_included=false`，`.env.example` 明确 `JOURVOLT_PUBLIC_ROOT=./public`。
- Boundary：本机只生成/检查临时 bundle，未上传服务器、未写入真实 `.env`、未启动公网 Pilot。

# 2026-08-22 自包含 Pilot bundle 运行烟测

## Plan

- [x] 使用 bundle 结构的隔离 Compose 项目启动 API 与 PostgreSQL。
- [x] 通过 `/healthz` 和 `/readyz` 验证服务健康与就绪。
- [x] 烟测结束后仅清理该临时项目的容器、网络和卷。

## Review

- PASS：`BUNDLE_RUNTIME_HEALTH={"mock_history":false,"mode":"fleet","persistence":"postgres","status":"ok"}`。
- PASS：`BUNDLE_RUNTIME_READY={"mode":"fleet","persistence":"postgres","status":"ok"}`。
- PASS：测试项目 `jourvolt-bundle-smoke-20260822` 已清理；现有本机 Mock 服务未受影响。
- Boundary：使用临时测试配置，不含 Tesla secret；该烟测证明 bundle 的 API/PostgreSQL 启动链，不等同于公网、Tesla OAuth 或真实车辆 Pilot。

# 2026-08-22 Pilot Release 参数化构建收口

## Plan

- [x] 使用计划中的 API 域名、App Link 主机和法律页 HTTPS 根地址运行正式 Pilot APK 构建入口。
- [x] 通过 Release lint、assemble、包名检查和未签名状态检查。
- [x] 保留未签名和未公网验证边界，不把候选 APK 当作可发布产物。

## Review

- PASS：`build-pilot-apk.ps1 -ApiBaseUrl https://api.jourvolt.com/ -AuthHost auth.jourvolt.com -PublicInfoBaseUrl https://api.jourvolt.com/` 成功。
- PASS：产物包名 `com.matelink`，状态 `UNSIGNED_RELEASE`，SHA-256 `7EAE5407DA3538626767FEFAD7CCE5FADF3E0282E7B0F3F8A2008CF32532DE1F`。
- PASS：Release lint/build 通过；当前 `MissingTranslation=0`，无 lint baseline。
- Boundary：`api.jourvolt.com`/`auth.jourvolt.com` 仍未完成 DNS、HTTPS、Tesla 审核和正式签名；该 APK 不能直接分发或证明真实车辆登录。

# 2026-08-22 Release 登录页隔离模拟器边界

## Review

- PASS（只读现有包）：隔离模拟器已有 `com.matelink` `1.4.2`；登录页显示官方 Tesla 授权说明、协议勾选和 `Use Tesla login`，勾选协议后登录按钮可用，最近日志无 FATAL/ANR。
- Boundary：当前未签名候选因与模拟器已有包签名不同而拒绝覆盖安装；没有卸载、清除数据或操作实体手机。因此该 UI 结果不能标记为“当前候选 APK 已安装”，正式签名后需在空模拟器或授权设备重新验证。

# 2026-08-22 目标恢复后的外部状态复核

## Review

- `api.jourvolt.com`：A/AAAA 均未解析。
- `auth.jourvolt.com`：A/AAAA 均未解析。
- Boundary：本次没有启动公网服务、修改 DNS、读取 Tesla 凭据、操作实体手机或执行 Git 发布；真实 Pilot 仍等待外部输入。

# 2026-08-22 登录回流契约保护收口

## Plan

- [x] 增加认证成功后从 Tesla 登录页回到原 Dashboard 的源代码契约测试。
- [x] 增加 Activity 运行期间二次 App Link intent 传入 Compose 的契约测试。
- [x] 重跑 Debug/Release 单元测试、Release lint、Release 构建和 `git diff --check`。

## Review

- PASS：`TeslaAuthNavigationContractTest` Debug/Release 定向测试各 7 项通过；认证状态必须执行 `onLoginSuccess()`，导航必须清除 `TeslaLogin` 返回栈并进入原 `Dashboard`。
- PASS：Activity 必须在 `onNewIntent()` 中更新 intent，保证已运行的 App 能继续处理新的 OAuth 一次性票据。
- PASS：Android Debug/Release 各 239 项 JVM 测试通过，failures/errors/skips 均为 `0`；Release lint/build 通过；`git diff --check` 通过。
- PASS：Release lint 为 258 findings、0 errors、`MissingTranslation=0`、无 baseline；258 只记录多语言覆盖/发布门禁，不描述成运行时 Bug 数量。
- PASS：最新未签名 `com.matelink` APK 位于 `android/app/build/outputs/apk/release/app-release-unsigned.apk`，SHA-256 `BB2A7D64D8B45E9B2DA866E84CD906D47D292C24C80C6858B52425A3FE933AD1`。
- Boundary：本轮只增加回流保护测试，没有安装 APK、操作实体手机、读取 Tesla 凭据、修改 DNS 或执行 Git 发布；真实 Tesla Pilot 外部门禁仍未改变。

# 2026-08-22 当前工作树 Pilot bundle 刷新

## Plan

- [x] 从当前工作树生成新的无密钥自包含 Pilot bundle。
- [x] 核对 manifest、法律页、App Link 目录和 secret 文件边界。
- [x] 用独立 Compose 项目启动 bundle 的 API/PostgreSQL 并验证 health/readiness，完成后清理临时资源。

## Review

- PASS：最新 bundle 位于 `E:/Claude_allow/Download/jourvolt-pilot-bundle-current`，manifest 为 `secrets_included=false`，静态根为 `./public`。
- PASS：bundle 内 `terms/`、`privacy/`、`.well-known/`、Caddy、Compose、预检和备份脚本均存在；`.env`、私钥、证书和 keystore 文件数量为 `0`。
- PASS：隔离 Compose 项目 `jourvolt-bundle-current-smoke-20260822` 的 `/healthz`、`/readyz` 均返回 `status=ok`、`mode=fleet`、`persistence=postgres`；临时容器、网络和卷已清理。
- Boundary：烟测只使用假配置，不含 Tesla secret；没有上传服务器、修改 DNS、启动公网 HTTPS、读取 Tesla 凭据或执行 Git 发布。

# 2026-08-22 隔离模拟器宿主环境门禁

## Review

- BLOCKED（环境）：启动 AVD `MateLink_P0_Qualification_API35` 时，QEMU 曾报告 `too many emulator instances are running`；清理进程后标准/只读启动仍未注册 `emulator-5554`，宿主没有 5554/5555 ADB 监听。
- PASS（静态）：`assembleDebug` 成功；Debug APK 包名为 `com.matelink.test.mock`；`TeslaAuthNavigationContractTest` 7 项通过，0 failures/errors/skips。
- Boundary：没有运行 instrumentation、没有卸载/清除 AVD 数据、没有操作实体手机；已停止本轮启动的隔离 AVD。现有历史模拟器截图不能升级为本次候选 APK 的运行时证明。

# 2026-08-22 临时 AVD 排除数据问题

## Review

- 新建全新 Android 35 临时 AVD `JourVolt_Temp_Verify_20260822` 后，仍未出现 `emulator-5554` 或 5554/5555 ADB 监听，排除原 AVD 用户数据损坏作为主要原因。
- 临时 AVD 已由 `avdmanager delete avd -n JourVolt_Temp_Verify_20260822` 删除，目录不存在；原 `MateLink_P0_Qualification_API35` 未清除或删除。
- Boundary：当前设备运行时回归受宿主 Android Emulator/ADB 环境阻塞；代码构建、契约测试和本地服务证据不受影响。

# 2026-08-22 ADB 连接参数复核

## Review

- 依次复核标准启动、只读启动、`-ports 5554,5555`、`-skip-adb-auth`、`-no-direct-adb` 和显式同版本 `-adb-path`；均未产生可用 `emulator-5554`。
- 结论：当前阻塞在宿主 Android Emulator/ADB 通道，不再继续改动 App 或 AVD 数据来绕过；后续恢复宿主后只需重新安装 Debug APK 即可继续页面回归。

# 2026-08-22 无设备门禁复核

## Review

- PASS：Android Debug JVM `239` tests，failures/errors/skips 均为 `0`；`assembleDebug` 成功。
- PASS：JourVolt Go `test ./... -count=1`、`go vet ./...` 通过；本机 `/healthz`、`/readyz` 返回 `status=ok`、`mode=mock_only`、`persistence=postgres`、`mock_history=true`。
- PASS：`preflight.sh`、`pilot-up.sh`、`backup-db.sh`、`restore-db.sh` 使用 Git Bash `bash -n` 均通过。Windows 系统 `bash.exe` 指向无 bash 的 WSL 环境，不能作为脚本失败证据。
- Boundary：模拟器运行时页面回归仍被宿主 ADB 阻塞；没有运行 instrumentation、没有操作实体手机、没有清除 AVD 数据。

# 2026-08-22 分析页费用与年度报告最小收口

## Plan

- [x] 费用页没有任何价格来源时不渲染空的零值费用图表和地点排行。
- [x] 保留真实免费 `0` 元，并增加缺失价格/免费价格边界测试。
- [x] 年度报告年份筛选改为可横向滚动，图表颜色改用主题令牌。
- [x] 收短英文空状态文案为 `No records yet`，不改变原 MateLink 页面结构。

## Review

- PASS：`CostBreakdownTest` 2 项通过；Debug/Release 各 `241` 项 JVM 测试通过，failures/errors/skips 均为 `0`。
- PASS：`assembleDebug`、`assembleRelease`、`lintRelease`、Go `test ./... -count=1`、`go vet ./...`、Docker `/healthz`/`/readyz` 和 `git diff --check` 通过。
- PASS：Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁，不是运行时 Bug 数量。
- PASS：最新参数化 Pilot Release APK 为 `com.matelink` / `MateLink` / `1.4.2` / versionCode `14`，未签名 SHA-256：`CDD0562955D0746A853DED0A93A1E77173212949A6AC1290893072FA7D09732E`；构建参数为 `https://api.jourvolt.com/`、`auth.jourvolt.com`，归档中无 `debug_mock_login`/`DebugMockLogin` 名称。
- Boundary：实体手机、Tesla 凭据、真实 OAuth/Fleet、服务器公网配置、正式签名、Git stage/commit/push 仍未执行；宿主 Emulator/ADB 仍未注册 `emulator-5554`。

# 2026-08-22 注销后 Tesla 官方撤销回流

## Plan

- [x] 服务端注销后级联删除 JourVolt token/session/车辆数据，并返回按配置区域生成的 Tesla consent 管理页 URL。
- [x] Android 注销成功后仅打开 HTTPS 的官方撤销页，不把本地 token 删除描述成 Tesla 远端自动撤销。
- [x] 增加 Go 区域 URL 测试和 Android 注销回流契约测试。
- [x] 生成新的无密钥 Pilot bundle，并用隔离端口完成 fleet 模式 Compose 烟测。

## Review

- PASS：Go `test ./... -count=1`、`go vet ./...`；Android Debug/Release 各 `243` JVM tests，failures/errors/skips 均为 `0`。
- PASS：`assembleDebug`、`assembleRelease`、`lintRelease`、`git diff --check`；Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline。258 属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：新参数化未签名 APK `com.matelink` SHA-256 `AD95D280FC857F2F39EE121FE9132AF64D1EE048376208E5064C72BB72B82D6A`；新 bundle `E:/Claude_allow/Download/jourvolt-pilot-bundle-revoke-20260822`，敏感文件计数 `0`。
- PASS：bundle Compose 在隔离端口 `18190` 返回 `/healthz`、`/readyz` `status=ok`、`mode=fleet`、`persistence=postgres`，临时资源已清理。
- Boundary：真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名、实体手机和 Git 发布仍未执行；运行时页面回归仍受宿主 Emulator/ADB 阻塞。

# 2026-08-22 注销回流返回地址修正

## Plan

- [x] 保留 Tesla 官方 consent 撤销页的已配置区域域名。
- [x] 将撤销完成后的 `back_url` 指向 JourVolt App Link 域名下的 `/privacy/`，不返回 Tesla 自有隐私页。
- [x] 补充区域域名、JourVolt 回落域名和 HTTPS 路径测试。
- [x] 重新生成无密钥 Pilot bundle，并在隔离端口完成 fleet 模式 Compose 烟测。

## Review

- PASS：Go `test ./... -count=1` 通过；撤销 URL 不携带 token、授权码或 session，`back_url` 为 HTTPS JourVolt `/privacy/`。
- PASS：bundle `E:/Claude_allow/Download/jourvolt-pilot-bundle-app-link-20260822` 的 `secrets_included=false`，敏感文件计数 `0`。
- PASS：隔离 Compose `jourvolt-bundle-app-link-smoke-20260822` 的 `/healthz`、`/readyz` 返回 `status=ok`、`mode=fleet`、`persistence=postgres`，临时资源已清理。
- Boundary：真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名、实体手机和 Git 发布仍未执行。

# 2026-08-22 Room 摘要分析归一化

## Plan

- [x] 让 StatsRepository 在建议与覆盖率计算前经过中性 API 数据映射。
- [x] 让建议距离和充入电量支持缺失值，不强制用零填补。
- [x] 重新通过 Android JVM、构建、lint、Go、Docker health/ready 和 diff check。
- [x] 生成最新无密钥 Pilot 部署输入包。

## Review

- PASS：Android Debug/Release JVM 各 `253` 项，构建和 Release lint 通过。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Go test/vet、Docker health/ready、`git diff --check` 通过；无密钥 bundle `E:/Claude_allow/Download/jourvolt-pilot-bundle-20260822-recommendation` 生成成功。
- Boundary：AVD 页面交互、真实 Tesla OAuth、公网 DNS/HTTPS、服务器、正式签名、实体手机和 Git 发布仍未执行。

# 2026-08-22 建议证据判断补充

## Plan

- [x] 将建议分组中的缺失速度从隐式数值兜底改为显式不可用。
- [x] 增加缺失速度不生成分组证据的单元测试。
- [x] 重跑 Android Debug/Release JVM、构建、Release lint 和 Release 静态扫描。

## Review

- PASS：Debug/Release JVM 各 `253` 项，failures/errors/skips 均为 `0`。
- PASS：`assembleDebug`、`assembleRelease`、`lintRelease` 通过；`MissingTranslation=0`、无 baseline。
- PASS：Release APK 未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`；SHA-256 `F03C42FEEBABE8309BC85D4185366BB00579BB3814C79FCF89701630DEC240F5`。
- Boundary：AVD 页面交互仍 `NOT_PERFORMED`，真实 Tesla Pilot 和服务器外部门禁未执行。

# 2026-08-22 统计与充电缺失值继续收口

## Plan

- [x] 修复统计覆盖日期存在但观测天数缺失时渲染 `0` 天的问题。
- [x] 修复充电汇总在能量/费用字段缺失时渲染 `0 kWh` 或零费用的问题；真实观测到的零值仍保留。
- [x] 为充电图表增加能量覆盖判断，缺少能量时显示不可用而不是伪造零柱。
- [x] 重新执行 Android Debug/Release JVM、构建、Release lint、Go test/vet、Docker HTTP 回归和 `git diff --check`。

## Review

- PASS：Android Debug/Release 各 `246` 个 JVM 用例通过，合计 `492`，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 baseline；该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：最新未签名 APK 为 `com.matelink` / `1.4.2`，SHA-256 `1E28924C7C81CEA02FE31EC63C6F064F60D36970B10BD96C66F5EF21DD945A9D`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- PASS：Docker HTTP 回归为 Mock 登录 → 1 台车辆 → `charging / 76% / mock_fixture` → 18 条行程、5 条充电 → 注销；旧 access 和旧 refresh 均返回 `401`。
- Boundary：页面交互仍为 `NOT_PERFORMED`（宿主 AVD 未注册 `emulator-5554`）；真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名、实体手机和 Git 发布仍未执行。

## 2026-08-22 充电能量边界测试补充

- 新增 `ChargeSummaryMetricTest`，覆盖缺失、NaN、负数能量不可用，以及真实 `0` 能量可用。
- Debug/Release 全量 JVM 各 `248` 项，合计 `496`，failures/errors/skips 均为 `0`；最新生产 APK SHA-256 为 `469C349EDC95F9955B4FF3F7789E73DFC104967A576B94332B1E43718FF0E400`。

# 2026-08-22 本地交付继续收口：电池缺失值与部署输入包

## Plan

- [x] 修复电池详情在缺少 Tesla 电量/续航字段时显示 `0%`、`0 km` 的问题，区分缺失值与真实零值。
- [x] 增加电池状态证据边界测试，并保持原 MateLink 页面结构。
- [x] 重跑 Debug/Release JVM、Debug/Release 构建、Release lint、Go test/vet 与 `git diff --check`。
- [x] 复核 Release 包名、静态 Mock/回环标记、lint 数量和未签名状态。
- [x] 生成当前工作树的无密钥 Pilot bundle，并通过 bundle Compose 配置核对。

## Review

- PASS：Debug/Release 各 `246` 个 JVM 用例，合计 `492`，failures/errors/skips 均为 `0`；定向电池证据测试通过。
- PASS：`assembleDebug`、`assembleRelease`、`lintRelease`、Go `test ./... -count=1`、`go vet ./...` 和 `git diff --check` 通过。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 tracked baseline；258 是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：未签名 `com.matelink` / `1.4.2` APK SHA-256 `333CC50B332008A2F45C1A5C0C34737FE97F3D0FF66DEA0FC6ADD55C91FF5FC0`；静态扫描未命中 Mock、回环地址、Debug 登录或 `com.jourvolt.app` 标记。
- PASS：本机 Docker HTTP 回归为 Mock 登录 → 1 台车辆 → `charging / 76% / mock_fixture` 快照 → 18 条行程、5 条充电 → 注销；旧 token 注销后返回 `401`。
- PASS：`E:/Claude_allow/Download/jourvolt-pilot-bundle-local-completion-20260822` manifest 为 `secrets_included=false`，敏感文件计数 `0`，条款/隐私页存在，bundle Compose 配置通过。
- Boundary：页面运行时回归仍受宿主 Emulator↔ADB 阻塞；真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名、实体手机和 Git 发布仍未执行。

# 2026-08-22 本机 Docker HTTP 回归与 ADB 根因确认

## Plan

- [x] 确认当前 JourVolt API/PostgreSQL Docker 实例健康。
- [x] 执行 Mock 登录、车辆、快照、历史、注销和旧 token 失效的 HTTP 回归。
- [x] 补齐 `ANDROID_SDK_HOME` 后重新尝试隔离 AVD，并读取 QEMU/WHPX 启动证据。
- [x] 使用重启 ADB、外部 adb 和 `-no-direct-adb` 组合确认宿主 ADB 通道问题。
- [x] 将本机服务证据和设备边界同步到项目文档与 Obsidian。

## Review

- PASS：`/healthz`、`/readyz`；Mock 登录成功，车辆 `1` 台，快照 `charging / 76% / mock_fixture`，行程 `18` 条、充电 `5` 条。
- PASS：注销 HTTP `200`，注销后旧 access token 访问车辆接口返回 `401`；token 未写入报告。
- NOT PERFORMED：AVD 虽报告 Android boot completed，但没有 `emulator-5554` 或 5554/5555 监听；未运行 instrumentation、未清除 AVD、未操作实体手机。
- Boundary：该阻塞属于宿主 Emulator↔ADB 通道；真实 Tesla OAuth、域名/DNS/HTTPS、服务器、正式签名和 Git 发布仍待外部条件。

## 2026-08-22 当前 bundle 刷新

- PASS：从当前工作树生成 `E:/Claude_allow/Download/jourvolt-pilot-bundle-runtime-20260822`，`PILOT_BUNDLE=PASS`。
- PASS：manifest `secrets_included=false`，包内携带 `./public` 法律页和部署模板；未打包 `.env`、Tesla secret、证书、私钥或正式 App Link 指纹。
- Boundary：bundle 可作为服务器部署输入，但仍需真实域名/DNS/HTTPS、Tesla 批准/私密配置和正式签名后才能进入真实 Pilot。

# 2026-08-22 正式版 Mock 产物收口与 AVD 门禁复核

## Plan

- [x] 正式版忽略历史 `mock_mode` 偏好，Debug 测试包保留 Mock 行为。
- [x] 正式版对异常 `mock_fixture` 来源 fail-closed，不在 APK 中保留 Mock 标签、回环地址或 Debug 登录标记。
- [x] 重跑 Android Debug/Release 单元测试、Debug/Release 构建和 Release lint。
- [x] 复核 Release 包名、版本、SHA-256、lint findings、MissingTranslation 和 tracked baseline。
- [x] 使用绝对 SDK 路径尝试隔离 AVD 页面回归，并记录未形成 ADB 设备的事实。

## Review

- PASS：`testDebugUnitTest`、`testReleaseUnitTest` 各 `243` 项，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 tracked lint baseline；258 是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Release `com.matelink` / `1.4.2` APK 静态扫描未命中 `127.0.0.1`、`10.0.2.2`、`MockTesla`、`mock_fixture`、`Local mock`、`debug_mock_login` 或 `JOURVOLT_MOCK_LOGIN`；SHA-256 `649C31CDC0932A8D81A2B4050793E12EF7A65FECCD8BA0AD8A6B80CEC789A5FA6`。
- NOT PERFORMED：两次启动隔离 AVD 均未注册 `emulator-5554`；未运行 instrumentation、未清除 AVD 数据、未操作实体手机。页面运行时回归等待宿主 Emulator/ADB 修复。
- Boundary：真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名和 Git 发布仍未执行；本地 Mock、构建和 bundle 烟测不替代这些外部门禁。

# 2026-08-22 分析与充电缺失值继续收口

## Plan

- [x] 分析摘要区分“无记录”和真实观测零值。
- [x] 充电详情区分缺失信号、真实零值和不可计算的效率。
- [x] 重跑 Android JVM、构建、lint、Go test/vet，并确认 Release 静态扫描。
- [x] 将本地结果、AVD `NOT_PERFORMED` 和域名/服务器/SSL 采购门禁同步到 docs 与 Obsidian。

## Review

- PASS：Android Debug/Release JVM 各 `252` 项通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 baseline；这是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Go `test ./... -count=1`、`go vet ./...` 和 Docker Mock HTTP 回归通过；旧 session 注销后返回 `401`。
- NOT PERFORMED：宿主 AVD 没有注册 `emulator-5554`，因此未宣称页面运行时通过；实体手机未操作。
- Boundary：真实 Tesla OAuth、DNS/HTTPS、服务器部署、正式签名和 Git 发布仍等待外部门禁。

# 2026-08-22 账号删除 HTTP 集成契约

## Plan

- [x] 让 PostgreSQL 集成测试实际调用 `DELETE /v1/account` handler，而不是只验证数据库删除函数。
- [x] 校验删除响应中的 Tesla 官方撤销 URL、JourVolt `/privacy/` 回流地址和 HTTPS 边界。
- [x] 运行临时隔离 PostgreSQL 集成测试并清理容器。
- [x] 生成包含当前工作树的无密钥 Pilot bundle。

## Review

- PASS：临时 PostgreSQL 上 `go test ./... -count=1` 通过，包含 OAuth/session/refresh、删除级联和 HTTP 响应契约。
- PASS：删除后旧 access token 不再授权；响应不包含 Tesla token、授权码或 session。
- PASS：bundle `E:/Claude_allow/Download/jourvolt-pilot-bundle-contract-20260822` 生成成功，`secrets_included=false`，敏感文件计数 `0`。
- Boundary：真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名、实体手机和 Git 发布仍未执行。

# 2026-08-22 分析摘要覆盖率归一化与本地门禁

## Plan

- [x] 让 `AnalysisSummary` 消费 `AnalysisCoverage`，避免 Room 旧零占位值进入可用指标。
- [x] 保留真实观测零值，并补充“有记录但无有效样本”的边界测试。
- [x] 重跑 Android Debug/Release JVM、构建、Release lint、Go test/vet、Docker health/ready 和差异检查。
- [x] 重新复制未签名 Release APK，并完成 Mock/回环地址/错误包名静态扫描。
- [x] 记录第三次 AVD 启动尝试仍未注册 ADB，保持页面运行时为 `NOT_PERFORMED`。

## Review

- PASS：Android Debug/Release JVM 各 `254` 项，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 baseline；这是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Go `test ./...`、`go vet ./...`、Docker Mock API health/ready 和 `git diff --check` 通过。
- PASS：Release APK `com.matelink` / `1.4.2` SHA-256 为 `EF0A6E7BD2673E7622159E0FC8AB50BE95726564C967CD13A78341097C74E61C`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。候选文件：`E:/Claude_allow/Download/matelink-1.4.2-release-unsigned-20260822-summary-coverage.apk`。
- NOT PERFORMED：第三次不同 AVD 启动方式仍未形成 `emulator-5554` ADB 设备；未运行 instrumentation、未清除 AVD、未操作实体手机。
- Boundary：真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名、App Link 和 Git 发布仍未执行。

# 2026-08-22 里程证据与电池趋势边界收口

## Plan

- [x] 新增共享 `MileageEvidence`，区分记录数与有效距离/能耗/电量差样本数。
- [x] 让年度、月份、日期、单次行程和图表在字段缺失时显示不可用，不显示假零值。
- [x] 移除电池趋势退化计算中的默认零值兜底，并保留真实零观测。
- [x] 重跑 Android Debug/Release 测试、构建、Release lint、Go test/vet、Docker health/ready 和差异检查。
- [x] 生成新的未签名 Release 候选并完成包名、Mock、回环地址和错误包名静态扫描。
- [x] 同步 Android 实施文档、Pilot 预检、Obsidian 和 lessons。

## Review

- PASS：Android Debug/Release 各 `258` 项 JVM 测试通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 成功。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；这是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Go `test ./...`、`go vet ./...`、本机 Docker `/healthz`/`/readyz` 和 `git diff --check` 通过。
- PASS：Release APK `com.matelink` / `1.4.2` SHA-256 为 `FCCB8DFDC92C783C22C95BACD3E1B7F4435756F2F7EE8F56DE41A0B8220F9566`；候选文件 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-mileage-evidence-v8.apk`，静态扫描 0 命中。
- NOT PERFORMED：ADB 设备列表为空；没有安装、卸载、清数据或 instrumentation。APK 未签名，不能替代正式签名覆盖安装。
- Boundary：真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名、App Link 和 Git 发布仍未执行。

# 2026-08-22 概览卡覆盖率一致性与采购答复

## Plan

- [x] 让统计驾驶/充电概览和年度报告摘要复用 `AnalysisCoverage`。
- [x] 让成本、每百公里和每 kWh 派生值同时要求对应输入证据。
- [x] 重跑 Android、Go、lint、APK 静态扫描和差异检查。
- [x] 记录阿里云轻量应用服务器备案规格与免费 HTTPS 路径。

## Review

- PASS：Android Debug/Release JVM 各 `254` 项，构建、Release lint、Go test/vet 和 `git diff --check` 通过。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 baseline；仍是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：最新未签名 APK `D40A5F7A4C70CAC13C829DCE46C706DB8C0E353EC615CC271B94FD55D5D90F72`，候选文件：`E:/Claude_allow/Download/matelink-1.4.2-release-unsigned-20260822-summary-coverage-v2.apk`；静态扫描无 Mock/回环/错误包名。
- Boundary：页面 AVD 交互仍 `NOT_PERFORMED`；真实 Tesla、公网 DNS/HTTPS、服务器、正式签名、App Link、实体手机和 Git 发布仍未执行。
- Purchase gate：Jovi 可自行购买一个主域名和阿里云中国内地轻量应用服务器 2 核 4G/50 GiB/公网 IPv4；先核对首年不超过 ¥600、正常续费不超过 ¥700/年。SSL 不需另购，采用 Caddy ACME；备案和 Tesla 审核不因购买完成而自动通过。

# 2026-08-22 Release Mock 来源构建隔离与 APK/手机门禁

## Plan

- [x] 让 Mock 来源只在 Debug `BuildConfig` 注入，Release 不携带 `mock_fixture` 字面量并保持 fail-closed。
- [x] 重新执行 Android Debug/Release JVM、Debug/Release 构建和 `lintRelease`。
- [x] 重新执行 Go test/vet、`git diff --check` 和 Release APK 静态扫描。
- [x] 使用 Android SDK `adb.exe` 做设备只读枚举；无设备时不执行安装、卸载、清数据或 instrumentation。
- [x] 将结果同步到 Android 实施文档、Pilot 预检、Obsidian 当前进度和上线计划。

## Review

- PASS：`testDebugUnitTest`、`testReleaseUnitTest` 各 `254` 项，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 全部成功。
- PASS：`lintRelease` 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline。258 是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Go `test ./...`、`go vet ./...` 和 `git diff --check` 通过；Windows 换行提示未被当作错误处理。
- PASS：Release `com.matelink` / `1.4.2` APK SHA-256 为 `B1FF4C3F16991EA0B79969B4E641D222CA88C90BE4339FEC41D636279A161044`；候选文件为 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-pdf-v6.apk`；静态扫描 0 命中 Mock、回环、Debug 登录和 `com.jourvolt.app`。
- NOT_PERFORMED：ADB `devices -l` 为空，没有可核对的手机序列号；未读取包/签名，未安装 APK，未卸载、清数据或运行 instrumentation。下一次必须先让目标手机在 ADB 出现，再进行只读签名门禁。
- Boundary：当前 APK 未签名，不能作为正式分发或同签名覆盖安装证据；真实 Tesla OAuth/Fleet、公网服务器、正式签名、App Link 和 Git 发布仍未执行。

# 2026-08-22 分析空状态与实时字段证据收口

## Plan

- [x] 为分析历史区分无记录、采集中、筛选无记录和字段覆盖不足。
- [x] 将效率、成本、续航和待机页面接入统一空状态判定。
- [x] 阻止电池满电续航在缺少实时 SOC/额定续航时推导，阻止充电详情把缺失字段显示为零。
- [x] 重跑 Android Debug/Release 测试、构建、Release lint、Go test/vet、Docker health/ready 和差异检查。
- [x] 生成新的未签名 Release 候选并完成包名、Mock、回环地址和错误包名静态扫描。
- [x] 同步 Pilot 预检和 Obsidian，保留手机 ADB/正式签名/真实 Tesla 外部门禁。

## Review

- PASS：Android Debug/Release 各 `255` 项 JVM 测试通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 成功。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；这是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Go `test ./...`、`go vet ./...`、本机 Docker `/healthz`/`/readyz` 和 `git diff --check` 通过。
- PASS：Release APK `com.matelink` / `1.4.2` SHA-256 为 `AF71E1E85600C9CA377E532BB0180D8BD5B0DE49981A70DE19F4536FFCDCABD1`；候选文件 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-analysis-state-v7.apk`，静态扫描 0 命中。
- NOT PERFORMED：ADB 设备列表为空；没有安装、卸载、清数据或 instrumentation。APK 未签名，不能替代正式签名覆盖安装。
- Boundary：真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名、App Link 和 Git 发布仍未执行。

# 2026-08-22 里程证据与最终本地门禁（当前最新）

## Plan

- [x] 新增共享 `MileageEvidence`，区分记录数与有效距离/能耗/电量差样本数。
- [x] 让年度、月份、日期、单次行程和图表在字段缺失时显示不可用，不显示假零值。
- [x] 移除电池趋势退化计算中的默认零值兜底，并保留真实零观测。
- [x] 重跑 Android Debug/Release 测试、构建、Release lint、Go test/vet、Docker health/ready 和差异检查。
- [x] 生成新的未签名 Release 候选并完成包名、Mock、回环地址和错误包名静态扫描。
- [x] 同步 Android 实施文档、Pilot 预检、Obsidian 和 lessons。

## Review

- PASS：Android Debug/Release 各 `258` 项 JVM 测试通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 成功。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；这是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Go `test ./...`、`go vet ./...`、本机 Docker `/healthz`/`/readyz` 和 `git diff --check` 通过。
- PASS：Release APK `com.matelink` / `1.4.2` SHA-256 为 `FCCB8DFDC92C783C22C95BACD3E1B7F4435756F2F7EE8F56DE41A0B8220F9566`；候选文件 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-mileage-evidence-v8.apk`，静态扫描 0 命中。
- NOT_PERFORMED：ADB 设备列表为空；没有安装、卸载、清数据或 instrumentation。APK 未签名，不能替代正式签名覆盖安装。
- Boundary：真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名、App Link 和 Git 发布仍未执行。

# 2026-08-22 行程详情与摘要证据收口（当前最新）

## Plan

- [x] 让单次行程详情保留速度、功率、海拔、电池、距离和时长的缺失状态，不显示假零值。
- [x] 让行驶摘要和最高速度图表在缺少速度证据时显示不可用，不把空筛选显示成整组零值。
- [x] 新增详情统计与行驶摘要边界测试，保留真实零值并拒绝矛盾电池差值。
- [x] 重跑 Android Debug/Release JVM、构建、Release lint、Go test/vet、Docker health/ready、静态扫描和 `git diff --check`。
- [x] 复制新的未签名 Release APK，并记录包名、版本、哈希和手机门禁状态。

## Review

- PASS：Android Debug/Release 各 `263` 项 JVM 测试通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 成功。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Go `test ./... -count=1`、`go vet ./...`、Docker `/healthz`/`/readyz` 和 `git diff --check` 通过。
- PASS：Release APK `com.matelink` / `MateLink` / `1.4.2` SHA-256 为 `23973805D55A94E8F09AC0533A16DAB2402809104045AD6059AE168252C784DE`；候选文件 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-drive-detail-evidence-v9.apk`，静态扫描 0 命中。
- NOT_PERFORMED：ADB 设备列表为空；没有安装、卸载、清数据或 instrumentation。APK 未签名，不能替代正式签名覆盖安装。
- Boundary：真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名、App Link 和 Git 发布仍未执行。

# 2026-08-22 设备验证尝试（当前最新）

## Plan

- [x] 读取最新 Obsidian、仓库状态、部署说明和 OAuth/App Link 代码。
- [x] 确认本地 Mock/Go/PostgreSQL、Android 测试、构建、lint 和 Release 静态门禁已有通过证据。
- [x] 重新启动 ADB 并尝试专用模拟器回归，不触碰实体手机数据。
- [x] 将设备未识别、未安装和未运行 instrumentation 的事实同步文档与 Obsidian。
- [ ] 待 Jovi 手机在 `adb devices -l` 出现后，先做包名/签名只读核对，再决定同签名覆盖安装。

## Review

- `adb devices -l` 为空；实体手机未被识别。
- `MateLink_P0_Qualification_API35` QEMU 启动尝试未注册 ADB，随后退出；模拟器回归为 `NOT_PERFORMED`。
- 本轮没有安装 APK、卸载应用、清除数据或运行 instrumentation。
- 最新未签名 Release 候选和哈希沿用上一条行程详情证据；未签名 APK 不替代正式签名覆盖安装。

# 2026-08-22 本地 API 契约与模拟器长时复核（当前最新）

## Plan

- [x] 对本机 Docker Mock API 执行登录、车辆、兼容 ping、能力、refresh 和 logout 契约复核。
- [x] 以 120 秒窗口重试专用 AVD，不清除模拟器数据。
- [x] 将本地 API 通过和 Android/真实 Tesla 证据边界同步到 docs 与 Obsidian。
- [ ] 等待实体手机出现在 ADB，完成包名/签名只读核对和后续覆盖安装门禁。

## Review

- PASS：Mock 登录 `200`、车辆列表 `200`（1 辆）、`/api/ping` `200`、能力接口 `200`。
- PASS：refresh token 轮换后旧 access token `401`、新 token `200`；logout 后 token `401`，未输出 token 值。
- NOT_PERFORMED：专用 AVD 120 秒仍未注册 ADB；没有安装 APK、卸载、清数据或 instrumentation。
- Boundary：本地 Mock API 通过不等于 Android 页面运行时或真实 Tesla OAuth/Fleet 通过；正式签名、实体设备和外部 Pilot 门禁仍未完成。

# 2026-08-22 App Link ticket replay 防护（当前最新）

## Plan

- [x] 防止 Activity 重建或重复 App Link 重复交换一次性 Tesla callback ticket。
- [x] 为新 ticket、in-flight 重复、已处理重复和空 ticket 增加边界测试。
- [x] 重跑 Android Debug/Release JVM、assembleDebug、assembleRelease 和 lintRelease。
- [x] 生成并扫描新的未签名 Release 候选，保持原 MateLink 包名和 Release Mock fail-closed。
- [x] 同步 docs、Pilot 预检、Obsidian 和 lessons。

## Review

- PASS：Android Debug/Release 各 `267` 项 JVM 测试通过，failures/errors/skips 均为 `0`。
- PASS：`assembleDebug`、`assembleRelease`、`lintRelease` 成功；Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Release 静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- PASS：最新未签名 Release SHA-256 为 `299C56B94F8DAF8F2505D9C5DD77FD5CE7B0105CFCFBDF2E9C7A75F87F6B6925`，候选 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-callback-replay-guard-v10.apk`。
- NOT_PERFORMED：ADB 为空；没有安装、卸载、清数据或 instrumentation。真实 Tesla OAuth/Fleet、正式签名和服务器仍未完成。

# 2026-08-22 OAuth callback 并发状态隔离后的最终本地门禁

## Review

- PASS：`TeslaLoginViewModel` 只允许当前 callback ticket 的失败回调写入 Error，旧请求不会覆盖新请求状态；与 in-flight/已处理 ticket 防重复保护一起收口 Activity 重建和重复 App Link 边界。
- PASS：Android Debug/Release 各 `267` 个 JVM 测试通过，failures/errors/skips 均为 `0`；`testDebugUnitTest`、`testReleaseUnitTest`、`assembleDebug`、`assembleRelease`、`lintRelease` Gradle 退出码均为 `0`。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Release APK 静态扫描未命中回环地址、Mock provider、Mock 登录标记或 `com.jourvolt.app`；未签名候选 SHA-256 为 `541F88C8C6AED2833C47093E86C767224983D5FC4D8879E499880C54DC326221`，文件为 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-callback-replay-guard-v12.apk`。
- NOT_PERFORMED：ADB 仍为空；没有安装、卸载、清数据或 instrumentation。正式签名、服务器/域名、Tesla 应用批准和真实 OAuth/Fleet 仍未完成。

# 2026-08-23 手机隔离 Mock 测试包准备（v36）

## Plan

- [x] 重新执行 Go test/vet 与 Docker smoke，确认本地服务仍为 `LOCAL MOCK PASS`。
- [x] 生成只通过 ADB reverse 访问本机 Docker 的实体测试设备隔离 Debug APK。
- [x] 只读核对包名、SHA-256 和设备 ADB 状态；不安装、不卸载、不清数据、不运行 instrumentation。

## Review

- PASS：Go `test ./... -count=1`、`go vet ./...`、Docker `smoke.ps1`；1 台模拟车、18 条行程、5 条充电、注销回收通过。
- PASS：`E:\Claude_allow\Download\matelink-test-mock-debug-20260823-phone-reverse-v35.apk`，包名 `com.matelink.test.mock`，SHA-256 `F9E6CD56C7D0FB946262F68945D4C73354A895486458379338B515CD25C8A5C3`；仅用于隔离 Mock 手工回归。
- NOT_PERFORMED：`adb devices -l` 仍为空；没有安装、卸载、清数据或 instrumentation。正式 `com.matelink` Release 仍未签名。

## Next

- Jovi 连接手机并开启 USB 调试后，先让我复查 `adb devices -l`；设备出现后再使用 `adb reverse tcp:18090 tcp:18090` 和该隔离包做手工 Mock 登录→原 Dashboard 回归。
- 真实登录仍需正式签名、受控域名/HTTPS、Tesla 应用批准、App Link 和私密配置；本地 Mock 不能代替真实 Tesla 证据。

# 2026-08-23 无密钥 Pilot 部署包复建（v37）

## Plan

- [x] 使用当前 Go 服务源码重新生成自包含 Pilot bundle。
- [x] 核对 manifest、目录和 ZIP，不把示例配置模板误判为真实密钥。
- [x] 固定 ZIP SHA-256，并确认未启动公网服务、未读取 Tesla 密钥。

## Review

- PASS：bundle `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260823-v36`，manifest `secrets_included=false`。
- PASS：ZIP `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260823-v36.zip`，SHA-256 `9A4E3B3556AA5AD96D18B5C390B555922F6EAAE401909777A8344CD484D0E5EF`；目录和 ZIP 的实际敏感条目均为 `0`。
- PASS：Bundle 内 Go test/vet 通过；默认 Compose 与 Pilot Compose 使用进程级占位值完成结构校验，临时值未写入 bundle、Git 或部署配置。
- NOT_PERFORMED：没有服务器、公网 DNS/HTTPS、Tesla 应用批准、正式签名或真实单车授权；bundle 仅是无密钥部署输入。

# 2026-08-23 部署预检 fail-closed 复核（v38）

## Review

- PASS：bundle 内 Go `test ./... -count=1`、`go vet ./...` 通过。
- PASS：`preflight.ps1 -EnvFile .env.example -SkipCompose` 按实际脚本参数运行；示例配置按预期返回 `PREFLIGHT=FAIL`，未启动服务。
- PASS：失败原因明确包含 Tesla 配置缺失、示例域名/占位值、令牌密钥缺失和正式 App Link 缺失；未将该结果误报为部署成功。
- NOTE：此前误用的不存在参数 `-SkipDocker` 不计入验证结果；未写入密钥、未改 DNS、未操作手机。

## Next

- [ ] ADB 出现授权设备后，使用隔离 `com.matelink.test.mock` APK 做实体 Mock 手工回归。
- [ ] Jovi 提供正式签名、受控域名/HTTPS、Tesla 应用批准和私密配置后，执行真实 Pilot 门禁。

# 2026-08-23 电池趋势曲线补强与 v39 门禁

## Review

- PASS：原电池趋势卡增加按日期中位数聚合的标准化续航曲线；无新页面、无实测容量误称。
- PASS：Android Debug/Release 各 `277` 个 JVM 用例通过，失败/错误/跳过均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- PASS：Release lint `194` 项、0 Error、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：v39 Release 与隔离 Debug APK 已归档，正式包未签名，隔离包不覆盖 `com.matelink`。

## Next

- [ ] ADB 出现授权设备后，安装 v39 隔离 Debug 并做原 Dashboard 手工 Mock 回归。
- [ ] 正式签名、受控域名/HTTPS、Tesla 应用批准和私密配置具备后，执行真实 Pilot 门禁。

# 2026-08-23 原统计分析卡充电损耗证据补强（v34）

## Plan

- [x] 保持原 MateLink 统计页视觉、导航和卡片结构不变，仅补充充电损耗分析。
- [x] 复用已有推荐引擎的成对样本规则，不为统计卡引入第二套充电损耗算法。
- [x] 将电网能量覆盖率、损耗证据覆盖率和保守样本数接入原分析卡。
- [x] 补充缺失、低于关系、零值和多条样本的领域测试。
- [x] 完成 Android Debug/Release 测试、构建、lint 和实际差异检查。

## Review

- PASS：充电损耗只在电网消耗与电池充入同时有效且电网消耗不小于电池充入时计算；未知数据不降级为零或免费。
- PASS：Debug/Release 各 `275` 个 JVM 用例通过，failures/errors/skips 均为 `0`。
- PASS：`assembleDebug`、`assembleRelease`、`lintRelease` 通过；Release lint `194` 项、0 Error、`MissingTranslation=0`、无 baseline。该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：`git diff --check` 实际问题行数为 `0`；v34 APK 已归档，Release 未签名、Debug Mock 为隔离包。
- PASS：额外生成云登录配置候选，`JOURVOLT_CLOUD_LOGIN=true`、API/App Link 均为计划 HTTPS 地址、Mock 登录为 `false`，包名仍为 `com.matelink`；候选未签名且域名未上线。
- NOT_PERFORMED：ADB 仍为空；未安装、卸载、清数据或 instrumentation。真实 Tesla、公网 HTTPS、正式签名和实体设备 UI 仍未完成。

## Next

- [ ] Jovi 让手机以 USB 调试授权状态出现在 `adb devices -l`，并提供原 `com.matelink` 正式签名后，才执行同签名覆盖安装。
- [ ] 继续等待受控域名/HTTPS、Tesla 应用批准、App Link 数字资产和私密配置，之后才能做真实 Pilot。

# 2026-08-23 待机耗电窗口证据收口（v35）

## Plan

- [x] 为充电间隔待机候选增加至少 2 小时资格门槛。
- [x] 保持无容量/Telemetry 证据时不推导 kWh 或平均功率。
- [x] 补充短窗口、边界时长和非有限值测试。
- [x] 重跑 Debug/Release 测试、构建、lint 和差异检查。
- [x] 生成普通 Release 与开启云登录的 Pilot 配置候选，并核对包名/Mock/回环边界。

## Review

- PASS：待机窗口小于 2 小时不进入 `IdleDrainPeriod`；2 小时及以上才具备候选资格。
- PASS：容量未知仍保持 SOC-only，未恢复固定 75kWh 或其他默认容量。
- PASS：Debug/Release 各 `276` 个 JVM 用例通过，failures/errors/skips 均为 `0`。
- PASS：Release lint `194` 项、0 Error、`MissingTranslation=0`、无 baseline；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：普通 Release 和云登录候选均为 `com.matelink`，云候选 Mock=false、无 loopback；Release 均未签名。
- NOT_PERFORMED：ADB 仍为空；未安装、卸载、清数据或 instrumentation。真实 Tesla、公网 HTTPS、正式签名和实体设备 UI 仍未完成。

## Next

- [ ] Jovi 让手机出现在 `adb devices -l`，并提供原 `com.matelink` 正式签名后，才执行同签名覆盖安装。
- [ ] 继续完成域名/DNS/HTTPS、Tesla 应用批准和私密配置，才能进行真实单车 OAuth/Fleet。

## 采购核对（不改变当前代码门禁）

- 最小部署采购：一个可备案主域名 + 一台有公网 IPv4 的大陆 Linux 服务器；API/Auth 使用同一主域名子域，第二个 `.com`/`.cn` 域名属于品牌保护，不是技术必需。
- 当前核到的阿里云官方轻量页 2 核 4G/40GB/2M 年付约 1733.04 元，不满足首年 600 元门禁；网上 199 元文章不作为购买依据。下单前仍确认正常续费不超过 700 元/年、至少 50GB 磁盘和公网 IPv4。
- 不购买付费 SSL；Pilot Caddy 使用 ACME/Let's Encrypt 免费 DV 证书自动签发与续期。ICP备案、App备案、Tesla批准、正式签名、App Link 和真实 Pilot 仍分别验收。

# 2026-08-22 本地服务端注销回归与全兼容路由巡检（v33）

## Review

- PASS：修复 Mock/未配置 Tesla OAuth 模式下 `DELETE /v1/account` 的空 OAuth 解引用；现在正常删除账号，且不虚构 Tesla 撤销链接。
- PASS：新增 `TestAccountDeletionWorksWhenTeslaOAuthIsNotConfigured`；Go `test ./... -count=1`、`go vet ./...` 通过。
- PASS：16 条 Android Retrofit 兼容路径全量本机巡检通过；18 条行程、5 条充电、注销回收和未配置 OAuth 注销均通过，结果为 `LOCAL COMPATIBILITY PASS`。
- PASS：现有 Docker smoke 继续为 `LOCAL MOCK PASS`；所有结果仍属于本地 Mock/兼容接口证据，不等于真实 Tesla OAuth/Fleet、公网部署、正式签名或实体设备 UI。
- PASS：v33 无密钥 bundle 已生成，敏感文件计数 `0`；ZIP `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v33.zip`，SHA-256 `432A12F3B3B3CCC0859D14580473C4B6FF4D5B45AD3E7747EB3872F422CAA964`。
- PASS：服务端重建后 Docker `smoke.ps1` 仍为 `LOCAL MOCK PASS`；全 16 条兼容路由巡检仍为 `LOCAL COMPATIBILITY PASS`。
- PASS：Android 当前工作树全门禁复验：Debug/Release 各 `273` 项、失败 0，`assembleDebug`、`assembleRelease`、`lintRelease` 通过；Release lint `194` 项、`MissingTranslation=0`、无 baseline、0 Error。
- PASS：最新未签名 Release APK：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-server-fix-v33.apk`，SHA-256 `726C2815106E8C0E09C2998B6098B014A5CD4BC56F1E51DBFCDB1DD49DC2121B`；不能覆盖正式签名 App。

## Next

- [ ] Jovi 提供可用的实体设备 ADB 连接后，才执行同签名 `adb install -r` 和受控页面验证。
- [ ] Jovi 提供正式签名、受控域名/HTTPS、Tesla 应用批准与私密配置后，进入真实 Pilot 预检。

# 2026-08-22 综合分析证据与样本量补强（v30）

## Review

- PASS：原统计分析卡布局保持不变；可用指标依据行补充样本数，显示“观测/派生/估算 + n”。
- PASS：效率、每百公里费用、充入/行驶能量比改用配对输入覆盖率的保守最小样本数；新增测试覆盖不完整覆盖场景。
- PASS：Android Debug/Release 各 `273` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`lintRelease` 通过，194 项 finding、`MissingTranslation=0`、无 baseline、0 Error。该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Release APK `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-evidence-samples-v30.apk`，SHA-256 `3C962C851C5BAE9EF4DB42AFE660A27C219D7ED9C8C47E27384B3CE7812D29F8`；Debug APK `E:\Claude_allow\Download\matelink-test-mock-debug-20260822-evidence-samples-v30.apk`，SHA-256 `5650ECE2450BD7D69F8C2E94FED21FD277C823F650336959D54C9E6F18EA1977`。
- NOT_PERFORMED：Release 没有正式签名，ADB 仍为空；未安装、卸载、清数据或 instrumentation。真实 Tesla OAuth/Fleet、DNS/HTTPS、App Link 和正式签名仍是外部门禁。

# 2026-08-22 Docker 与 Pilot bundle 当前复验（v32）

## Review

- PASS：Go `test ./... -count=1`、`go vet ./...` 和 `deploy/jourvolt-dev-mock/smoke.ps1` 通过；`LOCAL MOCK PASS`、health/ready `ok`、1 台车辆、18 条行程、5 条充电、注销回收通过。
- PASS：生成最新无密钥 bundle `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v32`，manifest `secrets_included=false`，敏感文件计数 `0`。
- PASS：ZIP `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v32.zip`，SHA-256 `5995469FF438E4CE96B0E9420937A0F8F3DECF95967BEF0D2AFEE07F94ED956B`。
- NOT_PERFORMED：未填写 Tesla 密钥、未启动公网服务器、未进行 DNS/HTTPS、正式签名、App Link 或真实车辆授权；本地 Mock 证据不等于真实 Tesla 证据。

# 2026-08-22 年度报告货币统一（v31）

## Review

- PASS：年度报告历史实现中的 `€`/`¥` 写死路径已统一为当前货币符号；活动年度报告、PDF 和历史参考代码不再固定某个币种，原布局不变。
- PASS：Android Debug/Release 各 `273` 个 JVM 用例通过，failures/errors/skips 均为 `0`；assembleDebug、assembleRelease、lintRelease 通过。
- PASS：Release lint `194` 项，`MissingTranslation=0`、无 baseline、0 Error；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。货币静态扫描只剩 `Currency` 枚举的合法符号定义。
- PASS：Release APK `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-currency-unification-v31.apk`，SHA-256 `700D2ABF0AB65E183A6AC6FCB9D4808823772270AF371B0631329947ECF20FAF`；Debug APK `E:\Claude_allow\Download\matelink-test-mock-debug-20260822-currency-unification-v31.apk`，SHA-256 `47F4D5F9F01E8E26959DE254A1D2C76FABF40E050DC6FF85BAE529CC5E639921`。
- NOT_PERFORMED：Release 没有正式签名，ADB 仍为空；未安装、卸载、清数据或 instrumentation。真实 Tesla OAuth/Fleet、DNS/HTTPS、App Link 和正式签名仍是外部门禁。

# 2026-08-22 原统计分析卡补强与 v29 APK

## Review

- PASS：保留原 MateLink 统计页和视觉风格，仅在已有“使用模式”区加入日均驾驶里程、行程次数；没有新增壳页面或重做主题。
- PASS：日均驾驶里程使用有效总里程除以观测驾驶天数，输入不足返回不可用；新增领域测试覆盖正常样本、零值和采集占位状态。
- PASS：Android Debug/Release 各 `272` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`lintRelease` 通过，194 项 finding、`MissingTranslation=0`、无 baseline、0 Error。该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Release 源码扫描未命中 `com.jourvolt.app`、回环地址、Mock 登录标记；Release 为 `com.matelink`，Debug Mock 为隔离包 `com.matelink.test.mock`。
- PASS：Release APK `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-daily-distance-v29.apk`，SHA-256 `CD8CA469D9DE91CB4F20EA41D5666BF4A256E6B85FA1E70C121098D7B1789310`；Debug APK `E:\Claude_allow\Download\matelink-test-mock-debug-20260822-daily-distance-v29.apk`，SHA-256 `7C7DA7485B0726A4015A3709FB08DC9585D2098027D0D5F0FCA6FA59B61FF0AA`。
- NOT_PERFORMED：Release 没有正式签名，ADB 仍为空；未安装、卸载、清数据或 instrumentation。真实 Tesla OAuth/Fleet、DNS/HTTPS、App Link 和正式签名仍是外部门禁。

# 2026-08-22 综合分析使用模式补强与 v20

## Plan

- [x] 在原有综合分析卡内增加平均行程时长、驾驶天数、平均充电时长和记录最高速度。
- [x] 保持原 MateLink 卡片、导航和主题；未知值继续显示“暂无记录”，不降级为零。
- [x] 统一 Stats 页面新增格式化的 Locale，补齐中英文资源。
- [x] 重跑 Android Debug/Release 单元测试、构建、Release lint 和差异检查。

## Review

- PASS：Debug/Release 各 `269` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 成功。
- PASS：Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：新未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-usage-summary-v20.apk`，包名 `com.matelink`，SHA-256 `ED7E78E6978C2B5D08E5F5B37E15D0E798193F0494414BCA0B08E37AE8C702E9`。
- PASS：隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-usage-summary-v20.apk`，包名 `com.matelink.test.mock`，SHA-256 `2E13F49F5C2C63B8C8C9C0280BA044B23FEFE99B8E25D4E4172538A56927E149`。
- NOT_PERFORMED：Release 仍未签名；`adb devices -l` 仍为空，未安装、卸载、清数据或 instrumentation。真实 Tesla OAuth/Fleet、正式签名和公网服务仍未完成。

## Boundary

- v20 只证明本地源码构建和静态/单元门禁，不证明实体设备 UI、正式覆盖安装或真实车辆数据。
- 当前混合工作树保持原状，不 reset、stage、commit 或 push。

# 2026-08-22 Tesla 授权 URL fail-closed 安全门禁与 v21

## Plan

- [x] 在打开 Custom Tab 前限制 Tesla 授权 URL 为官方 HTTPS 域名和 `/oauth2/v3/authorize` 路径。
- [x] 校验 `client_id`、`redirect_uri`、`response_type=code` 以及 `openid`/`offline_access` 最小 scope。
- [x] 增加伪造域名、错误协议、错误路径和缺失 scope 的 JVM 测试。
- [x] 重跑 Debug/Release 测试、构建、Release lint 和差异检查。

## Review

- PASS：Debug/Release 各 `271` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 成功。
- PASS：Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：v21 未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-tesla-url-guard-v21.apk`，包名 `com.matelink`，SHA-256 `C2CBC72BF8C462971A2C866DD203538629D21EE3890B31C9E9FA65F22FB16C1C`。
- PASS：v21 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-tesla-url-guard-v21.apk`，包名 `com.matelink.test.mock`，SHA-256 `2A38A330CE9DEBBCC3010E630BAE7C9323C29E0BF6EA5F69A28FD1B71B55A0C2`。
- NOT_PERFORMED：Release 未签名；`adb devices -l` 仍为空，未安装、卸载、清数据或 instrumentation。真实 Tesla OAuth/Fleet、正式签名和公网服务仍未完成。

# 2026-08-22 当前最终门禁（v14）

## Review

- PASS：正式 Tesla 登录 start/callback 与 Debug Mock 登录均具备请求代次隔离；取消的旧请求不会覆盖新状态、打开旧授权页或写回旧 session。
- PASS：Android Debug/Release 各 `269` 个 JVM 用例通过，failures/errors/skips 均为 `0`；构建、Release lint、Go test/vet、Docker health/ready 和 `git diff --check` 通过。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；这是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：最新未签名 Release APK 为 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-login-generation-v14.apk`，包名 `com.matelink`，SHA-256 `3C7588D70F418E9C29124BCFB2A7D0C0CAC7866A34B808D743923EFBCD8C86BB`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- PASS：隔离 Debug 测试包为 `E:\Claude_allow\Download\matelink-test-mock-debug-20260822-v1.apk`，包名 `com.matelink.test.mock`，SHA-256 `FF1C4D080F3F9F2A2572836A9244F8089A2F02786683852ED893754AB9F19EA4`，不进入 Release。
- NOT_PERFORMED：ADB 仍为空；没有安装、卸载、清数据或 instrumentation。正式签名、服务器/域名、Tesla 应用批准和真实 OAuth/Fleet 仍未完成。

# 2026-08-23 本地回归门禁复跑（v41）

## Review

- PASS：Android Debug/Release 合计 `554` 个 JVM 用例，失败/错误/跳过均为 `0`；Debug/Release 构建成功。
- PASS：Release lint `194` 项、0 Error、`MissingTranslation=0`、无 baseline；该数量是静态质量/多语言覆盖/发布门禁提醒，不是运行时 Bug 数量。
- PASS：Go test/vet 与本地 Mock smoke 通过：`LOCAL MOCK PASS`、1 台车辆、18 条行程、5 条充电、注销回收通过。
- PASS（预期拒绝）：`.env.example` 的 Pilot preflight 返回 `PREFLIGHT=FAIL`，拒绝示例域名、Tesla 配置、令牌密钥和正式 App Link，未启动公网服务。
- NEXT：只剩外部 Pilot 门禁：真实域名/DNS/HTTPS、服务器私密配置、Tesla 应用批准、正式签名和 App Link；当前没有把本地证据写成真实车辆交付。

# 2026-08-23 实体统计页数据回归（v42）

## Review

- PASS：实体 `com.matelink` 打开“更多 → 统计概览”，行程、充电、交流/直流比例和温度统计均可见。
- PASS：统计页在费用输入不可用时显示 `N/A`，没有把未知成本转换为 `0`。
- EVIDENCE_BOUNDARY：该页面使用手机已有自托管历史；本次仅导航截图，不证明真实 Tesla OAuth/Fleet。

# 2026-08-23 原 MateLink 覆盖升级策略确认（v43）

## Review

- PASS：按 Jovi 明确授权，仅卸载 `com.matelink.test.mock`；正式 `com.matelink` 保留并成功启动。
- DECISION：后续设备交付只接受原签名、包名 `com.matelink` 的 Release APK；执行 `adb install -r` 覆盖升级，保留 Room、DataStore、服务器地址、Token 和历史数据。
- GATE：正式 keystore 未提供前不构建或安装覆盖升级 APK；未签名 Release 或 `.test.mock` Debug 绝不能冒充升级包。

# 2026-08-23 原包签名覆盖与迁移修复（v44）

## Review

- PASS：原包公开签名指纹已核对，修复版 `com.matelink` 使用相同证书签名；`adb install -r` 成功。
- PASS：测试包 `com.matelink.test.mock` 已删除；正式 `com.matelink` 保留，未清数据/卸载。
- PASS：迁移修复测试加入后，Debug/Release 合计 `556` 个 JVM 用例通过；lint `194`、0 Error、`MissingTranslation=0`、无 baseline。
- BOUNDARY：原 API 地址和 Token 掩码仍保留；旧自托管实时连接测试返回服务暂时无法访问，实时车辆刷新未宣称通过。

# 2026-08-23 局域网 HTTP 与 AMap Release 修复（v45）

## Review

- PASS：可信局域网 HTTP 兼容资源已加入，公网 HTTP 仍由 `UrlSecurity` 拒绝。
- PASS：AMap JNI/反射类 R8 keep 规则已加入；最终实体冷启动进程存活，Dashboard 显示原车辆数据和地图，不再发生 AMap native 崩溃。
- PASS：最终签名 `com.matelink` APK 已通过 `adb install -r` 覆盖安装；测试包已删除。
- PASS：556 个 JVM 测试通过，失败/错误/跳过均为 0；lint `195`、0 Error、`MissingTranslation=0`、无 baseline。新增 1 条 `InsecureBaseConfiguration` 是可信局域网 HTTP 的静态提醒。

# 2026-08-23 公网 DNS/HTTPS Pilot 门禁复核（v46）

## Review

- NOT_READY：JourVolt 两个域名解析到 `198.18.0.x` fake-IP，HTTPS `/healthz` 超时；没有把它记录为真实公网服务。
- PASS：正式 `com.matelink` 仍在手机运行，测试包不存在；本轮未改 DNS、服务器或 Tesla 凭据。
- NEXT：需要真实公网 A 记录、HTTPS、正式 `assetlinks.json` 和 Tesla OAuth callback 后，才能执行真实 Pilot。

# 2026-08-23 Release 底部导航路由修复（v47）

## Review

- PASS：修复 R8/类型安全 Navigation 路由前缀失配，底部导航不再因未知 route 直接返回空。
- PASS：实体设备验证“仪表盘 / 行程 / 充电 / 更多”四个入口均可导航。
- PASS：560 个 JVM 测试通过，失败/错误/跳过均为 0；lint `195`、0 Error、`MissingTranslation=0`、无 baseline。
- APK：`E:\Claude_allow\Download\matelink-1.4.2-release-signed-nav-routenormalized-20260823.apk`，SHA-256 `A13F9B7BDE203D379C9249D0F11C40EA1FF40B144B1C43ADDF56272924F754F9`。

# 2026-08-23 实体设备隔离包安装验证（v40）

## Review

- PASS：ADB 识别 OnePlus 7 Pro（`6e4fa92f`）；`adb reverse tcp:18090 tcp:18090` 成功。
- PASS：v39 Debug 以 `adb install -r` 安装成功；`com.matelink.test.mock` 与 `com.matelink` 同时存在，正式包未被覆盖。
- PASS：隔离包启动并显示原 MateLink Dashboard、车辆卡片和底部导航；实体截图已保存至 `E:\Claude_allow\Download\matelink-test-mock-v39-phone-dashboard.png`。
- NOT_PERFORMED：未清数据、未卸载、未运行 instrumentation；既有测试会话使本次结果不能证明新鲜登录、真实 Tesla OAuth/Fleet 或正式 Pilot。

# 2026-08-22 云端配置候选构建（v26）

## Review

- PASS：使用 `android/build-pilot-apk.ps1` 生成了绑定 `https://api.jourvolt.com/`、App Link host `auth.jourvolt.com` 和公开信息根地址 `https://api.jourvolt.com/` 的 Release 配置候选；构建脚本执行 `assembleRelease` 与 `lintRelease` 成功。
- PASS：`aapt dump badging` 核对包名为 `com.matelink`、版本 `1.4.2`、versionCode `14`、应用名 `MateLink`；生成文件为 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-pilot-config-v26.apk`，SHA-256 `B427F37EA42219A95E4878B154D1E8A824206098AF44615BA195D0B9E1B8886A`。
- PASS：Release BuildConfig 已写入上述 HTTPS API 根地址和 `auth.jourvolt.com`；没有把 loopback 调试地址写入该 Release 配置候选。
- PASS：v24 Android 授权 URL/redirect_uri 安全测试与 v25 Go 回调路径配置测试仍作为当前本地安全基线；v25 Go `test ./... -count=1`、`go vet ./...` 和 Docker smoke 已通过。
- NOT_PERFORMED：`apksigner verify` 明确失败于未签名产物（缺少 `META-INF/MANIFEST.MF`）；没有正式 keystore/签名证书，因此不能覆盖安装到已有正式签名的 `com.matelink`。
- NOT_PERFORMED：`api.jourvolt.com`/`auth.jourvolt.com` 尚未由本地验证 DNS、公网 HTTPS、Tesla 应用批准或真实 OAuth/Fleet；本 APK 只是配置候选，不是云登录或真实车辆交付证明。
- NOT_PERFORMED：`adb devices -l` 仍为空；没有安装、卸载、清数据或 instrumentation。

# 2026-08-22 当前工作树复验（v27）

## Review

- PASS：当前 `deploy/jourvolt-dev-mock/smoke.ps1` 重新通过：`LOCAL MOCK PASS`，health/ready 为 `ok`，1 台车辆 `charging / 76% / mock_fixture`，18 条行程、5 条充电，`logout_revocation=PASS`。
- PASS：Go `test ./... -count=1` 与 `go vet ./...` 通过；Android `testDebugUnitTest` 与 `testReleaseUnitTest` 均通过，各 `272` 个测试，failures/errors/skips 均为 `0`。
- PASS：v26 配置候选仍保留在 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-pilot-config-v26.apk`，未重新签名或覆盖原 APK。
- NOT_PERFORMED：`adb devices -l` 仍为空；未安装、卸载、清数据或 instrumentation。真实 Tesla OAuth/Fleet、正式签名、DNS/HTTPS 和公网 Pilot 仍待外部门禁。

# 2026-08-22 AVD ADB 通道复核（v28）

## Review

- NOT_PERFORMED：ADB server 重启后正常监听 `127.0.0.1:5037`，但 `MateLink_P0_Qualification_API35` 两次启动均只在日志中报告 Android `Boot completed`，没有注册 `emulator-5554`，也没有开放预期的 5554/5555 端口。
- NOT_PERFORMED：第二次使用显式 `-ports 5554,5555` 启动仍相同；诊断进程已停止，未删除 AVD、未 wipe data、未安装 APK、未运行 instrumentation。
- Boundary：这是 AVD/ADB 宿主通道问题，不是 MateLink 页面或登录逻辑失败；本地 JVM、Docker 和 Go 证据不受影响。

# 2026-08-22 登录配置错误文案资源化与 AVD 通道复核（v23）

## Review

- PASS：将云登录未配置时的英文硬编码替换为中英文资源 `tesla_login_error_not_configured`；Release 和 Debug 登录路径保持原行为，不增加假登录。
- PASS：Android Debug/Release 各 `271` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- PASS：Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error；该数量继续按多语言覆盖/发布门禁问题记录，不是运行时 Bug 数量。
- PASS：当前 Docker smoke 仍为 `LOCAL MOCK PASS`：health/ready 为 `ok`，1 台车辆 `charging / 76%`，18 条行程、5 条充电，`logout_revocation=PASS`。
- PASS：v23 Debug APK：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-localized-login-errors-v23.apk`，包名 `com.matelink.test.mock`，SHA-256 `FD0421406419C0073BB6FF863A1E197502C452B4C89ED1D1D914B928E762E50D`；Release 候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-localized-login-errors-v23.apk`，包名 `com.matelink`，SHA-256 `1EE6F45FC1D4BF8E45AD81005CE9C8208F07E507F4FFDD0A5A33E65BE7B94DBE`。
- NOT_PERFORMED：手机 ADB 仍为空；专用 AVD 本次启动后没有监听 5554/5555，已停止刚才启动的明确进程，未删除 AVD、未清数据、未安装 APK、未运行 instrumentation。Release 未签名，正式签名、域名/HTTPS、Tesla 应用批准和真实 OAuth/Fleet仍待外部门禁。

# 2026-08-22 Tesla redirect_uri 归属 fail-closed 与 v24 门禁

## Review

- PASS：Tesla 官方授权 URL 现在除官方 HTTPS 端点和最小 scope 外，还必须携带回到配置 JourVolt App Link host、`/oauth/callback` 的 HTTPS `redirect_uri`；非 JourVolt 回调地址在 Custom Tab 打开前拒绝。
- PASS：新增非 JourVolt redirect_uri 边界测试；Android Debug/Release 各 `272` 个 JVM 用例通过，failures/errors/skips 均为 `0`。
- PASS：`assembleDebug`、`assembleRelease`、`lintRelease`、Docker smoke 和 `git diff --check` 通过；Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error，仍按多语言覆盖/发布门禁问题记录。
- PASS：v24 Debug APK：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-redirect-uri-guard-v24.apk`，包名 `com.matelink.test.mock`，SHA-256 `055297BDFEFC9223DBDE2FACDC646A29A020A500D0F0906DC462EDA607AF51E3`；Release 候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-redirect-uri-guard-v24.apk`，包名 `com.matelink`，SHA-256 `42732C846185CB30F38693FEED64C2369339A6AFDDE160EFEE4ACFBDC6348BA2`。
- NOT_PERFORMED：手机 ADB 仍为空；标准 AVD qualification 最终为 `NOT_PERFORMED|reason=emulator_not_registered_with_adb|serial=emulator-5554`。未安装、卸载、清数据或 instrumentation；Release 未签名，正式签名、域名/HTTPS、Tesla 应用批准和真实 OAuth/Fleet仍待外部门禁。

# 2026-08-22 服务端回调路径配置门禁（v25）

## Review

- PASS：Go `loadTeslaConfig` 现在强制 `TESLA_REDIRECT_URI` 为 `/v1/auth/tesla/callback`、`JOURVOLT_APP_LINK_URI` 为 `/oauth/callback`；同时拒绝用户信息、query、fragment 和非 443 端口。
- PASS：新增配置边界测试；Go `test ./... -count=1`、`go vet ./...`、Docker smoke 通过，输出仍为 `LOCAL MOCK PASS`，1 台车辆、18 条行程、5 条充电、注销回收通过。
- PASS：Android v24 门禁继续有效；该服务端配置收口不产生新的 APK，最新候选仍为 v24。
- NOT_PERFORMED：没有读取或写入 Tesla 密钥、没有启动公网服务；正式域名/HTTPS、Tesla 应用批准、正式签名和真实 OAuth/Fleet仍待外部门禁。

# 2026-08-22 登录错误文案资源化与当前本地交付门禁（v22）

## Review

- PASS：Tesla 登录取消、授权暂不可用、授权交换失败三条 fallback 文案已从 `TeslaLoginViewModel` 移入中英文资源；不再把英文硬编码在登录逻辑中。
- PASS：Android Debug/Release 各 `271` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- PASS：Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error；该数量继续按多语言覆盖/发布门禁问题记录，不是运行时 Bug 数量。
- PASS：当前 Docker Mock smoke 为 `LOCAL MOCK PASS`：health/ready 为 `ok`，1 台车辆 `charging / 76%`，18 条行程、5 条充电，注销后旧 access `401`。
- PASS：Debug APK：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-localized-login-errors-v22.apk`，包名 `com.matelink.test.mock`，SHA-256 `5D3D068C5A2F94E2591198446F711A8638BB1D8C61646AFC839B32520F1A83C`；Release 候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-localized-login-errors-v22.apk`，包名 `com.matelink`，SHA-256 `A1811C38159F810AE5C9DF9205B634645B5BD3BD84D8D2B9380766F61ECF43F8`。
- NOT_PERFORMED：`adb devices -l` 仍为空；没有安装、卸载、清数据或 instrumentation。Release 仍未签名，不能覆盖手机中已有正式签名的 `com.matelink`；正式签名、域名/HTTPS、Tesla 应用批准和真实 OAuth/Fleet 仍待外部门禁。

# 2026-08-22 统计综合分析页接入原有里程钻取（v17）

## Review

- PASS：综合分析卡新增“查看里程分解”入口，复用现有 `MileageScreen` 的年度→月份→日期→行程详情层级；没有重做原 MateLink 视觉或复制统计数据。
- PASS：移除过时的 Android/iOS parity TODO；`StatsScreen`、`NavGraph` 和中英文资源编译通过。
- PASS：Android Debug/Release 各 `269` 个 JVM 测试通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- PASS：Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Release APK `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-analysis-drilldown-v17.apk`，包名 `com.matelink`，SHA-256 `BD1999A77B5444584F948B2D3543CF3E50FAD3F1663D79BDB2BCF36AE01E338E`；隔离 Debug APK `E:\Claude_allow\Download\matelink-test-mock-debug-20260822-analysis-drilldown-v17.apk`，包名 `com.matelink.test.mock`，SHA-256 `A9FB3A45B226E258FC9F5015A1BFB2B66A63CA6BCF80507EF0E05CAA627C9DAA`。
- NOT_PERFORMED：`adb devices -l` 仍为空；没有安装、卸载、清数据或 instrumentation。v17 未签名，不可覆盖已有正式 MateLink；真实 Tesla OAuth/Fleet、域名/HTTPS 和正式签名仍未完成。

# 2026-08-22 本地 Docker 复建与无密钥 Pilot bundle（v18）

## Review

- PASS：当前源码重新构建并启动 `deploy/jourvolt-dev-mock`，`/healthz` 与 `/readyz` 均为 `ok`。
- PASS：新增 `deploy/jourvolt-dev-mock/smoke.ps1`，实际完成 Mock 登录、车辆列表、兼容快照、18 条行程、5 条充电和注销后旧 access `401`；输出为 `LOCAL MOCK PASS`，未打印 token。
- PASS：新 bundle `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v18` 包含 smoke 脚本；bundle 内 Go test/vet、Compose 配置校验通过，manifest 为 `secrets_included=false`，敏感文件计数 `0`。
- PASS：ZIP `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v18.zip`，SHA-256 `60740D9757EB1DC2AC2BD2769DED4774B71F333DDD6F140C072E3EB68C2B4260`。
- NOT_PERFORMED：本地 Mock 不代表真实 Tesla OAuth/Fleet；服务器公网部署、正式签名、域名/HTTPS、Tesla 应用批准、实体手机和 Git stage/commit/push 仍未执行。

# 2026-08-22 Docker smoke 计数修正与 Pilot bundle（v19）

## Review

- PASS：修正 smoke 对嵌套兼容响应的计数，实际验证 `data.drives=18`、`data.charges=5`，车辆快照 `charging / 76% / mock_fixture`，注销后旧 access 返回 `401`。
- PASS：v19 bundle `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v19` 包含修正版 smoke；Go test/vet、Pilot Compose 配置、manifest `secrets_included=false` 和敏感文件扫描 `0` 通过。
- PASS：ZIP `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v19.zip`，SHA-256 `CD2E1299F61C058BB37AB254A13DF5A27A27F43A85389AC87BBB2336DC4AC844`。
- NOTE：v18 bundle 已被 v19 替代；本地 Mock 证据仍不代表真实 Tesla OAuth/Fleet、公网服务器、正式签名或实体设备 UI。

# 2026-08-22 Locale 发布质量修复与 v15 APK

- PASS：报告、成本、Dashboard、效率、费率、待机格式化显式使用 Locale；日志时间格式固定使用 `Locale.ROOT`，不改变原界面和算法。
- PASS：Android Debug/Release 各 `269` 个 JVM 用例通过；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- PASS：Release lint `231` findings，`MissingTranslation=0`、无 baseline、0 Error；`DefaultLocale` 与 `ConstantLocale` 已清零。该 finding 数量按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- PASS：未签名 Release `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-locale-v15.apk`，SHA-256 `B11042EB9B8C9138BA68341E3AA085C12332AD545A90C1D58C4D20B5C045524E`；隔离 Debug `E:\Claude_allow\Download\matelink-test-mock-debug-20260822-locale-v15.apk`，SHA-256 `D65DE024D3894807582DDD0C910A74173E5F0105D5A649B003F70FEADBC5E48E`。
- NOT_PERFORMED：ADB 仍为空；正式签名、实体设备 UI、域名/HTTPS、Tesla 批准和真实 OAuth/Fleet 仍待外部门禁。

# 2026-08-22 发布门禁进一步收口与 v16 APK

- PASS：Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error；`DefaultLocale`、`ConstantLocale`、`TypographyEllipsis`、`TypographyDashes`、`ObsoleteSdkInt` 均为 0。
- PASS：Debug/Release 各 `269` 个 JVM 用例通过，assembleDebug、assembleRelease、lintRelease 通过；Release 静态扫描无 Mock、回环地址、Debug 登录标记或错误包名。
- PASS：未签名 Release `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-lint194-v16.apk`，SHA-256 `0090C25FCF3D0D69FED597389FAEB23045E8BA4A963C616F13AAB76DBDDB03CF`；隔离 Debug `E:\Claude_allow\Download\matelink-test-mock-debug-20260822-lint194-v16.apk`，SHA-256 `54420A3C8BF9D829F5D2962185B2D3D619E536D6DEE4F4C433DABAB81FDFB8CC`。
- NOT_PERFORMED：ADB 仍为空；正式签名、实体设备 UI、域名/HTTPS、Tesla 批准和真实 OAuth/Fleet 仍待外部门禁。

# 2026-08-22 无密钥 Pilot bundle 可上传交付物

- PASS：生成自包含 bundle `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-local`，manifest 为 `secrets_included=false`，未发现 `.env`、keystore、证书或私钥。
- PASS：bundle 内 `go test ./... -count=1`、`go vet ./...` 通过；使用进程级占位值的 Pilot Compose 配置校验通过，真实值未落盘。
- PASS：ZIP `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-local.zip`，SHA-256 `84C433701FEFFF44B49C0109FAFFB70103F184C781075D7E253233AFC01FC4C4`。
- NOT_PERFORMED：未在服务器启动、未填写 Tesla client secret/token key、未进行公网 DNS/HTTPS、正式签名或真实 OAuth/Fleet；bundle 只是下一阶段的安全部署输入。

# 2026-08-22 Debug Mock 登录代次隔离后的最终本地门禁

## Review

- PASS：Debug-only `DebugMockLoginViewModel` 加入请求代次和取消异常隔离，快速重复点击不会覆盖新状态或写回旧 session；正式 Release 不包含该实现。
- PASS：Android Debug/Release 各 `269` 个 JVM 测试通过，failures/errors/skips 均为 `0`；构建、Release lint、Go test/vet、Docker health/ready 和 `git diff --check` 通过。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Release APK 静态扫描未命中回环地址、Mock provider、Mock 登录标记或 `com.jourvolt.app`；未签名候选 SHA-256 为 `3C7588D70F418E9C29124BCFB2A7D0C0CAC7866A34B808D743923EFBCD8C86BB`，文件为 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-login-generation-v14.apk`。
- NOT_PERFORMED：ADB 仍为空；没有安装、卸载、清数据或 instrumentation。正式签名、服务器/域名、Tesla 应用批准和真实 OAuth/Fleet 仍未完成。

# 2026-08-22 Tesla 登录请求代次隔离后的最终本地门禁

## Review

- PASS：`TeslaLoginViewModel` 为 `/start` 与 callback exchange 增加请求代次校验；旧请求取消后不能覆盖新登录状态、打开旧授权页或写入旧 session。
- PASS：新增 `TeslaRequestGenerationTest`；Android Debug/Release 各 `269` 个 JVM 测试通过，failures/errors/skips 均为 `0`。
- PASS：`testDebugUnitTest`、`testReleaseUnitTest`、`assembleDebug`、`assembleRelease`、`lintRelease`、Go `test ./... -count=1`、`go vet ./...`、Docker health/ready 和 `git diff --check` 通过。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：Release APK 静态扫描未命中回环地址、Mock provider、Mock 登录标记或 `com.jourvolt.app`；未签名候选 SHA-256 为 `3C7588D70F418E9C29124BCFB2A7D0C0CAC7866A34B808D743923EFBCD8C86BB`，文件为 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-login-generation-v13.apk`。
- NOT_PERFORMED：ADB 仍为空；没有安装、卸载、清数据或 instrumentation。正式签名、服务器/域名、Tesla 应用批准和真实 OAuth/Fleet 仍未完成。

# 2026-08-22 当前最终门禁（v14）

## Review

- PASS：正式 Tesla 登录 start/callback 与 Debug Mock 登录均具备请求代次隔离；取消的旧请求不会覆盖新状态、打开旧授权页或写回旧 session。
- PASS：Android Debug/Release 各 `269` 个 JVM 用例通过，failures/errors/skips 均为 `0`；构建、Release lint、Go test/vet、Docker health/ready 和 `git diff --check` 通过。
- PASS：Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；这是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- PASS：最新未签名 Release APK 为 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-login-generation-v14.apk`，包名 `com.matelink`，SHA-256 `3C7588D70F418E9C29124BCFB2A7D0C0CAC7866A34B808D743923EFBCD8C86BB`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- PASS：隔离 Debug 测试包为 `E:\Claude_allow\Download\matelink-test-mock-debug-20260822-v1.apk`，包名 `com.matelink.test.mock`，SHA-256 `FF1C4D080F3F9F2A2572836A9244F8089A2F02786683852ED893754AB9F19EA4`，不进入 Release。
- NOT_PERFORMED：ADB 仍为空；没有安装、卸载、清数据或 instrumentation。正式签名、服务器/域名、Tesla 应用批准和真实 OAuth/Fleet 仍未完成。
# 2026-08-31 Task2 telemetry configure retry dead-end

## Plan

- [x] Trace the readiness presentation, polling timeout, and generation-owned configure lease.
- [x] RED: Add a presentation regression for waiting/pending telemetry with `configSynced != true`, including no automatic configure invocation.
- [x] GREEN: Derive the explicit configure/retry affordance from eligible status and unsynced config while preserving blocking error actions.
- [x] Run targeted Task2, full Debug JVM rerun, Android-test compilation, resource parity, and diff checks.

## Review

- PASS: `TelemetryPairingContractTest` passed 10/10 after the RED/GREEN cycle; its timeout regression proves the only configure request is the user's explicit tap. Fresh `:app:testDebugUnitTest --rerun-tasks` passed 447 tests with 0 failures/errors/skips. `:app:compileDebugAndroidTestKotlin` passed without running a device test. English/Chinese string parity is 1266/1266 keys with no differences, and `git diff --check` passed. No stage/commit/push/reset/stash/clean/deploy.
- NOT_PERFORMED: instrumentation was not run.

# 2026-08-31 行程与充电页面闪退修复

## Plan

- [x] 读取真机 `AndroidRuntime` 崩溃栈，复现并定位到云车辆 UID 缺失。
- [x] 对服务端 Fleet 车辆映射和 Android 历史身份边界完成 RED -> GREEN。
- [x] 为缺失身份增加可恢复错误和中英文等待提示，不以数字车 ID 伪造身份。
- [x] 完成 Go、Android Debug/Release、AndroidTest 编译、Release lint 与签名候选验证。
- [x] 通过 `adb install -r` 安装候选包并检查启动后无新 MateLink FATAL；统计页同类观察路径也已加保护。

## Review

- PASS：Go `go test ./... -count=1`、`go vet ./...`；Android Debug/Release 各 `449` 个 JVM 测试；`:app:compileDebugAndroidTestKotlin`；`:app:assembleRelease`；`:app:lintRelease`（0 Error、0 MissingTranslation）；`git diff --check` 均通过。
- PASS：服务端回归测试验证 Fleet JSON 包含 `vehicle_uid`；Android 回归验证缺失云身份保持 fail-closed 并返回 `CONFIGURATION/history_identity_unavailable`。
- PASS：最终签名候选 `E:\Claude_allow\Download\matelink-1.4.2-release-signed-history-crash-fix-stats-20260831.apk`，包名 `com.matelink`，SHA-256 `E39CCE42A593521EDEECDDEF0ED32604331A6D06CCBA2F675385ED385CD85CE1`，`apksigner` v2 verified；已使用 `adb install -r` 覆盖安装。
- DEVICE PASS（启动级）：设备安装后应用正常启动到登录页，安装后没有新的 `com.matelink` FATAL；本轮安装前设备上已不存在正式包，因此不声称保留已被用户卸载的数据。
- REVIEW：质量复核未发现 P0/P1；剩余 P2 是真实 Fleet provider -> 云端 API -> Android 解码 -> 历史页面的端到端测试尚未执行，需真实 Tesla 会话后补证。
- PASS：ECS 已完成匹配服务端源同步与 API 重建；公网 `/healthz`、`/readyz` 为 200 且 `fleet/postgres/ok`，未授权车辆接口为 401，PostgreSQL 与 `star-photo` 容器未被重启。
- PASS：正确的 `GET /v1/auth/tesla/start` 返回 200，授权端点为官方 `auth.tesla.cn/oauth2/v3/authorize`，回调为 `api.teslalink.joviluma.com/v1/auth/tesla/callback`；此前 POST 404 已确认是探测方法错误。
- NOT_PERFORMED：未输入 Tesla 凭据，未完成真实云登录/历史页面点击；未 commit/push。
# 2026-09-01 数据准备、待机能耗与设置体验修复

## Plan

- [x] 为云端首次登录的等待/收集状态与待机 404 降级补 RED 测试。
- [x] 修复待机能耗的本地历史降级和中文空态，不把缺失数据显示为错误或 0。
- [x] 优化首次登录数据准备提示，明确区分实时可用、车辆等待、历史收集和不支持。
- [x] 将高德 Key 配置改为 3 步图文面板，保留复制、隐私和验证闭环。
- [x] 将高级网络改为云端/自托管模式感知的卡片面板，加入版本号和本次修复说明。
- [x] 更新工程 Bug 记录、Lessons；完成本地构建与真机覆盖升级验证。
- [x] Obsidian/Codex memory 同步：项目 Markdown 镜像已通过 `invoke-mirror.ps1` 更新为 `MEMORY_UPDATED`；当前没有可写的项目槽位，不创建新 Vault 槽位。

## Review

- LOCAL PASS：Android Debug/Release 单测、Debug/Release 构建、Release lint、AndroidTest 编译、JourVolt API/Adapter Go test/vet 均通过。
- DEVICE PASS：同签名 `com.matelink` 由 1.4.2 覆盖升级至 1.4.3；首装时间保持，Dashboard、行程、充电、待机、设置和 AMap 向导可打开；无新增 MateLink FATAL。
- TELEMETRY PILOT PASS：NOT PERFORMED；未执行真实 Fleet Telemetry、虚拟钥匙配对或真实行程/充电事件，不能以本地测试代替。
- 版本候选：`E:\Claude_allow\Download\matelink-1.4.3-release-signed-readiness-standby-amap-settings-20260901.apk`，SHA-256 `0C92E0040F192F7229E0710F0C8119A3D63CEDCC03527F3374CBA87A0A968DC1`。
- 边界：本轮不修改 iOS，不提交、不推送、不部署；保留当前工作树中的既有用户资产。
