# MateLink Web 设计系统 v2（2026-11-08 重做）

所有者反馈：W1–W4 的页面只有文字和列表，没有面板感、没有图表与动画，不像苹果风格。本文件是重做的唯一风格准绳，所有页面必须遵守。

## 借鉴来源（开源/公开参考）

- **苹果 HIG 与苹果自家 App**：Fitness 的活动圆环、Health 的卡片摘要、Home 的设备瓷砖、设置里的分组列表——大标题、分组卡片、圆环与状态色。
- **Tremor / shadcn 仪表盘示例**（MIT 开源）：KPI 卡 + 图表卡的版式结构（只借版式，不引入其视觉语言）。
- **Recharts**（MIT）：带动画的面积/柱状图，本次重新引入作为图表引擎。
- **TeslaMate 的 Grafana 面板**：只借数据密度意识（每屏有关键数字+趋势），不借其深色工程风。

## 视觉原则（硬性）

1. **每屏先给图，再给字**：首屏必须包含至少一个视觉主体（圆环、图表、车辆示意图、瓷砖组），禁止纯文字/纯列表首屏。
2. **数字要大、要有状态色**：核心数字 ≥ 28px tabular；电量/充电/胎压等状态用语义色（teal 品牌、绿=正常/完成、蓝=充电中、琥珀=注意、红=异常）。
3. **卡片要有体积**：白色卡片 + 柔和阴影（0 8px 30px rgba(0,0,0,.05)）+ 20px 圆角；悬停可轻微上浮。禁止只有边框没有阴影的“表格感”卡片。
4. **动效克制但必须有**：圆环/进度条动画 600ms ease-out；图表入场动画；页面元素 200ms 过渡。不用弹跳、不用夸张缩放。
5. **图标用线性 SVG**（viz.tsx 的 Icon，1.7px 描边、圆角端点，SF Symbols 气质），禁止 emoji 当图标。
6. 图文结合：列表行必须带图标或可视化元素（电量胶囊、迷你折线、类型图标），不允许整页只有文字行。

## 令牌（在既有 --apple-* 之上扩展，见 index.css 的 v2 段）

- 图表色板：teal #157a86、绿 #34c759、蓝 #0a84ff、琥珀 #ff9f0a、红 #ff3b30、紫 #bf5af2。
- 渐变：电量环 teal→#34c759；充电功率蓝→teal；海拔紫→蓝。
- 字体：沿用系统 SF/PingFang 栈；大标题 28/700，卡片标题 16/600，KPI 数字 30/700 tabular。

## 组件库（src/components/viz.tsx，接口冻结，页面只许调用不许改）

- `Ring({ value, size?, label?, sub?, tone? })` 电量/完成度圆环（动画弧线+中心大数字）。
- `BatteryPill({ from, to })` 电量变化胶囊（填充比例+起止数字）。
- `Spark({ data, tone?, width?, height? })` 迷你折线（无轴）。
- `AreaSeries({ points: {x: string, y: number}[], unit, tone?, height? })` Recharts 面积图（渐变填充、动画、tooltip 显示数值+单位）。
- `Bars({ data: {label, value}[], unit, tone?, height? })` Recharts 柱状图（圆角柱、动画）。
- `Kpi({ label, value, unit?, tone?, spark? })` 指标块。
- `Tile({ icon, label, value?, active?, busy?, onClick })` Home 风控制瓷砖（状态高亮）。
- `Icon({ name, size? })` 线性图标集：gauge/bolt/route/remote/chart/person/lock/unlock/zap/speaker/climate/plug/play/stop/window/shield/pin/nav/battery/thermo/calendar/download/logout/refresh/chevron/sun/snow。
- `TpmsCar({ fl, fr, rl, rr })` 车辆俯视图 SVG + 四轮胎压气泡（异常轮变琥珀/红：<2.4 琥珀、<2.2 红）。
- `SegControl({ options, value, onChange })` 分段控件。
- `Skeleton({ height? })` 加载 shimmer。

Tailwind 4 已接入（index.css 首行 @import），可自由使用工具类 + CSS 变量（如 `bg-(--apple-surface)` 或任意值 `bg-[#fff]`），但颜色/圆角优先用令牌。

## 各页面的视觉任务（验收口径）

- **仪表盘**：左大卡 = 电量圆环 + 续航 + 状态徽标；右侧 KPI 网格（总里程/温度/锁/哨兵等带图标）；充电中显示功率面积小图；位置卡带静态示意（坐标+高德链接按钮）；最近行程为路线卡（起点→终点 + BatteryPill + Spark 海拔如有）；附近充电带距离条；地点为芯片组。
- **充电**：顶部月度柱状图卡（近 6 个月充入电量）；当前充电大卡（圆环+功率图）；列表行 = 类型图标（交流/直流）+ BatteryPill + 金额徽标；详情五曲线用 AreaSeries 分卡呈现，每卡有极值标注。
- **行程**：列表卡带路线视觉（起终点圆点连线）+ 距离大数字 + 海拔 Spark；详情页指标瓷砖组 + 四张 AreaSeries 图 + 驻车卡（待机功率用小条形表达）。
- **远程控制**：Apple Home 式瓷砖网格（图标+名称+当前状态），状态从 status 派生（锁/空调/哨兵/充电口）；参数型命令用瓷砖内嵌步进器；危险操作瓷砖红色调；结果用顶部浮动横幅动画出现。
- **分析**：年度 KPI 行（大数字+同比对象为覆盖说明）；月度 Bars（里程）+ AreaSeries（充入电量）双图；胎压卡用 TpmsCar 俯视图 + 趋势结论列表；导出按钮带图标。
- **我的**：头像占位圆（用户 ID 首字母）+ 账号卡；车辆卡带车型与状态点；邀请码卡带图标输入行。

## 禁忌

- 不许再交纯文字列表页；不许用 emoji 图标；不许自创与令牌冲突的颜色；不许改 viz.tsx / index.css / App.tsx（外壳与组件库由设计系统负责人维护，页面负责人只改自己的页面文件）。
- 数据口径与 W1–W4 已交付的 API 层完全一致（src/api/* 不动）；只改呈现层。
