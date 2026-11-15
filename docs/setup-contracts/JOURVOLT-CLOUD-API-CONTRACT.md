# JourVolt 云端接口与安全契约（P1 草案）

状态：`LOCAL IMPLEMENTATION PASS / REAL TESLA PILOT BLOCKED BY EXTERNAL IDENTITY`

这是 JourVolt 的最小接口与安全契约。Go/PostgreSQL 本机实现已经具备配置驱动的 Tesla OAuth/Fleet 路径；当前没有批准的 Tesla 应用身份、域名和 HTTPS 回调，因此只能运行 Mock 回归，不能宣称真实车辆通过。

## 边界

- 正式 `com.matelink` Android 客户端只访问 JourVolt 的 HTTPS 域名；本地 Mock 只存在于 `com.matelink.test.mock` Debug 测试包。
- App 使用系统浏览器跳转 Tesla 官方授权页面；JourVolt 不接收或保存 Tesla 密码。
- JourVolt 服务端保存 Tesla `client_secret`、应用私钥和加密 refresh token。
- JourVolt 内部用户主键使用 Tesla OpenID `sub`；不读取或要求 Tesla 邮箱。
- App 只保存 JourVolt 的短期 access session 和轮换 refresh session。
- 所有响应都按当前 JourVolt 用户隔离，客户端传入的 VIN/车辆 ID 不作为授权依据。

## App-facing endpoints

## Android compatibility surface

The original MateLink Android app reuses the existing read-only repository and page models. The JourVolt service therefore exposes the existing `/api/...` response shapes behind a JourVolt session bearer:

- `/api/ping` and `/api/readyz`
- `/api/matelink/v1/capabilities`
- `/api/matelink/v1/cars/{id}/snapshot`
- `/api/matelink/v1/cars/{id}/standby` (returns a collecting empty result when cloud history has not accumulated; the client may derive conservative candidates from its local archive)
- `/api/v1/cars`, `/api/v1/cars/{id}` and `/api/v1/cars/{id}/status`
- `/api/v1/cars/{id}/drives`, `/drives/{id}` and `/api/v1/cars/{id}/charges`, `/charges/{id}`, `/charges/current`
- `/api/v1/cars/{id}/battery-health`, `/updates` and `/api/v1/globalsettings`

The service maps Fleet API data to the existing Android models and checks the session-to-vehicle ownership on every request. Internal vehicle IDs are stable only within a JourVolt user and are not authorization credentials.

Until telemetry/history collection is enabled, the real Fleet path returns valid empty collections and capabilities identify only `live_status` as available. The UI must display a truthful not-collected state. Local Debug may explicitly enable `JOURVOLT_ENABLE_MOCK_HISTORY=true`; only the fixed `mock-user` then receives the isolated history fixture and `history_fixture` capability. The fixture is never used by the real Fleet provider or Release App.

### `GET /v1/auth/tesla/start`

创建一次性授权事务并返回浏览器跳转信息。

当前实现还要求客户端提供 `X-JourVolt-Terms-Version` 和 `X-JourVolt-Privacy-Version`。两者必须等于服务端当前版本；缺失或过期时返回 `400 consent_required`，不创建 OAuth state。服务端把版本与 state/nonce 关联，回调完成身份验证后才将同意记录原子写入用户记录。

响应只包含：

```json
{
  "authorization_url": "https://...",
  "transaction_id": "opaque-one-time-id",
  "expires_at": "2026-01-01T00:00:00Z"
}
```

事务必须绑定短时效 state、OIDC nonce 和一次性内部 transaction ID；日志不得记录完整授权 URL、code、state 或 nonce。Tesla 中国当前官方第三方令牌文档未声明 PKCE 参数，本服务按其服务端 `client_secret` 授权码流程实现，不虚构额外参数。

### `GET /v1/auth/tesla/callback`

仅由 Tesla 回调到服务端。服务端原子消费 state、校验 OIDC nonce/issuer/audience，使用服务端密钥换取 Tesla token，然后把 token 以 AES-GCM 密文写入 PostgreSQL。

浏览器不直接获得 Tesla token。成功后跳转到一次性 JourVolt exchange 页面/链接。

### `POST /v1/auth/exchange`

App 提交一次性 exchange ticket，服务端验证 ticket 未使用、未过期且绑定同一授权事务后，返回 JourVolt session。ticket 只能成功消费一次，重放返回统一失败。

成功响应示例：

```json
{
  "access_token": "opaque-short-lived-token",
  "refresh_token": "opaque-rotating-token",
  "expires_in": 900,
  "user": { "id": "opaque-user-id" },
  "vehicles": []
}
```

不返回 Tesla refresh token、Tesla 邮箱或完整 VIN。

### `POST /v1/session/refresh`

校验当前 refresh session，原子撤销旧 session 并签发新 session。并发重复使用旧 refresh token 时只允许一个请求成功，其余请求必须失效，不得产生两个有效后继 token。

### `POST /v1/session/logout`

撤销当前 JourVolt session；不影响 Tesla 账号的其他授权。

### `GET /v1/vehicles`

按服务端 session 返回当前用户可见车辆。默认只读 `vehicle_device_data` 范围的数据。任何车辆资源读取都再次检查 session 用户与车辆绑定关系。

### `GET /v1/vehicles/{id}/status`

读取车辆状态、行程、充电和分析所需的只读数据。Tesla 401/403/429 等状态转换为不泄露上游细节的 JourVolt 错误码，并在服务端记录计数指标而不是 token/VIN。

### `POST /v1/location/consent` / `DELETE /v1/location/consent`

记录或撤回位置处理的单独同意。撤回后停止位置读取/Telemetry 任务，并标记原始位置数据进入删除流程；拒绝位置不得阻断基础车辆状态。

### `POST /v1/routes/enable` / `POST /v1/routes/disable`

只有位置单独同意存在时才允许启用路线。启用流程由服务端编排 Tesla `vehicle_location`、虚拟钥匙和官方 Telemetry 状态；任何一步未完成都返回不可用，不生成补造路线。

### `DELETE /v1/trips/{id}` / `DELETE /v1/account`

用户可删除单条行程或注销账号。注销顺序：停止 Telemetry、删除服务端保存的 Tesla token、撤销 JourVolt session、删除在线数据；备份密文按最多 30 天自然过期。Tesla 官方第三方授权的撤销由用户在 consent 管理页完成，服务端不会伪造一个不存在的撤销 API。

当前 P1（未启用 Telemetry）已经验证的行为是：删除用户会级联删除 JourVolt session、加密 Tesla grant、用户车辆关联和用户同意记录，旧 access token 随后返回 401。未来启用 Telemetry 后，必须先增加可测试的停止采集步骤，再将其纳入同一删除事务；不得把计划中的 Telemetry 停止写成当前已验证能力。

## 数据和保留策略

- 原始高精度位置点：最多 30 天。
- 简化路线和行程摘要：最多 1 年。
- 基础车辆状态、授权审计和删除记录：按隐私政策确定最小期限。
- 日志禁止写入 Tesla token、VIN、精确坐标、Tesla 账号、授权 code、PKCE verifier。
- 日志只使用不可逆关联 ID、错误类别、HTTP 状态、耗时和计数指标。

## P1 必测矩阵

本机实现必须覆盖：

1. state/nonce 缺失、过期和重放。
2. exchange ticket 成功消费、重复消费和过期。
3. refresh token 原子轮换、并发旧 token 和数据库事务回滚。
4. Tesla 401 触发重新授权；429 触发退避，不泄露上游凭证。
5. 两个 JourVolt 用户互相读取车辆、行程和位置时均被拒绝。
6. 拒绝/撤回位置后基础状态仍可用，路线接口不可用。
7. 注销后 session、Tesla token、Telemetry 任务和在线行程均不可继续使用。

## 2026-08-11 实施状态

- 已实现 `/v1/auth/tesla/start`、`/callback`、`/exchange`，以及固定 App Link 回跳。
- 已实现 OpenID `sub` 派生内部用户、state/ticket 单次消费、JourVolt session 原子轮换。
- 已实现 Tesla access/refresh token 与 VIN 的 AES-GCM 加密存储；并发 refresh 通过 PostgreSQL 行锁只执行一次。
- 已实现 `GET /api/1/vehicles` 与 `GET /api/1/vehicles/{vin}/vehicle_data` 的只读 Provider；VIN 不返回 Android，车辆 ID 为用户内稳定内部 ID。
- 已实现 401 重新授权、一次 token refresh 重试、429 退避提示和离线/上游不可用分类。
- 已实现 Android 兼容车辆/状态接口与真实空历史；未申请位置或车控 scope。
- 已实现仅限本机 Debug Mock 的 18 行程/5 充电 fixture、详情与用户隔离测试，用于原 Stats/建议链路；真实 Fleet 路径仍返回真实采集结果或空集合。
- 未配置正式 Tesla 参数时所有真实认证接口继续 fail closed；Mock 只能由 `JOURVOLT_ENABLE_MOCK=true` 显式开启。

## 明确不在 P1

- 不实现 Tesla 密码表单或非官方 API。
- 不实现远程车控、充电命令、哨兵视频或历史录像。
- 不把现有 TeslaMate adapter 的数据库 ID、Basic Auth 或固定 API Token 直接暴露给正式 MateLink 云模式。
- 不使用 lint baseline、假数据或“已部署”文字替代真实 OAuth/车辆证据。

## 2026-08-21 会话注销安全收口

- 服务端注销现在按完整 JourVolt session 撤销：通过当前 access token 定位会话并写入 `revoked_at`，因此同一会话的 refresh token 也不能再次轮换出新 access token。
- 已重建本机 Docker API 并通过实际 HTTP 链路验证：`mock-login -> logout` 后，原 access 访问 `/v1/vehicles` 返回 401，原 refresh token 调用 `/v1/session/refresh` 返回 401，`/readyz` 仍为 ok。
- 新增数据库集成测试 `TestStoreLogoutRevokesRefreshSession`；本轮使用临时空 PostgreSQL 执行 4 个会话/令牌集成测试，全部通过。未设置 `JOURVOLT_TEST_DATABASE_URL` 时仍按项目约定跳过，不把未执行的外部数据库测试写成通过。
- 该修复只收口本地服务会话生命周期，不改变 Tesla 密码不入 App、真实 OAuth 和真实车辆仍需外部门禁的边界。

## 2026-08-21 云接口路由与 HTTPS 门禁收口

- Android Repository 与 API Factory 现在统一读取持久化 `ConnectionMode`；登录切换后车辆列表、快照和其他兼容接口不会再因内存模式滞后而走不同连接。
- Release 云模式只接受固定 JourVolt HTTPS 根地址；Debug 仅允许明确的本地 Mock HTTP 地址，公网 HTTP 和非根路径仍拒绝。
- JourVolt OAuth/refresh URL 校验保持严格 HTTPS；仅 Debug Mock refresh 明确允许本地 HTTP，不能被带入 Release。
- 通过 `testDebugUnitTest` 191/191、`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、`lintRelease` 和 `git diff --check`；Release lint `MissingTranslation=0`，无 baseline。
- 独立模拟器清空数据后重新执行 Local Mock login，进入原 MateLink Dashboard，显示 `Development Model 3`、`Charging`、`76%`、`Local mock`，连续快照轮询正常。该证据仍标记为 `LOCAL MOCK PASS`，不是真实 Tesla 车辆证据。

## 2026-08-21 Android 统计历史同步行为

- 本轮没有新增或改变云接口路由；原 Statistics 页面首次建立 ViewModel 时会调度已有的 `DataSyncWorker`，以现有兼容接口同步历史到 Room，再由原统计 Repository 读取。
- Mock provider 的 18 条行程和 5 条充电仅用于独立模拟器回归；真实 Fleet/云端没有历史时接口仍返回合法空集合，客户端显示采集状态，不生成 Mock 历史。
- 统计页新增的平均行驶距离、平均充电能量、每 100 km 成本和充入/行驶能量比均为 Android 本地派生值，不改变服务端字段契约；输入缺失时不返回或显示伪造零值。
- 该行为已经在原 `com.matelink` Debug 链路通过本地 Mock 回归，但不构成真实 Tesla OAuth、Fleet API 或公网部署证明。

## 2026-08-21 OAuth HTTP 契约集成测试

- 新增 `tesla_oauth_integration_test.go`，使用本地 OIDC/JWKS/token 测试服务验证真实实现的协议边界，不读取或保存真实 Tesla 凭据。
- 集成链路覆盖：`start` 生成 state/nonce，授权码回调消费 state，token 响应必须包含 access/refresh/expiry/ID Token，ID Token 校验 issuer、audience、subject、nonce 和签名，随后保存加密 Tesla grant 并生成一次性 App Link ticket。
- 使用 ticket 交换 JourVolt session 后再次重放同一 ticket 必须失败；测试在临时隔离 PostgreSQL 上实际运行通过。
- 该测试增强的是协议和数据库证据，不替代 Tesla 官方授权、真实车辆数据或公网部署验收。

## 2026-08-21 当前 Docker HTTP 回归

- 当前 Go API 镜像重建并启动后，`/healthz` 和 `/readyz` 均返回 `ok`，Mock 车辆列表返回 1 台。
- 实际执行 `mock-login -> /v1/vehicles -> logout`；注销后原 access 和原 refresh 分别返回 401，证明会话注销不会留下可轮换的 refresh session。
- 该回归使用本机 Mock Provider，不升级为真实 Tesla/Fleet 证据。

## 2026-08-21 Pilot 启动脚本

- `pilot-up.ps1` 使用同一个 `-EnvFile` 完成预检、精确 Compose 配置校验、edge profile 启动和容器内 `/readyz` 检查。
- 缺少正式域名、Tesla client 配置、签名指纹或 token key 时，脚本在启动前 fail-closed；不会把本机 Mock Compose 暴露成真实服务。

## 2026-08-21 Fleet HTTP 请求兼容性收口

- Fleet provider 的认证 GET 请求现在固定发送 `Content-Type: application/json`，与 Tesla 中国 Fleet API 的请求约定一致；原有 `Authorization: Bearer` 和 `Accept: application/json` 保持不变。
- 401 重试测试同时校验两次上游请求的 Content-Type，避免真实域名和凭据就绪后才暴露请求头兼容问题。
- 依据：[Tesla Fleet API 请求约定](https://developer.tesla.cn/docs/fleet-api/getting-started/conventions)。本轮仍未连接 Tesla 真实端点，不能替代真实 Pilot 验收。

## 2026-08-21 Tesla 中国 OIDC 元数据核对

- 已通过 Tesla 中国官方 OIDC 元数据地址核对默认 issuer、JWKS、授权端点、令牌端点和中国 Fleet API 基地址；代码默认值与元数据一致。
- 新增 `TestTeslaChinaDefaultsMatchOfficialOIDCMetadata`，将这些无密钥端点固化为本地契约测试。
- 元数据入口：[Tesla Fleet API 身份验证概述](https://developer.tesla.cn/docs/fleet-api/authentication/overview)。本地核对不等同于 Tesla 应用批准或真实车辆验收。

## 2026-08-21 Fleet 车辆状态映射测试

- 将 Tesla `vehicle_data` 到 MateLink 只读状态的字段映射抽为纯函数，覆盖电量、里程/续航英里到公里、充电插入状态、车门、空调、速度和中控状态。
- 新增 `TestFleetVehicleDataMapsToReadOnlyAndroidStatus`，确保真实 Fleet 响应不会在兼容层静默错位；Go `test`、`vet` 通过。

## 2026-08-21 Android 历史降级契约

- Android 兼容层在历史 API 失败时可读取已有 Room `DriveSummary`/`ChargeSummary`，返回 `STALE` 分析快照；服务端恢复后新快照覆盖旧快照。
- 摘要映射保留未知距离、电量、时长与真实零成本的区别，避免离线状态生成虚假统计。
- 该行为已纳入 Android `202` JVM tests；本地 Docker Mock、Go test/vet 和独立模拟器原 MateLink Statistics 回归通过。该证据仍不等同于真实 Tesla/Fleet 数据。

## 2026-08-21 授权同意与删除测试状态（当前最新）

- Go 测试覆盖当前同意版本、缺失/过期同意拒绝、OAuth state/nonce、一次性 ticket 重放、session 轮换和用户删除级联。
- 本机 Docker 使用 PostgreSQL 实际执行 `mock-login -> DELETE /v1/account -> /v1/vehicles`，删除接口返回 `deleted`，旧 session 读取车辆返回 `401`；`/healthz`、`/readyz` 仍为 `ok`。
- 这是本地协议与数据库证据，未调用 Tesla 真实端点，未存取 Tesla 账号密码，不应标记为真实 Tesla OAuth 或 Fleet 车辆通过。

## 2026-08-22 Android 历史元数据状态契约

- 历史接口的 `data.meta.availability` 仍使用 `available|collecting|unsupported`；Android 分页加载会保留第一页可用的 `meta`，不会因分页或空集合丢失状态。
- 两类历史集合都为空时，`collecting` 映射为采集中，全部已知数据源为 `unsupported` 映射为不支持，其余情况才映射为普通无记录；存在真实记录时不覆盖为无数据状态。
- 该状态只影响分析页面的解释性 UI，不改变历史数据、登录会话或 API 权限；旧 TeslaMate 没有 `meta` 时继续兼容为普通无记录。

## 2026-08-22 Statistics 指标状态映射

- Android Statistics 摘要卡现在消费历史 `meta.availability`：历史为空且为 `collecting` 时，将对应的空指标呈现为 `MetricState.Collecting`；已有有效值不被覆盖。
- 驾驶侧状态影响距离、驾驶能耗、效率和平均行程距离；充电侧状态影响充电能量、费用和平均充电能量；跨两类输入的费用/能量比在任一侧采集中时保持采集状态。
- 该映射只改变解释性展示，不改变 API 响应、权限、历史数据或统计聚合；旧 TeslaMate 没有 `meta` 时仍显示普通无记录/不可用。

## 2026-08-22 账号注销与 Tesla 官方撤销回流

- `DELETE /v1/account` 继续在服务端级联删除 JourVolt 用户、session、加密 Tesla grant、车辆关联和同意记录；删除后旧 access/refresh 不能继续使用。
- 当 Fleet OAuth 已配置时，响应额外返回 `tesla_consent_revoke_url`；该 URL 使用已配置的 Tesla 区域授权域名、当前 `client_id` 和安全回落页面，不携带 Tesla token。
- Android 注销成功后只接受 HTTPS 的该 URL 并打开 Tesla 官方 consent 管理页；没有把“服务端删除本地 token”描述成“Tesla 授权已自动撤销”。官方路径见 [Tesla Third-Party Tokens](https://developer.tesla.com/docs/fleet-api/authentication/third-party-tokens)。
- 新增 Go `TestConsentRevokeURLUsesConfiguredTeslaRegion` 与 Android 注销回流契约测试；Go test/vet、Android Debug/Release 单元测试、Debug/Release 构建、Release lint 通过。

## 2026-08-22 注销回流返回地址收口

- 修正 `teslaOAuth.consentRevokeURL()`：Tesla 官方撤销页仍使用已配置的 Tesla 区域授权域名，但 `back_url` 改为已配置 JourVolt App Link 域名下的 `/privacy/`，完成 Tesla 撤销后回到 JourVolt 隐私说明页。
- `back_url` 继续强制 HTTPS；URL 不携带 Tesla token、授权码或 session。测试覆盖 Tesla 区域、JourVolt 回落域名和 `/privacy/` 路径。
- 本轮仅改变注销后的浏览器回流目标，不改变 OAuth、车辆读取、权限隔离或原 MateLink 页面。

## 2026-08-22 删除接口集成契约

- PostgreSQL 集成测试现在通过实际 `DELETE /v1/account` HTTP handler 解码响应，并校验 `status=deleted`、Tesla 官方区域撤销页和 JourVolt `/privacy/` `back_url`。
- 同一测试继续校验用户、session、加密 Tesla grant、车辆关联和同意记录级联删除，以及旧 access token 不能再次授权。
- 本轮使用临时隔离 PostgreSQL，测试结束自动清理；没有使用 Tesla 真实凭据，也没有把本地集成结果升级为真实 OAuth/Fleet 证据。

## 2026-08-22 Android 授权回调归属校验

- Android 在打开 Tesla 官方 Custom Tab 前继续要求 HTTPS、官方授权域名、固定授权路径、`response_type=code` 和最小 `openid offline_access` scope。
- 同时解析授权请求中的 `redirect_uri`，必须回到配置的 JourVolt App Link host 和 `/oauth/callback`，仅提供一个非空回调地址不再通过。
- 非 JourVolt 回调地址会在浏览器打开前 fail-closed；该校验只增强客户端边界，不把本地 Mock 或静态测试描述为真实 Tesla OAuth 证据。

## 2026-08-22 服务端回调路径配置门禁

- `loadTeslaConfig` 现在要求 `TESLA_REDIRECT_URI` 精确使用 `/v1/auth/tesla/callback`，`JOURVOLT_APP_LINK_URI` 精确使用 `/oauth/callback`。
- 两个地址必须是无用户信息、无 query/fragment 的 HTTPS URL，非标准端口也会 fail-closed；新增 Go 配置边界测试。
- 该配置收口与 Android 的 `redirect_uri` 校验保持一致，减少“服务端启动成功但授权后无法回 App”的错误配置；不改变真实 Tesla 审核、DNS、正式签名和公网 HTTPS 外部门禁。

## 2026-08-22 云端配置候选绑定（v26）

- Android Release 配置候选绑定 `https://api.jourvolt.com/` 为 JourVolt API 根地址，绑定 `auth.jourvolt.com` 为 App Link host；服务端仍要求 callback `/v1/auth/tesla/callback`，App Link `/oauth/callback`。
- 该绑定只证明本地构建参数已经收口，不证明 DNS、证书、反向代理、Tesla redirect URI 注册或公网接口可达；真实 Pilot 仍需外部门禁逐项验证。
- 未写入 Tesla client secret、refresh token、私钥或 `.env`；未启动公网服务。

## 2026-08-22 当前工作树 API 复验（v27）

- Docker Mock 登录、车辆列表、兼容快照、18 条行程、5 条充电和注销回收重新通过；结果保持 `LOCAL MOCK PASS`，不升级为真实 Tesla OAuth/Fleet 证据。
- Go `test ./... -count=1` 与 `go vet ./...` 通过；本轮没有读取或写入 Tesla 密钥，也没有启动公网服务。
