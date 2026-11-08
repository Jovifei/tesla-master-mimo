# 10 Statistics 独立反证复核

Jovi，本次仅复核父任务初版 `05-statistics.md` 的 S01/S02/S03/S05，根目录为 `E:\project\_worktrees\matelink-release-data-audit-20261026`。未修改产品、资源、测试、构建、设备或用户数据；未执行测试/SQL/网络请求。下列均为当前冻结源码的静态追踪。父任务另行运行的 08 条件及 SQLite 合成样本不算本复核执行证据。

初版的 **Timeline 能量 P1 必须撤回**；**费用卡“100mi 误贴”断言必须更正**。国家/地区规范距离错用 formatter、地理字段未知变 0、地图质量过滤缺失、深入能量空和 0、时间线仅分钟时长被隐藏仍有源码依据。S01 保留条件性，未证明正常导航会复用同一 VM 或发生账号数据泄漏。

下列源码路径均相对 `android/app/src/main/java/com/matelink/`；行号为本次实际读取行号。

## S01：确认缺身份与请求门，限制实际触发结论

- `ui/screens/stats/CountriesVisitedViewModel.kt:54–84` 启动独立 `viewModelScope.launch`；67 返回后直接修改原集合/发布，59–61 的单位子任务也直接发布，没有 generation、scope 或返回后 context 检查。
- `RegionsVisitedViewModel.kt:114–171` 分别等待 countries129、regions133、charges137、drives138，再145发布；160–161 的 boundary 子任务没有请求门。错误分支只改 loading/error，保留旧内容。
- `data/repository/StatsRepository.kt:712–793` 每个 helper 独立调用 `resolveHistoryCarId`；214–215 只 resolve 后取 local ID，不使用主 Stats 的 `StatsRead.read/verify`（226–245）。因此一次 regions 加载可在多次读取间跨身份/修订。
- 反证边界：`CountriesVisitedScreen.kt:76,85–87`、`RegionsVisitedScreen.kt:93,102–104` 使用入口默认 Hilt VM 与参数 effect；`ui/navigation/NavGraph.kt:828–859` 从各自 back-stack route 提供固定 car/year/country。普通 push 新页并不能证明同 VM 被另一车/年复用；认证重定向也存在 `NavGraph.kt:467–485` 清栈路径。

结论：存在“页面/VM仍存活且scope变化或再次调用load”的静态并发缺口。不要写成每次切页必现或已观察到跨账号数据泄漏。最小修法为 captured scope/local identity + generation，完整 geo snapshot 返回后检查、身份变化清投影；不改布局。P1 影响只能维持条件性，需专门消费者交错测试才能证明具体可达场景。

## S02：距离确认，费用子项降级更正

- `data/repository/StatsRepository.kt:833–853` 将 DAO 的 canonical `totalDistanceKm` 原样传给 Country/RegionRecord。`UnitFormatter.kt:17–21` 提供 km→展示单位的转换，60–65 的 `formatDistance` 仅贴标签。
- `CountriesVisitedScreen.kt:303`、`RegionsVisitedScreen.kt:382,823` 确实将 canonical km 交给只贴标签 formatter：160.9344 km、mi 偏好会显示约161 mi。**确认显示错误，建议 P2**；改用 `formatDistanceKm` 即可。
- `StatsScreen.kt:1309–1313` 计算的是每100 canonical km费用，1362传了距离单位参数，但实际 `android/app/src/main/res/values/strings.xml:1246` 为字面量 `Cost/Distance`，`values-zh/strings.xml:1237` 为 `费用/距离`，均没有格式占位符，也没有 `100mi`。因此“标签变100mi而数值没转换”的初版断言不成立。

费用项可保留 P2 文案合同不明确：当前数值有 per100km 基准，标签未说明100或距离单位；不能据此声称用户实际看到错误100mi标签。最小修法应明确固定100km或显示偏好分母并同次换算，避免只修改公式。

## S03：未知变0确认，明确现代持久化已经资格化

- `AggregateDao.kt:492,500,524,533` 的 country SQL 对 primitive distance/energyAdded 求和；487/519 对充电缺组补0。region SQL 同类。它们没有逐字段 sample count/nullable receipt。
- 生产可达性：`UnifiedHistoryRepository.kt:568` 保存 `distanceKm ?: 0.0`；601 保存 `qualified.batteryInputKwh ?: 0.0`；最终 merge835 也保存 display 资格值缺失→0。`SyncRepository.kt:383,407` 同样如此。记录级 observed/derived 不证明每个字段都有值，因此地理显示可将字段未知解释成已知0。
- 重要限制：现代 energyAdded 列是资格投影，**并非原 API 未审查能量**；`SyncRepository.kt:403–412` 和 `UnifiedHistoryRepository.kt:591–608` 同时保留独立 raw evidence。可确认“字段可用性丢失”，不能只因 SQL 未读 energy_contract 就断言现代 mapper 会写入被拒的正数能量。遗留/手工异常 primitive 数值是否存在需单独证据。
- 地图 `AggregateDao.kt:809–869` 确实缺 summary quality 过滤，只检查坐标非null。`RegionsVisitedViewModel.kt:71–98` 只按类型/年份筛选；`RegionsVisitedScreen.kt:474–478` 原样建立坐标/address markers。因此同一个有合格 header/region 的国家，额外带 geocode aggregate 的 quarantined summary 不会在此链路被过滤。
- 可达性限制：`RegionsVisitedScreen.kt:189–194` 在 regions空且countryRecord空时进入空态，不能声称“只有 quarantined 行的国家一定画地图”。本复核未证明当前真实缓存存在这种行，未作设备复现。地图缺质量门为源码缺口，P1需实际可达损害证据；建议按 P2 数据资格不一致记录。
- UI 精确性更正：地图当前474–478只消费坐标和address，不显示 DTO 的 energy/distance，也没有记录详情回调。初版 RG11/RG12 中“marker能量/日期/详情ID”的措辞应区分 DTO 携带与真实可见字段。
- `StatsRepository.kt:575–578` 已用合格 `batteryInputKwh`，但空集合 `.sum()` 为0；607–613 无能量样本数。`StatsScreen.kt:520–522` 真正调用卡片；1856只检查 chargeDetailsProcessed，1873/1878直接输出两类能量。即使所有模式 UNKNOWN 或某已知模式缺能量，仍会显示0kWh。**P2未知变0确认**，不要误报主模式仲裁：525–543明确保留 UNKNOWN。

最小修法：geo和deep energy携带逐字段合格样本数/nullable总量，零样本显示现有未知值，真实0保留；地图查询使用相同质量cohort并验证有限/范围坐标。保留现有布局。

## S05：能量P1反证关闭，时长P2确认

`TimelineViewModel.kt:242` 确实读取 `chargeEnergyAdded`，但实际入口 `repository.load`（120）返回的 charges 已统一资格化：

1. `UnifiedHistoryRepository.kt:328–329` 对持久化行调用 `toAnalysisChargeData()`，不完整边界的未持久化行调用 `raw.withQualifiedEnergy()`。
2. `domain/analytics/HistorySummaryMapper.kt:137–174` 最终返回 `projected.withQualifiedEnergy()`；缓存入口 UHR229也走此 mapper。
3. `ChargeEnergyBinding.kt:36–38` 覆写 `chargeEnergyAdded = batteryInputKwh`；`ChargeModels.kt:132–143` 对 explicit contract、window/source、有限非负值资格化。

因此真实 Timeline 生产路径中该字段已是资格值。初版“显式unknown仍显示raw能量”的 P1 没有成立，不可用直接构造裸 ChargeData 的 mapper 单测代替真实消费者证明。改成显式读 `batteryInputKwh` 可提高合同可读性，但属于防御性清晰化，不能认定现有运行错误。

时长则没有对应上游保证：`HistorySummaryMapper.kt:64,83` 的有效 Room fallback 只填 durationMin，不造 durationStr；`toAnalysis*`、`withSafeHistoryDisplay`（102–105）、`withQualifiedEnergy`均不补字符串。UHR437/483只从已有 DTO/cached string合并。`TimelineViewModel.kt:224–225,247–248` 保留两字段，而 `TimelineScreen.kt:224,276` 只显示字符串。因此合法分钟、无字符串的缓存/旧记录可达但时长隐藏。**P2确认**；使用现有时长formatter回退有限合法分钟，不以0补未知。

## 保留的正向结论与验证范围

本复核不推翻主 Stats 的 `StatsRead`、同次Room事务/字段资格；不重开 Mileage 最新原子投影/选择保护；不把 Cost 的已记录金额与默认费率估算分栏当错误。S04/S06与其他矩阵行不在本次独立复核范围。

采用 scoped rg/范围读取核对生产者→消费者。CodeGraph 指定此根仍返回其他根节点，未用于冻结候选结论。没有独立运行测试；父任务需在最终05矩阵同步上述撤回/降级，运行证据另标实际执行者和层级。
