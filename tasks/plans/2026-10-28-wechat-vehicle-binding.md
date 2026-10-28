# 微信绑定与车辆会话恢复

Jovi 已授权实现本功能，并指定子 agent 使用 GPT-6.1 Terra / max。以小程序 `188b99cb` 为实现基线；Android build74 固定代码 `63cdb8ee` 只读。当前主任务是微信绑定，原有页面对齐审核保留为后续独立阶段。

## 副计划与依赖

1. **后端身份闭环（执行子 agent）**：仅修改 `deploy/jourvolt-dev-mock/`。增加真实微信 code 换身份适配器、持久映射、绑定状态、显式绑定/解绑、会话恢复以及配置示例。复用 Tesla verified subject 对应的 MateLink 用户，不按邮箱、VIN、车名合并。真实微信配置缺失时返回 503。
2. **后端规格审核（主 agent + 独立只读审核）**：验证微信 AppID 命名空间、一次性 code、重启持久化、冲突、账号注销、解绑后原会话及刷新会话不可再访问车辆；保留 App/Tesla 原登录及退出语义。
3. **前端恢复与交互（后端合同稳定后执行子 agent）**：修改 `miniprogram/src/services/{api,session}.ts`、`src/app.ts`、`src/pages/me/` 与必要测试。`Taro.login` 的 code 只传服务端；服务端签发 MateLink 会话。已授权用户可以明确绑定微信，后续启动恢复，显式退出后保持退出，支持重新进入、解绑和换 Tesla 账号。未经用户选择不自动建立新的绑定。
4. **安全/兼容审核（独立只读子 agent）**：按规格、再按代码质量顺序审核。覆盖响应迟到、退出/注销后恢复、跨账号切换、微信 code 重放、解绑与 restore/refresh 并发。全部修复后重审。
5. **综合验证与交付（主 agent）**：隔离 PostgreSQL 运行 Go 集成测试；Mini 运行 `npm test`、`npm run typecheck`、带 API 域名的 `npm run build:weapp`。微信开发者工具导入固定产物检查账号区域、绑定状态及未配置回退，记录 SHA、包版本和未验收项。

## API 合同

- `POST /v1/auth/wechat/restore`：`{code}`；绑定存在且 Tesla 授权可用时返回现有 MateLink session 形状。
- `GET /v1/auth/wechat/binding`：Bearer 会话，返回 `{bound:boolean,binding_version:string}`；版本为十进制字符串，不包含微信身份。
- `POST /v1/auth/wechat/bind`：Bearer 已授权会话 + `{code,binding_version}`，成功 `{status:"bound"}`；用户明确发起后先 GET 版本再获取一次性 code。
- `POST /v1/auth/wechat/unbind`：Bearer 会话，成功 `{status:"unbound"}`；微信来源会话撤销，原 Tesla 主会话语义保留。
- 预期错误：配置缺失 503，code 无效/过期/复用 401，未绑定 404，绑定冲突 409，Tesla 需重新授权 401。
- 不向小程序返回 openid、unionid、微信 session_key 或 Tesla 长期凭证；不在日志暴露微信 code / AppSecret。

## 验证与回滚边界

- 测试使用新建的本机隔离数据库，不借用生产 DSN，不清理用户数据。
- 服务端迁移为增量、可重入；关闭微信配置即可停止新绑定与恢复，既有 Tesla/App 登录继续可用。
- 不把本地合成 provider / 截图或测试通过称为真实微信绑定通过；实账号验收需要用户本人完成平台登录/授权。
- 代码与构建完成后按已有提交/推送/上传授权交付。真实微信 AppSecret、平台配置或本人 Tesla 登录缺失时只停在该外部步骤，继续完成可独立工作的内容。

## 审核记录

- [x] 固定小程序基线并确认工作树原始干净。
- [x] 确认 App 工作树有其他任务修改，使用冻结提交读取。
- [x] 后端闭环实现、PostgreSQL 回归与规格审核。
- [x] 前端实现、退出/换号隔离回归与审核。
- [ ] 全量验证、开发者工具检查、交付报告。

## 独立规格审查追加

- 后端初轮完整 PostgreSQL 回归 442 项通过、零失败/跳过；审查仍发现刷新先提交、过期令牌退出两种退出顺序缺口，待补确定性回归后关闭。
- 采用短期 logout-only 哈希关联处理刷新前令牌退出；该关联不得用于读取车辆或刷新。
- 解绑持久递增账号绑定版本。旧版本绑定在事务内返回 `409 wechat_bind_expired`，不自动重试；覆盖旧请求晚于解绑到达的情况。
- 前端补充：弹窗 scope/页面代次、旧绑定状态查询、恢复任务共享、旧账号内容遮蔽，以及普通请求不能破坏正在进行的 Tesla 授权。
