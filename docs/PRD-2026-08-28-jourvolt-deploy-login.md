# PRD：JourVolt / MateLink 部署与登录（2026-08-28）

| 项 | 值 |
| --- | --- |
| 文档版本 | v1（2026-08-28） |
| 语言 | 中文 |
| 目标仓库 | `E:\project\tesla_master\app_mimo` |
| 目标服务器 | 阿里云 ECS `120.55.64.11`（2 核 / 1870 MB / swap 2047 MB / 磁盘 40G 剩 27G / Alibaba Cloud Linux 3 / Docker 26.1.3） |
| 目标 App | `com.matelink`（MateLink），实体机 OnePlus 7 Pro `6e4fa92f` |
| 下游读者 | 架构师（部署架构设计）→ 工程师（落地） |
| 占位符约定 | `<自托管域名>` / `<API域名>` / `<AppLink域名>`；真实域名值待确认（见第 7 节 Q3） |
| 证据分级 | `LOCAL MOCK PASS` / `PHONE_SMOKE_PASS` / `REAL TESLA PILOT PASS`，不得混用 |

---

## 1. 产品目标

**一句话：让用户在自己手机上的 `com.matelink` 里，通过公网 HTTPS 连到自己的服务器，看到自己那辆真实特斯拉的车辆状态与既有历史数据，并且全程不碰用户的 Tesla 密码。**

三个正交目标：

| # | 目标 | 可判定口径 |
| --- | --- | --- |
| G1 | **自托管路径今天可用**：在 `120.55.64.11` 上跑通「TeslaMate 数据链路 + MateLink Adapter + 公网 HTTPS」，用户在实体机填入地址与 Token 后进入原 Dashboard，看到自己的车；手机上已有的 `190 行程 / 1600 km / 289 kWh / 31 充电 / 512 kWh` 不因本轮部署而丢失 | 实体机 `PHONE_SMOKE_PASS`：Settings 保存成功 → 同步成功 → Dashboard 显示真实车辆 + 既有历史条数不下降 |
| G2 | **云登录路径公网就绪**：域名、证书、App Link、assetlinks、Tesla 应用配置五项齐备，`preflight.sh` 由 `PREFLIGHT=FAIL` 转为 `PREFLIGHT=PASS`，`/healthz` 由 `mock_only` 变为 `fleet` | 服务器 `bash ./preflight.sh --env-file <私密.env> --verify-dns --verify-app-link` 退出码 `0` 且输出 `PREFLIGHT=PASS`；`curl https://<API域名>/healthz` 返回 `"mode":"fleet"` |
| G3 | **零密钥外泄、零业务代码改动、证据不越级**：所有秘密只存在于服务器 Git 忽略的 `.env`（权限 600）；本轮只改配置/脚本/部署物/构建参数；root 动作全部显式列出且不由 Agent 自动执行 | 全轮聊天、Git、日志、Obsidian 中零条 Tesla 密码 / `client_secret` / 私钥 / refresh token；`git status` 无未授权改动 |

---

## 2. 用户故事

主线：**我要用 App 登录并看到我自己的车。**

| # | 用户故事 | 验收要点（能不能算"做完"） |
| --- | --- | --- |
| US1 | 作为车主，我想在 App 的 Settings 里填入自己服务器的 HTTPS 地址和 API Token，就能连上我自己的自托管后端，这样我不必依赖任何第三方云服务 | 填入 `https://<自托管域名>` + Token → 保存 → 自动同步成功 → 底部四个一级导航均可切换 → Dashboard 显示我的车（车型/电量/最近快照时间），不是错误页 |
| US2 | 作为车主，我想在 App 里点「Use Tesla login」，用 Tesla 官方页面完成授权后自动回到 App 并看到我的车，这样我不必把 Tesla 密码交给任何第三方 App | 勾选协议后按钮可用 → 系统 Custom Tab 打开 Tesla 官方域名 → 授权后经 App Link 回到 App → 进入原 Dashboard 且显示真实车辆；App 进程内无任何 Tesla 密码字段 |
| US3 | 作为车主，我在 Dashboard 下拉刷新或等待自动轮询时，想看到车辆状态更新，这样我能知道车当前的真实情况 | 手动刷新返回成功且快照时间更新；轮询不产生重复登录；离线时显示可理解的错误而不是崩溃 |
| US4 | 作为车主，我想退出登录后能重新登录，并且旧凭据立即失效，这样我换手机或怀疑泄露时可以自己处置 | 自托管：清掉地址/Token 后所有接口不可达；云：退出后原 access 与 refresh 访问 `/v1/vehicles` 与 `/v1/session/refresh` 均返回 `401`，重新走 US2 可再次进入 |
| US5 | 作为车主，如果我的车在服务器上还没有历史数据，我想看到"正在采集/暂无记录"的真实空状态，而不是一堆假的 0 值 | 历史为空时分析页显示"采集中/不可用"，不出现 `0 km`、`0 kWh`、`0.00 元` 等假零值；已有真实记录时不被空状态覆盖 |
| US6 | 作为车主，当服务器不可达或证书过期时，我想看到明确的原因提示，而不是 App 卡住或白屏 | 断网/TLS 失败时有可读错误文案；服务恢复后无需重装即可重新同步 |
| US7 | 作为部署执行人（Jovi），我只想执行一次性的 root 动作（nginx/证书/安全组），之后日常运维用非 root 的 `jourvolt` 账户就能完成，这样不必每次都找 root | 所有 root 动作收敛在第 5 节清单，且总数 ≤ 6 条；其余动作 `jourvolt` 账户可独立完成 |

---

## 3. 范围与边界（两条路径）

### 3.1 两条路径的精确范围

| 维度 | 路径 A：自托管（P0，先跑通） | 路径 B：云登录（P1，并行推进） |
| --- | --- | --- |
| 交付物 | `deploy/teslamate-home-docker` 的裁剪版 Compose + 反向代理配置 | `deploy/jourvolt-dev-mock` 的 Pilot Compose（含 edge）+ 参数化 Release 构建 |
| 服务端组件 | PostgreSQL + TeslaMate(Elixir) + Mosquitto + TeslaMateApi + `matelink-adapter` | PostgreSQL + JourVolt Go API + edge（Caddy 或 nginx 承载静态与反代） |
| 宿主机端口 | Adapter `127.0.0.1:18080` | Go API `127.0.0.1:18090`（现状已被 staging 占用） |
| 对外 hostname | `https://<自托管域名>` | `https://<API域名>`（OAuth/API）+ `https://<AppLink域名>`（App Link/assetlinks） |
| App 侧入口 | Settings → 服务器根地址 + API Token → `ConnectionMode.SELF_HOSTED` → `triggerImmediateSync()` | 登录页 → `Use Tesla login` → Custom Tab → App Link ticket exchange → `ConnectionMode.TESLA_CLOUD` |
| App 侧改动 | **无**（纯用户配置，不重新构建 APK） | **仅构建参数**：`-ApiBaseUrl` / `-AuthHost` / `-PublicInfoBaseUrl` + 原 keystore 签名 |
| 谁对 Tesla 做授权 | TeslaMate Web UI（`:4000`，**仅 SSH 隧道访问一次，绝不开公网**） | JourVolt 服务端（官方 OAuth 授权码流程，App 不接触 token） |
| Tesla 凭据存放 | TeslaMate 数据库内（由 `TESLAMATE_ENCRYPTION_KEY` 加密） | JourVolt PostgreSQL 内（AES-GCM 加密 grant，`JOURVOLT_TOKEN_KEY_BASE64`） |
| 依赖的外部门禁 | 域名 A 记录、安全组 443、ICP 备案、Tesla 第三方应用 + 域名公钥托管（Fleet API 模式）、证书 | 域名 A 记录、安全组 443、ICP 备案、Tesla 应用审核通过且 scope 含三项、正式签名指纹 + `assetlinks.json`、证书 |
| 主要风险 | **1.87 GB 内存装不下全套**（见 3.3） | 外部门禁零项具备：今日 `api/auth.jourvolt.com` 实测 NXDOMAIN |

### 3.2 共享与隔离矩阵

| 资产 | 策略 | 说明与约束 |
| --- | --- | --- |
| 服务器 `120.55.64.11` | **共享** | 唯一服务器。两条路径共存时总内存必须留在预算内（3.3） |
| 主域名 | **共享** | 建议同一主域的三个子域：`<自托管域名>` / `<API域名>` / `<AppLink域名>`。`<API域名>` 与 `<AppLink域名>` 允许取同一值（preflight 允许），但推荐分开，避免 App Link 意图抢占整个 API 域名的 URL |
| TLS 终止 | **共享 nginx（方案 A）或 Caddy 容器（方案 B）** | 见 3.4。80 端口已被宿主机 nginx 独占，任何方案都不得抢 80 |
| 证书 | **共享/独立均可** | 统一 Let's Encrypt 免费 DV，按 hostname 分别签发；不买付费 SSL |
| Tesla 第三方应用（`client_id` / `client_secret`） | **可共享，也可分别注册** | 关键认知：**两条路径都需要 Tesla 第三方应用与域名公钥托管**，自托管不是绕开 Tesla 审核的捷径。是否共用一对凭据由 Q2 决定 |
| 域名公钥 `/.well-known/appspecific/com.tesla.3p.public-key.pem` | **共享** | 由 nginx 静态托管（或 Caddy `/srv`） |
| PostgreSQL | **待架构师决策：共用实例分库 vs 独立实例** | 共用省 60–100 MB 但两路径耦合、备份同卷；独立隔离但多吃内存。1.87 GB 下**倾向共用**，见 3.3 |
| Compose 项目名 | **隔离** | `jourvolt-staging`（现状）与 `matelink-selfhost`（新增）不得混用，避免 `docker compose down` 误伤 |
| 容器内存限额 | **隔离** | 每条路径独立 `mem_limit`，防止 TeslaMate 抢内存把 `star-photo` 拖进 OOM |
| 备份 | **逻辑隔离** | 两个库分别导出、同一 age 公钥加密、同一 `/srv/jourvolt-backups` 目录、文件名带库名 |
| `.env` | **隔离** | `/home/jourvolt/jourvolt-staging/.env` 与 `/home/jourvolt/matelink-selfhost/.env`，均 `600`、Git 忽略 |
| 日志 | **隔离** | 分别 `docker compose logs`，均禁止写入 VIN / token / 精确坐标 |
| 既有 `star-photo` 项目 | **不动** | 不重启、不改其 nginx 配置主体、不占用其域名 |

### 3.3 内存预算（硬约束 #1、#2、#4 的正面回答）

服务器只有 **1870 MB**，且 **swap 已用 392 MB**——说明内存已经处于压力状态，不是"还有余量"。

| 组件 | 估算常驻 | 处置 |
| --- | --- | --- |
| OS + page cache + 现有 star-photo | 待测（P0-1 必须实测） | 不动，`star-photo` 的峰值未知 |
| PostgreSQL（单实例，`shared_buffers=64MB`，`max_connections=30`） | 60–100 MB | 路径 A/B 共用则只算一次 |
| TeslaMate（Elixir/BEAM） | **250–450 MB** | 最大头；不装 Grafana 省下的内存全给它 |
| Mosquitto | 5–15 MB | 保留：TeslaMate 启动依赖 `MQTT_HOST`，且 Adapter 靠它取实时快照 |
| TeslaMateApi（Go） | 15–30 MB | 保留，但**绝不暴露端口** |
| `matelink-adapter`（Go） | 20–40 MB | 唯一对外组件 |
| JourVolt Go API | 20–40 MB | 路径 B |
| Caddy（若启用） | 20–40 MB | 方案 B 才需要 |

**路径 A 全套新增内存 ≈ 370–625 MB（不含 Caddy）。** 规则：

1. **准入门槛**：部署前 `free -m` 的 `available` 必须 **≥ 800 MB**；不满足则不允许启动全套。
2. **必裁项**：**不安装 Grafana**（仪表盘非 App 依赖，省 150–250 MB 与约 600 MB 镜像）。
3. **落地顺序**：先停 `jourvolt-staging` 的 api + postgres（路径 B 本轮不跑真机，代码已 ready，随时可重建），把内存让给路径 A；路径 B 联调时再反向切换，或两路径共用单个 PostgreSQL。
4. **强制限额**：所有新增容器显式 `mem_limit` 与 `memswap_limit`，把 OOM 限制在 JourVolt 侧，不得让 `star-photo` 被内核 OOM killer 选中。

### 3.4 TLS 终止方案（硬约束 #5、#6 的正面回答）

`0.0.0.0:80` 已被宿主机 nginx 占用（服务既有 `star-photo`），`443` 未监听，`caddy` 为 inactive，`jourvolt` 账户无 sudo。

| 方案 | 做法 | 是否需要 root | 评价 |
| --- | --- | --- | --- |
| **A（推荐）** | 由 **Jovi（root）** 在 `/etc/nginx/conf.d/` 新增独立 `jourvolt.conf`：`listen 443 ssl` 的三个 server 块，静态托管 `/.well-known/assetlinks.json`、`/terms/`、`/privacy/`、`/.well-known/appspecific/com.tesla.3p.public-key.pem`，其余反代到 `127.0.0.1:18080`（adapter）/ `127.0.0.1:18090`（Go API）；证书用 `certbot --nginx -d <域名>` 一次签发，续期由 certbot timer 自动完成 | **需要（一次性）** | 不抢 80、不动 `star-photo` 主体配置、续期全自动，后续 `jourvolt` 零 root 依赖。**注意**：`certbot --nginx` 会改写 nginx 配置，须显式只指定新域名，避免影响 `star-photo.conf` |
| **B（无 sudo 兜底）** | Caddy 容器以 `443:443` 发布（dockerd 以 root 身份绑定特权端口，`jourvolt` 只需 docker 组权限），ACME 走 **TLS-ALPN-01**；80 端口继续由 nginx 独占；沿用仓库现有 `Caddyfile.example` 与 `/srv` 静态结构 | 否 | 规避 root，但依赖"Caddy 在 80 不可达时自动回落 TLS-ALPN-01"的实测结果；且后续 nginx 若也要 443 会冲突 |
| **C（不推荐）** | 仅 SSH 隧道 / 局域网 HTTP 验证 | 否 | 手机不在服务器局域网内，**不算公网可用**，只能作为部署中途的自检手段 |

**Go API 不提供** `assetlinks.json` 与法律页（由 edge 静态提供），因此采用方案 A 时 nginx 必须自己托管这几类静态路径——这是最容易漏掉的一步。

### 3.5 待架构师拍板的两个取舍（PRD 不代替决策）

| 取舍 | 选项 | PRD 倾向 |
| --- | --- | --- |
| **T1：自托管数据从哪来** | ① 复用用户现有 TeslaMate 实例（ECS 只跑 adapter，直连远端库）② 迁移历史到 ECS，跑裁剪版全套 ③ ECS 全新冷启动，历史靠手机 Room 已有数据 | **先 ③ 再 ②**：③ 今天就能让用户看到车和历史（历史已在手机 Room 里），② 在 P1 补上以保证重装不丢；① 只有在现有实例稳定在线且数据库能安全打通时才选，**不默认推荐把家用 PostgreSQL 暴露到公网** |
| **T2：PostgreSQL 共用还是独立** | 共用实例分库 / 两实例并存 | **共用**：1.87 GB 下两个 PostgreSQL 是纯粹的浪费；代价是备份与故障域耦合，需在备份脚本里按库分别导出 |

---

## 4. 需求池

### 4.1 P0 —— 自托管今天可用（12 条）

| # | 需求 | 验收标准（可判定） |
| --- | --- | --- |
| P0-1 | **容量盘点**：实测并记录 `free -m`、`docker stats --no-stream`（含 `star-photo` 两个容器）、`df -h`、`swapon -s`、`ss -lntp` | 产出一份快照数据；`available` 数值明确；若 < 800 MB 立即触发 T1/T2 降级方案，不得硬上 |
| P0-2 | **裁剪版自托管 Compose**：`postgres + teslamate + mosquitto + teslamateapi + matelink-adapter`，**移除 grafana**；所有容器显式 `mem_limit`；`restart: unless-stopped` | `docker compose config --quiet` 通过；`docker compose ps` 五个容器全部 `Up`/`healthy`；`docker stats` 实测总内存 ≤ 700 MB |
| P0-3 | **端口暴露面收窄**：仅 `matelink-adapter` 绑定 `127.0.0.1:18080`；`teslamate:4000`、`teslamateapi:8080`、`postgres:5432`、`mosquitto:1883` 一律**不发布宿主机端口** | `ss -lntp` 中 4000/8080/5432/1883 均无监听；`curl http://127.0.0.1:18080/api/matelink/v1/capabilities` 不带 token 返回 `401 invalid API token` |
| P0-4 | **Token 强度**：`MATE_LINK_API_TOKEN` 为 ≥ 32 字节随机值，仅存于服务器 `.env`（`600`），不进 Git/聊天/日志 | `.env` 权限 `600`；`git check-ignore` 命中；带正确 `Authorization: Bearer` 返回 200，错一个字符返回 401 |
| P0-5 | **DNS**：`<自托管域名>` A 记录 → `120.55.64.11`，公网可解析（**禁止用本机代理的 fake-IP 结果当证据**） | 从非本机网络（手机 4G 或公共 DNS）`nslookup <自托管域名>` 返回 `120.55.64.11`；`198.18.x.x` 视为失败 |
| P0-6 | **安全组 / 防火墙**：放行 443；**不放行** 4000/8080/5432/1883/18080/18090 | 公网 `nc -zv 120.55.64.11 4000` 超时；`nc -zv 120.55.64.11 443` 通 |
| P0-7 | **ICP 备案风险确认**：确认 ECS 与域名的备案状态；未备案时 80/443 可能被云厂商拦截 | Q4 有明确答案；若未备案，P0-8 之前先给出绕行结论（见第 7 节 Q4 的默认假设） |
| P0-8 | **TLS 与反代**：按 3.4 方案 A 或 B 落地；`<自托管域名>` 全路径反代到 `127.0.0.1:18080`；nginx 层加 `limit_req` 限流 | `curl -i https://<自托管域名>/api/matelink/v1/capabilities -H "Authorization: Bearer <token>"` 返回 `200`；`openssl s_client` 显示 Let's Encrypt 签发、剩余有效期 > 60 天；HTTP→HTTPS 重定向生效 |
| P0-9 | **TeslaMate 首次 Tesla 授权**：经 SSH 隧道访问 `127.0.0.1:4000` 完成一次 Tesla 登录与车辆授权，随后不再开放 | TeslaMate 日志出现车辆在线并开始写入；`docker compose exec -T database psql -U teslamate -c 'select count(*) from cars;'` ≥ 1 |
| P0-10 | **数据链路打通**：Adapter 能从数据库读到车辆与历史 | `curl -s https://<自托管域名>/api/v1/cars -H "Authorization: Bearer <token>"` 返回至少 1 辆车；`/api/matelink/v1/cars/{id}/snapshot` 返回 200 且字段非空 |
| P0-11 | **App 真机验收**：实体机 `6e4fa92f` 上 `com.matelink` 填入 `https://<自托管域名>` + Token，保存并同步 | **PHONE_SMOKE_PASS**：Settings 保存无 `settings_public_http_unsafe` 报错 → 自动同步成功 → Dashboard 显示真实车辆 → 底部四导航可切换 → 统计页既有历史条数（190 行程 / 31 充电）**不下降**；截图留档 |
| P0-12 | **密钥与文件治理**：服务器 `.env`、备份目录权限收口；本轮不产生任何密钥外传 | `/home/jourvolt/**/.env` 权限 `600` 且属主 `jourvolt`；全轮聊天/Git/日志中零条 Tesla 密码、`client_secret`、私钥、refresh token、完整 VIN |

### 4.2 P1 —— 云登录公网就绪（10 条）

| # | 需求 | 验收标准（可判定） |
| --- | --- | --- |
| P1-1 | **DNS**：`<API域名>` 与 `<AppLink域名>` A 记录 → `120.55.64.11`，公网可解析 | 同 P0-5 口径，两个域名均通过；`198.18.x.x` 视为失败 |
| P1-2 | **Tesla 应用事实确认**：区域（`tesla.com` / `tesla.cn`）、审核状态、已批准 scope 是否含 `openid` `offline_access` `vehicle_device_data`、已登记的 `redirect_uri` | Q5 有明确答案；`TESLA_REDIRECT_URI` 与 Tesla 控制台登记值逐字符一致 |
| P1-3 | **正式签名与 App Link**：取得正式 `com.matelink` 签名证书 SHA-256，用 `write-assetlinks.sh` 生成并发布 `/.well-known/assetlinks.json` | `curl https://<AppLink域名>/.well-known/assetlinks.json` 返回含 `com.matelink` 与 32 段冒号分隔指纹的 JSON（**是证书指纹，不是 APK 文件哈希**） |
| P1-4 | **edge 就绪**：`<API域名>` / `<AppLink域名>` 的 443 反代到 `127.0.0.1:18090`；静态托管 `assetlinks.json`、`/terms/`、`/privacy/`、Tesla 公钥 | 四个静态 URL 公网 `200`；`https://<API域名>/healthz` 公网 `200` |
| P1-5 | **服务端私密配置**：填写 `DATABASE_URL`、`POSTGRES_PASSWORD`、`TESLA_CLIENT_ID`、`TESLA_CLIENT_SECRET`、`TESLA_REDIRECT_URI`、`JOURVOLT_APP_LINK_URI`、`JOURVOLT_TOKEN_KEY_BASE64`（32 字节）、`JOURVOLT_ACME_EMAIL`；`JOURVOLT_ENABLE_MOCK=false` | `bash ./preflight.sh --env-file <私密.env> --verify-dns --verify-app-link` **退出码 0 且输出 `PREFLIGHT=PASS`**（现状为 `PREFLIGHT=FAIL`）；不打印任何密钥值 |
| P1-6 | **模式切换**：Go API 由 `mock_only` 切到 `fleet` | `curl https://<API域名>/healthz` 返回 `{"mock_history":false,"mode":"fleet","persistence":"postgres","status":"ok"}`；`/readyz` 返回 200 |
| P1-7 | **法律页发布**：`/terms/`、`/privacy/` 补齐运营主体与联系渠道并公网可访问 | 两个页面公网 `200` 且含 `<html`；`preflight -VerifyAppLink` 不再报法律页缺失 |
| P1-8 | **参数化 Release 构建**：`build-pilot-apk.ps1 -ApiBaseUrl https://<API域名>/ -AuthHost <AppLink域名> -PublicInfoBaseUrl https://<公开条款隐私域名>/ -SigningPropertiesPath <仓库外私密properties>` | 输出包名 `com.matelink`；`apksigner verify` 通过；指纹与 P1-3 所用证书一致；Release 静态扫描未命中 Mock / 回环地址 / Debug 标记 |
| P1-9 | **真机端到端云登录**：OAuth → 车辆列表 → 刷新 → 退出 → 重登 | **REAL TESLA PILOT PASS**：Custom Tab 打开 Tesla 官方域名 → 授权后 App Link 回 App → Dashboard 显示真实车辆 → 刷新成功 → 退出后旧 access/refresh 均 `401` → 可重新登录。未取得该证据前一律标 `LOCAL MOCK PASS` 或 `PHONE_SMOKE_PASS` |
| P1-10 | **历史迁移**：把用户现有 TeslaMate 历史迁移到 ECS（P1，配合 T1②） | ECS 侧 `/api/v1/cars/{id}/drives` 条数与源库一致；手机清空 App 数据后重新同步仍能恢复历史（**需单独授权，不得在本轮执行清数据**） |

### 4.3 P2 —— 运维长期项（8 条）

| # | 需求 | 验收标准（可判定） |
| --- | --- | --- |
| P2-1 | 加密备份 + 异地上传 + 定时（age 加密 + rclone 到另一家境内对象存储 + systemd timer） | 手工执行一次 service 成功；`--require-upload` 在无 remote 时失败关闭；备份文件权限 `600` |
| P2-2 | 恢复演练 | 在**隔离**数据库完成一次真实 restore，校验条数后销毁；生产库不被动覆盖 |
| P2-3 | 监控与告警（内存、磁盘、容器健康、证书剩余天数） | 任一阈值触发时有可观测告警；内存 > 85% 时告警先于 OOM 到达 |
| P2-4 | 证书自动续期验证 | 续期后 `openssl s_client` 显示新有效期；续期失败可告警 |
| P2-5 | ICP 备案 + App 备案 | 备案号可查；公网 443 稳定可达 |
| P2-6 | 扩容方案（2C4G 及以上）与迁移步骤 | 出一份可执行的扩容 runbook；不自动下单 |
| P2-7 | 日志脱敏审计 | 全量检索日志无 VIN / token / 精确坐标 / 授权 code |
| P2-8 | 安全加固（fail2ban、安全组最小化、仅 SSH key 登录） | 端口扫描仅 443（与 SSH）开放；密码登录关闭 |

---

## 5. 需 Jovi 执行的 root 动作清单（不得由 Agent 自动执行）

| # | 动作 | 为什么必须 root | 替代方案（若不给 sudo） |
| --- | --- | --- | --- |
| R1 | 在 `/etc/nginx/conf.d/` 新增 `jourvolt.conf`（443 server 块 + 静态路径 + 反代），并 `nginx -t && systemctl reload nginx` | 443 属特权端口，nginx 配置属 root 所有 | 走 3.4 方案 B：Caddy 容器 `443:443` + TLS-ALPN-01，`jourvolt` 只需 docker 组权限 |
| R2 | 安装 `certbot` 并首次签发证书（`certbot --nginx -d <域名>`，显式只指定新域名） | 写 `/etc/letsencrypt`、改 nginx 配置 | 同上；或 Jovi 手工签发后把证书路径交给 nginx |
| R3 | 确认证书自动续期（certbot timer / cron）生效 | 写 systemd timer | 方案 B 下由 Caddy 自行续期，无需 root |
| R4 | 安装 `age` 与 `rclone`、创建 `/srv/jourvolt-backups`（`0750`）与 `/etc/jourvolt`（`0750`，属主 root、组 jourvolt） | 系统目录与包管理 | 备份降级为 jourvolt 家目录下手动执行；异地上传推到 P2 |
| R5 | 安装并启用 systemd 备份 service/timer | 写 `/etc/systemd/system` | 用 `jourvolt` 的 user-level systemd（`loginctl enable-linger`）或外部定时触发 |
| R6 | 阿里云控制台放行安全组 443，并确认 4000/8080/5432/1883 未放行 | 云厂商控制台权限（非服务器 sudo） | 无替代，必须由 Jovi 在控制台执行 |

> 说明：R1–R3 是一次性动作，完成后日常运维（拉镜像、起停容器、看日志、改 `.env`）`jourvolt` 账户即可独立完成。若 Jovi 不愿或无法执行 R1–R3，则整体切换 3.4 方案 B，并把 R4/R5 降级到 P2。

---

## 6. 里程碑与验收

| 里程碑 | 内容 | 验收命令 / 可观测结果 | 证据等级 |
| --- | --- | --- | --- |
| **M0 决策与盘点** | 架构师拍板 T1/T2 与 TLS 方案；Jovi 回答第 7 节 Q1–Q4、Q8–Q11 | 本文档第 7 节表格"答案"列全部非空；`free -m` 快照已记录 | — |
| **M1 自托管服务在服务器可用** | P0-1 ~ P0-4、P0-9、P0-10 | `docker compose ps` 五容器 healthy；`docker stats` 总量 ≤ 700 MB；`curl http://127.0.0.1:18080/api/v1/cars -H "Authorization: Bearer <token>"` 返回真实车辆；`psql` 查到 `cars` ≥ 1 且 `drives` 有数据 | 服务端自测 |
| **M2 自托管公网可用（本轮主交付）** | P0-5 ~ P0-8、P0-11、P0-12 | 公网 `curl -i https://<自托管域名>/api/v1/cars -H "Authorization: Bearer <token>"` 返回 200；`openssl s_client` 显示 Let's Encrypt 证书；**实体机填入地址与 Token 后进入 Dashboard 并显示真实车辆，既有 190 行程 / 31 充电不下降** | `PHONE_SMOKE_PASS` |
| **M3 云登录公网就绪** | P1-1 ~ P1-8 | `preflight.sh --verify-dns --verify-app-link` 输出 `PREFLIGHT=PASS`；公网 `https://<API域名>/healthz` 返回 `"mode":"fleet"`；`assetlinks.json` 公网可拉取且指纹正确；参数化 Release 构建通过 `apksigner verify` | 公网配置就绪（**仍不等于登录通过**） |
| **M4 云登录真机验收** | P1-9 | 实体机完成 Tesla 官方授权→回 App→车辆→刷新→退出 401→重登全链路 | `REAL TESLA PILOT PASS` |
| **M5 运维闭环** | P1-10、P2 全部 | 备份+异地上传+恢复演练通过；监控告警可观测；备案与扩容 runbook 就位 | 运维验收 |

---

## 7. 待确认问题清单（11 条）

| # | 问题 | 为什么需要 | 谁来答 | 阻塞哪条路径 | 无答案时的默认假设 |
| --- | --- | --- | --- | --- | --- |
| **Q1** | **自托管是否复用用户已有 TeslaMate 实例？现有实例在哪台机器、是否还在线、PostgreSQL 版本与数据量、能否导出 dump？** | 决定 T1；用户手机上 190 行程/1600 km 的源头就在这台机器上，不知其位置就无法保证历史可迁移、也无法判断能否只跑 adapter | Jovi | **路径 A（P0 全部）+ P1-10** | 默认走 T1③：ECS 冷启动，历史暂依赖手机 Room 已有数据 |
| **Q2** | **现有 TeslaMate 用的是哪种 Tesla 授权（Fleet API 第三方应用 / 旧 owner token）？Tesla 开发者应用注册在 tesla.com 还是 tesla.cn？是否另有第二组凭据可供云路径使用？** | 两条路径**都**依赖 Tesla 第三方应用与域名公钥托管；区域决定 `TESLA_AUTH_URL`/`FLEET_API_BASE` 默认值；也决定能否共用一对凭据 | Jovi（查 Tesla 开发者控制台） | **路径 A（P0-9）+ 路径 B（P1-2/P1-5）** | 默认两条路径共用同一对已具备的凭据；区域默认 `tesla.cn`（代码默认值） |
| **Q3** | **域名具体值？`<自托管域名>` / `<API域名>` / `<AppLink域名>` 是否同一主域？DNS 由谁改、在哪个平台改（阿里云解析 / 其他注册商）？** | 没有 A 记录就没有证书，也没有 App 可填的地址；DNS 归属决定谁能执行 P0-5/P1-1 | Jovi | **两条路径（P0-5 / P1-1）** | 保持占位符，不臆造域名；不自动改 DNS |
| **Q4** | **ECS 是否已 ICP 备案？域名是否已在阿里云完成备案接入？安全组 443 是否放行？** | 未备案域名指向中国大陆服务器时，80/443 访问可能被云厂商拦截，会导致 P0-8/P1-4 即使配置正确也"公网不通" | Jovi（云控制台） | **两条路径（P0-6/P0-7/P0-8 / P1-4）** | 默认**未备案**，先按"可能被拦"预案准备；若实测被拦，则本轮只交付 M1（服务端自测）+ SSH 隧道验证，不宣称公网可用 |
| **Q5** | **Tesla 开发者应用是否已审核通过？已批准 scope 是否含 `openid` / `offline_access` / `vehicle_device_data`？允许的回调地址是否已填为 `https://<API域名>/v1/auth/tesla/callback`？** | 服务端 `loadTeslaConfig` 强制回调路径为 `/v1/auth/tesla/callback` 且必须 HTTPS 无端口；不一致则授权后被拒 | Jovi（Tesla 控制台） | **路径 B（P1-2/P1-5/P1-9）** | 默认尚未通过；路径 B 停在 M3 之前，不进入真机验收 |
| **Q6** | **Tesla 公钥 `/.well-known/appspecific/com.tesla.3p.public-key.pem` 能否由 nginx 静态托管？TeslaMate 版本是否强制要求该公钥？** | 80 端口被 nginx 占用，公钥必须由现有 443 入口或 Caddy 提供；这决定路径 A 能否完成 Fleet API 授权 | Jovi + 架构师 | **路径 A（P0-9）** | 默认由 nginx 托管；若 TeslaMate 版本不要求，则该步跳过（需实测确认） |
| **Q7** | **正式签名 keystore 与证书 SHA-256 指纹来源？`assetlinks.json` 用哪张证书？当前手机上是与本机一致的临时证书还是官方发布证书？** | App Link 校验依赖证书指纹；指纹错则回调无法回到 App，云登录直接失败 | Jovi / 签名持有人 | **路径 B（P1-3/P1-8/P1-9）** | 默认沿用与手机现有包一致的证书（现状可用），但**明确记录它不是官方发布 keystore**，不作为长期方案 |
| **Q8** | **`jourvolt` 账户是否需要 sudo？能否给 sudo，或由 Jovi 亲自执行第 5 节 R1–R5？** | 直接决定 TLS 走方案 A 还是方案 B，进而决定 nginx 配置、证书路径与后续所有运维方式 | Jovi | **两条路径（P0-8 / P1-4）** | 默认**不给 sudo**，按方案 A 由 Jovi 一次性执行；若 Jovi 拒绝执行，则整体切方案 B |
| **Q9** | **`star-photo` 两个容器的内存占用上限是多少？能否在其高峰时让出内存？** | 1.87 GB 下，路径 A 的准入判断依赖这个数 | Jovi | **路径 A（P0-1/P0-2）** | 默认 star-photo 峰值不可让，路径 A 预算上限按 700 MB 硬限执行 |
| **Q10** | **手机上现有 190 行程 / 31 充电 数据是否允许保留在 Room（不重装 App）？是否接受"服务器侧历史从今天开始采集"？** | 决定 M2 的验收口径：是"历史条数不下降"还是"历史从零开始" | Jovi | **路径 A（P0-11）+ P1-10** | 默认**不重装、不清数据**，验收口径为"历史条数不下降" |
| **Q11** | **是否接受把 `matelink-adapter` 直接暴露公网（Token + TLS + 限流）？还是坚持 VPN / Tailscale？** | 决定 M2 是否算完成；也决定是否需要额外的隧道组件（会再吃内存） | Jovi | **路径 A（P0-8/P0-11）** | 默认接受"Token + TLS + 限流"；`teslamateapi:8080` 绝不暴露；不引入 Tailscale（避免多占内存与新增依赖） |

**最阻塞的三条**：**Q1**（不知道现有 TeslaMate 在哪，历史与架构都定不下来）→ **Q3/Q4**（没有域名 A 记录与备案确认，两条路径都无法公网可达）→ **Q8**（不给 root 就得整个换 TLS 方案）。

---

## 8. 明确不做

| 不做的事 | 原因 |
| --- | --- |
| 不收集、不记录、不传输 Tesla 账号密码、`client_secret`、应用私钥、refresh token、完整 VIN | 隐私与安全的硬边界；这些只写入服务器 Git 忽略的私密 `.env` |
| 不修改 App 业务代码逻辑 | 本轮只允许配置、脚本、部署物与构建参数；确需改代码必须单列并说明理由 |
| 不自动购买服务器/域名/证书，不自动改 DNS，不自动启动公网服务 | 需用户操作的外部动作全部显式列出（第 5 节与第 7 节） |
| 不 stage / commit / push | 除非 Jovi 单独授权（现状已有 81 项未提交改动，与本轮无关） |
| 不安装 Grafana | 1870 MB 内存装不下；App 不依赖它 |
| 不把 `teslamate:4000` / `teslamateapi:8080` / `postgres:5432` / `mosquitto:1883` 暴露到公网 | TeslaMateApi 无 token 也会响应，一旦暴露等于把车辆数据公开 |
| 不启用 Tesla Telemetry、位置/路线能力、远程车控、哨兵视频 | 需要单独的 scope、位置合规服务与授权；不在本轮范围 |
| 不限量开放多用户公测 | 单服务器容量与备案都不支持 |
| 不买付费 SSL、不使用付费证书 | 统一 Let's Encrypt 免费 DV |
| 不使用 lint baseline、假数据或"已部署"文字冒充证据 | 证据分级必须使用 `LOCAL MOCK PASS` / `PHONE_SMOKE_PASS` / `REAL TESLA PILOT PASS`，不得越级 |
| 不在生产启用 Mock（`JOURVOLT_ENABLE_MOCK` 必须显式 `false`） | preflight 已强制；Mock 只属于 Debug 测试包 |
| 不在本轮清空手机 App 数据或卸载重装 | 手机上已有真实历史，清数据不可逆；P1-10 的迁移验证需单独授权 |

---

## 9. 硬约束

1. **密钥边界**：绝不收集/记录/传输 Tesla 账号密码、`TESLA_CLIENT_SECRET`、应用私钥、refresh token；这些只写入服务器 Git 忽略的私密 `.env`（权限 `600`）；PRD 全文只用占位符与键名。
2. **不自动执行外部动作**：不自动购买服务器/域名/证书、不自动改 DNS、不自动启动公网服务；需用户操作的动作集中在第 5 节（root）与第 7 节（确认项）。
3. **证书策略**：只用 Let's Encrypt 免费 DV，不买付费 SSL；证书私钥只留在服务器，不进 Git。
4. **Git 边界**：不 stage / commit / push，除非 Jovi 单独授权。
5. **代码边界**：不改 App 业务逻辑代码；本轮只允许配置、脚本、部署物与构建参数。如确需改代码，必须单列条目并说明理由后重新评审。
6. **证据分级**：`LOCAL MOCK PASS`（本机 Docker/Mock）／`PHONE_SMOKE_PASS`（实体机 UI，非真实 Tesla OAuth）／`REAL TESLA PILOT PASS`（真实 Tesla 授权 + 真实车辆）。三者不得混用、不得越级陈述。
7. **既有项目不可伤**：不重启、不删除、不改写 `star-photo` 的容器与其 nginx 主体配置；新增配置使用独立的 `conf.d` 文件与独立 Compose 项目名。
8. **内存纪律**：所有新增容器显式内存限额；部署前必须完成 P0-1 容量盘点；`available < 800 MB` 时不得启动路径 A 全套。

---

## 10. 给架构师的第一批决策（摘要）

1. **T1 数据来源**（Q1）：现有 TeslaMate 实例在哪、能否迁移 —— 决定路径 A 是"只跑 adapter"、"迁移历史"还是"冷启动"。
2. **T2 数据库拓扑 + TLS 方案**（Q8）：PostgreSQL 共用还是独立、走 nginx（需 Jovi root）还是 Caddy 容器（TLS-ALPN-01）—— 这两个决定一旦定下，P0 的八个条目可以并行开工。

其余问题（Q3/Q4 域名与备案、Q5/Q7 Tesla 与签名）阻塞的是 P1，不阻塞 P0 开工。
