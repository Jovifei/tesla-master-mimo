# 微信小程序 M1 接口契约边界

## 已存在、优先复用

小程序只调用 MateLink 自有 HTTPS API，并沿用当前后端的会话和车辆作用域：

- `GET /api/v1/cars`
- `GET /api/v1/cars/{carId}/status`
- `GET /api/v1/cars/{carId}/data-readiness`
- `GET /api/v1/cars/{carId}/telemetry/pairing`
- `GET /api/v1/cars/{carId}/drives`
- `GET /api/v1/cars/{carId}/charges`
- `GET /api/v1/cars/{carId}/charges/current`

当前 M1 只实现车辆/状态/readiness/pairing 的适配入口；行程和充电页面保留边界提示，不伪造列表数据。

现有响应常见包装是 `data.cars`、`data.status`、`data.items` 和 `data/meta`。小程序在适配层归一化嵌套响应，保留 `null`、0、false、来源和时间，不把接收时间改名为车辆观测时间。

## 拟新增、尚未实现

以下接口来自交接设计，不得当作当前后端已有能力：

- `POST /v1/auth/wechat/session`
- `POST /v1/account-links/tesla/transactions`
- `GET /v1/account-links/tesla/transactions/{id}`
- `POST /v1/account-links/tesla/transactions/{id}/confirm`
- `POST /v1/account-links/tesla/transactions/{id}/cancel`
- `GET /v1/account/identities`

这些接口必须绑定微信会话、client type、challenge、OAuth state/nonce 和过期时间；必须防重放、CSRF、越权、账号冲突和跨账号历史继承。M1 不会以客户端 openid、VIN、车辆名或数字 carId 代替服务端验证。

## 错误与质量

小程序应分别呈现 `permission_required`、`pairing_required`、`telemetry_not_configured`、`waiting_vehicle`、`telemetry_error`、`billing_blocked` 和网络/超时。HTTP 200、`202 configuring` 或空历史都不能单独代表持续采集已就绪。

历史列表可保留不完整摘要；要求完整证据的分析只接受 `observed`/`derived`。云端空响应不能删除合法本地缓存，分页中断必须保留已成功页面并允许续传。
