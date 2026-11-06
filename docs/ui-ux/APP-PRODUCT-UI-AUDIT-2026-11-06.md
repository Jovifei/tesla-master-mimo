# MateLink APP 产品与用户体验审核报告

日期：2026-11-06  
审核范围：Android 主端、Web 端登录与核心页面、数据获取/刷新、参数计算、空态、页面切换和用户可见状态。  
当前分支：`codex/app-foundation-ui-integrate-20261025`  

## 结论

当前版本不能判定为“所有功能都已经完整实现”，但本轮审核中识别出的 P0/P1 核心问题已完成一轮最小整改。Android 主流程包含登录、车辆首页、充电、行程、历史分析、缓存降级和错误重试；Web 真实模式已切断页面级 mock 数据泄露，关键图表、地区和详情页已改为 API 数据或明确空态。源码验证通过不等于真机、Release 或真实服务验收。

本报告严格区分三种状态：

- **已修复**：本轮已经修改源码并通过验证。
- **已发现**：已在源码中确认，但本轮没有修改。
- **未验证**：需要真机、Release 包或真实服务数据才能确认，不能用单元测试代替。

## 优先级总表

| 端 | 优先级 | 问题 | 状态 | 证据类型 |
|---|---|---|---|---|
| Web | P0 | 真实模式可能展示 mock 车辆、趋势和地区数据 | 已修复，Web lint/build 通过 | 源码 + 构建 |
| Android | P1 | 分析页时间窗口使用不同的时钟/时区 | 已修复，定向测试/lint 通过 | 源码 + 单元测试 + lint |
| Android | P1 | 充电/行程筛选为空时语义不清 | 已修复，定向测试/lint 通过 | 源码 + lint |
| Web | P1 | 页面缺少统一加载、空态、错误和重试状态 | 已修复关键范围；仍有页面待补 | 源码 + Web lint/build |
| Web | P1 | 路由保护、请求取消、移动端布局和 Token 存储存在风险 | 已发现，未修改 | 源码审查 |
| Android | P2 | 启动占位、分析页刷新、页面动画、Timeline 主题和英文资源 | 已修复，自动化通过 | 源码 + 单元测试 + lint |

“已修复，自动化通过”表示源码改动已经编译并通过测试；不表示已经完成真机视觉验收或 Release 验收。

本轮执行整改后，报告中的“已发现，未修改”仅保留给明确列入后续范围的风险；Web 真实模式、关键图表/地区/详情页、Android 分析时钟和充电/行程筛选空态已进入“已修复”。

## 本轮已完成的修复

### 1. 能耗和费用分析页增加刷新闭环

问题：效率、费用分析页只有首次进入时加载，没有页面内刷新入口。网络失败或数据源更新后，用户只能离开页面再进入。

处理：

- 增加前台返回刷新。
- 增加下拉刷新。
- 增加错误状态下的“重试”按钮。
- 刷新过程中继续保留同车、同身份的已发布缓存，避免页面闪白。
- 网络失败时继续显示缓存，并标识为缓存状态。

涉及文件：

- [EfficiencyViewModel.kt](../android/app/src/main/java/com/matelink/ui/screens/efficiency/EfficiencyViewModel.kt:120)
- [EfficiencyScreen.kt](../android/app/src/main/java/com/matelink/ui/screens/efficiency/EfficiencyScreen.kt:70)
- [CostViewModel.kt](../android/app/src/main/java/com/matelink/ui/screens/cost/CostViewModel.kt:191)
- [CostScreen.kt](../android/app/src/main/java/com/matelink/ui/screens/cost/CostScreen.kt:74)

用户结果：用户可以在当前页面主动重试，刷新失败不会立即丢失已有历史数据。

### 2. 启动期间不再出现空白屏

问题：`startDestination` 尚未确定时，导航层直接返回，用户可能看到短暂的空白页面。

处理：增加全屏品牌加载占位。

涉及文件：[NavGraph.kt](../android/app/src/main/java/com/matelink/ui/navigation/NavGraph.kt:336)

### 3. 页面切换增加轻量动画

问题：底部导航图标已有动画，但页面本身切换生硬，详情页和分析页缺少连续反馈。

处理：全局 NavHost 增加短时淡入、横向滑入和返回方向动画，时长控制在 120–180ms，避免影响数据加载。

涉及文件：[NavGraph.kt](../android/app/src/main/java/com/matelink/ui/navigation/NavGraph.kt:531)

### 4. 时间线适配暗色主题和英文资源

问题：Timeline 顶栏固定白色，暗色主题下对比异常；英文资源中仍有中文的“暂无事件、行驶、充电、未知路线”等文案。

处理：顶栏改为主题 surface 颜色，并补齐英文文案。

涉及文件：

- [TimelineScreen.kt](../android/app/src/main/java/com/matelink/ui/screens/timeline/TimelineScreen.kt:71)
- [strings.xml](../android/app/src/main/res/values/strings.xml:296)

### 5. Android 分析时间口径和列表筛选空态

效率、费用、续航 ViewModel 现在接收同一套 `Clock`，日期解析、窗口筛选和 as-of 日期使用 `LocalDate.now(clock)` 与 `clock.zone`。为兼容既有测试，保留了原有测试构造器入口。

充电和行程列表在有时间、类型、费用、位置或距离筛选而结果为空时，显示“当前范围没有记录”，并提供“清除筛选”按钮；无筛选时仍显示普通无记录卡片，读取错误继续显示错误文案和重试入口。

涉及文件：

- [EfficiencyViewModel.kt](../android/app/src/main/java/com/matelink/ui/screens/efficiency/EfficiencyViewModel.kt:84)
- [CostViewModel.kt](../android/app/src/main/java/com/matelink/ui/screens/cost/CostViewModel.kt:141)
- [RangeViewModel.kt](../android/app/src/main/java/com/matelink/ui/screens/range/RangeViewModel.kt:93)
- [ChargesScreen.kt](../android/app/src/main/java/com/matelink/ui/screens/charges/ChargesScreen.kt:398)
- [DrivesScreen.kt](../android/app/src/main/java/com/matelink/ui/screens/drives/DrivesScreen.kt:332)

## Android 审核结果

### 源码中已存在的保护（未等同于真机验收）

- 登录页已有协议同意、云端登录、自托管入口、登录中、失败、车辆配对和权限引导状态。
- 充电和行程列表已有下拉刷新、分页加载、缓存降级和错误 Snackbar 重试。
- 费用计算保留手动费用、估算费用、部分观测费用，并使用不同覆盖数区分来源；相关 ViewModel 测试通过。
- 能耗核心计算使用距离加权逻辑；本轮源码审查和现有自动化测试未发现明显算术错误。
- 车辆/账号/来源身份校验和 Room 缓存隔离已有较多回归测试。

### P1：时间窗口使用的时钟不统一

现象：充电、行程列表部分逻辑使用注入的 Clock，而效率、费用、续航等分析逻辑仍直接使用 `LocalDate.now()` 和 `ZoneId.systemDefault()`。

用户影响：在跨时区、午夜边界或设备时区变化时，同一条记录可能在列表页属于“今天”，在分析页却属于“昨天”。

建议：把分析页的 `Clock`、`ZoneId` 和数据源 as-of 时间统一注入，并让筛选、日期解析、统计窗口共用同一套时间口径。

涉及位置：

- `EfficiencyViewModel.kt`：约 222、280、323 行
- `RangeViewModel.kt`：约 331、343、371、393 行
- `CostViewModel.kt`：约 348、404 行
- 同类逻辑还出现在 Mileage、Vampire 等分析页

状态：**已修复。**

### P1：充电/行程筛选为空时语义不够明确

现象：筛选后没有记录时，页面可能只显示“没有充电记录”或“没有行程记录”。

用户影响：用户无法判断是当前筛选范围没有记录、历史数据还在同步、数据源不可用，还是历史真的缺失。

建议：

- 明确显示“当前筛选范围没有记录”。
- 提供“清除筛选”操作。
- 将 `FILTER_EMPTY`、`NO_RECORDS`、`SOURCE_UNAVAILABLE`、`COLLECTING` 分开显示。

状态：**已修复。**

### P2：分析空态原因可能被压缩成普通无记录

现象：分析历史仓库在部分空结果路径中只依据记录数量判断，可能丢失“正在采集”或“数据源不支持”的原因。

用户影响：页面显示“尚未开始采集”，但实际可能是数据源不支持该分析，用户会错误地等待或反复刷新。

建议：保留 API metadata/provider availability，并传递到统一的 `NoDataReason`。

状态：**已发现，未修改。**

### P2：列表项动画可能造成长列表滚动负担

现象：充电和行程列表中的每一项都使用可见动画。

建议：只对首屏或新插入项动画，历史长列表使用静态组合。

状态：**已发现，未修改。**

### P2：More 分析入口缺少当前车辆和同步时间

现象：分析入口集中在 More 页面，但用户不容易确认当前分析针对哪辆车、数据截至何时。

建议：页面顶部显示车辆名称、车辆标识和最近同步时间，保持只读。

状态：**已发现，未修改。**

## Web 审核结果与整改

本轮已修改 Web 真实模式数据边界和关键页面状态；未把 mock 夹具作为真实服务失败时的隐式回退。

### P0：真实模式仍可能展示 mock 数据

证据：

- `App.tsx` 和 `store.ts` 在真实模式先清空示例车辆，再加载 API 车辆和状态；mock 模式有明确演示标识。
- `Charges.tsx` 月度图表改为根据充电 API 数据按月聚合；无数据显示空态。
- `Dashboard.tsx` 电池趋势改为电池健康 API 数据；无数据显示空态。
- `CountriesVisited.tsx` 改为按当前车辆请求 visited-regions API，并补 loading/error/empty。
- `ChargeDetail.tsx`、`DriveDetail.tsx`、`CurrentCharge.tsx` 改为真实 API 查询，移除随机遥测和硬编码天气/位置。

用户影响：用户可能把演示数据误认为自己的车辆数据、充电记录和电池趋势。

建议：

- 真实模式禁止读取 mock 数据。
- mock 模式在页面上明确标注“演示数据”。
- 所有图表使用 API 返回数据，没有数据时显示来源明确的空态。
- CountriesVisited 传递当前车辆和年份范围，接入真实 API。

状态：**已修复。**

### P1：多个 Web 页面没有完整加载和空态

涉及页面包括 Mileage、Statistics、Heatmap、TopDestinations、EfficiencyCurve、SentryEvents、CountriesVisited 等。

用户影响：请求还没有完成时页面可能直接显示 0、空图或空白，用户无法区分“正在加载”和“确实没有数据”。

建议：统一 PageState：`loading`、`content`、`empty`、`error`、`stale`，并给 error 状态提供重试按钮。

证据类型：**源码 + Web lint/build；未做真实浏览器逐页验收。**

### P1：Web 路由缺少登录保护和请求代次保护

问题：

- `onboardingDone=false` 时仍可直接访问 dashboard 等路由。
- 切车或快速切页后，旧请求可能晚于新请求返回并覆盖新车辆数据。
- App 获取车辆失败只有文字提示，没有回退和重试动作。

建议：增加 AuthGuard、AbortController 或 request epoch，并在车辆切换时取消旧请求。

证据类型：**源码审查，仍待后续；未做切车竞态和未登录直达路由的浏览器复现。**

### P1：Web 计算和设置存在口径问题

已发现问题：

- TopDestinations [TopDestinations.tsx](../web_matelink/src/pages/TopDestinations.tsx:26) 把距离乘以 100 后直接显示，结果可能放大约 100 倍。
- Mileage [Mileage.tsx](../web_matelink/src/pages/Mileage.tsx:42) 的能耗汇总使用全部历史充电数据，但页面标题按年份展示；约 43–44 行的平均效率为简单平均，未按距离或有效样本加权。
- BatteryHealth [BatteryHealth.tsx](../web_matelink/src/pages/BatteryHealth.tsx:27) 未处理原始额定续航为 0、结果越界和非法数值。
- Heatmap [Heatmap.tsx](../web_matelink/src/pages/Heatmap.tsx:40) 先计算整段平均速度，再写入起止小时，跨小时行程可能重复累计。
- Settings 中的单位、温度、费率设置没有持久化或参与计算；对应行为需补浏览器操作验证。
- API token 通过 [store.ts](../web_matelink/src/store.ts:63) 持久化到 localStorage，存在明文存储风险。

证据类型：**源码 + 部分修复 + Web lint/build；Mileage/Heatmap/设置安全等仍待真实浏览器数据回放。**

### P1：移动端布局和可访问性不足

问题：固定侧边栏宽度、固定网格列数和缺少响应式断点，手机端可能横向溢出；表单缺少部分 `label`、`aria-live` 和 Enter 提交支持。

建议：先补移动端断点和单列降级，再补表单语义和错误播报。

证据类型：**源码审查，已发现，未修改；未做真实手机浏览器像素验收。**

## 验证记录

已执行：

- `:app:testDebugUnitTest --tests com.matelink.ui.screens.efficiency.EfficiencyMileageIdentityConsumerTest`：通过
- 其中新增固定 `Clock` 时区跨午夜边界回归：通过
- `:app:testDebugUnitTest --tests com.matelink.ui.screens.cost.CostRoomFirstViewModelTest`：通过
- `:app:testDebugUnitTest`：通过
- `:app:lintDebug`：通过
- `:app:testDebugUnitTest --tests com.matelink.ui.screens.range.RangeRoomFirstRegressionTest`：通过
- `npm run lint`（`web_matelink`）：通过
- `npm run build`（`web_matelink`）：通过；仅有 Vite 产物超过 500 kB 的既有体积提示
- `git diff --check`：通过

验证与覆盖范围：

| 验证项 | 覆盖内容 | 未覆盖内容 |
|---|---|---|
| 效率 ViewModel 定向测试 | 刷新、缓存投影、身份隔离和旧请求保护 | 真机下拉手势、前台生命周期和像素布局 |
| 费用 ViewModel 定向测试 | 费用投影、Room 缓存和费用来源 | 真机下拉手势、错误按钮点击和真实服务 |
| Debug 全量单元测试 | Android 逻辑回归 | Release/R8、真实账号、真实车辆 |
| Debug lint | Kotlin、资源和 Compose 静态检查 | Web 构建和浏览器运行时 |
| Web lint/build | TypeScript、页面编译和生产打包 | 真实 TeslaMate 接口、浏览器交互和移动端像素布局 |

这些结果证明 Android 改动可以编译、定向测试通过、lint 没有失败项，Web 可以 lint 并完成生产打包；不能替代真实设备逐页视觉验收、Release 构建、真实服务字段回放或跨页面竞态验收。

## 建议实施顺序

### 第一优先级

1. 用真实 TeslaMate 服务和浏览器逐页回放验证本轮 Web API 数据、空态和错误态。
2. 补齐 Mileage、Heatmap、Statistics、SentryEvents 等未覆盖页面的统一重试和空态。

### 第二优先级

1. 对 Android 分析仓库补充 provider availability 和 collecting 状态。
2. 完成 Android Release/R8 与真实设备视觉验收。

### 第三优先级

1. Web 路由保护、请求取消和移动端布局。
2. Web 设置持久化与安全存储。
3. Android More 页面补充车辆和同步时间上下文。
4. Android 长列表动画降级，并进行真实设备性能检查。

## 本轮未完成项和风险

- 未安装或发布新的 APK。
- 未完成真机逐页视觉验收。
- 未完成 Android Release 构建和 Release lint。
- Web 真实模式与关键页面已修改，但未做真实 TeslaMate 接口联调。
- Hub 上报服务连接被拒绝，已保留本地 pending 记录：[matelink-ux-audit-20261106.pending.json](../tasks/matelink-ux-audit-20261106.pending.json)。
- 仍未处理：Web token localStorage 明文存储、未登录直达/请求竞态的浏览器复现、Mileage/Heatmap 计算口径、移动端像素布局；Android 未做 Release/真机验收。
- 本报告中的 Web 修复已通过源码和构建验证，尚未用生产账号或真实用户数据验证。

