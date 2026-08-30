# iOS Apple 重设计交接 — 2026-08-30

> 分支：`feature/ios-apple-redesign`（禁止提交到 `main`）
> 远端：`https://github.com/Jovifei/tesla-master-mimo.git`
> 工作区：`app_mimo/ios/`
> 对照基线：Android `com.matelink`
> 本地 HEAD（本文档提交前）：`f72a4ec`

给后续 agent：先读本文，再读 `ios/VERIFY_IOS.md`。不要在 `main` 上继续 iOS 工作。

---

## 现在完成了什么

三层都在这条分支上，Windows 源码已齐，**尚未 Mac 编译证明**。

### 1. Apple 原生核心页（已推送至 `28ef539`）

设计系统 `MateTheme` / `CarColorPalette` / `MateAnimation`；类型安全 `Route` + `RouteDestinationView`；Dashboard / 行程 / 充电 / 电池 / Settings / Onboarding / More；分页 API、曲线模型、LTTB、en/zh-Hans/ja。

### 2. 车辆核心逻辑对齐（`e9666f7`）

- 门/窗/前后备箱：`VehicleStatusPresentation` = Android `openVehicleOpenings`
- 充电面板仅 `CarStatus.isCharging`
- 行程/充电详情：`positions` / `charge_points` 真实曲线与轨迹
- 当前充电：30s / 起步 4s 轮询、DC 满电未拔枪警告、无活跃充电返回 `nil`
- 换车写入 instance，启动时恢复 `currentCarId`

### 3. 列表、snapshot、分析与占位页（`f72a4ec`）

- Dashboard：优先 `/api/matelink/v1/cars/{id}/snapshot`（嵌套 `data.status`），失败再 `/status`；Live/Recent/History/Mock 徽章
- 行程列表：日期筛选默认 All time、距离桶、短行程 1 min / 0.5 km
- 充电列表：日期默认最近 7 天、AC/DC、有无费用、短充 0.1 kWh
- 分析页全量分页：`getAllDrives` / `getAllCharges`（不再只取 TeslaMate 第一页）
- Efficiency 加权效率 + `speed_avg` 20 km/h 桶；Range 的 `diff` 改为额定续航差；Cost 地点按总花费 Top 5
- Vampire：standby API，资格 ≥2h、覆盖 ≥80%、默认 30 天；失败再按行程间隔回退
- 长途 `TripDetector`（300 km + 两段行程 + 一次 DC）
- 到访国家（地址末段）、Where Was I（行驶/充电/停车）、TPMS 7/30 天（Dashboard 刷新采样）
- More 入口已挂上 Long Trips / Countries / Where Was I；Dashboard 胎压可点进趋势

---

## 卡在什么地方

这些不是“还没搬公式”，是当前环境或平台栈拦住了。

| 卡点 | 原因 | 影响 |
|------|------|------|
| **没有 Mac / Xcode 证明** | 本机是 Windows，不能跑 `xcodebuild` / 模拟器 / 签名 | 不能宣称编译通过或可上架 |
| **Widget 未接线** | `MateLink/Widget` 有源码，`project.yml` 无 extension、无 entitlements、App Group 未验证 | 不能把小组件当已交付 |
| **无 Room / WorkManager / MQTT** | Android Stats、Export、Sentry、TPMS 历史依赖本地库和后台 Worker | iOS 用 REST 分页 + UserDefaults 采样近似，不是同一套后台 |
| **Sentry 真机采集** | Android 写本地告警日志，不是 TeslaMate 列表 API | iOS real 模式仍无对等采集通道 |
| **系统通知 / 高德原生 SDK / Watch** | 需要 APNs、高德 iOS Key、Watch target | 地图目前 MapKit 回退 |
| **字体文件可能未入库** | `VERIFY_IOS.md` 检查项 5：`Inter-*.ttf` / `JetBrainsMono-*.ttf` | Mac 上若缺字体，回退系统字体 |

Android/deploy 工作区里还有**未纳入本分支 iOS 提交**的改动（Tesla 登录文案、jourvolt-dev-mock）。不要把它们混进 iOS commit。

---

## 将来需要完成什么

按顺序，仍在 `feature/ios-apple-redesign` 或后续 `feature/ios-*`。

### 立刻（Mac，阻塞验收）

```bash
cd app_mimo/ios
xcodegen generate
pod install
xcodebuild -workspace MateLink.xcworkspace -scheme MateLink \
  -destination 'platform=iOS Simulator,name=iPhone 16' build
```

打开 `MateLink.xcworkspace`，不要打开裸 `.xcodeproj`。清单：`ios/VERIFY_IOS.md`。

Mock 手测：门告警、充电面板、列表筛选（充电默认 7 天）、snapshot 徽章、详情曲线、CurrentCharge 轮询/DC 警告、换车重启、Long Trips / TPMS / Countries / Where Was I。

### 随后（平台能力，独立任务）

1. Widget：`project.yml` extension + entitlements + App Group `group.com.matelink` 真机验证
2. 后台刷新 / 通知：对齐 Android TPMS/Sentry `NotificationCompat`
3. 高德 iOS SDK（现为 MapKit）
4. Sentry 真机采集（若产品需要，不能假装 TeslaMate REST 已有）
5. Apple Watch（Android 无对等物，属 iOS 增量）

### 不要做

- 不要 `git push origin main`
- 不要把 Android OAuth / deploy mock 改动并进 iOS 提交
- 不要把 mock 失败静默回退成假数据

---

## 关键路径

| 路径 | 角色 |
|------|------|
| `ios/MateLink/Core/Utils/VehicleStatusPresentation.swift` | 开口告警 |
| `ios/MateLink/Core/Utils/HistoryDateFilter.swift` | 日期/距离/充电筛选常量 |
| `ios/MateLink/Core/Utils/TripDetector.swift` | 长途检测 |
| `ios/MateLink/Core/Utils/TpmsSampleStore.swift` | 胎压本地采样 |
| `ios/MateLink/Core/API/ApiClient.swift` | snapshot / standby / 分页 / current charge |
| `ios/MateLink/Features/Dashboard/DashboardView.swift` | snapshot 优先 + 胎压入口 |
| `ios/MateLink/Features/Drives/DriveListView.swift` | 行程筛选 |
| `ios/MateLink/Features/Charges/ChargeListView.swift` | 充电筛选 |
| `ios/MateLink/Features/Trips/TripsView.swift` | 长途 |
| `ios/MateLink/Features/Tpms/TpmsTrendView.swift` | 胎压趋势 |
| `ios/VERIFY_IOS.md` | Mac 验证 |

## 提交记录（本分支 iOS 相关）

| Commit | 内容 |
|--------|------|
| `28ef539` | Settings / Onboarding / More Apple 重写 |
| `e9666f7` | 车辆核心逻辑对齐 |
| `f72a4ec` | 列表筛选、snapshot、分析全量、Trips/TPMS/Countries/WhereWasI |
