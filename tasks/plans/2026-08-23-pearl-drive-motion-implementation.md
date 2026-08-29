# 珍珠电驱 Android 轻量优化实施计划

## 当前目标与证据

- 目标：在原 `com.matelink` 的现有页面、面板、数据和四个一级路由上，完成“珍珠电驱”轻量视觉优化；不重做信息架构。
- 已有证据：原 Release 已通过四个底部入口实体导航验证；560 个 JVM 用例通过；Release lint 0 Error、`MissingTranslation=0`、无 baseline。
- 当前状态：设计规格已获 Jovi 明确授权；当前工作树只有设计规格和 `.superpowers/` 未跟踪文件，Android 源码尚未开始本轮修改。
- 证据边界：本轮本地测试和预览只证明实现候选与原有页面回归，不证明真实 Tesla OAuth/Fleet 或生产发布。

## 允许范围与禁止事项

- 允许修改：`android/app/src/main/java/com/matelink/ui`、对应 JVM 测试、必要的 Android 文档和 Obsidian 项目记录；预览产物只能写入 `E:\Claude_allow\Download`。
- 保留：`com.matelink`、原 Dashboard、行程/充电/更多页面、统计内容、现有导航顺序、Room/DataStore、登录和自托管连接。
- 禁止：重做布局、删除现有统计、改变登录/服务器逻辑、修改包名或签名、触碰真实凭据、安装/卸载/清理手机数据、stage/commit/push。

## 实施步骤

### 1. RED：先建立视觉契约测试

- 目的/前提：锁定底部图标语义和动效参数，防止以后回退到泛化图标或超长动画。
- 文件：新增 `android/app/src/test/java/com/matelink/ui/navigation/PearlDriveVisualContractTest.kt`。
- 命令：`android\\gradlew.bat :app:testDebugUnitTest --tests com.matelink.ui.navigation.PearlDriveVisualContractTest`。
- 期望证据：测试因目标枚举/令牌尚不存在而失败，失败原因必须是缺少待实现符号，而不是测试拼写错误。
- 失败/回滚：若失败原因不正确，只修正测试；不写生产代码。
- 可标记状态：`RED_CONFIRMED`。

### 2. GREEN：底部导航图标与动效令牌

- 目的：用车辆/路线/能量/分析四个功能语义图标替换当前泛化图标，并增加选中图标轻微弹性缩放；保留原标签、点击和路由。
- 文件：`MateLinkNavHost.kt`、新增 `PearlDriveMotion.kt`、上述测试。
- 约束：选中缩放约 `1.06x`、动画约 `180ms`；目标触控区域仍由 `NavigationBarItem` 保证；不使用无限闪烁。
- 命令：先运行单测确认 GREEN，再运行 `:app:testDebugUnitTest`。
- 期望证据：契约测试通过，原导航路由测试仍通过。
- 失败/回滚：恢复仅本轮新增的导航/令牌改动，不触碰历史导航修复。
- 可标记状态：`NAV_MOTION_PASS`。

### 3. GREEN：面板材质与字体层级定点优化

- 目的：消除双重虚线/实线般的硬边，使用浅色背景、白色面板、低对比边缘和轻微阴影；统一统计数字使用等宽数字，保持原卡片内容和顺序。
- 文件：新增/修改 `ui/theme` 令牌、`TelemetryPanel.kt`、`MoreScreen.kt`、`StatsScreen.kt`、必要的 `DashboardScreen.kt`。
- 约束：不改变卡片数量、布局顺序和数据来源；不把缺失值改成零；深色模式仍使用主题色；避免新的 `Color.White` 业务硬编码。
- 命令：`:app:testDebugUnitTest`、`:app:assembleDebug`。
- 期望证据：编译通过，静态源码检查确认本轮触碰的面板不再使用高对比渐变边框；既有统计契约测试通过。
- 失败/回滚：只回滚本轮面板样式修改，保留已经通过的导航契约改动。
- 可标记状态：`PANEL_TYPE_PASS`。

### 4. GREEN：仪表盘刷新与统计页轻动效

- 目的：为刷新按钮增加一次性阻尼旋转，为首次有效电量/续航值增加短促 slide-fade；统计主卡继续保留完整分析内容，不增加假数据或“AI”文案。
- 文件：`DashboardScreen.kt`、`StatsScreen.kt`，必要时复用 `PearlDriveMotion.kt`。
- 约束：只在用户刷新或真实值首次出现时触发；不使用持续无限循环；动画不可阻塞刷新与返回。
- 命令：`:app:testDebugUnitTest`、`:app:assembleDebug`。
- 期望证据：构建通过，运行时检查确认首页/统计页仍可进入；动画代码不触碰 ViewModel 或 Repository。
- 失败/回滚：删除本步动画调用，保留静态样式。
- 可标记状态：`SCREEN_MOTION_PASS`。

### 5. 预览与发布门禁

- 目的：生成只用于审查的 6 秒动效预览，并确认 Android 现有质量门禁没有回归。
- 文件：HyperFrames/Remotion 候选预览写入 `E:\Claude_allow\Download`；项目文档和 Obsidian 仅记录结果，不把预览当 APK 功能证明。
- 命令：优先使用已安装的 HyperFrames lint/inspect/preview；Android 运行 `:app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease :app:lintRelease`，另跑 `git diff --check`。
- 期望证据：Debug/Release 测试、构建、lint 和差异检查通过；`MissingTranslation=0`、无 lint baseline；lint 数量按静态质量/多语言覆盖/发布门禁记录，不能描述为运行时 Bug。
- 失败/回滚：若构建或 lint 回归，停止安装和发布，不修改无关代码；修复后重新执行本步。
- 可标记状态：`LOCAL_UI_CANDIDATE_PASS`，不是实体设备交付或真实 Tesla 通过。

## 停止条件

- 遇到需要正式签名、手机覆盖安装、生产服务器、域名/DNS/HTTPS、Tesla 凭据或 Git 操作时立即停止并向 Jovi 报告。
- 若原页面内容或数据契约需要改动才能实现视觉效果，停止扩展范围，保留当前候选并请求新的授权。

## 回滚边界

- 只允许回滚本轮新增/修改的 Android UI、测试、预览和文档文件；不得使用 `git reset`、`git checkout`、`git clean` 或覆盖其他未授权工作。

## Review

- `RED_CONFIRMED`：契约测试先因缺少 `TopLevelIconKey`/`PearlDriveMotion` 失败，后在实现后通过。
- `NAV_MOTION_PASS`：车辆/路线/能量/分析图标映射与 1.06 倍、180ms 选中动效通过。
- `PANEL_TYPE_PASS`：Telemetry/More/Stats 软面板与等宽数字改动通过构建和样式契约测试。
- `SCREEN_MOTION_PASS`：刷新 450ms、数值 220ms 有限过渡通过契约测试；未使用 `repeatable`。
- `LOCAL_UI_CANDIDATE_PASS`：Debug/Release 合计 570 个 JVM 用例通过；构建、lint、`git diff --check` 通过；lint 195、0 Error、`MissingTranslation=0`、无 baseline。
- APK 候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-pearl-drive-20260823.apk`，SHA-256 `F30ABFE4A9ACF5378841DEFDED77DF40B7B9A6881CADF775DDB425CCC66041EB`；包名 `com.matelink`，未签名，未安装。
- `NOT_PERFORMED`：未安装 APK、未运行 instrumentation；HyperFrames CLI 未安装，HTML 预览仅为设计预览。
- `PHONE_SMOKE_PASS`：Jovi 后续授权后，签名 `com.matelink` Release 与手机当前证书 SHA-256 `9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774` 一致；`adb install -r` 成功；设备实际 base APK 与候选 SHA-256 均为 `ADCA1469B93755E1971964CDA679E0B3C859CC39A421452E843145AAB3B33B83`。
- 真机回归：冷启动、Dashboard、行程、充电、更多、统计概览、设置入口和刷新后进程均通过；未运行 instrumentation、未清除数据、未修改服务器配置。
- 证据：`E:\Claude_allow\Download\matelink-pearl-drive-phone-dashboard-20260823.png`、`E:\Claude_allow\Download\matelink-pearl-drive-phone-more-20260823.png`、`E:\Claude_allow\Download\matelink-pearl-drive-phone-stats-20260823.png`。
