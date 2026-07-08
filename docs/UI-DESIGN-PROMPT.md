# MateLink App UI 设计提示词

> 用于 Stitch / AI 原型设计工具，生成 App 页面原型图

---

## 通用版（可直接复制粘贴）

```
开发一个名为「MateLink」的 Tesla 车辆数据监控 App 的 UI 和 UX 设计。

## App 信息
- App 名称：MateLink
- 副标题：Your Tesla Data Companion
- 平台：iOS + Android 双端
- 设计风格：iOS 用 SwiftUI 圆角卡片风格，Android 用 Material 3 设计语言
- 主题色：深蓝 #1976D2（主色）、白色 #FFFFFF（背景）、深灰 #1a1a2e（文字）
- 强调色：绿色 #4CAF50（电量/在线）、橙色 #FF9800（充电中）、红色 #f44336（警告/低电量）
- 语言：中文（主）+ 英文（辅）

## 底部导航栏（4个Tab）
1. 📊 Dashboard（仪表盘）— 车辆实时状态总览
2. 🚗 Drives（行程）— 驾驶历史列表
3. ⚡ Charges（充电）— 充电历史列表
4. ⚙️ More（更多）— 统计/设置/报告等入口

---

## 页面 1：Dashboard（仪表盘）

顶部：
- 车辆名称「Tesla Model 3」+ 状态徽章（绿色圆点 + "Online"）

核心卡片（大卡片）：
- 左侧：圆形电池环形图，中间显示 78%，下方文字 "Battery"
- 右侧：数字 312 km，下方文字 "Estimated range"

信息网格（2×2 小卡片）：
- 📍 Last Drive · 31.2 km
- ⚡ Last Charge · 42 kWh
- 🌡️ Inside Temp · 24°C
- 🔒 Doors · Locked

车辆状态栏（横向图标）：
- 🔒 Locked · 🛡️ Sentry OFF · ❄️ Climate OFF · 🔌 Plugged

温度卡片：
- Inside 24° / Outside 28°

胎压卡片（2×2 网格）：
- FL 2.5 bar / FR 2.5 bar / RL 2.4 bar / RR 2.4 bar

电量趋势卡片：
- 标题「7-Day Battery Trend」
- 柱状图，7根柱子，最后一根绿色（今天），前6根蓝色
- X轴：Mon Tue Wed Thu Fri Sat Sun

---

## 页面 2：Drives（行程列表）

顶部：标题「Drive History」

列表项（卡片式）：
- 左侧：出发地址 → 到达地址（箭头连接）
- 右侧：距离 31.2 km · 时长 22 min
- 底部：日期时间 + 效率 152 Wh/km + 电量变化 85%→72%
- 标签：AC 或 DC（根据充电类型）

点击进入详情页：
- 地图区域（显示路线轨迹）
- 5个统计卡片：距离/时长/平均速度/能耗/效率
- 电量变化条：85% → 72%（绿色渐变）
- 5条曲线图（速度/功率/海拔/温度/胎压）
- 注：图表下方标注 "Simulated data — based on trip summary"

---

## 页面 3：Charges（充电列表）

顶部：标题「Charge History」

列表项（卡片式）：
- 左侧：充电地点名称
- 右侧：+38.5 kWh（绿色大字）/ 65%→100%
- 底部：日期时间 + 充电类型 AC/DC + 功率 7.4 kW + 费用 ¥18.50

点击进入详情页：
- 充电曲线图（功率/电压/温度 3条线）
- 统计卡片：充入电量/费用/效率/电池变化
- 注：图表下方标注 "Simulated data — based on charge summary"

---

## 页面 4：More（更多）

列表式导航：
- 📊 Statistics — 统计钻取（年→月→日→行程）
- 🔥 Heatmap — 热力图（15天×24小时）
- 🌿 Efficiency — 效率散点图 + Golden Foot 评分
- 📍 Destinations — 目的地 Top20
- 💰 Cost — 成本分析（AC/DC拆分 + 月度柱状图）
- 🔋 Range — 续航分析（预估 vs 实际）
- 🧛 Vampire — 吸血鬼损耗
- 🔋 Battery Health — 电池健康（衰减环形图 + 趋势）
- 📄 Annual Report — 年度报告 PDF
- 📤 Export Data — 数据导出（CSV/JSON）
- 🕐 Timeline — 时间线（驾驶绿+充电橙+休息灰）
- 🖥️ Software Versions — 固件版本
- ⚙️ Settings — 设置
- ℹ️ About — 关于

---

## 页面 5：Settings（设置）

实例管理卡片：
- 标题「Instances」
- 列表项：Home TeslaMate · 192.168.1.100:4000（绿色 ✓ Active）
- 列表项：Office Server · 10.0.0.50:4000（可切换）
- 「+ Add Instance」按钮

连接卡片：
- Server URL 输入框（带 placeholder）
- API Token 密码输入框
- 「Test Connection」按钮

显示设置卡片：
- Language（下拉选择：System Default / English / 中文 / 日本語 / Deutsch / Français）
- Theme（下拉选择：System / Light / Dark）
- Mock Mode（开关）

关于卡片：
- Version 0.1.0-alpha

---

## 页面 6：Annual Report（年度报告）

顶部大卡片（渐变深蓝背景）：
- 标题「Annual Summary 2025」
- 大数字：12,450 km
- 副标题：Total Distance Driven

统计网格（3列）：
- 186 Drives
- 2,840 kWh Energy
- ¥1,280 Cost

月度柱状图：
- 标题「Monthly Distance (km)」
- 12根柱子（Jan-Dec），最后一根绿色

充电习惯卡片：
- AC: 120 次 / DC: 66 次
- Average Duration: 2h 15min
- Most Common Hour: 22:00

驾驶习惯卡片：
- Average Duration: 22 min
- Driving Days: 186
- Top Speed: 180 km/h
- Efficiency Rating: Excellent（绿色）

底部按钮：
- 📄 Generate & Share PDF（蓝色大按钮）

---

## 页面 7：Web Dashboard（Web 端）

左侧边栏（深色 #1e293b）：
- 🚗 MateLink（Logo）
- Dashboard（高亮）
- Drives
- Charges
- Statistics
- Trips
- Settings

主区域顶部：
- 标题「Dashboard」
- 4个统计卡片横排：Battery 78% / Today 45 km / Charged 38.5 kWh / Efficiency 152 Wh/km

下方双列布局：
- 左：Weekly Distance 柱状图（7根柱子）
- 右：Recent Activity 列表（绿点=行程、橙点=充电、蓝点=充电完成）

---

## 设计规范

- 字体：SF Pro (iOS) / Roboto (Android) / Inter (Web)
- 圆角：卡片 14-16px，按钮 8-12px，输入框 8px
- 间距：卡片间距 10-12px，内边距 14-16px
- 阴影：轻阴影 0 1px 3px rgba(0,0,0,0.08)
- 动画：页面切换 0.3s ease，数值变化 0.6s 动画
- 图标：SF Symbols (iOS) / Material Icons (Android) / Lucide (Web)
- 暗色模式：背景 #1a1a2e，卡片 #2d2d44，文字 #ffffff
```

---

## 精简版（如果字数限制）

```
设计一个叫「MateLink」的 Tesla 车辆监控 App UI 原型。

主题色：深蓝 #1976D2，强调色绿 #4CAF50 / 橙 #FF9800 / 红 #f44336
风格：iOS SwiftUI 圆角卡片 + Android Material 3
语言：中文

底部4个Tab：Dashboard / Drives / Charges / More

Dashboard 页：
- 顶部：Tesla Model 3 + 绿色 Online 徽章
- 大卡片：电池环形图 78% + 续航 312 km
- 2×2 小卡片：Last Drive 31.2km / Last Charge 42kWh / Inside 24°C / Locked
- 胎压 2×2：FL 2.5 / FR 2.5 / RL 2.4 / RR 2.4 bar
- 7天电量趋势柱状图

Drives 页：行程卡片列表（起止地址+距离+时长+效率+电量变化）
Charges 页：充电卡片列表（地点+kWh+类型AC/DC+功率+费用）
More 页：统计/热力图/效率/目的地/成本/续航/吸血鬼/电池健康/报告/导出/时间线/固件/设置/关于

Settings 页：实例管理(URL+Token切换) + 连接测试 + 语言(6选项) + 主题 + Mock模式

年度报告页：大数字12,450km + 3列统计 + 12月柱状图 + 充电习惯 + 驾驶习惯 + PDF按钮

Web 端：左侧深色边栏 + 4统计卡片 + 柱状图 + 活动流
```

---

## 使用说明

1. **Stitch / Midjourney / DALL-E**：直接粘贴通用版 prompt
2. **Figma AI**：用精简版，配合截图参考
3. **v0.dev**：可以加 `use shadcn/ui` 和 `use Tailwind CSS` 前缀
4. **Claude / GPT**：可以用通用版 + "生成 HTML/CSS 原型"
