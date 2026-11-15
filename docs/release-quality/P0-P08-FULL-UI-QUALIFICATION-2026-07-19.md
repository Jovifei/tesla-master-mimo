# P0.8 最小可测试性支撑与剩余 Full-UI 资格闭环

日期：2026-07-19
结论：**FAIL — 不进入候选提交边界审查。**

## 目标与边界

本轮只验证 P0.7 遗留的真实 Android UI 路径：设置连接、停车详情、行程/充电空数据，以及 About 的三项公开说明入口。

P0.7 的既有基线没有重跑；本报告只记录 P0.8 的增量设备证据。未修改业务数据模型、网络协议、Room schema、同步实现或 Docker/生产服务；未连接真实手机。

## 隔离环境与执行方式

- 本地 Android Emulator，经 `ro.kernel.qemu=1` 和显式序列号守卫确认；序列号不落盘。
- 每个直接 instrumentation 执行前都运行 emulator-only guard；使用 `am instrument`，未运行 `connectedDebugAndroidTest`，也没有处理 UTP gRPC 环境问题。
- 测试夹具仅监听本机回环地址；向模拟器提供合成数据。控制状态不记录 Authorization header、令牌或真实服务器地址。
- 每项设备执行前清空 logcat。最终通过窗口的扫描结果：`FATAL EXCEPTION=0`、`ANR=0`、`SQLiteException=0`、`WorkManager unrecoverable/FAILED=0`。

## 最小测试支撑

- `tools/p0_qualification/fixture_server.py`：增加 normal、401、timeout、空行程、空充电、停车部分缺失等合成场景，以及不含凭据的状态计数。连接测试计数只统计 `/api/ping`，避免后台同步访问 `/cars` 造成假阳性。
- `android/app/src/androidTest/java/com/matelink/p0/`：增加真实 Activity/Compose UI 的 P0.8 设备测试与合成状态准备。
- 仅为 debug 包增加条件测试语义：设置结果卡的 success/failure 标签，以及行程/充电 Pull-to-refresh 容器标签。release 包没有这些标签；业务 UI 与业务链路未改。

## Full-UI 结果

| 路径 | 结果 | 设备证据 |
| --- | --- | --- |
| 局域网 Test Connection 成功且未保存前不持久化 | PASS | 成功结果卡可见；夹具收到连接 ping；保存前状态仍是旧配置。 |
| Save Configuration 持久化新配置 | **FAIL** | 成功 Test Connection 后点击保存，30 秒内测试状态仍未读到新根地址。 |
| 单一 API 地址/令牌字段、令牌掩码、无旧 token 字段 | PASS | UI 语义断言通过。 |
| 错误令牌 401、失败状态可见且不持久化 | PASS | 合成夹具返回 401；失败结果卡可见；持久化令牌未变化。 |
| 9 组非法地址 | PASS | 每组显示失败结果；连接 ping 计数均为零。 |
| 公网 HTTP 拦截 | PASS | 失败结果可见；连接 ping 计数为零。 |
| timeout、夹具复位、独立新会话正常连接 | PASS | 超时失败结果可见；夹具复位后新 UI 会话可再次成功连接。 |
| About 三项公开说明 | PASS | Help、Legal、Changelog 都从真实 About 页面点击；未配置公开页时留在 App 内。 |
| Drives -> Parked detail -> 返回 Drives | PASS | 经真实列表导航、Activity recreate、系统返回；正常和部分缺失停车数据均渲染。 |
| 停车详情部分字段 | PASS | 缺失字段显示 `not_available`，没有伪造数值。 |
| Drives/Charges 空态与返回 | PASS | 合成空响应显示对应空态，且可返回 Dashboard。 |
| Drives/Charges 空态下拉刷新 | **FAIL** | 真实向下手势后 10 秒内未收到对应列表的再次请求；两页均可复现。 |

隔离的最终设备用例口径为 10 项：**7 PASS / 3 FAIL**。失败项是 1 个保存持久化用例和 2 个空态下拉刷新用例。

## 构建与回归

- `:app:assembleDebug :app:assembleDebugAndroidTest`：PASS。
- `:app:testDebugUnitTest`：PASS，JUnit XML 汇总 `46 tests / 0 failures / 0 errors / 0 skipped`。
- 设备测试使用守卫后的单类/单方法直接 instrumentation 执行；避免把本机 UTP 问题误报为产品结果。

## 变更与候选提交边界

- 生产文件仅包含 debug 条件下的测试语义：
  - `android/app/src/main/java/com/matelink/ui/screens/settings/SettingsScreen.kt`
  - `android/app/src/main/java/com/matelink/ui/screens/drives/DrivesScreen.kt`
  - `android/app/src/main/java/com/matelink/ui/screens/charges/ChargesScreen.kt`
- 其余变更限于 androidTest、测试夹具和守卫/启动脚本。
- 未使用 child agent；未执行 Git add、commit、push、reset、checkout、clean 或 stash。
- 工作树本来就包含无关脏改动；本轮没有处理或覆盖它们。`git diff --check` 未报告空白错误（仅现有 LF/CRLF 提示）。

## 停止条件与唯一下一步

P0.8 保持 **FAIL**，不生成候选提交，也不进入 P0.9/P1。

唯一下一步：取得授权后，开展一个只修复“成功后保存未持久化”和“空态下拉刷新未触发列表请求”的最小业务修复包，并在同一隔离模拟器上复跑本报告中的 10 项用例。
