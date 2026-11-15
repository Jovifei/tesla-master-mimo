# MateLink / JourVolt 进度与缺口盘点（2026-08-28）

本文只做状态盘点，不改代码、不改配置、不提交 Git、不触碰服务器与域名。
证据优先级：当前仓库 on-disk 状态 > `tasks/todo.md` > Obsidian 记忆。

## 0. 一句话结论

代码侧 App 本地接入已经收口到「真机 UI + 自托管数据 + 本地 Mock 全绿」，
距离「真实 Tesla 单车 Pilot」**只剩外部门禁 + 一批本地遗留**，
而外部门禁目前**零项具备**（域名今天实测仍为 NXDOMAIN）。

---

## 1. 当前 on-disk 真实状态（2026-08-28 现场复核）

| 项 | 实测值 |
| --- | --- |
| 仓库 | `E:\project\tesla_master\app_mimo` |
| 分支 | `codex/matelink-jurvolt-release-20260823` |
| HEAD | `ec6c7390f1fc9743e5147a8298c22ccc64ac63f2`（2026-08-23 12:46） |
| 远端 | `origin/codex/matelink-jurvolt-release-20260823` 与 HEAD 同 commit（**已推送**） |
| 未提交改动 | `git status --porcelain` 共 **81** 项（修改 + 未跟踪） |
| 最新本地门禁 | Debug/Release JVM 各 **322** 项，`0 failures / 0 errors / 8 skipped`；`lintRelease` 0 Error、204 Warning、`MissingTranslation=0`、无 baseline |
| 实体机 | OnePlus 7 Pro `6e4fa92f`，同签名 `adb install -r` 覆盖成功 |
| 云服务器 | 阿里云 ECS `120.55.64.11`，jourvolt-staging 容器 healthy，`mode=mock_only`，API 仅绑 `127.0.0.1:18090` |
| 域名 | `api.jourvolt.com` / `auth.jourvolt.com` 今日 `nslookup` 均 **Non-existent domain**（此前为 `198.18.0.x` fake-IP） |

**关键偏差**：`ec6c739` 是 8/23 12:46 的提交，之后 5 天的全部工作
（珍珠电驱 v48/v49、8/24 阿里云 staging、8/27 MQTT + 状态化 + 原创车型图、8/28 TPMS 计划与 Task 1 代码）
**全部只存在于工作树，未 stage / 未 commit / 未 push**。

---

## 2. 已完成且有证据

### 2.1 App 主线结构

- 单一正式包 `com.matelink`，原 Dashboard、底部四导航、Settings、完整本地数据链路保留。
- 运行时 `TESLA_CLOUD` / `SELF_HOSTED` 双模式；旧自托管配置兼容并可迁移恢复。
- 中英双语，`MissingTranslation=0`，无 lint baseline。
- 货币统一：新装默认 CNY，旧配置兼容 EUR，报告/PDF/成本/统计统一读当前货币。

### 2.2 JourVolt 云链路（代码全部 ready，未经真实 Tesla 验证）

- 官方 OAuth：Custom Tab 打开 Tesla 授权页，App Link ticket 交换进入原 Dashboard。
- URL 安全收口三层：官方 HTTPS 端点 + 最小 scope（v21）、`redirect_uri` 必须回 JourVolt host 的 `/oauth/callback`（v24）、服务端 `loadTeslaConfig` 强制回调路径并拒绝用户信息/query/fragment/非 443 端口（v25）。
- 登录态：session 交换、refresh 原子轮换、注销撤销、旧 token 401、请求代次隔离（v13/v14）。
- 文案资源化：登录取消/暂不可用/交换失败三条 fallback 已中英文化（v22/v23）。
- 服务端：OAuth state/nonce、一次性 ticket、AES-GCM 加密 token/VIN、PostgreSQL 行锁 refresh、Fleet 只读 Provider，无配置时 fail-closed（`503 oauth_not_configured`）。

### 2.3 本地 Mock 与部署物

- `deploy/jourvolt-dev-mock`：Go + PostgreSQL Compose，`smoke.ps1` 实测 1 车 / 18 行程 / 5 充电 / logout revocation 通过（`LOCAL MOCK PASS`）。
- 无密钥 bundle v36/v37：`secrets_included=false`，敏感条目 0，Go test/vet 通过。
- `preflight.ps1` 对示例配置按预期返回 `PREFLIGHT=FAIL`（缺失 Tesla 配置 / 示例域名 / token key / App Link）。
- 阿里云 ECS staging（8/24）：非 root `jourvolt` 账户、容器 healthy、`/healthz` 与 `/readyz` ok、SSH 隧道 smoke 通过；修复 `GOPROXY=https://goproxy.cn,direct`。

### 2.4 数据分析与诚实性

- Stats 综合分析卡：使用模式、充电损耗、电池趋势曲线、日均驾驶里程、派生/估算/观测三类证据标签与样本量。
- 里程钻取：`StatsScreen → NavGraph → Screen.Mileage` 年→月→日→行程层级。
- 零值保真：缺失值显示 `N/A`，不降级为 0；Locale 显式化；`DefaultLocale`/`ConstantLocale` 清零。
- 费用：Room v16 `charge_cost_overrides` + 15→16 迁移测试通过，手动金额 > 明确免费 > TeslaMate 正数 > 1.10 元/度估算。

### 2.5 真机证据（PHONE_SMOKE_PASS，不含真实 Tesla）

- v44 同签名覆盖安装 + 旧自托管迁移修复；v45 局域网 HTTP + AMap R8 keep；v47 Release 底部导航路由归一化；v49 珍珠电驱签名包真机回归。
- 实体统计页真实历史数据：190 行程 / 1,600 km / 289 kWh / 181 Wh/km / 31 充电 / 512 kWh，费用与距离 `N/A`（成本输入不可用，属正确保真）。

### 2.6 状态化数据与视觉（8/27，未提交）

- Adapter 接入 Paho MQTT v1.5.1、默认/命名空间主题、120 秒来源分类、PostgreSQL 降级。
- Android 状态模型保留零值/false/小数；Dashboard 按驾驶/开口/胎压增量显示；当前充电页增加充电口、相数、电压电流、请求电流与计划时间。
- Canvas 原创 Model 3/Y/S/X 轮廓（无 Logo、未使用官网图片）。
- Debug 状态验证页 + 五状态 × 360/412 × 中英 × 明暗 × 100/200% 视觉矩阵全部通过。

---

## 3. 进行中：TPMS 趋势与行程通知（今日 8/28 新建计划）

计划文件：`tasks/plans/2026-08-28-tpms-trends-trip-notifications.md`
`tasks/todo.md` 中 Task 1–5 **全部未勾选**。

| Task | 计划内容 | 磁盘实况 |
| --- | --- | --- |
| 1 | Room v17 TPMS 样本 + 每车阈值 + 纯分析器 | 代码文件**已生成**（Entity / Dao / Repository / Analyzer / 两个测试 / `schemas/17.json`），但 **todo 未勾选、无测试通过证据** |
| 2 | Worker 采样写入 + 阈值转换通知 | `TpmsTrendNotificationManager.kt` **缺失**，Worker 未改 |
| 3 | 7/30 日四线趋势 UI + 阈值设置 | `ui/screens/tpms/*` **缺失**，Dashboard 入口未接 |
| 4 | 新完成行程一次性通知 | `TripNotificationManager.kt` / `TripNotificationStateStore.kt` **缺失** |
| 5 | 全量门禁 + 独立 Debug 通知验证 | 未开始 |

注：Task 1 相关文件时间戳为今日 19:24–19:51，与本机另一进行中的实现会话重叠，**测试结论以复跑为准**。

---

## 4. 还差什么（按能否由代码解决分层）

### 4.1 代码侧可在本地继续完成（无外部门禁）

1. **TPMS Task 2–5**：通知、趋势 UI、行程通知、全量门禁与真机通知验证。
2. **实体机状态化 UI 回归**：当前只有历史快照，驾驶 / 开口 / TPMS 告警 / 充电中四类状态**没有可观测实体数据**，不能算设备通过。
3. **专用 AVD 回归**：Pixel 5 API 35 AVD 能完成 Android boot（17.9 s），但 ADB 未注册、5560/5561 未监听，属宿主 Emulator↔ADB 通道阻塞，非 App 缺陷。
4. **Git 收口**：81 项未提交改动需要 Jovi 分别授权 stage / commit / push（默认 no-push）。
5. **记忆同步**：见第 5 节。

### 4.2 外部硬门禁（代码无法替代，当前 0/6 具备）

| # | 门禁 | 当前状态 |
| --- | --- | --- |
| 1 | 可控 DNS 域名 | ❌ 今日 `nslookup` NXDOMAIN |
| 2 | Tesla 中国开发者应用审核 + `openid` / `offline_access` / `vehicle_device_data` | ❌ 未申请/未批准 |
| 3 | 公网 HTTPS callback + 域名公钥注册 | ❌ 依赖 1、2 |
| 4 | 正式签名证书 + `/.well-known/assetlinks.json` | ⚠️ 目前用与手机原包一致的本机证书，非官方发布 keystore |
| 5 | 服务器采购 + ICP/App 备案 | ❌ 预算门禁未满足（首年 ≤600 元；阿里云 2C4G/40G/2M 官方约 1733.04 元/年） |
| 6 | 真实单车 OAuth/Fleet Pilot 验收 | ❌ 依赖 1–5 |

顺序固定：`域名 → Tesla 批准 → 公网 HTTPS/App Link → 服务器私密 .env → preflight → edge profile → 构建正式签名 APK → 真实单车验收`。
未取得 `REAL TESLA PILOT PASS` 前，任何证据只能标 `LOCAL MOCK PASS` 或 `PHONE_SMOKE_PASS`。

---

## 5. 记忆与仓库的偏差（建议尽快修正）

- Obsidian `tesla-speed/tesla-master-app-mimo/02-当前进度.md` 最新条目为 **2026-08-24 阿里云 staging**，
  **缺 8/26–8/28 的 MQTT 状态化、原创车型图、TPMS 计划与 Task 1 代码、以及本次盘点**。
- Obsidian `tesla-speed/00-项目概览.md` 顶部权威状态仍停在 **Stage J（2026-08-11）**，未含 Stage K Round-2（8/21）与 Stage W（8/26–8/27）。
- 仓库 `tasks/todo.md` 权威，最新条目为 8/27；TPMS 五个 Task 未勾选。
- 本工作区 `.workbuddy/memory/` 此前为空，本次开始记录。

---

## 6. 附：S12 引擎声浪（tesla-speed 主线）当前位置

固定顺序：`Python Realism → Automatic Qualification → Human Audition → Profile Freeze Review → Jovi Explicit Approval → Approved Profile → Simulink Productization → Runtime → Android/ESP32`。

- 已完成：Stage W（`agent/s12-stage-w-ecosystem-bakeoff`，推送在 `b9a1111f...`）的 W0 独立审计、W3 冻结 PTR 桥、W4 waveguide、W5 localized afterfire、W6 真 20s/60s Bake-Off、W9 诊断收口；完整 S12 Python `1000 passed / 1 skipped`、Track-P guard `180 files / 2 symbols` + `32 passed`。
- 卡点：`REFERENCE_TARGET_MISSING`。R1 intake 仍缺 `sha256.txt`、原始 WAV/FLAC、同步 RPM/load/gear；P4 等版权授权录音源，P6 仅 teacher-only。
- 因此：**不做架构选型、不做 OEM 声明、不进入 Profile Freeze**，也没有 Human PASS。

---

## 7. 建议的下一步优先级

1. 先让 TPMS Task 1 拿到可复现的测试通过证据，再推进 Task 2–5（授权范围：仅本地 Android，不动服务端/网络配置）。
2. 单独授权一次 Git commit，把 8/23 之后 5 天的工作固化（避免继续累积 81 项未提交）。
3. 补齐 Obsidian 记忆到 8/28（8/24 之后的部分目前只存在于仓库）。
4. 外部项按第 4.2 节顺序单项推进，先解决域名真实 A 记录，其余全部依赖它。
