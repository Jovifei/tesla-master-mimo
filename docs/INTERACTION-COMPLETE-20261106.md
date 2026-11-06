# 方案 I：App / 小程序交互视觉完整源码阶段

日期：2026-11-06。仓库只限 `Jovifei/tesla-master-mimo`。这是已接线的 UI 源码交付，不是让本地 Agent 实施的设计清单。没有修改 main、原工作区、生产服务、历史数据、凭证或车辆。

## 固定基线与新分支

| 端 | 新分支 | 本轮开始时核对并保留的基线 |
| --- | --- | --- |
| Android App | `codex/app-interaction-complete-20261106` | `fc812ff8505dc72f6002023bc93c041d5de92b75`，原 `codex/app-interaction-latest-20261106` |
| 微信小程序 | `codex/miniprogram-interaction-complete-20261106` | `51d6514a7d897ec2890e60bc37d00c43c4d87a71`，原 `codex/miniprogram-interaction-latest-20261106` |

两条线分别继承自己的最新实现，不互相覆盖后端。交付 HEAD/tree/parent 以本 PR 最终固定回执及本提交的 `git rev-parse HEAD HEAD^{tree} HEAD^` 为准；不能把某个旧绿色提交的结果移给新提交。未自行合并原开发分支或 main。

## A—G 完成对照与真实边界

| 组 | 已保留并接入的实际消费者 | 本轮增量与边界 |
| --- | --- | --- |
| A 车辆连接 | App TeslaLoginScreen；Mini vehicle/auth 页面；ConnectionVisual | 内置真实车型侧图、连接中/待确认/确认/不可用状态、窄屏自适应；只由真实已有状态驱动，不用计时器伪造绑定完成 |
| B 四轮胎压 | App TpmsTrendScreen → TpmsVehicleVisual；Mini tools → TpmsPanel → TpmsVehicleVisual | 已保存作用域阈值真正进入轮位颜色及原生文案；低/高/范围内/旧观测/未知独立，显示逐轮时间；点选过滤实际趋势，合法 0 保留 |
| C 行程 A/B | App DriveDetailScreen；Mini drives 详情 | 保留实际地图、数据和地址；仅表示起终点顺序。Mini 回执变化同步清除旧端点焦点，同回执重绘不清除用户选择，后台/减少动态效果时停止过渡 |
| D Tesla / 微信 | App 原有 Tesla → MateLink；Mini auth/me 的 Tesla 与可选微信流程 | 原生授权动作不变，错误优先于旧成功。Mini 区分 MateLink 会话已建立与微信绑定已确认。当前 Android 基线没有微信绑定消费者，不新增假按钮或假成功，也不把这项描述成已具备原生微信登录 |
| E 驻车充电 | App ParkedDetailScreen 的 RecordedChargeRail；Mini drives/analysis 的 ParkedChargeTimeline | 仅画已记录时段。未知结束用空心/虚线；Mini 用 ID+开始+结束定位展开，不因重复 ID 展开另一条；电量非有限保持未知，0 保留，不从空白推断停充/耗电 |
| F 车身开口 | 两端现有 dashboard 展开区 | 车辆位置示意 + 四类状态；App 补齐原生文字及无障碍信息，打开/关闭/未知不只靠颜色表达。汇总车门/车窗不推断具体哪一扇 |
| G 加载与空态 | 原品牌加载、drive/charge 空态及失败状态 | 不改变加载/重试原流程，不新增整页图片遮罩；保留前后台和减少动态效果约束。失败并非没有记录；说明与按钮仍由原生页面负责 |

原页面布局、导航、指标顺序、地图、真实授权和数据结构保留。不是对全部页面重新设计，也不是给每个静态状态复制一张含长文字的图片。

## 图片与状态合同

- 两端连接/充电插图复用既有公开车型 PNG（720×405），SHA-256 `450d729623ffdb4aa7f2936afd6b0522f918f0b1c8de1f81cdb90baf71a56d54`；插图不冒充当前绑定车辆。原车型目录、照片选择和 Owner 图片不变。
- 俯视轮位采用原有同形位置示意（SVG/Android vector/Mini PNG），不能宣称识别了真实车辆车型。图片本身不嵌入长文案，中文/英文状态由 UI 控件呈现。
- 颜色沿用当前主题。胎压“设置范围内”仅表示满足用户已保存上下限，不能叫原厂正常诊断；无配置、停用、无效配置或未核验/旧观测不做新告警。
- Mini 继续用现有 `evaluateTpmsAlerts`，包括已有变化规则；App 复用 `TpmsCustomAlertEvaluator` 的上下限比较，不重写或扩大后台通知算法。视觉提醒不证明系统通知送达。
- 时间为当前来源已提供的独立观测/接收依据，不能把最新一轮时间说成四轮共同采样时间；旧历史不升级成实时值。

## 本轮根因与修复

Mini 路线组件把焦点存在与回执无关的局部状态中，回执变化后可能保留旧焦点；新回执键同步重建内层状态。驻车展开原来仅用 ID，遇到复用 ID 会同时展开不同时间段；现在采用完整区间键并在回执替换后清空。非有限电量不再格式化成 NaN/Infinity。连接卡采用等比例可收缩布局，减少动态效果和后台状态会关闭端点过渡。

胎压视觉原先没有接到已保存阈值。Mini 从已核验 user/origin/stable vehicle 的既有设置读取，再经 TpmsPanel 接入；App 在与历史相同的已核验 context.localHistoryCarId 下读取配置，读后重验身份，迟到请求不能发布旧规则。App 刷新任务在 start 前记录 lazy job，修复同步/重入发布覆盖最新取消对象的风险。设置变化清除旧资格并重新读取；车/账号变化不保留另一作用域的显示资格。

## 实际验证与固定回执

小程序修复源码 `72f7e293e9f5a292ef5ccde08ec246cd5bd06564` 在受控 runner `42157495074` 中先完成类型、全部 1009 项测试（0 fail/error/skip）、微信生产 bundle 以及 52 张真实组件浏览器截图，再正常快进提交。artifact `13475581261`，SHA-256 `3aa45e7d66104d37238d9e640f4853a916f560b21fea08a2b6b40335ea5e8646`。这是实际修复证据，最终文档提交仍须由自己的全部检查重新验收。

最终必须核对：App 完整 Debug/Release XML、双 lint、R8、API35 原生 Room/Compose 和截图；Go/隔离 PostgreSQL/race/TLS/upstream/Web；Mini 类型、全部组件与资源测试、四时区、生产 bundle 和真实 React 消费者截图。浏览器 DOM 适配层的截图不算微信原生验收；模拟器图片不算用户手机或自然车况。最终 run/job/artifact 和准确计数写入 PR 固定回执，不借用旧绿色。

## 仍独立的门

本次完成交互视觉源码及上述相关数据/取消边界，不声称完整加密备份恢复、归档桥长期续绑、跨来源历史去重、所有车辆命令上线或所有自然通知已经完成。真实 Tesla 同意/车辆确认、微信真实绑定、双真实账号隔离、自然 Fleet 行程/充电/TPMS/通知、签名保数据装机、微信开发者工具/真机，以及生产备份/部署/回滚仍分开核验。未知保持未知，个人归档不替代 Fleet 首事件。

本地接收和回滚矩阵见 `LOCAL-RECEIVE-INTERACTION-COMPLETE-20261106.md`。
