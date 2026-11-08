# Tesla MateLink: 多端 UI 现状取证与远端设计全量交接白皮书

> **报告主体**: 本地 Android 研发代理 (Antigravity Local Pairing Assistant)  
> **交接对象**: 远端设计团队 (Tesla_MateLink Remote Design Agent / ChatGPT / Figma)  
> **生成时间**: 2026-11-09T00:54:44.335335  
> **代码分支**: `codex/ui-evidence-20261108` @ `050568b`  
> **已装版本**: `com.matelink` v2.1.87 (build 107)  
> **真机环境**: OnePlus 7 Pro (1440x3120, 600dpi, 384x832dp)  
> **云端端点**: `https://api.teslalink.joviluma.com/` (HTTP 200 OK)  

---

## 一、交付物概览与多端版本比对

| 端体系 | 代码路径 | 分支 | 完整 Commit SHA | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| **ANDROID** | `E:\project\tesla_master\app_mimo` | `codex/ui-evidence-20261108` | `050568b73442...` | Exact Match (v2.1.87 / 107) |
| **MINIPROGRAM** | `E:\project\tesla_master\miniprogram_Weixin` | `codex/miniprogram-e-migration-20261029` | `4ee0c0479450...` | Dirty |
| **WEB** | `E:\project\tesla_master\web_matelink` | `main` | `717a343741a2...` | Dirty |
| **IOS** | `E:\project\tesla_ios` | `feature/ios-apple-redesign` | `18a0b65a9b3c...` | UNKNOWN (No physical Mac/iOS connected in local Windows environment) |

---

## 二、全量 34 页面与弹窗功能清单

| Screen ID | 归属路由 | 核心用户任务 | 主要指标与单位 | 源码位置 |
| :--- | :--- | :--- | :--- | :--- |
| **SCR-01** | `Root` | 查看车辆核心状态（续航、电量、锁车、空调、哨兵） | 电量(%), 续航(km), 总里程(km), 位置(中文地名) | `DashboardScreen.kt` |
| **SCR-02** | `Dashboard` | 查看最近行程概要、车内外温度、四轮胎压、当前地图定位及附近充电 | 温度(°C), 胎压(bar), 距离(km), 电价(¥/度) | `DashboardScreen.kt` |
| **SCR-02B** | `Dashboard` | 浏览附近超充与目的地充电站价格与停车优惠政策 | 电价(¥/度), 服务费(¥), 距离(km), 免费停车时长(h) | `DashboardScreen.kt` |
| **SCR-02C** | `Dashboard` | 查看与管理常用地点（家、公司等） | 地点名称, 经纬度坐标 | `DashboardScreen.kt` |
| **SCR-03** | `Dashboard` | 车控安全二次确认弹窗（防误触关键车辆指令） | 操作指令名称 | `DashboardScreen.kt` |
| **SCR-04** | `Root` | 查看近期行程频次柱状图、里程统计汇总及筛选器 | 总行程数(次), 总距离(km), 总时间(min), 最高速度(km/h) | `DrivesScreen.kt` |
| **SCR-04B** | `Drives` | 浏览按日分组的行程历史流水与驻车时长卡片 | 能耗(kWh/100km), 里程(km), 时长(min), 极速(km/h), 驻车(h m) | `DrivesScreen.kt` |
| **SCR-05** | `Drives` | 查看单次行程的高德真实轨迹、起止点中文位置、起止时间与核心能耗 | 起止地名, 里程(km), 驾驶时长(m), 平均能耗(kWh/100km) | `DriveDetailScreen.kt` |
| **SCR-05B** | `DriveDetail` | 查看行程内的详细速度分布曲线及统计采样值 | 最高速度(km/h), 均速(km/h), 采样统计 | `DriveDetailScreen.kt` |
| **SCR-06** | `DriveDetail` | 全屏分析单次行程的速度、功率、动能回收与电量SOC变化多轴图表 | 速度(km/h), 功率(kW), 回收功率(kW), 电量(%) | `DriveCurvesScreen.kt` |
| **SCR-07** | `Drives` | 查看驻车期间的掉电分析、车内外温度监控及休眠/唤醒能耗 | 起止电量(%), 掉电速率(%/h), 驻车能耗(kWh) | `ParkedDetailScreen.kt` |
| **SCR-08** | `Root` | 查看近期充电统计汇总及按日充入电量柱状图 | 充电次数(次), 总充入电量(kWh/度), 总费用(¥), 平均电价(¥/度) | `ChargesScreen.kt` |
| **SCR-08B** | `Charges` | 浏览充电流水列表，包含高德真实中文反查地名、充电类型、起止电量与费用 | 地名(中文), 充电量(度), 起止SOC(%), 单笔费用(¥), 快充/慢充 | `ChargesScreen.kt` |
| **SCR-09** | `Charges` | 查看单次充电的详细电量爬升曲线、充电功率曲线及电费账单明细 | 充电量(度), SOC变化(%), 峰值功率(kW), 费用(¥) | `ChargeDetailScreen.kt` |
| **SCR-10** | `ChargeDetail` | 手动录入或调整单次充电的实际支付金额（支持自定义电费分摊） | 金额数值 (¥) | `ChargeDetailScreen.kt` |
| **SCR-11** | `Root` | 更多中心：以苹果 Inset Grouped 规范聚合数据分析、报告与系统设置入口 | 数据连接状态提示 | `MoreScreen.kt` |
| **SCR-11B** | `More` | 更多中心下部：报告与导出卡片，系统配置（数据状态、软件更新、哨兵、设置、关于） | 系统配置项列表 | `MoreScreen.kt` |
| **SCR-12** | `More` | 查看电池衰减评估环形进度条、当前标准化续航、基线续航及历史衰减拟合趋势 | 健康度(%), 估算衰减(%), 标准化续航(km), 基线续航(km) | `BatteryScreen.kt` |
| **SCR-13** | `More` | 分析车辆整体百公里能耗水平、历史能耗趋势及季节性差异 | 平均能耗(Wh/km), 参与计算里程(km), 行程数(次) | `EfficiencyScreen.kt` |
| **SCR-13B** | `Drives / More` | 查看年度里程汇总、年化推算里程、总耗电量及年化分布柱状图 | 累计里程(km), 年化里程(km), 行程数, 平均能耗(kWh/100km) | `MileageScreen.kt` |
| **SCR-14** | `More` | 分析车辆全生命周期充电支出、手工录入费用 vs 算法估算费用及月度账单柱状图 | 已记录总价(¥), 算法估算(¥), 充入总电量(kWh) | `CostScreen.kt` |
| **SCR-15** | `More` | 评估基于驾驶习惯的个性化续航模型置信度及额定续航消耗偏差 | 偏差率(%), 有效行程数(次), 置信度(%), 偏差影响因素 | `RangeScreen.kt` |
| **SCR-16** | `More` | 分析车辆驻车静态掉电（幽灵掉电 Vampire Drain）与休眠率 | 掉电速率(%/天), 休眠耗电占比 | `VampireScreen.kt` |
| **SCR-17** | `More` | 时间线形式串联全天连续行驶、驻车、充电流水全景 | 行驶时间, 行驶距离(km), 耗时(m) | `TimelineScreen.kt` |
| **SCR-18** | `Dashboard / More` | 展示四轮底盘模型、四轮胎压观测值(bar)、时间戳及四轮多色折线历史趋势图 | 各轮胎压(bar), 样本数(个), 观测时间 | `TpmsTrendScreen.kt` |
| **SCR-19** | `Dashboard / More` | 管理 Tesla 官方授权凭据、高级网络模式、语言、冷态胎压阈值及货币 | 授权状态, 货币符号, 阈值(bar) | `SettingsScreen.kt` |
| **SCR-19B** | `Settings` | 配置峰平谷三时段分时电价规则、测试后端连通性、查看版本号与提交工单 | 时段电价(¥/度), 当前软件版本号(2.1.87), Git SHA | `SettingsScreen.kt` |
| **SCR-20** | `More` | 查看 MateLink 应用定位、软件版本、多端统一技术栈、数据源合规与开源协议 | 版本号(2.1.87), Git SHA(3cc34dc), 开源协议(MIT) | `AboutScreen.kt` |
| **SCR-21** | `More` | 查看近7天24小时哨兵触发热力图格点矩阵与历史警报位置清单 | 警报时间, 触发地点(中文地名) | `SentryHistoryScreen.kt` |
| **SCR-23** | `Dashboard` | 查看车内外当前温度状态与行程期间的温度记录审计提示 | 车内温度(°C), 车外温度(°C), 记录样本数 | `TemperatureTrendScreen.kt` |
| **SCR-24** | `Dashboard` | 唤起底部导航应用选择面板（高德地图 vs Tesla车机导航） | 导航目标站名 | `DashboardScreen.kt` |
| **SCR-25** | `More` | 查看综合行驶数据分析（加权效率、行驶能耗、充入电量、地点识别进度） | 行驶里程(km), 加权效率(kWh/100km), 充入电量(MWh) | `StatsScreen.kt` |
| **SCR-26** | `More` | 组合旅程管理（空状态引导页与创建入口） | 步骤引导说明 | `TripsScreen.kt` |
| **SCR-28** | `App Global Event` | 行程同步到达提醒弹窗（提示新行程已同步完成） | 新行程通知文案 | `NavGraph.kt` |

---

## 三、逐页视觉取证目录 (对应 screenshots/ 原始截图)

### 【SCR-01】车辆状态与快捷控制卡片 (仪表盘核心)
- **图片位置**: `screenshots/SCR-01-DASHBOARD-TOP.png`
- **用户任务**: 查看车辆核心状态（续航、电量、锁车、空调、哨兵）
- **核心指标**: 电量(%), 续航(km), 总里程(km), 位置(中文地名)
- **源码实现**: `com/matelink/ui/screens/dashboard/DashboardScreen.kt`

### 【SCR-02】胎压、高德地图与最近行程 (仪表盘核心)
- **图片位置**: `screenshots/SCR-02-DASHBOARD-BOTTOM.png`
- **用户任务**: 查看最近行程概要、车内外温度、四轮胎压、当前地图定位及附近充电
- **核心指标**: 温度(°C), 胎压(bar), 距离(km), 电价(¥/度)
- **源码实现**: `com/matelink/ui/screens/dashboard/DashboardScreen.kt`

### 【SCR-02B】附近充电站与停车策略 (仪表盘核心)
- **图片位置**: `screenshots/SCR-02B-DASHBOARD-FOOTER.png`
- **用户任务**: 浏览附近超充与目的地充电站价格与停车优惠政策
- **核心指标**: 电价(¥/度), 服务费(¥), 距离(km), 免费停车时长(h)
- **源码实现**: `com/matelink/ui/screens/dashboard/DashboardScreen.kt`

### 【SCR-02C】我的地点快捷收藏 (仪表盘核心)
- **图片位置**: `screenshots/SCR-02C-DASHBOARD-MYPLACES.png`
- **用户任务**: 查看与管理常用地点（家、公司等）
- **核心指标**: 地点名称, 经纬度坐标
- **源码实现**: `com/matelink/ui/screens/dashboard/DashboardScreen.kt`

### 【SCR-03】安全防误触二次确认弹窗 (安全弹窗)
- **图片位置**: `screenshots/SCR-03-SAFETY-CONFIRM-DIALOG.png`
- **用户任务**: 车控安全二次确认弹窗（防误触关键车辆指令）
- **核心指标**: 操作指令名称
- **源码实现**: `com/matelink/ui/screens/dashboard/DashboardScreen.kt`

### 【SCR-24】附近充电外呼导航面板 (抽屉面板)
- **图片位置**: `screenshots/SCR-24-NEARBY-CHARGING-NAV-SHEET.png`
- **用户任务**: 唤起底部导航应用选择面板（高德地图 vs Tesla车机导航）
- **核心指标**: 导航目标站名
- **源码实现**: `com/matelink/ui/screens/dashboard/DashboardScreen.kt`

### 【SCR-28】行程完成自动触发展开弹窗 (到达通知)
- **图片位置**: `screenshots/SCR-28-TRIP-COMPLETED-DIALOG.png`
- **用户任务**: 行程同步到达提醒弹窗（提示新行程已同步完成）
- **核心指标**: 新行程通知文案
- **源码实现**: `com/matelink/ui/navigation/NavGraph.kt`

### 【SCR-04】每日行程频次柱状图 (行程主页)
- **图片位置**: `screenshots/SCR-04-DRIVES-LIST.png`
- **用户任务**: 查看近期行程频次柱状图、里程统计汇总及筛选器
- **核心指标**: 总行程数(次), 总距离(km), 总时间(min), 最高速度(km/h)
- **源码实现**: `com/matelink/ui/screens/drives/DrivesScreen.kt`

### 【SCR-04B】大字号行程卡片与驻车底色块 (行程流水)
- **图片位置**: `screenshots/SCR-04B-DRIVES-LIST-SCROLLED.png`
- **用户任务**: 浏览按日分组的行程历史流水与驻车时长卡片
- **核心指标**: 能耗(kWh/100km), 里程(km), 时长(min), 极速(km/h), 驻车(h m)
- **源码实现**: `com/matelink/ui/screens/drives/DrivesScreen.kt`

### 【SCR-05】行程详情 (高德真实轨迹折线) (行程轨迹)
- **图片位置**: `screenshots/SCR-05-DRIVE-DETAIL.png`
- **用户任务**: 查看单次行程的高德真实轨迹、起止点中文位置、起止时间与核心能耗
- **核心指标**: 起止地名, 里程(km), 驾驶时长(m), 平均能耗(kWh/100km)
- **源码实现**: `com/matelink/ui/screens/drives/DriveDetailScreen.kt`

### 【SCR-05B】行程内嵌速度趋势曲线 (行程分析)
- **图片位置**: `screenshots/SCR-05B-DRIVE-DETAIL-CURVES.png`
- **用户任务**: 查看行程内的详细速度分布曲线及统计采样值
- **核心指标**: 最高速度(km/h), 均速(km/h), 采样统计
- **源码实现**: `com/matelink/ui/screens/drives/DriveDetailScreen.kt`

### 【SCR-06】完整速度与海拔采样分析 (专业曲线)
- **图片位置**: `screenshots/SCR-06-DRIVE-CURVES.png`
- **用户任务**: 全屏分析单次行程的速度、功率、动能回收与电量SOC变化多轴图表
- **核心指标**: 速度(km/h), 功率(kW), 回收功率(kW), 电量(%)
- **源码实现**: `com/matelink/ui/screens/drives/DriveCurvesScreen.kt`

### 【SCR-07】驻车详情 (回退骨架状态) (驻车分析 (P1))
- **图片位置**: `screenshots/SCR-07-PARKED-DETAIL.png`
- **用户任务**: 查看驻车期间的掉电分析、车内外温度监控及休眠/唤醒能耗
- **核心指标**: 起止电量(%), 掉电速率(%/h), 驻车能耗(kWh)
- **源码实现**: `com/matelink/ui/screens/drives/ParkedDetailScreen.kt`

### 【SCR-08】每日充入电量统计直方图 (充电主页)
- **图片位置**: `screenshots/SCR-08-CHARGES-LIST.png`
- **用户任务**: 查看近期充电统计汇总及按日充入电量柱状图
- **核心指标**: 充电次数(次), 总充入电量(kWh/度), 总费用(¥), 平均电价(¥/度)
- **源码实现**: `com/matelink/ui/screens/charges/ChargesScreen.kt`

### 【SCR-08B】逆地理中文地名充电流水 (充电流水)
- **图片位置**: `screenshots/SCR-08B-CHARGES-LIST-SCROLLED.png`
- **用户任务**: 浏览充电流水列表，包含高德真实中文反查地名、充电类型、起止电量与费用
- **核心指标**: 地名(中文), 充电量(度), 起止SOC(%), 单笔费用(¥), 快充/慢充
- **源码实现**: `com/matelink/ui/screens/charges/ChargesScreen.kt`

### 【SCR-09】充电详情 (电量与功率曲线) (充电曲线 (P1))
- **图片位置**: `screenshots/SCR-09-CHARGE-DETAIL.png`
- **用户任务**: 查看单次充电的详细电量爬升曲线、充电功率曲线及电费账单明细
- **核心指标**: 充电量(度), SOC变化(%), 峰值功率(kW), 费用(¥)
- **源码实现**: `com/matelink/ui/screens/charges/ChargeDetailScreen.kt`

### 【SCR-10】电费价格自定义弹窗 (改价弹窗)
- **图片位置**: `screenshots/SCR-10-CHARGE-COST-EDIT.png`
- **用户任务**: 手动录入或调整单次充电的实际支付金额（支持自定义电费分摊）
- **核心指标**: 金额数值 (¥)
- **源码实现**: `com/matelink/ui/screens/charges/ChargeDetailScreen.kt`

### 【SCR-12】电池健康度环形图与衰减趋势 (电池健康)
- **图片位置**: `screenshots/SCR-12-BATTERY-HEALTH.png`
- **用户任务**: 查看电池衰减评估环形进度条、当前标准化续航、基线续航及历史衰减拟合趋势
- **核心指标**: 健康度(%), 估算衰减(%), 标准化续航(km), 基线续航(km)
- **源码实现**: `com/matelink/ui/screens/battery/BatteryScreen.kt`

### 【SCR-13】能耗多维分析 (环境与能耗关联) (能耗分析)
- **图片位置**: `screenshots/SCR-13-ENERGY-ANALYSIS.png`
- **用户任务**: 分析车辆整体百公里能耗水平、历史能耗趋势及季节性差异
- **核心指标**: 平均能耗(Wh/km), 参与计算里程(km), 行程数(次)
- **源码实现**: `com/matelink/ui/screens/efficiency/EfficiencyScreen.kt`

### 【SCR-13B】累计里程分布直方图 (里程统计)
- **图片位置**: `screenshots/SCR-13B-MILEAGE.png`
- **用户任务**: 查看年度里程汇总、年化推算里程、总耗电量及年化分布柱状图
- **核心指标**: 累计里程(km), 年化里程(km), 行程数, 平均能耗(kWh/100km)
- **源码实现**: `com/matelink/ui/screens/mileage/MileageScreen.kt`

### 【SCR-14】补能成本与费用审计 (费用审计)
- **图片位置**: `screenshots/SCR-14-COST-ANALYSIS.png`
- **用户任务**: 分析车辆全生命周期充电支出、手工录入费用 vs 算法估算费用及月度账单柱状图
- **核心指标**: 已记录总价(¥), 算法估算(¥), 充入总电量(kWh)
- **源码实现**: `com/matelink/ui/screens/cost/CostScreen.kt`

### 【SCR-15】个性化续航预测模型 (续航模型)
- **图片位置**: `screenshots/SCR-15-RANGE-ANALYSIS.png`
- **用户任务**: 评估基于驾驶习惯的个性化续航模型置信度及额定续航消耗偏差
- **核心指标**: 偏差率(%), 有效行程数(次), 置信度(%), 偏差影响因素
- **源码实现**: `com/matelink/ui/screens/range/RangeScreen.kt`

### 【SCR-16】待机吸血鬼耗电 (Vampire Drain) (待机分析 (P2))
- **图片位置**: `screenshots/SCR-16-VAMPIRE-DRAIN.png`
- **用户任务**: 分析车辆驻车静态掉电（幽灵掉电 Vampire Drain）与休眠率
- **核心指标**: 掉电速率(%/天), 休眠耗电占比
- **源码实现**: `com/matelink/ui/screens/vampire/VampireScreen.kt`

### 【SCR-17】驾驶时光机 (时间线模式) (时间轴)
- **图片位置**: `screenshots/SCR-17-TIMELINE.png`
- **用户任务**: 时间线形式串联全天连续行驶、驻车、充电流水全景
- **核心指标**: 行驶时间, 行驶距离(km), 耗时(m)
- **源码实现**: `com/matelink/ui/screens/timeline/TimelineScreen.kt`

### 【SCR-18】胎压四轮底盘与30天多色趋势 (胎压趋势 (P1))
- **图片位置**: `screenshots/SCR-18-TPMS-TREND.png`
- **用户任务**: 展示四轮底盘模型、四轮胎压观测值(bar)、时间戳及四轮多色折线历史趋势图
- **核心指标**: 各轮胎压(bar), 样本数(个), 观测时间
- **源码实现**: `com/matelink/ui/screens/tpms/TpmsTrendScreen.kt`

### 【SCR-21】哨兵警报 7x24h 矩阵热力图 (哨兵安全)
- **图片位置**: `screenshots/SCR-21-SENTRY-HISTORY.png`
- **用户任务**: 查看近7天24小时哨兵触发热力图格点矩阵与历史警报位置清单
- **核心指标**: 警报时间, 触发地点(中文地名)
- **源码实现**: `com/matelink/ui/screens/sentry/SentryHistoryScreen.kt`

### 【SCR-23】车内外温控曲线监控 (温控分析 (P1))
- **图片位置**: `screenshots/SCR-23-TEMPERATURE-TREND.png`
- **用户任务**: 查看车内外当前温度状态与行程期间的温度记录审计提示
- **核心指标**: 车内温度(°C), 车外温度(°C), 记录样本数
- **源码实现**: `com/matelink/ui/screens/temperature/TemperatureTrendScreen.kt`

### 【SCR-25】全局数据统计概览 (全局统计)
- **图片位置**: `screenshots/SCR-25-STATS-OVERVIEW.png`
- **用户任务**: 查看综合行驶数据分析（加权效率、行驶能耗、充入电量、地点识别进度）
- **核心指标**: 行驶里程(km), 加权效率(kWh/100km), 充入电量(MWh)
- **源码实现**: `com/matelink/ui/screens/stats/StatsScreen.kt`

### 【SCR-26】跨城长途行程归集 (Trips) (长途旅程)
- **图片位置**: `screenshots/SCR-26-TRIPS.png`
- **用户任务**: 组合旅程管理（空状态引导页与创建入口）
- **核心指标**: 步骤引导说明
- **源码实现**: `com/matelink/ui/screens/trips/TripsScreen.kt`

### 【SCR-11】更多中心 (苹果 Inset Grouped 规范) (更多中心)
- **图片位置**: `screenshots/SCR-11-MORE-MENU.png`
- **用户任务**: 更多中心：以苹果 Inset Grouped 规范聚合数据分析、报告与系统设置入口
- **核心指标**: 数据连接状态提示
- **源码实现**: `com/matelink/ui/screens/more/MoreScreen.kt`

### 【SCR-11B】更多中心 (系统设置区域) (更多中心)
- **图片位置**: `screenshots/SCR-11B-MORE-MENU-SCROLLED.png`
- **用户任务**: 更多中心下部：报告与导出卡片，系统配置（数据状态、软件更新、哨兵、设置、关于）
- **核心指标**: 系统配置项列表
- **源码实现**: `com/matelink/ui/screens/more/MoreScreen.kt`

### 【SCR-19】设置与官方授权凭证 (官方授权)
- **图片位置**: `screenshots/SCR-19-SETTINGS.png`
- **用户任务**: 管理 Tesla 官方授权凭据、高级网络模式、语言、冷态胎压阈值及货币
- **核心指标**: 授权状态, 货币符号, 阈值(bar)
- **源码实现**: `com/matelink/ui/screens/settings/SettingsScreen.kt`

### 【SCR-19B】分时电价规则与版本信息 (版本与电价)
- **图片位置**: `screenshots/SCR-19B-SETTINGS-BOTTOM.png`
- **用户任务**: 配置峰平谷三时段分时电价规则、测试后端连通性、查看版本号与提交工单
- **核心指标**: 时段电价(¥/度), 当前软件版本号(2.1.87), Git SHA
- **源码实现**: `com/matelink/ui/screens/settings/SettingsScreen.kt`

### 【SCR-20】关于 MateLink 与架构致谢 (关于)
- **图片位置**: `screenshots/SCR-20-ABOUT.png`
- **用户任务**: 查看 MateLink 应用定位、软件版本、多端统一技术栈、数据源合规与开源协议
- **核心指标**: 版本号(2.1.87), Git SHA(3cc34dc), 开源协议(MIT)
- **源码实现**: `com/matelink/ui/screens/about/AboutScreen.kt`

### 【SCR-22】软件更新日志与检查更新 (软件更新)
- **图片位置**: `screenshots/SCR-22-SOFTWARE-UPDATES.png`
- **用户任务**: 
- **核心指标**: 
- **源码实现**: ``

### 【SCR-27】遥测数据就绪度与同步状态 (就绪度诊断)
- **图片位置**: `screenshots/SCR-27-DATA-READINESS.png`
- **用户任务**: 
- **核心指标**: 
- **源码实现**: ``

### 【SCR-29】年度驾驶报告与里程总结 (年度报告)
- **图片位置**: `screenshots/SCR-29-ANNUAL-REPORT.png`
- **用户任务**: 
- **核心指标**: 
- **源码实现**: ``

### 【SCR-30】驾驶与补能数据导出卡片 (数据导出)
- **图片位置**: `screenshots/SCR-30-EXPORT.png`
- **用户任务**: 
- **核心指标**: 
- **源码实现**: ``

---

## 四、高优先级体验优化空间 (P1) 与重构指引

# MateLink Android UI 现状核查、问题归纳与优化空间报告

本报告由本地 Android (`app_mimo`) 代理基于连接真机 (OnePlus 7 Pro, Android 11, 1440x3120, 600dpi) 上运行的 MateLink v2.1.87 (commit `050568b`) 独立取证整理，供 Tesla_MateLink 项目远端设计（ChatGPT / Figma）团队直接引用与方案深化。

---

## 一、高优先级体验优化空间 (P1)

### 【FINDING-01】驻车详情页缺失估算数据与传感器展示，回退为空白提示
- **页面 / 状态**: `SCR-07-PARKED-DETAIL.png` (驻车详情页)
- **视觉证据**: `screenshots/SCR-07-PARKED-DETAIL.png`
- **具体观察**: 用户从行程列表点击驻车记录卡片（例如“驻车于 访云街 西蔡庙 10h 48m”）进入详情时，由于当前驻车时段后端未返回高密度遥测采样点，页面直接回退居中显示“暂不可用 / 重试”，页面其余区域大面积留白。
- **用户影响**: 无法查看该段驻车期间的掉电量、估算驻车能耗、车内外温度监控曲线及休眠比例，给用户“功能未完成”或“数据丢失”的误解。
- **源码位置**: [ParkedDetailScreen.kt](file:///android/app/src/main/java/com/matelink/ui/screens/drives/ParkedDetailScreen.kt#L120-L160)
- **改善建议**: 
  1. 即使无明细采样点，也应继承行程流水中已有的起始电量、结束电量、驻车时长等宏观数据，渲染骨架摘要卡片；
  2. 提供“驻车能耗分析说明”与“待机哨兵/温度传感器采样机制”引导卡片，而非单一居中文本。

---

### 【FINDING-02】充电详情页未继承列表已解析的中文逆地理位置
- **页面 / 状态**: `SCR-09-CHARGE-DETAIL.png` (充电详情页)
- **视觉证据**: `screenshots/SCR-09-CHARGE-DETAIL.png`
- **具体观察**: 在充电历史列表 (`SCR-08B`) 中，通过到达行程坐标关联与高德逆地理编码，已成功将经纬度解析为真实中文地名（如“余杭美塘广场”）；但在进入充电详情页 (`SCR-09`) 时，位置卡片仍显示“暂无可用车辆位置”，充电详情子标题显示“未知位置”。同时顶部出现红色提示“该详情暂时无法与已保存的同一会话匹配”。
- **用户影响**: 列表与详情地名数据不一致，影响用户对充电账单真实地点的核对体验。
- **源码位置**: [ChargeDetailScreen.kt](file:///android/app/src/main/java/com/matelink/ui/screens/charges/ChargeDetailScreen.kt#L180-L220)
- **改善建议**:
  1. `ChargeDetail` 路由传参时携带已在列表解析成功的 `address` 或在 ViewModel 中统一关联 Room 数据库中已持久化的逆地理记录；
  2. 弱化“无法与会话匹配”的技术型报错文案，优化为平滑的离线快照提示。

---

### 【FINDING-03】胎压趋势页车模占比过高，历史曲线横向压缩
- **页面 / 状态**: `SCR-18-TPMS-TREND.png` (胎压趋势页)
- **视觉证据**: `screenshots/SCR-18-TPMS-TREND.png`
- **具体观察**: 页面上半部分俯视车模与4个轮胎卡片占用了超过 45% 的垂直视口高度；下半部分“已记录胎压”历史曲线虽然呈现了4条多色折线，但由于时间跨度大（10/10 - 11/09）且近期样本集中，曲线点密集重叠。且文字说明较多（“旧观测”、“覆盖：已记录 34 个样本”）。
- **用户影响**: 图表可读性受限，用户难以直观分辨具体哪个轮胎在特定日期的微小压差变化。
- **源码位置**: [TpmsTrendScreen.kt](file:///android/app/src/main/java/com/matelink/ui/screens/tpms/TpmsTrendScreen.kt#L140-L220)
- **改善建议**:
  1. 采用紧凑型底盘图例或浮层联动模式，将上方视口压缩至 25% 以内，为下部图表留出至少 320dp 高度；
  2. 增加曲线手势探针与单轮孤立高亮（点击左前轮则仅高亮左前轮曲线，其余置灰）；
  3. 精简辅助文字，采用微动效图标引导交互。

---

### 【FINDING-04】车内外温度趋势页缺少历史行程连续曲线
- **页面 / 状态**: `SCR-23-TEMPERATURE-TREND.png` (温度趋势页)
- **视觉证据**: `screenshots/SCR-23-TEMPERATURE-TREND.png`
- **具体观察**: 页面顶部清晰展示当前车内 (22.3°C) 与车外 (20.0°C) 温度，但切换“近7天”或“近30天”时，下方提示“已有行程，但温度缺少合格来源或时间证据。需真实记录或导入行程，不会生成示意值”。
- **用户影响**: 用户期望看到近期的气温变化与车内空调负荷走势，但面对的是静态审计告知。
- **源码位置**: [TemperatureTrendScreen.kt](file:///android/app/src/main/java/com/matelink/ui/screens/temperature/TemperatureTrendScreen.kt#L90-L150)
- **改善建议**:
  1. 当缺少点对点连续采样时，可使用各次行程起止点的环境均温作为散点柱状/连线图展示；
  2. 结合当地历史天气 API 进行辅助趋势拟合，并在图例中注明数据置信度。

---

## 二、架构设计与中低优先级打磨空间 (P2)

### 【FINDING-05】待机能耗分析 (Vampire) 计算耗时与加载占位
- **页面 / 状态**: `SCR-16-VAMPIRE-DRAIN.png`
- **视觉证据**: `screenshots/SCR-16-VAMPIRE-DRAIN.png`
- **具体观察**: 进入页面后需要计算长周期的休眠与唤醒电耗，呈现全屏灰底无线条道路（Infinite Loop）动效，持续约 2-3 秒。
- **改善建议**: 引入类似 iOS 风格的毛玻璃渐显骨架屏（Shimmer Placeholder），并在 Room DB 缓存上一次计算结果实现秒开。
- **源码位置**: [VampireScreen.kt](file:///android/app/src/main/java/com/matelink/ui/screens/vampire/VampireScreen.kt)

---

### 【FINDING-06】单次行程详情中的详细曲线入口层级
- **页面 / 状态**: `SCR-05-DRIVE-DETAIL.png` & `SCR-06-DRIVE-CURVES.png`
- **视觉证据**: `screenshots/SCR-05-DRIVE-DETAIL.png`, `screenshots/SCR-06-DRIVE-CURVES.png`
- **具体观察**: 行程详情页面内部同时存在“详细曲线 ->”按钮（卡片右上角）和顶部 TopAppBar 右上角图标按钮，而页面下滑后底部又有内嵌的速度曲线。三者功能重叠。
- **改善建议**: 统一曲线交互入口，将内嵌曲线与全屏交互整合为多Tab卡片（速度 / 功率 / 电量）。
- **源码位置**: [DriveDetailScreen.kt](file:///android/app/src/main/java/com/matelink/ui/screens/drives/DriveDetailScreen.kt#L150-L210)

---

## 三、最新版本已解决的已知缺陷（已在 v2.1.87 真机验证）

1. **【RESOLVED-01】仪表盘冗余机型来源行清除**:
   - 原首页包含 `Model Y · 标准续航版 实时数据 · 混合来源 11-07 20:38` 占行空间。
   - 现已彻底移除 (`SCR-01`)，车辆 3D 渲染与快捷车控整体上移，首屏信息密度大幅优化。
2. **【RESOLVED-02】行程历史核心指标字号放大**:
   - 行程流水卡片中的 能耗、里程、时长、极速 提升至 18sp Bold 并采用 JetBrains Mono 等宽字体 (`SCR-04B`)，一目了然。
3. **【RESOLVED-03】驻车卡片专属底色与 P 标**:
   - 驻车记录支持灰色独立背景与 Teal 色 `P` 标，并展示总驻车时长（如“10h 48m”），与行车记录形成明确视觉对比。
4. **【RESOLVED-04】充电历史中文位置解析与防抖限频**:
   - 充电列表告别“未知位置”，成功关联到达行程坐标并经高德逆地理编码展示中文商圈地名 (`SCR-08B`)。
5. **【RESOLVED-05】Tab 切换重影 (Ghosting) 彻底消除**:
   - 消除淡入淡出动画冲突，采用 `EnterTransition.None` / `ExitTransition.None`，底栏切换瞬间响应，无重影残留 (`SCR-11`, `interaction_flow.mp4`)。
6. **【RESOLVED-06】附近充电与停车优惠政策实装**:
   - 仪表盘新增附近超充与目的地充电列表，明确标注分时电价（如 `¥1.25/度 (分时¥0.95~1.65)`）及停车政策（如 `超充免费停车2小时`），并支持一键呼起高德地图/Tesla导航 (`SCR-02B`, `SCR-24`)。
