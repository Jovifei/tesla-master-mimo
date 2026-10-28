# 0.6.0 微信绑定实现与验收边界

状态：代码实现、规格审查和质量审查通过；真实微信与 Tesla 联调未验收。

本轮按 Jovi 要求使用 GPT-6.1 Terra / max 执行子任务，由主代理负责分工、独立审核、整合和最终验证。小程序工作树为 `E:\Claude_allow\Download\mp-parity-wt-20261025`，分支为 `codex/tesla-auth-devtools-fallback-20261024`；实现前 HEAD 为 `188b99cb`。Android App 工作树只读。

## 完成的副计划

| 子任务 | 结果 | 证据 |
|---|---|---|
| 后端身份映射与接口 | PASS | 微信 AppID/openid 作用域哈希关联原 MateLink/Tesla 用户；不按车辆、邮箱或名称合并 |
| 前端恢复与绑定交互 | PASS | Tesla 主入口保留；确认绑定、状态、解绑、手动微信进入、启动/页面共享恢复 |
| 并发和账号隔离 | PASS | 一次性 code、确认前版本、退出/换号/注销、迟到响应、解绑与刷新、过期锁等待 |
| 独立规格与质量审查 | PASS | 两端均由独立只读子 agent 审查；发现问题先复现、修复后复审 |
| 本机综合检查 | PASS | Mini 807 项、资源6项、类型检查；真实隔离 PostgreSQL 的 Go 465 PASS、0 FAIL、0 SKIP |
| 真实 provider/设备 | NOT_RUN | 服务端微信私有配置与本人 Tesla 登录尚未完成 |

## 用户流程

1. 首次仍在 Tesla 官方页面登录，进入已有 MateLink 账号及车辆。
2. 在“更多 → 账号管理”选择绑定当前微信；确认前读取绑定版本，确认后才获取一次性微信 code。
3. 用户启用后，后续可恢复同一账号。主动退出会关闭本机自动进入，也保留手动使用已绑定微信进入的入口。
4. 解绑删除微信身份关系并撤销微信来源会话；原 Tesla 来源会话保留。切换 Tesla 账号不会继承旧账号恢复设置。
5. Tesla 授权失效时仍需官方重新授权；微信身份本身不能取得新的车辆权限。

## 接口与数据边界

| 接口 | 合同 |
|---|---|
| `GET /v1/auth/wechat/binding` | Bearer；返回 `bound` 和十进制字符串 `binding_version` |
| `POST /v1/auth/wechat/bind` | Bearer、新 code、确认前固定的版本；旧意图返回 `409 wechat_bind_expired` |
| `POST /v1/auth/wechat/unbind` | Bearer；事务内递增持久版本并删除绑定，即使此前未绑定也递增 |
| `POST /v1/auth/wechat/restore` | 新 code；返回原账号的 MateLink 会话，不返回微信身份或 Tesla 凭证 |

微信 `session_key` 丢弃；openid/unionid 仅以服务端作用域哈希保存。小程序不保存 Tesla 密码、Tesla refresh token、AppSecret、微信 code 或身份值。本机保存 MateLink 会话、当前账号及来源隔离的非秘密恢复偏好。

账号恢复先验证已有 Tesla grant；未到期的本地 Tesla access 若被远端提前撤销，仍以随后 Fleet 调用发现并要求重新授权为边界。刷新前 access 哈希在15分钟内仅可用于退出同一会话，不能读取车辆、绑定或刷新；超出该窗口不保证任意迟到退出请求。

绑定/解绑在数据库锁等待结束后、提交前再次校验凭证时间。解绑和注销使微信来源会话及其刷新凭证失效。前端各页面等待同一个恢复任务；失效或换号时遮蔽旧车辆、位置和历史，普通请求不会删除正在进行的 Tesla 授权证明。

## 构建、生产与后续

- 版本为 0.6.0，API 为 `https://api.teslalink.joviluma.com`，合成数据构建开关关闭，域名校验保持开启。
- 2026-10-29，微信开发者工具显示 0.6.0 代码上传成功，固定代码提交 `a2bac32c3b2998eea918fed82c35aa7203a06b99`。这是已上传的开发版本；微信后台设置体验版与正式发布状态未确认。
- 同日服务器 API 已部署同一代码提交。公网 `/healthz` 返回 `ok`、`fleet` 和该提交；旧环境、端口、挂载和用户保持，部署前后核心数据数量一致。部署前已在服务器私有位置备份数据库和原配置，并保留固定旧镜像的回滚方案。
- 服务器已配置小程序 AppID，AppSecret 尚未配置；真实微信恢复接口因此返回 `503 wechat_not_configured`。填入私有 AppSecret、启用服务并完成真实微信交换与车辆读取后，才能确认微信绑定可用。AppSecret 不进聊天或 Git。
- API 合同附带增量迁移、回滚和网关限速要求。真实账号验收和微信平台发布状态分别记录。
- 全页面跟随 App 的目标仍有独立待办，见 `MP-APP-PARITY-FOLLOWUP-20261028.md`。本轮不把微信绑定通过等同于费用、驻车、筛选和地图完全对齐。

本机验证日志、上传成功截图和生产部署回执保存在 `E:\Claude_allow\Download\matelink-wechat-binding-20261028`；独立测试数据库未使用生产数据。
