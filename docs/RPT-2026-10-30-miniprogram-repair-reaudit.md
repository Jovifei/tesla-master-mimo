# 小程序修复回执独立复审与逐页运行审核

日期：2026-10-30。结论：**修复未完成，CHANGES_REQUIRED**。

## 审核基线与结论

- 实际目录：`E:\project\tesla_master\app_mimo`。
- 分支：`codex/miniprogram-e-migration-20261029`；HEAD：`0e4cd561dc7d79a980f08dff8ad056b19093481b`。
- 本次审核的是 **HEAD 加当前未提交修复**，不是单独的 HEAD。版本仍是 0.6.1。
- Jovi 粘贴的“6/6 全部完成”是待核实的执行方声明。逐项复核原 21 项后：**7 项关闭、8 项部分修复、6 项未关闭**。部分项只剩参数标签等较小问题，表中已说明，不把它们都认作仍有原来的严重程度。
- 当前自动测试 **818 项：773 通过、45 失败**；60 个文件中 54 个全绿、6 个存在失败；资源测试 6/6 通过。TypeScript 检查失败，有 2 处错误。
- 隔离微信构建成功，但 Taro/Babel 编译成功不会验证 TypeScript 类型，不能覆盖前述失败。
- 微信开发者工具实际运行 **35/35 个入口状态**，无捕获到的未处理异常；可渲染不代表参数正确。另有详情、曲线、短充、实时充电、同意版本、设置、长途和导出交互取证。
- 直接执行真实源码的 25 个独立业务反例，**11 通过、14 失败**。这些是围绕风险选择的反例，不代表整个产品的缺陷率。

本轮未改业务源码、原有测试、Android/iOS、私密配置或原 dist。所有新脚本、构建副本、截图与报告都在 E 盘。

证据目录：`reports/mini-reaudit-20261030/`。

## 一、修复回执逐项判定

“关闭”指本轮核实的原始反例通过，不表示真实车辆或生产验收通过。

|原项|判定|当前结果|
|---|---|---|
|P1-01 来源身份|部分|DTO 已增加来源字段；公开 ID 回退仍跨实例合并；费用覆盖与长途保存未贯穿新身份|
|P1-02 隐藏短途跨接驻车|未关闭|helper 修了，但页面先过滤短途再把剩余集合当完整序列|
|P1-03 隔离/异来源驻车|部分|analysis helper 保留了隔离边界；行程页面仍提前移除隔离记录|
|P1-04 免费超充覆盖账单|部分|正账单不再被抹成 0；无账单历史 DC 仍可由当前车级权益猜为免费|
|P1-05 币种安全|关闭|TRY/BGN 保留，冲突/null 和与估算币种不符时不再估 CNY；三字母格式不等于已验证 ISO 发行币种|
|P1-06 TeslaMate 零费用跨页|未关闭|是否传 estimatePrice 仍决定同一个 0 是估算还是 actual 0|
|P1-07 嵌套车辆身份|关闭原反例|嵌套 car_id=9、请求车辆=7 已拒绝；缺 car/空 car 仍按兼容路径接受，未证明强制身份齐全|
|P1-08 地图转换/断线|部分|坐标转换、零坐标排除已接入；两个孤立点之间仍会被 fallback 连线|
|P1-09 功率方向|关闭|正/负样本分别取峰值，回收取绝对值，无对应方向显示未知，均值明确标为样本|
|P1-10 单位费用|部分|电池充入分母已正确，12 元/10 kWh=1.2；页面仍把该 10 kWh 称为“输入电量”|
|P1-11 旧长途覆盖新费用|关闭直接顺序问题|同键时最新历史已胜出；跨实例/长途投影丢身份另归 P1-01|
|P2-01 当前充电实时字段|未关闭|SOC 仍读错层级，live 电量被完成历史规则拒绝，开始/观测时间与持续时间未完整分开|
|P2-02 当前充电状态机|部分|新增 starting/charging_started；失败仍可被当 null；Insights 不消费 isCharging=false|
|P2-03 短充开关|部分|列表和图表联动，摘要未联动|
|P2-04 默认时间窗|关闭|初始化、reset、缓存读取 fallback 都已接入行程 7 天/充电 30 天|
|P2-05 日期区间|部分|chip 的反序交换已加；任意结束日仍不可选，Insights 独立起止输入未 normalize|
|P2-06 AC/DC 分类|未关闭|raw fallback 与完整合同 mode 仍由不同消费者分别采用|
|P2-07 真零值|关闭|0 分钟、0 距离均速与仅有 0 A 的 HUD 门控均已修正；部分旧测试仍要求旧文案|
|P2-08 满页畸形行分页|关闭|已按原始 rows.length 推断 hasMore；20 行中 1 行无效时仍可继续|
|P2-09 同意版本|未关闭|存储 helper 已增加，但页面仍用 session 存在将勾选置 true|
|P2-10 历史能力/更新分页|未关闭|tools 源码未改；温度/哨兵/国家地区仍属诚实占位，更新仍用 length===20 判断下一页|

## 二、仍会影响数据正确性的主要问题

### 1. 新身份键之后的 ID 回退仍覆盖不同来源实例【P1】

`miniprogram/src/domain/history.ts:31` 生成了不同实例键，但 `:140-144` 的 compatibleID 仍只看公开 ID/session，`:156` 在 key 不同时继续用它强制合并。

当前真实源码执行结果：

```text
实例 A、实例 B：同 id=1、session=session-1，canonical key 不同
预期保留 2 条；实际只剩 1 条
```

新增的 `miniprogram/src/domain/history.test.ts:173` 自己也验证这一反例，本轮直接失败。

同一个遗漏还贯穿：

- `domain/history_controller.ts:72-80`：B 实例详情仍可回填 A 实例记录。
- `services/charge_cost.ts:21-24`：A 实例保存的手动金额 5，会套用到同 session 的 B 实例。
- `domain/insights.ts:109-138`：长途白名单投影丢弃 sourceInstanceId/sourceRecordId，随后去重；选中 A/B 两段后实际只保存 1 段。
- `domain/history.ts:35`：直接拼接冒号，`(a:rec:b,c)` 与 `(a,b:rec:c)` 生成相同键；source/sourceVehicleId 也未参与。

建议修复边界：统一来源身份必须贯穿 DTO、key、兼容回退、详情准入、缓存、费用覆盖、长途保存和重新读取，不能只新增 key helper。

### 2. 驻车 helper 没有收到真正完整序列【P1】

`miniprogram/src/pages/drives/index.tsx:497-500` 仍先调用 `items.filter(isAppRouteDrive)` 和 filterHistory，再把 trustedItems 传给 selectVisibleDriveHistoryItems。

输入 A 00:00–00:10、短途 B 01:00–01:01、C 02:00–02:10：

- helper 直接收到完整 A/B/C 时，不造 A→C，反例通过。
- 页面同样的真实调用链先移除 B，仍造出 **A→C 的 110 分钟驻车**。
- B 换成 quarantined 时，页面同样跨接。
- 本轮微信模拟器也实际显示了该错误驻车卡，截图 `interaction-parked-short-gap.png`。

analysis.ts 保留 quarantine 边界的修复有效，不再重开这个已解决的 helper 子项。

### 3. 地图 fallback 恢复了本应断开的路线【P1】

`miniprogram/src/pages/drives/index.tsx:642`：

```text
polylines.length > 0 ? polylines : [{ points: allValidPoints, ... }]
```

“有效点—缺坐标点—有效点”没有任何连续两点线段，polylines 本应为空；当前 fallback 仍把两个孤立点连成一条线。独立探针从当前源码提取构造段和实际 JSX 表达式执行，实际 1 条线，期望 0 条。WGS84→GCJ-02 转换和 (0,0) 排除已确认修复。

### 4. 费用仍有免费资格与跨页语义分歧【P1】

- `services/charge_cost.ts:74-75`：正数账单优先已正确，但当前车级 freeSupercharging+DC 仍会把缺账单历史判为 free 0，没有会话级免费证据。
- `services/charge_cost.ts:71`：TeslaMate 原始 cost=0 的含义仍依赖调用参数。同一 10 kWh/CNY 记录，充电页得到 estimate 11.40，分析页得到 actual 0。
- `pages/charges/index.tsx:570` 传默认估价与当前车辆免费标志，`pages/analysis/index.tsx:252`、`pages/insights/index.tsx:132` 和 `pages/tools/index.tsx:117` 没有采用同一投影配置。

正账单优先、TRY/冲突币种反例、电池分母和同键最新费用顺序已经通过，本轮不要求重做这些已确认子项。

### 5. 当前充电仍丢实时参数，停止态仍显示正在充电【P2】

- `services/api.ts:656` 读的是顶层 raw.current_battery_level；后端 `deploy/jourvolt-dev-mock/telemetry_http.go:232-234` 实际放在 battery_details.current_battery_level。
- `domain/drive_energy.ts:323-338` 继续用完成历史规则解析 live 累计电量。
- `pages/insights/index.tsx:262-267` 不使用 isCharging=false 隐藏停止会话。
- `pages/charges/index.tsx:342-351` 仍把 current 请求失败折成 null 再判断“即将开始”；初次 load 与 poll 还走不同过滤路径。

真实源码与微信模拟器合成响应均已验证：

```text
输入 source=fleet_api、battery_details.current_battery_level=66、charge_energy_added=5
实际 DTO：currentBatteryLevel=null、energyAddedKwh=null
is_charging=false 时，Insights 仍显示“正在充电”及 0 kW
```

开放会话通常没有 duration_min；Fleet-only start_date 可能只是观测时间，页面仍标“开始”。这些参数不能用当前快照补成未经证明的历史起点。

### 6. 短充列表/图表与摘要仍不是同一母集【P2】

`pages/charges/index.tsx:573` 的 intervalView 未读取 showShortCharges；`:578-581` 只修了图表。

微信模拟器两条合成记录：10 kWh/12 元 + 0.05 kWh/12 元。

|状态|列表|图表|摘要|
|---|---|---|---|
|隐藏短充|1 条|1 条 / 10 kWh|2 次 / 10.05 kWh / 24 元|
|显示短充|2 条|2 条 / 10.05 kWh|2 次 / 10.05 kWh / 24 元|

截图 `interaction-short-charge-off.png` 直接展示了这一差异。

### 7. 同意版本、日期与参数来源标签仍有残留【P2】

- `pages/me/index.tsx:35` 仍是 Boolean(initialSession) || validConsent；`:126` 只要 session 存在就强制勾选。微信模拟器注入旧版本记录后，两个 checkbox 实际仍为 true。新增 helper 本身还接受 Infinity 时间戳，但页面绕过是主要问题。
- `pages/insights/index.tsx:200-201` 的两个日期输入仍直接 setQuery；反向区间会把本应在范围内的记录排空。行程/充电独立结束日仍由 CSS 隐藏。原生 SDK 的 picker 事件注入本轮未成功改变值，因此这项依据源码链和直接函数反例，不冒充原生控件验收通过。
- `pages/insights/index.tsx:234-235` 的单位费用已用电池 10 kWh，却仍标“费用与输入电量配对”。原生显示 10 kWh、1.2 CNY/kWh，而合成 AC 输入实际为 12 kWh。
- 新补充发现：`domain/data_labels.ts:6-9` 未包含 fleet_api。当前充电原始 source=fleet_api，页面仍显示“来源未标明”，与数据实际来源不符；`interaction-current-live.png` 可见。

### 8. 当前类型检查阻断交付【检查门失败】

```text
src/services/charge_cost.ts(74,89): TS2339
Property 'costProvenance' does not exist on type 'Charge'.

src/domain/insights.test.ts(73,11): TS2740
构造的 Charge 缺少 durationMinutes、SOC、latitude 等必填字段。
```

前者位于业务源码，后者位于测试。不能以“微信包能生成”替代类型检查通过。

## 三、逐页参数、公式与来源复核

178 处生产组件参数绑定已按当前 TypeScript AST 提取到 `parameter-bindings.md/json`，包含文件、行号、实际值表达式和单位。它是绑定索引，不是 178 个独立参数全部通过的声明。下面列出各消费链的计算口径、运行证据和限制。

|页面/功能|参数/来源与计算|本轮判断与证据|
|---|---|---|
|仪表盘|cars 元数据；snapshot/status 的 SOC、额定/理想/估算续航、里程、位置、车内外温度、锁/枪/空调/哨兵、驾驶速度/功率/挡位；同响应 mi→km、F→C|原生渲染；合成 SOC 80%、280 km、20118 km、22/18°C 对应；0/false/负功率等原有单测通过|
|HUD/底部当前充电|chargeEnergyAdded、chargerPower、电压/电流、小时换算剩余时间、目标 SOC|仅有电流的门控已修；文案变动令一条旧断言失配；真实充电未验|
|车辆外观|已关联资料与本机手动外观分开；不影响车辆身份|资源 97 图、6 项完整性测试通过；原生样例“外观未识别”属当前合成输入边界|
|数据状态/配对|readiness、pairing、缓存诊断独立来源|现有组件/请求负例通过；真实钥匙与远程 configure 未操作|
|行程列表/摘要/趋势|公里、分钟、SOC、服务端峰速；合格净能量÷同笔距离×100；摘要四项|原生默认 7 天/10/100km 距离 chip 正确；来源身份与驻车仍阻断|
|行程详情|报告均速与距离/时长均速分开；SOC 差；净 kWh；按正负选功率峰值与样本均值|合成 10 km/30 min/1.5 kWh=15 kWh/100km，20 km/h；正功率无回收；原生取证通过对应参数|
|行程地图|逐点坐标转换、缺点分段|转换已修；孤立点 fallback 仍错误连线|
|驻车|完整邻接、起止时间/SOC、服务端储能/环境/充电重叠|analysis helper 部分修复；行程页误跨短途/隔离记录原生复现|
|行程曲线|原始时间点速度/功率/SOC/海拔；速度时间积分归一为距离估算；最多600绘图点|原生五曲线渲染/全屏可用；完整游标与断点单元验证；原生 SDK slider 注入未确认移动成功|
|充电列表/摘要/月图|分钟、电池充入、输入侧电量、SOC、费用；币种分开；短充开关|单条原生参数正确；短充摘要母集、零费用投影、mode/身份仍有问题|
|当前充电（两个入口）|current 的 live 值、状态、功率、起点/时长|原生空态可用，非空反例丢 5 kWh/66% SOC、停止态误显示|
|充电详情|合格电池/AC 双计数器，同来源/窗口/模式后才算效率；样本极值/算术均值|原生 10/12×100=83.33%；功率0/10均5，电压230/240均235，电流0/16均8，温度20/22均21；均保留0|
|充电曲线|SOC、功率、电压、电流、温度来自原始采样，缺失不补0|原生五曲线及单元验证；真实采样完整性仍未验|
|统计|总距离/能量覆盖独立；平均能耗为配对加权；次数、时长、平均距离、费用、AC/DC、温度/月分组|原生可渲染；计算现有单测通过；费用/来源/mode 残留影响汇总|
|里程|按本机年月日分组和日历下钻，区别于终身里程表|原生与下钻组件测试；未知日期不生成里程；来源身份仍影响输入|
|能耗|合格净能量/同笔正距离；历史最低/中位/最高、百分位、温度与速度分组|原生渲染；合格/负/0/未知/配对测试；不解释为公开车型排名|
|时间轴|已加载行程/充电/驻车排序，日期同本机时区|原生渲染；来源身份及费用仍影响结果|
|待机|合格 EnergyRemaining 端点和同窗口小时才算 kWh/W；SOC 不猜容量|原生诚实未知能量；analysis 隔离边界修复通过；采样真实资格仍待真实 Provider|
|电池健康|capacity、health%、rated efficiency、range 读取 battery-health；Wh/km÷10|原生报告59.2/60 kWh、99.3%、衰减0.7%、14.2 kWh/100km；合成报告缺来源时续航估算保持未知|
|续航|近90天正能量/正距离，条件分组5段/100km或全局10段/300km，加合格容量；额定下降偏差/季节/速度|原生可渲染；现有门槛、单位、无容量负例测试通过；历史身份残留影响样本|
|费用|同币种费用与同笔电池充入配对；单次均价；月度/地点；分时估算单列|12/10=1.2已验证；输入标签错误、TeslaMate zero 跨页不同；不能据此给财务结论|
|长途|手选段，重新计算公里/分钟/kWh/配对能耗/币种费用；有界脱敏本机保存|原生创建、保存、编辑可运行；跨实例两段保存成一段，独立反例失败；删除仅合成单元覆盖|
|分时电价|峰/平/谷、24小时无重叠覆盖、默认价、币种与保存时区|原生恢复默认/保存/重开字段一致；跨账户、时段、真0单测通过|
|位置/历史位置|一次坐标转换；只读实际采样或有资格的窗口，不插值|原生当前位置/历史查询入口；历史位置请求单测通过；地图实际道路与真实时刻未验|
|胎压/提醒|逐轮 bar、来源和时间资格；30天保留；新鲜观测与本机阈值比较|原生未知态、阈值输入校验与保存反馈可运行；原生持久化重开读回未证实，由单元测试覆盖；逐轮/旧值/真0/告警单测通过，真实胎压未验|
|温度/哨兵/国家地区|只有当前温度/哨兵状态、已有地址，未生成历史/地区推断|原生诚实占位；属于尚无能力，不能记功能完成|
|软件更新|updates 的版本/时间；当前 length===20 决定下一页|原生3条记录可见；元数据分页问题未修|
|年度报告|本机年份筛选；距离/能量独立覆盖，能耗配对，费用按币种|原生10km、15kWh/100km、12CNY正确；生成2331字节PDF；费用/身份输入仍受未关闭项影响|
|CSV/JSON 导出|固定字段脱敏、公式注入防护、日期/类型过滤、覆盖声明|原生生成431字节CSV，PDF头正确；现有JSON/CSV/xref测试通过；真实分享被拦截，未发给任何人|
|更多/设置/法务|入口、会话、同意版本、条款/隐私文档|原生渲染；旧同意+会话仍被勾选；隐私/条款版本常量一致|
|Tesla/微信授权、退出、解绑、注销|owned transaction、origin/user/generation、后台 binding、严格URL、会话与账号清理|原有自动化负例通过；原生仅验安全空授权页；未操作真实账号、微信绑定或注销|
|线框预览|本地旋转/缩放/开口示意，不发车控命令|原生入口渲染；不代表加载实车3D模型|

## 四、测试失败如何解释

原始结果保留在 `full-tests.json/log`，没有修改断言或测试输入来制造全绿。

|测试文件|通过|失败|
|---|---:|---:|
|domain/history.test.ts|15|1|
|domain/history_pages.test.tsx|32|35|
|services/api_security.test.ts|47|1|
|services/charge_cost.test.ts|11|2|
|pages/charges/charge_detail.test.tsx|18|5|
|pages/vehicle/vehicle_page.test.tsx|53|1|

上表以机器摘要 `test-file-summary.json` 为准；其余54个文件全部通过。失败数量不能直接当作45个独立产品缺陷。

时间夹具诊断：历史页许多测试写死 2026-10-14，现在实际日期为10月30日，新默认近7天会把这些记录排除。**只在证据目录增加独立固定时钟 setup/config**，将该文件诊断时钟设为10月15日，67项得到65通过/2失败，原来的35个失败降到2个。这个诊断解释了其中33项的日期依赖，不能替代原始测试门，也没有覆盖原文件。

其余失败包含真实身份合并失败，以及新币种/默认日期/短充规则、explicit null 币种与旧文案预期尚未统一。必须逐项校准合同与夹具，不能简单删除失败断言。

## 五、构建、运行证据与展示

- 382个构建输入文件复制后逐个校验哈希，依赖通过 E 盘 junction 复用；未复制 .env。
- 隔离验证域名为 `https://audit-build.invalid`，不是生产候选包。
- 普通构建197文件，内容SHA256：`d4d91f36572b1ac1d5ccc094d949e1d7a89681f395decee046c7ba3d4d3470a9`。
- 后续原生展示使用同源码合成构建：`6eb802490e684b6e5aea5f841d5787ef33780d1ee1c2e576ec0da17711e8da63`。
- 当前源码内置合成开关在无 process 的微信环境未直接启用；因此实际展示由自动化SDK对 HTTP、storage 注入合成响应与独立内存状态。源码没有为展示而修改。
- SDK方法触发与真正用户操作不等价的地方已保留边界：picker/slider 原生值变更未确认；文件系统替身初测不兼容后，改用模拟器原生文件系统复测，CSV/PDF已真实生成到沙箱，随后保存到E盘并清除本轮两个沙箱文件。
- 分享/openDocument被拦截，仅验证调用，不宣称真实发送或外部查看器通过。
- 测试后的自动化钩子已恢复。

展示页：`reports/mini-reaudit-20261030/index.html`。包含35入口截图、关键交互截图、实际参数、失败摘要与完整证据链接。

## 六、验收边界与下一步

本轮完成的是“独立复审与重新测试”，**没有认定修复完成**。没有部署、体验版上传、手机安装、真实车辆操作、真实账号删除或外部消息发送。

真实 Tesla/Fleet 数据、微信实际绑定与OAuth、真机地图位置一致性、真实分享送达、后台订阅通知仍未验证。温度/哨兵/国家地区历史能力仍缺失。

建议先关闭类型错误，并补齐“新身份→兼容回退→缓存→详情→费用→长途”的整条链，再处理行程页面驻车母集、地图fallback、唯一费用语义和当前充电状态。回执里的勾选只有在对应生产消费者与反例都通过后才能成立。

Hub：本轮显式绑定 E 盘 Git 身份后读取 revision，仍返回 WinError10061 连接被拒绝；未发送状态事件，不自动重试。错误保存在 `hub-revision.log`。
