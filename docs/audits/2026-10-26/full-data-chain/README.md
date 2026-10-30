# Android上线前数据链路总审

**结论：当前候选暂不通过上线。** 已完成39个注册路由的源码链路审查，约500条字段/位置检查记录；有确定错值/漏读，也有真实采样和提供方证据缺口。不能宣称每一显示位置已由真实数据支撑。

审查从2026-10-26开始，跨至客户端2026-10-27收口。实际工作根为`E:\project\_worktrees\matelink-release-data-audit-20261026`，分支`codex/release-data-audit-20261026`；从原目录复制当前候选954文件，稳定副本哈希不变。只有审查文档和隔离样本，本轮没有修产品/改布局、安装手机或发布。详情见[00-scope-and-isolation.md](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/00-scope-and-isolation.md)。

## 全范围覆盖

| 组 | 注册路由数 | 字段检查行 | 页面及矩阵 |
|---|---:|---:|---|
| 登录/首页 | 2 | 72 | [01-auth-home.md](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/01-auth-home.md)：TeslaLogin、Dashboard及全部主组件 |
| 充电 | 3 | 67 | [02-charging.md](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/02-charging.md)：列表、详情、当前充电 |
| 行程/驻车 | 4 | 47 | [03-driving-parked.md](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/03-driving-parked.md)：列表、详情、曲线、驻车；含1条历史已删除地图项 |
| 我的分析 | 6 | 55 | [04-analysis.md](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/04-analysis.md)：电池、能效、续航、待机、温度、四轮胎压 |
| 统计/费用/里程/时间线 | 6 | 102 | [05-statistics.md](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/05-statistics.md)：含国家/地区、里程年/月/日 |
| 长途/报告/导出 | 6 | 104 | [06-trips-reports.md](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/06-trips-reports.md)：列表/创建/详情、年度、PDF、CSV/JSON |
| 系统/配置/辅助 | 12 | 56 | [07-system.md](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/07-system.md)：我的入口、设置、电价、胎压设置、高德配置/预览、关于、数据状态、软件更新、哨兵及未开放辅助路由 |
| 合计 | **39** | **503** | [route-inventory.json](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/route-inventory.json)、[field-matrix.json](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/field-matrix.json)可逐项检索 |

行数不是实车通过数，也不是互不重叠的单个传感器数量；同一行可能明确列出共用链路的多个位置。39是注册路由数，WhereWasI/Vehicle3d等没有发现当前生产入口，其实现问题单列潜伏项，不冒充当前可见页面阻断。

## 已确认的优先问题

1. **登录/车辆绑定与会话恢复。** 当前车ID默认1，但真实首车可能不是1；登录fallback未写回，使首页和主导航继续请求无效ID。首页会拒绝不在当前车单中的ID，这是可用性问题，不能描述为已证明越权。冷启动从登录开始后会话失效的重定向条件实际失败；退出与同账号token轮换也有条件性竞争。见01-H01/H04/H05、09反证。
2. **充电类型/费用/当前会话。** 未知模式聚合后被当AC；车辆免费标志把明确12元第三方账单变0；历史预合并可跨窗口借旧字段；当前快照覆盖会话起点SOC/曲线，部分字段绕开当前会话资格。见02-CHG-P1-01至04。
3. **行程曲线/单位/端点与能量来源。** mph可重复换算，稀疏值数组与时间轴错位，A→B→A终点被全局distinct丢掉；无采样以145Wh/km给出实际感能耗；驻车nullable SOC被primitive占位0替代等。见03-D01至07。默认参考模型若获准，须独立说明，不计实测。
4. **部分子页缺身份/命名空间一致性。** Vampire没有同类scope/迟到请求门；CreateTrip/Export拿remote正ID读负local-key Room，已有数据可能仍展示空。国家/地区的旧读取链也缺当前请求门，但数据泄漏/导航复用须按条件证明，不能称每次push/pop必现。见04-AN01、06-N01/N02、05-S01。
5. **本地阈值和地理字段。** 胎压自定义profile保存正ID、监测读负ID，真实store回读null；Geo地图SQL未排除混合集合的隔离点；字段未知量被非空0小计吞掉，本地年份边界漏记录。见07-SYS04、05-S03/S04。
6. **保存记录与输出。** 有同drive指纹但不同charge/名称的既存SavedTrip时，浏览会触发清理删除；年度→PDF年参数与用户所选不一致，导出范围/格式/字段合同需要固定。N06为条件性保存风险；PDF有可改年份预览、导出未声明完整账本，所以N03/N07按复核降P2。见06、09。

这些是去重后的问题组，不是断言每个用户、每次请求必现。错误乘法、未取得字段、未消费已有数据和缺提供方能力分别在矩阵注明。

## 实际验证

| 层级 | 实际执行 | 结果 |
|---|---|---|
| Kotlin/真实函数、VM、临时Room配置 | 原目录审查专用11条件；被测实现复制后未变 | **8断言失败、3对照通过**；非编译/框架错误；见08/XML |
| 原始DAO SELECT/最小合成SQLite | 冻结树直接提取生产SQL7条件 | **4失败、3对照通过**；见11/geo-sql-results.json；不是Android Room设备测试 |
| 源码冻结 | 954文件复制前后/目标，收口再次校验 | 无漂移；不是单一已提交源码版本 |
| 既有全量 | 上一阶段Debug/Release各1356、Release8skip，双lint/R8等 | **继承证据**，不能替新审查条件或外部后续修改背书 |

本轮没有运行39页真机数据/自然Fleet事件，也未操作用户数据库、生产或授权。初审、静态SOURCE_OK、模拟测试、手机与提供方结果分层记录。

## 反证后撤回/收窄

- Timeline表面读`chargeEnergyAdded`，上游UHR已资格化；**撤回其能量旁路P1**，保留分钟有值但字符串缺失时隐藏的问题。
- Stats“费用/距离”资源没有100mi占位；**撤回100mi误贴断言**，保留国家/地区canonical km formatter的真实错误和分母说明问题。
- WhereWasI/Vehicle3d无当前入口、驻车地图在外部变更中移除，相关旧/潜伏问题不计当前可见P1。
- 没有声称仅quarantined记录的国家必画地图；SQL样本保留了合格锚点才验证额外隔离点。
- 分时50kWh预览明确是“假设”，不是假用户账单；问题是启用配置尚无实际费用消费者。

完整独立反证：[09-independent-review.md](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/09-independent-review.md)、[10-statistics-review.md](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/10-statistics-review.md)。

## 空值如何处理

- **源里有、客户端丢/错/隐藏**：修ID、DTO/资格、nullable读取、公式和绑定，不能用等待采样搪塞。
- **提供方有但未请求/未保存**：接通有字段时刻和scope的采集/保存；修复前不冒充已经有历史。
- **从未采到或不支持**：旧摘要不能还原真实路线/速度/温度曲线、容量和完整哨兵事件。保留具体原因和后续采样状态，不以0、当前值、固定容量/波形填满。

同一记录须在列表、摘要、图、详情、重开及导出保持来源/窗口/单位/币种一致；观察、推导和估计明确区分。这是最终体验门，不能以“页面能打开”或单次构建通过替代。

## 下一步

可审阅的源码范围、测试与非布局约束见[FIX-PLAN.md](E:/project/_worktrees/matelink-release-data-audit-20261026/docs/audits/2026-10-26/full-data-chain/FIX-PLAN.md)的F1–F7。按Jovi先授权代码修改的约定，本轮尚未实施这些新增修复；不自动提交、安装或上线。生产/真实账户/车辆门分别验收。

Hub初始计划已上报；后续上报遇共享工作目录不一致及本机服务拒绝连接，已停止自动重试并留本地说明，没有声称发送成功。审查证据不依赖Hub在线。
