# Tesla MateLink MIMO

Language / 语言: [中文](#中文) | [English](#english)

---

## 中文

### 项目定位

`app_mimo` 是 Tesla MateLink 当前主力应用工程。它的近期目标是把 Stitch 项目“MateLink - Tesla 监控（简约白）”复刻成可运行的双端应用，并在这个高保真壳子基础上逐步接入真实 TeslaMate 数据。

当前开发策略是 Android 先行、iOS 跟进、Web 辅助验证：

- Android 作为第一阶段基线端，优先完成页面结构、底部导航、更多入口、详情页跳转和 mock/real 数据边界。
- iOS 使用同一套产品信息架构和页面命名，保持与 Android 的功能入口一致，同时适配 SwiftUI、安全区和 Apple 平台交互习惯。
- Web 客户端用于浏览器端页面验证、数据展示实验和桌面调试，不替代原生端。

### 核心功能

| 功能域 | 说明 |
| --- | --- |
| 仪表盘 | 展示车辆电量、续航、在线状态、锁车状态、空调、温度、胎压、哨兵和近期能耗。 |
| 行程 | 行程历史、行程详情、位置详情、里程、能耗、效率和目的地信息。 |
| 充电 | 充电历史、当前充电、充电详情、费用估算和电价配置。 |
| 分析 | 续航趋势、能耗分析、热力图、电池健康、待机耗电、年度报告和导出入口。 |
| 更多与设置 | 服务地址配置、mock/real 模式、连接测试、关于页面、调试状态和 deferred 功能说明。 |
| 小组件 | Android Widget 源码已存在；iOS Widget 源码存在但本轮仍标记为 deferred，尚未完成 target、entitlements 和 App Group wiring。 |

### 工程结构

```text
app_mimo/
|- android/                 Android app, Kotlin, Jetpack Compose
|  `- app/src/main/java/com/matelink/
|     |- data/              API, repository, mock/real data boundary
|     |- ui/                Compose screens, navigation, theme
|     `- widget/            Android widget source
|- ios/                     iOS app, SwiftUI, XcodeGen, CocoaPods entry
|  |- MateLink/             App source
|  |- project.yml           XcodeGen project definition
|  `- Podfile               CocoaPods dependencies
|- shared/                  Shared API/model definitions and mock data
|- web_matelink/            Vite + React web client
`- docs/                    Page mapping, audit notes, verification status
```

### 数据模式

项目明确区分 mock 和 real 模式：

- mock mode：用于离线开发、页面还原和演示，不应该访问真实 TeslaMate 服务。
- real mode：通过用户配置的 TeslaMate API 地址访问真实数据；失败时应显示明确的错误或 unavailable 状态，不应静默回退成 mock。
- shared/mock 数据只用于开发占位，真实数据协议仍保留可替换 adapter 边界。

### Android 开发

要求：

- Android Studio
- JDK 17
- Android SDK 与 Gradle 环境

常用命令：

```powershell
cd E:\project\tesla_master\app_mimo\android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

当前说明：

- Android 页面壳子、底部导航、更多入口和主要详情页已经具备。
- 部分分析类页面仍处于 mock/placeholder 阶段，页面应清楚标注数据来源，避免把演示数据伪装成真实数据。
- 本地生成目录如 `.gradle/`、`.idea/`、`app/build/` 和 `local.properties` 不应提交。

### iOS 开发

要求：

- Mac
- Xcode
- XcodeGen
- CocoaPods

Mac 侧生成与打开方式：

```bash
cd app_mimo/ios
brew install xcodegen cocoapods
xcodegen generate
pod install
open MateLink.xcworkspace
```

注意：

- 使用 CocoaPods 后应打开 `.xcworkspace`，不要直接打开裸 `.xcodeproj`。
- Windows 侧只能完成源码检查和工程文件准备，不能声明 iOS 编译、签名或真机验证通过。
- iOS Widget 当前是 `deferred / source exists but target not wired`，不能当作已完成能力验收。

### Web 开发

```powershell
cd E:\project\tesla_master\app_mimo\web_matelink
npm install
npm run dev
npm run build
```

技术栈包括 Vite、React、TypeScript、React Router、Zustand、Recharts、Leaflet 和 Tailwind CSS。

### 验收重点

- 页面结构：Stitch 页面都有对应 app 页面或明确 deferred 说明。
- 交互闭环：底部导航、更多入口、历史列表、详情页和设置按钮应可点击并进入正确页面。
- 数据诚实：mock 与 real 模式不可混淆，真实请求失败必须显示错误/空态。
- 跨端一致：Android 和 iOS 保持同一信息架构，允许平台交互细节不同。
- 工程边界：`docs/git_ref` 只读；提交前先拉取远端；子仓库单独提交和推送。

### Git

当前远端：

```text
https://github.com/Jovifei/tesla-master-mimo.git
```

推荐流程：

```powershell
cd E:\project\tesla_master\app_mimo
git pull --rebase --autostash origin main
git status
git add <changed-files>
git commit -m "docs: describe app_mimo"
git push origin main
```

---

## English

### Purpose

`app_mimo` is the current primary Tesla MateLink application project. Its near-term goal is to reproduce the Stitch project “MateLink - Tesla Monitor (Minimal White)” as a runnable Android + iOS app, then use that high-fidelity shell as the foundation for real TeslaMate data integration.

The development strategy is Android-first, iOS-following, with Web support for inspection:

- Android is the baseline implementation for page structure, bottom navigation, More entries, detail navigation, and mock/real data boundaries.
- iOS follows the same product information architecture and screen naming, while adapting to SwiftUI, safe areas, and Apple platform behavior.
- The Web client supports browser-side validation, data display experiments, and desktop debugging. It does not replace the native apps.

### Core Features

| Area | Description |
| --- | --- |
| Dashboard | Vehicle battery, range, online state, lock state, climate, temperature, tire pressure, sentry mode, and recent energy usage. |
| Trips | Drive history, drive detail, location detail, mileage, consumption, efficiency, and destinations. |
| Charging | Charge history, current charge, charge detail, cost estimates, and tariff configuration. |
| Analytics | Range trends, energy analysis, heatmap, battery health, vampire drain, annual report, and export entry points. |
| More and Settings | Server configuration, mock/real mode, connection testing, about screen, debug status, and deferred feature notes. |
| Widgets | Android Widget source exists. iOS Widget source exists but remains deferred until target, entitlements, and App Group wiring are completed. |

### Project Structure

```text
app_mimo/
|- android/                 Android app, Kotlin, Jetpack Compose
|  `- app/src/main/java/com/matelink/
|     |- data/              API, repository, mock/real data boundary
|     |- ui/                Compose screens, navigation, theme
|     `- widget/            Android widget source
|- ios/                     iOS app, SwiftUI, XcodeGen, CocoaPods entry
|  |- MateLink/             App source
|  |- project.yml           XcodeGen project definition
|  `- Podfile               CocoaPods dependencies
|- shared/                  Shared API/model definitions and mock data
|- web_matelink/            Vite + React web client
`- docs/                    Page mapping, audit notes, verification status
```

### Data Modes

The app intentionally separates mock mode and real mode:

- mock mode is for offline development, page reproduction, and demos. It should not call a real TeslaMate service.
- real mode uses the configured TeslaMate API base URL. Failures should show explicit error or unavailable states, not silently fall back to mock.
- shared mock data is only a development placeholder. The real data protocol remains behind replaceable adapter boundaries.

### Android Development

Requirements:

- Android Studio
- JDK 17
- Android SDK and Gradle tooling

Common commands:

```powershell
cd E:\project\tesla_master\app_mimo\android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Current notes:

- Android has the main shell, bottom navigation, More entries, and key detail screens.
- Some analytics pages are still mock or placeholder-driven. Their data source should remain visible and honest.
- Local generated directories such as `.gradle/`, `.idea/`, `app/build/`, and `local.properties` should not be committed.

### iOS Development

Requirements:

- Mac
- Xcode
- XcodeGen
- CocoaPods

Generate and open on Mac:

```bash
cd app_mimo/ios
brew install xcodegen cocoapods
xcodegen generate
pod install
open MateLink.xcworkspace
```

Notes:

- After CocoaPods integration, open `.xcworkspace`, not the bare `.xcodeproj`.
- Windows can prepare and inspect source files, but cannot prove iOS compilation, signing, or device validation.
- iOS Widget status is `deferred / source exists but target not wired`; it should not be accepted as a finished feature yet.

### Web Development

```powershell
cd E:\project\tesla_master\app_mimo\web_matelink
npm install
npm run dev
npm run build
```

The Web stack includes Vite, React, TypeScript, React Router, Zustand, Recharts, Leaflet, and Tailwind CSS.

### Acceptance Focus

- Page coverage: every Stitch page should have an app screen or a clear deferred note.
- Interaction closure: bottom tabs, More entries, history lists, detail pages, and settings actions should navigate correctly.
- Data honesty: mock and real modes must stay separate; real request failures must show error/empty states.
- Cross-platform consistency: Android and iOS should share the same information architecture, with platform-specific interaction details allowed.
- Repository boundary: `docs/git_ref` is read-only reference material; pull before committing; commit and push this child repository separately.

### Git

Remote:

```text
https://github.com/Jovifei/tesla-master-mimo.git
```

Recommended workflow:

```powershell
cd E:\project\tesla_master\app_mimo
git pull --rebase --autostash origin main
git status
git add <changed-files>
git commit -m "docs: describe app_mimo"
git push origin main
```
