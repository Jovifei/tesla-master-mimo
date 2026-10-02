# M1 微信小程序与 Android 业务契约审计

日期：2026-09-12

审计基线：`feature/wechat-miniprogram@418e1e72325704fed9f3be645af15226b352aa6e`

对照：Android `TeslamateApi`、`TeslamateRepository`、`TeslaLoginViewModel`、`DataReadinessViewModel`、`UnifiedHistoryRepository` 与既有 Go 路由。

## 结论

`ANDROID_PARITY = PARTIAL`，`MINIPROGRAM_READY_FOR_REAL_DATA = NO`。M1 证明了 Taro 工程可以构建、页面路由可以生成、部分 DTO 可以归一化；没有证明微信登录、Tesla 关联、真实车辆、历史恢复或分析能力可用。

## 已对齐的部分（PASS）

- 四个主入口按交接规划生成：车辆、行程、充电、我的。
- 小程序只调用 MateLink API，不直接访问 Tesla Fleet API、MQTT、mTLS 或私钥。
- API 地址要求构建时注入 HTTPS；未配置时 fail-closed，不回退 mock 数据。
- 车辆列表适配 `data.cars`/`car_id`/`car_details.model`，未知车型保持“车型待识别”。
- 车辆状态适配 `data.status`、`battery_details`、`car_geodata`、`odometer`，保留 null。
- 纯函数覆盖未知 readiness、质量门槛和空云端保留本地行；6/6 测试通过。

## 关键差异与缺陷

### P0：身份与授权链没有实现

`miniprogram/src/services/api.ts` 的 `loginWithWechat()` 直接抛出“未实现”，没有调用 `wx.login`，没有 `/v1/auth/wechat/session` 交换，也没有一次性 Tesla 绑定事务、OAuth callback、challenge、冲突确认或会话刷新。Android 已有 OAuth code exchange、token rotation、callback replay 和 post-login onboarding；小程序目前不能登录后读取真实车辆。

### P0：就绪状态读取了错误的 item

Go/Android 的 `data-readiness` 同时返回 `live_status` 和 `telemetry`。持续采集状态在 `key=telemetry`，而 `miniprogram/src/pages/vehicle/index.tsx` 只查 `key=live_status`。车辆核心状态可用时，小程序可能把 Telemetry 显示成可用，即使服务端仍是 `waiting_vehicle`、`pairing_required` 或 `awaiting_first_event`。

### P0：行程和充电没有接入

Android 使用行程/充电分页、详情、当前充电和历史合并；小程序 `DrivesPage`、`ChargesPage` 只显示说明文字，`matelinkApi` 没有 drives/charges 请求。当前不会展示任何真实历史，也没有第二页失败续传、详情或当前充电。

### P1：车辆展示只覆盖最小字段

Android 展示状态、锁车、空调、胎压、充电、驾驶、位置、来源和观测时间等嵌套字段；小程序只展示 state、电量、额定续航、里程。`status` 接口当前不返回 `observed_at/source` 顶层值，小程序因此通常无法显示新鲜度和来源；位置已归一化但页面没有地图或位置展示。

### P1：多车、账号作用域和生命周期未实现

小程序固定取 `cars[0]`，没有车辆选择、稳定车辆身份或账号切换清理。页面只在一次 `useEffect` 中读取，没有 Android 的前后台恢复、请求代次、轮询停止/恢复和过期会话处理。`session.ts` 只有读写函数，没有任何写入调用、过期检查、refresh、logout 或撤销。

### P1：错误分类被丢失

`requestJson` 只抛出 `MateLink API <status>`，没有保留 `permission_required`、`pairing_required`、`telemetry_error`、`billing_blocked`、`Retry-After` 或服务端 message key。Android 的 readiness 和登录页面按这些分类给出不同操作，小程序会把它们混成通用异常。

### P1：历史合并仍是占位实现

`mergeHistoryByKey` 只按字符串 ID 追加，重复时静默保留本地行；没有 Android 的同会话匹配、字段证据优先级、quality/source 合并、请求代次和账号作用域。它不能替代 `UnifiedHistoryRepository` 或服务端历史导入，也没有分页游标。

### P2：平台与发布边界未验证

`project.config.json` 没有 AppID，业务域名/隐私主体/类目未核验；未在微信开发者工具、安卓微信或苹果微信真机运行。当前锁树的 npm audit 仍有 12 个传递依赖 advisory（3 critical），发布安全门禁未通过。

## Android 接口覆盖矩阵

| Android 能力 | 小程序当前状态 |
| --- | --- |
| `cars` | 部分实现，固定首车 |
| `status` | 部分实现，字段明显减少 |
| `data-readiness` | 部分实现，读取错误的 `live_status` |
| `telemetry/pairing` | 有方法但没有页面调用 |
| `telemetry/configure` | 未实现 |
| `drives` / drive detail | 未实现 |
| `charges` / current / detail | 未实现 |
| battery health / updates / global settings | 未实现 |
| history import | 未实现 |
| OAuth/session/refresh/logout | 未实现 |

## 放行判断

当前只能放行 M1 隔离开发和微信开发态构建，不能放行真实数据测试、体验版发布或人工审核提交。申请 AppID 前可以由 Jovi 完成主体、类目、域名和隐私资料准备；取得 AppID 后先实现并测试 M2 微信会话/账号关联，再进入 M3 车辆就绪。不要在当前骨架上承诺“和 Android 一样”。

本审计没有修改业务源码；具体修复应按 M2→M5 分层授权和测试。
