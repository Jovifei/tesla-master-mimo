# 微信小程序接口契约边界（M2 修订）

M1 审计记录保留在 `M1_ANDROID_PARITY_AUDIT_20260912.md`；本文件记录当前实现后的契约。

## 已存在、优先复用

小程序只调用 MateLink 自有 HTTPS API，并沿用当前后端的会话和车辆作用域：

- `GET /api/v1/cars`
- `GET /api/v1/cars/{carId}/status`
- `GET /api/v1/cars/{carId}/data-readiness`
- `GET /api/v1/cars/{carId}/telemetry/pairing`
- `GET /api/v1/cars/{carId}/drives`
- `GET /api/v1/cars/{carId}/charges`
- `GET /api/v1/cars/{carId}/charges/current`

当前实现覆盖车辆/状态/readiness/pairing、行程/充电分页与详情、当前充电、微信会话和 Tesla 官方授权回流；仍只显示服务端已确认的真实字段。

现有响应常见包装是 `data.cars`、`data.status`、`data.items` 和 `data/meta`。小程序在适配层归一化嵌套响应，保留 `null`、0、false、来源和时间，不把接收时间改名为车辆观测时间。车辆持续采集只读取 `items[key=telemetry]`。

## 微信会话与 Tesla 关联

当前后端已实现：

- `POST /v1/auth/wechat/session`
- `GET /v1/auth/tesla/start`（带 `X-WeChat-Link-Token` 时创建一次性关联事务）
- `GET /v1/auth/tesla/callback`（消费 OAuth state/nonce，服务端保存加密 Tesla token）
- `POST /v1/auth/exchange`（消费一次性登录 ticket）
- `GET /oauth/wechat/authorize`（小程序 web-view 的自有桥接入口）

`POST /v1/auth/wechat/session` 只返回 `authenticated` 或短期 `link_required` 凭证。服务端根据 AppID 与 openid 哈希查找已绑定用户；首次身份必须完成 Tesla 官方 OAuth，不能由客户端自行认领账号或历史。

所有关联凭证、OAuth state、登录 ticket 都有过期和一次性消费约束；微信 `session_key`、AppSecret、Tesla token、VIN 和精确位置不进入小程序代码、缓存或日志。

## 错误与质量

小程序应分别呈现 `permission_required`、`pairing_required`、`telemetry_not_configured`、`waiting_vehicle`、`telemetry_error`、`billing_blocked` 和网络/超时。HTTP 200、`202 configuring` 或空历史都不能单独代表持续采集已就绪。

历史列表可保留不完整摘要；要求完整证据的分析只接受 `observed`/`derived`。云端空响应不能删除合法本地缓存，分页中断必须保留已成功页面并允许续传。
