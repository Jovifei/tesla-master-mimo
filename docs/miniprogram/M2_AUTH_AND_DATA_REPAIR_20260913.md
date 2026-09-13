# 小程序 M2 账号与数据链路整改

日期：2026-09-13

分支：`feature/wechat-miniprogram`

## 整改结果

- 服务端新增 `POST /v1/auth/wechat/session`。服务端使用 `WECHAT_APP_ID`、`WECHAT_APP_SECRET` 调用微信 `jscode2session`，只保留 openid 哈希；`session_key` 不返回、不落库、不写日志。
- 首次微信身份返回十分钟有效的一次性关联凭证。小程序携带该凭证启动 Tesla 官方 OAuth；OAuth state、nonce 和登录 ticket 均有过期与一次性消费约束。
- 小程序 web-view 使用自有 `/oauth/wechat/authorize` 桥接页接收 Tesla 回流，再用 `wx.miniProgram.postMessage` 返回一次性 ticket。桥接失败时保留浏览器/Android 手动返回入口。
- OAuth 成功后服务端绑定同一微信身份与 Tesla 用户；账号冲突、过期关联和 provider 重新授权都会返回分类错误，客户端不按 openid、VIN、车辆名或数字 car id 猜测账号。
- 会话层支持刷新、退出、单次 401 重试、隐私授权前置检查和账号作用域缓存。
- 车辆页改为读取 `key=telemetry`，显示车型、车辆状态、锁车、空调、充电/驾驶指标、坐标、来源和观测时间；服务端 status 会把车辆元数据回传，因此首次刷新后即可更新车型。
- 行程/充电页已接入分页、详情、当前充电和本地历史缓存；缓存按微信用户与稳定车辆隔离，云端空页不会删除本机较早记录，重复页不会重复计数，缺失字段保持 `暂无数据`。
- Fleet Telemetry 未配置时 readiness 会明确返回 `telemetry_not_configured`；provider 重新授权错误会保留 `pairing_required`/`permission_required`，不会被错误覆盖成“已就绪”。

## 本地证据

- Go：`go test ./...`、`go vet ./...`、`go mod verify` 通过。
- 小程序：`npm run typecheck`、Vitest 27 项、`npm run build:weapp` 通过。
- 构建时使用 `TARO_APP_API_BASE_URL=https://api.teslalink.joviluma.com`；产物应继续扫描确认没有动态 `process.env`。
- `npm audit --omit=dev --audit-level=high` 当前受 npm registry 网络超时阻断，不能据此宣称依赖安全通过；此前锁树的高危 advisory 仍是发布阻断项。

## 平台与真实数据门禁

以下项目需要 Jovi 在正式 AppID/主体和微信开发者工具、安卓微信、苹果微信中完成：

1. 微信后台配置 API `request` 业务域名和授权桥接页 `web-view` 业务域名，关闭开发工具的“跳过域名校验”。
2. 在 API 服务器只设置 `WECHAT_APP_ID` 与 `WECHAT_APP_SECRET`，不把 AppSecret 放入小程序工程或构建产物。
3. 验证微信会话 → Tesla 官方 OAuth → 回流 → 车辆选择；若 Tesla 要求虚拟钥匙，车主仍需在 Tesla App 中确认。
4. 验证 `config_synced=true`、首个真实 MQTT、一次真实行程和一次完整充电，再核对数据库/API/UI。
5. 在账号切换、授权撤销、回流失败、网络中断、分页中断和空云端响应场景下确认数据不串号、不丢失、不伪造。

当前工程可以进入 AppID 后的开发工具联调，尚无真实微信/Tesla/Telemetry 证据，不能宣称体验版、审核或生产上线完成。
