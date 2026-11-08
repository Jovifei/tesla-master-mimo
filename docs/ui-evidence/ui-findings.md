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
