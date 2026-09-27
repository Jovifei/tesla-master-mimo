# 最终架构决策：Fleet API 多用户主链路 + Jovi 个人 TeslaMate 归档

更新时间：2026-09-27
决策人：Jovi
状态：当前权威架构，替代 2026-08-28 文档中“历史永不上传服务器”的约束

## 1. 最终结论

MateLink 面向多人使用时，正式新增数据必须走：

```text
每位用户
  -> MateLink 官方 Tesla OAuth
  -> 车辆级虚拟钥匙确认
  -> Fleet Telemetry
  -> JourVolt 云端按用户/车辆隔离
  -> MateLink App
```

Jovi 个人已经存在的 TeslaMate 历史走独立归档链路：

```text
Jovi 本地 TeslaMate PostgreSQL（只读）
  -> 本地 Archive Bridge
  -> MateLink 云端 TeslaMate 原始来源归档
  -> Jovi 账号 + 指定车辆
```

TeslaMate 不作为多人共享采集器，不接收其他用户的 Tesla 授权，也不把多人数据写入同一套 TeslaMate 数据库。

## 2. 两套授权必须分开

### 2.1 Tesla 官方车辆授权：所有正式用户都需要

这是数据采集授权，不是本地配置：

1. 用户在 MateLink 点击 Tesla 登录。
2. MateLink 跳转 Tesla 官方 OAuth 页面；密码、MFA 只在 Tesla 官方页面输入。
3. 用户授权应用所需的最小 OAuth scope。
4. MateLink 给出 Tesla 虚拟钥匙链接。
5. 用户在 Tesla 官方 App/页面确认把应用虚拟钥匙添加到车辆。
6. MateLink 通过 Command Proxy 发送 Fleet Telemetry 配置。
7. 车辆产生真实事件后，云端才可标记首事件和持续采集。

登录成功不等于 Telemetry 就绪。OAuth scope、车辆虚拟钥匙、签名配置、接收端 TLS、MQTT 落库和真实车辆事件是独立门禁。用户可以在 Tesla 车辆 Locks 页面移除虚拟钥匙以撤销车辆级实时访问。

### 2.2 TeslaMate 归档绑定：当前只给 Jovi 个人使用

这是 MateLink 自己的数据来源授权，不是 Tesla 官方授权：

1. Jovi 在 MateLink 中选择“TeslaMate 历史归档”。
2. App 显示当前云账号、目标车辆和来源实例标识。
3. Jovi 明确确认绑定；云端生成仅限账号、车辆和来源实例的可撤销凭据。
4. 本地桥接只读取 TeslaMate，使用该凭据上传归档批次。
5. Jovi 可在 App 中撤销绑定；撤销后桥接上传返回 401/403，断点不推进。

当前仓库已完成归档导入接口和本地桥接的第一版，但 App 内来源绑定/凭据发放 UI 仍是下一阶段工作。因此当前不能要求 Jovi 手填 `ARCHIVE_TOKEN`，也不能把本地 `.env` 当成最终用户授权体验。

## 3. 为什么多人必须以 Fleet API 为主

| 方案 | 多用户适合度 | 作用 |
| --- | --- | --- |
| Fleet OAuth + 虚拟钥匙 + Telemetry | **正式主方案** | 每位用户按账号和车辆授权；云端可按租户隔离；不要求用户运行 TeslaMate |
| Jovi 本地 TeslaMate 桥接 | **个人迁移/备份方案** | 保留已有完整路线、充电采样、地址和费用；依赖 Jovi 本地主机在线 |
| 一套 TeslaMate 服务多人共用 | **禁止** | 数据库缺少租户边界，行程隐私会混库 |
| 每个用户一套 TeslaMate | **禁止作为产品方案** | 资源、轮询配额、维护和数据绑定复杂度不可接受 |

Tesla Fleet API 的 OAuth token、用户、车辆和 Telemetry 会话必须按用户隔离。TeslaMate 来源记录不能覆盖 Fleet Telemetry 原始记录；跨来源只有明确确认同一会话且字段不冲突时才可合并展示。

## 4. 数据来源与存储规则

### 4.1 Fleet Telemetry 来源

- `source=telemetry_mqtt`。
- 用户和车辆由云端认证上下文确定。
- 只接收真实车端事件；没有事件时返回 collecting/waiting/awaiting_first_event，不把空数组转换成零值。
- `config_synced=true` 只能来自真实官方配置确认。

### 4.2 TeslaMate 归档来源

- `source=teslamate_archive`。
- 身份键：云用户、目标车辆、来源实例、来源车辆、来源记录类型、来源记录 ID。
- 行程保存路线点、起止地址和里程字段；充电保存采样点、地址、费用和能量字段。
- 同一来源记录重传必须幂等；不同来源记录即使开始时间相同也不能合并。
- null、明确零值、观测值和缺失值必须分别保留。
- 归档成功不代表 Fleet Telemetry 成功；两类来源在 UI 和质量字段中分开显示。

### 4.3 本地桥接

桥接运行在 Jovi TeslaMate 所在主机，使用 PostgreSQL 只读账号：

- 只执行 SELECT；不读取 Tesla 登录密码或 TeslaMate 加密密钥。
- 批次上传成功后才原子推进 JSON cursor。
- HTTP 失败、鉴权失败、服务端拒绝或进程重启都从上一次 cursor 重试。
- 日志不输出 token、VIN、精确坐标或原始响应正文。
- Docker profile 默认不启动，必须在来源绑定完成后显式启用。

## 5. 当前实现证据与边界

- TeslaMate 本地数据库已经恢复；最新真实行程能够通过 Adapter 到达手机。
- 手机有效行程数量已经与源库按页面过滤口径一致。
- `deploy/jourvolt-dev-mock` 已增加 `/api/v1/cars/{id}/history/archive/import`，按用户/车辆鉴权并保存归档来源字段。
- `deploy/teslamate-home-docker/archive-bridge` 已增加只读查询、路线/充电映射、批量上传和原子 cursor。
- 提交 `433d761` 已推送并部署为线上 API build；文档记录提交为 `6f47867`。
- 当前线上 `/readyz` 仍为 `fleet/postgres/ok` + `telemetry=awaiting_first_event`。这表示 Fleet Telemetry 尚未收到真实首事件。
- 当前没有配置归档 binding credential，桥接尚未上传真实云端记录。

## 6. 禁止再次发生的误解

1. 不向 Codex、MateLink 或聊天发送 Tesla 用户名、密码、MFA、refresh token、client secret 或私钥。
2. 不把登录成功当作车辆虚拟钥匙和 Telemetry 已完成。
3. 不把 TeslaMate 个人归档当作多人采集主源。
4. 不要求普通用户编辑本地 TeslaMate `.env`。
5. 不把 TeslaMate 归档记录计作 Fleet Telemetry 实车 PASS。
6. 不因云端无历史而重新启用跨用户 TeslaMate 共享数据库。
