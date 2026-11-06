---
title: GPT-5.6 Commander Handoff
project: Tesla MateLink MIMO / app_mimo
updated: 2026-07-16
audience: GPT-5.6 web commander and Codex execution agent
status: active; implementation code changes require Jovi approval
---

# GPT-5.6 指挥官交接：Tesla MateLink MIMO

## 0. 使用方式与指挥边界

你是 GPT-5.6 网页版，负责做产品/技术指挥；Codex 是连接本机仓库、Android 设备、Docker 和 Obsidian 的执行代理。你不能假定自己能读取本机文件或运行命令：请把可验证、范围明确的指令交给 Codex 执行。

每一条指令必须说明目标、允许范围、验收标准和禁止范围。不要要求无证据地“完成”或“全量修复”。任何仓库内的非文档写入（实现代码、Gradle/Compose/Docker 配置、脚本、测试、数据库迁移）均须先让 Codex 向 Jovi 列出精确改动范围并等待明确授权；`git add`、`git commit`、`git push` 还需要分别授权。文档和 Obsidian 写入也须由 Jovi 在该轮任务中明确要求。

默认写入根仅限 `E:\project\tesla_master\app_mimo`；父仓库 `AGENTS.md` 和 Obsidian 项目记忆是允许读取的外部例外，除非 Jovi 明确要求，否则不得修改父仓库或其他目录。安装/卸载 APK、清除 App 数据、保存或故意写入错误 token 都会改变手机状态，必须在执行该动作前获得 Jovi 当轮确认，并在错误态测试后恢复有效连接。

当前首要原则：先把已有 Android 候选版本完成真机真实数据验收，再决定是否进入下一代码包。不要重复已解决的崩溃、同步、Adapter、驻车详情或缺失数据展示工作。

## 1. 产品目标与完成定义

### 产品目标

MateLink 是一个以 Android 为主、iOS 跟进、Web 辅助的 Tesla 监控与可信分析产品。它连接用户自托管的 TeslaMateApi/兼容 API，不直接登录 Tesla。用户应能在自己的手机上安全配置服务器，看见真实车辆状态、行程、驻车、充电和分析数据。

### MVP 成功定义

1. 从干净安装开始，用户可填写正确 API Root 和 API token，测试并保存连接。
2. Dashboard、行程、驻车详情和充电页展示服务端真实数据，来源与不可用状态清晰；不把缺失值伪造成 `0`、`false` 或免费。
3. 刷新、后台同步、冷启动、错误 token、服务器不可达和无数据时均不崩溃，并给出可理解提示。
4. Debug/Release 构建、数据库迁移和真机验收均有可重复证据。
5. 完成后才进入小范围 Beta、签名 AAB、隐私/数据安全说明和商店发布。

产品目前还不能称为“全量真实数据已交付”：Android 真机真实数据验收、手动充电费用持久化和发布基线尚未完成。

## 2. 当前仓库与运行环境

| 项目 | 当前事实 |
|---|---|
| 本地独立仓库 | `E:\project\tesla_master\app_mimo` |
| 父协调仓库 | `E:\project\tesla_master`，与本仓库独立 Git 管理 |
| 当前分支 | `codex/app-mimo-data-setup` |
| 本地提交基线 | `7f01e4d`（相对对应远端分支 ahead 1） |
| app_mimo Git 远端 | `https://github.com/Jovifei/tesla-master-mimo.git` |
| 父仓库 Git 远端 | `https://github.com/Jovifei/Tesla_MateLink.git` |
| 工作树 | 有大量未提交候选改动；必须保留，不可 reset/checkout/覆盖 |
| Docker 部署目录 | `deploy/teslamate-home-docker` |
| 本机 Adapter API Root | `http://127.0.0.1:8080` |
| 当前手机 LAN API Root | `http://192.168.0.104:8080`，动态 IP，每次使用前重查 |
| TeslaMate Web | `http://127.0.0.1:4000`，不是当前 App API Root |
| Grafana | `http://127.0.0.1:3000`，不是当前 App API Root |
| 公网 API Root | 当前没有已证明、可用的公网地址；文档中的 example.com 均为示例 |

API Root 只填写根地址，不追加 `/api/v1`。手机端 token 必须与 `deploy/teslamate-home-docker/.env` 的 `MATE_LINK_API_TOKEN` 值相同；只能记录键名和路径，禁止在文档、聊天或 Git 写入 secret 值。

## 3. 已完成且已核验的事实

### 已在本地提交基线或当前候选工作树中完成

- 同步分页保护、详情聚合持久化和 Dashboard/Drives 基础改进已在 `7f01e4d` 中。
- Go `matelink-adapter` 已位于 `deploy/teslamate-home-docker/adapter`，并通过 Docker 暴露：
  - `/api/matelink/v1/capabilities`
  - `/api/matelink/v1/cars/{carId}/snapshot`
  - `/api/matelink/v1/cars/{carId}/parked/{olderDriveId}/{newerDriveId}`
- Android 已有 Adapter DTO/Repository、Dashboard Adapter 快照优先逻辑、驻车详情导航、Room v13 行程能耗来源/覆盖率持久化、紧凑的行程/充电详情头部，以及缺失值诚实展示。
- 已有费用规则内核 `EffectiveChargeCostResolver`：手动金额 > 明确免费 > TeslaMate 正数金额 > 1.10 元/度估算。它尚未有 Room 持久化的用户编辑入口。

### 2026-07-16 复验结果

- Docker 中 database、TeslaMate、TeslaMateApi、mosquitto、Grafana 与 `matelink-adapter` 正在运行。
- Adapter capabilities 在 `127.0.0.1:8080` 和 `192.168.0.104:8080` 均返回 HTTP 200（带有效 Authorization，未记录 token 值）。
- `go test ./...` 通过。
- Docker Compose 配置校验通过。
- Android `:app:testDebugUnitTest` 通过：43 tests、0 failures、0 errors。
- Android `:app:assembleDebug` 通过；APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。
- `git diff --check` 通过。

这些结论仅证明当前候选代码和服务端链路可用；不能替代真机真实数据体验验收。

## 4. 当前未完成项、风险与优先级

### P0：真机真实数据验收（下一步，不改代码）

当前没有 Android 设备连接，因此尚未证明手机端已保存 token、Dashboard 已显示真实数据、驻车详情导航已在实机生效。

执行前提：Jovi 连接 Android 设备并保持解锁，手机与部署机器同一局域网。

验收动作：

1. 从当前 APK 安装或清数据后启动 App。
2. 在 Settings 的 Advanced Network 填 `http://192.168.0.104:8080`（执行前重查 IP）。
3. 填入部署端同一 token，点击 Test Connection 后 Save。
4. 验证 Dashboard 的电量、续航、里程和胎压；验证行程、驻车详情和充电页。
5. 连续进行冷启动、刷新、后台同步；抓取 logcat，检查无 FATAL EXCEPTION、无 HTTP 401、无虚假零值。
6. 分别测试错误 token、服务端不可达和无数据状态，确认可理解的提示和稳定退化。失败态默认只可使用已获批准的 App 侧错误 API Root/token 或既有空数据样本；不得停止/重启容器、修改主机网络、删除或写入服务端数据。任何部署、网络或服务端运行状态变更必须先获得 Jovi 当轮明确授权。
7. 在 Jovi 明确授权文档写入后，将脱敏截图、命令结果摘要、日志摘要和结论写为新的验收报告，并更新 `tasks/todo.md` 与 Obsidian 记忆。

P0 通过标准：20 次冷启动、10 次手动刷新、5 次后台同步零崩溃；服务端与 UI 核心数值可对照；失败态可理解；证据归档。

证据脱敏规则：截图不得显示 Settings token；日志与报告不得含 Bearer token、Authorization 头、精确家庭/行程位置、个人名称、设备序列号或车架号。默认不落盘保存原始敏感证据，只保存脱敏摘要；如确有原始证据需要，先由 Jovi 批准精确的非 Git 路径、最短保留期与删除责任，且绝不把内容写入 Git、Obsidian 或交接文档。

### P1：充电费用手动覆盖（代码授权后才做）

目标：增加独立 `charge_cost_overrides` Room 表，持久化“手动金额”和“明确免费”；让列表、详情、统计读取最终费用，但不让同步覆盖用户输入。

建议边界：新建覆盖实体/DAO/Repository；在 `StatsDatabase` 中加入迁移；接入 Charges/ChargeDetail ViewModel 与 UI；重用 `EffectiveChargeCostResolver`；添加迁移、规则和 UI/状态测试。不得在此包扩大到地图、Sentry、MQTT 重构、iOS 或 Web。

验收标准：编辑后立即生效；重启、刷新、同步后保留；四级费用优先级正确；Room migration 与 Android 单测通过。

### P2：发布基线与 Beta

1. 审查当前未提交文件，排除 `android/.kotlin`、IDE 状态、`.env` 与构建缓存。
2. 在 Jovi 分别授权后暂存、提交和推送精确文件集。
3. 生成并测试签名 Release AAB；使用真实手机和至少一类不同屏幕规格验证。
4. 先进行内部测试，再进行封闭 Beta；持续记录真实用户连接、同步、崩溃、ANR 和耗电。
5. 再准备隐私政策、数据安全声明、商店图文、支持渠道和部署说明。

## 5. 禁止混淆或重复处理的事项

- 用户最初给出的会话 ID `019f559c-f053-7150-853a-dbf5c6a04a1d` 属于 `E:\project\jovi-automation`，不能作为 app_mimo 历史混入。
- app_mimo 对应的历史交接会话为 `019f4294-2f33-7502-b705-62e49d238256`；以当前仓库和运行状态复核其结论。
- Adapter、Android 快照接入、驻车详情、紧凑详情头、能耗来源持久化和缺失值诚实展示已存在，不要重新实现。
- 旧的 HTTP 401 是历史真机未保存 token 的现象；只有现场再次复现时才作为当前故障处理。
- 不把 GitHub 远端、TeslaMate Web `:4000`、Grafana `:3000` 或示例域名填为 App API Root。

## 6. 关键资料与记忆入口

### 仓库内资料

- 任务/执行台账：`tasks/todo.md`
- 经验与授权规则：`tasks/lessons.md`
- 最近修复报告：`docs/BUG-REPAIR-REPORT-2026-07-11.md`
- 最近实施计划：`docs/superpowers/plans/2026-07-11-app-mimo-crash-ui-data-repair.md`
- 数据分析设计：`docs/superpowers/specs/2026-07-11-vehicle-data-analytics-design.md`
- 页面核对台账：`docs/STITCH_PAGE_MAPPING.md`
- 数据目录：`docs/DATA-CATALOG.md`
- 用户部署说明：`docs/USER-DEPLOYMENT-SETUP-GUIDE.md`
- Docker 配置：`deploy/teslamate-home-docker/docker-compose.yml`
- Adapter 入口：`deploy/teslamate-home-docker/adapter/cmd/adapter/main.go`
- Android 数据入口：`android/app/src/main/java/com/matelink/data/api/TeslaMateApi.kt`、`android/app/src/main/java/com/matelink/data/repository/TeslamateRepository.kt`
- Android Dashboard 入口：`android/app/src/main/java/com/matelink/ui/screens/dashboard/DashboardViewModel.kt`
- 费用规则：`android/app/src/main/java/com/matelink/domain/analytics/EffectiveChargeCostResolver.kt`

### Obsidian 持久记忆（每次任务先读）

根目录：`E:\AI_Tools\Obsidian\Data\notes-personal\codex_memory\03-项目记忆\tesla-speed\tesla-master-app-mimo`

- `00-项目概览.md`：目标与阶段判断。
- `01-总体计划.md`：里程碑、范围与不扩展边界。
- `02-当前进度.md`：当前分支、已验证证据、下一步。
- `03-关键决策.md`：数据真实性、授权、独立仓库等不可逆决策。
- `04-工作流与知识.md`：恢复顺序、地址规则、Git 与授权规则。
- `05-地址与证据索引.md`：本地路径、Git 远端、服务地址与动态地址检查清单。

证据优先级：当前仓库/运行状态 > `tasks/todo.md` 和最新报告 > 上述 Obsidian 记忆 > 历史会话。IP、设备连接、Docker 状态、Git 分支和测试结果都是动态事实，必须每次现场复核。

## 7. 对 Codex 的工作规则

1. 开始任何非平凡任务先读 `E:\project\tesla_master\AGENTS.md`、`tasks/lessons.md`、`tasks/todo.md` 和上述 Obsidian 记忆。
2. 搜索/探索 Android 代码先使用项目代码关系图；关系图缺失时再搜索精确路径。
3. 保留脏工作树；不使用 destructive Git 命令，不覆盖既有改动。
4. 仓库内的非文档写入（代码、配置、脚本、测试、迁移）前，Codex 必须先向 Jovi 请求授权并列出精确文件范围与验证方式；文档/Obsidian 写入也须有本轮明确要求。
5. 只有在 Jovi 授权相应文档写入后，才更新 `tasks/todo.md` 的计划、证据和 review，并同步 Obsidian 当前进度/决策/地址记忆。
6. 只有通过实际测试、构建、运行日志或真机证据，才能宣称完成。
7. 没有 Jovi 的明确授权，不暂存、不提交、不推送；永不提交 `.env` 或任何 secret。

## 8. 建议 GPT-5.6 下达的第一条指令

将下列文字原样或按需微调后发给 Codex：

> 以 `E:\project\tesla_master\app_mimo` 为唯一写入根，先只读读取父目录 `AGENTS.md`、本仓库 `tasks/lessons.md`、`tasks/todo.md` 与 Obsidian 的 `00` 至 `05` 项目记忆。不要修改任何仓库文件、配置、Git 状态或 secret。请先核对当前 Git/Adapter/Docker/ADB 状态；若没有 Android 设备连接，简洁告诉 Jovi 连接设备并保持解锁。设备可用后，先向 Jovi 请求当轮设备状态变更授权，明确安装/清数据/保存 token/错误 token 测试的动作和恢复计划；获准后再执行 P0 真机真实数据验收。只保存脱敏证据，未经明确文档授权不得写报告、台账或 Obsidian。仅当发现需要修复的实现问题时，先报告根因、精确文件范围、最小方案与验证计划，等待 Jovi 明确授权后再改代码。不要扩大到费用覆盖、地图、Sentry、iOS、Web、暂存或提交。

## 9. 指挥官应在 P0 结束后如何决策

- P0 通过：请 Codex 列出 P1 充电费用覆盖的精确文件集、数据库迁移方案和测试清单，向 Jovi 请求代码授权。
- P0 失败但可定位：请 Codex 只提出最小修复包，等待 Jovi 授权；不可顺带重构。
- P0 因网络、token 或设备阻塞：请 Codex 输出精确阻塞证据和用户操作，不把它伪装成代码故障。
- P1 通过：再安排候选改动审查、Release AAB、内部/封闭 Beta 和发布材料。

## 10. 交接文档自身的维护规则

每当以下任一事实变化，Codex 必须先报告拟更新内容；仅在 Jovi 当轮明确授权文档/Obsidian 写入后，才更新本文件和 Obsidian `02-当前进度.md`、`05-地址与证据索引.md`：当前分支/提交、候选代码范围、服务地址、设备验收、测试结果、下一优先级、授权状态。

不得把本文件当成静态计划；它是未来指挥和执行之间的事实合同。
