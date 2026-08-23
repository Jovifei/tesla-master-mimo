# MateLink 单一 App 登录改造状态

更新时间：2026-08-15

## 已实施

- 正式 Android 包恢复为唯一 `com.matelink`；不再构建 `com.jourvolt.app`。
- `debug` 变体使用 `com.matelink.test.mock`，仅用于本地 Mock/模拟器自动化，不是用户交付 APK。
- 保留原 MateLink Dashboard、底部导航、Room/DataStore/SecureSettings 和历史数据；登录成功路由回原 Dashboard。
- 新增运行时 `TESLA_CLOUD` / `SELF_HOSTED` 连接模式：旧服务器 URL、Token 或 Instance 自动保留为自托管，新安装默认进入 Tesla 登录。
- Tesla 登录使用 Custom Tab 打开官方授权页；App 只交换一次性 JourVolt ticket 并保存 JourVolt session，不收集 Tesla 密码或 Tesla token。
- 主 Activity 增加正式包的 HTTPS App Link 回调；云模式使用固定 HTTPS API、Bearer session、刷新轮换和一次 401 重试。
- 原 Settings 保留完整功能；Tesla 账号状态、重新授权、退出和注销位于顶部；服务器、Token、Basic Auth 和证书选项移入折叠的高级自托管区域。
- Mock 登录入口只由 Debug BuildConfig 启用；Release 关闭 Mock、本地回环地址和明文网络。
- Android 只保留中文和英文；`de`、`ja`、`fr` 残留资源已移除，不使用 lint baseline。
- `deploy/jourvolt-dev-mock` 继续提供本机 PostgreSQL、兼容 API、Mock/Fleet provider、session 轮换和真实配置 fail-closed 路径。
- 原 Stats 页面已接入透明建议引擎；建议按受控温度/速度分组与距离加权结果生成，并显示阈值、样本、覆盖、可信度、月度影响区间、动作和方法。
- 已增加真实 Pilot 前置工具：关闭 Mock 的 Compose 模板、正式 `com.matelink` App Link 示例和不输出密钥的 `preflight.ps1`；它们只在外部 Tesla/域名条件具备后使用。
- OAuth 回调交换前新增二次校验：只接受 BuildConfig 绑定的 HTTPS 主机和 `/oauth/callback` 精确路径，拒绝明文、错误主机和其他路径。

## 交付边界

本轮实现的是“原 MateLink 单一 App 的登录接入结构”。本地 Mock 只能证明开发链路，不能证明 Tesla 官方 OAuth 或真实车辆数据：

```text
Debug test package -> local Mock session -> original Dashboard/navigation
Release com.matelink -> Tesla official OAuth -> JourVolt session -> original Dashboard
```

真实 Tesla 登录仍为 `CONFIG-READY / REAL TESLA PILOT BLOCKED`，原因是批准的 Tesla 应用、受控域名、公开 HTTPS callback、App Link `assetlinks.json` 和私密配置尚未提供。未读取或保存 Tesla 密码、client secret、refresh token 或私钥。

未操作实体手机、未运行 connected instrumentation；最新 Debug 包只覆盖安装到独立模拟器 `emulator-5554`。错误的 `com.jourvolt.app` 设备安装状态不在本批处理范围内，需另行授权后再卸载。

## 当前已完成的验证

- `:app:compileDebugKotlin`：PASS。
- 全量 `:app:testDebugUnitTest`：177 tests，0 failures、0 errors、0 skipped。
- 连接模式迁移、OAuth/App Link 契约及 URL 安全目标测试：PASS；指定目标共 31 个测试目标。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:verifyDebugForegroundServiceType`：PASS；AndroidTest APK 只构建未运行。
- `:app:assembleRelease`：PASS；产物为未签名 APK，包名经 badging 确认为 `com.matelink`。
- `:app:lintRelease`：PASS；`MissingTranslation=0`，`BaselineFiles=0`；8 条 Information、255 条 Warning，属于发布门禁统计，不是运行时 Bug 数。
- Release APK 二进制未检出 `com.jourvolt.app`、Mock 登录、回环地址或 `JOURVOLT_MOCK_LOGIN` 字符串。
- `git diff --check`：PASS（仅保留既有换行提示）。
- Go `go test ./...`、`go vet ./...` 和 `docker compose config --quiet`：PASS；本机 health 为 `ok/postgres`，未配置 OAuth 的 start 返回 HTTP 503，继续 fail closed。
- 独立模拟器 `emulator-5554`：`LOCAL MOCK HISTORY PASS`；首屏、Mock 登录、原 Dashboard、主动刷新/后台同步、More -> Stats、中英文建议卡均通过。历史 fixture 为 18 条行程和 5 条充电；Stats 显示 420 km、215 Wh/km、23 条来源记录和三类有证据建议，fatal/ANR 为 0。
- 最新 Android 门禁：179 个 JVM tests，0 failures/errors/skips；Release lint 仍为 0 error、255 warning、8 information，`MissingTranslation=0`，无 lint baseline。

本轮完成的是凭证无关的 App/本机 Mock 交付门禁；这仍不是 Tesla 官方 OAuth 或真实车辆证据。

## 2026-08-21 当前执行证据（最新）

- Android JVM 测试已更新为 182/182 PASS；本轮新增 Dashboard 来源映射测试。
- 模拟器 emulator-5554 已重新回归：本地 Mock 登录进入原 Dashboard，车辆状态、76% 电量、Dashboard/Drives/Charges/More 底部导航均可见；来源徽章现在显示 Local mock，不再把 mock_fixture 错报为 Unavailable；最近 AndroidRuntime 日志无 FATAL。
- Release APK 仍为 com.matelink，路径为 android/app/build/outputs/apk/release/app-release-unsigned.apk；静态检查未发现错误包名、Mock 登录标记或本地回环地址。
- Release lint 为 0 errors、255 warnings、8 information，MissingTranslation=0，未建立 lint baseline。这是多语言覆盖/发布门禁统计，不是 883 个运行时 Bug。
- 本机 Docker API 与 PostgreSQL 均 healthy；/healthz 和 /readyz 均返回 status=ok。Pilot Caddy edge profile 和 assetlinks 模板已准备，但未公网启动。
- 当前状态仍为 APP STRUCTURE READY / LOCAL MOCK PASS / REAL TESLA PILOT BLOCKED。真实 Tesla 应用批准、受控域名、公网 HTTPS callback、正式签名指纹和真实车辆尚未执行。

## 2026-08-21 Fleet 请求兼容性收口

- Go Fleet provider 的认证 GET 请求已补齐 Tesla 中国官方约定的 `Content-Type: application/json`，并由 401 重试测试校验旧/新 token 两次请求。
- Go `test` 与 `vet` 通过；本次没有连接真实 Tesla 端点，也没有改变 `com.matelink`、原 Dashboard、Mock 隔离或真实 Pilot 外部门禁。

## 下一步

1. Jovi 完成 Tesla 应用、域名、HTTPS callback 和 App Link 外部门禁后，才启用真实 Fleet OAuth 并做单车验证。
2. 真机覆盖安装、卸载 `com.jourvolt.app`、正式签名和 Git 操作均需单独授权。

## 2026-08-21 货币与成本显示收口

- 新安装默认货币改为 CNY；已有明确 `currencyCode` 的安装继续保留用户选择；只有没有显式币种但已有旧服务器/Token 配置的升级安装才保留 EUR 兼容默认值。
- Cost、Tariff Configuration、Annual Report、Annual Report PDF、Stats、Trip/Charge 初始状态均从 `SettingsDataStore` 读取当前货币，不再在活动 UI 路径写死 `¥` 或 `€`。
- 手动充电总价继续由 `SettingsDataStore` 持久化，并由充电详情、成本和年度报告读取；旧计划中“Room-backed 费用覆盖”属于历史实现路线，本批未新增 Room 迁移。
- 证据：184/184 JVM tests、Debug/Release、Release lint、`git diff --check` 通过；lint 为 0 error，`MissingTranslation=0`，无 baseline；独立模拟器 Settings 显示 `¥ CNY - CNY` 且没有 EUR，最近 AndroidRuntime 无 FATAL。
- 本次变化不改变 `com.matelink`、原 Dashboard/导航、自托管兼容和真实 Tesla 外部门禁；真实 OAuth、真实车辆和实体手机仍未验证。

## 2026-08-21 会话失效回登录收口

- 云模式的 JourVolt session refresh 失败后，`JourVoltSessionRefresher` 清除本地 session；NavGraph 现在观察 session 与连接模式，在已打开 Dashboard 的 TESLA_CLOUD 模式下自动回到 Tesla 登录页。
- SELF_HOSTED 模式没有 JourVolt session 时不会被误送回 Tesla 登录页；登录页自身不会重复导航。
- 新增 `SessionExpiryNavigationTest` 覆盖云模式失效、自托管模式和登录页三种边界。
- 证据：187/187 JVM tests，0 failures/errors/skips；Debug、Release、lint 通过；`MissingTranslation=0`，无 lint baseline；最新 Debug 已重新安装到独立模拟器，原 Dashboard/车辆状态/底部导航可见，最近 AndroidRuntime 无 FATAL。

## 2026-08-21 重新授权入口与完整回归（最新）

- Settings 的 Tesla 账号区域新增“Connect or re-authorize Tesla”；导航层统一先清除当前 JourVolt session，再打开 Tesla 登录页，避免已登录状态把用户重新弹回 Dashboard。
- 界面层不再重复调用登出；重新授权只保留一个清理入口，SELF_HOSTED 连接和原设置不变。
- `emulator-5554` 实测：原 Dashboard -> More -> Settings -> Connect or re-authorize Tesla -> Tesla 登录页 -> 协议确认 -> Local mock login -> 原 Dashboard；`Development Model 3`、`Charging`、`76%`、Dashboard/Drives/Charges/More 均可见。
- 最新门禁：`testDebugUnitTest`、`assembleDebug`、`assembleRelease`、`lintRelease` 全部 PASS；187/187 JVM tests，lint 0 errors、`MissingTranslation=0`、无 lint baseline。
- Debug APK 已覆盖安装到独立模拟器；最近 AndroidRuntime 无 FATAL。未操作实体手机，未运行 connected instrumentation，未执行真实 Tesla OAuth/401/refresh 或真实车辆验收。

## 2026-08-21 Room 费用覆盖收口（当前最新）

- 新增 Room `charge_cost_overrides` 表，数据库版本从 15 升到 16，使用 `(carId, chargeId)` 复合主键，费用覆盖按车辆和充电记录隔离。
- 首次访问时从旧 `SettingsDataStore` JSON 一次性迁移；只有 Room 写入成功后才清理旧 JSON 并写入迁移完成标记，避免升级用户丢失已有手动费用。
- 充电详情的编辑/重算、充电列表、成本页和年度报告统一读取 `ChargeCostOverrideStore`；后续不再把新的费用覆盖写回全局 DataStore。
- 验证：187/187 JVM tests、Debug、Release、AndroidTest APK、Release lint 全部通过；Room 15→16 迁移测试在隔离模拟器完成 4/4；`MissingTranslation=0`，无 lint baseline。
- 最新 Debug 已覆盖安装到独立模拟器；本地 Mock 登录可回到原 Dashboard，车辆状态、`76%` 电量和 Dashboard/Drives/Charges/More 可见，最近 AndroidRuntime 无 FATAL。
- 该阶段仍是本地代码与 Mock 证据；未执行实体手机操作、真实 Tesla OAuth、真实车辆、服务器采购或公网 Pilot。

## 2026-08-21 JourVolt 会话注销安全收口

- Go 服务端注销从只撤销 access hash 改为撤销完整 session；原 refresh token 在注销后不能继续轮换。
- 本机 Docker HTTP 验证：登录后注销，原 access 请求返回 401，原 refresh 请求返回 401，`/readyz` 返回 ok。
- Go `go test ./... -count=1` 与 `go vet ./...` 通过；临时空 PostgreSQL 的 4 个会话/令牌集成测试通过；代码仍保持 Mock/Fleet 配置隔离，未接入真实 Tesla。

## 2026-08-21 Debug 本地路由例外与云 HTTPS 门禁

- 修复登录后 Repository 使用瞬时连接模式、API Factory 使用持久化模式造成的路由不一致；两者现在都以 `ConnectionModeStore.mode` 为准。
- 云 API Factory 仅在 Debug 且目标为本地 HTTP 时放行 `10.0.2.2`/回环调试地址；Release 和真实云配置继续强制 HTTPS。
- Session refresh URL 默认严格 HTTPS；仅 Debug Mock 配置显式允许本地 HTTP，公网 HTTP 不会被放行。
- 独立模拟器清空应用数据后重新执行 Mock 登录，原 Dashboard 显示本机 fixture 的 `Development Model 3`、`Charging`、`76%`、`Local mock`。
- 最新 Android 门禁：191/191 JVM tests，Debug/AndroidTest APK、Release、Release lint 全部 PASS；`MissingTranslation=0`，无 lint baseline；Go test/vet 和本机 Docker HTTP 回归也通过。

## 2026-08-21 原 Stats 分析结论与首次同步收口（最新）

- 保留原 MateLink Stats 页面、More -> Statistics 路由、主题和既有统计卡片；本轮没有新增登录壳或重做 UI。
- 综合分析在原卡片下增加 4 个可解释派生结论：平均每次行驶距离、平均每次充电充入能量、每 100 km 成本、充入/行驶能量比。输入不足时保持不可用，不把未知值转成 0；可用值标记为 `DERIVED / LOCAL_CALCULATION`。
- 建议卡补充观测天数，并继续展示阈值、样本量、累计距离和置信度；建议生成仍受既有样本门槛限制。
- 修复首次进入 Statistics 只读 Room 的逻辑缺口：`StatsViewModel` 触发现有 `DataSyncWorker`，Mock 历史 fixture 完成后再由 Room 驱动原页面；真实云端没有历史时仍保持合法空状态。
- 独立 `emulator-5554` 回归：Local Mock -> 原 Dashboard -> More -> Statistics，页面显示 `420 km`、`215 Wh/km`、`23` 条来源记录、Derived conclusions 和带 `57 days / confidence 66%` 的建议；日志显示同步完成，最近 AndroidRuntime 无 FATAL/ANR。
- 最新全量门禁：193/193 JVM tests，`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、`lintRelease` 全部 PASS；`MissingTranslation=0`、无 lint baseline。
- Release APK badging 为 `com.matelink`；未检出 `com.jourvolt.app`、Mock 登录标记、`10.0.2.2` 或 `127.0.0.1`；`git diff --check` 退出码 0。
- 边界：本轮仍未接触 Tesla 凭据、真实 OAuth/车辆、正式域名、公网 callback、服务器或实体手机；证据等级为 `LOCAL MOCK HISTORY PASS`，不是 `REAL TESLA PILOT PASS`。

## 2026-08-21 OAuth 服务端契约集成测试

- Go 服务新增本地 OIDC/JWKS/token 模拟集成测试，覆盖 Android 所依赖的 start -> Tesla callback -> ticket -> exchange 协议，不改变正式 App 的官方 OAuth 路径。
- 测试实际验证 state/nonce、ID Token 签名和 claims、Tesla grant 加密保存、App Link ticket、JourVolt session 生成以及 ticket 重放拒绝。
- 普通 `go test ./... -count=1`、隔离 PostgreSQL OAuth 集成测试和 `go vet ./...` 均通过；该证据仍标记为协议模拟，不是真实 Tesla 登录/真实车辆证据。

## 2026-08-21 当前工作树全量回归

- Android 全量命令 `testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease lintRelease --no-daemon` 通过。
- 独立模拟器清空 `com.matelink.test.mock` 数据后重新启动：官方 Tesla 登录说明、自托管高级入口和测试包 Mock 入口均可见；Mock 登录后进入原 Dashboard，显示 `Development Model 3 / Charging / 76%`，底部 `Dashboard / Drives / Charges / More` 可见。
- 从 More 进入原 Statistics 页面显示 `420 km`、`215 Wh/km`、`90 kWh`、`58 kWh`、`42.50 ¥`，并显示 Derived conclusions 和样本/覆盖/置信度建议；最近 logcat 未发现该包 FATAL。
- 本机 Docker 按当前代码重建后 `/healthz=ok`、`/readyz=ok`、车辆列表 1 台；注销后旧 access 与 refresh 均返回 401。
- 证据仍是 `LOCAL MOCK PASS / LOCAL MOCK HISTORY PASS`，不是真实 Tesla OAuth、Fleet 车辆或公网 Pilot 证明；未操作实体手机。

## 2026-08-21 Pilot 执行入口

- 新增 `deploy/jourvolt-dev-mock/pilot-up.ps1`，将真实 Pilot 的 env 预检、Compose 校验、Caddy edge 启动和 API 容器内 readiness 检查串联为一条可重复命令。
- `preflight.ps1 -EnvFile` 现在把同一配置文件传给 `docker compose --env-file`，避免自定义配置路径与实际启动配置不一致。
- 该入口保持 fail-closed：示例域名、缺少 Tesla 配置或缺少 32 字节 token key 时在启动前退出；不能把脚本存在或本地运行描述为真实 Tesla 车辆通过。

## 2026-08-21 Pilot Release 构建入口

- 新增 `android/build-pilot-apk.ps1`，要求显式传入公网 HTTPS API 根地址和 App Link 主机名。
- 脚本执行 `lintRelease`、`assembleRelease`、正式包名检查，并输出未签名 APK 的 SHA-256；签名密钥仍由发布持有人在脚本外管理。
- 构建入口现在支持可选 `-SigningPropertiesPath`：未提供时保持 `UNSIGNED_RELEASE`；提供私密 properties 后生成 `SIGNED_RELEASE`，并以 `apksigner verify` 作为签名门禁。密码和 keystore 内容不进入仓库或日志。

## 2026-08-21 本地交付最新证据

- 历史分析网络失败时从既有 Room 行程/充电摘要构造 `STALE` 快照，未知值不转成真实零值，网络恢复后新数据优先；未新增数据库迁移。
- Statistics 保持原有页面结构，仅补充底部滚动安全区；独立模拟器滚动到末尾后建议行动完整位于底部导航上方。
- Android 最新全量证据：`202` JVM tests，failures/errors/skips 均为 `0`；Debug、Debug AndroidTest、Release、Release lint 通过；`MissingTranslation=0`。Lint 剩余 `256` 项属于多语言覆盖/发布门禁，不是运行时 Bug 数量。
- 未签名 `com.matelink` Release `1.4.2` SHA-256：`704593A8EEC463DBABCAF20E9BD338016708C9901CEC9906C9121A69F84972F1`。
- 当前状态仍为 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。

## 2026-08-21 费用覆盖显示收口（当前最新）

- 充电汇总新增 `costCoverage`：没有任何费用来源时，汇总和成本图表显示 `Not available`，不再把默认 `0` 显示成免费；有来源的真实 `0` 元仍保留为 `0`。
- 统计页每百公里成本统一走有限值规则，真实免费成本显示 `0`，缺失、负数、NaN 或 Infinity 保持不可用。
- 新增成本/距离边界测试；Android JVM `209` tests，failures/errors/skips 均为 `0`。
- `assembleRelease` 与 `lintRelease` 通过；lint 共 `256` findings、`0` errors、`MissingTranslation=0`、无 baseline。该数量是发布门禁统计，不是运行时 Bug 数量。
- 未签名 APK 已用 SDK `aapt2` 核对：包名 `com.matelink`、version `1.4.2`、versionCode `14`；SHA-256：`172F73C17ACF5D9BBC61BB34F852FE5D32D393604B109E374340789155B2A204`。
- Go API/Adapter test/vet、本机 Docker `/healthz` 与 `/readyz` 通过；仍只属于 `LOCAL MOCK PASS / LOCAL MOCK HISTORY PASS`。
- Boundary：没有操作实体手机、Tesla 凭据、正式签名、服务器、DNS 或 Git 发布；真实 Pilot 仍为外部门禁。

## 2026-08-21 隔离模拟器最新完整链路

- Debug APK `com.matelink.test.mock` `1.4.2` 已覆盖安装到 `emulator-5554`；未连接实体手机，未运行 instrumentation。
- 手工点击验证通过：协议确认 → Local mock login → 原 MateLink Dashboard；车辆显示 `Development Model 3 / Charging / 76% / Local mock`，底部 `Dashboard / Drives / Charges / More` 可见。
- More → Statistics 验证通过：`420 km`、`215 Wh/km`、`23` 条来源记录、派生结论及带样本量/覆盖期/置信度的建议均可见。
- 最近 300 条 logcat 未发现 `FATAL EXCEPTION` 或 `ANR in`；该证据仍是 `LOCAL MOCK HISTORY PASS`，不能替代 Tesla 官方 OAuth/真实车辆证据。

## 2026-08-21 授权同意与账号生命周期收口（当前最新）

- 原 MateLink 登录页现在分别要求用户主动确认《用户协议》和《隐私政策》；未配置公开 HTTPS 页面时，正式 Tesla 登录按钮保持不可用，避免在缺少告知材料时开始云端授权。
- Android 仅本地保存已确认的文档版本和时间；它不保存 Tesla 密码、Tesla token 或 client secret。启动授权时把已确认的版本发送给 JourVolt 服务端，服务端把它绑定到一次性 OAuth state/nonce，并只在身份验证完成后写入用户同意审计记录。
- 设置页的“注销账号”先明确展示删除范围；确认后调用 `/v1/account`，当前 P1 服务端删除 JourVolt 用户、会话、加密 Tesla grant、车辆关联和同意记录。旧 access token 随后不能再读取车辆。Telemetry 尚未启用，因此没有把尚未存在的停止任务写成已验证能力。
- Debug 测试包的本地 Mock 会话明确标注“未使用 Tesla 账号”；该包可使用本机回环 HTTP 进行隔离验证。正式 `com.matelink` Release 继续只接受 HTTPS 云端地址，且不包含 Mock 登录入口。
- 已准备 `web_matelink/public/terms/` 与 `web_matelink/public/privacy/` 的待发布文本；上线前仍必须补充真实运营主体与联系渠道并托管到受控 HTTPS 域名，不能把本地文件当作已发布隐私政策。
- 本轮证据为 `LOCAL MOCK PASS`：`emulator-5554` 完成 Mock 登录 → 原 Dashboard → 设置页删除确认 → 清洁登录页，最近 300 条 logcat 无 FATAL/ANR。它不构成真实 Tesla OAuth、真实车辆或公网 Pilot 证明。
- 最终本地门禁：Android JVM `211` tests，failures/errors/skips 均为 `0`；Go `test`/`vet`、Docker `/healthz`/`/readyz`、`lintRelease`、`assembleRelease` 和 `git diff --check` 通过。Release lint 为 `256` findings、`0` errors、`MissingTranslation=0`，该数量仍是多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- `build-pilot-apk.ps1` 已以 HTTPS API、App Link 和公开信息地址参数生成候选：`com.matelink` `1.4.2` / versionCode `14` / `UNSIGNED_RELEASE`，SHA-256 `ABE80587FF6328D7D95FEB62E1038386583D4D63200AE3661AACAC06AC96A77A`。这是本地产物，不代表域名已经解析、页面已经发布或可向用户分发。

## 2026-08-21 个性化续航模型本地落地

- 新增 `PersonalizedRange.kt`：基于近 90 天有效行程计算距离加权能耗；当前温度按 `<10/10–25/>25°C`，速度按 `<50/50–90/>90 km/h` 分组。
- 分组模型至少需要 5 次有效行程和 100 km；分组不足时回退到至少 10 次和 300 km 的全局模型；两者都不足时明确不可用。
- 当前可用电池容量不是有效观测时只显示 Tesla 额定续航（如果有），不输出虚构的个性化公里数；卡片同时显示来源、样本量、覆盖距离和置信度。
- 续航页保留原有额定续航偏差、影响因素和行程列表，只增加一张原风格卡片；无近期有效样本时显示状态文案，不显示 `0` 统计。
- `PersonalizedRangeTest` 覆盖分组优先、全局回退、容量缺失、90 天窗口、非法值和分组边界。
- 本地门禁：Android JVM `216` tests，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过；lint `258` findings、`0` errors、`MissingTranslation=0`，无 baseline。
- SDK `aapt2` 核对 Release 仍为 `com.matelink` / MateLink / `1.4.2` / versionCode `14`；当前未签名 APK SHA-256 为 `AF7BD36F1F21C7AB1880A888ADF0DE2191BFCA1134F51AAFD40912E864AB499A`。
- `emulator-5554` 只安装了 `com.matelink.test.mock` Debug 包并打开原 Range Analysis 页面；无历史时显示“暂无可用于该模型的近90天有效行程样本”，最近日志无 FATAL/ANR。证据仍为本地 Mock，不是 Tesla 真实车辆证明。

## 2026-08-21 电池健康趋势模型本地落地

- 新增独立 `BatteryTrend.kt`：只接收有日期、SOC、额定续航和外部温度的历史行程样本；有效范围为 SOC `70–100%`、温度 `15–30°C`，并过滤 NaN、Infinity、非正数和缺失值。
- 额定续航先标准化到 100% SOC，再用中位数比较早期 30 天基线与最近窗口；至少 10 个有效样本且覆盖 30 天才进入趋势状态，缺少早期基线时只显示趋势、不计算衰减百分比。
- `BatteryViewModel` 现在优先保留后端容量观测；无容量观测时读取分析历史并在原电池页增加小卡片，明确趋势估算不是实测容量，保留原 MateLink 页面结构和视觉语言。
- 英文/中文均补齐趋势状态、复数样本、覆盖期、置信评分和无样本文案；无有效样本时不显示 `0` 统计。模拟器回归确认文案为单百分号，卡片无重叠或溢出。
- `BatteryTrendTest` 覆盖标准化、中位数、基线分离、样本/覆盖门槛、非法值和 SOC/温度边界。
- 本地门禁：Android JVM `221` tests，failures/errors `0`；Go test/vet、`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- `emulator-5554` 仅覆盖安装 `com.matelink.test.mock` Debug 包并验证原 Battery Health 页面；最近 500 条应用日志无 FATAL/ANR。Release `aapt2` 核对为 `com.matelink` / MateLink / `1.4.2` / versionCode `14`，未签名 SHA-256 为 `81744CA5A06C467583183D7AA882BDDC1C84A22B31F4B1C728CCE51B929BF091`。
- Boundary：趋势模型仍需真实 Fleet/Telemetry 数据验证；本地 Mock 不等于真实 Tesla 车辆。实体手机、正式签名、服务器、公网 DNS/HTTPS、Tesla 审核和 Git 发布仍未执行，状态保持 `REAL TESLA PILOT BLOCKED`。

## 2026-08-21 Statistics 数据覆盖摘要本地落地

- 原 MateLink Statistics 页面保留原 Dashboard/More 路由、既有统计卡片、派生结论和建议卡；本轮只在原综合分析卡片底部增加轻量 `Data coverage` 区块，没有重做 UI。
- 新增 `AnalysisCoverage` 领域模型：按当前 All Time/年度筛选统计里程、行驶能耗、充电能量和费用的有效输入覆盖率，并显示观测起止日期与覆盖天数。
- 覆盖率语义明确：没有该类记录时为不可用；记录存在但字段全部缺失时为 `0%`；真实费用 `0` 仍算作有费用来源。NaN、Infinity、负值和无效日期不进入有效样本。
- `StatsRepository` 在一次历史读取中同时生成建议证据和覆盖摘要，避免为新增 UI 重复拉取相同 Room 历史；Android 仍在后台线程执行聚合。
- 本地证据：`emulator-5554` 安装 `com.matelink.test.mock` Debug 后，原 Statistics 页面显示 `420 km`、`215 Wh/km`、`23` 条记录、派生结论、`18/18` 行程覆盖、`5/5` 充电覆盖和 `56 days` 观测范围；截图无重叠/截断，最近日志无 FATAL/ANR。
- 门禁：Android JVM `224` tests，failures/errors/skips `0`；Debug、Debug AndroidTest、Release、Release lint、Go `test`/`vet` 和 `git diff --check` 通过。Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；258 是发布门禁 findings，不是运行时 Bug 数量。
- Release 仍为未签名 `com.matelink` / MateLink / `1.4.2` / versionCode `14`，最终重建 SHA-256 `E0016BD9598E5900694BDCEDDBB21C07A00C67BD1A4AE46B2028348A4E9E3DEA`，不可作为正式用户分发包。真实 Tesla OAuth、Fleet 数据、服务器、DNS/HTTPS、正式签名和实体手机仍未执行。

## 2026-08-22 分析历史采集状态与待机证据收口

- `AnalysisHistoryRepository` 分页读取现在保留历史响应的 `meta`，空集合按服务端状态区分 `COLLECTING`、`SOURCE_UNAVAILABLE` 和普通 `NO_RECORDS`；分页仍按稳定源 ID 去重，不增加额外请求。
- 效率、成本、续航和待机四个原分析页面统一使用状态面板：云端刚连接但尚未采集历史时显示“正在采集”，不支持历史时显示“不可用”，只有真正没有记录时才显示普通空状态。
- 待机页不再把 SOC 下降写成待机能耗：TeslaMate 只有充电记录时显示“停车电量下降”，kWh、平均功率和原因保持未知；通用省电内容明确标为通用信息，不冒充个性化建议。
- 新增 `classifyEmptyHistory` 边界测试，覆盖采集中、不支持、可用但无记录，以及有记录时不覆盖状态；中文和英文资源均已补齐。
- 本地验证：Android JVM `226` tests，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease`、Go `test`/`vet`、Docker `/healthz`/`/readyz` 和 `git diff --check` 通过。Release lint 仍为 `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；这是多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- `emulator-5554` 仅覆盖安装 `com.matelink.test.mock` Debug 包并进入原 Standby 页面；当前本地会话没有历史时页面保持空状态，没有显示虚假零值、kWh 或功率。未操作实体手机，未运行 instrumentation。

## 2026-08-22 Statistics 状态呈现继续收口

- 原 `com.matelink` Statistics 页面继续沿用原卡片、颜色、导航和信息密度；本轮没有创建新壳或替换视觉方案。
- `MetricState` 已实际接入 Statistics 摘要卡：`Available` 显示真实/派生/估算证据，历史接口报告 `collecting` 且当前指标无值时显示“采集中”，不可用和可重试状态不再降级成普通无记录。
- 采集状态同时覆盖距离、驾驶能耗、效率、充电能量、费用、派生结论和覆盖率；已有有效值不会被采集状态覆盖。
- 本地门禁：Android JVM `228` tests，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease`、Go `test`/`vet`、Docker `/healthz`/`/readyz` 和 `git diff --check` 通过。
- Release lint 为 `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- `emulator-5554` 仅安装 `com.matelink.test.mock` Debug，验证原 Dashboard → More → Statistics：保留车辆页和原底部导航，Statistics 显示 `420 km`、`215 Wh/km`、`90 kWh`、`58 kWh`、`42.50 ¥`、派生结论和 `18/18`、`5/5` 覆盖；截图无重叠/截断，最近 800 条日志无 FATAL/ANR。
- 当前 Release 未签名 APK 仍为 `com.matelink` / MateLink / `1.4.2` / versionCode `14`，SHA-256：`26EECC6974891F451BEC8CC6F421AB33341A088812B02C880ED3458655054D0A`。未操作实体手机、真实 Tesla 凭据、正式签名、服务器、公网 DNS/HTTPS 或 Git 发布；状态仍为 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。

## 2026-08-22 JourVolt 会话刷新地址收口

- 修复 `JourVoltSessionRefresher`：Debug Mock 会话过期时使用 `JOURVOLT_MOCK_BASE_URL`，正式 Debug/Release 云模式使用固定 `JOURVOLT_API_BASE_URL`；不会把本地 Mock 刷新请求误发到正式云地址。
- 保留原有单飞刷新、refresh token 轮换、失败清理和 Release HTTPS 校验；正式包不引入 loopback 或 Mock 路由。
- 新增登录链契约测试，覆盖 Mock/云地址分支。
- 本地门禁：Android JVM `229` tests，failures/errors/skips 均为 `0`；Debug/Release、Release lint、Go `test`/`vet`、Docker health/ready 和 `git diff --check` 通过。
- Release lint 仍为 `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- `emulator-5554` 仅重新安装 `com.matelink.test.mock` Debug 并启动原 Dashboard，车辆文案可见，最近 500 条日志无 FATAL/ANR；实体手机未操作。
- 最新未签名 Release SHA-256：`BC49FCDB3094DB8B0A74A74C0BB97301E0DF337E84A563FFBD86756256FDBE6E`。真实 Tesla OAuth/Fleet、正式签名和公网 Pilot 仍需外部门禁。

## 2026-08-22 原 Dashboard 云端车辆读取与错误语义收口

- 修复原 `DashboardViewModel` 的车辆选择链：云端返回的第一辆车与旧安装保存的 `currentCarId` 不一致时，自动保存服务端返回的用户内稳定 ID，后续轮询不会继续请求旧车辆。
- `ApiResult.Error` 保留既有 message/code，同时增加稳定 `ApiErrorKind`：授权失效、限流、服务不可用、网络、配置和响应格式等类别；不改变现有 TeslaMate 兼容接口和调用方的旧字段。
- 原 Dashboard 的 Partial 状态不再丢弃 `error` 参数。401/403、429、5xx 和网络失败分别显示重新授权、限流、服务暂不可用或网络错误说明；不再把授权失败误写成“车辆已连接、同步中”，也不生成实时数值。
- 新增 `ApiErrorKindTest` 与 `DashboardErrorMappingTest`；中文/英文新增对应错误文案，原 MateLink 页面结构、卡片风格和底部导航未改动。
- 本地门禁：Android JVM `234` tests，failures/errors/skips 均为 `0`；Debug/Release、Release lint、Go `test`/`vet`、Docker health/ready 和 `git diff --check` 通过。Release lint 为 `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- `emulator-5554` 仅覆盖安装 `com.matelink.test.mock` Debug 并启动原 Dashboard；UI dump 找到 Dashboard 和车辆文案，最近 500 条 logcat 无 FATAL/ANR。实体手机未操作。
- 最新未签名 Release SHA-256：`7818C9B849E7C183258371FBFC6F77D9EF78F11B6841007B50A0FD9CDD6F7EB6`。真实 Tesla OAuth/Fleet、正式签名、服务器公网配置和 Pilot 仍需外部门禁。

## 2026-08-22 Tesla 登录错误语义收口

- 原 Tesla 官方登录流程不变：仍由 Custom Tab 打开 Tesla 官方页面，Android 不接收或保存 Tesla 密码；本轮只把授权开始和一次性 ticket 交换的 HTTP 失败转换为用户可理解的状态。
- 400 显示授权请求配置问题，401/403 显示授权失效，429 显示 Tesla 限流，5xx 显示云登录暂不可用，其他状态显示可重试的通用错误；不再把 `HTTP 503` 原样暴露给用户。
- 新增 `TeslaLoginErrorMappingTest`；中文和英文资源均已补齐，未改变原登录页、自托管入口、Dashboard 回流或 Mock/Release 边界。
- 本地门禁：Android JVM `236` tests，failures/errors/skips 均为 `0`；Debug/Release、Release lint、Go `test`/`vet`、Docker health/ready 和 `git diff --check` 通过。Release lint 为 `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- `emulator-5554` 仅覆盖安装最新 `com.matelink.test.mock` Debug 并启动原 Dashboard；Dashboard 和车辆文案可见，最近 500 条 logcat 无 FATAL/ANR。实体手机未操作。
- 最新未签名 Release SHA-256：`E262FCBADF4B6DFE7CC7BEC23259A23681DA350AF3D5677E09BD862161B60B50`。真实 Tesla OAuth/Fleet、正式签名、服务器公网配置和 Pilot 仍需外部门禁。

## 2026-08-22 Mock 源集与语言发布边界收口

- Mock 登录实现已从 `src/main` 移到 `src/debug`；`src/release` 只保留同名空入口，正式 `com.matelink` Release 不依赖构建优化来隐藏 Mock UI。
- Mock 登录文案也移到 Debug 资源；Release APK 核对不到 `DebugMockLogin` 类或 `debug_mock_login` 资源。原 Tesla 官方 OAuth、原 Dashboard、原底部导航和自托管入口保持不变。
- Android 首发语言策略收口为中文和英文；不再保留不完整的 `values-de`、`values-fr`、`values-ja` 资源目录。`MissingTranslation=0` 继续作为发布门禁，不能把 lint findings 描述成运行时 Bug。
- 本轮 Android JVM Debug/Release 各 `237` tests，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline。
- 最新未签名 Release APK：`com.matelink` / MateLink / `1.4.2` / versionCode `14`，SHA-256 `A72EDDA56A3AE13645E710621682007BA5B881117011993F858E2ECA2F48478E`，大小 `60,966,848` bytes。该 APK 仍不是正式签名分发包。
- `emulator-5554` 仅覆盖安装 `com.matelink.test.mock` Debug 并启动原 Dashboard；UI dump 找到车辆、`Local mock`、刷新、设置和原 Dashboard/Drives/Charges/More 导航，最近 500 条 logcat 无 FATAL/ANR。实体手机、真实 Tesla、服务器公网 Pilot、正式签名和 Git 发布仍未执行。

# 2026-08-22 分析页费用与年度报告最小收口

- 费用页现在按价格覆盖判断是否显示费用图表和地点排行：无价格来源不再渲染看似为 `0` 的内容，真实免费 `0` 元仍保留。
- 年度报告年份筛选改用可横向滚动的 `LazyRow`；报告图表改用 Material 主题颜色，保持原卡片布局并兼容深色模式。
- 英文 `analysis_no_records` 改为 `No records yet`，中文保持 `暂无可分析记录`。
- 新增 `CostBreakdownTest`，覆盖无价格记录、真实免费价格和地点排行过滤。
- 本地门禁：Debug/Release 各 `241` 个 JVM 用例通过；Go test/vet、Docker health/ready、Debug/Release 构建、Release lint、`git diff --check` 通过。Release lint `258` findings、0 errors、`MissingTranslation=0`，按多语言覆盖/发布门禁记录。
- 最新参数化 Pilot Release：`android/app/build/outputs/apk/release/app-release-unsigned.apk`，包名 `com.matelink`，SHA-256 `CDD0562955D0746A853DED0A93A1E77173212949A6AC1290893072FA7D09732E`；API `https://api.jourvolt.com/`、App Link `auth.jourvolt.com`。
- Boundary：真实 Tesla OAuth/Fleet、DNS/HTTPS、服务器公网 Pilot、正式签名、实体手机和 Git 发布仍未执行；状态保持 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。

## 2026-08-22 最终本地门禁复核

- Go `test ./... -count=1`、`go vet ./...` 通过；本机 Docker `/healthz` 与 `/readyz` 仍为成功，模式为 `mock_only`，历史 fixture 为显式本地 Mock。
- Android 正确任务 `:app:testDebugUnitTest :app:testReleaseUnitTest :app:lintRelease :app:assembleDebug :app:assembleRelease` 通过；Debug/Release 各 `237` tests，failures/errors/skips 均为 `0`。
- Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；`258` 只记录多语言覆盖/发布门禁，不描述成运行时 Bug。
- 最新未签名 Release 为 `com.matelink` / MateLink / `1.4.2` / versionCode `14`，SHA-256 `A72EDDA56A3AE13645E710621682007BA5B881117011993F858E2ECA2F48478E`；APK 归档没有 `DebugMockLogin` 或 `debug_mock_login`。
- `emulator-5554` 覆盖安装 Debug 后 UI dump 找到 Dashboard、`Development Model 3`、`Local mock`、Drives/Charges/More；最近 500 条应用日志 `FATAL_MARKERS=0`。实体手机、真实 Tesla 凭据、正式签名、公网 Pilot 和 Git 发布仍未执行。

## 2026-08-22 Tesla 登录回流契约保护

- 新增 `TeslaAuthNavigationContractTest`，锁定认证状态成功后必须执行 `onLoginSuccess()`，导航必须清除 `TeslaLogin` 返回栈并进入原 `Dashboard`；因此 OAuth 成功不会再次落入 Mock 单页或停留在登录页。
- 同一测试锁定 `MainActivity.onNewIntent()` 更新 Compose 使用的 intent，保证 Activity 已运行时收到新的 App Link 一次性票据仍能继续交换会话。
- Android Debug/Release 各 `239` 项 JVM 测试通过，failures/errors/skips 均为 `0`；Release lint/build 和 `git diff --check` 通过。
- Release lint 为 `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量是多语言覆盖/发布门禁统计，不是运行时 Bug 数量。
- 最新未签名 Release APK SHA-256：`BB2A7D64D8B45E9B2DA866E84CD906D47D292C24C80C6858B52425A3FE933AD1`。实体手机、正式签名、公网 Tesla OAuth/Fleet 和真实车辆仍未验证。

## 2026-08-22 当前注销回流与 Pilot 产物

- 注销链已补齐：Go `DELETE /v1/account` 删除 JourVolt 侧 token/session/车辆数据；已配置 Fleet OAuth 时返回 `tesla_consent_revoke_url`，Android 注销成功后打开 Tesla 官方 consent 管理页。服务端不把本地删除冒充为 Tesla 远端自动撤销。
- 新增 Go `TestConsentRevokeURLUsesConfiguredTeslaRegion` 和 Android 注销回流契约；当前正式 Debug/Release JVM 测试各 `243` 项，failures/errors/skips 均为 `0`。
- Go `test ./... -count=1`、`go vet ./...`、Android Debug/Release 构建、`lintRelease`、`git diff --check` 通过。Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；258 是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新参数化未签名 Pilot APK：`android/app/build/outputs/apk/release/app-release-unsigned.apk`，包名 `com.matelink`，SHA-256 `AD95D280FC857F2F39EE121FE9132AF64D1EE048376208E5064C72BB72B82D6A`；API `https://api.jourvolt.com/`、App Link `auth.jourvolt.com`。
- 最新无密钥部署包：`E:/Claude_allow/Download/jourvolt-pilot-bundle-revoke-20260822`；`secrets_included=false`，敏感文件计数 `0`。隔离端口 `18190` 的 bundle Compose 烟测返回 `/healthz` 与 `/readyz` `status=ok`、`mode=fleet`、`persistence=postgres`，临时资源已清理。
- Boundary：真实 Tesla OAuth/Fleet、域名/DNS/公网 HTTPS、正式签名、服务器采购、实体手机和 Git 发布仍未执行；状态保持 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。

# 2026-08-22 本轮工作树最新验证

- 本轮修改：费用缺失来源不再渲染空零值图表/地点排行；真实免费 `0` 元保留；年度报告年份筛选可横向滚动；报告图表使用主题颜色；英文空状态改为 `No records yet`。
- `CostBreakdownTest` 2 项通过；Android Debug/Release 各 `241` JVM 测试通过，failures/errors/skips 均为 `0`；Go test/vet、Docker health/ready、Debug/Release、Release lint 和 `git diff --check` 通过。
- Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 APK：`android/app/build/outputs/apk/release/app-release-unsigned.apk`，`com.matelink`，SHA-256 `9C1F6B6C39B1DC9F882F0F05304209645F80C3F50380AAC1F4FE8D55DC4B4B6D`，无 Mock 登录名称。
- Boundary：宿主 Emulator/ADB 未注册 `emulator-5554`；实体手机、真实 Tesla OAuth/Fleet、服务器公网、正式签名、DNS/HTTPS 和 Git 发布仍未执行。

## 2026-08-22 Release Mock 边界收口与设备门禁结果

- 正式版 Mock 行为继续 fail-closed：`SettingsRepository` 在 `JOURVOLT_MOCK_LOGIN=false` 时强制忽略历史 `mock_mode` 偏好；Dashboard 收到 `mock_fixture` 来源时，正式版显示“数据不可用”，Debug 测试包仍保留本地 Mock 证据显示。
- Android `testDebugUnitTest`、`testReleaseUnitTest` 各 `243` 项通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。
- Release lint 正确统计为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，没有被跟踪的 lint baseline；258 只表示多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- Release APK 静态扫描未命中 `127.0.0.1`、`10.0.2.2`、`MockTesla`、`mock_fixture`、`Local mock`、`debug_mock_login` 或 `JOURVOLT_MOCK_LOGIN`；包名 `com.matelink`、versionName `1.4.2`，SHA-256 `649C31CDC0932A8D81A2B4050793E12EF7A65FECCD8BA0AD8A6B80CEC789A5FA6`，仍为未签名产物，不可直接分发。
- 隔离 AVD `MateLink_P0_Qualification_API35` 使用绝对 SDK 路径重试两次仍未注册 `emulator-5554`，宿主没有 5554/5555 监听；只停止本轮明确属于该 AVD 的进程，未运行 instrumentation、未清除 AVD 数据、未操作实体手机。当前页面运行时回归仍为 `NOT_PERFORMED`。

## 2026-08-22 注销回流地址修正后的交付状态

- Android 端仍只消费服务端返回的受信任 Tesla 官方 consent 管理页 URL；本轮后端修正了完成官方撤销后的 `back_url`，使其返回 JourVolt App Link 域名的 `/privacy/` 页面。
- 本轮未改变 Android 源码和 APK；当前参数化未签名 Pilot APK 仍为 `com.matelink`，SHA-256 `AD95D280FC857F2F39EE121FE9132AF64D1EE048376208E5064C72BB72B82D6A`。
- 该修正已通过 Go `test ./... -count=1`；Android 既有 `243` 项 Debug/Release JVM 门禁和 Release 构建证据保持有效。真实 Tesla 回调、正式签名和公网 App Link 仍未执行。

## 2026-08-22 统计与充电缺失值继续收口

- 统计覆盖期在日期存在但观测天数缺失时不再拼接 `0` 天；只显示已知日期范围。
- 充电汇总只聚合有限且实际存在的能量/费用字段；字段缺失时显示不可用，真实观测到的 `0` 仍保留。充电能量图表增加覆盖判断，避免把缺失值绘成零柱。
- Android Debug/Release 各 `246` 个 JVM 用例通过，合计 `492`，`assembleDebug`、`assembleRelease`、`lintRelease` 通过；Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline。该数量只表示多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 Release APK：`com.matelink` / `1.4.2`，SHA-256 `1E28924C7C81CEA02FE31EC63C6F064F60D36970B10BD96C66F5EF21DD945A9D`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- 页面运行时仍为 `NOT_PERFORMED`：宿主 AVD 未向 ADB 注册 `emulator-5554`；真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名、实体手机和 Git 发布仍未执行。

## 2026-08-22 充电能量边界测试补充

- 新增 `ChargeSummaryMetricTest`，锁定缺失/NaN/负数能量不可用、真实零值可用的汇总边界。
- Debug/Release 全量 JVM 各 `248` 项，合计 `496`，failures/errors/skips 均为 `0`；最新 APK SHA-256 为 `469C349EDC95F9955B4FF3F7789E73DFC104967A576B94332B1E43718FF0E400`。

## 2026-08-22 本机 Docker HTTP 回归与 ADB 根因

- 当前 Docker JourVolt API/PostgreSQL 实例在 `127.0.0.1:18090` healthy；`/healthz` 返回 `mock_only / postgres / mock_history=true`，`/readyz` 返回 `ok`。
- 本机 HTTP 回归通过：Mock 登录 → 1 台车辆 → 原兼容快照 `charging / 76% / mock_fixture` → 18 条行程、5 条充电 → 注销；注销后旧 access token 返回 `401`。
- AVD 宿主诊断：补齐 `ANDROID_SDK_HOME` 后 `emulator -list-avds` 可发现 AVD，QEMU/WHPX 启动并报告 Android boot completed（约 39 秒），但宿主 `adb` 始终无 `emulator-5554`、5554/5555 监听；重启 ADB、外部 adb、`-no-direct-adb` 均未改变结果。
- 结论：当前页面运行时回归阻塞在宿主 Emulator↔ADB 通道，不是 App 登录或本机 Docker API 失败；未运行 instrumentation、未清除 AVD、未操作实体手机。

## 2026-08-22 电池缺失值与本地发布候选复核

- 电池页现在在 ViewModel/UI 边界保留 Tesla 实际观察字段：缺少电量或续航时详情页不再渲染 `0%`、`0 km`；真实 `0%` 仍作为有效观察值显示。容量趋势、状态卡和续航卡仍沿用原 MateLink 结构。
- 新增 `BatteryStatsEvidenceTest`，覆盖缺失字段、真实零电量和独立续航字段三种边界。
- Android Debug/Release 各 `246` 个 JVM 用例通过，合计 `492`，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease`、Go test/vet 和 `git diff --check` 通过。
- Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 baseline；258 仍按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- 当前未签名 `com.matelink` / MateLink / `1.4.2` APK SHA-256：`333CC50B332008A2F45C1A5C0C34737FE97F3D0FF66DEA0FC6ADD55C91FF5FC0`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- 页面交互回归仍为 `NOT_PERFORMED`：宿主 AVD 可 boot 但未向 ADB 注册 `emulator-5554`；没有运行 instrumentation、清除 AVD 或操作实体手机。

## 2026-08-22 本地交付继续收口

- 原 `com.matelink`、原 Dashboard 和原视觉结构保持不变。本轮只修复分析摘要与充电详情的缺失值边界：未知不再被渲染为零，真实零值仍可见。
- Android Debug/Release 全量 JVM 各 `252` 项通过；`assembleDebug`、`assembleRelease`、`lintRelease`、Go test/vet 通过。lint 的 `258` findings 继续归类为多语言覆盖/发布门禁问题，`MissingTranslation=0`，无 baseline。
- Release APK 静态扫描通过，未发现回环地址、Mock provider、Debug 登录标记或错误包名。未签名 APK 不能用于正式发布，仍需正式签名后才能进行 App Link/覆盖安装验收。
- 页面运行时回归仍是 `NOT_PERFORMED`：宿主 AVD 可 boot 但没有形成 `emulator-5554` ADB 设备；未运行 instrumentation、未清除 AVD、未操作实体手机。

## 2026-08-22 建议引擎证据收口

- 建议分组现在显式要求有限的实际速度；缺失速度不会被当成可比较的数值，也不会生成高速/基准/低温个性化建议。
- Android Debug/Release 全量 JVM 各 `253` 项通过，`assembleDebug`、`assembleRelease`、`lintRelease` 通过；`MissingTranslation=0`，无 lint baseline。
- 最新未签名 Release APK SHA-256：`F03C42FEEBABE8309BC85D4185366BB00579BB3814C79FCF89701630DEC240F5`。页面运行时仍因宿主 AVD 未形成 `emulator-5554` 而保持 `NOT_PERFORMED`。

## 2026-08-22 Room 摘要进入分析前的证据归一化（当前最新）

- `StatsRepository` 在生成建议和覆盖率前，先把 Room 摘要映射回中性 API 数据模型；旧数据库为了兼容保留的零占位值不会绕过缺失值判定。
- 建议输入的距离、充入电量改为可空；只有有限且满足阈值的真实输入才参与分组和聚合。新增映射边界断言，未改变原 MateLink 页面结构。
- Android Debug/Release 全量 JVM 各 `253` 项通过；`assembleDebug`、`assembleRelease`、`lintRelease`、Go test/vet 通过。Release lint `258` findings 仍归类为多语言覆盖/发布门禁问题，`MissingTranslation=0`，无 baseline。
- 最新未签名 Release APK SHA-256：`FB632B7E23F93F06C96A7A9590CB17EECF6ACA0240C1CE9D6A58C51595B7A26F`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。

## 2026-08-22 分析摘要覆盖率门禁（当前最新）

- `AnalysisSummary` 和结论卡现在接收 `AnalysisCoverage`：Room 旧摘要存在记录但没有有效距离、能耗、充电或费用样本时，指标保持不可用，不再把兼容零占位值渲染成真实结果；真实观测到的 `0` 仍可用。
- 新增覆盖率边界测试，Stats 页面改为把覆盖证据传入摘要构建器；未改变原 `com.matelink`、原 Dashboard、原导航和整体视觉结构。
- Android Debug/Release JVM 各 `254` 项通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease`、Go test/vet 和 `git diff --check` 通过。
- Release lint 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 lint baseline；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 Release APK：`com.matelink` / `1.4.2`，SHA-256 `EF0A6E7BD2673E7622159E0FC8AB50BE95726564C967CD13A78341097C74E61C`；已复制到 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-summary-coverage.apk`。静态扫描未命中回环地址、Mock provider、Debug 登录标记或 `com.jourvolt.app`。
- 页面运行时仍为 `NOT_PERFORMED`：第三次不同参数的 AVD 启动尝试仍能 boot 但未向 ADB 注册 `emulator-5554`；未运行 instrumentation、未清除 AVD、未操作实体手机。真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名和 Git 发布仍未执行。

## 2026-08-22 概览卡覆盖率一致性（当前最新）

- 统计页驾驶概览、充电概览和年度报告摘要现在复用 `AnalysisCoverage`；记录存在但有效距离、驾驶能耗或充入电量样本为 `0` 时显示不可用，真实观测零值仍保留。
- 成本/每百公里和每 kWh 只有在成本与对应距离/充电能量证据同时存在时显示；不再由记录数量单独推导金额或效率。
- Android Debug/Release JVM 各 `254` 项通过，`assembleDebug`、`assembleRelease`、`lintRelease`、Go test/vet 和 `git diff --check` 通过。Release lint `258` findings 继续归类为多语言覆盖/发布门禁问题，`MissingTranslation=0`，无 baseline。
- 最新未签名 APK SHA-256：`D40A5F7A4C70CAC13C829DCE46C706DB8C0E353EC615CC271B94FD55D5D90F72`；候选文件：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-summary-coverage-v2.apk`。静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。

## 2026-08-22 Release Mock 来源构建隔离与手机门禁（当前最新）

- 修复正式 APK 中残留 `mock_fixture` 字面量的问题：Mock 来源只通过 Debug 的 `JOURVOLT_MOCK_SOURCE` 注入；Release 字段为空，`TeslamateRepository` 同时受 `JOURVOLT_MOCK_LOGIN=false` 硬门禁保护，Dashboard 在 Release 将 Mock 来源视为不可用。Debug 本地 Mock 链路和原 MateLink UI 不变。
- `testDebugUnitTest`、`testReleaseUnitTest` 各 `254` 项通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 全部 `BUILD SUCCESSFUL`。Go `test ./...`、`go vet ./...` 通过，`git diff --check` 退出码 `0`（仅有 Windows 换行提示）。
- Release lint 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 lint baseline；258 只记录多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 Release APK 为 `com.matelink` / `MateLink` / `1.4.2`，SHA-256：`B1FF4C3F16991EA0B79969B4E641D222CA88C90BE4339FEC41D636279A161044`；候选文件：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-pdf-v6.apk`。静态扫描未命中 `127.0.0.1`、`10.0.2.2`、Mock provider、Debug 登录标记或 `com.jourvolt.app`。
- 电脑侧 ADB 使用 Android SDK `platform-tools\adb.exe` 枚举结果为空，没有可安全核对的实体手机序列号；因此本轮未读取 `com.matelink` 包/签名，也未执行安装、卸载、清数据或 instrumentation。未签名 APK 也不能替代正式签名覆盖安装验收。
- 当前状态仍为 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。下一步需要先让手机开启 USB 调试并在 ADB 中出现，再做只读包名/签名核对；真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、正式签名和 Git 发布仍未执行。

## 2026-08-22 分析页空状态与实时数据证据收口（当前最新）

- 分析历史新增统一判定：无数据源记录、正在采集、当前筛选无记录、已有记录但字段覆盖不足分别显示，不再把筛选空集或字段缺失误报为“尚未采集”。效率、成本、续航、待机四个 ViewModel 已接入该判定，中文/英文文案已补齐。
- 电池页的“满电续航估算”只有在实时 SOC 与额定续航同时被观察到时才计算；缺少任一字段不再用健康接口的当前续航或默认 `0%` 推导。正在充电卡片的缺失 SOC/目标电量/充入能量显示不可用，不绘制不完整进度条；真实观测到的 `0` 仍保留。
- 未改变原 `com.matelink`、Dashboard、底部导航和既有视觉结构；本轮只收口数据状态表达与计算证据边界。
- Android Debug/Release `test*UnitTest` 各 `255` 项通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease`、Go `test ./...`、`go vet ./...`、Docker health/ready 和 `git diff --check` 通过。
- Release lint 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 lint baseline；这 `258` 条是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 Release APK 为 `com.matelink` / `MateLink` / `1.4.2`，SHA-256：`AF71E1E85600C9CA377E532BB0180D8BD5B0DE49981A70DE19F4536FFCDCABD1`；候选文件：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-analysis-state-v7.apk`。静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`；未签名产物不可覆盖安装。
- ADB `devices -l` 当前为空，未读取实体手机包名/签名，未安装、卸载、清数据或运行 instrumentation。当前仍为 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`；真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、正式签名、App Link 和 Git 发布仍未执行。

## 2026-08-22 Android 设备验证尝试（当前最新）

- 已重新启动 ADB，并尝试使用专用 AVD `MateLink_P0_Qualification_API35` 做 Debug Mock 回归；QEMU 曾启动但始终未注册为 `emulator-5554`，随后退出。
- 实体手机和模拟器均未出现在 `adb devices -l`；因此本轮没有安装 APK、卸载应用、清除数据或运行 instrumentation。该结果是 `NOT_PERFORMED`，不是安装成功或页面通过。
- 最新 Release 候选仍为未签名 `com.matelink` / `1.4.2`：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-drive-detail-evidence-v9.apk`，SHA-256 `23973805D55A94E8F09AC0533A16DAB2402809104045AD6059AE168252C784DE`；未签名 APK 不得覆盖实体手机。
- Jovi 连接手机后需开启 USB 调试并接受 RSA 授权，使 `adb devices -l` 显示唯一序列号；后续先做包名/签名只读核对，再按同签名 `adb install -r` 门禁执行。

## 2026-08-22 本地 API 与长时模拟器复核（当前最新）

- 专用 AVD 使用 5554、5556 端口并延长到 120 秒启动，均未注册 ADB；没有新增模拟器 UI 证据。
- 本机 Docker Mock API 复核通过：Mock 登录 `200`、车辆列表 `200`（1 辆）、`/api/ping` `200`、能力接口 `200`；refresh token 轮换 `200`，旧 access token `401`、新 token `200`；退出后 token `401`。
- 以上是 `LOCAL MOCK API PASS`，不等于 Android 页面运行时通过，也不等于真实 Tesla OAuth/Fleet 通过。实体手机仍需先出现在 ADB，且正式覆盖还需要匹配签名。

## 2026-08-22 行程详情与摘要证据收口（当前最新）

- `DriveDetailStats` 改为保留可空观测边界：缺少速度、功率、海拔、电池、距离或时长时，原行程详情显示不可用，不再把字段回填为 `0`；真实观测到的 `0` 仍保留。海拔继续使用原页面结构并按用户单位格式化。
- 行驶摘要的最高速度缺失时不再显示 `0 km/h`；无有效筛选记录时摘要四项显示不可用，最高速度图表排除没有速度证据的时间桶。列表、筛选、导航、原 `com.matelink` 包名和视觉结构未改动。
- 新增 `DriveDetailStatsEvidenceTest`、`DrivesSummaryEvidenceTest`。Android Debug/Release JVM 各 `263` 项通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease`、Go `test ./...`、`go vet ./...`、Docker `/healthz`/`/readyz` 和 `git diff --check` 通过。
- Release lint 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 lint baseline；这 `258` 条是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 Release APK 为 `com.matelink` / `MateLink` / `1.4.2`，SHA-256：`23973805D55A94E8F09AC0533A16DAB2402809104045AD6059AE168252C784DE`；候选文件：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-drive-detail-evidence-v9.apk`。静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`；未签名产物不可覆盖安装。
- ADB `devices -l` 当前为空，未读取实体手机包名/签名，未安装、卸载、清数据或运行 instrumentation。当前仍为 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`；真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、正式签名、App Link 和 Git 发布仍未执行。

## 2026-08-22 里程统计证据收口与电池趋势兜底修正（当前最新）

- 新增共享 `MileageEvidence`：年度、月份、日期和单次行程分别统计有效距离、能耗和电量差样本；记录存在但字段缺失时，页面显示不可用，不再把缺失值渲染成 `0`。没有有效距离的月份/日期也不再进入图表。
- 里程聚合改为只累加有限且满足约束的观测值；真实 `0 kWh` 和 `0%` 电量差仍被保留。电池趋势的退化计算移除基线/当前值的默认零值兜底，缺少有效输入时保持不可用。
- 未改变原 `com.matelink`、Dashboard、导航、页面布局和视觉风格；本轮只补齐里程/电池指标的证据边界。
- Android Debug/Release 各 `258` 项 JVM 测试通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease`、Go `test ./...`、`go vet ./...`、Docker health/ready 和 `git diff --check` 通过。
- Release lint 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 lint baseline；这 `258` 条仍是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 Release APK 为 `com.matelink` / `MateLink` / `1.4.2`，SHA-256：`FCCB8DFDC92C783C22C95BACD3E1B7F4435756F2F7EE8F56DE41A0B8220F9566`；候选文件：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-mileage-evidence-v8.apk`。静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`；未签名产物不可覆盖安装。
- ADB `devices -l` 当前为空，未读取实体手机包名/签名，未安装、卸载、清数据或运行 instrumentation。当前仍为 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`；真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、正式签名、App Link 和 Git 发布仍未执行。

## 2026-08-22 App Link 一次性 ticket replay 防护（当前最新）

- `TeslaLoginViewModel` 现在区分 in-flight 与已处理的 callback ticket；Activity 重建或重复 App Link 不会再次交换同一个一次性 ticket，也不会把已登录用户误导到一次无意义的 `401`。
- 新增 `TeslaCallbackReplayGuardTest`，覆盖新 ticket、重复 in-flight、已处理和空 ticket；Android Debug/Release JVM 各 `267` 项通过，0 failures/errors/skips。
- `assembleDebug`、`assembleRelease`、`lintRelease` 通过；Release lint 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline。最新未签名 Release SHA-256：`299C56B94F8DAF8F2505D9C5DD77FD5CE7B0105CFCFBDF2E9C7A75F87F6B6925`；候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-callback-replay-guard-v10.apk`。
- Release 静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。APK 仍未签名；ADB 为空，未安装、卸载、清数据或 instrumentation。该结果仍是本地代码/构建证据，不是实体手机或真实 Tesla Pilot 通过。

## 2026-08-22 OAuth callback 并发状态隔离与最终本地门禁（当前最新）

- `TeslaLoginViewModel` 在一次性 ticket 防重复交换之外，进一步隔离已取消/过期 callback 请求的失败回调：只有仍属于当前 ticket 的请求才可以写入 Error 状态，旧请求不会覆盖新登录流程的 Loading 或成功状态。
- `TeslaCallbackReplayGuardTest` 覆盖新 ticket、in-flight 重复、已处理重复、空 ticket；Android Debug/Release 各 `267` 个 JVM 用例通过，failures/errors/skips 均为 `0`。
- 重新执行 `testDebugUnitTest`、`testReleaseUnitTest`、`assembleDebug`、`assembleRelease`、`lintRelease`，全部 PASS（Gradle 退出码 `0`）。Release lint 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；这 `258` 条是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 Release APK 为 `com.matelink` / `MateLink` / `1.4.2`，SHA-256：`541F88C8C6AED2833C47093E86C767224983D5FC4D8879E499880C54DC326221`；候选文件：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-callback-replay-guard-v12.apk`。
- Release APK 静态扫描未命中 `127.0.0.1`、`10.0.2.2`、`MockTesla`、`mock_fixture`、`Local mock`、`debug_mock_login`、`JOURVOLT_MOCK_LOGIN` 或 `com.jourvolt.app`。当前没有正式签名密钥，候选不可直接分发或覆盖原安装。
- `adb devices -l` 仍为空；本轮未安装 APK、卸载应用、清理数据或运行 instrumentation。该结果是本地代码/构建证据，不是实体手机 UI 或真实 Tesla OAuth/Fleet 通过。

## 2026-08-22 Tesla 登录请求代次隔离与最终本地门禁（当前最新）

- `TeslaLoginViewModel` 为 `/v1/auth/tesla/start` 和 callback exchange 增加请求代次校验；新登录、App Link callback、自托管切换、退出或注销会使旧请求失效。旧请求的取消异常不会再覆盖新流程的状态，也不会继续打开旧授权页或写入旧 session。
- 新增 `TeslaRequestGenerationTest`，覆盖当前请求可发布、过期请求不可发布；Android Debug/Release 各 `269` 个 JVM 用例通过，failures/errors/skips 均为 `0`。
- `testDebugUnitTest`、`testReleaseUnitTest`、`assembleDebug`、`assembleRelease`、`lintRelease` 全部 PASS，Gradle 退出码 `0`；Go `test ./... -count=1`、`go vet ./...`、Docker `/healthz`/`/readyz`（`200/200`）和 `git diff --check` 通过。
- Release lint 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；这 `258` 条是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 Release APK 为 `com.matelink` / `MateLink` / `1.4.2`，SHA-256：`3C7588D70F418E9C29124BCFB2A7D0C0CAC7866A34B808D743923EFBCD8C86BB`；候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-login-generation-v13.apk`。Release 静态扫描仍未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- `adb devices -l` 仍为空；本轮没有安装、卸载、清数据或 instrumentation。当前证据仍是本地代码/服务/构建通过，不是实体手机 UI 或真实 Tesla OAuth/Fleet 通过。

## 2026-08-22 Debug Mock 登录代次隔离后的最终本地门禁（当前最新）

- Debug-only `DebugMockLoginViewModel` 复用请求代次校验并忽略取消异常；快速重复点击本地 Mock 登录时，旧请求不能覆盖新状态或写回旧 session。该修复不进入正式 Release 源集。
- Android Debug/Release 各 `269` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`testDebugUnitTest`、`testReleaseUnitTest`、`assembleDebug`、`assembleRelease`、`lintRelease` 全部 PASS，Gradle 退出码 `0`。
- Go `test ./... -count=1`、`go vet ./...`、Docker `/healthz`/`/readyz`（`200/200`）和 `git diff --check` 通过。Release lint 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 Release APK 为 `com.matelink` / `MateLink` / `1.4.2`，SHA-256：`3C7588D70F418E9C29124BCFB2A7D0C0CAC7866A34B808D743923EFBCD8C86BB`；候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-login-generation-v14.apk`。静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- `adb devices -l` 仍为空；本轮没有安装、卸载、清数据或 instrumentation。v14 仍为未签名候选，不可直接覆盖已有 MateLink。

## 2026-08-22 Locale 发布质量修复后的最终本地门禁（v15）

- 显式 Locale 已补齐到报告、成本、Dashboard、效率、费率和待机页面格式化；日志时间格式固定使用 `Locale.ROOT`。原界面、单位和算法未重做。
- Android Debug/Release 各 `269` 个 JVM 用例通过，`assembleDebug`、`assembleRelease`、`lintRelease` 全部通过；`git diff --check` 通过。
- Release lint 降至 `231` findings，`MissingTranslation=0`、无 baseline、0 Error；已清除 `DefaultLocale` 与 `ConstantLocale`，其余 finding 仍按多语言覆盖/发布门禁问题记录，不描述为运行时 Bug。
- 最新未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-locale-v15.apk`，`com.matelink` / `1.4.2`，SHA-256 `B11042EB9B8C9138BA68341E3AA085C12332AD545A90C1D58C4D20B5C045524E`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- 隔离 Debug 包：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-locale-v15.apk`，`com.matelink.test.mock`，SHA-256 `D65DE024D3894807582DDD0C910A74173E5F0105D5A649B003F70FEADBC5E48E`；仅用于模拟器/隔离设备。
- ADB 仍为空；未安装、卸载、清数据或 instrumentation。正式签名、真实设备 UI、域名/HTTPS、Tesla 批准和真实 OAuth/Fleet 仍未完成。

## 2026-08-22 发布门禁进一步收口（v16）

- 移除 API 26 已不可能执行的图标资源目录兼容分支；保留 API 29/36 仍需要的条件。
- Release lint 降至 `194` findings，`MissingTranslation=0`、无 baseline、0 Error；`DefaultLocale`、`ConstantLocale`、`TypographyEllipsis`、`TypographyDashes`、`ObsoleteSdkInt` 均为 0。
- 最新未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-lint194-v16.apk`，包名 `com.matelink`，SHA-256 `0090C25FCF3D0D69FED597389FAEB23045E8BA4A963C616F13AAB76DBDDB03CF`。
- 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-lint194-v16.apk`，包名 `com.matelink.test.mock`，SHA-256 `54420A3C8BF9D829F5D2962185B2D3D619E536D6DEE4F4C433DABAB81FDFB8CC`；仅用于模拟器/隔离设备。
- Debug/Release 各 `269` 个 JVM 测试通过，构建和静态扫描通过；ADB 仍为空，未进行实体设备安装。

## 2026-08-22 统计综合分析页接入原有里程钻取（v17）

- 保留 MateLink 原有统计页、卡片和主题；在综合分析卡底部新增“查看里程分解”入口，复用现有 `MileageScreen` 的年度→月份→日期→行程详情链路，不重复创建另一套统计页面。
- 移除统计页中已过时的 Android/iOS parity TODO；导航由 `StatsScreen` 经 `NavGraph` 接入 `Screen.Mileage`，中英文资源均已补齐。
- Android Debug/Release 各 `269` 个 JVM 测试通过，`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- Release lint 为 `194` findings，`MissingTranslation=0`、无 baseline、0 Error；该数量继续按多语言覆盖/发布门禁问题记录，不是运行时 Bug 数量。
- 新未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-analysis-drilldown-v17.apk`，包名 `com.matelink`，SHA-256 `BD1999A77B5444584F948B2D3543CF3E50FAD3F1663D79BDB2BCF36AE01E338E`。
- 新隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-analysis-drilldown-v17.apk`，包名 `com.matelink.test.mock`，SHA-256 `A9FB3A45B226E258FC9F5015A1BFB2B66A63CA6BCF80507EF0E05CAA627C9DAA`；只用于模拟器/隔离设备。
- `adb devices -l` 仍为空；未安装、卸载、清数据或 instrumentation。该 APK 证据不代表实体设备 UI、正式签名或真实 Tesla OAuth/Fleet 已通过。

## 2026-08-22 本地 Docker 复建与无密钥 Pilot bundle（v18）

- 使用当前工作树重新构建并启动 `deploy/jourvolt-dev-mock`；`/healthz`、`/readyz` 均为 `ok`。
- 新增并运行 `deploy/jourvolt-dev-mock/smoke.ps1`：Mock 登录、1 台车辆、快照 `charging / 76% / mock_fixture`、18 条行程、5 条充电和注销后旧 access `401` 均通过；证据标记为 `LOCAL MOCK PASS`。
- 新无密钥 bundle：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v18`，包含 smoke 脚本；bundle 内 Go test/vet 和 Pilot Compose 配置校验通过，manifest `secrets_included=false`，敏感文件计数 `0`。
- ZIP：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v18.zip`，SHA-256 `60740D9757EB1DC2AC2BD2769DED4774B71F333DDD6F140C072E3EB68C2B4260`。
- 本地服务和 bundle 仍不代表公网部署、正式签名、Tesla 应用批准、真实 OAuth/Fleet 或实体设备 UI 已通过。

## 2026-08-22 Docker smoke 计数修正与 Pilot bundle（v19）

- 修正 `smoke.ps1` 对兼容接口嵌套响应的计数：现在读取 `data.drives` 与 `data.charges`，实际输出 `18` 条行程、`5` 条充电；v18 bundle 已被本版本替代。
- 修正版 smoke 通过：`LOCAL MOCK PASS`、1 台车辆、`charging / 76% / mock_fixture`、18 条行程、5 条充电、`logout_revocation=PASS`。
- 最新无密钥 bundle：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v19`；Go test/vet、Pilot Compose 配置、manifest `secrets_included=false` 和敏感文件扫描 `0` 通过。
- ZIP：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v19.zip`，SHA-256 `CD2E1299F61C058BB37AB254A13DF5A27A27F43A85389AC87BBB2336DC4AC844`。

## 2026-08-22 Stats 综合分析使用模式补强（v20）

- 保留原 MateLink 统计卡片和导航，仅在现有综合分析卡中增加“使用模式”：平均行程时长、驾驶天数、平均充电时长、记录最高速度。
- 新增值按观测值/派生值标记；无有效输入显示暂无记录，不显示虚假 `0`。Stats 页面金额、百分比、数量和新增时长格式化显式使用当前 Locale。
- Debug/Release 各 `269` 个 JVM 用例通过；`assembleDebug`、`assembleRelease`、`lintRelease`、`git diff --check` 通过。
- Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error；该数量按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- 未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-usage-summary-v20.apk`，`com.matelink`，SHA-256 `ED7E78E6978C2B5D08E5F5B37E15D0E798193F0494414BCA0B08E37AE8C702E9`。
- 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-usage-summary-v20.apk`，`com.matelink.test.mock`，SHA-256 `2E13F49F5C2C63B8C8C9C0280BA044B23FEFE99B8E25D4E4172538A56927E149`；只用于隔离测试，不作为正式 App 交付。
- `adb devices -l` 仍为空；没有实体手机安装、卸载、清数据或 instrumentation。正式签名、真实 Tesla OAuth/Fleet、公网域名/HTTPS仍待外部门禁。

## 2026-08-22 Tesla 授权 URL fail-closed 安全门禁（v21）

- `TeslaLoginViewModel` 在打开 Custom Tab 前校验服务端返回的授权 URL：仅接受 `https://auth.tesla.cn/oauth2/v3/authorize` 或 `https://auth.tesla.com/oauth2/v3/authorize`，并要求 `client_id`、`redirect_uri`、`response_type=code`、`openid` 和 `offline_access`。
- 伪造域名、明文 HTTP、错误路径和缺失最小 scope 均拒绝，不进入浏览器；解析使用纯 JVM `java.net.URI`，便于本地测试并保持 fail-closed。
- Tesla 中国官方第三方令牌流程文档：`https://developer.tesla.cn/docs/fleet-api/authentication/third-party-tokens`。
- Debug/Release 各 `271` 个 JVM 用例通过；`assembleDebug`、`assembleRelease`、`lintRelease`、`git diff --check` 通过。
- Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error；该数量按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- v21 未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-tesla-url-guard-v21.apk`，`com.matelink`，SHA-256 `C2CBC72BF8C462971A2C866DD203538629D21EE3890B31C9E9FA65F22FB16C1C`。
- `adb devices -l` 仍为空；实体安装、正式签名、真实 Tesla OAuth/Fleet、公网域名/HTTPS仍待外部门禁。

## 2026-08-22 登录错误文案资源化与 v22 本地门禁

- Tesla 登录取消、授权暂不可用、授权交换失败三条 fallback 文案已移入中英文资源；登录 ViewModel 不再硬编码英文错误文本。
- Debug/Release 各 `271` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- v22 未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-localized-login-errors-v22.apk`，包名 `com.matelink`，SHA-256 `A1811C38159F810AE5C9DF9205B634645B5BD3BD84D8D2B9380766F61ECF43F8`。
- v22 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-localized-login-errors-v22.apk`，包名 `com.matelink.test.mock`，SHA-256 `5D3D068C5A2F94E2591198446F711A8638BB1D8C61646AFC839B32520F1A83C`；仅用于模拟器/隔离设备，不进入正式 Release。
- 当前 Docker smoke 仍为 `LOCAL MOCK PASS`：1 台车辆、`charging / 76% / mock_fixture`、18 条行程、5 条充电、`logout_revocation=PASS`。
- `adb devices -l` 仍为空；未安装、卸载、清数据或 instrumentation。Release 未签名，不能覆盖已有正式签名；正式签名、真实 Tesla OAuth/Fleet、公网域名/HTTPS仍待外部门禁。

## 2026-08-22 云登录配置错误文案资源化与 v23 门禁

- 云登录未配置时的英文硬编码已替换为中英文资源 `tesla_login_error_not_configured`；不改变 Release fail-closed 配置门禁，也不增加假登录入口。
- Debug/Release 各 `271` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- v23 未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-localized-login-errors-v23.apk`，包名 `com.matelink`，SHA-256 `1EE6F45FC1D4BF8E45AD81005CE9C8208F07E507F4FFDD0A5A33E65BE7B94DBE`。
- v23 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-localized-login-errors-v23.apk`，包名 `com.matelink.test.mock`，SHA-256 `FD0421406419C0073BB6FF863A1E197502C452B4C89ED1D1D914B928E762E50D`；仅用于隔离模拟器/测试设备。
- ADB 仍为空；专用 AVD 本次未注册 5554/5555，已停止刚才启动的隔离进程。未安装、卸载、清数据或 instrumentation；正式签名、真实 Tesla OAuth/Fleet、公网域名/HTTPS仍待外部门禁。

## 2026-08-22 Tesla redirect_uri 归属 fail-closed 与 v24 门禁

- Tesla 授权 URL 校验新增回调归属限制：解码后的 `redirect_uri` 必须是配置 JourVolt App Link host 下的 HTTPS `/oauth/callback`，并拒绝用户信息、查询参数和 fragment。
- 新增非 JourVolt 回调地址拒绝测试；Debug/Release 各 `272` 个 JVM 用例通过，failures/errors/skips 均为 `0`。
- `assembleDebug`、`assembleRelease`、`lintRelease`、Docker smoke 和 `git diff --check` 通过；Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error，该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- v24 未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-redirect-uri-guard-v24.apk`，包名 `com.matelink`，SHA-256 `42732C846185CB30F38693FEED64C2369339A6AFDDE160EFEE4ACFBDC6348BA2`。
- v24 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-redirect-uri-guard-v24.apk`，包名 `com.matelink.test.mock`，SHA-256 `055297BDFEFC9223DBDE2FACDC646A29A020A500D0F0906DC462EDA607AF51E3`；仅用于隔离模拟器/测试设备。
- 手机 ADB 仍为空；标准 AVD qualification 最终为 `NOT_PERFORMED|reason=emulator_not_registered_with_adb|serial=emulator-5554`。未安装、卸载、清数据或 instrumentation；正式签名、真实 Tesla OAuth/Fleet、公网域名/HTTPS仍待外部门禁。

## 2026-08-22 云端配置候选构建（v26）

- `android/build-pilot-apk.ps1` 已用 `https://api.jourvolt.com/`、`auth.jourvolt.com` 和同一 HTTPS 根地址完成 Release 构建；该配置只用于未来受控域名，不代表域名已解析或服务已上线。
- `aapt dump badging` 核对 `com.matelink` / `MateLink` / `1.4.2` / versionCode `14`；候选 APK：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-pilot-config-v26.apk`，SHA-256 `B427F37EA42219A95E4878B154D1E8A824206098AF44615BA195D0B9E1B8886A`。
- Release 配置没有 loopback API 地址；既有 v24 redirect_uri 安全校验、v25 服务端回调路径门禁、272 个 JVM 用例基线和 Release lint `194`（`MissingTranslation=0`、无 baseline、0 Error）继续有效。该 lint 数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- `apksigner verify` 判定该产物未签名；正式签名、DNS/HTTPS、Tesla 应用批准、真实 OAuth/Fleet 和实体设备 UI 仍未完成。未执行安装、卸载、清数据或 instrumentation。

## 2026-08-22 当前工作树复验（v27）

- Android `testDebugUnitTest` 与 `testReleaseUnitTest` 重新通过，各 `272` 个测试，failures/errors/skips 均为 `0`；Go `test ./... -count=1` 与 `go vet ./...` 通过。
- 当前 Docker smoke 重新通过：`LOCAL MOCK PASS`、health/ready `ok`、1 台车辆 `charging / 76% / mock_fixture`、18 条行程、5 条充电、注销回收通过。
- v26 Release 配置候选仍未签名，`adb devices -l` 仍为空；未执行实体设备安装、卸载、清数据或 instrumentation。真实 Tesla OAuth/Fleet、正式签名、DNS/HTTPS 和公网 Pilot 仍未完成。

## 2026-08-22 AVD ADB 通道复核（v28）

- ADB server 重启后可监听 `127.0.0.1:5037`，但专用 AVD 两次启动都只报告 Android `Boot completed`，没有注册 `emulator-5554`，也没有可用的 5554/5555 端口。
- 显式 `-ports 5554,5555` 仍未改变结果；诊断进程已停止，未删除 AVD、未清理模拟器数据、未安装 APK 或运行 instrumentation。
- 该结果只说明当前宿主 AVD/ADB 通道未完成，不能作为 MateLink 登录或页面失败证据；真实设备安装门禁仍保持关闭。

## 2026-08-22 原统计分析卡小幅补强（v29）

- 保留原 MateLink 统计页、卡片、导航和视觉风格；在已有“使用模式”区增加“日均驾驶里程”和“行程次数”，没有新建页面或重做主题。
- `averageDistancePerDrivingDayKm` 只由有效总里程除以观测驾驶天数得到，输入不足时保持不可用状态，不把未知数据降级为 `0`，并补充了领域测试。
- Android `testDebugUnitTest` 与 `testReleaseUnitTest` 各 `272` 个用例通过，failures/errors/skips 均为 `0`；`lintRelease` 通过，194 项 finding、`MissingTranslation=0`、无 baseline、0 Error。该 finding 数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- Release 配置候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-daily-distance-v29.apk`，包名 `com.matelink`，SHA-256 `CD8CA469D9DE91CB4F20EA41D5666BF4A256E6B85FA1E70C121098D7B1789310`；`aapt` 核对版本 `1.4.2` / versionCode `14`，`apksigner` 明确为未签名。
- 隔离 Debug Mock：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-daily-distance-v29.apk`，包名 `com.matelink.test.mock`，SHA-256 `7C7DA7485B0726A4015A3709FB08DC9585D2098027D0D5F0FCA6FA59B61FF0AA`；v2 签名有效，仅用于隔离测试。
- Release 源码扫描未命中 `com.jourvolt.app`、loopback、Mock 登录标记；ADB 仍为空，没有安装、卸载、清数据或 instrumentation。该结果仍不代表真实 Tesla OAuth/Fleet、实体设备 UI 或正式签名通过。

## 2026-08-22 综合分析证据与样本量补强（v30）

- 保留原统计分析卡布局；有值指标的依据行现在显示“观测/派生/估算 + 样本数”，让用户能判断结论不是无依据的单个数字。
- 效率、每百公里费用、充入/行驶能量比的 `sampleCount` 改为配对输入覆盖率的保守最小值；不把一侧记录数相加后误称为共同样本。
- Android `testDebugUnitTest` 与 `testReleaseUnitTest` 各 `273` 个用例通过，failures/errors/skips 均为 `0`；`lintRelease` 通过，194 项 finding、`MissingTranslation=0`、无 baseline、0 Error。该 finding 数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- Release 配置候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-evidence-samples-v30.apk`，包名 `com.matelink`，SHA-256 `3C962C851C5BAE9EF4DB42AFE660A27C219D7ED9C8C47E27384B3CE7812D29F8`；`apksigner` 明确为未签名。
- 隔离 Debug Mock：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-evidence-samples-v30.apk`，包名 `com.matelink.test.mock`，SHA-256 `5650ECE2450BD7D69F8C2E94FED21FD277C823F650336959D54C9E6F18EA1977`；v2 签名有效，仅用于隔离测试。
- ADB 仍为空，没有安装、卸载、清数据或 instrumentation；v30 是本地代码/构建证据，不代表实体设备 UI、正式签名或真实 Tesla OAuth/Fleet 已通过。

## 2026-08-22 年度报告货币统一（v31）

- 修复年度报告历史实现中的 `€`/`¥` 写死路径；活动年度报告、PDF 和保留的历史代码统一使用当前用户货币符号，保持原页面结构不变。
- Android Debug/Release 各 `273` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过，Release lint 仍为 194 项 finding、`MissingTranslation=0`、无 baseline、0 Error。该 finding 数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- Release 配置候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-currency-unification-v31.apk`，包名 `com.matelink`，SHA-256 `700D2ABF0AB65E183A6AC6FCB9D4808823772270AF371B0631329947ECF20FAF`；未签名。
- 隔离 Debug Mock：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-currency-unification-v31.apk`，包名 `com.matelink.test.mock`，SHA-256 `47F4D5F9F01E8E26959DE254A1D2C76FABF40E050DC6FF85BAE529CC5E639921`；v2 签名有效，仅用于隔离测试。
- 货币静态扫描仅命中 `Currency` 枚举的合法符号定义；Release 源码未命中 Mock、loopback 或错误包名。ADB 仍为空，没有安装、卸载、清数据或 instrumentation。

## 2026-08-22 当前工作树 Android 全门禁复验（v33）

- 本轮服务端只改动注销空配置路径；Android 当前工作树重新执行 `testDebugUnitTest`、`testReleaseUnitTest`、`assembleDebug`、`assembleRelease` 和 `lintRelease`，全部通过。Debug/Release 各 `273` 个 JVM 用例，failures/errors/skips 均为 `0`。
- Release lint `194` 项（186 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 当前 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-server-fix-v33.apk`，包名 `com.matelink`，SHA-256 `726C2815106E8C0E09C2998B6098B014A5CD4BC56F1E51DBFCDB1DD49DC2121B`；未签名，不能覆盖手机已有正式签名。
- 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-server-fix-v33.apk`，包名 `com.matelink.test.mock`，SHA-256 `47F4D5F9F01E8E26959DE254A1D2C76FABF40E050DC6FF85BAE529CC5E639921`；v2 签名有效，仅用于模拟器/隔离测试。
- 设备 ADB 列表仍为空；没有安装、卸载、清数据或 instrumentation。实体页面回归、正式签名和真实 Tesla OAuth/Fleet 仍未完成。

## 2026-08-23 原统计分析卡充电损耗证据补强（v34）

- 保持原 MateLink 统计页、卡片、导航和主题；没有新增壳页面或切换视觉方案。
- 将已有推荐引擎的充电损耗判定接入原“综合分析”卡：只使用同时存在的电网消耗与电池充入能量，且电网消耗不小于电池充入能量的样本；损耗为 `(电网消耗 - 电池充入) / 电网消耗`，缺失、非有限值或关系不成立时显示不可用。
- 数据覆盖区新增电网能量覆盖率和损耗证据覆盖率；总结卡显示充电损耗及保守有效样本数，避免把未知或不成对的数据包装成结论。
- Android `testDebugUnitTest` 与 `testReleaseUnitTest` 各 `275` 个用例通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。
- Release lint `194` 项（186 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。`git diff --check` 实际问题行数为 `0`。
- 最新 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260823-charging-loss-v34.apk`，包名 `com.matelink`，SHA-256 `3B74B780E9FFFEAA645B5F66888E57778F752C253160FB4950C5CFF6924F967A`；未签名，不能覆盖手机已有正式签名。
- 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260823-charging-loss-v34.apk`，包名 `com.matelink.test.mock`，SHA-256 `867FED439680CC4DB11C4CD2A95D55188C161711D34D7D9B01BF4B51845C00C5`；v2 签名有效，仅用于模拟器/隔离测试。
- 另生成云登录配置候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260823-pilot-config-charging-loss-v34.apk`，包名 `com.matelink`，SHA-256 `C4A3C45420B90A494C793939B01CCEC57173BB589BD245E0E45B48BEDBFC1A97`；`JOURVOLT_CLOUD_LOGIN=true`、API `https://api.jourvolt.com/`、App Link host `auth.jourvolt.com`、Mock 登录为 `false`，无回环地址，但仍未签名且域名/服务尚未上线。
- ADB 只读检查仍为空；没有安装、卸载、清数据或 instrumentation。该本地门禁不等于实体页面或真实 Tesla OAuth/Fleet 通过。

## 2026-08-23 待机耗电窗口证据收口（v35）

- 原 MateLink 待机页和视觉保持不变；待机候选窗口现在必须至少 `2` 小时，短充电间隔不再被误报为停车耗电。
- 无 Telemetry/可用容量时仍只显示 SOC 下降和原因未知，不生成固定容量、kWh 或平均功率。
- 新增 `isQualifiedStandbyWindow` 纯函数及 `1.99/2.0/NaN/Infinity` 边界测试。
- Android Debug/Release 各 `276` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。
- Release lint `194` 项（186 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。`git diff --check` 实际问题行数为 `0`。
- 最新普通 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260823-standby-window-v35.apk`，包名 `com.matelink`，SHA-256 `F29BADC8AB9E1B3E04419C4B9CDC21261541FA9A7EC54D6F271FF2DC8BB870B9`；未签名。
- 最新云登录候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260823-pilot-config-standby-window-v35.apk`，包名 `com.matelink`，SHA-256 `2B30E7119B32E7A4A74F32B5012DECEAB1FDC4DE89B0BE92DBE40C182CE28777`；云登录开启、计划 HTTPS 地址、Mock 登录关闭，但未签名且域名/服务尚未上线。
- 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260823-standby-window-v35.apk`，包名 `com.matelink.test.mock`，SHA-256 `2E8FDC8507C6C20222EFD9879E6800F0E5CD32D94501F8991A4056750B41E8BC`；仅用于隔离测试。
- ADB 仍为空；没有安装、卸载、清数据或 instrumentation。上述构建证据不等于实体页面或真实 Tesla OAuth/Fleet 通过。

## 2026-08-23 手机隔离 Mock 测试包准备（v36）

- Go `test ./... -count=1`、`go vet ./...` 和 Docker `smoke.ps1` 重新通过；结果为 `LOCAL MOCK PASS`，health/ready 为 `ok`，1 台模拟车、18 条行程、5 条充电，注销回收通过。
- 额外生成实体测试设备用隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260823-phone-reverse-v35.apk`，包名 `com.matelink.test.mock`，SHA-256 `F9E6CD56C7D0FB946262F68945D4C73354A895486458379338B515CD25C8A5C3`。该包仅使用 `127.0.0.1:18090`，需设备出现后配合 `adb reverse tcp:18090 tcp:18090`，不覆盖正式 `com.matelink`。
- `adb devices -l` 当前仍为空，因此没有安装、卸载、清数据或 instrumentation；该包只作为待设备连接后的手工 Mock 回归输入，不是正式发布包。
- 正式 Release 仍未签名；真实 Tesla OAuth/Fleet、受控域名/HTTPS、App Link 和实体页面验收仍未完成。

## 2026-08-23 无密钥 Pilot 部署包复建（v37）

- 使用当前 `deploy/jourvolt-dev-mock/package-pilot.ps1` 重新生成自包含部署目录：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260823-v36`。
- 压缩包：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260823-v36.zip`，SHA-256 `9A4E3B3556AA5AD96D18B5C390B555922F6EAAE401909777A8344CD484D0E5EF`。
- `PILOT-BUNDLE-MANIFEST.json` 为 `secrets_included=false`；实际敏感文件计数和压缩包敏感条目计数均为 `0`。示例 `.env.example` 仅为配置模板，不是密钥。
- Bundle 内直接执行 Go `test ./... -count=1`、`go vet ./...`，默认 Compose 与 Pilot Compose 使用进程级占位值校验均通过；占位值未写入 bundle、Git 或部署配置。
- 未启动公网服务、未填写 Tesla 密钥、未修改 DNS；该包只是后续服务器部署输入，不能证明真实 Tesla OAuth/Fleet。

## 2026-08-23 部署预检 fail-closed 复核（v38）

- PASS：bundle 内 Go `test ./... -count=1` 与 `go vet ./...` 均通过。
- PASS：使用仓库实际参数 `-SkipCompose` 执行 `.env.example` 预检；预检按设计返回失败并明确拒绝示例域名、缺失 Tesla 配置、示例密钥和缺失正式 `assetlinks.json`，没有启动服务。
- NOTE：上一次误用不存在的 `-SkipDocker` 参数的命令不计入验证结果；已按脚本声明的真实参数重跑。该记录只证明 fail-closed 行为，不证明公网部署或真实 Tesla 登录。
- NOT_PERFORMED：ADB 仍为空；正式签名、服务器/DNS/HTTPS、Tesla 应用批准、App Link 和真实 OAuth/Fleet 仍未完成。

## 2026-08-23 电池趋势曲线补强与 v39 门禁

- 保持原 MateLink 电池趋势卡和页面结构不变；将已有合格的标准化续航样本按日期聚合，重复日期取中位数，并在卡片内增加轻量趋势曲线和首尾日期。
- 曲线只在趋势状态可用且至少有两个日期点时显示；仍明确标记为标准化续航趋势估算，不等同于实测容量或真实电池健康。
- 新增日期聚合/中位数领域测试；Android Debug/Release 各 `277` 个 JVM 用例通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。
- Release lint `194` 项（186 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。`git diff --check` 通过。
- v39 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260823-battery-trend-v39.apk`，包名 `com.matelink`，SHA-256 `6E580E84C97436885D30075A5A8F6D9FE3A0DE08F4572820BA67261868210F41`；未签名。
- v39 隔离手机 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260823-battery-trend-v39.apk`，包名 `com.matelink.test.mock`，SHA-256 `9316D4A7E89A1A5E6130732E1AA8656E613848A08D3216839F8B19954CAD1794`；使用 `adb reverse tcp:18090 tcp:18090`，不覆盖正式 App。
- NOT_PERFORMED：ADB 仍为空；未安装、卸载、清数据或 instrumentation。真实 Tesla OAuth/Fleet、正式签名、域名/HTTPS、App Link 和实体页面仍未完成。

## 2026-08-23 实体设备隔离包安装验证（v40）

- PASS：ADB 识别 OnePlus 7 Pro（serial `6e4fa92f`，状态 `device`）；`adb reverse tcp:18090 tcp:18090` 建立成功。
- PASS：使用 `adb install -r` 安装 v39 隔离 Debug，返回 `Success`；包名为 `com.matelink.test.mock`，没有覆盖正式 `com.matelink`。只读包列表同时确认两个包存在。
- PASS：隔离包启动后显示原 MateLink Dashboard、车辆卡片、电量/充电状态和原底部导航；随后可进入行程、更多和设置页面。设备截图：`E:\Claude_allow\Download\matelink-test-mock-v39-phone-dashboard.png`、`E:\Claude_allow\Download\matelink-original-v39-phone-current.png`。
- NOTE：隔离包保留了此前测试数据/自托管配置，本次没有 `pm clear`、卸载或 instrumentation；因此本证据证明安装与原页面运行，不证明新安装登录按钮、真实 Tesla OAuth 或 Fleet 车辆读取。
- NOT_PERFORMED：正式签名、服务器/DNS/HTTPS、Tesla 应用批准、App Link 和真实车辆 Pilot 仍未完成。

## 2026-08-23 本地回归门禁复跑（v41）

- PASS：Android 全门禁 `testDebugUnitTest`、`testReleaseUnitTest`、`assembleDebug`、`assembleRelease`、`lintRelease` 返回 `BUILD SUCCESSFUL`；Debug/Release 合计 `554` 个 JVM 用例，failures/errors/skips 均为 `0`。
- PASS：Release lint `194` 项、`0 Error`、`MissingTranslation=0`、无 baseline；该数量按静态质量/多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- PASS：JourVolt Go `test ./... -count=1`、`go vet ./...`；本地 `smoke.ps1` 返回 `LOCAL MOCK PASS`，health/ready 为 `ok`，1 台模拟车、18 条行程、5 条充电，注销回收 `PASS`。
- PASS（预期拒绝）：`preflight.ps1 -EnvFile .env.example -SkipCompose` 按设计返回 `PREFLIGHT=FAIL`，拒绝示例配置并未启动公网服务；这是外部配置未就绪的安全门禁，不是代码测试失败。
- NOT_PERFORMED：真实 Tesla OAuth/Fleet、正式签名、受控域名/HTTPS、App Link 和服务器 Pilot 仍未完成。

## 2026-08-23 实体统计页数据回归（v42）

- PASS：原 `com.matelink` 在实体设备打开“更多 → 统计概览”，显示历史行程、充电和温度统计；截图：`E:\Claude_allow\Download\matelink-original-v41-phone-stats.png`、`E:\Claude_allow\Download\matelink-original-v41-phone-stats-lower.png`。
- OBSERVED：当前样本显示 `190` 次行程、`1,600 km`、`289 kWh`、`181 Wh/km`、`31` 次充电、`512 kWh`；交流/直流比例为 `14/498 kWh`，极端温度数据可见。
- EVIDENCE_BOUNDARY：`费用/距离` 显示 `N/A`，符合无可用成本输入时保持不可用的契约；本次数据来自手机已有自托管历史，不证明 Tesla OAuth/Fleet 真实接入。
- NOT_PERFORMED：本次仅导航和截图，没有清数据、卸载、instrumentation 或代码修改。

## 2026-08-23 原 MateLink 覆盖升级策略确认（v43）

- PASS：按 Jovi 授权，仅删除实体设备上的 `com.matelink.test.mock`；正式 `com.matelink` 保留并启动成功，未清理正式包数据。
- DECISION：正式设备升级固定使用原签名、包名 `com.matelink` 的 Release APK，通过 `adb install -r` 覆盖安装；Room、DataStore、服务器地址、Token 和历史数据必须保留。
- GATE：当前 Release 产物仍未签名；未取得原 keystore 前不执行覆盖安装。`.test.mock` Debug 只允许模拟器/专用隔离环境，不再安装到用户手机作为交付包。

## 2026-08-23 原包签名覆盖与迁移修复（v44）

- PASS：从手机原 `com.matelink` 提取公开签名指纹，使用相同本机 Debug keystore 签署修复版 Release；APK 包名为 `com.matelink`，证书 SHA-256 为 `9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`。
- PASS：修复 `ConnectionModeStore` 的升级迁移：无 JourVolt 会话时，旧服务器地址/Token 优先恢复 `SELF_HOSTED`，不会被已持久化的错误 `TESLA_CLOUD` 模式覆盖；新增回归测试。
- PASS：修复版签名 APK `E:\Claude_allow\Download\matelink-1.4.2-release-signed-migration-fix-20260823.apk`，SHA-256 `7DB882DA415DF28F9458450FDF04F913BECDA6BF649CFC894D7D42541335EF3B`；`adb install -r` 返回 `Success`。
- PASS：设备包列表仅剩 `com.matelink`；正式包 `versionCode=14/versionName=1.4.2`，原包 `firstInstallTime` 保留，未执行 `pm clear` 或正式包卸载。
- PASS：修复版 Debug/Release 合计 `556` 个 JVM 测试通过，失败/错误/跳过均为 `0`；Release lint `194` 项、0 Error、`MissingTranslation=0`、无 baseline。
- BOUNDARY：升级后高级网络页仍显示原 API 地址和 Token 掩码，证明配置未被清除；旧自托管实时连接测试返回“服务器暂时无法访问”，因此实时车辆刷新尚未通过，不能写成真实车辆运行证明。

## 2026-08-23 局域网 HTTP 与 AMap Release 修复（v45）

- PASS：Release 自托管网络资源允许可信局域网 HTTP；应用层 `UrlSecurity` 继续拒绝公网 HTTP，JourVolt 云端 URL 仍要求 HTTPS。
- PASS：为 AMap JNI/反射类加入 R8 keep 规则，并按 R8 生成规则补充可选 GNSS 类 `dontwarn`；Release mapping 保留 `com.autonavi.base.amap.mapcore.ClassTools` 原名。
- PASS：最终签名 APK `E:\Claude_allow\Download\matelink-1.4.2-release-signed-amap-keep-20260823.apk`，包名 `com.matelink`，证书与原包一致，SHA-256 `B67B57C449100CA6BBF5EF0643C0AAD43F43272C68ADF2D815C151B05D59C19A`；`adb install -r` 成功。
- PASS：实体设备冷启动后 `com.matelink` 进程存活，原 Dashboard 显示车辆名、电量 `86%`、续航 `354 km`、里程 `17,573 km`、位置地图；没有测试包或新启动 fatal。
- PASS：Debug/Release 合计 `556` 个 JVM 用例通过，失败/错误/跳过均为 `0`；Release lint `195` 项、0 Error、`MissingTranslation=0`、无 baseline。新增的 1 条是为可信局域网 HTTP 兼容保留的 `InsecureBaseConfiguration` 静态提醒，不是运行时 Bug。

## 2026-08-23 Release 底部导航路由修复（v47）

- ROOT_CAUSE：Release 混淆后类型安全 Navigation 的运行时路由前缀与 `qualifiedName` 不一致，`currentTopLevelDestination()` 返回空，底部栏直接不渲染。
- PASS：路由匹配增加 `$` 归一化和稳定类型名末段回退；新增两项路由回归测试。
- PASS：最终签名 APK `E:\Claude_allow\Download\matelink-1.4.2-release-signed-nav-routenormalized-20260823.apk`，包名 `com.matelink`，SHA-256 `A13F9B7BDE203D379C9249D0F11C40EA1FF40B144B1C43ADDF56272924F754F9`；`adb install -r` 成功。
- PASS：实体设备点击“仪表盘 / 行程 / 充电 / 更多”均实际导航成功；原车辆数据和地图保留。
- PASS：Debug/Release 合计 `560` 个 JVM 用例通过，失败/错误/跳过均为 `0`；Release lint `195` 项、0 Error、`MissingTranslation=0`、无 baseline。

## 2026-08-23 公网 DNS/HTTPS Pilot 门禁复核（v46）

- NOT_READY：`api.jourvolt.com`/`auth.jourvolt.com` 返回 `198.18.0.x` fake-IP，HTTPS 健康检查超时，不能标记为公网部署成功。
- PASS：手机正式 `com.matelink` 仍运行稳定，原 Dashboard 和车辆数据保留；本轮未操作服务器、DNS 或 Tesla 凭据。
