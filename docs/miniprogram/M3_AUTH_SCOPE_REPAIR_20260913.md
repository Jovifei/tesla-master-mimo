# 小程序 M3 账号、车辆、历史与授权事务整改

日期：2026-09-13

基线：`5adaa47dea8889a3babb07a1475696c5becccd81`

候选与正式改动已在隔离 worktree 增量完成，版本更新为 `0.2.1`。

## M3-A

- API 请求捕获会话代次和 canonical user；旧账号的 200、401、网络失败、刷新和 finally 不会写入新账号。
- refresh flight 按会话 owner 隔离；退出、重新登录和同账号重登都会使旧 flight 失效。
- 车辆、行程、充电页面按 API origin、用户、会话代次和 stable vehicle 建立 scope；切车、切后台、卸载和详情乱序都会使旧 lane 失效。
- 历史合并保留 `source`/`quality_state` 证据，保留 0/false，避免不可信导入洗白 observed；同 canonical session 使用最新 public ID；分页缺页/重复页不会标记完整。
- 缓存使用 v2 schema，包含 API origin、账号、车辆和会话代次；旧格式隔离保留，缓存写入失败显示在线数据未保存提示。

## M3-B

- 微信 Tesla 授权事务绑定 channel、client proof、预期 canonical user、OAuth state/nonce 和过期时间。
- 已绑定用户重新授权必须携带原会话并回到同一 canonical user；冲突 fail-closed。
- 微信回流只携带短期 callback reference；小程序用原 proof 查询/领取，URL 不再携带可直接兑换的登录 ticket。
- 小程序 web-view 失败时返回微信并回查事务；微信 channel 不使用 Android `intent://`。
- grant、login ticket、微信身份绑定、challenge 消费和授权 artifact 在同一 PostgreSQL 事务中提交；claim 事务锁保证一次性领取。
- logout 会尝试取消待授权事务，并始终清理本地敏感状态。

## 本地证据

- `go test ./... -count=1`、`go vet ./...`、`go mod verify`、`go build ./...`：PASS；Go JSON 证据中 15 个 PostgreSQL 相关用例因未设置 `JOURVOLT_TEST_DATABASE_URL` SKIP。
- 小程序 `npm ci --dry-run`、`npm run typecheck`、Vitest 7 文件/42 项、配置 API host 的 `build:weapp`：PASS。
- 产物扫描：动态 `process.env`、AppSecret、session_key、grant/private-key 标记未发现；`URLSearchParams` 出现在产物中，真实微信运行时仍需平台验证。
- M3 包候选回归：历史 22 + scope 8、API 片段 11；这是辅助证据，不替代完整工程测试。
- `JOURVOLT_TEST_DATABASE_URL` 未配置时 PostgreSQL 并发/故障用例会 SKIP；Docker Linux 引擎不可用时不能伪造 PG PASS。
- 在线 `npm audit --omit=dev --audit-level=high`：退出码 1，12 项（critical 3、moderate 8、low 1）；完整 audit 同样失败。不能用离线空报告替代。

## 仍需人工门禁

正式 AppID/主体、request 与 web-view 业务域名、安卓微信和 iOS 微信、Tesla 官方同意、虚拟钥匙、`config_synced`、首个 MQTT、真实行程/充电、换机历史恢复和生产部署都未在本轮验证。未验证项不能标记 PASS，也不能宣称 READY_FOR_RELEASE。
