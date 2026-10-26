# MateLink Bug 根因索引

## 2026-10-27 App数据链路修复候选（2.1.54 / build73）

- 发现基准为2.1.53/build72已审候选；本轮在E盘 `matelink-release-data-audit-20261026`、App分支 `codex/app-release-data-fixes-20261027` 实施，原候选基线提交 `e53a2d01`。
- [逐问题版本台账](APP-ISSUE-VERSIONS.md)保存发现日期、版本、改法、验证与提交；[批次记录](releases/2026-10-27-app-data-fixes.md)及F1–F7分组记录对应来源→资格/计算→持久化→展示链。
- 本轮只修数据、元信息、计算、并发及现有位置formatter/路由绑定；保持原页面参数排列、尺寸、字体、间距与既有颜色。
- 状态：分组及真实Room/VM/Notification负例已逐批RED→GREEN，full-debug-22 1555方法/17失败，正在修旧契约fixture及缓存头真实回归后复跑。候选包、最终本地提交与实际APK hash待最终验收回填。
- 软件修复不等于真实历史补采；未曾采集的旧样本、未提供的来源实例/事件脉冲保持明确边界。本轮未安装手机或部署生产，Hub当前拒绝连接未成功上报。


本索引记录**已复现的症状、根因与证据等级**，供相同问题再次出现时按 ID 查找。它不代替 [Android 发布台账](ANDROID-RELEASE-LOG.md) 或 Git 提交历史。状态为 `WIP` 的修复尚未取得固定 SHA/同 SHA CI，不能写作已解决。记录日期：2026-10-25。

## 遇到“暂无数据”先查什么

用同一账号、稳定车辆 UID、API 来源及 `source_instance_id`、选中本地日期窗和记录 ID 核对：① 原始 receipt 是否有该字段及**自己的观测时间**；② 资格合同是否接受来源、单位、币种、质量和时间；③ Room 是否保存且冷启动可重读；④ 列表、摘要、图表、详情是否消费同一投影；⑤ UI 是诚实未知，还是把已有值隐藏。真实零、历史缺测、网络失败、跨源隔离分别记录，不把当前车辆快照填到过去。对外截图和 Bug 文档不写车主位置、凭证或原始日志。

### A. 源未采到／历史时点缺证据

| ID / 状态 | 复现与根因 | 处理、防回归与证据 |
| --- | --- | --- |
| `ML-DATA-20261025-01` **OPEN-EVIDENCE** | build71 某段历史驻车详情起止电量未知，首页却有当前 SOC。`ParkedDetailViewModel.kt` 只能用相邻行程末/初 SOC；该两条历史行程也缺 SOC。`VehicleStatusStore.kt` 仅保存当前快照；归档驻车响应不含合格温度、能量、功率样本。当前 SOC 的观测时间不属于旧窗口。 | **不能**倒填或恢复固定 `0.60 kWh/百分点` 伪能耗。WIP UI 用 `—` 与时间证据说明替代三次“暂无数据”；未来应按账号/车/来源/观测时间有界保存真实状态点，再以边界证据计算。回归：`ParkedTelemetryPresentationTest` 与 API35 驻车 UI（当前 WIP 未验）；真实历史仍需自然采样。关键词：`驻车 当前电量 历史端点 温度 采样 证据时间`。 |
| `ML-DATA-20261025-02` **OPEN-TRIAGE** | 用户持续看到其他页面“暂无数据”；目前未建立逐字段原始 receipt→资格→Room→UI 的完整记录清单，不能一概判为车端未上报。 | 每发现新字段按本页开头五步新建子条目，写精确页面/记录窗口与失败环节；没有来源证据时保留未知，不填零。参考 [数据合同](DATA-FOUNDATION.md)。关键词：`暂无数据 跨页 空态 数据源`。 |

### B. 准入资格拒绝／计算口径不一致

| ID / 状态 | 复现与根因 | 处理、防回归与证据 |
| --- | --- | --- |
| `ML-COST-20261025-01` **WIP** | build71 充电列表单卡显示按本机默认单价估算的 `≈¥`，同一近 30 天摘要却把费用和单次均价写成“暂无数据”。`ChargesViewModel.effectiveCost()` 自 `2815e41f` 起排除 `ESTIMATE`；卡片仍消费带来源的投影，摘要/柱图/费用筛选因此与卡片不一致。 | WIP：同一合格投影计算卡片、窗口费用总和、每次均价、柱图和费用筛选；有估算时显示 `≈`、覆盖笔数，手动/实付/免费零、未知与跨币种仍分开。原始能量/手动费用已在 Room，默认费率在本机设置；可离线重算，不把估算冒充账单。回归：`ChargeSummaryMetricTest`、`ChargesRoomCostConsumerTest`（新补丁未获同 SHA CI）。关键词：`充电费用 估算 总和 单次均价 币种 Room`。 |
| `ML-COST-20261025-02` **FIXED@af6** | Battery 云端 UID 与本机缓存键不一致、Cost 临时错误丢已发布缓存／同车 opaque→UID 重绑定错配，可能表现为离线“暂无数据”。 | `1deeb3cb` 用已验证身份摘要和作用域映射恢复读取、保留临时错误前缓存；`VehicleStatusStoreScopeTest`、`CostRoomFirstViewModelTest` 随 `af6fe089` [同 SHA CI](https://github.com/Jovifei/tesla-master-mimo/actions/runs/40174354397) 通过。仅为合成/Room 证据，真实多账号 Fleet 未验。关键词：`UID 重绑 电池 费用 离线 缓存`。 |

### C. Room／作用域／日期窗口漏消费

| ID / 状态 | 复现与根因 | 处理、防回归与证据 |
| --- | --- | --- |
| `ML-HISTORY-20261025-01` **WIP** | 实机选择行程“近 7 天”，图表只画 7 天，摘要仍为 368 次全历史。行程与充电 VM 先载入本机 Room 全车行，再用不含日期条件的 `applyFiltersAndUpdateState()` 投影；`db41ac710` 的滚动自动 `loadMore()` 还扩展起点而保留“近 7 天／近 30 天”标签。 | WIP：选中窗口固定；列表、摘要、图表和来源覆盖共同按本地日 `[start, end+1日)` 过滤。滚动不暗中扩窗；显式“查看更多历史”才转为标有真实范围的自定义日期。回归：`DriveHistoryWindowTest`、对应 Charge 窗口/真实 VM 与 UI 测试（未获同 SHA CI）。关键词：`近7天 近30天 全历史 摘要 自动加载 范围`。 |
| `ML-HISTORY-20261025-02` **WIP** | 切到更早指定日期时，旧记录明明在手机 Room，却因 VM 只在内存 `allDrives`/`allCharges` 为空时读 Room，被上一窗口的非空列表挡住；离线失败后仍显示旧窗口。 | WIP：每次日期窗变更先按已验证账号/车辆从 Room 重读该窗，再做可取消的远端刷新；网络失败保留同窗缓存并明示状态。回归：离线冷开→近 7 天→旧单日、切账号/车和旧请求迟到负例；不能用旧窗数据充当缓存。关键词：`查旧日期 Room 冷开 本地优先 超时`。 |
| `ML-HISTORY-20261025-03` **WIP** | 自定义旧日期图表补空柱到今天，第一不完整周可能漏掉；`endOfDay()` 旧实现给 `23:59:59`，但读取采用 `< end`，遗漏当天最后一秒。 | WIP：图表止于选中结束日并保留首个部分周；请求与客户端统一“次日本地零点**排他**”边界。回归：最后一秒/毫秒、次日零点、UTC 偏移和 DST，参考 `LocalDayBoundariesTest`。关键词：`自定义日期 最后一秒 DST 图表 周`。 |
| `ML-SCOPE-20261025-01` **FIXED@af6** | 相邻行程或充电记录按数字 ID/同车拼接，若来源实例不同可混合驻车历史；Battery/Cost 缓存也有 UID 作用域缺口。 | `1deeb3cb` 对 account/car/source instance 严格准入；同源正例及异源负例进入 Room/VM 回归，`af6fe089` [CI 18/18](https://github.com/Jovifei/tesla-master-mimo/actions/runs/40174354397)。未知实例不猜认领。关键词：`source_instance_id 跨账号 串车 同ID 驻车`。 |
| `ML-CACHE-20261025-01` **FIXED@build70** | 弱网冷开历史页出现 0 记录/加载中，根因是 Room 行以 `localHistoryCarId` 保存，而 VM 用远端 carId 查询。 | `f810ace5` 经 `cachedContextForRemote()` 离线取验证映射；build70/2.1.51 真机记录见 [发布台账](ANDROID-RELEASE-LOG.md)。之后不得绕过 scope 直接按同数字 ID 读。关键词：`localHistoryCarId 负数键 冷启动 空列表`。 |

### D. UI 隐藏／文案／验证回退

| ID / 状态 | 复现与根因 | 处理、防回归与证据 |
| --- | --- | --- |
| `ML-UI-20261025-01` **WIP** | build71 驻车详情只留下时长、起止/变化三格，旧版的能量、平均功率、车内外温度、覆盖率和样本数区域在 `hasCloudDetail=false` 时整块消失；即使有样本数也被藏起，图标色有回退。 | WIP：恢复六项紧凑图标行，缺值用 `—` 和一条历史证据说明，保留多次充电及百分点/小时；不恢复旧固定容量伪能耗。回归：`ParkedTelemetryPresentationTest`、`ParkedDetailFullUiQualificationTest` 的 full/partial/sample 场景（未获同 SHA CI）。关键词：`驻车 六指标 图标 颜色 暂无数据`。 |
| `ML-UI-20261025-02` **WIP** | “全部／通勤／短途／长途”行程筛选组件仍定义于 `DrivesScreen.kt`，但页面已不调用，属于可见功能被删。 | WIP：恢复紧凑入口及切换后真实列表变化回归 `DriveHistoryFilterUiTest`；不得以“视觉精简”删除用户已批准的功能。关键词：`通勤 短途 长途 筛选 丢失`。 |
| `ML-MAP-20261025-01` **OPEN-TRIAGE** | 实机一条行程的地图停留在与文字地点不符的默认城市，且没有 A→B 线。尚未取得该行程原始定位点，不能认定是上游坐标错误。确定的 UI 缺陷是 `AmapMapView` 在内容渲染失败后仍回调加载成功；`DriveMapCard` 收到少于两个不同有效点也展示地图。 | WIP：渲染失败不报成功；不足两个有效点时保留路线文字，明确说明不能绘制实际轨迹。`DriveRoutePointsTest` 覆盖 0/1/2 点；真实地点与原始点的同车/同窗核对仍待单独完成。文档和对外证据不记录车主具体地点。关键词：`地图 异地 默认底图 无轨迹 A到B 定位点`。 |
| `ML-UI-20261025-03` **WIP** | 充电页 AC/DC、费用、地点三个筛选组件仍定义于 `ChargesScreen.kt`（`ChargeTypeFilterDropdown`/`CostFilterDropdown`/`LocationFilterDropdown`），但 `ChargesContent` 未渲染，VM 过滤逻辑与回调参数均已就绪——与 `ML-UI-20261025-02` 同源，同为 `aa8be8ca`（build 66，已入 main）“remove secondary filter chips”所致，原登记遗漏。 | WIP：在日期筛选行下挂载三个下拉并同步 `headerCount`；`chargeTypeFilter`/`costFilter`/`selectedLocations` 在 VM 侧本已生效，恢复后不得再以“视觉精简”摘除入口。回归：切换后列表/摘要/图表随同一投影变化，以及滚动位置与月份指示器 index 偏移（未获同 SHA CI）。关键词：`充电 AC DC 费用 地点 筛选 丢失 死代码`。 |
| `ML-CI-20261025-02` **FIXED** | API35 原生测试在 `DrivesScreen` 发生 4 项超时：日期筛选行与距离筛选行横向滚动容器歧义、`DriveHistoryFilterUiTest` 距离过滤期望颠倒且未滚动到首屏外卡片、`ParkedDetailFullUiQualificationTest` 的 `scrollUntilText` 在异步网络返回前过早耗尽重试、`QualificationFullAppSmokeTest` 子串匹配导致多节点歧义。 | 为 `DateFilterChips` 补充明确 testTag 与顺序对齐；`scrollUntilText` 在等待循环中持续推进列表并兼容异步网络到达；纠正 `DriveHistoryFilterUiTest` 距离区间断言并增加必要滚动。 |
| `ML-CI-20261025-01` **FIXED@af6** | API35 原 shell 截图 PNG 头正常但实际全黑，若只看文件存在会误判 UI 通过。 | `5845b5a9` 改取 Compose root，`4d50af93` 上传图及像素证明；`af6fe089` [API35 run](https://github.com/Jovifei/tesla-master-mimo/actions/runs/40174354371) XML 80/0/0/2、三张合成图非黑且哈希不同。仅是模拟器视觉资格。关键词：`API35 全黑截图 PNG 像素 视觉`。 |

旧案例入口：[10 月 4 日手机历史停在旧日期](BUG-REPAIR-2026-10-04-phone-history-stale.md)、[10 月 1 日归档同步与费用](BUG-REPAIR-2026-10-01-sync-charge-cost.md)、[8 月 31 日历史页闪退](BUG-REPAIR-REPORT-2026-08-31-history-page-crash.md)。它们的当时状态不自动代表当前版本；复发时先比对版本、来源和同窗测试。

维护规则：每个新 Bug 先搜索症状关键词与四类入口；新增稳定 ID、首见/复现版本、根因证据、修复 SHA、真实测试层级、仍待人类/自然事件门。未提交为 `WIP`，CI/手机/生产各自验收后再改状态；每次版本递增同步更新 [变更索引](CHANGE-INDEX.md) 与 [发布台账](ANDROID-RELEASE-LOG.md)。
