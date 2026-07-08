# MateLink Stitch 1:1 — Baseline Page Mapping

## 2026-07-08 repair reconciliation

- Fixed: Android Settings no longer exposes the removed Palette Preview debug route as a no-op button.
- Fixed: Android More now exposes existing Annual Report, Export Data, 3D Vehicle Preview, and Current Charge routes.
- Fixed: Android Dashboard marks the synthetic 7-day battery trend as an estimated demo until real history data is connected.
- Fixed: iOS `CurrentChargeView` verifies the real `/charges/current` endpoint in real mode and shows an unavailable state on failure instead of silently deriving everything from `/status`.
- Fixed: iOS Add Instance only dismisses after `AppState.connect` succeeds; save failures remain visible on the form.
- Fixed: iOS analysis/report real-mode fetches no longer use `try? await api.fetch(...) ?? []`; failures now surface as explicit error/unavailable states.
- Still deferred: iOS Widget remains source-only without target, entitlements, or App Group wiring.
- Still gated: Android native build requires Java/Gradle on this machine; iOS native build requires Mac/Xcode/XcodeGen/CocoaPods.

## 2026-07-08 interaction reconciliation

- Fixed: Android Dashboard state and status chips are now static pills instead of clickable Material chips with empty handlers.
- Confirmed: Android Settings product buttons route to `NavGraph` or call their ViewModel/instance callbacks; remaining empty handlers in that file are preview defaults or read-only text-field shims.
- Fixed: iOS More now exposes Current Charge and a deferred 3D Vehicle Preview entry.
- Fixed: iOS Settings now exposes Tariff Config from the preferences section.
- Fixed: iOS Dashboard primary cards now click through to Battery Health, Mileage, Location Detail, Current Charge, and Statistics.
- Fixed: Android Settings now forwards the Tariff Config route into the visible settings content; the tariff card is no longer a dead click.
- Deferred: iOS 3D Vehicle Preview is a wired placeholder until the native rendering stack is selected.
- Deferred: Android still lacks first-class Heatmap and Top Destinations entries to match iOS More.
- Deferred: iOS still lacks the Android Saved Trips entry; Drive History remains available as the second tab.

> 范围：`app_mimo/`（Android · iOS · Web）vs Stitch 白色简约瑞士风项目 `11493757920836657212`（Precision Minimalist）。
> 依据：`docs/PRD/MateLink_Stitch_Swiss_PRD_2026-07-05.md`、`docs/PRD/MateLink_UI_PRD.md`、`app_mimo/README.md` 及三端真实源码文件。
> 基准日期：2026-07-05。仅记录仓库现状，不臆测不存在的文件。

## 0. 当前调试状态（2026-07-05 修复轮后）

- Android 活动壳已进入 `MateLinkNavHost` + `NavGraph` 的 4-Tab 结构，`MoreScreen.kt` 已存在并作为第四 Tab 中转页；不要再按“More=Settings / 分析页不可达”的旧结论调试。
- 本轮已修复 Android P0：`NavGraph.kt` 不再引用缺失的 `PalettePreviewScreen` / `Screen.PalettePreview`。
- 本轮已修复 iOS P0：`MoreView.swift` 的 `RangeView()` 现在对应真实 `RangeView` 类型；app target 中 iOS 17-only `ContentUnavailableView` 已替换为 iOS 16 可用的 `EmptyStateView`。
- 本轮已修复关键 mock/real 边界：Android Dashboard 通过 `TeslamateRepository` / `ApiResult` 读取数据并受 mock mode 控制；iOS Timeline real mode 通过 `state.real` 请求 drives/charges，失败显示错误，不再静默回退 mock。
- Widget 状态仍为 deferred/source-only：iOS widget source 存在，但 project target、entitlements、App Group wiring 未完成，不应作为本轮已支持能力验收。
- Windows 侧仍不能声明原生编译通过：Android native build 需要 Java/Gradle 可用；iOS 编译需要 Mac/Xcode/XcodeGen/CocoaPods。

## 1. 页面映射表

图例：✅ 文件存在且已连入活动导航壳；📦 文件存在但未连入活动导航壳；❌ 无文件；❓ 不确定/需核实。

| Stitch 页面 | 产品角色 | Android 现状 | iOS 现状 | Web 现状 | Gap / mismatch | 建议归属 |
|---|---|---|---|---|---|---|
| dashboard | 实时总览（电量/续航/状态/胎压/7日趋势） | ✅ `DashboardScreen.kt`（4-Tab 起始页，repository/mock path，核心卡片已接导航） | ✅ `DashboardView.swift`（`ContentView` Tab） | ✅ `Dashboard.tsx`（`/dashboard`，侧栏首项） | Web 用 14 项侧栏非 Stitch 4-Tab；Android 视觉仍需后续 Swiss-minimal 收口 | Web / Android 视觉 |
| trip history | 行程历史列表（月切换/筛选/高效徽章） | ✅ `DrivesScreen.kt`（Tab，label=`Drives`） | ✅ `DriveListView.swift`（Tab） | ✅ `Drives.tsx`（`/drives`） | 三端 Tab/标题仍为 `Drives`，未对齐 Stitch「行程历史」中文口径 | 跨端（文案） |
| trip detail | 单次行程复盘（地图+5曲线 Tab） | 📦 `DriveDetailScreen.kt` 仅在 `NavGraph.kt` 注册，`MateLinkNavHost` 中 `DrivesScreen()` 未传 `onNavigateToDriveDetail` → 活动壳不可达 | ✅ `DriveDetailView.swift`（`NavigationLink`） | ✅ `DriveDetail.tsx`（`/drives/:id`） | Android 详情在活动导航壳中无入口 | Android |
| charge history | 充电历史列表（AC/DC/总电量/总费用/实时卡） | ✅ `ChargesScreen.kt`（Tab，label=`Charges`） | ✅ `ChargeListView.swift`（Tab） | ✅ `Charges.tsx`（`/charges`） | Android `ChargesScreen()` 未传 `onNavigateToChargeDetail`/`onNavigateToCurrentCharge`，列表→详情/实时链路在活动壳断开；Web 无「正在充电」置顶实时卡入口 | Android / Web |
| charge detail | 单次充电复盘（4曲线+阶段划分） | 📦 `ChargeDetailScreen.kt` 仅 `NavGraph.kt`，活动壳不可达 | ✅ `ChargeDetailView.swift` | ✅ `ChargeDetail.tsx`（`/charges/:id`） | 同上，Android 链路断裂 | Android |
| current charge | 充电中实时监控（1s 功率曲线/ETA/阶段） | ✅ `CurrentChargeScreen.kt` 已通过 `NavGraph` / Dashboard / More 链路可达 | ✅ `CurrentChargeView.swift`（充电列表置顶入口） | ✅ `CurrentCharge.tsx`（`/current-charge`）但侧栏无入口 | Web 有路由无入口；双端实时曲线仍需真实接口验证 | Web（入口）/ 双端验证 |
| heatmap | 高频时段/常去目的地/路线排行 | ❓ README 标 ✅ 但 `ui/screens/` 下无独立 `HeatmapScreen.kt`，疑似内嵌于 `StatsScreen`，需核实 | ✅ `HeatmapView.swift`（`MoreView` 入口） | ✅ `Heatmap.tsx`（`/heatmap`） | Android 是否独立页存疑 | Android（核实） |
| range analysis | 预估 vs 实际偏差/影响因素/评级 | ❓ README 标 ✅ 但无独立 `RangeScreen.kt`，疑似内嵌，需核实 | ✅ `RangeView.swift`（`MoreView`） | ❌ 无 Range 页（grep 确认无 `Range.tsx`/`Vampire.tsx`/`Timeline.tsx`/`CostAnalysis.tsx`） | Web 整页缺失；Android 独立页存疑 | Web（新建）/ Android（核实） |
| efficiency analysis | 平均能耗/评级/散点/同车型对比 | ❓ README 标 ✅ 但无独立 `EfficiencyScreen.kt`，疑似内嵌，需核实 | ✅ `EfficiencyView.swift`（`MoreView`） | ✅ `EfficiencyCurve.tsx`（`/efficiency`） | Android 独立页存疑；命名 `EfficiencyCurve` ≠ Stitch「效率分析」 | Android（核实） |
| battery health | 健康度/容量衰减/循环/温度/维护建议 | ✅ `BatteryScreen.kt` 已通过 Dashboard / More 链路可达 | ✅ `BatteryHealthView.swift`（`MoreView`） | ✅ `BatteryHealth.tsx`（`/battery`，侧栏） | Android/Web/iOS 信息结构存在，视觉和真实数据仍需实机验证 | 双端验证 |
| vampire drain | 待机耗电/耗电来源/优化建议 | ❓ README 标 ✅ 但无独立 `VampireScreen.kt`，需核实 | ✅ `VampireView.swift`（`MoreView`） | ❌ 无 Vampire 页 | Web 缺失；Android 存疑 | Web（新建）/ Android（核实） |
| mileage drill-down | 年度/月度/场景/365热力/Top5/钻取 | ✅ `MileageScreen.kt` 已通过 Dashboard / More 链路可达 | ✅ `MileageView.swift`（`MoreView`） | ✅ `Mileage.tsx`（`/mileage`） | 三端存在；细节视觉和钻取深度仍需对齐 | 跨端 polish |
| more | 分析/报告/系统入口中转页（车辆摘要+分组） | ✅ `MoreScreen.kt`（4-Tab 第四项，托管分析/系统入口） | ✅ `MoreView.swift`（Tab，托管分析入口） | ❌ 无 More 页（采用 14 项侧栏替代） | Web 信息架构与 Stitch 4-Tab+More 不一致 | Web（IA 决策） |
| settings | 服务器/Token/连接测试/语言/主题/模拟 | ✅ `SettingsScreen.kt`（从 More 进入；含 mock mode 开关） | ✅ `SettingsView.swift`（经 `MoreView` 进入，非 Tab） | ✅ `Settings.tsx`（侧栏） | iOS 多实例仍未达到 Android 能力；Web IA 不同 | 跨端 polish |
| about | 品牌/技术栈/依赖/车辆摘要/许可 | ✅ `AboutScreen.kt`（经 More 进入） | ✅ `AboutView.swift`（经 More 进入） | ❌ 无 About 页 | Web 缺失 | Web（新建） |

> Stitch PRD 另列「统计-年份/月度」「时间线」「固件版本」「哨兵历史」「目的地」「成本分析」等页。仓库现状：Android 相关页面已在 `NavGraph.kt` 注册并由 `MoreScreen` / Dashboard 逐步接入；iOS `StatisticsView`/`UpdatesView`/`TimelineView`/`DestinationsView`/`CostView` 经 `MoreView` 可达；Web 有 `Statistics`/`SoftwareVersions`/`SentryEvents`/`TopDestinations`/`CountriesVisited`/`Trips`，缺 `Timeline`/`Cost`/`Range`/`Vampire`。这些不在本表最小 16 页范围内，仅作上下文。

## 2. 关键不确定性（需后续核实，不要猜）

1. **Android/Web 差异需分开判断**：Android 已切到活动 4-Tab + More 壳；Web 仍是 14 项侧栏，不能用 Android 结论推导 Web 已对齐 Stitch。
2. **Android 分析页仍需逐页实测**：`Heatmap`/`Range`/`Efficiency`/`Vampire` 等页面入口存在或已注册，但真实数据、空态、视觉 1:1 仍需运行时验收。
3. **Widget 不进入本轮完成口径**：Android Widget 代码较完整；iOS Widget source 存在但 target/entitlements/App Group 未接，仍为 deferred。

## 3. 已存在的可复用基础

- **跨端共享**（`app_mimo/shared/`）：GCJ-02 坐标转换（含港澳豁免）、`TariffConfig` 分时电价、ISO 8601 解析。三端行为一致，是地图与成本页的统一基础。
- **数据层降级模式**：`DelegatingCarRepository`（Network-First + 缓存 + Mock 可切换）三端均有类比实现，是所有页面的数据契约基础。
- **Android 成熟基建**：Room v12（14 实体/11 迁移）、Hilt（3 模块）、WorkManager 同步、完整通知系统（充电/哨兵/胎压/OTA/里程/电池 6 类 + BootReceiver）、Glance Widget、多实例 CRUD、年度报告 PDF + CSV/JSON 导出、`UrlSecurity` 运行时 HTTPS/LAN 守卫、`EncryptedSharedPreferences`。可直接复用到所有分析/报告页。
- **iOS**：SwiftUI + Swift Charts、`MoreView` 已实现 Stitch「更多」IA 中转页（最接近 Stitch 模型）、Keychain `WhenUnlockedThisDeviceOnly`、Onboarding 持久化、SceneKit 3D。
- **Web**：React 19 + Vite + Zustand store、Recharts、Leaflet、`mock_data.json` 离线数据、online/offline 横幅、Mock 模式横幅、`store` 中的 `theme`/`mockMode`/`currentCarId` 全局状态。
- **多语言**：三端均支持 EN/中/日/德/法 5 语；iOS `Resources/` 5 个 `.lproj`。

## 4. 对白色简约瑞士风（Precision Minimalist）的 Top 5 不一致

1. **Web 信息架构仍未对齐**：Stitch 规范 4-Tab + More；Android/iOS 已有 More 中转页，Web 仍用 14 项侧栏替代。
2. **Android Dashboard 视觉仍需收口**：数据链和核心导航已修，但 Dashboard 仍存在 Material elevation、英文硬编码、emoji 状态等与 Precision Minimalist 不一致的读感。
3. **Web 缺 5+ 个 Stitch 页面**：`Range`/`Vampire`/`Timeline`/`Cost Analysis`/`About`/`More` 均无文件；`CurrentCharge` 有路由但侧栏无入口。Web 远未达到 Stitch 页面集合。
4. **iOS Widget 未产品化**：`CurrentCharge`/`Mileage`/`Sentry`/`About` 页面已补，但 Widget 仍是 source-only/deferred。
5. **视觉系统未对齐 Precision Minimalist**：Stitch 白色简约风要求纯白底 / 黑主色 / 金点缀 / 8px 圆角 / 无阴影靠边框分层 / JetBrains Mono `tabular-nums`。三端 README 仅提及「主题切换」但未确认落地白色简约设计令牌；Android Tab 标签仍为英文 `Dashboard/Drives/Charges/More`，未对齐 Stitch 中文 `仪表盘/行程/充电/更多` 口径。

## 5. 落地建议优先级（实现导向）

1. **P0 原生编译验证**：安装/切换到可用 Java 后跑 Android Gradle；到 Mac 后跑 XcodeGen/CocoaPods/Xcode app target。
2. **P1 Web More/缺页决策**：Web 决策是否保留侧栏或加 More 入口，并补 `Range`/`Vampire`/`Timeline`/`Cost`/`About`。
3. **P1 补齐 Web 缺页**：`Range`/`Vampire`/`Timeline`/`Cost`/`About`，复用 `store` + Recharts + 现有 `mock_data.json`。
4. **P1 补齐 iOS 缺页**：`CurrentCharge`（复用 `ChargeDetailView` 曲线组件）、`Mileage`（复用 Web `Mileage.tsx` 数据契约）。
5. **P2 视觉令牌统一**：在三端 theme 中落地 Precision Minimalist 设计令牌（白底/黑/金/8px/无边框阴影/`tabular-nums`），并统一 Tab 中文文案。
