# MateLink Android 版本发布台账

## [2.1.93 / build 113] — 2026-11-21 信息密度改造（已覆盖安装）

- **问题（Jovi 实测）**：字体偏大、废话多；充电热力图变成一行/大格子，看不到几个月；行程首屏被摘要+柱图+日期卡占满，看不到行程；仪表盘大车图占首屏；充电首屏被摘要占满。
- **修改**：全局 typography 降一档（display 57/45/36→44/36/30，headline 32/28/24→26/22/20，正文 16/14→15/13，标签 12/11→11/10）；行程页保留摘要置顶并压缩，删除重复“行程历史”标题，行程列表紧随摘要；柱图移到列表下方；充电摘要保留顶部并删掉第二行重复算术，热力图改为有数据的 3 个月紧凑多周网格并移到列表下方，默认筛选 90 天，删除重复标题与“查看最近30天”长句；仪表盘车图 170→110dp、最近行程提到里程/位置上方、删除重复电量行、预约/空调并一行、空能耗不占格；三页标题再降半档。
- **验证**：`ChargesRoomCostConsumerTest` 适配 90 天默认窗口；`Charges*`、`Drives*`、`Dashboard*` 定向测试通过；全量 Debug 1980 项 0 失败；第二版按 #115 修正：摘要回顶部压缩、充电列表在热力图上方、仪表盘删重复行；真机 2.1.93 首屏确认：行程页首屏 3 条行程、充电页紧凑多月热力图+首条记录、仪表盘最近行程上移。
- **正式包**：`com.matelink` 113 / 2.1.93，APK SHA-256 `2F5A19986A1D84C8694F53476585EDAA15EBD787E3059B05C13A0181604AA9AE`，归档 `E:\Claude_allow\Download\MateLink-2.1.93-build113-corrected.apk`，`lastUpdateTime=2026-11-21 10:14`。**已提交并推送 `133dd72b`（2026-11-21 11:10 覆盖安装复核）。**
- **后续**：充电摘要是否折叠/移到列表下方、其余分析页说明文字继续精简，待群里拍板。

## [2.1.92 / build 112] — 2026-11-20 数据源加固（已覆盖安装）

- **背景**：2026-11-20 部署事故——自建 reader（192.168.0.104:18080）停机期间 App 停留旧缓存且无任何提示，Jovi 打开 App 误判"数据丢失"。数据本身无损失（自建历史在手机本地 Room，云端历史在云端库，两套作用域）。事故复盘与铁律已写入项目记忆：任何停/切数据源操作必须先说明影响、事后验证回执。
- **加固**：主页新增「数据源离线 · 当前显示最后已知状态」横幅（`DashboardScreen.DataSourceOfflineBanner`）：snapshotFreshness 为 HISTORY/UNAVAILABLE 时显示，附最后同步时间（无成功同步时显示"尚无成功同步"）；数据源正常时不渲染，不阻塞既有缓存内容。
- **服务**：自建 reader 栈 7 容器已重新拉起，18080 手机侧可达（401 鉴权正常）。
- **测试**：SettingsExperienceContractTest（版本契约 112/2.1.92）、DriveHistoryAutoEnergyConsumerTest、DrivesAddressEnrichmentTest 通过；R8 `assembleRelease` PASS。
- **正式包**：`com.matelink` 112 / 2.1.92，APK SHA-256 `EE4C0FDB328031E3759843AF03A513D569513556A1D8FA68F820F4A8FB9FD211`，归档 `E:\Claude_allow\Download\MateLink-2.1.92-build112-dirty.apk`。覆盖安装成功，`lastUpdateTime=2026-11-20 23:04:03`。真机验证：云端车辆在线 99%/380km，最近行程 11-20 21:40，crash buffer 无记录。
- **当前改动尚未提交。**

## [2.1.91 / build 111] — 2026-11-19 行程真实地点与日期卡紧凑化（已覆盖安装）

- **需求**：行程卡不得再显示字母 A/B，必须显示真实物理地点；行程页日期扇形卡由竖向四行（相对日期/星期/日/条数）压缩为两行，首行“周六 + 9 条”、第二行大号“14 + 11月”。
- **A/B 根因（真机诊断）**：自建列表收据中的 25 条行程全部没有起止经纬度（诊断计数 40 个缺失端点），因此原地址补全逻辑即使放宽身份校验也拿不到坐标；引擎能出真实地名的只有详情接口/详情缓存（含位置点）。旧版详情又把地址反查串在多次 2 秒元数据请求之后、共用 3 秒总预算，两次 AMap 反查（700ms 限速）常被整体丢弃。
- **修复**：
  - 列表：先读取该行程的本地详情缓存，再对最近至多 8 条缺失端点发起有界详情请求，用详情起止点/位置点补出坐标后再做 AMap 反查；所有坐标只用于展示地址，不写入能耗或统计证据。
  - 详情：起止点各自独立 6 秒预算并即时发布，先解析出的一端先显示；缓存详情头缺经纬度时回退到列表收据坐标；地址解析与车辆元数据并行，元数据后续刷新不再覆盖已解析地址。
  - 反查：AMap 优先取小区/学校/商场/社区 AOI；AMap 不可用时在自建模式下回退 OSM Nominatim（1 req/s 限速）；街道地址在反查失败时保留原街道名，不再退成字母。
  - 兜底文案：确无任何坐标/地址时显示“出发地/目的地”（英文 Origin/Destination），不再显示“A点/B点”。
- **日期卡**：两行布局，首行相对日期或星期 + 当日条数，第二行大号日号 + 小号月份；7 个日历日、左右滑动、点选与右侧卡片让位逻辑不变。
- **测试**：`DrivesAddressEnrichmentTest`（新增 source 为空、正 driveId 仍生成地址目标的用例）、`AddressDisplayFormatterTest`、`GeocodingExternalBoundaryTest`、`SettingsExperienceContractTest` 通过；`:app:compileDebugKotlin` 与 R8 `:app:assembleRelease` PASS。
- **真机复验**：自建 reader 服务运行时，11/18 四段行程全部显示真实地点：08:44“业成街万通中心 → 钱腾云都”、07:28“精诚电力设备 → 业成街万通中心”、08:37“钱腾云都 → 精诚电力设备有限公司”，07:36/08:42 驻车分别显示“业成街万通中心”“精诚电力设备”；日期卡显示“昨天 6 条 / 18 11月”“周六 9 条 / 14 11月”等两行。首装时间未变，crash buffer 无 com.matelink 记录。
- **正式包**：`com.matelink` 111 / 2.1.91，`debuggable=false`，证书 SHA-256 `9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`；APK SHA-256 `66C5B8DB110F0301750CF3B70DC7251DAD98D7D56BE5DB4D0B0C465546EAAE40`；归档 `E:\Claude_allow\Download\MateLink-2.1.91-build111-dirty.apk`。覆盖安装成功，`firstInstallTime=2026-08-31 22:36:47` 未变，`lastUpdateTime=2026-11-19 00:38:49`。
- **已知边界**：若自建 reader 服务不可达且该行程详情从未缓存，则起止坐标仍无从取得，卡片显示“出发地/目的地”而非真实地点；服务恢复后列表会自动补详情并解析。驻车地点同理，缺端点时保留“未知”。当前改动尚未提交。

## [2.1.90 / build 110] — 2026-11-18 第一批数据/交互更新（已覆盖安装）

- **需求共识**：小时级 SOC 本地曲线（现有 15 分钟状态轮询 + 行程/充电起止电量回填，每小时最多 1 点，保留 30 天，空档实线连接）；按反馈将曲线放在**仪表盘“7天电量趋势”卡片点开展示**，不放在充电页；充电页保留**按日充电量热力图**旧形式；行程日期改为有限高度横向斜向扇形，固定最近 7 个日历日；驻车段按相邻行程 ≥30 分钟显示；速度/功率曲线连成线；地图用全部可用 GPS 点连折线，点不足退虚线 A→B；说明文字收进 `ⓘ`；本地/云端边界按“云端只保留授权、车辆标识和必要命令状态”；升级前自动数据库快照保留 1 份。
- **数据层**：新增 Room `soc_samples`（schema 25）与 `SocHistoryStore`；TPMS 后台轮询、Dashboard 状态刷新、行程/充电汇总回填本地 SOC；`DatabaseModule` 在迁移前复制数据库到私有 `database_backups`（保留 1 份）；移除同步时的本地历史上传调用；旧版自建充电明细来源为空时允许合并，修复整轮同步 `invalid_response`。
- **UI**：仪表盘“7天电量趋势”卡片点开显示本地小时曲线（仅曲线，点按显示时间/电量）；充电页恢复“按日充电量”旧热力图；行程历史改为横向斜向日期扇形，点击/选中卡片时右侧日期卡自动右移让出空间，7 个日历日卡面可读，空日期保留；速度/功率使用连续观测线；路线地图优先完整 GPS 折线；统计概览/电池/能耗/费用/年度/里程/续航/待机共 8 页加入语义色 + 浅渐变。
- **A→B**：地址解析优先使用 AMap AOI/小区/学校/商场/社区名称，街道地址会尝试坐标反查；无法取得地址时明确显示 A点/B点，不再显示“未知”。
- **测试**：Debug 全量 **1979 项 0 失败/0 错误**；R8/`assembleRelease` PASS；真机验证同步 SUCCESS（此前 `invalid_response` 消失），11/17 四段行程已显示；仪表盘点开 SOC 曲线、旧热力图、日期扇形、驻车行、连续曲线均已复验。
- **正式包**：`com.matelink` 110 / 2.1.90，`debuggable=false`，证书 SHA-256 `9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`；APK SHA-256 `7C0E47C69CB0F8FE0480322E766EFED78825109E9DAF273777D59754C716F56A`；归档 `E:\Claude_allow\Download\MateLink-2.1.90-build110-dirty.apk`。覆盖安装成功，`firstInstallTime=2026-08-31 22:36:47` 未变，`lastUpdateTime=2026-11-18 22:28:02`；crash buffer 无 com.matelink 记录。
- **11/17 缺失原因（已记录）**：当时自建服务 `/drives` 只返回到 11/16，App 未删除；同时旧版 source=null 充电明细让整轮同步 `invalid_response`。修复旧版合并条件后同步 SUCCESS，11/17 四段行程落库。
- **第二批**：8 个统计页视觉完成第一轮；统计算法复核记录见巡检文档。A→B 在无 AOI/坐标反查数据时退为 A点/B点。当前改动尚未提交。

## [2.1.89 / build 109] — 2026-11-17 全页面数据/图表巡检修复（已覆盖安装，工作树候选）

- **来源**：2026-11-17 真机逐页巡检（OnePlus 7 Pro `6e4fa92f`）发现并修复 11 类显示/交互/图表问题，完整证据见 `runtime/ui-verify-20261116/FINDINGS-20261117.md`。
- **主要修复**：旧归档充电隐藏不可信电压/电流（2 V/0 A）；行程距离曲线改用 GPS 观测累计（7.1 km 行程约 1.3→7 km）；充电 AC/DC 筛选与卡片徽标共用平均功率兜底；统计交流/直流比例增加“已同步明细 48/73”口径说明；待机能耗强提示改为仅在全部窗口缺能量证据时出现；费用分析月度图在有估算费用时恢复；年度报告“days/drives”本地化；能耗趋势补纵轴刻度；列表/详情统一系统 12/24 小时制；仪表盘新增每日电量趋势本地化并取消 120 条截断。
- **测试**：Debug 全量 **1978 项 0 失败/0 错误**；`:app:lintRelease` 0 error / 365 warnings；R8/`assembleRelease` PASS。
- **正式包**：`com.matelink` 109 / 2.1.89，`debuggable=false`，证书 SHA-256 `9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`；APK SHA-256 `86D6A4061A405094B1E14DFECCEE27CA00F3201594A684BAAAAB3474D8D5DDA4`，包内 GIT_SHA `93465832`（**从含未提交修复的工作树构建**）。APK 归档：`E:\Claude_allow\Download\MateLink-2.1.89-build109-93465832-dirty.apk`。
- **真机**：覆盖升级成功，`firstInstallTime=2026-08-31 22:36:47` 未变，`lastUpdateTime=2026-11-17 02:02:57`；关于页显示 `v2.1.89 (93465832)`；crash buffer 0；上述修复项已逐项复验通过。
- **待办**：源码已提交（本包在提交前从工作树构建）；推送与正式升版待用户测试后决定。

## [2.1.88 / build 108] — 2026-11-16 全页面数据复核 + 旧归档充电详情提示修正（已覆盖安装并验证）

- **逐页数据核对**：仪表盘、行程列表/详情与曲线、充电列表/详情、统计概览、里程、电池健康、长途旅程、能耗、费用分析、续航、待机能耗、时间线、年度报告、导出、数据状态、软件更新、哨兵历史、设置、分时电价、关于、胎压/温度趋势均在真机逐页核对；里程、能耗、电量、费用、年度外推等口径与列表汇总、Room 明细交叉一致。
- **显示修复一**：软件更新页“已安装天数”标签此前直出 `%1$d 天` 占位符，现标签仅显示“天”，天数单独呈现（真机确认“天 / 6”）。
- **显示修复二**：旧版自建归档（负数本地历史 ID、明细无来源标识）的充电详情此前误提示“请刷新历史列表后重试”，现明确提示“此充电记录来自旧版自建归档，缺少来源标识，完整详情无法校验或离线保存；列表数据仍可正常显示”。身份校验未放宽：旧归档仍不进已验证缓存，列表数据不受影响。
- **测试**：新增 `LegacySelfHostedChargeNoticeTest` 3 项；Debug 全量 **1971 项 0 失败/0 错误**；`:app:lintRelease` 0 error；R8/`assembleRelease` PASS。
- **正式包**：`com.matelink` **108 / 2.1.88**，`debuggable=false`，V2 签名证书 SHA-256 `9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`（与历史包一致）；APK SHA-256 `37DD7DBFB9B7C3CBF5CB022656A793628EA7655F87456FEEA60182E248886339`，包内 GIT_SHA `2412f526`。APK 归档：`E:\Claude_allow\Download\MateLink-2.1.88-build108-2412f526.apk`。
- **真机安装**：同签 `adb install -r` 覆盖升级成功；`firstInstallTime=2026-08-31 22:36:47` 未变，`lastUpdateTime=2026-11-16 01:43:53`；启动后 crash buffer 0 条 FATAL/ANR。实测充电汇总 10 次 / 156.9 度 / ≈¥176.5 / ≈¥19.61 与列表逐条一致。
- **未阻断本版的显示/交互建议**：单次均价未显示费用覆盖分母（本次为 9/10）；部分页面 12/24 小时制并存；`Screen.Vehicle3d` 路由已注册但无入口；高德全屏地图预览需配置 AMap Key；数据状态页条目仍显示“尚未确认”。

## 2026-10-27 显示验收续查候选（2.1.55 / build74）

- 同一App分支保留build73，追加三项经RED复现的消费者修复：新授权失败/旧会话、首页电流显示、年度费用漏计；布局参数不改。
- 当前为软件候选；[验收记录](releases/2026-10-27-display-acceptance.md)记录实际测试、打包、SHA和安装状态，三项发现/修复版本分别进入[87条问题账本](APP-ISSUE-VERSIONS.md)。
- 代码63cdb8ee；Debug1566/0/0/0、Release1566/0/0/8，双lint0error，R8/assembleRelease和签名核验PASS。APK为MateLink-2.1.55-build74-63cdb8ee.apk，SHA256 AA2DDD82F008DCCB6F31C71634972DD8FD291A2177AFB4D8FF5BA59918F2ADC6；设备尚未安装验收。
- 原始目录配置仍72，实际手机版本尚不能读取；本轮无ADB设备，未安装或清理用户数据，不因本地回归通过声称真实逐页验收完成。

## 2026-10-27 App数据链路修复候选（2.1.54 / build73）

- 发现基准为2.1.53/build72已审候选；本轮在E盘 `matelink-release-data-audit-20261026`、App分支 `codex/app-release-data-fixes-20261027` 实施，原候选基线提交 `e53a2d01`。
- [逐问题版本台账](APP-ISSUE-VERSIONS.md)保存发现日期、版本、改法、验证与提交；[批次记录](releases/2026-10-27-app-data-fixes.md)及F1–F7分组记录对应来源→资格/计算→持久化→展示链。
- 本轮只修数据、元信息、计算、并发及现有位置formatter/路由绑定；保持原页面参数排列、尺寸、字体、间距与既有颜色。
- **代码提交**：`161d77cea4d110aac93b52956644ab3ea5603733`。Debug/Release各1556方法0fail/0error（Release8既有skip），双lint0error，AndroidTest源码编译、两个Go模块tests/vet和R8通过。
- **签名候选**：`com.matelink` 73/2.1.54，debuggable=false，证书与既有包一致；APK SHA256 `425072A9A5C46DD9F54901FEE07170A6FEAE3C1DA2C6BFE3A48214B68A726482`。包内GIT_SHA=161d77ce，文件位于批准下载目录。安装、provider和生产验收分别保留待验。
- 软件修复不等于真实历史补采；未曾采集的旧样本、未提供的来源实例/事件脉冲保持明确边界。本轮未安装手机或部署生产，Hub当前拒绝连接未成功上报。


本文件记录 MateLink Android App 每次**正式包（release）**的版本更新与真机安装情况，倒序排列（最新在最上）。

适用范围：Release 正式包为 `com.matelink`；Debug 固定为隔离包 `com.matelink.test.mock`，不得覆盖车主包。
完整构建、部署与真机验证 SOP 请参见：[SOP-ANDROID-DEVICE-DEPLOYMENT-AND-VERIFICATION.md(release-quality/SOP-ANDROID-DEVICE-DEPLOYMENT-AND-VERIFICATION.md)

> 安全约定：本文件只记录构建配置的文件路径与公网地址，**不记录 keystore 口令**。
> 签名配置文件位于仓库外（`E:\Claude_allow\matelink-release.properties`），不提交远端。

---

## [2.1.52 / build 71] — 2026-10-25 已同签覆盖安装，页面回归修复中

- **实际安装源码**：PR #27 Draft/open 的 `af6fe089a57c95b3839dc304bb0385c3ba4e8e29`，tree `d400bc920bd6e05205022d40459da2ac4f965dea`，parent `24a1141ad1bcf7eb7a01ab8e7baafd0995ac68d2`。较早的 `84e3ca91` 是整合候选，不能当最终包。PR 此后若前进，新 SHA 是另一候选，不自动成为手机安装版。
- **本版变化**：整合 build70 页面与数据底座；行程 SOC 回到右侧并移除历史“功率积分100%”徽标；同一驻车内逐次显示充电及各自 kWh/h，停车段仅在完整 SOC 边界证据下显示百分点/小时；电池、效率、费用走作用域 Room-first 消费。跨来源驻车/充电、Battery UID 缓存与 Cost 临时错误保留已补真实回归。
- **同 SHA 验证**：[完整 Actions 40174354397](https://github.com/Jovifei/tesla-master-mimo/actions/runs/40174354397) 18/18 SUCCESS；Android Debug/Release 各 1271 项 0 fail/error，Release 8 项既有 skip，双 lint 0 error，R8 PASS；Go、隔离 PG18、race、TLS、Web、Mini 和源码审计成功。[API35 40174354371](https://github.com/Jovifei/tesla-master-mimo/actions/runs/40174354371) XML 80/0/0/2，三张 Compose 合成画面非黑且哈希不同。它们证明固定源码和隔离界面，不代替自然 Fleet。
- **正式候选包与真机**：`com.matelink` 71/2.1.52，Release 不可调试；APK SHA256 `BB8DFBB30C8A2DE1721C74DA9942B81AF22D640CBF84355BFAC5AC378E8C6FF1`，与已安装包证书一致。保留数据的 `adb install -r` 成功，`firstInstallTime` 未变；启动进程存活，限定日志未见 FATAL/ANR/Room 迁移错误。未清除或导入用户历史。
- **安装后发现的回归仍 OPEN/WIP**：同页充电卡有估算费用而摘要费用/单次均价缺失；日期按钮与摘要/图表/列表不同窗且滚动暗中扩窗；驻车六项遥测整块隐藏、起止历史 SOC 证据缺失与图标状态色回退；行程类型筛选入口丢失。根因、当前未提交修复及防复发测试见 [Bug 根因索引](BUG-REGISTER.md)。这些本轮修复未获新固定 SHA/同 SHA CI/签名装机，**不能用上述 af6 绿色结果为其背书**。
- **发布门**：PR 仍 Draft，main 与生产未因本包变更。真实多账号 Fleet 首事件、自然行程/充电、逐字段历史采样及页面交互仍独立验收。逐提交记录见 [变更索引](CHANGE-INDEX.md)。

---

## [2.1.51 / build 70] — 2026-10-25 驻车详情重设计 + 冷启动缓存键修复（已覆盖安装并验证）

- 起因：用户反馈驻车详情"暂无数据"过多、位置数据摆放不佳；并要求当轮解决已定位的缓存键问题。
- **驻车详情页重设计**（`f810ace5` + `a0..` 徽标一致性）：分层卡片——①概要 Hero（大字地址 + 等宽时间轴 `2026-10-23 20:59 → 2026-10-23 23:18` + 共驻车时长 + 语义徽标 `⚡ 充电 +69%`/`耗电 −X%`，方向由显示的起止电量推导，与明细卡永不矛盾）；②起止电量三格（本地必有数据，零"暂无数据"）；③关联充电卡；④能耗与环境遥测卡——云端明细存在时 6 格（能耗/功率/车内温/车外温/覆盖率/采样数），不存在时**单行"云端明细待同步"提示替代满屏"暂无数据"**。全部文案双语入库。
- **冷启动缓存键修复**（同 `f810ace5`）：`ChargesViewModel`/`DrivesViewModel` 缓存读空时经 `VehicleContextRepository.cachedContextForRemote` 离线解析 `localHistoryCarId` 换键重读，彻底消除弱网冷启动"0 记录+加载中"——此前 Room 行按负数作用域键持久化而冷启动只有远端 carId。源码审计合同新增 `cachedContextForRemote(` 断言。
- 实测：杀进程冷启动 2 秒内充电页直接渲染缓存（67 次 / 920 kWh / 记录行，无加载态）；驻车详情新布局本地模式与云端增强模式（来源 teslamate_archive）均验证通过。
- 测试：Debug/Release 各 1029 项全绿（8 skip）；lintRelease 通过。Release APK SHA-256：`4FFCE545D533B2BDA46693B44CCFFEA8F5C550DB72C827DB91306F4EF36DC987`。
- 遗留待办：数据分析三页（电池健康/能耗/费用）Room 缓存化；PR #22 变基升版合并。

---

## [2.1.50 / build 69] — 2026-10-25 驻车详情本地优先修复（已覆盖安装并验证）

- 起因：用户实测 build 68 反馈驻车详情页只有"暂无数据"。根因有二：① `ParkedDetailViewModel` 纯网络加载，无超时无缓存，弱网即空白；② 本地 Room 行按 `localHistoryCarId`（负数作用域键）持久化，冷启动无法离线解析该映射，按远端 carId 读取必空。
- **驻车详情本地优先**（`f84af473`）：进入页面立即用相邻两条本地行程构造详情（位置/时间窗/时长/起止电量），云端 20s 超时增强（温度/功率/关联充电），失败保留本地数据 + "云端历史暂未同步成功"横幅 + 重试按钮；数据来源行如实标注"本机缓存（由相邻行程推得）"。
- **drive-id 无歧义兜底读取**（`ea92d999`）：`DriveSummaryDao.getByDriveId` 跨 car 键按行程 ID 查询，恰一行才采用（多行歧义保持未知），解决冷启动映射缺失。
- **充电段不冒充能耗**（`4d582938`）：驻车电量增量为正（充电）时，0.60kWh/% 平率估算不适用，"估算能耗"显示暂无数据；仅放电段估算。
- 测试：Debug 1029 / Release 1029（8 skip）全绿；lintRelease 通过（Gradle 2GB 堆 GC 抖动，按既有经验以 4GB 堆构建）。新增 `ParkedDetailLocalFallbackTest` 3 项合同。
- Release APK SHA-256：`CFD0DA0DC25041E048DE6F91214A0B107DF998AB9415860A2452D498F6152AA0`；证书谱系不变。
- 真机：同签 `install -r` 成功，`firstInstallTime=2026-08-31 22:36:47` 保持；实测驻车详情（景兴路 20:59~23:18）本地秒渲染：+69% 电量变化、138 分钟、31%→100%，充电段估算能耗如实"暂无数据"，进程存活 0 崩溃。
- 已定位待下轮：充电/行程列表冷启动缓存读键与 `localHistoryCarId` 不一致（`VehicleContextStore` 离线映射未接入 VM），弱网冷启动列表仍需网络；数据分析三页缓存化。

---

## [2.1.49 / build 68] — 2026-10-25 实机 UI 审计修复（已覆盖安装并验证）

- 来源：2026-10-25 对 build 67 的实机 UI 审计（OnePlus 7 Pro `6e4fa92f`；证据存档于主仓库 `.zcode/ui-audit-20261024/`，含修改前后截图与控件树）。分支 `codex/ui-audit-fixes-20261025`，基于 main@`427de74e`；提交：`6eaeff87`、`41a5c1a3`、`34320288`、`bcfbe74e`。
- **充电/行程页云端历史加载新增 20 秒超时**（`HISTORY_LOAD_TIMEOUT_MS`）：超时后保留已渲染的本地缓存并提示"云端同步超时，已显示本地缓存数据；下拉刷新可重试"；无缓存时才升级为页面错误，替代此前弱网下无限"加载中…"。实装于 `ChargesViewModel`/`DrivesViewModel`，放置决策共享 `domain/history/historyTimeoutMessage`（合同测试 3 项）。
- **行程卡片能耗单位恢复完整 `kWh/100km`**（与详情页一致）；`TelemetryMetricStrip` 紧凑单元格对 ≥8 字符长单位改为数值下方独立一行（`compactUnitLine` 合同测试 3 项），不再为四列宽度截断单位。
- **驻车卡片 SOC 区间统一**为 `UnitFormatter.formatSocRange` 的 `33% → 32%` 格式，与行程卡片一致。
- **能耗来源徽标显示条件与指标数值对齐**：能量显示"暂无数据"的卡片不再出现"功率积分"等来源徽标。
- **仪表盘车内/车外温度无数据占位**由 `--` 改为"等待车辆"，与数据就绪页词汇一致。
- 测试：Debug 1026 项 0 失败；Release 1026 项 0 失败、8 项预期跳过；`lintDebug`/`lintRelease` 通过。同步更新源码审计合同（`HistoryRefreshIntegrationContractTest` 要求超时包装）与发布身份合同（build 68）。
- Release APK SHA-256：`B78578577F78FE389016EC7482D957EF90DF448B38069C609EEBE1BE96FF42FE`；签名证书 SHA-256 `9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`（与历史包一致）。
- 真机：同签名 `adb install -r` 成功；`versionCode=68`/`2.1.49`，`firstInstallTime=2026-08-31 22:36:47` 保持，`lastUpdateTime=2026-10-25 00:57:47`；启动后全程导航进程存活，crash buffer 0 条 FATAL/ANR。
- 实机复验：充电页由"0 次充电+永久加载"恢复为完整真实数据（充电次数 67、充电量 920 kWh，与统计概览口径一致）；行程卡片完整单位/驻车 SOC 格式/徽标对齐逐项通过；温度卡在有数据时显示 25.1°C/25.0°C。
- 遗留（非本版本范围）：弱网 TLS 间歇失败根因（2026-10-09 起）、双账号实机切换验收、64 条充电 967→920 历史差异对账、数据分析三页（电池健康/能耗/费用）缓存化改造。

---

## [2.1.13 – 2.1.48 / build 32–67] — 2026-09-10 至 2026-10-24 台账补录说明

- 此区间的正式包未在本台账逐条记录，属台账欠账；各版本内容以 git 提交历史（main 分支）及对应阶段文档为准（如 build 67 见 `TECH-2026-10-23-history-loading-cache-pagination.md`）。
- 期间里程碑：OOM 历史读取修复（build 38–41）、登录浏览器选择（build 29 后续）、每趟行程能耗与单位归一化（build 46–50 区间）、紧凑四列历史摘要（build 60–62）、即时缓存渲染与无限滚动（build 67 / v2.1.48，2026-10-24 合并 main 并真机安装）。
- 自 build 68 起恢复逐版本记录；后续每次 release 构建必须在安装验证后回填本台账。

---

## [2.1.12 / build 31] — 2026-09-10 本地验证候选（已覆盖安装）

- 版本从 2.1.11/build30 递增；源码提交为 `fe27f2b`（功能基线 `4e97691949ecef9a39fd135be3efb988907b0f61`），本阶段补充了不完整旧别名行的统计过滤契约与最小实现。
- Debug/Release JVM 各 543 项：0 failures / 0 errors，Release 8 项预期跳过；`lintDebug`、`lintRelease`、`assembleDebug`、`assembleDebugAndroidTest` 和签名 `assembleRelease` 均通过。
- Release APK：`android/app/build/outputs/apk/release/app-release.apk`；由 `fe27f2b` 重建，SHA-256：`98E763A01D699E43FFFC9A9C4A824B6A7FFA2DC099AA30BE2FDF5544639DA810`。
- 正式证书 SHA-256：`9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`；包名保持 `com.matelink`。
- OnePlus 7 Pro（`6e4fa92f`）已执行同签名 `adb install -r` 覆盖安装；`versionCode=31`、`versionName=2.1.12`，`firstInstallTime=2026-08-31 22:36:47` 保持；主 Activity 启动成功，进程存活，最近 800 行日志无 MateLink FATAL/ANR。
- 页面级 Tesla OAuth、浏览器选择、虚拟钥匙、真实 Telemetry 和历史数据仍待 Jovi 解锁手机后验收。
- 本轮没有部署 ECS；临时 PostgreSQL 因本机 Docker Linux 引擎不可用未执行，未连接生产数据库。

---

## [2.1.11 / build 30] — 2026-09-09 阶段版本（已构建，未重复安装）

- 基于已推送的 `6ced331` Telemetry 数据真实性修复和浏览器选择优化；仅更新 Android 版本号与本次更新说明。
- 保留 Tesla 登录浏览器选择；等待车辆、Telemetry 和历史数据继续按真实证据显示，不生成合成数据。
- 已构建并核验 `com.matelink` Release，非 debuggable；证书 SHA-256：`9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`。
- APK SHA-256：`C86C1FD079491B85143BF0604A6C4C88F9C8AB95A932CE2BD68DCF18817B54BA`。
- Debug/Release 各 526 项测试：0 failures / 0 errors，Release 8 项预期跳过；`lintDebug`、`lintRelease` 通过。
- 本阶段版本已提交并推送；没有再次安装设备，设备当前仍是 2.1.10/build29。

---

## [2.1.10 / build 29] — 2026-09-09 浏览器选择与 Telemetry 数据真实性修复

- Tesla 登录改为 App 内选择浏览器；显式启动所选应用，避免默认浏览器闪退阻断登录。取消后可重新发起登录；虚拟钥匙深链与 OAuth 回调保持原流程。
- 已构建并核验 `com.matelink` Release，非 debuggable；证书 SHA-256 与既有正式包一致：`9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`。
- APK SHA-256：`45F920C6ADB6B9B971553319C6EEAFCF5453AE80CDBE49BEF0D8FC5868EC0EE5`。
- 源码提交：`6ced331`，已推送至 `fix/20260907-onboarding-source-integrity`。
- ECS 已部署同一提交：`/healthz`、`/readyz` 内外均返回 `build_sha=6ced331`、`mode=fleet`、`persistence=postgres`、`status=ok`；现有远端源码备份位于 `/home/jourvolt/jourvolt-staging/.rollback-6ced331-source-sync`，`.env` 未读取或修改。
- OnePlus 7 Pro（`6e4fa92f`）已用同签名 `adb install -r` 覆盖安装；version `2.1.10` / build `29`，`firstInstallTime=2026-08-31 22:36:47` 保持不变，未卸载或清理用户数据。
- Debug/Release 各 526 项测试：0 failures / 0 errors，Release 8 项预期跳过。首次组合构建因 2 GB Gradle 堆不足停止；独立打包使用临时 4 GB 堆参数成功，未修改项目 JVM 配置。
- 最终 `lintDebug`、`lintRelease` 均通过。
- 实机只读查询确认 MATCH_ALL 能返回 Chrome，普通查询仅返回默认 Heytap；安装后 `com.matelink` 进程启动存活，crash buffer 中未发现 `com.matelink` FATAL。手机仍锁屏，未猜测图案，浏览器列表点击和真实 OAuth 回流留待 Jovi 解锁后验收。
- 数据链路源码审查见 `docs/audits/2026-09-09-browser-and-telemetry-readiness.md`；不能承诺授权后自动补齐所有曲线及历史指标。

---

## [2.1.9 / build 28] — 2026-09-08 授权恢复重试与行程列表地址修复

### 已验证

- Go `go test ./... -count=1`、`go vet ./...`、`go mod verify` 通过。
- Android Debug/Release JVM 测试通过；Release lint 0 errors（现有 warnings 保留）。
- Release APK 已用现有 `com.matelink` 同签名证书构建并通过 `apksigner` V2 校验；SHA-256 为 `D080F637A259D661B64C55DCBEA8CD68432E75EB72ABBEF9D94D54CFB37703BD`。
- OnePlus 7 Pro（`6e4fa92f`）已执行 `adb install -r` 覆盖安装；version `2.1.9` / build `28`，`firstInstallTime=2026-08-31 22:36:47` 保持不变，证明未卸载、未清理本地数据。

### 本次修复

- `/v1/auth/exchange` 成功后，对历史 `telemetry_error`、`pairing_required`、`permission_required` 车辆触发一次去重 configure 重试。
- 行程列表从真实首尾坐标执行高德逆地理编码；已有地址和缺失坐标保持真实语义。

### 真实数据边界

- 当前 ECS 尚未收到新的 Tesla MQTT 首事件；GPS、今日行程、路线/速度/功率曲线和真实充电仍需 Jovi 完成 Tesla 官方授权、虚拟钥匙配对并产生车辆事件后验收。
- 安装后进程可启动且无 FATAL/ANR 日志；设备随后回到图案锁屏，未猜测用户图案，页面级点击验收留给 Jovi 解锁后执行。

---

## [2.1.6 / build 25] — 2026-09-06 历史归档与位置真实性修复

### 已验证

- 本地历史不再在 Android 或服务端按“两天”截断；上传按服务端 200 条/类型上限分批，保留完整有效归档。
- 服务端已部署本提交；现有 68 条 `local_import` 记录保留并转换为 `incomplete/local_import_unverified`，不会再被接口全量隐藏，也不会进入统计或曲线。
- 空的实时 GPS 快照不会覆盖有效缓存；缓存位置只能显示为 `database_latest` 与原缓存时间，不能冒充实时 Telemetry。
- 旧可信自托管 API 的缺失质量字段在远端边界归类为 `legacy_remote_api`；本地未验证导入仍严格排除分析。
- Android Debug/Release JVM 各 515 项：0 failures / 0 errors；Go test/vet 通过；独立代码复审无 Critical/Important。
- 已同签名 `adb install -r` 覆盖 OnePlus 7 Pro：`com.matelink` 2.1.6/build25，首次安装时间仍为 `2026-08-31 22:36:47`；冷启动仍进入既有车辆，不出现登录页或 FATAL。
- APK：`app-release.apk`，SHA-256 `2619874B8A708BCD6DA8CC3B2D43E28625104F0FCEB8CE7EB0CF4FD76F2763ED`。

### 真实数据边界

- 当前生产 Telemetry 仍为 `awaiting_first_event`，数据库仍为 pairing/latest/route points 均为 0；因此位置页正确显示“等待车端 GPS”。
- 旧导入摘要没有路线点、地址或速度/功率采样，不能诚实补出“未知→未知”、地图路线或曲线；电池健康接口仍由服务端明确标记 unsupported。
- 需要 Jovi 在 Tesla 官方流程完成虚拟钥匙/Telemetry 配置，并产生一段真实驾驶和一次真实充电后，才能验收实时 GPS、起终点、路线/速度/功率曲线、充电曲线与健康趋势。

---

## [2.1.5 / build 24] — 2026-09-06 数据真实性与 Fleet Telemetry 修复

### 证据状态

- 源码/本地门禁：Go test/vet、Android Debug/Release JVM、Debug/Release lint、Debug/AndroidTest APK 编译均通过。
- 包边界：Debug APK 静态核对为 `com.matelink.test.mock` / `MateLink Test`；Release 仍为唯一的 `com.matelink` 候选。
- 已安装：同签名 `adb install -r` 覆盖 OnePlus 7 Pro 的 `com.matelink`；安装后为 2.1.5/build24、非 DEBUGGABLE，首次安装时间保持为 2026-08-31 22:36:47，启动和进程级 FATAL 检查通过。
- APK：`app-release.apk`，SHA-256 `A041DCC1D1A5367017D15DF2A8E3B1F7A107E3D37A40F732ABA396E3447E5494`；签名摘要与设备安装前包相同。
- 未执行：Tesla 虚拟钥匙/Telemetry 配置及真实行程、充电验证；未删除生产数据。

### 服务端同步状态

- ECS API 已按 `b15960d` 部署；`/healthz` 和 `/readyz` 均返回 200，后者显示 `awaiting_first_event`。
- 旧云端记录未删除：66 条 drives、2 条 charges 已标记为 `local_import/quarantined`；真实 Telemetry 首事件仍未到达。

### 本次范围

- Fleet Telemetry 官方枚举和单位归一化、逐字段回退、行程/充电质量状态与隔离。
- Android 移除合成历史/曲线/假状态，地图只显示有效真实坐标并转换一次到高德坐标系。
- 行程/充电/统计在未观测或 quarantined 数据下保持不可用，不再把未知归类为 AC/DC 或填充零值。
- Debug 恢复隔离标识，Release 禁用备份；版本递增到 2.1.5/build 24。

---

## [2.1.4 / build 23] — 2026-09-06 物理真机7大核心体验修复与纯净交付

### 一、版本标识

| 项目 | 值 |
|---|---|
| versionName | `2.1.4` |
| versionCode | `23` |
| 包名 | `com.matelink`（正式包，杜绝分身） |
| minSdk / targetSdk | 26 / 35 |
| 构建类型 | debug / release（统一正式包名 `com.matelink`） |
| 发布日期 | 2026-09-06 14:30 |

### 二、本次核心修复（7大真机问题）

1. **高德地图底图渲染**：挂接直接生命周期与工厂流转，解除逆地理编码循环死锁，矢量瓦片与 POI 渲染恢复。
2. **真实位置与零伪造**：全链路清除硬编码 `30.27°N, 120.15°E` 及假西湖区/拱墅区；无 GPS 时诚实显示“等待车端 GPS”；一旦上报即时逆地理编码。
3. **行程曲线拒绝造假**：移除详情页正弦波生成器，对仅有聚合统计的行程诚实展示说明文案。
4. **充电站点与时序修正**：纠正 18:43 第三方快充（25% -> 96%）与 19:30 超充补满（96% -> 100%）的时序与站点归属。
5. **车主 3D 渲染图内置**：内置纯黑 Model Y + 19 寸 Gemini 轮毂 3D 渲染图，精准匹配车主车型。
6. **消除手机双软件**：默认取消 `.test.mock` 后缀，保证手机上只安装唯一正式应用 `com.matelink`。
7. **全量 35 趟行程展示**：时间筛选默认置为全部时间，完整呈现所有历史行程。

### 三、真机安装记录

| 项目 | 值 |
|---|---|
| 设备 | OnePlus 7 Pro（GM1910），`6e4fa92f`，Android 11 |
| 安装方式 | `adb install -r`（覆盖安装，保留用户原有配置与 Room 数据） |
| 安装后版本 | versionCode **23** / **2.1.4** ✅ |
| 卸载双开/测试包 | `com.matelink.test.mock` 已完全卸载 ✅ |
| 桌面状态 | 唯一 `MateLink` 图标 ✅ |
| 单元测试 | 490/490 passed (`BUILD SUCCESSFUL`) ✅ |

---

## [1.4.5 / build 17] — 2026-09-03 版本号递增（同代码，供设备资格验证）

### 一、版本标识

| 项目 | 值 |
|---|---|
| versionName | `1.4.5` |
| versionCode | `17` |
| 包名 | `com.matelink`（正式包） |
| minSdk / targetSdk | 26 / 35 |
| 构建类型 | release（`SIGNED_RELEASE`，R8 + 资源压缩） |
| 发布日期 | 2026-09-03 12:09 |

**本次变更性质**：**仅递增版本号，产品代码零改动**。
目的是让设备上的包可与上一版（同为 1.4.4/16 的旧包）明确区分，便于真机资格验证时判断装的是哪个包。

### 二、源码来源

| 项目 | 值 |
|---|---|
| 分支 | `fix/20260902-android-state-and-ux-reliability` |
| 本次提交 | `1fca1c428d02ac49555cb3da864c19ab3024d867` — *chore(android): bump version to 1.4.5 (build 17)* |
| 父提交 | `e51cbad`（含 `b6509ad` 主修复 + `e51cbad` mock 徽标收尾） |
| 基准 main | `11ba77d`（未被直接修改） |
| 改动量 | 1 文件，+2 / -2（仅 `android/app/build.gradle.kts` 的 versionCode / versionName） |
| 远端状态 | 已推送；`git ls-remote` 验证分支 HEAD = `1fca1c428d02...` ✅ |
| 关联 PR | **PR #4**（Draft，待真机验证后收口） |

### 三、构建产物

| 项目 | 值 |
|---|---|
| 文件大小 | 62,331,759 字节（≈59.4 MB） |
| SHA-256 | `6389840C81542D3DC2A90F21ACD54B6727DF042E58007C246F3CDD1DC3837C23` |
| 包名校验 | `com.matelink` ✅ |
| 签名校验 | `apksigner_verified` ✅ |
| 构建耗时 | 3m28s，`BUILD SUCCESSFUL`（60 tasks，29 up-to-date） |
| 归档位置 | `E:\Claude_allow\matelink-apk-archive\matelink-1.4.5-build17-20260903-release.apk` |

构建命令与签名配置同上一版（见 [1.4.4 条目](#三构建产物)），三个公网地址为 release guard 强制值。

### 四、真机安装记录

| 项目 | 值 |
|---|---|
| 设备 | OnePlus 7 Pro（GM1910），`6e4fa92f` |
| 安装方式 | `adb install -r`（**升级安装**，17 > 16） |
| 安装时间 | 2026-09-03 12:09:11 |
| 安装前 | versionCode 16 / 1.4.4 |
| 安装后 | versionCode **17** / **1.4.5** ✅ |
| firstInstallTime | 2026-08-31 22:36:47（**未变 → 覆盖升级，Room 历史数据保留**） |
| 手机端 APK SHA-256 | `6389840c...3837c23`，与本地产物**完全一致** ✅ |
| 启动验证 | `com.matelink/.MainActivity` 前台运行，进程存活（PID 911） |
| 崩溃检查 | 0 条 FATAL / has died 日志 ✅ |

### 五、与上一版（1.4.4/build 16）的关系

| 项 | 1.4.4 / build 16 | 1.4.5 / build 17 |
|---|---|---|
| 产品代码 | 修复版（PR #4 内容） | **完全相同** |
| 版本号 | 1.4.4 / 16 | 1.4.5 / 17 |
| SHA-256 | `6ea2b47a...` | `6389840c...` |
| 大小 | 62,331,755 B | 62,331,759 B |

两版功能等价，17 仅用于版本可辨识性。**验证功能请以 17 为准**。

### 六、遗留事项

1. **真机行为资格验证未开始**（PR #4 保持 Draft 的原因）：
   - Cloud ↔ Self-hosted 强停重启
   - token 过期 + 断网
   - 重新授权：取消 / 成功
   - 切车同时轮询（竞态）
   - MQTT live → recent → fallback
   - 真实 AC / DC 充电
   - 第二次启动配置 / 语言保持
2. PR #4 待上述矩阵通过后收口、进入合并审查；之后再基于新 `main` 推进行驶完成报告 PR2。

---

## [1.4.4 / build 16] — 2026-09-03 状态恢复与实时数据 UX 可靠性修复

### 一、版本标识

| 项目 | 值 |
|---|---|
| versionName | `1.4.4` |
| versionCode | `16` |
| 包名 | `com.matelink`（正式包） |
| minSdk / targetSdk | 26 / 35 |
| 构建类型 | release（`SIGNED_RELEASE`，R8 混淆 + 资源压缩） |
| 发布日期 | 2026-09-03 |

> 注：本次为**代码修复发布**，未递增 versionName / versionCode（仍为 1.4.4 / 16）。
> 因此单看版本号无法区分新旧包，须以**构建时间 + APK SHA-256** 作为判定依据。

### 二、源码来源

| 项目 | 值 |
|---|---|
| 远端仓库 | `https://github.com/Jovifei/tesla-master-mimo.git` |
| 分支 | `fix/20260902-android-state-and-ux-reliability` |
| 基准 main | `11ba77d3206828ce4dc745d03dbde7f21eeb0201`（未被直接修改） |
| 提交 1（主修复） | `b6509add52eb854a1a92eceaf1cb69d474106e65` — *fix(android): harden state restoration and live data UX* |
| 提交 2（收尾） | `e51cbad` — *fix(android): restore mock snapshot badge and tidy charge view model* |
| 关联 PR | **PR #4**（Draft，未合并；`mergeable=true`） |
| PR 改动量 | 20 个文件，+420 / -369（仅 Android 生产代码、对应测试、一份审核文档） |

### 三、构建产物

| 项目 | 值 |
|---|---|
| 产物路径 | `app/build/outputs/apk/release/app-release.apk` |
| 文件大小 | 62,331,755 字节（≈59.4 MB） |
| SHA-256 | `6EA2B47A310B49413E4238B37C10F1FE9BD6B6123B6232E977DADD065952628E` |
| 包名校验 | `com.matelink` ✅（脚本断言，不含 `com.matelink.test.mock`） |
| 签名校验 | `apksigner_verified` ✅ |
| 构建耗时 | 4m24s，`BUILD SUCCESSFUL` |
| 构建环境 | 本机 `/tmp` 干净 clone（绕开 workspace 坏仓库） |

**构建命令（可复现）**：

```powershell
cd <clone>/android
.\build-pilot-apk.ps1 `
  -ApiBaseUrl          "https://api.teslalink.joviluma.com/" `
  -AuthHost            "auth.teslalink.joviluma.com" `
  -PublicInfoBaseUrl   "https://auth.teslalink.joviluma.com" `
  -SigningPropertiesPath "E:\Claude_allow\matelink-release.properties"
```

三个公网地址均为 `build.gradle.kts` 的 release guard **强制校验值**，缺一即 fail-fast（guard 拒绝 localhost / 内网 / HTTP / example 域名）。

**签名配置**：`E:\Claude_allow\matelink-release.properties`（仓库外）
- `storeFile=C:/Users/Admin/.android/debug.keystore`
- 证书指纹 SHA-256 `9AB144E8...81FC774`，DN `CN=Android Debug`
- 与手机既有 `com.matelink` 签名一致 → **可覆盖安装，Room 历史数据不丢**
- 取舍：这是 Android 默认调试证书而非正式发布 keystore；换正式 keystore 需卸载重装（会丢本地历史），列为上架市场前的长期决策项。

### 四、本次修复内容

**状态恢复与连接模式**
- Self-hosted 不再因残留 Cloud Session 而被第二次启动自动切回 Cloud
- 临时断网 / 5xx 不再直接清空 Session（改为保留 + 标记待刷新）
- 云 Session 刷新路径加固

**授权流程**
- 重新授权改为**非破坏式**：用户取消授权不再先注销旧 Session
- 授权成功路径与旧 Session 平滑衔接

**实时数据 UX**
- Dashboard 数据新鲜度：旧瞬时数据不再被继续标记为「实时」
- Current Charge 页实时参数修正
- 切车竞态：切车与轮询并发时的数据归属修正
- 单位显示修正

**其他**
- Settings 两字段化
- 第二次启动的语言恢复

### 五、验证状态

| 阶段 | 结果 |
|---|---|
| 远端 CI（Debug JVM 单测） | 477 / 477 PASS（failures 0 / errors 0 / skipped 0） |
| 远端 CI（assembleDebug） | PASS |
| 远端 CI（assembleDebugAndroidTest） | PASS |
| 远端 CI（lintDebug） | PASS（Lint errors 0，warnings 238） |
| 远端 CI（git diff --check） | PASS |
| 本地 release 构建 | BUILD SUCCESSFUL（含 `:app:lintRelease`） |
| 本地 apksigner 校验 | PASS |
| **真机安装启动** | **PASS**（见下节） |

### 六、真机安装记录

| 项目 | 值 |
|---|---|
| 设备 | OnePlus 7 Pro（GM1910），序列号 `6e4fa92f`，USB 调试已授权 |
| 安装方式 | `adb install -r`（覆盖升级，非卸载重装） |
| 安装时间 | 2026-09-03 11:37:04 |
| firstInstallTime | 2026-08-31 22:36:47（**未变 → 覆盖升级，本地数据保留**） |
| 安装后 base.apk | 62,331,755 字节，与构建产物一致 ✅ |
| 启动验证 | `com.matelink/.MainActivity` 前台运行，进程存活（PID 28322） |
| 崩溃检查 | 无 FATAL / AndroidRuntime 崩溃日志 ✅ |

**安装命令**：

```bash
adb install -r "<产物路径>/app-release.apk"
```

### 七、遗留事项

1. **真机行为资格验证未完成**（PR #4 尚未收口的原因），需在实机跑完并回填结果：
   - Cloud ↔ Self-hosted 强停重启
   - token 过期 + 断网
   - 重新授权：取消 / 成功
   - 切车同时轮询（竞态）
   - MQTT live → recent → fallback
   - 真实 AC / DC 充电
   - 第二次启动配置 / 语言保持

2. **PR #4 仍为 Draft**，待上述真机矩阵通过后收口并进入合并审查；之后再基于新 `main` 继续行驶完成报告 PR2。

3. **版本号未递增**：下次发布建议递增 versionCode（如 17），避免同版本号覆盖时难以从包信息判断新旧。

---

## 附录：如何判定手机上跑的是哪个版本

由于 1.4.4/build 16 本次未递增版本号，判定安装是否为本次修复版请用：

```bash
# 1. 看更新时间（本次应为 2026-09-03）
adb shell dumpsys package com.matelink | grep -E "lastUpdateTime|versionName|versionCode"

# 2. 看 base.apk 大小（本次应为 62331755 字节）
adb shell stat -c '%y %s' $(adb shell pm path com.matelink | tr -d '\r' | cut -d: -f2)

# 3. 精确比对 SHA-256（拉取后与 6EA2B47A... 比对）
adb pull $(adb shell pm path com.matelink | tr -d '\r' | cut -d: -f2) phone.apk
sha256sum phone.apk
```

---

*本台账随每次正式包发布追加更新。*
