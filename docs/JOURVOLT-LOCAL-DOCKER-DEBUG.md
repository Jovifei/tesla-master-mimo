# JourVolt 本机 Docker 调试

## 目的

在正式服务器、域名和 Tesla 应用审核完成前，用本机 Docker 验证原 MateLink Android App 的 JourVolt session、兼容接口和原页面流转。

默认模式不是 Tesla 官方 OAuth 验证，也不是生产部署证明。服务默认使用固定 Mock provider，不接收 Tesla 账号或密码；真实 OAuth/Fleet 代码已实现，但只有正式配置完整时才启用。

## 启动服务

```powershell
cd E:\project\tesla_master\app_mimo\deploy\jourvolt-dev-mock
docker compose up --build -d
Invoke-RestMethod http://127.0.0.1:18090/healthz
docker compose ps
```

期望：JourVolt API 为 `Up`，PostgreSQL 为 `healthy`，health 返回 `status=ok`、`persistence=postgres`。

停止本机 JourVolt 调试服务：

```powershell
docker compose down
```

## 构建 Android Debug 测试包

Android Emulator 默认将宿主机映射为 `10.0.2.2`：

```powershell
cd E:\project\tesla_master\app_mimo\android
.\gradlew.bat :app:assembleDebug
```

Debug 变体的包名是 `com.matelink.test.mock`，只允许安装到独立模拟器或专用测试设备。打开 App 后只使用“本机 Mock 登录（仅测试包）”。验收路径必须走到原有 Dashboard 和底部导航；Mock 页面不是交付首页。

实体专用测试设备使用 ADB 反向端口，服务不开放局域网监听：

```powershell
adb -s <测试设备序列号> reverse tcp:18090 tcp:18090
.\gradlew.bat :app:assembleDebug `
  -PJOURVOLT_MOCK_BASE_URL=http://127.0.0.1:18090/
```

该地址只进入 Debug BuildConfig；Debug 测试包的明文例外仅允许 `10.0.2.2`、`127.0.0.1` 和 `localhost`。正式 `com.matelink` Release 仍为 HTTPS-only 且没有 Mock 登录入口。

## 当前接口边界

API 同时提供 /healthz 进程探针和 /readyz PostgreSQL 就绪探针；/api/readyz 继续作为需要 JourVolt session 的兼容接口。

Pilot Compose 的 edge profile 可选启用 Caddy HTTPS/App Link 入口；默认不启动，避免在没有受控域名和正式签名时误开放公网。

- `/healthz`、`/api/ping`、`/api/readyz`。
- `/v1/dev/mock-login`、`/v1/session/refresh`、`/v1/session/logout`、`DELETE /v1/account`。
- `/api/v1/cars`、车辆状态和 `/api/matelink/v1/cars/{id}/snapshot`。
- 默认 Debug Mock 同时开启显式历史 fixture：18 条行程、5 条充电及对应详情，用于验证原 Dashboard -> 同步 -> Room -> Stats -> 建议卡的完整链路；health 返回 `mock_history=true`，能力端点增加 `history_fixture`。
- 需要验收“从连接之日起开始记录”的真实空状态时，将 Compose 环境变量 `JOURVOLT_ENABLE_MOCK_HISTORY=false` 后重建服务；正式 Fleet/Release 路径不会读取该 fixture。
- `/v1/auth/tesla/start`、`/callback`、`/exchange` 已实现；没有完整私密配置时返回 `oauth_not_configured`。
- 完整配置时启用 Tesla 中国官方授权、OIDC nonce/`sub` 校验、加密 token、事务轮换和 Fleet 只读车辆 Provider。

## 当前验证证据

- Go 单元测试与 vet：PASS。
- PostgreSQL 集成：OAuth state/ticket 重放拒绝、并发 refresh 单次上游轮换 PASS。
- Docker Compose：PASS；PostgreSQL 持久化、健康检查、无 token 401、Mock 登录、车辆状态、刷新轮换、旧 token 失效、退出后 401 均通过。
- Android 全量 JVM：177 tests，0 failures/errors/skips；Debug、Release 与 lint PASS。Release lint 为 0 error、255 warning、8 information，`MissingTranslation=0`，没有 lint baseline。
- 当前单一 App `com.matelink.test.mock` 已在独立模拟器 `emulator-5554` 完成 `LOCAL MOCK HISTORY PASS`：首屏、Mock 登录、原 Dashboard 车辆状态、主动刷新与后台同步、More -> Stats、中英文建议卡均通过。
- 历史 fixture 经兼容接口返回 18 条行程和 5 条充电；原 Stats 从 Room 汇总出 420 km、215 Wh/km、23 条来源记录，并显示高速、低温和充电损耗三类数据建议。每条建议包含阈值、样本量、覆盖量、可信度、预计月度影响、动作和方法。

## 2026-08-21 当前执行证据（最新）

- Android JVM 测试为 182/182 PASS；Release、lint 和差异检查通过。
- 本机 API 与 PostgreSQL 容器均为 healthy；进程探针 /healthz 和数据库就绪探针 /readyz 均为 ok。
- 模拟器回归确认原 MateLink Dashboard 可进入，车辆状态和完整底部导航可见；本地 Mock 来源显示为 Local mock，未再出现来源与卡片状态矛盾。
- Caddy edge profile 只作为后续受控公网 Pilot 模板，当前没有启动公网入口；真实 Tesla OAuth、域名、App Link 和真实车辆仍未证明。
- 回归先复现了 Mock 成功后 IO 线程触发 Navigation 崩溃，随后切回主线程修复；清空模拟器日志后重复流程无新崩溃。实体手机没有安装。

## 本地 Mock 不能证明

- Tesla 官方 OAuth、MFA、Fleet API token 或真实车辆状态；
- 真实域名 HTTPS 回调、Tesla 应用审核、多用户生产隔离；
- Fleet Telemetry、地图路线、位置敏感信息处理或生产 SLA。

正式生产服务器继续后置。真实单车 OAuth Pilot 可复用同一套本机 Docker，但必须先完成受控域名、Tesla 应用批准，以及可由 Tesla 访问的稳定 HTTPS 回调、公钥和 App Link；临时随机域名不能作为正式回调。

## 2026-08-22 当前本机交付状态

- Go `test ./... -count=1`、`go vet ./...` 和隔离 fleet Compose 烟测通过；账号删除集成测试现在实际解析并校验服务端返回的 Tesla 官方撤销 URL，以及 JourVolt `/privacy/` 回落地址。
- Android 当前既有门禁为 Debug/Release 各 `243` JVM tests，Release lint `0` errors、`MissingTranslation=0`、无 baseline；lint findings 按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- 最新未签名正式包仍为 `com.matelink`：`E:/project/tesla_master/app_mimo/android/app/build/outputs/apk/release/app-release-unsigned.apk`，SHA-256 `AD95D280FC857F2F39EE121FE9132AF64D1EE048376208E5064C72BB72B82D6A`。
- 最新无密钥 Pilot bundle 为 `E:/Claude_allow/Download/jourvolt-pilot-bundle-app-link-20260822`；本机 bundle 烟测证明启动链可用，不证明公网 HTTPS、Tesla 审核、真实 OAuth 或真实车辆。

## 真实单车 Pilot 需要 Jovi 执行

1. 准备可控制 DNS 的 JourVolt 域名。
2. 在 Tesla 中国开发者平台提交 JourVolt 应用并取得批准的 `client_id`、`client_secret`、scope 和 redirect URI。
3. 按 Tesla 要求托管域名公钥并完成伙伴注册。
4. 配置公网 HTTPS 回调和 Android App Link `assetlinks.json`。
5. 把密钥写入已忽略的本机 `.env` 或服务端私密配置；不要发给聊天、Git 或 Obsidian。
6. 只把“审核是否通过、域名、公开回调 URI、批准 scope”这些非敏感结果告诉 Codex，再构建 `JOURVOLT_CLOUD_LOGIN=true` 的 Pilot APK。

## 2026-08-22 当前最终本机交付复核

- 正式版 Mock 已 fail-closed：正式构建忽略历史 `mock_mode` 偏好，异常 `mock_fixture` 来源不显示本地 Mock 标签；Debug 测试包行为不变。
- Android Debug/Release 各 `243` JVM tests、`assembleDebug`、`assembleRelease`、`lintRelease` 通过；Release lint 为 258 findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无被跟踪 baseline。该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名正式 APK：`E:/project/tesla_master/app_mimo/android/app/build/outputs/apk/release/app-release-unsigned.apk`，`com.matelink` / `1.4.2`，SHA-256 `649C31CDC0932A8D81A2B4050793E12EF7A65FECCD8BA0AD8A6B80CEC789A5FA6`。APK 静态扫描未发现 Mock 登录、Mock provider、回环地址或 Debug 标记。
- 本轮两次启动隔离 AVD 均未形成 `emulator-5554`，所以没有把页面交互回归写成通过；实体手机、真实 Tesla OAuth/Fleet、公网 DNS/HTTPS、服务器、正式签名和 Git 发布仍未执行。

## 2026-08-22 本机服务 HTTP 交付证据

- 运行中的 `jourvolt-dev-mock` API/PostgreSQL：`127.0.0.1:18090`，`/healthz` 与 `/readyz` 均成功；Mock 历史明确为 `18` 条行程和 `5` 条充电。
- HTTP 流程通过：`POST /v1/dev/mock-login` → `/v1/vehicles` → `/api/matelink/v1/cars/1/snapshot` → 行程/充电兼容接口 → `POST /v1/session/logout`；旧 token 注销后访问返回 `401`。本证据只属于 `LOCAL MOCK PASS`。
- AVD 已实际完成 Android boot，但 ADB server 未建立设备连接；设置 `ANDROID_SDK_HOME` 只能恢复 AVD 发现，不能修复宿主 5554/5555 通道。页面回归仍为 `NOT_PERFORMED`。

## 2026-08-22 最新自包含 Pilot bundle

- 从当前工作树重新生成：`E:/Claude_allow/Download/jourvolt-pilot-bundle-runtime-20260822`，`PILOT_BUNDLE=PASS`。
- bundle manifest 为 `secrets_included=false`，静态根 `./public`；条款、隐私、Caddy、Compose、预检、备份恢复和 systemd 模板均随包携带。
- `.env`、Tesla secret、正式证书、签名私钥和 `assetlinks.json` 正式指纹仍不会被打包，部署前必须按外部门禁注入。

## 2026-08-22 当前工作树回归

- `127.0.0.1:18090` 的 API/PostgreSQL healthy：`/healthz=ok,mock_only,postgres`，`/readyz=ok`。
- Mock 登录后取得 1 台用户车辆；原兼容快照为 `charging / 76% / mock_fixture`。
- 历史兼容接口返回 18 条行程、5 条充电；注销成功后旧 access token 访问车辆接口返回 `401`。
- 最新部署输入包为 `E:/Claude_allow/Download/jourvolt-pilot-bundle-local-completion-20260822`，manifest `secrets_included=false`，敏感文件计数 `0`，bundle Compose 配置通过。
- 以上只属于 `LOCAL MOCK HISTORY PASS`，不证明 Tesla 官方 OAuth、真实 Fleet、真实车辆或公网部署。

## 2026-08-22 当前工作树 HTTP 与 Android 回归

- 实际 HTTP 链路再次通过：Mock 登录、车辆列表、兼容快照 `charging / 76% / mock_fixture`、18 条行程、5 条充电、注销；旧 access 和旧 refresh 均返回 `401`。
- Android Debug/Release 各 `246` 个 JVM 用例通过，合计 `492`；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过，`MissingTranslation=0`、无 baseline。
- 最新未签名 APK SHA-256 为 `1E28924C7C81CEA02FE31EC63C6F064F60D36970B10BD96C66F5EF21DD945A9D`；Release 静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- 页面交互回归仍为 `NOT_PERFORMED`，因为宿主 AVD 没有向 ADB 注册 `emulator-5554`；上述服务证据只属于 `LOCAL MOCK HISTORY PASS`。

## 2026-08-22 无密钥 Pilot bundle 校验

- 已生成 `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-local` 及 ZIP `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-local.zip`，ZIP SHA-256 为 `84C433701FEFFF44B49C0109FAFFB70103F184C781075D7E253233AFC01FC4C4`。
- manifest 的 `secrets_included=false`，密钥文件扫描为 `0`；bundle 内 Go test/vet 和 Compose 配置校验通过。
- Compose 校验使用的只是进程级占位值，未创建 `.env`、未启动 Pilot 服务，也不等于真实 Tesla 或公网部署通过。

## 2026-08-22 最终 JVM 计数

- `ChargeSummaryMetricTest` 已加入边界回归；Debug/Release 全量 JVM 各 `248` 项，合计 `496`，failures/errors/skips 均为 `0`。
- Release APK 已按当前生产源码重新构建，SHA-256 为 `469C349EDC95F9955B4FF3F7789E73DFC104967A576B94332B1E43718FF0E400`。

## 2026-08-22 当前登录并发修复与隔离 Debug 包

- Tesla 登录 start/callback 和 Debug Mock 登录均已加入请求代次隔离；旧取消请求不能覆盖新状态或写回旧 session。
- Debug/Release JVM 各 `269` 项通过，Go test/vet、Docker health/ready 和 Release 静态扫描通过；Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 baseline。该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 隔离 Debug 测试包：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-v1.apk`，包名 `com.matelink.test.mock`，SHA-256 `FF1C4D080F3F9F2A2572836A9244F8089A2F02786683852ED893754AB9F19EA4`。它仅用于模拟器/隔离设备，不替代正式 Release，也未安装到实体手机。
- `adb devices -l` 仍为空；本机 Docker 证据仍属于 `LOCAL MOCK HISTORY PASS`，不等于 Android 页面运行时或真实 Tesla OAuth/Fleet 通过。

## 2026-08-22 Locale 修复与最新 APK

- Android Debug/Release 各 `269` 个 JVM 用例通过；显式 Locale 修复后 Release lint 为 `231` findings，`MissingTranslation=0`、无 baseline、0 Error。
- 最新未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-locale-v15.apk`，SHA-256 `B11042EB9B8C9138BA68341E3AA085C12332AD545A90C1D58C4D20B5C045524E`。
- 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-locale-v15.apk`，SHA-256 `D65DE024D3894807582DDD0C910A74173E5F0105D5A649B003F70FEADBC5E48E`；不进入正式 Release，也不替代真实 Tesla 证据。

## 2026-08-22 v16 发布门禁

- Release lint 为 `194` findings，`MissingTranslation=0`、无 baseline、0 Error；Locale、Typography 和 API 26 资源目录收口后没有新增运行时错误。
- 最新未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-lint194-v16.apk`，SHA-256 `0090C25FCF3D0D69FED597389FAEB23045E8BA4A963C616F13AAB76DBDDB03CF`。
- 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-lint194-v16.apk`，SHA-256 `54420A3C8BF9D829F5D2962185B2D3D619E536D6DEE4F4C433DABAB81FDFB8CC`；仅用于模拟器/隔离设备。

## 2026-08-22 v17 Android 分析页本地交付

- 统计综合分析卡新增到原有 MileageScreen 的入口；该入口只改变 Android 导航连接，不改变本机 Docker Mock API、Provider 或历史数据契约。
- v17 Release 未签名 APK：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-analysis-drilldown-v17.apk`，SHA-256 `BD1999A77B5444584F948B2D3543CF3E50FAD3F1663D79BDB2BCF36AE01E338E`。
- 本地 Docker 证据仍必须标为 `LOCAL MOCK PASS`；本次 Android `adb devices -l` 为空，没有进行实体设备安装或页面运行时回归。

## 2026-08-22 v18 Docker smoke 与 bundle

- 当前源码重新构建 `jourvolt-dev-mock` 后，`/healthz`、`/readyz` 均为 `ok`；`smoke.ps1` 已固化 Mock 登录、车辆、快照、历史和注销回收检查。
- 本次输出：`LOCAL MOCK PASS`、1 台车辆、`charging / 76% / mock_fixture`、18 条行程、5 条充电，`logout_revocation=PASS`。
- 新 bundle：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v18`；ZIP `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v18.zip`，SHA-256 `60740D9757EB1DC2AC2BD2769DED4774B71F333DDD6F140C072E3EB68C2B4260`；manifest `secrets_included=false`，敏感文件计数 `0`。
- 所有结果仍属于本地 Mock/部署输入证据，不等于真实 Tesla OAuth/Fleet 或公网 Pilot。

## 2026-08-22 v19 smoke 计数修正

- `smoke.ps1` 已修正为读取 `data.drives` / `data.charges`；实际验证为 18 条行程、5 条充电，不再把响应容器误报为 1 条记录。
- v19 bundle：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v19`；ZIP `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v19.zip`，SHA-256 `CD2E1299F61C058BB37AB254A13DF5A27A27F43A85389AC87BBB2336DC4AC844`。
- v18 bundle 已被 v19 替代；两者都不含真实密钥，但后续部署只使用 v19。

## 2026-08-22 当前源码复验（v22）

- Android 登录错误文案资源化不改变 Docker API；使用当前工作树重新执行 `smoke.ps1` 仍为 `LOCAL MOCK PASS`，health/ready 为 `ok`，1 台车辆、18 条行程、5 条充电，`logout_revocation=PASS`。
- 本地 Mock 证据仍只证明 Docker 兼容接口和注销回收链路；不代表公网部署、真实 Tesla OAuth/Fleet、正式签名或实体设备 UI 已通过。

## 2026-08-22 当前工作树复验（v27）

- `smoke.ps1` 重新通过：`LOCAL MOCK PASS`，health/ready 为 `ok`，车辆 `charging / 76% / mock_fixture`，18 条行程、5 条充电，注销回收通过。
- Go `test ./... -count=1` 与 `go vet ./...` 通过；本机 Docker 仍只作为开发/契约验证环境，不代表公网 Pilot。

## 2026-08-22 当前部署输入复验（v32）

- 当前源码重新执行 Go `test ./... -count=1`、`go vet ./...` 和 `smoke.ps1` 均通过；输出为 `LOCAL MOCK PASS`、health/ready `ok`、1 台车辆 `charging / 76% / mock_fixture`、18 条行程、5 条充电、`logout_revocation=PASS`。
- 最新无密钥 bundle：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v32`；manifest `secrets_included=false`，敏感文件计数 `0`。
- ZIP：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v32.zip`，SHA-256 `5995469FF438E4CE96B0E9420937A0F8F3DECF95967BEF0D2AFEE07F94ED956B`。
- bundle 仍只是服务器部署输入；没有填写 Tesla 密钥、没有启动公网服务，也不能代替真实 OAuth/Fleet、正式签名、DNS/HTTPS 或实体设备验证。

## 2026-08-22 兼容接口全路由巡检与注销空配置修复（v33）

- 修复未配置 Tesla OAuth 的 Mock/开发模式调用 `DELETE /v1/account` 时对空 OAuth 对象解引用的问题；此模式现在正常删除账号并省略 Tesla 撤销链接，不再触发服务端崩溃。
- 新增回归测试 `TestAccountDeletionWorksWhenTeslaOAuthIsNotConfigured`；Go `test ./... -count=1`、`go vet ./...` 通过。
- 全部 Android Retrofit 兼容路径本机巡检通过（16 条）：能力、快照、停车详情、ping/readyz、车辆/状态、行程/充电列表与详情、当前充电、电池健康、升级和全局设置；18 条行程、5 条充电、注销回收和未配置 OAuth 注销均通过，结果为 `LOCAL COMPATIBILITY PASS`。
- 既有 `smoke.ps1` 仍为 `LOCAL MOCK PASS`；本地证据不等于真实 Tesla OAuth/Fleet、公网 HTTPS、正式签名或实体设备 UI 通过。
- 已重新生成无密钥 bundle：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v33`；ZIP：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v33.zip`，SHA-256 `432A12F3B3B3CCC0859D14580473C4B6FF4D5B45AD3E7747EB3872F422CAA964`。
