# Tesla MateLink: 多端 UI 现状取证与远端设计交接主索引 (INDEX)

> **交接目标**: 为 Tesla_MateLink 项目远端设计（ChatGPT / Figma）团队提供真实、详尽、免唤醒、免失真的本地 Android (`app_mimo`) UI 现状基准、全量页面清单、视觉证据、交互录屏、设计 Token 与优化建议。  
> **报告主体**: 本地 Android 研发代理 (Antigravity Local Pairing Assistant)  
> **交接对象**: 远端设计团队 (Tesla_MateLink Remote Design Agent / ChatGPT / Figma)  
> **基准日期**: 2026-11-09  
> **代码分支**: `codex/ui-evidence-20261108` @ `050568b` (基于 v2.1.87)

---

## 一、交付物全景概览

| 交付文件 | 类型 | 规格/规模 | 说明与用途 |
| :--- | :--- | :--- | :--- |
| **[`OVERVIEW-GRID.png`](file:///docs/ui-evidence/screenshots/OVERVIEW-GRID.png)** | 视觉全景 | 2880 x 3120 px (1.56 MB) | 4x4 核心界面带标注全景画板（16个核心界面一览） |
| **[`screenshots/SCR-*.png`](file:///docs/ui-evidence/screenshots/)** | 原始截图 | 34 张 1440x3120 无损 PNG | 真机全分辨率只读视觉取证，涵盖各功能状态与弹窗 |
| **[`interaction_flow.mp4`](file:///docs/ui-evidence/videos/interaction_flow.mp4)** | 交互录屏 | 14 秒 MP4 (832 KB) | 底部 4 Tab 切换与滑动流畅度、转场物理与消除残影验证 |
| **[`screen-inventory.csv`](file:///docs/ui-evidence/screen-inventory.csv)** | 页面清单 | 34 个路由页面/弹窗 (13.7 KB) | 涵盖进入路径、用户任务、主要数据/单位、操作及源码位置 |
| **[`current-design-tokens.json`](file:///docs/ui-evidence/current-design-tokens.json)** | 设计规范 | 5.7 KB JSON | 现有色板、排版规范、AppleTokens 边距圆角、动画弹簧参数 |
| **[`ui-findings.md`](file:///docs/ui-evidence/ui-findings.md)** | 体验报告 | 8.0 KB Markdown | 归纳 P1 体验痛点、P2 视觉微调、已修复项及具体代码位置 |
| **[`manifest.json`](file:///docs/ui-evidence/manifest.json)** | 元数据清单 | 2.9 KB JSON | 设备运行环境、多端 Git 审计、生产服务端点与安全承诺 |
| **[`SHA256SUMS.txt`](file:///docs/ui-evidence/SHA256SUMS.txt)** | 完整性校验 | SHA-256 校验文件 | 本次交接全量交付文件的哈希签名，确保无损流转 |

---

## 二、真实环境与版本审计

### 1. 本地真机采集环境
- **测试设备**: OnePlus 7 Pro (`GM1910`), 设备标识 `6e4fa92f`
- **操作系统**: Android 11 (API 30), 原生 OxygenOS / HydrogenOS 内核
- **屏幕分辨率**: 1440 x 3120 px, 物理点密度 600 dpi
- **逻辑视口**: 384 x 832 dp (比例尺 1dp = 3.75px)
- **字体缩放**: 1.0 (标准), 系统语言: 简体中文 (`zh-CN`)
- **已装版本**: `com.matelink` v2.1.87 (VersionCode: 107, 对应 Git 节点 `050568b`)
- **生产服务端点**: `https://api.teslalink.joviluma.com/` (HTTP 200 OK 正常提供遥测数据)

### 2. 多端 Git 仓库真实版本对照

| 端名称 | 仓库物理路径 | 分支 (Branch) | 完整 Commit SHA | Tree SHA | Dirty 状态 | 已安装/部署状态 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Android** | `E:\project\tesla_master\app_mimo` | `codex/ui-evidence-20261108` | `050568b734429c65755b9cc202e72c1d4ec8cc45` | `47816a6172ad875c...` | Clean (未改业务代码) | **真机实测一致** (v2.1.87 / 107) |
| **小程序** | `E:\project\tesla_master\miniprogram_Weixin` | `codex/miniprogram-e-migration-20261029` | `4ee0c0479450a27a10e3a845f063e7ddd49f6b23` | `c4f9e25b2c50a06b...` | Dirty (存在本地临时改动) | 微信开发者工具就绪 / 手机需扫码预览 |
| **Web 网页** | `E:\project\tesla_master\web_matelink` | `main` | `717a343741a2678cbff33cfbe35d6be8db33e086` | `240aee4c9b828944...` | Dirty (本地有构建痕迹) | 线上通过 Nginx 运行对应构建物 |
| **iOS** | `E:\project\tesla_ios` | `feature/ios-apple-redesign` | `18a0b65a9b3ccb39acdd695f95883f9e13522c5e` | `e81c1efd1988b285...` | Clean | **UNKNOWN** (本地 Windows 无 Mac/真机) |

---

## 三、逐页视觉证据与功能映射表

所有截图均存放于 [screenshots/](file:///docs/ui-evidence/screenshots/) 目录下。点击对应文件即可查看真机原始 1440x3120 高清无损画面：

### 1. 仪表盘 (Dashboard Tab)
| Screen ID | 界面状态 | 截图文件 | 核心数据与指标 | 源码组件位置 |
| :--- | :--- | :--- | :--- | :--- |
| `SCR-01` | 车辆英雄卡片 & 快捷操作 | [SCR-01-DASHBOARD-TOP.png](file:///docs/ui-evidence/screenshots/SCR-01-DASHBOARD-TOP.png) | 车辆在线、剩余电量 85%、续航 335km、四快捷按钮（锁/枪/空调/哨兵） | [`VehicleHeroCard.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/dashboard/VehicleHeroCard.kt) |
| `SCR-02` | 胎压、高德地图与最近行程 | [SCR-02-DASHBOARD-BOTTOM.png](file:///docs/ui-evidence/screenshots/SCR-02-DASHBOARD-BOTTOM.png) | 四轮 2.7~2.8 bar、杭州高德实时位置地图、最近行程卡片 | [`DashboardScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/dashboard/DashboardScreen.kt) |
| `SCR-02B` | 附近充电站与停车策略 | [SCR-02B-DASHBOARD-FOOTER.png](file:///docs/ui-evidence/screenshots/SCR-02B-DASHBOARD-FOOTER.png) | 谷电价 0.72元/度、尖峰 1.48元/度、2小时免费停车规则、车位空闲比 | [`NearbyChargingCard.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/dashboard/NearbyChargingCard.kt) |
| `SCR-02C` | 我的地点快捷收藏 | [SCR-02C-DASHBOARD-MYPLACES.png](file:///docs/ui-evidence/screenshots/SCR-02C-DASHBOARD-MYPLACES.png) | 常用驻车/充电位置标签、存为地点操作入口 | [`DashboardScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/dashboard/DashboardScreen.kt) |
| `SCR-03` | 安全防误触二次确认弹窗 | [SCR-03-SAFETY-CONFIRM-DIALOG.png](file:///docs/ui-evidence/screenshots/SCR-03-SAFETY-CONFIRM-DIALOG.png) | 关闭哨兵二次防误触对话框（遵循零下发安全规约） | [`DashboardScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/dashboard/DashboardScreen.kt) |

### 2. 行程历史与轨迹 (Drives Tab)
| Screen ID | 界面状态 | 截图文件 | 核心数据与指标 | 源码组件位置 |
| :--- | :--- | :--- | :--- | :--- |
| `SCR-04` | 每日行程频次柱状图 | [SCR-04-DRIVES-LIST.png](file:///docs/ui-evidence/screenshots/SCR-04-DRIVES-LIST.png) | 7天行程频次柱状图、顶部汇总统计 | [`DrivesScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/drives/DrivesScreen.kt) |
| `SCR-04B` | 大字号行程卡片与驻车块 | [SCR-04B-DRIVES-LIST-SCROLLED.png](file:///docs/ui-evidence/screenshots/SCR-04B-DRIVES-LIST-SCROLLED.png) | 18sp 加粗指标（能耗 13.4 kWh/100km、里程 4.5km、时长 16min）、驻车卡片区分底色 | [`DrivesScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/drives/DrivesScreen.kt) |
| `SCR-05` | 行程详情 (高德真实轨迹) | [SCR-05-DRIVE-DETAIL.png](file:///docs/ui-evidence/screenshots/SCR-05-DRIVE-DETAIL.png) | 真实青色街道级 GPS 轨迹折线、起点终点地名 | [`DriveDetailScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/drives/DriveDetailScreen.kt) |
| `SCR-05B` | 行程内嵌速度趋势 | [SCR-05B-DRIVE-DETAIL-CURVES.png](file:///docs/ui-evidence/screenshots/SCR-05B-DRIVE-DETAIL-CURVES.png) | 行程详情内嵌速度曲线折线图与能量回收区间 | [`DriveDetailScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/drives/DriveDetailScreen.kt) |
| `SCR-06` | 完整速度与海拔采样分析 | [SCR-06-DRIVE-CURVES.png](file:///docs/ui-evidence/screenshots/SCR-06-DRIVE-CURVES.png) | 55 km/h 峰值速度、平均 17 km/h、最高/最低样本曲线 | [`DriveCurvesScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/drives/DriveCurvesScreen.kt) |
| `SCR-07` | 驻车详情 (回退骨架状态) | [SCR-07-PARKED-DETAIL.png](file:///docs/ui-evidence/screenshots/SCR-07-PARKED-DETAIL.png) | 驻车暂不可用回退空态（P1 优化项） | [`ParkedDetailScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/drives/ParkedDetailScreen.kt) |

### 3. 充电记录与账单 (Charges Tab)
| Screen ID | 界面状态 | 截图文件 | 核心数据与指标 | 源码组件位置 |
| :--- | :--- | :--- | :--- | :--- |
| `SCR-08` | 每日充入电量统计直方图 | [SCR-08-CHARGES-LIST.png](file:///docs/ui-evidence/screenshots/SCR-08-CHARGES-LIST.png) | 充电频次、充入总电量分布直方图 | [`ChargesScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/charges/ChargesScreen.kt) |
| `SCR-08B` | 逆地理中文地名充电流水 | [SCR-08B-CHARGES-LIST-SCROLLED.png](file:///docs/ui-evidence/screenshots/SCR-08B-CHARGES-LIST-SCROLLED.png) | 余杭美塘广场、快充 39.0度 (¥50.00)、慢充 1.2度 | [`ChargesScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/charges/ChargesScreen.kt) |
| `SCR-09` | 充电详情 (电量与功率曲线) | [SCR-09-CHARGE-DETAIL.png](file:///docs/ui-evidence/screenshots/SCR-09-CHARGE-DETAIL.png) | 93 kW 峰值充电功率、15%->83% SOC 爬升曲线、电费单价 | [`ChargeDetailScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/charges/ChargeDetailScreen.kt) |
| `SCR-10` | 电费价格自定义弹窗 | [SCR-10-CHARGE-COST-EDIT.png](file:///docs/ui-evidence/screenshots/SCR-10-CHARGE-COST-EDIT.png) | 充电账单费用/单价修改模态弹窗 | [`ChargesScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/charges/ChargesScreen.kt) |

### 4. 深度分析与更多中心 (More Hub & Analytical Screens)
| Screen ID | 界面状态 | 截图文件 | 核心数据与指标 | 源码组件位置 |
| :--- | :--- | :--- | :--- | :--- |
| `SCR-11` | 更多中心 (分组列表规范) | [SCR-11-MORE-MENU.png](file:///docs/ui-evidence/screenshots/SCR-11-MORE-MENU.png) | 苹果分组列表样式、8 项分析工具入口 | [`MoreScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/more/MoreScreen.kt) |
| `SCR-11B` | 更多中心 (系统设置区域) | [SCR-11B-MORE-MENU-SCROLLED.png](file:///docs/ui-evidence/screenshots/SCR-11B-MORE-MENU-SCROLLED.png) | 账户、车机凭证、软件更新与关于入口 | [`MoreScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/more/MoreScreen.kt) |
| `SCR-12` | 电池健康与历史衰减曲线 | [SCR-12-BATTERY-HEALTH.png](file:///docs/ui-evidence/screenshots/SCR-12-BATTERY-HEALTH.png) | 99.5% 健康度环形图、0.5% 衰减、可用容量 59.8 kWh | [`BatteryHealthScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/battery/BatteryHealthScreen.kt) |
| `SCR-13` | 能耗趋势多维分析 | [SCR-13-ENERGY-ANALYSIS.png](file:///docs/ui-evidence/screenshots/SCR-13-ENERGY-ANALYSIS.png) | 平均 149.4 Wh/km、各温度区间能耗对比 | [`EnergyAnalysisScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/energy/EnergyAnalysisScreen.kt) |
| `SCR-13B` | 里程统计分布 | [SCR-13B-MILEAGE.png](file:///docs/ui-evidence/screenshots/SCR-13B-MILEAGE.png) | 3,172 km 总统计、周/月/年度里程直方图 | [`MileageScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/mileage/MileageScreen.kt) |
| `SCR-14` | 费用与补能成本审计 | [SCR-14-COST-ANALYSIS.png](file:///docs/ui-evidence/screenshots/SCR-14-COST-ANALYSIS.png) | 每公里成本 0.18 元、家充/超充/第三方支出饼图 | [`CostAnalysisScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/cost/CostAnalysisScreen.kt) |
| `SCR-15` | 个性化续航预测模型 | [SCR-15-RANGE-ANALYSIS.png](file:///docs/ui-evidence/screenshots/SCR-15-RANGE-ANALYSIS.png) | 基于驾驶风格预估续航、93% 置信度模型 | [`RangeAnalysisScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/range/RangeAnalysisScreen.kt) |
| `SCR-16` | 待机吸血鬼耗电 (Vampire) | [SCR-16-VAMPIRE-DRAIN.png](file:///docs/ui-evidence/screenshots/SCR-16-VAMPIRE-DRAIN.png) | 待机损耗计算占位（P2 优化项） | [`VampireDrainScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/vampire/VampireDrainScreen.kt) |
| `SCR-17` | 驾驶时光机 (时间线模式) | [SCR-17-TIMELINE.png](file:///docs/ui-evidence/screenshots/SCR-17-TIMELINE.png) | 时间轴流水视图、按日聚合出行动态 | [`TimelineScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/timeline/TimelineScreen.kt) |
| `SCR-18` | 胎压四轮底盘与30天趋势 | [SCR-18-TPMS-TREND.png](file:///docs/ui-evidence/screenshots/SCR-18-TPMS-TREND.png) | 俯视底盘四轮定位卡片、30天多色折线图（P1 优化项） | [`TpmsTrendScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/tpms/TpmsTrendScreen.kt) |
| `SCR-19` | 设置与官方授权凭证 | [SCR-19-SETTINGS.png](file:///docs/ui-evidence/screenshots/SCR-19-SETTINGS.png) | 授权状态有效、数据同步频率与缓存管理 | [`SettingsScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/settings/SettingsScreen.kt) |
| `SCR-19B` | 设置关于与版本卡片 | [SCR-19B-SETTINGS-BOTTOM.png](file:///docs/ui-evidence/screenshots/SCR-19B-SETTINGS-BOTTOM.png) | 当前版本 v2.1.87 (build 107)、检查更新 | [`SettingsScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/settings/SettingsScreen.kt) |
| `SCR-20` | 关于 MateLink | [SCR-20-ABOUT.png](file:///docs/ui-evidence/screenshots/SCR-20-ABOUT.png) | 架构技术栈、开源许可协议及致谢 | [`AboutScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/about/AboutScreen.kt) |
| `SCR-21` | 哨兵警报 7x24h 热力图 | [SCR-21-SENTRY-HISTORY.png](file:///docs/ui-evidence/screenshots/SCR-21-SENTRY-HISTORY.png) | 7天 24小时矩阵热力点阵、触发事件回溯 | [`SentryHistoryScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/sentry/SentryHistoryScreen.kt) |
| `SCR-23` | 车内外温控曲线监控 | [SCR-23-TEMPERATURE-TREND.png](file:///docs/ui-evidence/screenshots/SCR-23-TEMPERATURE-TREND.png) | 车内 22.3°C / 车外 20.0°C、历史温度折线（P1 优化项） | [`TemperatureTrendScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/temperature/TemperatureTrendScreen.kt) |
| `SCR-24` | 附近充电外呼导航面板 | [SCR-24-NEARBY-CHARGING-NAV-SHEET.png](file:///docs/ui-evidence/screenshots/SCR-24-NEARBY-CHARGING-NAV-SHEET.png) | 唤起高德地图 / 发送至车机导航底部选择浮层 | [`NearbyChargingCard.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/dashboard/NearbyChargingCard.kt) |
| `SCR-25` | 全局数据统计概览 | [SCR-25-STATS-OVERVIEW.png](file:///docs/ui-evidence/screenshots/SCR-25-STATS-OVERVIEW.png) | 总能耗、累计里程、充电总电量、总支出卡片 | [`StatsOverviewScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/stats/StatsOverviewScreen.kt) |
| `SCR-26` | 跨城长途行程归集 (Trips) | [SCR-26-TRIPS.png](file:///docs/ui-evidence/screenshots/SCR-26-TRIPS.png) | 自动合并连续驾驶的长途路程归档空态引导 | [`TripsScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/trips/TripsScreen.kt) |
| `SCR-28` | 行程完成自动触发展开弹窗 | [SCR-28-TRIP-COMPLETED-DIALOG.png](file:///docs/ui-evidence/screenshots/SCR-28-TRIP-COMPLETED-DIALOG.png) | 驻车结算摘要通知浮层 | [`DashboardScreen.kt`](file:///android/app/src/main/java/com/matelink/ui/screens/dashboard/DashboardScreen.kt) |

---

## 四、远端设计（ChatGPT / Figma）重点重构指引

基于对本地端代码与真机取证的归纳，远端设计介入时请特别关注以下四个高优先级（P1）体验节点（完整技术背景详见 [`ui-findings.md`](file:///docs/ui-evidence/ui-findings.md)）：

1. **【P1】驻车详情页 (`ParkedDetailScreen`) 骨架与引导卡片**
   - **痛点**: 无高密度采样点时退化为单行居中文本“暂不可用”，白屏感严重。
   - **设计需求**: 设计标准 Inset Grouped 风格的“驻车宏观摘要卡片”（起始电量、结束电量、驻车时长、掉电率）以及“待机哨兵与休眠策略说明”。
2. **【P1】充电详情页 (`ChargeDetailScreen`) 中文逆地理地址继承与价格编辑交互**
   - **痛点**: 充电流水中已正确解析中文地名（如美塘广场），但详情页头部回退为“未知位置”，且技术报错“无法与会话匹配”。
   - **设计需求**: 统一地点头部规范；将价格编辑从普通弹出框升级为符合 iOS 风格的轻量半屏 BottomSheet。
3. **【P1】胎压趋势页 (`TpmsTrendScreen`) 空间层级与单轮聚焦交互**
   - **痛点**: 俯视车模与四轮卡片占用过多高度（约45%），下方 30 天多色历史曲线受挤压；文字说明冗余。
   - **设计需求**: 建议重构为紧凑型底盘示意图，图表预留 320dp 以上视口；设计“单轮点击高亮”手势，点击左前轮仅高亮该折线，其余弱化。
4. **【P1】温度趋势页 (`TemperatureTrendScreen`) 曲线连续性**
   - **痛点**: 车内车外当前温度清晰，但由于历史驻车采样未入库，趋势图处于缺省态。
   - **设计需求**: 规范历史数据缺失时的优雅占位符，支持双温阶梯区间的色彩填充视觉（车内绿色舒适区间、车外环境色温映射）。

---

## 五、设计 Token 与视觉系统摘要

提取自 `current-design-tokens.json` 与 `AppleTokens.kt`：
- **圆角体系 (Corner Radius)**:
  - 极小元素 (Tag/Badge): `6.dp`
  - 按钮与小卡片: `10.dp`
  - 标准 Inset Grouped 卡片容器: `14.dp`
  - 模态弹窗 / 底部抽屉: `20.dp`
- **内边距与间距 (Spacing Grid)**:
  - 页面水平外边距: `16.dp`
  - 卡片内部边距: `16.dp`
  - 卡片间纵向间距: `12.dp`
  - 紧凑指标间距: `8.dp`
- **核心色彩语义 (Semantic Colors)**:
  - 背景色: 浅色 `#F2F2F7` (Apple System Grouped Background) / 深色 `#000000`
  - 卡片表面: 浅色 `#FFFFFF` / 深色 `#1C1C1E`
  - 品牌青绿 (Teal Accent): `#007D79` (次级 `#0A8491`, 强调 `#10B981`)
  - 警告与错误: 橙色 `#FF9500`, 红色 `#FF3B30`
- **转场物理规范 (Motion Springs)**:
  - Tab 切换: 基于自定义横向微位移淡入淡出 (`slideInHorizontally(10dp) + fadeIn(duration=180ms)`)，完全消除双重重叠残影。
  - 弹窗展开: 物理弹簧阻尼系数 `DampingRatioLowBouncy (0.75f)`, 刚度 `StiffnessMedium (400f)`。

---

## 六、安全与环境执行承诺说明

在整套视觉取证过程中，本地 Android 代理严格执行了安全审计红线：
1. **0 次车辆控制**: 涉及车锁、空调、哨兵的弹窗仅测试至 UI 确认层，均点击“取消”或返回键关闭，未向车机发送任何控制指令。
2. **0 次主动唤醒**: 完全复用真机本地 SQLite Room 缓存及生产服务器历史遥测流，避免频繁轮询唤醒休眠车辆。
3. **隐私脱敏**: 真实车牌、车架号 (VIN)、真实家庭经纬度等在文档与演示数据中均已完全脱敏。
4. **环境无侵入**: 未清空 App 数据、未更改本地数据库、未触碰线上已部署服务。
