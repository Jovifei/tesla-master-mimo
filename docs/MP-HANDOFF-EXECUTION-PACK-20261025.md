# 小程序复刻交接执行包（MP-HANDOFF-EXECUTION-PACK-20261025）

> 本文档是给**执行 AI** 的完整任务书。制定者已完成调研与可行性核实；所有 wire 形状、行号、命令均为 2026-10-25 实测。执行前通读 §0 纪律，违反任何一条即停止。

## 0. 固定基线与纪律（不可变更）

| 项 | 值 |
|---|---|
| App 参照（**只读**） | `E:\project\tesla_master\app_mimo` @ `3665155a`（2.1.51/build70）。禁止修改其中任何文件 |
| Mini 基线 | `5aa336c5`（PR #25 头） |
| 当前进度头 | `56ecebdb`（含仪表盘对齐、驻车卡+详情、充电轮询、免费费用、mock 数据集、审查修复 6 项） |
| 工作分支 | `codex/mp-dashboard-parity-20261025` |
| 展示分支（Jovi 审核用） | `codex/tesla-auth-devtools-fallback-20261024`（已同步至 56ecebdb） |
| 唯一工作区 | `E:\Claude_allow\Download\mp-parity-wt-20261025` |

### Git 操作强制约定

子模块 worktree 的 git 根解析有已知 bug（`git -C` 与裸 `git status` 会把 gitdir 当工作树，见仓库 `tasks/lessons.md`）。**所有 git 命令必须显式绑定**：

```bash
export GIT_DIR=/e/project/tesla_master/.git/modules/app_mimo/worktrees/mp-parity-wt-20261025
export GIT_WORK_TREE=/e/Claude_allow/Download/mp-parity-wt-20261025
```

禁止事项：改动原 E 工作区（`E:\project\tesla_master\app_mimo` 的主 checkout）；触碰 `E:\Claude_allow\Download\mp-parity-build70-20261025`（Codex 并发目录，内有其裸仓库文件）；reset/clean/删除任何 worktree；改系统网络栈（含 WinNAT 端口保留段）；控制手机；未经 Jovi 安排推送预览或合并 main。

推送展示分支必须带 lease 锁定已知基点，例：
```bash
git push --force-with-lease=refs/heads/codex/tesla-auth-devtools-fallback-20261024:<已知基点SHA> origin <新SHA>:refs/heads/codex/tesla-auth-devtools-fallback-20261024
```

## 1. 环境与命令（Windows Git Bash）

```bash
cd /e/Claude_allow/Download/mp-parity-wt-20261025/miniprogram
npm test                      # 全量 Vitest（当前 718 项；经 test-tooling/vitest.config.mts）
npm run typecheck             # tsc --noEmit，必须零错误
npm run build:weapp           # Taro 生产构建 + 车辆资源完整性校验
```

- `node_modules` 与 `test-tooling/` 已从主工作树复制就位；直接 `npx vitest run` 会失败（入口/config 不匹配），务必用 `npm test`。
- 构建域名经 `.env` 注入（gitignore 内，不入库）。审核构建 `.env` 两行：
  ```
  TARO_APP_API_BASE_URL=https://api.teslalink.joviluma.com/
  TARO_APP_MOCK_FIXTURE=true
  ```
  回归构建只留第一行（开关关）。构建后核验：`grep -o 'api.teslalink.joviluma.com' dist/common.js`。
- gh CLI 未登录；PR 评论用 git credential 取 token 调 GitHub API（历史会话有先例脚本）。

## 2. 阶段 A：补齐 mock 分析端点

改动限 `miniprogram/src/services/mock_fixture.ts`（路由函数 `mockRequest`）+ me 页一行提示。**先读解析代码再造形状**：

### A1 battery-health
- 解析：`src/services/analysis_api.ts` `getBatteryHealth`（约 94-105 行）：读 `data.battery_health`（对象或 null），字段 `max_range`/`current_range`/`max_capacity`/`current_capacity`/`rated_efficiency`，且有 `assertVehicle(health, car.id, car)` 断言（按 car 断言字段如 `car_id`/`vehicle_uid` 补齐——执行时从 `assertVehicle` 实现确认所需键）。
- 路由：`GET /api/v1/cars/{id}/battery-health` → `{ data: { battery_health: { …断言字段, max_range: 298, current_range: 289, max_capacity: 60, current_capacity: 59.6, rated_efficiency: 142 } } }`（单位 km/Wh-km；`units.unit_of_length:'km'` 若解析读取）。

### A2 updates（软件更新）
- 解析：`getUpdates`（约 106-111 行）：`data.updates` 数组 ≤500，每项 `{update_id, version, start_date, end_date}`，同样过 `assertVehicle`。
- 数据：3 条版本（2026-08-12 `2026.26.3`、2026-09-15 `2026.32.1`、2026-10-08 `2026.38.2`），端点带 `?page=N&show=20`，第 2 页返回空数组（空页行为）。

### A3 standby（待机能耗窗口）
- 解析：`getStandby`（约 178-182 行）：`data.windows` 数组 ≤500，每项过 `assertVehicle` 后交给 `qualifyParked(row, data)`（约 58-76 行）——**字段名逐一对照 qualifyParked 读取的键**（已知读 `linked_charge.charge_id`、`source`、`reason` 等；其余从实现抄）。
- 数据：复用生成器 `planned` 的相邻行程对，挑时长 ≥60 分钟的窗口 8-12 个；`units` 按 qualifyParked 期望补。

### A4 history-context
- 解析：`getHistoryContext`（约 184-188 行）：按其返回 `{carId, stableId, capabilityVersion}` 的实际读取键造值（`car.id`=7、stableId=现有 `mock-vehicle-uid-7`、capabilityVersion 取解析允许的最新值）。

### A5 me 页提示（不改授权链）
`src/pages/me/index.tsx` 顶部加：仅当 `MOCK_FIXTURE_ENABLED`（从 `services/mock_fixture` 导入）时渲染一行 `<Text className="muted">合成数据模式——账号状态为模拟，授权流程不可用</Text>`。授权状态走 `rawRequest`+本地 pending（无 pending 时直接返回 none，不发网络），**不得拦截或重写**。

### A6 合同测试
`src/services/mock_fixture.test.ts` 新增 4 项（每端点 1 项）：battery-health 数值断言、updates 分页含空页、standby 窗口形状与时长范围、history-context 键值。

## 3. 阶段 B：「更多」页对齐 App

1. 只读参照：App `3665155a` 的更多页源码（从 `android/.../ui/navigation/NavGraph.kt` 找「更多」路由 → 对应 Screen；入口文案取 `res/values-zh/strings.xml` 同词）。
2. 现状：Mini `src/pages/me/index.tsx`（299 行）已有：登录/会话、设置、法务、数据状态说明。
3. 改动：入口清单、顺序、命名对齐 App（数据状态 + 数据分析入口：统计概览/电池健康/里程统计/行程历史/长途旅程/能耗/费用分析/续航分析/待机能耗/时间线），跳转现有 `/pages/analysis/index?kind=*`（先读 analysis 页支持的 kind 枚举，缺失入口映射到最接近的 kind 或诚实置灰并注明）；**保留既有登录/设置/法务功能与顺序习惯**。
4. 交付：App vs Mini 入口×顺序×文案逐项差异表（附 PR 评论）；真机/DevTools 点击每个入口可达。

## 4. 测试数据规格（在现有 76 天数据集上新增，不推翻）

生成器在 `mock_fixture.ts` `planTimeline()`（固定种子 20261025，确定性）：

| # | 场景 | 规格 | 验证点 |
|---|---|---|---|
| 1 | 冬季负温 | 12 条行程 `outside_temp_avg` ∈ [-8.2, -0.4]（连续取值，勿全同） | 温度负值渲染不钳制、不误判缺失 |
| 2 | 再生制动 | 15 条行程 `energy_consumed_net < 0` 或路线点含 `power < 0` 段 | 负能耗绿色/负号路径、效率为负显示 |
| 3 | 跨月边界 | 8/31→9/1、9/30→10/1 各有相邻行程 | 按月分组图表不断档 |
| 4 | 隔离记录 | 1 条 `quality_state='quarantined'` 行程 | 不进列表/统计（现有行为保持，防回归） |
| 5 | 电池退化 | A1 的 current_capacity 序列随时间 60→59.6→59.2（与 A2 更新时间线对应） | 电池健康页趋势正确 |

每项加 `mock_fixture.test.ts` 断言（存在性+数值边界）。**注意**：新增场景不得破坏既有 4 项 fixture 测试与 203/25 量级断言（量级断言范围可放宽到 ±20%，需同步改测试并说明）。

## 5. 验收与交付（每阶段）

1. `npm test` 全绿；`npm run typecheck` 零错误。
2. 双模式构建：mock 构建（审核用）+ 默认构建（证明开关关零影响），各记 contentSha256。
3. 提交（独立 commit，信息格式 `feat(miniprogram): ...` / `test(miniprogram): ...`）→ 推送工作分支 → lease 保护同步展示分支。
4. PR #28 评论：固定 SHA、限定 diff、测试/构建结果、差异表（阶段 B）、用户 DevTools 待补截图说明。
5. 等 Codex PASS / CHANGES_REQUIRED；CHANGES_REQUIRED 修复后重走 1-4。

## 6. 阶段 C（可选；未经 Jovi 安排不启动）

真实账号同车同刻对照（App build70 实机 vs Mini 真机预览），按 `E:\Claude_allow\Download\mp-pr28-data-chain-audit-prompt-20261025.md` 采集 PASS/FAIL/BLOCKED；含行程分页覆盖差异（App 日期窗无限滚动 vs Mini 页基，App `DrivesViewModel.loadMore` 步长 30 天为参照）评估。需要 Jovi 配合登录。

## 7. 已知事实速查（省你重查）

- 拦截点：`services/api.ts` `requestJson.attempt` 开头（`MOCK_FIXTURE_ENABLED` 分支）；`rawRequest` 直连端点（session/auth 类）**不**被拦截，属预期。
- 假会话：`services/session.ts` `readAppSession` 开头（仅开关开启时）。
- 充电轮询：`pages/charges/index.tsx`（30s/4s 双拍、scopeGate 异常安全、load() 启动）——已修复，勿重复改动。
- 驻车：`domain/drive_history.ts`（≥0.5km 资格对）+ `pages/drives/index.tsx`（列表卡 + overlay，请求 key 守卫）。
- 免费费用：`services/charge_cost.ts` `overlayChargeCosts`（manual→free→actual→estimate）+ `Car.freeSupercharging`。
- mock 现有覆盖：cars/snapshot/status/readiness/pairing/current-charge/历史分页/行程详情(含路线点)/充电详情/驻车详情；**未覆盖**=本包 §2 的 4 端点 + auth 类。
- 测试文件即规格：`mock_fixture.test.ts`、`drive_history.test.ts`、`charge_cost.test.ts`、`vehicle_page.test.tsx`（剩余时间断言已是 `1.5h` App 原始小时格式）。
