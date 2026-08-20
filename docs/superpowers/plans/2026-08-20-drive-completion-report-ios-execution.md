# Tesla MateLink 行驶完成报告与 iOS 原生应用严谨执行方案

> 制定日期：2026-08-20  
> 仓库：`Jovifei/tesla-master-mimo`  
> 当前基线：`main@1d861b8167baa906114b6b0fb6d480bcd42d0491`  
> Android 第一阶段分支：`feature/20260820-drive-completion-report`  
> iOS 后续分支：Android 合并并验收后，再从最新 `main` 创建  
> 当前状态：`PLAN_APPROVED_A0_EXECUTING`

---

## 1. 总目标与交付边界

本项目分成两个先后严格隔离的交付阶段，不使用一个长期巨型分支同时修改 Android 和 iOS。

### 阶段 A：Android 行驶完成报告

实现一次真实可用、可验收的 Android 功能闭环：

1. 新行程完成检测；
2. 一次性去重与首次启用水位线；
3. 前台弹窗和后台系统通知；
4. 通知点击进入对应行驶报告；
5. 类似参考截图的地图叠加报告界面；
6. 所有指标具有明确来源、覆盖率和缺失值语义；
7. 无地图 Key、无路线、无功率序列时仍能诚实降级；
8. 数据库升级保留用户数据和已有配置；
9. 单元、集成、UI、构建和真机验证全部留存证据；
10. 通过 Draft PR 完成最终代码审计，不直接写入 `main`。

### 阶段 B：iOS 原生应用

只有在阶段 A 合并并完成 Android 真机验收后才开始：

1. 从最新 `main` 建立独立 iOS 分支；
2. 对 Android 全部能力建立逐项对照矩阵；
3. 先修复/确认当前 iOS 工程可生成、可编译、可测试；
4. 用 SwiftUI 实现真实数据连接、车辆、Dashboard、行程、充电、统计、设置等能力；
5. 实现与 Android 共用语义的行驶报告；
6. 对 iOS 后台执行能力和通知机制做平台能力审计，不承诺未经验证的持续轮询；
7. GitHub Actions macOS、Xcode 单测、UI 测试和 TestFlight 级构建通过后才声明完成。

---

## 2. 强制执行模型：每一步必须经历五个状态

所有阶段必须按下列顺序推进：

```text
EXECUTE
  ↓
VERIFY
  ↓
AUDIT
  ↓
APPROVE_FOR_COMMIT
  ↓
COMMIT
```

状态定义：

| 状态 | 含义 |
| --- | --- |
| `NOT_STARTED` | 尚未开始 |
| `EXECUTING` | 正在实施，仅允许修改本阶段范围 |
| `IMPLEMENTED_PENDING_VERIFY` | 功能写完，尚未通过自动验证 |
| `VERIFY_FAILED` | 自动验证失败，禁止进入审核 |
| `VERIFY_PASSED_PENDING_AUDIT` | 自动验证通过，等待人工审计 |
| `AUDIT_FAILED` | 代码、语义、安全、UI 或范围审核不通过 |
| `AUDIT_PASSED_AWAITING_COMMIT_APPROVAL` | 审核通过，列出候选文件等待提交批准 |
| `COMMITTED` | 已提交到功能分支 |
| `BLOCKED` | 外部环境、接口或设备证据不足 |
| `CLOSED` | 本阶段完成，证据和结论均已归档 |

### 禁止事项

- 禁止跳过 `VERIFY` 或 `AUDIT` 直接提交；
- 禁止用“编译成功”代替功能验收；
- 禁止将数据缺失转换成 `0`、`false`、免费或模拟曲线；
- 禁止为了通过测试而清除 App 数据；
- 禁止把 token、地图 Key、家庭地址、真实域名或数据库密码写入仓库；
- 禁止直接向 `main` 推送功能代码；
- 禁止在 Android 尚未闭环时同时扩展 iOS 大范围代码；
- 禁止把本地缓存、APK、Gradle 状态、Xcode DerivedData、Pods 构建产物提交。

---

## 3. 审核角色与证据要求

每个阶段至少要有四类证据：

1. **实现证据**  
   变更文件、核心类、迁移、接口、页面或状态机说明。

2. **自动验证证据**  
   命令、退出码、测试数量、失败数量、静态扫描和构建产物路径。

3. **人工审核证据**  
   需求逐项映射、异常路径、隐私、语言、UI、数据库升级和回归检查。

4. **Git 证据**  
   基线 SHA、阶段提交 SHA、候选文件清单、diff 统计、PR 状态和 CI 结果。

任何一类证据缺失时，不允许使用“完成”“PASS”“可发布”等结论。

---

# 第一部分：Android 行驶完成报告

## A0. 基线冻结与环境审计

### 执行

- 克隆或更新仓库；
- 检出 `feature/20260820-drive-completion-report`；
- 确认分支起点严格等于批准的 `main` 基线；
- 检查工作树、子模块、LFS、忽略规则和未跟踪文件；
- 记录 JDK、Gradle、Android SDK、ADB、Go、Docker 和 Git 版本；
- 盘点 GitHub Actions、现有测试任务和构建脚本；
- 运行当前未修改基线的可执行测试，建立“变更前”结果；
- 记录 Android 数据库版本、已有迁移和安装升级边界；
- 记录现有同步调度、通知权限申请、deep link 和导航结构；
- 记录真实设备配置状态，但不得读取或打印 token/Key。

### 自动验证

建议命令根据环境调整，但证据至少覆盖：

```bash
git status --short
git rev-parse HEAD
git merge-base HEAD main
git diff --check
cd android
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
cd ../deploy/teslamate-home-docker/adapter
go test ./...
```

如存在可用连接设备：

```bash
adb devices
adb shell pm path com.matelink
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime"
```

### 人工审核

- 工作树是否干净；
- 分支是否来自正确基线；
- 当前失败是否为基线已有失败；
- 数据库迁移是否存在历史风险；
- 测试是否会修改用户配置；
- 当前环境是否具备真机或模拟器验收条件。

### 通过标准

- 形成 `BASELINE_AUDIT`；
- 所有基线失败都有明确归因；
- 不能运行的任务标记 `BLOCKED`，不得伪造 PASS；
- 未修改源码前不允许“顺手修复”无关问题。

### 阶段候选提交

仅当基线审计文档需要入库时，单独提交：

```text
docs(audit): record drive report implementation baseline
```

---

## A1. 需求冻结、数据来源矩阵与报告契约

### 执行

建立 `Drive Report V1`，逐字段定义：

| 字段组 | 主要内容 |
| --- | --- |
| 标识 | `schemaVersion`、`carId`、`driveId` |
| 时间 | 起止时间、时长、时区 |
| 地点 | 起终点地址、经纬度、隐私显示规则 |
| 行驶 | 距离、里程表起止与变化、平均/最大速度 |
| 电池 | 起止 SOC、电量变化 |
| 能量 | 行程能耗、平均能耗、来源、覆盖率 |
| 费用 | 估算费用、币种、电价来源、估算标志 |
| 环境 | 车内/车外平均温度、平均海拔 |
| 曲线 | 速度、功率、SOC、海拔时间序列 |
| 地图 | 路线点、路线有效性、坐标系 |
| 诚实语义 | `available`、`derived`、`estimated`、`unavailable` |
| 隐私 | 通知和报告的地址脱敏状态 |

逐项审查现有 API 和本地数据库，分类为：

- `DIRECT`：后端直接提供；
- `DERIVED`：有合法输入时推导；
- `ESTIMATED`：需要明确标注估算；
- `UNAVAILABLE`：当前无法可靠获得；
- `FORBIDDEN_TO_FABRICATE`：不得以零值或模拟值替代。

### 自动验证

- JSON fixture 能被 Go/Kotlin/Swift 各自解析；
- 空值、非法数值、负距离、无结束时间、零点坐标均有测试；
- 契约字段命名和单位固定；
- `schemaVersion` 兼容性测试；
- 费用、能耗和覆盖率计算具有纯函数测试。

### 人工审核

重点逐项确认：

- 截图字段中哪些仓库确实支持；
- “消耗电量 0”“平均能耗 0”“预计费用 0”是否真实零值；
- 里程变化是否由可靠里程表字段产生；
- 平均海拔是否有足够采样；
- 地图曲线是否来自真实位置序列；
- 通知是否泄露详细地点；
- 中英文、单位和格式是否一致。

### 通过标准

- 每个 UI 字段都能追溯到契约字段和数据来源；
- 无法支持的字段明确显示“不可用”；
- 契约冻结后，平台不得私自改变计算语义。

### 阶段候选提交

```text
docs(contract): define Drive Report V1 truth and privacy rules
test(contract): add cross-platform drive report fixtures
```

---

## A2. Adapter/API 能力审计与最小后端扩展

### 执行

先判断现有接口是否足以支撑报告，只有不足时才增加接口。

建议目标接口：

```http
GET /api/matelink/v1/cars/{carId}/drives/{driveId}/report
```

接口必须：

- 只读；
- 使用固定 V1 DTO；
- 不返回秘密配置；
- 明确缺失值；
- 明确能耗来源和覆盖率；
- 路线点按数量上限和精度策略返回；
- 不把无坐标数据转成 `(0, 0)`；
- 对不存在、尚未完成、无权限和上游暂不可用分别返回清晰错误；
- 保持旧 `/api/v1` 调用兼容。

若现有接口已足够，则输出“无需增加接口”的审计结论，并直接在 Android 客户端建立 DTO 映射。

### 自动验证

```bash
go test ./...
go vet ./...
```

并覆盖：

- 完整报告；
- 缺失能耗；
- 缺失曲线；
- 未完成行程；
- 非法 carId/driveId；
- 多车辆隔离；
- token 错误；
- 响应中无敏感字段。

### 人工审核

- 是否重复实现已有逻辑；
- 是否破坏当前 Adapter 路由；
- 是否把费用估算错误放到后端；
- 是否有无界数组或大响应风险；
- 是否泄露地址、token 或内部错误栈。

### 通过标准

- 后端扩展是“最小必要”；
- 所有旧测试和新测试通过；
- API contract 与 A1 完全一致。

### 阶段候选提交

```text
feat(adapter): expose truthful Drive Report V1 data
test(adapter): cover drive report contract and error paths
```

如无需后端变更，不产生该功能提交，只提交审计记录。

---

## A3. Android 完成行程检测、持久化与一次性去重

### 执行

新增独立的投递状态模型，建议包含：

```text
carId
driveId
detectedAt
notificationPostedAt
openedAt
dismissedAt
deliveryState
reportVersion
```

核心规则：

1. 唯一键为 `carId + driveId`；
2. 只有存在结束时间且满足最小有效条件的行程才生成报告；
3. 首次启用建立水位线，不补发全部历史行程；
4. 重复同步、重试、进程重启、设备重启都不得重复通知；
5. 多车辆独立维护；
6. 离线后一次发现多段新行程时，进入待查看队列；
7. 迁移必须保留所有现有 Room 数据；
8. 不使用 destructive migration；
9. 已发布数据库版本存在 identity hash 风险时，增加后续修复迁移和回归测试；
10. 检测和投递状态分开，避免“通知失败后永远丢失报告”。

### 自动验证

至少覆盖：

- 新安装；
- 已有数据库升级；
- 已发布版本数据库升级；
- 第一次同步历史数据；
- 新增一段行程；
- 同一行程重复同步；
- 同 ID 不同车辆；
- 多段离线行程；
- 进程重启；
- 设备重启；
- 无结束时间；
- 零距离/异常距离；
- 事务失败和重试。

### 人工审核

- migration 是否保留已有表与记录；
- 水位线是否会漏掉启用后刚结束的行程；
- 唯一约束是否正确；
- 是否存在先标记已投递、后通知失败的竞态；
- WorkManager 并发执行是否可能重复；
- 数据库存储是否包含不必要的精确地址。

### 通过标准

- 同一行程最多投递一次；
- 首次启用不轰炸历史通知；
- 保留用户数据；
- 迁移失败不能通过清数据掩盖。

### 阶段候选提交

```text
feat(android): persist completed-drive report delivery state
test(android): cover watermark dedupe and Room migrations
```

---

## A4. Android 通知、前台弹窗与 Deep Link

### 执行

实现：

- 专用通知频道；
- Android 13+ 通知权限处理；
- 前台时展示 App 内报告弹窗/页面入口；
- 后台或锁屏时发系统通知；
- 通知点击直接进入 `carId + driveId` 对应报告；
- 不使用易失的内存事件作为唯一入口；
- 通知默认只显示距离、时长和车辆，不显示精确地点；
- 通知被拒绝时，报告仍进入 App 内待查看队列；
- 多段离线行程采用单条汇总或受控分组，避免通知轰炸；
- `PendingIntent` requestCode 和 flags 支持多车辆、多行程；
- 处理 Activity 已存在、冷启动和进程死亡三种路径；
- 不将报告通知设置成无法关闭的 ongoing 通知。

### 自动验证

- 通知构建纯函数测试；
- deeplink 参数解析测试；
- notification permission granted/denied；
- 冷启动和 warm start 导航；
- 重复投递；
- 多车辆 requestCode；
- notification tap；
- 无报告记录时的安全回退。

### 人工审核

- 锁屏隐私；
- 标题/正文格式；
- 通知权限被拒绝后的用户体验；
- 点击是否进入正确行程；
- 是否存在打开旧行程或错误车辆的风险；
- 前台和后台是否产生双重提示。

### 通过标准

- 前台只出现一次 App 内提醒；
- 后台只出现一次系统提醒；
- 点击始终落到正确报告；
- 无通知权限也不丢失报告。

### 阶段候选提交

```text
feat(android): deliver completed-drive report notifications
test(android): verify notification privacy and deep links
```

---

## A5. Android 行驶报告页面

### 执行

页面结构：

```text
全屏/大面积路线地图或地图占位
└── 深色半透明、可折叠报告卡
    ├── 起点/终点
    ├── 时间与持续时间
    ├── 关键指标
    ├── 速度/功率曲线
    └── 展开、收起、地址显隐
```

必须支持：

- 地图 Key 已配置：显示路线；
- 未配置地图 Key：报告其余内容照常可用；
- 地图 SDK 错误：显示明确降级，不崩溃；
- 起终点默认脱敏，可临时显示；
- 真实零值、不可用、推导和估算使用不同文案；
- 能量无可靠来源时显示“不可用”；
- 费用没有能耗或电价时显示“不可用”；
- 没有速度/功率序列时不画假曲线；
- 时序采样过少时显示覆盖不足；
- 深色/浅色、横竖屏、字体缩放和中文布局；
- 语义化 contentDescription 和可触控区域；
- 路线点和图表数据量做上限控制；
- 返回、折叠和滚动状态正常；
- 从通知进入、从行程详情进入和恢复状态一致。

### 自动验证

- Compose JVM 测试；
- Compose UI 测试；
- 中英文格式契约测试；
- 所有格式化字符串的 `%` 与参数类型检查；
- Snapshot/截图对比可作为辅助，不替代行为测试；
- 空数据、部分数据、完整数据三组 fixture；
- 地图可用、未配置、失败三种状态；
- 深色/浅色；
- 大字体和窄屏。

### 人工审核

对照参考图逐项审核，但不复制错误语义：

- 起终点视觉层级；
- 卡片遮罩与地图可读性；
- 指标排列；
- 收起交互；
- 速度/功率图例；
- 长地址、省略和脱敏；
- “0”和“不可用”的区别；
- 估算费用是否明确；
- 数据来源和覆盖率是否能被用户理解。

### 通过标准

- 参考图的核心信息结构实现；
- 不伪造任何指标或曲线；
- 地图故障不影响报告主体；
- 中文界面无硬编码英文和截断问题。

### 阶段候选提交

```text
feat(android): add truthful completed-drive report UI
test(android): cover drive report states and localization
```

---

## A6. Android 集成、回归和真机验收

### 自动验证总门

```bash
cd android
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
git diff --check
```

Adapter 有改动时：

```bash
cd deploy/teslamate-home-docker/adapter
go test ./...
go vet ./...
```

安全检查：

- token/Key/密码扫描；
- 精确地址和真实个人数据扫描；
- 日志脱敏；
- 构建产物和缓存检查；
- Manifest 合并结果检查；
- Room migration schema 检查。

### 真机/模拟器矩阵

| 场景 | 必须验证 |
| --- | --- |
| 新安装 | 不补发历史行程 |
| 原版本覆盖安装 | 配置、数据库和历史记录保留 |
| App 前台 | 完成行程后只弹一次 |
| App 后台 | 只发一次系统通知 |
| 通知权限拒绝 | 待查看报告仍存在 |
| App 被杀 | 后续同步可恢复投递 |
| 设备重启 | 不重复通知 |
| 重复同步 | 不重复通知 |
| 多车辆 | 报告与车辆匹配 |
| 多段离线行程 | 不通知轰炸，队列完整 |
| 无地图 Key | 报告可用 |
| 地图失败 | 无崩溃 |
| 缺能耗 | 显示不可用 |
| 缺曲线 | 不绘制假线 |
| 中文/英文 | 文案完整 |
| 深色/浅色 | 可读性通过 |
| 大字体 | 无关键内容丢失 |
| 通知点击 | 冷/热启动均进入正确报告 |

### 审核要求

- 保留数据安装，不得 `pm clear`；
- 测试前后只核对“配置是否仍存在”，不读取秘密值；
- 完整操作后检查 crash markers；
- 至少一次真实或受控 TeslaMate 数据链路；
- 如果真实行程无法即时制造，使用固定测试 fixture 验证逻辑，并明确真车证据尚未完成。

### 通过标准

- 自动门全绿；
- 设备矩阵无 P0/P1；
- 无崩溃；
- 用户配置保留；
- 没有假数据；
- 所有要求均有代码、测试和设备证据映射。

---

## A7. Android 最终审计、Draft PR 与合并门

### 执行

- 更新执行台账；
- 列出所有提交；
- 比较 `main...feature/20260820-drive-completion-report`；
- 检查意外文件、二进制、缓存和秘密；
- 建立 Draft PR；
- PR 中逐项链接测试证据；
- 完成自审；
- 解决所有 review thread；
- CI 通过后标记 Ready for Review；
- 未经 Jovi 最终批准，不合并 `main`。

### 最终审核清单

- [ ] 所有需求有证据；
- [ ] 所有测试有命令和结果；
- [ ] 所有迁移有升级证明；
- [ ] 所有通知有隐私审查；
- [ ] 所有 UI 状态有截图或设备证据；
- [ ] 所有数据有来源和缺失语义；
- [ ] 无硬编码秘密；
- [ ] 无无关重构；
- [ ] PR diff 可解释；
- [ ] 回滚方案可执行。

### Android 最终交付

- 功能分支；
- Draft/Ready PR；
- 提交 SHA 列表；
- 自动验证报告；
- 真机验收报告；
- 数据库迁移报告；
- API 契约；
- 用户可见变更说明；
- 尚未完成或环境受阻项。

---

# 第二部分：iOS 原生应用

## B0. Android 合并后重新冻结基线

Android 通过并合并后：

1. 拉取最新 `main`；
2. 记录 Android 最终 merge SHA；
3. 建立新分支，例如：

```text
feature/YYYYMMDD-ios-native-parity
```

4. 不在 Android 功能分支继续开发 iOS；
5. 重新建立基线和 CI 证据。

---

## B1. iOS 工程真实性审计与完整功能矩阵

### 执行

盘点：

- `project.yml`、XcodeGen、Podfile、Info.plist；
- 当前 Swift 文件是否真正被 target 引用；
- Bundle resources、字体、地图 SDK；
- Widget target、App Group、entitlements；
- Mock API 与真实 API；
- Keychain、UserDefaults、实例切换；
- 当前页面是否使用真实接口还是模拟数据；
- Android 所有 screens/services/workers/widgets；
- iOS 对应能力、替代方案和平台限制。

建立 `IOS_PARITY_MATRIX.md`，每项状态只允许：

- `NOT_IMPLEMENTED`
- `SOURCE_ONLY`
- `COMPILES`
- `TESTED`
- `DEVICE_VERIFIED`
- `DEFERRED_WITH_REASON`

### 审核

- 当前 iOS 代码不能因“文件存在”就算功能完成；
- 图表若由模拟数据生成，必须标记；
- 后台通知能力必须基于 Apple 平台验证结果；
- Widget 没有 target/entitlement/device proof 时不得声明完成。

---

## B2. iOS 工程与 CI 基础

### 执行

- 生成并固定 Xcode 工程；
- 设置最低系统版本、Bundle ID 占位和配置分层；
- Swift concurrency；
- URLSession API Client；
- Keychain；
- 本地持久化；
- 统一错误模型；
- Mock/Real 数据来源；
- 日志脱敏；
- GitHub Actions macOS 构建；
- SwiftLint/格式规则（若引入，需固定版本）；
- 单元测试 target 和 UI 测试 target。

### 自动验证

```bash
xcodegen generate
xcodebuild -scheme MateLink -sdk iphonesimulator build
xcodebuild -scheme MateLink -sdk iphonesimulator test
```

### 通过标准

- macOS CI 可重复生成、编译和测试；
- 不依赖开发者机器私有路径；
- secrets 只通过本地或 CI secret 注入；
- 当前工程结构有明确 source of truth。

---

## B3. iOS P0 功能组

按小阶段分别执行、验证、审核和提交：

1. 首次配置和真实连接；
2. 多实例和车辆切换；
3. Dashboard；
4. Drives 列表和详情；
5. Drive Report V1；
6. Charges 列表和详情；
7. Settings/About；
8. 错误态、空态、Mock/Real 标签；
9. 中文/英文资源；
10. 地图降级。

每个功能组均建立：

- API fixture；
- ViewModel/Store 单元测试；
- SwiftUI UI 测试；
- 模拟器截图；
- 真机 smoke；
- Android 语义对照审核。

---

## B4. iOS 行驶完成通知策略门

不得直接照搬 Android 后台轮询。

必须先做平台能力审计，并在以下方案中基于证据选择：

- App 活跃时本地检测；
- BGTaskScheduler 的受控刷新；
- 服务端/APNs 推送；
- 打开 App 时待查看队列；
- 多机制组合。

审核问题：

- 是否能达到用户期待的及时性；
- App 被系统挂起时是否可靠；
- 是否需要服务端扩展；
- 通知点击是否能恢复对应报告；
- 地址隐私；
- token 和 APNs 凭据边界；
- 失败后是否仍保留待查看报告。

任何未经设备和系统行为验证的后台方案不得标记为可靠。

---

## B5. iOS 完整功能对齐

按 Android 功能矩阵继续实现：

- Statistics；
- Battery；
- Range；
- Cost/Tariff；
- Timeline/Trips；
- Reports/Export；
- Sentry；
- Software Versions；
- 地图相关页面；
- Widget；
- 其他 Android 已验收功能。

每一项只有达到 `DEVICE_VERIFIED` 才能计入完整对齐；暂不适合 iOS 的功能要写清原因和替代方案。

---

## B6. iOS 最终验收

验证维度：

- macOS CI；
- Xcode 单元测试；
- XCUITest；
- iPhone 模拟器矩阵；
- 至少一台真机；
- 前后台切换；
- 网络断开与恢复；
- token 失效；
- 多实例切换；
- 深色/浅色；
- 动态字体；
- 中文/英文；
- 内存与崩溃；
- 隐私清单；
- TestFlight 级 Archive；
- 无真实秘密和个人数据入库。

最终通过后建立独立 iOS PR，等待 Jovi 审核和批准合并。

---

# 第三部分：提交策略

## 1. 提交原则

- 每个提交只包含一个可独立解释的阶段；
- 提交前先列出精确候选文件和用途；
- 审核通过后再提交；
- 不使用一个“巨大最终提交”；
- 不在一个提交中混合文档、数据库迁移、通知、UI 和 iOS；
- 修复审核问题使用新提交，最终是否 squash 由 PR 合并策略决定；
- 每次提交后记录 SHA 和测试结果。

## 2. Android 建议提交序列

```text
docs(plan): define gated drive report execution
docs(audit): record implementation baseline
docs(contract): define Drive Report V1
feat(adapter): expose truthful drive report data          # 仅在确有必要时
test(adapter): cover report contract and errors           # 仅在确有必要时
feat(android): persist drive report delivery state
test(android): cover migrations watermark and dedupe
feat(android): add report notifications and deep links
test(android): cover notification delivery and privacy
feat(android): add completed-drive report UI
test(android): cover report states localization and maps
docs(qa): record Android drive report qualification
```

## 3. iOS 建议提交序列

```text
docs(ios): freeze Android-to-iOS parity matrix
build(ios): establish reproducible Xcode project and CI
feat(ios): add secure real-data configuration
feat(ios): add vehicle and dashboard flows
feat(ios): add drives and Drive Report V1
feat(ios): add charges and cost semantics
feat(ios): add remaining feature parity groups
feat(ios): add verified notification flow
test(ios): complete unit UI and device qualification
docs(ios): record final parity and proof boundaries
```

---

# 第四部分：停止条件与回滚

出现以下任一情况立即停止当前阶段：

- 分支基线不正确；
- 工作树包含来源不明改动；
- 数据库迁移会删除或重建用户数据；
- 测试修改真实用户配置；
- 通知或日志暴露精确地址、token 或 Key；
- 上游接口无法支持关键字段但 UI 准备伪造；
- Android 出现崩溃；
- iOS 未经过 Xcode/macOS 但准备声明编译通过；
- 需求范围扩展到当前阶段以外；
- PR 出现无法解释的二进制或大文件；
- 自动验证失败；
- 审核发现 P0/P1 问题。

回滚策略：

- 尚未提交：恢复当前阶段候选文件；
- 已提交未推主干：revert 阶段提交，不重写 `main`；
- PR 中：保持 Draft，新增修复提交；
- 数据库：提供前向修复迁移，禁止通过清数据恢复；
- Adapter：保留旧接口兼容并可关闭新路由；
- 通知：通过配置开关停止新投递，但保留报告记录。

---

# 第五部分：执行台账格式

每个阶段必须填写：

```text
阶段：
状态：
基线 SHA：
开始时间：
结束时间：
实施文件：
实施摘要：
自动验证命令：
自动验证结果：
设备/人工验证：
审核发现：
修复记录：
剩余风险：
候选提交文件：
提交批准：
提交 SHA：
PR：
结论：
```

状态变更必须有证据，不接受只写“完成”。

---

# 第六部分：当前立即动作

已完成：

- [x] 核实仓库和 `main`；
- [x] 冻结当前基线；
- [x] 创建 Android 第一阶段分支：
  `feature/20260820-drive-completion-report`；
- [x] 制定主执行方案；
- [x] 制定逐阶段审核台账模板。

尚未执行：

- [x] 获得 Jovi 对计划文件提交与 A0 执行的明确批准；
- [x] 向 GitHub 功能分支提交计划文件；
- [ ] A0 基线环境审计；
- [ ] 任何源码修改。

## 首次候选提交文件

Jovi 已明确批准提交以下精确文件：

1. `docs/superpowers/plans/2026-08-20-drive-completion-report-ios-execution.md`  
   用途：本主执行方案、阶段门禁、测试矩阵和提交策略。

2. `tasks/drive-report-ios-execution-ledger.md`  
   用途：逐阶段记录实施、验证、审核、批准、提交 SHA 和阻塞项。

首次提交消息：

```text
docs(plan): define gated drive report and iOS execution
```

首次提交不包含任何 Android、Adapter 或 iOS 源码。
