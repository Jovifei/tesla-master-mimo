# Fleet API 多用户主链路 + Jovi TeslaMate 个人归档实施计划

日期：2026-09-27
依据：[`docs/ARCH-DECISION-2026-09-27-fleet-teslamate-hybrid.md`](../../docs/ARCH-DECISION-2026-09-27-fleet-teslamate-hybrid.md)
适用分支：`codex/teslamate-cloud-archive`
当前提交：`6f47867`（归档 API 已部署，绑定流程尚未完成）

## 0. 最终目标与硬边界

### 最终目标

- 多用户正式新增数据由每位用户的 Tesla OAuth、车辆虚拟钥匙和 Fleet Telemetry 提供。
- Jovi 个人 TeslaMate 历史通过本地只读桥接完整归档到 Jovi 账号和指定车辆。
- Fleet Telemetry 原始来源与 TeslaMate 归档来源独立保留；不跨来源静默覆盖、不重复统计、不跨账号/车辆认领。
- 普通用户只操作 App 和 Tesla 官方授权页面，不编辑 TeslaMate `.env`，不接触桥接 token。

### 禁止事项

- 不接收、记录或请求 Tesla 用户名、密码、MFA、refresh token、client secret 或私钥。
- 不让一套共享 TeslaMate 服务采集多个用户。
- 不把 TeslaMate 归档成功写成 Fleet Telemetry 实车 PASS。
- 不把 HTTP 200、空数组、Mock、历史导入或本地手机数据当作真实车端首事件。
- 不清空手机 Room/DataStore，不卸载正式 `com.matelink`，不覆盖 TeslaMate 原数据卷。

## 1. 当前基线和已完成证据

### 已完成

- TeslaMate 本地 PostgreSQL、Adapter 和手机自托管读取已恢复。
- 源库最新真实行程已到手机，手机有效行程口径与源库一致。
- 服务端已增加 `/api/v1/cars/{id}/history/archive/import`。
- 服务端保留 `teslamate_archive` 来源、来源实例/车辆/记录 ID、路线点、充电采样、地址、费用和 null/zero 语义。
- 本地 `archive-bridge` 已实现只读 SELECT、批量上传、HTTP 脱敏、原子 cursor 和失败重试。
- 归档 API 已部署到 `jourvolt-pilot`，线上 build 为 `433d761`；PostgreSQL、Fleet Telemetry、MQTT、Command Proxy 未重启。

### 当前未完成

- App 内的 TeslaMate 来源绑定和可撤销归档凭据流程。
- 桥接 token 的安全下发/撤销/过期机制。
- 真实归档批次上传和云端源库数量/记录核对。
- Fleet Telemetry 真实虚拟钥匙确认、配置确认、MQTT 首事件、真实行程和充电验收。

## 2. 阶段 A：完成 App 驱动的来源绑定授权

### A1. 云端绑定模型和接口

目的：把当前工程人员手填 `.env` 的临时路径替换为用户确认、账号/车辆限定、可撤销的绑定。

文件范围：

- `deploy/jourvolt-dev-mock/telemetry_store.go`
- `deploy/jourvolt-dev-mock/telemetry_import.go`
- `deploy/jourvolt-dev-mock/telemetry_http.go` 或 `main.go`
- 新增绑定单测和租户隔离单测

实现要求：

1. 新增 `history_archive_bindings` 加性表：user、vehicle、source_type、source_instance_id、source_vehicle_id、credential_hash、created_at、expires_at、revoked_at。
2. `POST /api/v1/cars/{id}/history/archive/bind` 只允许当前用户的当前车辆；请求必须明确 source instance/vehicle。
3. 返回一次性、可撤销、只用于归档上传的凭据；数据库只存 hash，不存明文。
4. `POST /history/archive/import` 同时要求普通用户会话和有效绑定凭据；凭据只能作用于绑定的用户/车辆/来源三元组。
5. 增加 revoke/status 接口；撤销后返回 401/403，cursor 不推进。

当前实现状态：

- 服务端已增加绑定表、一次性 hash 凭据、绑定创建/状态/撤销和归档上传 Header 校验。
- 绑定版已部署到 ECS `jourvolt-pilot`，线上 build `64b5a8a`；未重启 PostgreSQL/Fleet/MQTT/Command Proxy。
- Go 全量测试/vet 和临时 PostgreSQL 集成测试均通过；真实用户 App 调用仍待 A2。

验证：

- RED：缺 source、错车辆、错账号、过期、撤销、重复绑定均失败。
- GREEN：正确绑定可创建、重复上传幂等、两个账号互不可见。
- 命令：`go test ./...`、`go vet ./...`、PostgreSQL 集成测试。
- 状态：`PASS` 仅表示服务端契约；不表示用户已完成 Tesla 授权。

回退：只停止新绑定接口或撤销绑定；旧 `/history/import` 保持兼容，原始 TeslaMate 数据不删除。

### A2. App 绑定界面

状态：当前下一项。服务端已完成，但必须先完成 App 入口和本地 bridge enrollment，不能要求 Jovi 手工复制 token 到 `.env`。

文件范围：

- `android/app/src/main/java/com/matelink/ui/screens/readiness/DataReadinessScreen.kt`
- `android/app/src/main/java/com/matelink/ui/screens/readiness/DataReadinessViewModel.kt`
- `android/app/src/main/java/com/matelink/data/api/TeslaMateApi.kt`
- `android/app/src/main/java/com/matelink/data/repository/TeslamateRepository.kt`
- `android/app/src/main/res/values*/strings.xml`

用户流程：

1. App 云端模式下显示“TeslaMate 历史归档（仅个人）”。
2. 用户确认目标车辆、来源实例名称和上传范围。
3. App 调用绑定接口并展示“已绑定/已撤销/待上传/失败原因”。
4. App 不显示 Tesla 密码，不显示云端 access token，不要求用户输入 `.env`。
5. 归档凭据只写入本地受保护存储，或通过局域网一次性 enrollment 发送给本地 bridge；UI 不记录原文日志。

验证：

- ViewModel 行为测试覆盖确认、取消、网络失败、撤销、重新绑定。
- Release lint、Debug/Release JVM、同签名真机 `adb install -r`。
- 手工门禁：用户明确确认一次来源绑定；不清除现有自托管配置。

## 3. 阶段 B：本地 Bridge enrollment 和真实归档

### B1. Bridge 安全接入

文件范围：

- `deploy/teslamate-home-docker/archive-bridge/*`
- `deploy/teslamate-home-docker/docker-compose.yml`
- `deploy/teslamate-home-docker/README.md`

要求：

- 数据库连接默认使用只读角色；启动时拒绝空 token、空来源身份和非 HTTPS 公网归档地址。
- 支持一次性 enrollment 或从 App 传入已 hash 绑定凭据；不把凭据写入日志。
- source vehicle ID 必须由绑定确认，不按昵称、车型或数字 ID 猜测。
- 断点只在服务端 2xx 后推进；批次失败保持旧 cursor。
- 每日 reconciliation 重新读取最近修正窗口，使用来源记录 ID 幂等更新。

验证：

- 使用隔离 PostgreSQL fixture 验证 SELECT-only、route/charge/null/zero 映射。
- 重复上传、HTTP 401/403、服务端 500、进程重启、cursor 损坏均有测试。
- 不连接生产 token 做本地单测。

### B2. 真实小批上传

前置：

- 阶段 A 绑定 PASS。
- ECS API build 与本地桥接契约一致。
- 本机 Docker TeslaMate 仍运行，源库逻辑备份可恢复。

执行顺序：

1. 先上传 1 条最新已结束行程。
2. 云端按账号/车辆/来源查询，核对开始/结束时间、路线点数量、地址和 null/zero 字段。
3. 重复运行桥接，云端数量不增加、记录不重复。
4. 上传 1 条充电记录后核对采样和费用。
5. 再开启全量历史；容量或错误超限时停止扩批，不静默截断。

状态标签：

- `ARCHIVE_SOURCE_PASS`：TeslaMate 原始记录已落云端。
- `FLEET_TELEMETRY_PASS`：只有真实 Fleet Telemetry 车端事件才能使用。

回退：撤销 binding credential 或停止 archive profile；保留 TeslaMate、手机 Room 和已上传原始归档。

## 4. 阶段 C：Fleet Telemetry 正式用户链路

### C1. 用户 OAuth

- App 只跳转 Tesla 官方授权页。
- 请求最小必要 scope；位置功能明确请求 `vehicle_location`。
- 服务端保存加密 token，不把 Tesla 密码返回或写入 App。
- OAuth 成功只标记账号授权，不直接标记 Telemetry 已就绪。

### C2. 虚拟钥匙和配置

1. App 展示“前往 Tesla 添加虚拟钥匙”。
2. 用户在 Tesla 官方 App/页面确认车辆。
3. 服务端通过 Vehicle Command Proxy 签名配置请求。
4. `config_synced=true` 只能来自真实配置确认。
5. `awaiting_first_event` 持续显示等待，不生成 GPS/行程/充电伪数据。

### C3. 真实验收

- 覆盖至少 3 趟真实行程和 1 次真实充电。
- 检查 MQTT 入站、数据库落库、App 展示、来源、时间戳、重复和跨账号隔离。
- 观察至少 7 天；Fleet 成为新增数据主源后，TeslaMate 仅保留个人回退/归档能力。

## 5. 上线门禁、回滚和停止条件

### 上线顺序

```text
服务端绑定接口
  -> App 绑定 UI
  -> Bridge enrollment
  -> 1 条 TeslaMate 小批
  -> 重复/断网/修正验证
  -> TeslaMate 全量归档
  -> 每人 Fleet OAuth
  -> 虚拟钥匙
  -> Fleet 首事件
  -> 7 天并行观察
  -> Fleet 新增采集主源
```

### 任一阶段失败时

- 停止当前阶段，不删除上一阶段数据。
- 归档失败只停止 bridge，不改 TeslaMate 源库。
- Fleet 失败继续保留 TeslaMate 个人自托管读取。
- App 仍显示未知/等待/不可用，不用 0 替代缺失。
- 生产数据库只做加性迁移；先备份并做隔离恢复。

### 完成标准

只有以下条件全部满足才可宣称项目完成：

- 多用户 OAuth/虚拟钥匙/Fleet 数据互不可见；
- Jovi TeslaMate 全量归档可重复恢复；
- Bridge 断网、重启、重复、修正都不丢不重；
- Fleet Telemetry 有真实首事件和持续真实行程/充电；
- App 不要求 Tesla 密码、不要求普通用户编辑 `.env`；
- 线上部署、签名包、真机和人工授权证据分别记录。
