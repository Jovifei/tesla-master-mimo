# 架构决策：路线三 — Fleet API 多租户 + 手机本地历史（2026-08-28）

| 项 | 值 |
| --- | --- |
| 决策人 | Jovi |
| 决策状态 | **已拍板，按本方案执行** |
| 背景文档 | `docs/PRD-2026-08-28-jourvolt-deploy-login.md`、`docs/ARCH-2026-08-28-jourvolt-deploy.md` |
| 适用范围 | MateLink App（`com.matelink`）+ JourVolt Go API + 阿里云 ECS `120.55.64.11` |
| 本文档目的 | 让后续任何 agent / 开发会话在执行前先读懂"为什么是路线三"，避免回退到已否决的方案 |

---

## 0. 一句话结论

> **多用户云端服务走 Fleet API 多租户（JourVolt Go API），行车历史由 App 在各用户手机本地积累，服务器只存加密登录凭据与实时状态转发，永不存用户的行车数据。**

---

## 1. 产品目标（Jovi 原始口径）

1. App 将**发行给多人共同使用**（当前 7–8 人，未来更多）。
2. **每个用户的行车数据保存在用户自己的手机上**，不要保存在服务器；尽量不让云端接触行车数据。
3. 服务器只做：用户登录凭据的安全保管 + 实时车辆状态转发。

## 2. 三个候选路线的完整对比（含实测数据）

### 路线一：TeslaMate 一套（2026-08-28 下午曾部署，现已停用）

```
App → matelink-adapter → TeslaMate 数据库 → Tesla（TeslaMate 7×24 轮询攒历史）
```

- **1 个用户：完全可用**。用户手机里 190 条行程 / 1600 km / 289 kWh / 31 次充电的历史，全部来自本路线。
- **多用户不可行**：TeslaMate 数据库 schema（`cars`/`positions`/`drives`/`charges`）**没有用户维度**，8 个人授权后数据混在同一批表里，App 无法按用户区分，且等于互相可见行车隐私。
- 实测五容器 RSS 合计 ≈ 276 MB（`postgres 51.6 + teslamate 216.3 + adapter 4.6 + teslamateapi 1.9 + mosquitto 2.1`）。
- 处置：容器已 `stop`（数据卷保留），作为**单用户自托管备用能力**长期保留，不参与多用户产品。

### 路线二：每人跑一套 TeslaMate（8 套）——否决

- 内存：276 MB × 8 ≈ **2.2 GB > 服务器 1.87 GB**，直接装不下。
- 配额：TeslaMate 的工作方式是高频轮询（每车每天数万次请求），**Tesla 官方 Fleet API 对第三方应用有严格限流**，8 个用户必然触发封禁。
- 运维：8 套 token、8 套数据库、8 份备份，任何一套出问题都是线上事故。
- 结论：**无论换多大的服务器都不可行**，这是配额与架构问题，不是资源问题。

### 路线三：Fleet API 多租户（✅ 已选定）

```
App → JourVolt Go API（按用户 AES-GCM 加密 token，按需查询）→ Tesla 官方 Fleet API
                ↘ 实时状态原样转发给 App
历史：App 在手机 Room 本地把快照积累成行程/充电记录（见 §4）
```

- 实测 Go API 常驻 ≈ 30 MB 级，单实例天然支持多用户（`user → tokens` 按用户隔离）。
- 查询只在用户打开 App 时按需发生，配额友好。
- 服务器**永不存**行车数据；只存登录会话、加密 token、车辆 ID 映射。
- **这正是产品目标的原话实现**："数据保存在用户自己的手机上"。

## 3. 关键技术事实（代码证实，后续 agent 不要再重新论证）

1. **App 从不直连 Tesla**。唯一 Retrofit 接口 `TeslamateApi`，端点全部是 `api/matelink/v1/*`（Adapter 原生路由）与 `api/v1/*`（TeslaMateApi 兼容路由）。见 `android/app/src/main/java/com/matelink/data/api/TeslaMateApi.kt`。
2. **两种连接模式只是换 baseUrl**：`TeslamateRepository` 按 `ConnectionMode` 选择 `BuildConfig.JOURVOLT_API_BASE_URL`（云）或用户自填 `settings.serverUrl`（自托管），请求/解析代码完全一致。见 `TeslamateRepository` 第 151 行。
3. **Tesla 官方 API 不提供行程/充电历史端点**，只有实时状态（`/api/1/vehicles/{vin}/vehicle_data`）。TeslaMate 的历史是它自己高频轮询攒出来的——这就是"历史必须有人攒"的根源。
4. **云端模式下历史当前为空**：`deploy/jourvolt-dev-mock/main.go` 566–589 行，fleet 非 mock 时 `drives`/`charges` 返回空数组；App 侧历史唯一入口是 `SyncRepository.syncDriveSummaries/ChargeSummaries`（从后端同步），**App 没有本地自产历史的机制**。
5. 自托管 Adapter 端口 `127.0.0.1:18080`，JourVolt Go API 端口 `127.0.0.1:18090`。nginx 已按域名分层：`teslalink.*` → 18080（自托管），`api.*` → 18090（云端），`auth.*` → 静态 + App Link。**不要混淆这两个端口**——502 只说明五容器处于 stop 状态，不是配置错误。

## 4. 历史积累决策：方案 A — App 手机本地攒（已选定）

三个选项：TeslaMate 攒（= 路线一，单用户）/ 服务器按用户攒（违背"数据在手机"，且吃存储与配额）/ **手机本地攒（选定）**。

方案 A 的产品语义：

- App 在后台按节奏快照车辆状态；充电会话结束、行程结束后在**手机 Room** 内聚合成充电/行程记录。
- 新用户从登录之日起积累；历史数据**永不上传**。
- 数据精度诚实标注：快照粒度低于 TeslaMate 的高频采集，界面必须用与现有统计一致的证据标签（观测/派生/估算），缺失显示不可用，**不造假数据、不把未知显示为零**。
- 与既有 Room v16/v17 迁移体系、统计分析、TPMS 本地样本同路。

## 5. 执行计划（路线三落地，按依赖排序）

### 阶段 0：已完成基线（2026-08-28 当日达成）

| 项 | 状态 |
| --- | --- |
| ECS `120.55.64.11`（2C/1870MB/40G，Alibaba Cloud Linux 3，Docker 26.1.3） | ✓ |
| 域名 `joviluma.com` 备案通过（苏ICP备2026062639号-1），DNS 阿里云解析 | ✓ |
| 三子域 HTTPS（Let's Encrypt，自动续期）：`teslalink` / `api.teslalink` / `auth.teslalink` | ✓ |
| APK 下载页 `https://auth.teslalink.joviluma.com/download/` | ✓（APK 占位） |
| 安全边界：Token 门禁 401、4000/8080/5432/1883 公网不可达、star-photo 零影响 | ✓ |
| TeslaMate 五容器 stop（数据卷保留，单用户备用） | ✓ |
| sudo `/etc/sudoers.d/jourvolt`（NOPASSWD:ALL） | ✓ |

### 阶段 1：云端多租户就绪（进行中）

| # | 任务 | 负责 | 验收 |
| --- | --- | --- | --- |
| 1.1 | `docker-compose.pilot.ecs.yml` + `.env.ecs.example`（除 Tesla secret 外全部就绪） | 工程师 | compose config 通过 |
| 1.2 | Jovi 亲手填 `TESLA_CLIENT_ID` / `TESLA_CLIENT_SECRET`（指引：`docs/JOURVOLT-ECS-SECRET-SETUP.md`） | Jovi | preflight 仅剩 0 个失败项 |
| 1.3 | Go API 切 fleet：`/healthz` 返回 `"mode":"fleet"` | 工程师 | preflight 退出码 0 `PREFLIGHT=PASS` |
| 1.4 | APK 构建 + 签名（`build-pilot-apk.ps1 -ApiBaseUrl https://api.teslalink.joviluma.com/ -AuthHost auth.teslalink.joviluma.com`）+ 上架下载页 | 工程师 | `apksigner verify` 通过、下载页 curl 200、记录证书 SHA-256 |
| 1.5 | `assetlinks.json` 填入正式证书指纹；Tesla 3p 公钥发布（`--pubkey`） | 工程师 | verify-public.sh 全绿 |
| 1.6 | `/terms/` `/privacy/` 完善运营主体 + "行车数据仅存用户手机"条款 | 工程师 | Tesla 审核可提交状态 |

### 阶段 2：手机本地历史积累（方案 A，新功能）

| # | 任务 | 负责 |
| --- | --- | --- |
| 2.1 | 架构设计：快照采集 Worker 节奏、充电会话判定、行程判定、Room 迁移（v18+）、与现有 `SyncRepository` 的关系（同步路径保留，本地积累为云模式补充）、停止条件与电量边界 | 架构师 |
| 2.2 | 分批实现（TDD：先 RED 测试） | 工程师 |
| 2.3 | 全量门禁：Debug/Release JVM、lint、`MissingTranslation=0`、Release 静态扫描 | QA |
| 2.4 | 真机验证：充电会话与行程的本地生成、证据标签正确 | QA |

### 阶段 3：Pilot 验收与发布

| # | 任务 | 负责 |
| --- | --- | --- |
| 3.1 | Jovi 本人：下载页装 APK → Tesla 官方授权 → 看到自己车 → 刷新 → 退出 401 → 重登 | Jovi |
| 3.2 | Tesla 第三方应用审核提交（回调 `https://api.teslalink.joviluma.com/v1/auth/tesla/callback`，scope `openid`/`offline_access`/`vehicle_device_data`，隐私政策 URL） | Jovi |
| 3.3 | 审核通过后，其余 7 人经下载页分批安装、登录；观察配额与稳定性 | Jovi + 团队 |
| 3.4 | 运维闭环：age 加密备份、内存/证书告警、恢复演练 | 工程师 |

## 6. 硬约束（继承全部既有边界）

1. 行车数据（行程/充电/位置轨迹）**永不落服务器**；服务器只存加密凭据与会话。
2. Tesla `client_id`/`client_secret`、keystore、token 等秘密只写入服务器 Git 忽略的 `.env`（600），不进聊天、Git、Obsidian。
3. 不改 App 业务代码逻辑的既有边界照旧；方案 A 属于**新功能**，走正常设计→实现→QA 流程。
4. 证据分级：`LOCAL MOCK PASS` / `PHONE_SMOKE_PASS` / `REAL TESLA PILOT PASS` 不得混用；Tesla 审核未通过前，其他用户的端到端登录被外部阻塞，不得宣称"已开放"。
5. TeslaMate 五容器保持 stop；恢复仅用于 Jovi 个人自托管场景（`docker compose -f docker-compose.selfhost.yml start`），不进入多用户链路。
6. Git 不 stage/commit/push 除非单独授权。

## 7. 后续 agent 的行为指令

- **禁止**重新提出路线一/路线二，或建议"把 TeslaMate 搬上服务器服务多人"——已被本文档否决并说明理由。
- **禁止**把 `teslalink`（18080，自托管）与 `api.teslalink`（18090，云端）的端口/域名混淆；502 只说明五容器停用。
- 涉及历史数据的实现必须先读 §4，遵守"本地积累 + 诚实标签 + 不造假数据"。
- 云端模式下任何"服务器保存历史/轨迹"的提议都违反本文档，需 Jovi 明确推翻本决策后方可讨论。
