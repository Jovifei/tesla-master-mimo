# MateLink 微信小程序 M0 可行性记录

日期：2026-09-11

仓库基线：`origin/main@e9197d892621d8dac39b04e4221d603de2241155`

实现分支：`feature/wechat-miniprogram`

## 证据分类

### REMOTE_VERIFIED

- `git fetch --all --prune` 后，产品远端为 `Jovifei/tesla-master-mimo`；`origin/main` 与已合并 Android 修复一致。
- ZIP 内 Manifest 与 18 个文件 SHA-256 全部匹配；交接基线为 `e9197d8`。
- 当前仓库没有顶层小程序工程；`deploy/jourvolt-dev-mock` 是既有 Go/Fleet/PostgreSQL 后端，不是小程序实现。

### REPO_RECORDED

- Android `com.matelink` 2.1.12/build31 已构建并覆盖安装；ECS 记录为 `6ced331`、`fleet/postgres/ok`、`awaiting_first_event`。
- 既有 Tesla OAuth、Fleet Telemetry、虚拟钥匙和历史分页能力只代表源码/记录中存在；线上最新部署、微信容器和跨设备绑定仍需单独验证。

### PROPOSED

- M1 采用 Taro 4.2.1 + React 18.3.1 + TypeScript 5.7.3，Webpack 5 构建；依赖已锁定并完成开发态微信构建。
- 小程序只访问 MateLink 自有 HTTPS API；不访问 Tesla Fleet API、MQTT、mTLS 或服务器私钥。
- 首版保留车辆、行程、充电、我的四个入口，优先显示来源、更新时间、就绪状态和缺失原因。
- Tesla OAuth 通过官方页面；微信会话与既有 MateLink canonical 用户的安全关联属于 M2，不能由 openid、VIN 或车辆名称直接认领。

### UNVERIFIED / BLOCKED

- 没有提供微信主体、AppID、类目、request/upload 业务域名或发布态后台截图，无法证明正式小程序准入。
- 没有在微信开发者工具、安卓微信和苹果微信中验证 Tesla OAuth、取消/超时/后台恢复、虚拟钥匙跳转或回流。
- 现有后端没有已确认的微信会话/账号关联接口；`/v1/auth/wechat/session` 和 account-link 事务仍是拟新增契约。
- 没有部署当前 `main` 到 ECS，也没有真实 `config_synced=true`、首个 MQTT、完整行程/充电证据。

## 结论

`AUTH_FEASIBILITY = UNVERIFIED`。M1 工程骨架可继续做隔离开发；M2 身份关联、M3 真实车辆和 M6 发布验收必须等待主体/AppID/域名与两平台真实验证。开发工具跳过域名校验、synthetic fixtures 或 HTTP 200 都不能把该状态改为 PASS。
