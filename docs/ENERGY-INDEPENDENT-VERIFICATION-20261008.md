# 本地独立能耗验证：2026-10-08

被测业务源14ca59d；本地隔离树db68b49只新增阶段规格，未改业务代码。JDK17/Android SDK工具门通过。旧能耗focused单测BUILD SUCCESSFUL（1m55s），不代表新边界正确。

新增EnergyContractIndependentRegressionTest实际调用产品函数，参考数值手工独立计算；六项测试实际运行，6 tests / 6 failed，Gradle退出1。覆盖API有效0、负净回收、零能量行程加权、重复乱序区间覆盖虚增、5分钟gap截成30秒、有限输入产生Infinity。这是合成真实函数回归，不是自然车辆证据，不修改产品实现或放宽预期。

另外只读审查：DriveSummaryDao.kt两种avg消费SQL分子跳过null能量而分母仍包括该距离。内存SQLite复现20kWh/100km加未知能量900km，得到20Wh/km，配对有效样本应200Wh/km；全未知被coalesce成0。DriveModels.efficiencyWhKm可穿透Infinity。驻车推导SOC101→0缺范围验证。这些由远端完整修复并回归，不由本地接管业务。

父agent再次运行verify_energy_dao_sql.py，从实际DAO提取SQL于一次性内存SQLite执行4项：2 PASS / 2 FAIL，退出1。未知能量距离误入分母和全未知被置0已实际复现；已知零和负净回收SQL两项通过，不能泛称SQL拒绝负值。独立原oracle不依赖产品能量函数。

APIbb09独立只读审查：native未订阅/保存EnergyRemaining或Power，archive路线有power而净能量null；不要把官方字段可用当实际收到。telemetry_core.go applyChargingEnergy共用AC/DC基准槽，切换会重设、正delta覆盖EnergyAdded，需回归双字段交错/复位/迟到。parked_history_bounds.go只可证明同scope停车首尾SOC与时间，旧archive不足以提供停车期间能量，未知保持null。session observed不能将派生能量升级实测。这些是源码合同风险，尚未对自然源或生产执行新验收。

清理：7个旧验证树可重建缓存/失败日志已移至E:/Claude_allow/Download/matelink-cleanup-recoverable-20261008，可恢复；保留旧outputs/reports/test-results与全部签名/回滚/迁移/有效测试。永久删除操作被自动审批策略阻止，未执行永久删除。逐路径移出回执cleanup-receipt.json保留本机。

手机当前已连接，现场只读2.1.26/build45，firstInstallTime2026-08-31 22:36:47；本轮新包尚未安装。原始日志不上传。Hub读取连接拒绝，未声称本轮状态已上报。
