# ARCH：JourVolt / MateLink 部署架构设计与任务分解（2026-08-28）

| 项 | 值 |
| --- | --- |
| 文档版本 | v1（2026-08-28） |
| 作者 | 高见远（架构师） |
| 上游输入 | `docs/PRD-2026-08-28-jourvolt-deploy-login.md`、`docs/JOURVOLT-REAL-PILOT-PREFLIGHT.md`、`docs/PROGRESS-GAP-REVIEW-2026-08-28.md`；主理人实测服务器现状；Jovi 已答 Q1/Q3/Q4/Q8 |
| 下游读者 | 工程师（寇豆码，落地 Compose / nginx / 脚本 / 构建参数）→ QA（严过关，验收） |
| 目标服务器 | 阿里云 ECS `120.55.64.11`，`ecs.c1m1.large`，2 vCPU / 2 GiB（可见 1870 MB）/ swap 2047 MB（已用 392 MB）/ 40 G 系统盘（剩 27 G）/ **1 Mbps 固定带宽** / Alibaba Cloud Linux 3 (OpenAnolis) / Docker 26.1.3 |
| 部署账户 | `jourvolt`（非 root，**Jovi 已确认会给 sudo**），SSH 密钥 `~/.ssh/joviluma_jourvolt_deploy_ed25519`，部署根 `/home/jourvolt/` |
| 域名（DNS 在阿里云解析控制台，A 记录均已指向 `120.55.64.11`） | 主域 `joviluma.com`；`photo.joviluma.com`（**既有 star-photo，占用 80**）；`teslalink.joviluma.com`（自托管 Adapter）；`api.teslalink.joviluma.com`（Go API / Tesla OAuth 回调）；`auth.teslalink.joviluma.com`（App Link / assetlinks） |
| 占位符约定 | 本文只出现**键名**与占位符；真实密钥值一律写 `<…>`，不出现任何 secret 明文 |
| 证据分级 | `LOCAL MOCK PASS` / `PHONE_SMOKE_PASS` / `REAL TESLA PILOT PASS`，不得混用、不得越级 |

---

## 0. 结论速览（先给答案）

| 决策 | 结论 | 一句话理由 |
| --- | --- | --- |
| **T1 数据来源** | **② 迁移（主）+ ③ 冷启动（Plan B）**。① 复用远端实例**否决** | 迁移不只保住历史，还让 ECS 上的 TeslaMate **继承库里已加密的 Tesla token**，从而绕开"重新授权必须公网 443 托管 Tesla 公钥"的备案阻塞；冷启动只能等备案 |
| **T2 数据库拓扑** | **共用实例分库**（`postgres` 单容器，两库 `teslamate` + `jourvolt`） | 1870 MB 下第二实例多花 60–100 MB 且 page cache 不能共享，直接吃掉 700 MB 预算的 15% |
| **T2 TLS 方案** | **方案 A，但签发方式收紧为 A′（certbot `certonly --webroot` + nginx 手工引用证书）** | `certbot --nginx` 会改写 nginx 配置；`--webroot` 完全不碰 `star-photo.conf`，风险归零 |
| **Mosquitto 存废** | **保留**（`mem_limit 40m`） | 实测：Adapter 对 MQTT 是**可选**依赖（`MQTT_ENABLED=false` 即降级到 PostgreSQL），但 TeslaMate 侧 `MQTT_HOST` 默认必填；砍掉只省约 8 MB RSS，却丢失 `locked`/`plugged_in`/`charging_details` 等 App 已实现的"状态化"字段 |
| **Grafana** | **移除** | App 不依赖，省 150–250 MB 与约 600 MB 镜像下载（1 Mbps 下是关键） |
| **是否改动 `deploy/teslamate-home-docker/docker-compose.yml`** | **不改** | 该文件是 Jovi 自己电脑上**正在运行**的模板，改它会污染源端。ECS 裁剪版另起 `docker-compose.selfhost.yml` |
| **新增容器内存天花板** | **700 MB**（`mem_limit` 之和，正好用满），实测 RSS 目标 ≤ 480 MB | 见第 4.2 节逐项分配 |
| **P0 分两阶段** | **P0-a（今晚可完成可验收，不依赖域名/备案）** / **P0-b（依赖备案接入 + 安全组 443）** | 备案未通过前，P0-b 只能出配置与 dry-run，禁止声称完成 |

---

## 1. 现状事实（设计输入，不再验证）

| 类别 | 事实 |
| --- | --- |
| 已在跑（不可伤） | `star-photo-star-weather-1`（`127.0.0.1:3100->3000`）、`star-photo-star-weather-worker-1` |
| 已在跑（JourVolt 侧） | `jourvolt-staging-jourvolt-dev-api-1`（`127.0.0.1:18090->8080`，healthy）、`jourvolt-staging-jourvolt-postgres-1`（`postgres:16-alpine`，healthy，无显式内存限额） |
| 现有 API 证据 | `curl http://127.0.0.1:18090/healthz` → `{"mock_history":true,"mode":"mock_only","persistence":"postgres","status":"ok"}` |
| 端口占用 | `0.0.0.0:80` = 宿主机 **nginx（root 进程）**，配置 `/etc/nginx/conf.d/star-photo.conf`；**443 未监听**；caddy inactive；22 已开放 |
| 现有项目名 / 目录 | `jourvolt-staging`，`/home/jourvolt/jourvolt-staging` |
| 内存压力 | 总 1870 MB，swap 已用 392 MB → **已处于压力状态，不是"还有余量"** |
| Compose 项目名（本轮新增） | `matelink-selfhost`（P0）。**必须与 `jourvolt-staging` 隔离**，防止 `docker compose down` 误伤 |
| 手机硬资产 | `com.matelink` Room 内 190 行程 / 1600 km / 289 kWh / 31 充电 / 512 kWh。**本轮不重装、不清数据、不换签名** |

---

## 2. 部署拓扑

### 2.1 拓扑图

```mermaid
flowchart TB
    PHONE["MateLink App<br/>com.matelink · OnePlus 7 Pro 6e4fa92f"]

    subgraph ECS["阿里云 ECS 120.55.64.11 · 2C / 1870MB / 1Mbps"]
        subgraph HOSTNS["宿主机 root 命名空间"]
            NGINX["nginx（root 进程）<br/>:80 由 star-photo 既有 server 独占<br/>:443 本轮新增 server 块<br/>conf.d/jourvolt.conf"]
            SP["star-photo-web<br/>127.0.0.1:3100 to 3000<br/>【既有 · 不动】"]
            SPW["star-photo-worker<br/>【既有 · 不动】"]
        end

        subgraph SELF["Compose 项目 matelink-selfhost（P0）"]
            ADP["matelink-adapter :8080<br/>发布 127.0.0.1:18080<br/>mem 76m · 唯一对外组件"]
            TMA["teslamateapi :8080<br/>不发布端口 · mem 64m"]
            TM["teslamate :4000<br/>不发布端口 · mem 288m"]
            MQ["mosquitto :1883<br/>不发布端口 · mem 40m"]
            PG[("postgres :5432<br/>库 teslamate + jourvolt<br/>不发布端口 · mem 232m")]
        end

        subgraph STG["Compose 项目 jourvolt-staging（P1）"]
            API["jourvolt-dev-api :8080<br/>发布 127.0.0.1:18090<br/>mem 96m"]
        end
    end

    SRV["/srv/jourvolt/public<br/>root:jourvolt 2775<br/>assetlinks / terms / privacy / Tesla 3p 公钥"]
    TESLA["Tesla 官方<br/>auth.tesla.cn · fleet-api"]

    PHONE -->|"HTTPS 443<br/>teslalink.joviluma.com"| NGINX
    PHONE -->|"HTTPS 443<br/>api / auth.teslalink.joviluma.com"| NGINX
    NGINX -->|"http 127.0.0.1:18080"| ADP
    NGINX -->|"http 127.0.0.1:18090"| API
    NGINX -.->|"静态直出，不经后端"| SRV
    NGINX -->|"conf.d/star-photo.conf<br/>photo.joviluma.com :80"| SP
    SP --- SPW

    ADP -->|"/api/* 反代（项目内网）"| TMA
    ADP -->|"SQL 直读 positions/cars/drives"| PG
    ADP -.->|"MQTT 订阅 teslamate/cars/#<br/>可选叠加，失败自动降级"| MQ
    TMA --> PG
    TM -->|"写入 positions/drives/charges"| PG
    TM -->|"发布实时状态"| MQ
    TM -->|"轮询车辆"| TESLA
    API --> PG
```

### 2.2 链路说明

| 链路 | 协议与边界 |
| --- | --- |
| 手机 → nginx | 公网 HTTPS/443。TLS 在**宿主机 nginx**终止，证书由 certbot 签发（`--webroot`，不触碰 `star-photo.conf`） |
| nginx → Adapter | 明文 HTTP 到 `127.0.0.1:18080`。同一宿主机回环，不跨网络，可接受 |
| nginx → Go API (P1) | 明文 HTTP 到 `127.0.0.1:18090` |
| nginx → 静态 | 直读 `/srv/jourvolt/public`，**不经过任何后端**（assetlinks / terms / privacy / Tesla 3p 公钥） |
| Adapter → teslamateapi | `/api/**` 走 `httputil.NewSingleHostReverseProxy`，仅在 `matelink-selfhost` 项目内网，端口 8080 **不发布** |
| Adapter → PostgreSQL | `pgx` 直连，读 `positions`/`cars`/`drives`/`charges`/`states`/`addresses` |
| Adapter → Mosquitto | `teslamate/cars/+/+` 订阅，仅在 120 秒新鲜窗口内覆盖 DB 快照；**broker 不可用时代码显式降级到 PostgreSQL**（`mqtt.go` 日志原文：`PostgreSQL fallback remains available`） |
| TeslaMate → Mosquitto | TeslaMate 自身发布实时状态；`MQTT_HOST` 默认必填（`DISABLE_MQTT=true` 可关，见 5.2） |
| TeslaMate → Tesla 官方 | 出公网轮询；**这是唯一主动出站的车辆数据链路** |

**与 star-photo 的共存关系**：nginx 的 80 端口由 `star-photo.conf` 的 `server_name photo.joviluma.com` 占用；本轮新增的 `jourvolt.conf` 只在**同一个 80 端口上新增按 `server_name` 精确匹配**的 server 块（ACME challenge + 301），以及新的 443 server 块。nginx 按 `server_name` 精确匹配优先，双方互不抢占。落地前必须先确认 `star-photo.conf` 里没有 `listen 80 default_server`（见 T04 前置检查）。

---

## 3. 两个必须拍板的取舍

### 3.1 T1：自托管数据从哪来

#### 3.1.1 决策矩阵

| 判据 | ① 复用 Jovi PC 上的 TeslaMate（ECS 只跑 Adapter，直连远端库） | ② **迁移历史到 ECS（推荐）** | ③ ECS 冷启动（Plan B） |
| --- | --- | --- | --- |
| 手机 Room 里 190 行程 / 31 充电的命运 | **不丢**（不动 App） | **不丢**（不动 App），且服务端多一份可恢复副本 | **不丢**（不动 App），但服务端无副本 |
| 服务端历史 | 依赖家用机 7×24 在线，否则 App 间歇性不可用 | **完整继承**，服务端与手机各一份 | 从今天开始采集，历史只有手机里那一份 |
| Tesla token | 留在 PC 上，ECS 无 token，不需要重新授权 | **随库迁移**（`tokens` 表，用 `TESLAMATE_ENCRYPTION_KEY` 加密）→ **ECS 侧无需重新授权** | **必须重新授权** → 需要公网 443 托管 `/.well-known/appspecific/com.tesla.3p.public-key.pem` → **阻塞于备案** |
| 公网暴露面 | 需把家用 PostgreSQL 暴露公网或用隧道 → **违反安全约束** | 无 | 无 |
| 内存 | ECS 最省（只跑 Adapter） | 与 ③ 相同（迁移只影响磁盘） | 基线 |
| 工期 / 1 Mbps 影响 | 不可用 | dump 压缩后预计数 MB–数十 MB，1 Mbps 下数分钟～半小时，可接受 | 今晚即可起服务 |
| 失败模式 | 家用机离线 → 全线不可用 | 家用机起不来 → 自动降级到 ③ | 授权阻塞在备案上 |
| 结论 | **否决** | **推荐（主路径）** | **保留为 Plan B，且今晚就先按 ③ 起服务** |

#### 3.1.2 推荐结论

> **T1 = ② 迁移（主）+ ③ 冷启动（Plan B），两者今晚同时开工、不互斥。**

- **今晚**：先按 ③ 把裁剪版五容器跑起来（`T03`），让服务端**从今天开始采集**，P0-a 的服务端自测当晚闭环。
- **同时**：Jovi 在自己电脑上执行 `dump-teslamate-local.sh`（`T02`），产出 dump 后 scp/rsync 到 ECS，`migrate-teslamate.sh` 做 `pg_restore`。迁移完成后 **ECS 的 TeslaMate 直接继承库里的 token，跳过 P0-9 重新授权**——这是迁移方案最大的隐藏收益：它把 P0-9 从"阻塞于备案"变成"不阻塞"。
- **① 否决理由（硬）**：需要把家用 PostgreSQL 暴露公网或引入隧道组件；且家用机不属于可运维资产（无 SLA、无备份、无监控），把线上 App 的可用性押在它身上不可接受。

#### 3.1.3 "服务器侧历史从今天开始采集"是否可接受

| 场景 | 可接受性 |
| --- | --- |
| 走 ② 迁移成功 | 不涉及——服务端历史完整 |
| 走 ③ 冷启动（Plan B） | **可接受，但有代价**：手机 Room 里的 190 行程**不会丢**（App 不清数据、不重装、不换签名），用户在统计页看到的仍是原有历史；代价是"服务端副本缺失"——一旦手机丢失/清数据/换机，这批历史无服务端可恢复。**补救**：家用机随时可再开机 dump 一次，② 的路径长期有效，随时可补；不存在"过了今晚就永远拿不到"的时间窗。 |
| ① | 不可接受（见上） |

#### 3.1.4 迁移路径（T1② 完整命令骨架）

**A. 在 Jovi 自己电脑上（`deploy/scripts/dump-teslamate-local.sh`，仅本地执行，产物不外发）**

```bash
#!/usr/bin/env bash
set -Eeuo pipefail
# 在 Jovi 自己的电脑（源 TeslaMate 所在 Docker 主机）上执行
cd <源 TeslaMate compose 目录>
docker compose ps                                   # 确认 database / teslamate 均 Up
docker compose exec -T database psql -U teslamate -d teslamate -tAc 'SHOW server_version;'
docker compose exec -T database psql -U teslamate -d teslamate -tAc 'select version();'
docker compose exec -T database psql -U teslamate -d teslamate -tAc \
  'select count(*) from drives;'                    # 记下源库条数，作为迁移后校验基线
docker compose exec -T database psql -U teslamate -d teslamate -tAc \
  'select count(*) from charges;'
docker compose exec -T database psql -U teslamate -d teslamate -tAc \
  'select count(*) from positions;'
docker compose exec -T database psql -U teslamate -d teslamate -tAc \
  'select count(*) from tokens;'                    # token 是否存在，决定能否跳过重新授权
umask 077
docker compose exec -T database pg_dump -U teslamate -d teslamate \
  --format=custom --compress=9 --no-owner --no-privileges \
  > "teslamate-$(date +%Y%m%d).dump"
ls -lh teslamate-*.dump
# 记录 TESLAMATE_ENCRYPTION_KEY：见下方红线
```

> **红线**：`TESLAMATE_ENCRYPTION_KEY` 是解开库里 Tesla token 的唯一钥匙。
> 它由 Jovi 在**自己的终端**里 `grep '^TESLAMATE_ENCRYPTION_KEY=' <源目录>/.env` 读出，并**直接经 SSH 写进 ECS 的 `/home/jourvolt/matelink-selfhost/.env`**。
> **绝对不得**粘贴进聊天、Obsidian、Git、日志或备份目录。若不迁移这个 key，即使 dump 成功，TeslaMate 也读不出 token，必须重新授权（回到备案阻塞）。

**B. 传输到 ECS（1 Mbps，压缩 + 断点续传；**绝不暴露家用 PostgreSQL 端口到公网**）**

```bash
# 在 Jovi 自己的电脑上执行。分卷便于断点续传与校验
split -b 20M "teslamate-$(date +%Y%m%d).dump" teslamate-dump.part-
sha256sum teslamate-dump.part-* > teslamate-dump.sha256
rsync -avP --partial --append-verify -e "ssh -i ~/.ssh/joviluma_jourvolt_deploy_ed25519" \
  teslamate-dump.part-* teslamate-dump.sha256 \
  jourvolt@120.55.64.11:/home/jourvolt/matelink-selfhost/import/
# 在 ECS 上校验后合并
ssh -i ~/.ssh/joviluma_jourvolt_deploy_ed25519 jourvolt@120.55.64.11 \
  'cd ~/matelink-selfhost/import && sha256sum -c teslamate-dump.sha256 && cat teslamate-dump.part-* > teslamate.dump && rm -f teslamate-dump.part-*'
```

> 若 dump 小于 20 MB 可不分卷；`rsync --partial --append-verify` 保证 1 Mbps 下断线可续。
> **严禁**为图省事把家用机的 5432 端口映射/穿透到公网。

**C. 在 ECS 上（`deploy/scripts/migrate-teslamate.sh`）**

```bash
#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
cd /home/jourvolt/matelink-selfhost
SRC_MAJOR="${SRC_MAJOR:?源库 PostgreSQL 大版本，例如 16 或 18}"

# 1) 版本兼容检查：pg_dump 只能「低版本 -> 高版本」，反之失败
CUR_MAJOR="$(docker compose exec -T postgres psql -U jourvolt -d jourvolt -tAc \
  "select current_setting('server_version_num')::int/10000;")"
if (( SRC_MAJOR > CUR_MAJOR )); then
  echo "ABORT: 源库 PG${SRC_MAJOR} 高于 ECS PG${CUR_MAJOR}；" \
       "请将 .env 的 POSTGRES_IMAGE 设为 postgres:${SRC_MAJOR}-alpine 并重建 postgres 卷后再迁移" >&2
  exit 1
fi

# 2) 建库与角色（幂等；密码走 stdin 文件，不出现在 argv）
bash ./scripts/init-shared-db.sh

# 3) 恢复到共享实例
docker compose exec -T postgres pg_restore \
  -U jourvolt -d teslamate --no-owner --no-privileges --clean --if-exists \
  -v /dev/stdin < ./import/teslamate.dump

# 4) 校验条数与源库一致
docker compose exec -T postgres psql -U jourvolt -d teslamate -tAc 'select count(*) from drives;'
docker compose exec -T postgres psql -U jourvolt -d teslamate -tAc 'select count(*) from charges;'
docker compose exec -T postgres psql -U jourvolt -d teslamate -tAc 'select count(*) from tokens;'
```

**版本兼容规则（工程师必须执行）**：

| 源库大版本 | ECS `POSTGRES_IMAGE` | 备注 |
| --- | --- | --- |
| ≤ 16 | `postgres:16-alpine`（默认） | 与现有 staging 一致，可直接恢复 |
| 17 | `postgres:17-alpine` | — |
| 18 | `postgres:18-alpine` | 仓库现有家用模板用的就是 `postgres:18-trixie`，**源库很可能是 18**；此时 ECS 必须升到 18，现存 staging postgres 数据为 mock，可弃 |

统一用 **alpine 变体**：Debian 版镜像体积约是 alpine 的 2–3 倍，在 1 Mbps 下这一项就能省 20–40 分钟下载。

**D. 迁移后**（关键，决定 P0-9 能否跳过）

```bash
cd /home/jourvolt/matelink-selfhost
docker compose up -d teslamate
docker compose logs -f --tail=100 teslamate
# 期望：不再要求登录/授权，日志出现车辆在线并开始写入
docker compose exec -T postgres psql -U jourvolt -d teslamate -tAc 'select count(*) from cars;'
```

> 若 `tokens` 表非空且 `TESLAMATE_ENCRYPTION_KEY` 与源端一致 → TeslaMate 直接用既有 token 采集，**P0-9 跳过**。
> 若 token 解开失败（key 不一致或源端用 Fleet API 且绑定了源域名/IP）→ 退回重新授权，此时 **P0-9 阻塞于备案 + 443 + Tesla 公钥**，须立即上报主理人，不要自行尝试绕过。

---

### 3.2 T2：数据库拓扑 + TLS 方案

#### 3.2.1 决策矩阵 A：PostgreSQL 共用 vs 独立

| 判据 | 共用实例分库（**推荐**） | 两实例并存 |
| --- | --- | --- |
| 内存 | 单实例 `shared_buffers=64MB` 约 90–160 MB RSS | 多 60–100 MB，且**两个实例的 page cache 不能共享**，实际惩罚大于账面 |
| 连接数 | 一份 `max_connections=30` 供两路径共用，需精算池大小 | 各自独立，互不抢占 |
| 故障域 | **耦合**：postgres OOM → 两条路径同时不可用 | 隔离 |
| 备份 | 同卷；**必须按库分别 `pg_dump`**，恢复演练在隔离库做 | 天然分离 |
| 版本自由度 | 一个 tag 定死两条路径；升 PG18 时 jourvolt 库要重建（mock 数据，可弃） | 各自选版本 |
| Compose 项目边界 | 需要一条 external 网络让 `jourvolt-staging` 连过来 | 无耦合 |
| 在 700 MB 预算下 | **可行**（见 4.2） | **不可行**（合计超限） |

**结论：共用实例分库。** 落地形态不是"复用 `jourvolt-staging` 里那个容器"，而是：

- 共享 PostgreSQL 归属 **`matelink-selfhost`** 项目（P0 今晚就建，五容器之一，与 PRD P0-2 的"五个容器"口径一致）；
- `jourvolt-staging` 项目**删除**其 `jourvolt-postgres` 服务，改为通过 external 网络 `jourvolt-infra` 连共享实例；
- 这样两条路径各自 `up/down` 互不影响，postgres 有独立生命周期与独立备份，同时只付一份内存。

> 为什么不直接复用 staging 里那个 postgres 容器？因为它的镜像 tag 被 staging 钉死在 `postgres:16-alpine`，而源库很可能是 PG18——一旦需要升版本就必须销毁重建，跨项目操作反而更乱。放进 `matelink-selfhost` 后 tag 由 `.env` 的 `POSTGRES_IMAGE` 自由控制。

#### 3.2.2 决策矩阵 B：TLS 方案

| 判据 | A：宿主机 nginx + certbot（**选定**） | B：Caddy 容器绑 443 + ACME TLS-ALPN-01（兜底） |
| --- | --- | --- |
| 是否需要 root | 是，**Jovi 已确认给 `jourvolt` 开 sudo** → 可脚本化、幂等、可重复执行 | 否 |
| 抢 80 端口 | 否（新增按 `server_name` 匹配的 80 server 块，不动 star-photo） | 否 |
| 与 star-photo 隔离 | 新增独立 `conf.d/jourvolt.conf`；**签发方式改用 `--webroot` 后 certbot 完全不改写 nginx 配置** | 完全隔离（Caddy 独立进程） |
| 证书续期 | certbot timer / systemd，全自动 | Caddy 自动 |
| 内存代价 | **0**（nginx 已在跑，复用） | +20–40 MB |
| 静态资产托管 | nginx 直出 `/srv/jourvolt/public` | Caddy 直出 |
| 风险 | `certbot --nginx` 可能改写配置 → **用 `--webroot` 消除** | 依赖 TLS-ALPN-01 在 80 不可达时的实测回落行为 |

**结论：方案 A，且签发方式收紧为 A′。**

> **A′ 与 A 的差别（这是本设计对 PRD 的一处主动收紧）**：
> PRD 写的是 `certbot --nginx -d <域名>`。该模式会由 certbot 的 nginx 插件**改写 nginx 配置文件**，虽然有 `-d` 限定，但插件在扫描/改写 `conf.d/` 时仍存在波及 `star-photo.conf` 的可能，与"不动 star-photo"的硬约束存在张力。
> **A′ 改为 `certbot certonly --webroot -w /srv/jourvolt/acme -d …`**：certbot 只往 webroot 写 challenge 文件并调 ACME，**完全不读不写任何 nginx 配置**；证书路径由我们在 `jourvolt.conf` 里手工引用。风险归零，且续期 deploy-hook 只需 `systemctl reload nginx`。

**判断条件（写死给工程师，答案到位即可开工）**：

| 若 | 则 |
| --- | --- |
| `sudo -n true` 免密成功 且 `nginx -v` 可用 | 走 **A′**（`setup-root.sh` 全自动） |
| `sudo -n true` 失败（未开 sudo 或需密码） | 走 **B**（`setup-caddy-edge.sh`，见 7.2） |
| `sudo` 可用但备案未接入 | 仍走 A′，但**只执行到 `nginx -t` 与配置落盘**，证书签发步骤必须 `--skip-cert` 跳过（见第 8 节） |

---

## 4. 端口与内存预算

### 4.1 端口分配表（最终）

| 宿主机端口 | 绑定地址 | 目标 | 是否对外 | 安全组 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 22/tcp | `0.0.0.0` | sshd | 是 | **已放行，保留** | 仅 SSH key 登录（P2-8） |
| 80/tcp | `0.0.0.0` | 宿主机 nginx | 是 | 已放行 | **既有，star-photo 在用**；本轮只**新增按 `server_name` 匹配的 server 块** |
| 443/tcp | `0.0.0.0` | 宿主机 nginx | 是 | **需新增放行（R6，阿里云控制台人工）** | 本轮新增 |
| 3100/tcp | `127.0.0.1` | `star-photo-web:3000` | 否 | 不放行 | **既有，不动** |
| **18080/tcp** | **`127.0.0.1`** | `matelink-adapter:8080` | 否 | **不放行** | P0 唯一对外组件的宿主机落点 |
| 18090/tcp | `127.0.0.1` | `jourvolt-dev-api:8080` | 否 | **不放行** | P1，现状即如此，保持不变 |
| —— | —— | `teslamate:4000` | **不发布宿主机端口** | **不放行** | 仅经 SSH 隧道**直连容器 IP** 访问一次 |
| —— | —— | `teslamateapi:8080` | **不发布** | **不放行** | 无 token 也会响应，暴露等于公开车辆数据 |
| —— | —— | `postgres:5432` | **不发布** | **不放行** | — |
| —— | —— | `mosquitto:1883` | **不发布** | **不放行** | — |

> **注意**：现有 `deploy/teslamate-home-docker/docker-compose.yml` 里 `teslamate` 是 `"4000:4000"`（绑 `0.0.0.0`）、`grafana` 是 `"3000:3000"`。裁剪版必须把 teslamate 的 `ports:` 整段删除，grafana 整段删除。这是硬约束的直接落地项。

**SSH 隧道访问 TeslaMate（不发布 4000 的正确做法）**

```bash
# 在 Jovi 自己的电脑上执行；容器 IP 从 ECS 上取得
CID="$(docker compose -f docker-compose.selfhost.yml ps -q teslamate)"
CIP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$CID")"
# 输出后在本机执行：
ssh -i ~/.ssh/joviluma_jourvolt_deploy_ed25519 -N -L 14000:"${CIP}":4000 jourvolt@120.55.64.11
# 然后本地浏览器打开 http://127.0.0.1:14000
```

> 走的是 sshd → docker bridge 的宿主机内路由，**不需要给 teslamate 发布任何宿主机端口**。
> TeslaMate 侧需设 `VIRTUAL_HOST=127.0.0.1`；若 Phoenix 的 origin 校验仍拦，临时设 `CHECK_ORIGIN=false`（4000 不对外，风险可控），授权完成后改回。

### 4.2 内存预算表（硬约束：新增 ≤ 700 MB）

| 容器（Compose 服务） | `mem_limit` | `memswap_limit` | 实测 RSS 预估 | 说明 |
| --- | --- | --- | --- | --- |
| `postgres`（共享，两库） | **232m** | 344m | 90–160 MB | `shared_buffers=64MB`、`max_connections=30`、`work_mem=2MB`、`maintenance_work_mem=32MB` |
| `teslamate`（Elixir/BEAM） | **288m** | 512m | 180–330 MB | 最大头；给 224 MB 的 swap 余量，宁可慢也不要被 kill |
| `mosquitto` | **40m** | 64m | 5–12 MB | 保留（见 5.2） |
| `teslamateapi` | **64m** | 104m | 18–40 MB | 端口不发布 |
| `matelink-adapter` | **76m** | 120m | 22–55 MB | 唯一对外，snaphot/standby 查询会拉行，留足 |
| **P0 合计（本轮新建）** | **700 MB** | — | **RSS 目标 ≤ 480 MB** | **正好用满 700 MB 上限** |
| `jourvolt-dev-api`（P1，已存在） | 96m | 128m | 20–45 MB | 现状已运行，本轮只是补限额，**净增约 0** |
| 下线：`jourvolt-staging` 的 postgres | −（约 85–100 MB 实际占用） | — | — | 并入共享实例后销毁 |

**口径说明（工程师与 QA 按此判定）**

1. **700 MB 是 cgroup 天花板，不是预留。** 判定"新增容器总内存 ≤ 700 MB"看的是 `docker inspect` 里五个容器 `Memory` 字段之和 = 700 MB。
2. **真实共存压力看 RSS**：`docker stats --no-stream` 实测五容器 RSS 之和目标 ≤ 480 MB；超过 600 MB 立即触发降级（先停 `jourvolt-staging` 的 API，再考虑 `DISABLE_MQTT=true`）。
3. **准入门槛**：启动前 `free -m` 的 `available` **≥ 800 MB**；不满足不得 `up`（`capacity-gate.sh` 强制拦截）。
4. **1 Mbps 不影响内存**，但显著影响部署时长：镜像拉取是今晚最慢的一步（见 4.4）。

### 4.3 OOM 归属保证（OOM 只能发生在 JourVolt 侧）

机制与理由：

1. **五个 JourVolt 容器全部有 `mem_limit`** → 当某容器触及自身限额，内核在**该 cgroup 内部**回收/杀进程，**不会升级为全局 OOM**，因此不会牵连 `star-photo`。
2. `star-photo` **不设限额也不重启**（硬约束），它吃到的是"五个容器限额之和之外的剩余"——只要 700 MB 天花板成立且准入 `available ≥ 800 MB`，剩余空间足以覆盖其既有占用。
3. **swap 兜底**：`memswap_limit > mem_limit` 让 TeslaMate 的瞬时峰值溢出到 swap（swap 尚余约 1.6 GB），从"被 kill"降级为"变慢"。
4. **预警先于 OOM**（P2-3，`memory-watch.sh` + systemd timer）：`available` < 20% 或任一 JourVolt 容器 RSS > 其 `mem_limit` 的 85% 即告警。**在 1870 MB 的机器上，这条比事后恢复有价值得多**，故本轮就纳入。

### 4.4 1 Mbps 带宽下的拉取与传输策略

| 事项 | 影响 | 对策 |
| --- | --- | --- |
| 镜像拉取（teslamate + postgres + mosquitto + teslamateapi + golang 构建镜像）约 600–900 MB | 1 Mbps ≈ 125 KB/s → 理论 **1.5–2 小时** | ① 优先配置**阿里云容器镜像加速器**（`/etc/docker/daemon.json` 的 `registry-mirrors`，走阿里云内网，可降到分钟级）；② 拉取放后台 + 失败重试循环；③ 镜像一律选 alpine 变体 |
| 配置镜像加速可能需 `systemctl restart docker` | **会停掉 star-photo 容器，违反硬约束** | 先 `docker info \| grep -i 'live restore'`：为 `true` 才允许 restart；否则**放弃加速**，只用 `systemctl reload docker`，接受慢速拉取 |
| `pg_dump` 传输 | 压缩后通常数 MB–数十 MB | `split -b 20M` + `rsync --partial --append-verify` 断点续传 |
| Adapter 镜像构建 | 需拉 `golang:1.24-alpine`（约 100 MB 压缩） | Dockerfile 已内置 `GOPROXY=https://goproxy.cn,direct`；构建放后台，预计 20–40 分钟 |

---

## 5. 裁剪版自托管 Compose 设计

### 5.1 服务清单（逐服务说明）

| 服务 | 镜像 | 环境变量（键名） | `mem_limit` | healthcheck | `depends_on` | 发布端口 |
| --- | --- | --- | --- | --- | --- | --- |
| `postgres` | `${POSTGRES_IMAGE:-postgres:16-alpine}` | `POSTGRES_USER=jourvolt`、`POSTGRES_PASSWORD`、 `POSTGRES_DB=jourvolt`、`POSTGRES_INITDB_ARGS`、`TZ` | 232m | `pg_isready -U jourvolt -d jourvolt`（可靠，必配） | — | **否** |
| `teslamate` | `teslamate/teslamate:latest` | `ENCRYPTION_KEY`、`DATABASE_USER/PASS/NAME/HOST/PORT`、`DATABASE_POOL_SIZE=5`、`MQTT_HOST=mosquitto`、`MQTT_PORT=1883`、`TZ`、`VIRTUAL_HOST=127.0.0.1`、`CHECK_ORIGIN` | 288m | **不配**（镜像内不保证有 `wget`/`curl`/`nc`，避免写出脆弱检查）；验收改用宿主机直连容器 IP 的 curl | `postgres: service_healthy`、`mosquitto: service_started` | **否** |
| `mosquitto` | `eclipse-mosquitto:2` | 无（`command: mosquitto -c /mosquitto-no-auth.conf`，沿用镜像自带无认证配置，**仅项目内网可达**） | 40m | 不配 | — | **否** |
| `teslamateapi` | `tobiasehlert/teslamateapi:latest` | `ENCRYPTION_KEY`、`DATABASE_*`、`MQTT_HOST`、`TZ`、`API_TOKEN`、`API_TOKEN_HEADER=Authorization` | 64m | 可选 `nc -z 127.0.0.1 8080`（若镜像内无 `nc` 则删掉） | `postgres: service_healthy`、`mosquitto: service_started`、`teslamate: service_started` | **否** |
| `matelink-adapter` | `build: ./adapter` | `DATABASE_URL`、`UPSTREAM_URL=http://teslamateapi:8080`、`API_TOKEN`、`TZ`、`MQTT_ENABLED=true`、`MQTT_HOST=mosquitto`、`MQTT_PORT=1883`、`MQTT_NAMESPACE=` | 76m | 不配（无鉴权会返回 401，与健康判定语义冲突）；**验收口径就是 401** | `postgres: service_healthy`、`mosquitto: service_started`、`teslamateapi: service_started` | **`127.0.0.1:18080:8080`（唯一）** |

> **已移除**：`grafana` 整个服务，以及 `teslamate-grafana-data` 卷。
> **`restart` 策略**：全部 `restart: unless-stopped`（PRD P0-2 要求）。
> **日志**：统一 `json-file`，`max-size: 10m`、`max-file: "3"`——27 GB 磁盘下防止日志撑爆；日志内容禁写 VIN / token / 精确坐标（P2-7 审计项）。

### 5.2 Mosquitto 存废：实测结论（不照抄 PRD）

PRD 3.3 给的理由是"TeslaMate 启动依赖 `MQTT_HOST`，且 **Adapter 靠它取实时快照**"。我逐条核了代码与上游文档，**结论是"保留"，但理由与 PRD 不同，且 PRD 的第二条理由不成立**：

| 判断 | 核实结果 | 证据 |
| --- | --- | --- |
| Adapter 靠 MQTT 取实时快照？ | **不成立（MQTT 是可选叠加，不是依赖）** | `adapter/cmd/adapter/mqtt.go:355-359`：`loadMQTTConfig()` 中 `enabled: !strings.EqualFold(env("MQTT_ENABLED","true"),"false")`，`runMQTT()` 首行 `if !config.enabled { return }`。main.go:214 的 `postgresStore.Snapshot()` 直接从 `positions JOIN cars` 取快照；`mqtt.go` 的连接失败/订阅失败/断开三处日志原文均为 `PostgreSQL fallback remains available` |
| TeslaMate 启动依赖 `MQTT_HOST`？ | **默认成立，但有官方开关** | TeslaMate 环境变量表：`MQTT_HOST` = 「Hostname of the broker (**required unless `DISABLE_MQTT` is true**)」；`DISABLE_MQTT` 默认 `false`。官方文档 compose 均含 `MQTT_HOST=mosquitto` |
| 砍掉能省多少？ | **约 8 MB RSS**（`eclipse-mosquitto:2` 实测 5–12 MB） | 相对 700 MB 预算不到 1.2% |
| 砍掉会丢什么？ | **会丢 App 已实现的"状态化"字段** | `postgresStore.Snapshot()` 里 `car_status.locked/doors_open/windows_open` 被硬编码为 `nil`，`fields["locked"]="unavailable"`，且**完全没有 `charging_details`**；而 `mqttFieldPaths` 提供 `locked`、`sentry_mode`、`doors_open`、`windows_open`、`plugged_in`、`charging_state`、`charger_voltage/current/power`、`time_to_full_charge` 等。PROGRESS-GAP 里 8/27 做的"驾驶/开口/TPMS 告警/充电中"四类状态，其中开口与充电中**只能靠 MQTT** |

**结论：保留 Mosquitto，`mem_limit 40m`。** 用不到 1.2% 的预算换回 App 已实现状态化字段的一半，同时消除 TeslaMate 启动的不确定性，这笔账很清楚。

**降级开关（写进文档备查，默认不开）**：若 `docker stats` 实测五容器 RSS 之和 > 600 MB 且逼近系统压力，按序降级：
1. 停 `jourvolt-staging` 的 API（P1 本轮不跑真机）→ 省 20–45 MB；
2. 仍不够才设 `DISABLE_MQTT=true` 并移除 mosquitto 服务 → 省约 8 MB，代价是 App 的锁车/门窗/充电状态变为不可用。

### 5.3 裁剪版 Compose（新增文件，完整骨架）

落盘路径：`deploy/teslamate-home-docker/docker-compose.selfhost.yml`

```yaml
# ECS 裁剪版：无 Grafana；teslamateapi / postgres / mosquitto / teslamate 一律不发布宿主机端口。
# 注意：本文件是新增，不修改同目录的 docker-compose.yml（那是 Jovi 自己电脑上在跑的家用模板）。
name: matelink-selfhost

x-logging: &default-logging
  driver: json-file
  options:
    max-size: "10m"
    max-file: "3"

services:
  postgres:
    image: ${POSTGRES_IMAGE:-postgres:16-alpine}
    restart: unless-stopped
    environment:
      POSTGRES_USER: jourvolt
      POSTGRES_PASSWORD: ${JOURVOLT_DB_PASSWORD:?set JOURVOLT_DB_PASSWORD in .env}
      POSTGRES_DB: jourvolt
      POSTGRES_INITDB_ARGS: "--encoding=UTF8 --locale=C"
      TZ: ${TZ:-Asia/Shanghai}
    command:
      - postgres
      - -c
      - shared_buffers=64MB
      - -c
      - max_connections=30
      - -c
      - work_mem=2MB
      - -c
      - maintenance_work_mem=32MB
      - -c
      - temp_buffers=2MB
      - -c
      - effective_cache_size=256MB
      - -c
      - wal_buffers=4MB
      - -c
      - checkpoint_completion_target=0.9
      - -c
      - log_min_duration_statement=2000
      - -c
      - log_statement=none
    volumes:
      - selfhost-postgres:/var/lib/postgresql/data
    mem_limit: 232m
    memswap_limit: 344m
    logging: *default-logging
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U jourvolt -d jourvolt"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 30s
    networks:
      - jourvolt-infra

  mosquitto:
    image: eclipse-mosquitto:2
    restart: unless-stopped
    command: mosquitto -c /mosquitto-no-auth.conf
    volumes:
      - mosquitto-conf:/mosquitto/config
      - mosquitto-data:/mosquitto/data
    mem_limit: 40m
    memswap_limit: 64m
    logging: *default-logging
    networks:
      - jourvolt-infra

  teslamate:
    image: teslamate/teslamate:latest
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
      mosquitto:
        condition: service_started
    environment:
      ENCRYPTION_KEY: ${TESLAMATE_ENCRYPTION_KEY:?set TESLAMATE_ENCRYPTION_KEY in .env}
      DATABASE_USER: teslamate
      DATABASE_PASS: ${TESLAMATE_DB_PASSWORD:?set TESLAMATE_DB_PASSWORD in .env}
      DATABASE_NAME: teslamate
      DATABASE_HOST: postgres
      DATABASE_PORT: 5432
      DATABASE_POOL_SIZE: 5
      MQTT_HOST: mosquitto
      MQTT_PORT: 1883
      TZ: ${TZ:-Asia/Shanghai}
      VIRTUAL_HOST: 127.0.0.1
      CHECK_ORIGIN: ${TESLAMATE_CHECK_ORIGIN:-false}
    volumes:
      - ./import:/opt/app/import
    cap_drop:
      - ALL
    mem_limit: 288m
    memswap_limit: 512m
    logging: *default-logging
    networks:
      - jourvolt-infra
    # 不发布 4000：仅经 SSH 隧道直连容器 IP 访问（见文档 4.1）

  teslamateapi:
    image: tobiasehlert/teslamateapi:latest
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
      mosquitto:
        condition: service_started
      teslamate:
        condition: service_started
    environment:
      ENCRYPTION_KEY: ${TESLAMATE_ENCRYPTION_KEY:?}
      DATABASE_USER: teslamate
      DATABASE_PASS: ${TESLAMATE_DB_PASSWORD:?}
      DATABASE_NAME: teslamate
      DATABASE_HOST: postgres
      DATABASE_PORT: 5432
      MQTT_HOST: mosquitto
      TZ: ${TZ:-Asia/Shanghai}
      API_TOKEN: ${MATE_LINK_API_TOKEN:?set MATE_LINK_API_TOKEN in .env}
      API_TOKEN_HEADER: Authorization
    mem_limit: 64m
    memswap_limit: 104m
    logging: *default-logging
    networks:
      - jourvolt-infra
    # 不发布 8080：无 token 也会响应，暴露等于公开车辆数据

  matelink-adapter:
    build:
      context: ./adapter
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
      mosquitto:
        condition: service_started
      teslamateapi:
        condition: service_started
    environment:
      DATABASE_URL: postgres://teslamate:${TESLAMATE_DB_PASSWORD}@postgres:5432/teslamate?sslmode=disable
      UPSTREAM_URL: http://teslamateapi:8080
      API_TOKEN: ${MATE_LINK_API_TOKEN:?}
      TZ: ${TZ:-Asia/Shanghai}
      MQTT_ENABLED: ${MQTT_ENABLED:-true}
      MQTT_HOST: mosquitto
      MQTT_PORT: 1883
      MQTT_NAMESPACE: ${MQTT_NAMESPACE:-}
    ports:
      - "127.0.0.1:18080:8080"
    mem_limit: 76m
    memswap_limit: 120m
    logging: *default-logging
    networks:
      - jourvolt-infra

volumes:
  selfhost-postgres:
  mosquitto-conf:
  mosquitto-data:

networks:
  jourvolt-infra:
    name: jourvolt-infra
```

> `jourvolt-infra` 网络声明为具名网络，P1 的 `jourvolt-staging` 通过
> ```yaml
> networks: { jourvolt-infra: { external: true } }
> ```
> 接入同一个网络即可连到 `postgres:5432`。

### 5.4 需要新增 / 修改的文件清单（相对路径）

| 动作 | 路径 | 说明 |
| --- | --- | --- |
| **新增** | `deploy/teslamate-home-docker/docker-compose.selfhost.yml` | 裁剪版五容器（本文档 5.3） |
| **新增** | `deploy/teslamate-home-docker/.env.selfhost.example` | 键名清单：`TZ`、`POSTGRES_IMAGE`、`JOURVOLT_DB_PASSWORD`、`TESLAMATE_ENCRYPTION_KEY`、`TESLAMATE_DB_PASSWORD`、`MATE_LINK_API_TOKEN`、`MQTT_ENABLED`、`TESLAMATE_CHECK_ORIGIN`。**全为占位符** |
| **新增** | `deploy/scripts/capacity-gate.sh` | 容量盘点 + `available ≥ 800 MB` 准入拦截 |
| **新增** | `deploy/scripts/init-shared-db.sh` | 幂等创建 `teslamate` 角色与库；密码经 stdin 文件注入，不出现在 `ps` |
| **新增** | `deploy/scripts/dump-teslamate-local.sh` | **在 Jovi 自己电脑上执行**的 pg_dump 与基线计数 |
| **新增** | `deploy/scripts/migrate-teslamate.sh` | 版本兼容检查 + `pg_restore` + 条数校验 |
| **新增** | `deploy/scripts/deploy-selfhost.sh` | 编排：容量门禁 → 拉镜像（重试）→ `up -d` → 自检 |
| **新增** | `deploy/scripts/tunnel-teslamate.sh` | 输出容器 IP 与 `ssh -L` 命令，供 TeslaMate 隧道访问 |
| **新增** | `deploy/scripts/setup-root.sh` | R1–R5 幂等自动化（sudo 版，见第 7 节） |
| **新增** | `deploy/nginx/jourvolt.conf.template` | 独立 nginx 配置模板（三个 443 server + 一个 80 ACME/301 server） |
| **新增** | `deploy/nginx/jourvolt-ssl.selfsigned.inc` / `jourvolt-ssl.le.inc` | 证书切换用的 include 片段，保证任何时刻 `nginx -t` 都通过 |
| **新增** | `deploy/scripts/publish-static.sh` | 同步 assetlinks / terms / privacy / Tesla 公钥到 `/srv/jourvolt/public` 并修权限与 SELinux 上下文 |
| **新增** | `deploy/scripts/verify-public.sh` | 公网自检（DNS / 443 / 证书 / 限流 / 端口未暴露） |
| **新增** | `deploy/scripts/backup-jourvolt.sh` | 按库分别 `pg_dump` + `age` 加密 |
| **新增** | `deploy/scripts/memory-watch.sh` | 内存与证书天数告警（P2-3） |
| **新增** | `deploy/systemd/jourvolt-memory-watch.{service,timer}` | P2-3 定时器 |
| **新增** | `deploy/systemd/jourvolt-backup.{service,timer}` | P2-1 定时器（复用仓库既有 `systemd/backup.env.example` 约定） |
| **新增** | `deploy/jourvolt-dev-mock/docker-compose.pilot.ecs.yml` | P1：只含 `jourvolt-dev-api`，连 external `jourvolt-infra`，不带 edge、不绑 80/443 |
| **新增** | `deploy/jourvolt-dev-mock/.env.ecs.example` | P1 键名清单 |
| **新增** | `deploy/scripts/setup-caddy-edge.sh` | 方案 B 兜底脚本（仅当 sudo 不可用时启用） |
| **不修改** | `deploy/teslamate-home-docker/docker-compose.yml` | **源端在跑的家用模板，禁止改动** |
| **不修改** | `deploy/jourvolt-dev-mock/docker-compose.pilot.example.yml`、`preflight.sh`、`pilot-up.sh`、`write-assetlinks.sh`、`Caddyfile.example` | P1 用 `pilot-up.sh --no-edge` 即可绕开 80/443 冲突，**无需改这些文件** |
| **不修改** | `/etc/nginx/conf.d/star-photo.conf` | 只备份与 diff 校验，不改写 |

### 5.5 两个已知风险与处置（工程师先看）

| 编号 | 风险 | 验收/处置命令 |
| --- | --- | --- |
| **R-1** | **手机 Room 历史被空服务端覆盖**：手机切到冷启动的 ECS 后，App 同步可能以服务端空历史为准，导致 Room 里 190 行程 / 31 充电下降。这是本轮**唯一可能伤害硬资产**的路径 | ① 切换前先在手机统计页截图记录条数（190 行程 / 31 充电）；② **优先顺序：先完成 T1② 迁移，再让手机切到新服务器**；③ 若必须今晚先连，只做"填地址+保存+同步"一步，同步完成后**立即复查条数**；④ 条数一旦下降，立即改回原地址并上报，**不要自行清数据或重装** |
| **R-2** | **`teslamateapi` 的 Bearer 前缀**：Adapter 反代 `/api/*` 时会原样转发 `Authorization: Bearer <token>`；若 teslamateapi 按原始 header 值比对 `API_TOKEN`，会 401 | 验收命令：`curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/api/v1/cars -H "Authorization: Bearer ${MATE_LINK_API_TOKEN}"`，期望 `200`。若返回 `401`：先试 `.env` 里把 `MATE_LINK_API_TOKEN` 设为 `Bearer <原 token>`（**纯配置改动，不碰代码**）；仍失败则记录为 P0-10 部分通过——Adapter 原生路由 `/api/matelink/v1/*` 不受影响，App 的 `/api/v1/cars` 走 fallback 上报 |

> R-2 的 fallback 需主理人确认 App 是否强依赖 `/api/v1/cars`。设计上 Adapter 的原生路由已覆盖 `capabilities`、`snapshot`、`parked`、`standby`，`/api/` 只是 legacy 代理。

---

## 6. 任务分解（给工程师）

> **5 个任务，按依赖排序。T02 与 T03 可部分并行**（T02 的 dump 在 Jovi PC 上做，不占 ECS 资源）。
> 标注：`P0-a` = 今晚可完成可验收（不依赖域名/备案）；`P0-b` = 依赖域名 A 记录 + 备案接入 + 安全组；`P1` = 云登录；`P2` = 运维长期项。

### T01 · 基础设施与容量门禁（`P0-a`）

| 项 | 内容 |
| --- | --- |
| 目标 | 建目录与权限骨架、完成容量盘点并强制准入、`jourvolt-staging` 现状留证 |
| 涉及文件 | 新增 `deploy/scripts/capacity-gate.sh`、`deploy/scripts/deploy-selfhost.sh`、`deploy/teslamate-home-docker/.env.selfhost.example` |
| 依赖 | 无 |
| 可并行 | 与 T02 并行（T02 的 dump 在 Jovi PC 上执行） |

**验收命令**

```bash
mkdir -p /home/jourvolt/matelink-selfhost && cd /home/jourvolt/matelink-selfhost
umask 077 && cp .env.selfhost.example .env && chmod 600 .env
bash ./scripts/capacity-gate.sh
# 期望：GATE=PASS，且输出 available >= 800
free -m; swapon -s; df -h
docker stats --no-stream            # 含 star-photo 两个容器，留作基线
ss -lntp                            # 留证：4000/8080/5432/1883 均无监听
ss -lntp | grep -E ':80|:443'       # 80 被 nginx(root) 占用、443 未监听
sudo -n true && echo SUDO_OK || echo SUDO_BLOCKED   # 决定 T04 走 A′ 还是 B
```

---

### T02 · 数据层：共享 PostgreSQL + 历史迁移（`P0-a`）

| 项 | 内容 |
| --- | --- |
| 目标 | 共享实例建双库双角色；完成 T1② 迁移（含版本检查）；落地按库备份脚本 |
| 涉及文件 | 新增 `deploy/scripts/init-shared-db.sh`、`deploy/scripts/dump-teslamate-local.sh`、`deploy/scripts/migrate-teslamate.sh`、`deploy/scripts/backup-jourvolt.sh`；新增 `docker-compose.selfhost.yml` 的 `postgres` 服务段 |
| 依赖 | T01 |
| 可并行 | dump 步骤在 Jovi PC 上并行执行 |

**验收命令**

```bash
bash ./scripts/init-shared-db.sh
docker compose exec -T postgres psql -U jourvolt -d jourvolt -tAc '\l'   # 出现 jourvolt 与 teslamate 两个库
docker compose exec -T postgres psql -U jourvolt -d teslamate -tAc 'select count(*) from drives;'
# 期望：与源库基线一致；若走 Plan B 冷启动则为 0（此时必须已按 R-1 记录手机条数）
docker compose exec -T postgres psql -U jourvolt -d teslamate -tAc 'select count(*) from tokens;'
# 期望 >= 1（迁移场景）；为 0 则说明需重新授权 -> P0-9 转为阻塞于备案，立即上报
bash ./scripts/backup-jourvolt.sh --dry-run
```

---

### T03 · 自托管五容器落地与链路自测（`P0-a`）

| 项 | 内容 |
| --- | --- |
| 目标 | 五容器起全、端口收窄、Token 生效、TeslaMate 授权（或 token 继承）、Adapter 数据链路打通 |
| 涉及文件 | 新增 `deploy/teslamate-home-docker/docker-compose.selfhost.yml`（剩余四个服务）、`deploy/scripts/deploy-selfhost.sh`、`deploy/scripts/tunnel-teslamate.sh` |
| 依赖 | T01、T02 |
| 可并行 | 否（串行） |

**验收命令**

```bash
docker compose config --quiet                       # 通过
bash ./scripts/deploy-selfhost.sh                   # 内部含拉取重试
docker compose ps                                   # 五容器 Up；postgres healthy
ss -lntp | grep -E ':(4000|8080|5432|1883)'         # 期望：无任何输出
ss -lntp | grep 18080                               # 期望：仅 127.0.0.1:18080
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/api/matelink/v1/capabilities
# 期望 401（不带 token）
curl -s http://127.0.0.1:18080/api/matelink/v1/capabilities \
  -H "Authorization: Bearer ${MATE_LINK_API_TOKEN}"        # 期望 200
curl -s http://127.0.0.1:18080/api/v1/cars \
  -H "Authorization: Bearer ${MATE_LINK_API_TOKEN}"        # 期望 200 且至少 1 辆车（见 R-2）
docker stats --no-stream                            # 五容器 RSS 之和目标 <= 480 MB
bash ./scripts/tunnel-teslamate.sh                  # 输出 ssh -L 命令
docker compose exec -T postgres psql -U jourvolt -d teslamate -tAc 'select count(*) from cars;'
# 期望 >= 1
```

> **证据等级**：本任务全部结果标注 **`LOCAL MOCK PASS`（服务端自测）**。
> **不得宣称公网可用。**

---

### T04 · 公网入口：DNS / 安全组 / nginx / TLS / 静态资产（`P0-b`）

| 项 | 内容 |
| --- | --- |
| 目标 | nginx 独立配置 + `nginx -t` 通过；证书签发与自动续期；静态资产发布；公网自检 |
| 涉及文件 | 新增 `deploy/nginx/jourvolt.conf.template`、`deploy/nginx/jourvolt-ssl.selfsigned.inc`、`deploy/nginx/jourvolt-ssl.le.inc`、`deploy/scripts/setup-root.sh`、`deploy/scripts/publish-static.sh`、`deploy/scripts/verify-public.sh` |
| 依赖 | T03；**外部**：域名备案接入完成 + 安全组 443 放行（R6，阿里云控制台人工） |
| 可并行 | 否（依赖外部门禁） |

**验收命令**

```bash
# 备案前（dry-run 阶段）
sudo nginx -t                                       # 期望：syntax is ok / test is successful
diff /root/star-photo.conf.bak.<TS> /etc/nginx/conf.d/star-photo.conf   # 期望：无差异
certbot certonly --webroot -w /srv/jourvolt/acme --dry-run \
  --cert-name jourvolt -d teslalink.joviluma.com \
  -d api.teslalink.joviluma.com -d auth.teslalink.joviluma.com \
  --agree-tos -m <证书联系邮箱> --no-eff-email        # 备案未通过时注定失败，仅用于排练

# 备案后（正式签发）
bash ./scripts/setup-root.sh                        # 幂等，可重复执行
sudo nginx -t && sudo systemctl reload nginx
curl -s -o /dev/null -w '%{http_code}\n' https://teslalink.joviluma.com/api/matelink/v1/capabilities \
  -H "Authorization: Bearer ${MATE_LINK_API_TOKEN}"  # 期望 200
echo | openssl s_client -connect teslalink.joviluma.com:443 -servername teslalink.joviluma.com 2>/dev/null \
  | openssl x509 -noout -issuer -dates              # 期望 Let's Encrypt 且剩余 > 60 天
bash ./scripts/verify-public.sh                     # DNS / 443 / 端口未暴露 全绿
systemctl status certbot-renew.timer                # 或 cron 等价物，enable
```

---

### T05 · 云登录 P1 就绪 + P2 运维闭环（`P1` + `P2`）

| 项 | 内容 |
| --- | --- |
| 目标 | Go API 切 `fleet`、preflight 转 PASS、assetlinks 发布、参数化 Release 构建；备份定时与内存告警上线 |
| 涉及文件 | 新增 `deploy/jourvolt-dev-mock/docker-compose.pilot.ecs.yml`、`deploy/jourvolt-dev-mock/.env.ecs.example`、`deploy/scripts/memory-watch.sh`、`deploy/systemd/jourvolt-memory-watch.{service,timer}`、`deploy/systemd/jourvolt-backup.{service,timer}` |
| 依赖 | T04（443 与静态资产）；**外部**：Tesla 应用审核通过、正式签名指纹、运营主体信息 |
| 可并行 | systemd 备份/告警部分可与 P1 主体并行 |

**验收命令**

```bash
# P1：注意 80/443 归宿主机 nginx，必须 --no-edge，不得启用 Caddy edge
bash ./pilot-up.sh --no-edge --env-file .env        # 期望 PILOT_DEPLOY=PASS
bash ./preflight.sh --env-file .env --verify-dns --verify-app-link
# 期望：退出码 0 且输出 PREFLIGHT=PASS
curl -s https://api.teslalink.joviluma.com/healthz
# 期望：{"mock_history":false,"mode":"fleet","persistence":"postgres","status":"ok"}
curl -s https://auth.teslalink.joviluma.com/.well-known/assetlinks.json | grep -c 'com.matelink'
# 期望 >= 1，且含 32 段冒号分隔指纹

# 构建参数（Windows，构建产物仍需签名持有人签名）
cd android
.\build-pilot-apk.ps1 -ApiBaseUrl https://api.teslalink.joviluma.com/ `
  -AuthHost auth.teslalink.joviluma.com `
  -PublicInfoBaseUrl https://auth.teslalink.joviluma.com/ `
  -SigningPropertiesPath <仓库外私密 properties>

# P2
systemctl status jourvolt-memory-watch.timer jourvolt-backup.timer
bash ./scripts/memory-watch.sh --once                # 期望输出阈值判定，不误报
bash ./scripts/backup-jourvolt.sh --require-upload   # 无 remote 时按 fail-closed 失败退出
```

> **证据等级**：P1 未拿到真实 Tesla 授权前，一切只能标 `PHONE_SMOKE_PASS`；
> 只有走完 Tesla 官方 OAuth → App Link 回 App → 真实车辆 → 刷新 → 退出 401 → 重登，才允许标 `REAL TESLA PILOT PASS`。

---

### 任务依赖图

```mermaid
graph LR
    T01["T01 基础设施与容量门禁<br/>P0-a"] --> T02["T02 共享 PostgreSQL + 历史迁移<br/>P0-a"]
    T01 --> T03["T03 五容器落地与链路自测<br/>P0-a"]
    T02 --> T03
    T03 --> T04["T04 公网入口 DNS/TLS/静态<br/>P0-b"]
    T04 --> T05["T05 云登录 P1 + 运维 P2"]
    EXT1(["外部：sudo 生效"]) -.-> T04
    EXT2(["外部：备案接入 + 安全组 443"]) -.-> T04
    EXT3(["外部：Tesla 应用审核 + 签名指纹"]) -.-> T05
```

---

## 7. root 动作清单（可直接粘贴执行）

> 前提：**Jovi 已确认给 `jourvolt` 开 sudo** → 下面全部动作写进 `deploy/scripts/setup-root.sh`，由 `jourvolt` 用 `sudo` 自动执行。
> 脚本要求：**`set -Eeuo pipefail`、出错即停、可安全重复执行（幂等）、不打印任何密钥值**。

### 7.1 方案 A′（推荐）：宿主机 nginx + `certbot certonly --webroot`

```bash
#!/usr/bin/env bash
# deploy/scripts/setup-root.sh —— 由具备 sudo 的 jourvolt 执行；幂等；出错即停
set -Eeuo pipefail
umask 022

DOMAIN_SELFHOST="${DOMAIN_SELFHOST:-teslalink.joviluma.com}"
DOMAIN_API="${DOMAIN_API:-api.teslalink.joviluma.com}"
DOMAIN_APPLINK="${DOMAIN_APPLINK:-auth.teslalink.joviluma.com}"
ACME_EMAIL="${ACME_EMAIL:-<证书联系邮箱>}"     # 占位符，部署时替换
CERT_NAME="jourvolt"
SKIP_CERT="${SKIP_CERT:-false}"                # 备案未通过时置 true，只做配置与 nginx -t
TS="$(date +%Y%m%d-%H%M%S)"

# ---------- R0 前置检查 ----------
sudo -n true                                   # 非交互 sudo 必须可用，否则整体切方案 B
nginx -v
docker info --format '{{.ServerVersion}}'
grep -n 'default_server' /etc/nginx/conf.d/star-photo.conf \
  && { echo "ABORT: star-photo 占用 80 的 default_server，需人工评审后再加 80 server 块" >&2; exit 1; } \
  || echo "OK: star-photo 未使用 default_server"

# ---------- R0b Docker 镜像加速（可选，受硬约束限制） ----------
if docker info --format '{{.LiveRestoreEnabled}}' | grep -qi 'true'; then
  sudo install -d /etc/docker
  sudo tee /etc/docker/daemon.json >/dev/null <<'JSON'
{
  "live-restore": true,
  "registry-mirrors": ["https://<阿里云ACR加速地址>.mirror.aliyuncs.com"]
}
JSON
  sudo systemctl restart docker                # live-restore 已开启，容器不停
else
  echo "SKIP: live-restore 未开启，放弃镜像加速，避免重启 docker 停掉 star-photo 容器"
fi

# ---------- 护栏：先备份 star-photo.conf ----------
if [[ ! -f "/root/star-photo.conf.bak.${TS}" ]]; then
  sudo cp -a /etc/nginx/conf.d/star-photo.conf "/root/star-photo.conf.bak.${TS}"
fi

# ---------- R4 目录与权限 ----------
sudo install -d -o root -g jourvolt -m 2775 /srv/jourvolt/public
sudo install -d -o root -g jourvolt -m 2775 /srv/jourvolt/public/.well-known
sudo install -d -o root -g jourvolt -m 2775 /srv/jourvolt/public/.well-known/appspecific
sudo install -d -o root -g jourvolt -m 2775 /srv/jourvolt/acme
sudo install -d -o root -g jourvolt -m 0750 /srv/jourvolt-backups
sudo install -d -o root -g jourvolt -m 0750 /etc/jourvolt
if command -v getenforce >/dev/null 2>&1 && [[ "$(getenforce)" == 'Enforcing' ]]; then
  sudo semanage fcontext -a -t httpd_sys_content_t '/srv/jourvolt/public(/.*)?' || true
  sudo restorecon -Rv /srv/jourvolt/public
fi

# ---------- R1 nginx 独立配置 ----------
sudo tee /etc/nginx/conf.d/jourvolt-ssl.selfsigned.inc >/dev/null <<'INC'
ssl_certificate     /etc/nginx/jourvolt-selfsigned.crt;
ssl_certificate_key /etc/nginx/jourvolt-selfsigned.key;
INC
[[ -f /etc/nginx/jourvolt-selfsigned.crt ]] || \
  sudo openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -subj '/CN=jourvolt-placeholder' \
    -keyout /etc/nginx/jourvolt-selfsigned.key \
    -out    /etc/nginx/jourvolt-selfsigned.crt 2>/dev/null
sudo tee /etc/nginx/conf.d/jourvolt-ssl.le.inc >/dev/null <<'INC'
ssl_certificate     /etc/letsencrypt/live/jourvolt/fullchain.pem;
ssl_certificate_key /etc/letsencrypt/live/jourvolt/privkey.pem;
INC
sudo cp -a /etc/nginx/conf.d/jourvolt-ssl.selfsigned.inc /etc/nginx/conf.d/jourvolt-ssl.inc
# 渲染 jourvolt.conf（模板中 %SELFHOST% / %API% / %APPLINK% 三个占位符）
sed -e "s/%SELFHOST%/${DOMAIN_SELFHOST}/g" \
    -e "s/%API%/${DOMAIN_API}/g" \
    -e "s/%APPLINK%/${DOMAIN_APPLINK}/g" \
    deploy/nginx/jourvolt.conf.template | sudo tee /etc/nginx/conf.d/jourvolt.conf >/dev/null

sudo nginx -t                                  # 任何时刻都必须通过（占位证书保证）

# ---------- R2 证书签发（--webroot，不碰任何 nginx 配置） ----------
if [[ "${SKIP_CERT}" == 'true' ]]; then
  echo "SKIP_CERT=true：备案未接入，本步跳过；证书仍是占位自签，不对外提供 443"
else
  sudo dnf install -y certbot
  sudo certbot certonly --webroot -w /srv/jourvolt/acme \
    --cert-name "${CERT_NAME}" \
    -d "${DOMAIN_SELFHOST}" -d "${DOMAIN_API}" -d "${DOMAIN_APPLINK}" \
    --agree-tos -m "${ACME_EMAIL}" --no-eff-email --keep-until-expiring \
    --deploy-hook 'systemctl reload nginx'
  sudo cp -a /etc/nginx/conf.d/jourvolt-ssl.le.inc /etc/nginx/conf.d/jourvolt-ssl.inc
fi

sudo nginx -t && sudo systemctl reload nginx

# ---------- 护栏：确认 star-photo.conf 未被改动 ----------
if ! sudo diff -q "/root/star-photo.conf.bak.${TS}" /etc/nginx/conf.d/star-photo.conf >/dev/null; then
  echo "ALERT: star-photo.conf 被修改，立即回滚并中止" >&2
  sudo cp -a "/root/star-photo.conf.bak.${TS}" /etc/nginx/conf.d/star-photo.conf
  sudo nginx -t && sudo systemctl reload nginx
  exit 1
fi

# ---------- R3 续期 ----------
sudo systemctl enable --now certbot-renew.timer 2>/dev/null \
  || { echo '0 3 * * * root certbot renew --quiet --deploy-hook "systemctl reload nginx"' \
       | sudo tee /etc/cron.d/jourvolt-certbot-renew >/dev/null; }
sudo certbot renew --dry-run

# ---------- R4 备份工具链 ----------
sudo dnf install -y age rclone

# ---------- R5 systemd 定时任务 ----------
sudo install -m 0644 deploy/systemd/jourvolt-backup.service        /etc/systemd/system/
sudo install -m 0644 deploy/systemd/jourvolt-backup.timer          /etc/systemd/system/
sudo install -m 0644 deploy/systemd/jourvolt-memory-watch.service  /etc/systemd/system/
sudo install -m 0644 deploy/systemd/jourvolt-memory-watch.timer    /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now jourvolt-backup.timer jourvolt-memory-watch.timer

# ---------- R6 安全组（只能人工，脚本只做服务端自检） ----------
ss -lntp | grep -E ':(4000|8080|5432|1883|18080|18090)' || true
echo "TODO(人工·阿里云控制台): 放行 443；确认 4000/8080/5432/1883/18080/18090 未放行"
```

**nginx 配置模板要点**（`deploy/nginx/jourvolt.conf.template`，工程师照此实现）：

| server | 内容 |
| --- | --- |
| `listen 80; server_name %SELFHOST% %API% %APPLINK%;` | `location /.well-known/acme-challenge/ { root /srv/jourvolt/acme; }`；其余 `return 301 https://$host$request_uri;` |
| `listen 443 ssl; server_name %SELFHOST%;` | `include conf.d/jourvolt-ssl.inc;`；`limit_req zone=jourvolt burst=20 nodelay`；`location /.well-known/appspecific/ { root /srv/jourvolt/public; }`；`location / { proxy_pass http://127.0.0.1:18080; }` |
| `listen 443 ssl; server_name %API%;` | 同上静态公钥；`location / { proxy_pass http://127.0.0.1:18090; }` |
| `listen 443 ssl; server_name %APPLINK%;` | **静态优先**：`/.well-known/assetlinks.json`、`/terms/`、`/privacy/` 全部 `root /srv/jourvolt/public`；其余 `proxy_pass http://127.0.0.1:18090;` |
| `limit_req_zone $binary_remote_addr zone=jourvolt:10m rate=10r/s;` | 放在 `conf.d/jourvolt.conf` 顶部（http 上下文） |

> **必须**：`proxy_set_header Host $host;`、`X-Forwarded-Proto $scheme`、`X-Real-IP $remote_addr`。
> **静态文件权限坑**：`write-assetlinks.sh` 生成的文件是 `chmod 600`，nginx worker 读不到。
> `publish-static.sh` 拷贝后必须 `chmod 644` 并 `chown root:jourvolt`。

### 7.2 方案 B（兜底，仅当 sudo 不可用时）：Caddy 容器 + TLS-ALPN-01

```bash
#!/usr/bin/env bash
# deploy/scripts/setup-caddy-edge.sh —— 无 sudo 兜底；jourvolt 账户直接执行
set -Eeuo pipefail
umask 022

cd /home/jourvolt/jourvolt-staging
# 只绑 443，绝不碰 80（80 由宿主机 nginx 独占）
docker compose -f docker-compose.pilot.ecs.yml -f docker-compose.caddy-443.yml up -d
docker compose exec -T jourvolt-caddy caddy validate --config /etc/caddy/Caddyfile
# 证书：ACME TLS-ALPN-01（80 不可达时的回落路径），需实测确认
curl -s -o /dev/null -w '%{http_code}\n' https://teslalink.joviluma.com/api/matelink/v1/capabilities \
  -H "Authorization: Bearer ${MATE_LINK_API_TOKEN}"
```

| 对比项 | A′ | B |
| --- | --- | --- |
| root | 需要（已有） | 不需要 |
| 内存代价 | 0 | +20–40 MB（Caddy 容器） |
| 静态资产 | nginx 直出 `/srv/jourvolt/public` | Caddy 直出，需额外挂载 |
| 风险 | 低（certbot 不碰 nginx） | 中（依赖 TLS-ALPN-01 回落实测；后续 nginx 若也要 443 会冲突） |

---

## 8. 备案等待期内，P0-b 每条能做到的程度

| P0-b 条目 | 等待期内可做到 | 等待期内**不能**声称 |
| --- | --- | --- |
| nginx `jourvolt.conf` 落盘 | ✅ 可完整写出，并用占位自签证书使 `nginx -t` 通过 | ❌ 不能 `reload` 生效并宣称"HTTPS 已通" |
| 80 端口 ACME server 块 | ✅ 可落盘并 `nginx -t` | ❌ 不能对外提供（未备案时 80 可能被拦） |
| certbot 签发 | ⚠️ 仅 `certbot certonly --webroot --dry-run` 排练（备案未通过会失败，属预期） | ❌ 不能签发真实证书；`--dry-run` 失败不等于配置错误 |
| 证书续期 | ✅ 可写好 timer/cron 并 `certbot renew --dry-run` | ❌ 没有真实证书时"续期已验证"不成立 |
| DNS A 记录 | ✅ 已指向 `120.55.64.11`；可用公共 DNS 验证解析 | ❌ 解析成功 ≠ 443 可达（备案拦截在解析之后） |
| 安全组 443 | ⚠️ 可先在控制台放行（不影响 star-photo），服务端用 `ss -lntp` 自检 | ❌ 放行 ≠ 公网可达 |
| 静态资产发布 | ✅ `publish-static.sh` 可完整执行，本地 `curl -H 'Host: …' http://127.0.0.1/` 可验证内容 | ❌ 公网 `curl https://…` 在备案前必然失败 |
| 公网自检 `verify-public.sh` | ⚠️ 脚本可写好并跑，预期返回"443 不可达" | ❌ 不得把该结果记为通过 |
| App 真机公网验收 | ❌ 不可做 | ❌ **绝不可**用 SSH 隧道或局域网结果冒称 `PHONE_SMOKE_PASS` |

> **一句话**：等待期内 P0-b 的交付物是**配置 + 脚本 + `nginx -t`/`--dry-run` 证据**，状态一律写 **`BLOCKED_BY_ICP`**，不得写"完成"。
> 同时 P0-a 不受影响，今晚可完整闭环并标注 `LOCAL MOCK PASS`（服务端自测）。

---

## 9. 待明确事项

### 9.1 阻塞 P0（**必须先由用户回答/确认，共 4 条**）

| # | 问题 | 阻塞什么 | 无答案时的默认动作 |
| --- | --- | --- | --- |
| **Q-P0-1** | 源 TeslaMate（Jovi PC）的**授权模式**是什么？`tokens` 表是否有数据？`TESLAMATE_ENCRYPTION_KEY` 能否一并迁移？**源 PostgreSQL 的大版本**是多少？ | T02 迁移、P0-9（能否跳过重新授权）、`POSTGRES_IMAGE` 选型 | 默认按"源库为 PG18、token 可迁移"预备；执行前由 `dump-teslamate-local.sh` 实测确认后再定 tag。**拿不到 key 就走 Plan B，并立即上报 P0-9 转为备案阻塞** |
| **Q-P0-2** | `jourvolt` 的 sudo 是否已实际生效？`sudo -n true` 是否免密？nginx 是否可 `reload`？ | T04 走 A′ 还是 B | 默认按 A′ 出脚本；`sudo -n true` 失败则整体切方案 B，并把 R4/R5 降级到 P2 |
| **Q-P0-3** | 手机首次切到新服务器时，Room 里 190 行程 / 31 充电**会不会被空服务端覆盖**？是否接受"先完成迁移再切换"？ | P0-a 的真机步骤（R-1） | 默认**先迁移后切换**；若必须今晚连，按 R-1 的"记录条数 → 只做一次同步 → 立即复查"执行，条数下降即回退并上报 |
| **Q-P0-4** | `joviluma.com` 的备案接入**预计何时完成**？安全组 443 **是否已放行**？ | 整个 P0-b | 默认 `BLOCKED_BY_ICP`；等待期内只出配置与 dry-run，不宣称完成 |

### 9.2 只阻塞 P1（不阻塞 P0 开工，共 4 条）

| # | 问题 | 阻塞什么 |
| --- | --- | --- |
| Q-P1-1 | Tesla 开发者应用审核状态？已批准 scope 是否含 `openid` / `offline_access` / `vehicle_device_data`？`redirect_uri` 是否已登记为 `https://api.teslalink.joviluma.com/v1/auth/tesla/callback`？注册区域是 `tesla.cn` 还是 `tesla.com`？ | P1-2 / P1-5 / P1-9 |
| Q-P1-2 | 正式 `com.matelink` 签名证书 SHA-256 指纹的来源与归属？`assetlinks.json` 用哪张证书？（现状是"与手机原包一致的本机证书"，非官方发布 keystore） | P1-3 / P1-8 / P1-9 |
| Q-P1-3 | `/terms/`、`/privacy/` 的运营主体与联系渠道由谁提供？（当前仓库文本是待发布版本，未完成法律审核） | P1-7 |
| Q-P1-4 | 异地对象存储的 rclone remote 由谁配置？（凭据写入 `/etc/jourvolt/rclone.conf`，不进仓库与聊天） | P2-1 |

### 9.3 我在本设计中做出的假设（若与事实不符需回评）

1. 源 TeslaMate 使用的是**官方 TeslaMate 镜像**且数据库 schema 可被 `teslamate/teslamate:latest` 向前迁移。
2. Adapter 的 `/api/**` legacy 代理对 App 是**非强依赖**（App 主要走 `/api/matelink/v1/**`）。若 App 强依赖 `/api/v1/cars`，R-2 的优先级从 P1 升到 P0。
3. `star-photo` 的 nginx server **未使用 `listen 80 default_server`**（T04 前置检查会验证；若是，则需人工评审后再决定是否加 80 server 块）。
4. 阿里云 ECS 的安全组与云防火墙不会额外拦截已放行端口（除备案拦截外）。
5. Mosquitto 使用镜像自带的 `/mosquitto-no-auth.conf`（无认证），安全前提是**仅项目内网可达、绝不发布端口**——本设计满足。

---

## 10. 硬约束自检表（QA 逐条打勾）

| # | 硬约束 | 落地点 | 验收命令 |
| --- | --- | --- | --- |
| 1 | 不装 Grafana | 裁剪版服务清单无 `grafana` | `docker compose config --services \| grep -c grafana` → `0` |
| 2 | 不把 4000 / 8080 / 5432 / 1883 发布到公网或宿主机对外地址 | 四个服务均无 `ports:` | `ss -lntp \| grep -E ':(4000\|8080\|5432\|1883)'` → 无输出 |
| 3 | 不动 star-photo：不重启容器、不改 nginx 配置主体 | `setup-root.sh` 的备份 + diff 护栏 | `diff /root/star-photo.conf.bak.<TS> /etc/nginx/conf.d/star-photo.conf` → 无差异；`docker ps --filter name=star-photo` 的 `Up` 时长未被重置 |
| 4 | 新 nginx 配置必须用独立文件 | `/etc/nginx/conf.d/jourvolt.conf` | `ls /etc/nginx/conf.d/` 出现 `jourvolt.conf`，且 `star-photo.conf` 内容未变 |
| 5 | 秘密只用占位符与键名；`.env` 权限 600 | 全部脚本与 `.env.selfhost.example` | `stat -c '%a %U' /home/jourvolt/**/.env` → `600 jourvolt`；`git check-ignore` 命中 |
| 6 | 只用 Let's Encrypt 免费 DV | `certbot certonly --webroot` | `openssl x509 -noout -issuer` 含 `Let's Encrypt` |
| 7 | 不 stage / commit / push | 全程 | `git status --porcelain` 新增项仅限 `deploy/**` 新增文件 |
| 8 | 不改 App 业务代码逻辑 | 本设计只涉及配置/脚本/部署物/构建参数 | `git status --porcelain -- android/ web_matelink/` → 无改动 |
| 9 | 证据分级不混用 | 各任务标注 | P0-a 标 `LOCAL MOCK PASS`；P0-b 未备案时标 `BLOCKED_BY_ICP`；P1 真机未过 Tesla 授权前最高 `PHONE_SMOKE_PASS` |
| 10 | 内存纪律 | 五容器 `mem_limit` = 700 MB；准入 `available ≥ 800 MB` | `capacity-gate.sh` → `GATE=PASS`；`docker stats --no-stream` RSS 之和 ≤ 480 MB |

---

## 11. 附：Adapter 快照数据流（供工程师理解链路）

```mermaid
sequenceDiagram
    participant App as MateLink App
    participant Nginx as nginx :443
    participant Adp as matelink-adapter :18080
    participant Mq as mosquitto
    participant Db as postgres :5432
    participant Tma as teslamateapi :8080

    App->>Nginx: GET /api/matelink/v1/cars/1/snapshot<br/>Authorization: Bearer <token>
    Nginx->>Adp: http 127.0.0.1:18080（TLS 在 nginx 终止）
    Adp->>Adp: authenticate() 校验 Bearer
    Adp->>Db: SELECT positions JOIN cars LEFT JOIN states<br/>ORDER BY date DESC LIMIT 1
    Db-->>Adp: 快照行（battery/odometer/geo/TPMS/state）
    alt MQTT_ENABLED=true 且 120s 内有新鲜消息
        Adp->>Mq: 订阅 teslamate/cars/+/+（后台常驻）
        Mq-->>Adp: locked / plugged_in / charging_details 等
        Adp->>Adp: mergeSnapshot(base, live)
    else MQTT 不可用
        Adp->>Adp: PostgreSQL fallback（locked/doors/charging 标 unavailable）
    end
    Adp-->>Nginx: 200 { data: snapshot }
    Nginx-->>App: 200 HTTPS

    App->>Nginx: GET /api/v1/cars（legacy）
    Nginx->>Adp: 127.0.0.1:18080
    Adp->>Tma: 反代（同项目内网，8080 不发布）
    Tma->>Db: 查询车辆
    Tma-->>Adp: JSON
    Adp-->>App: 200（见风险 R-2）
```
