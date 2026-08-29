# PR1：实时快照与分析真值修复审计

- 基线：`main@5f3fee151744ecbb24beffce5cf6089ef4deeb34`
- 分支：`fix/20260829-live-data-analysis-truth`
- 状态：代码级审计完成，设备验证与完整 Android 构建仍待本地环境补充

## 本提交范围

1. 新增统一快照证据模型，区分实时、最近、历史和不可用状态。
2. Dashboard 在 Adapter 降级到 TeslaMateApi 时清除旧快照时间和字段来源。
3. Dashboard 与当前充电链路拒绝 `(0,0)`、越界和非有限坐标。
4. 当前充电参数优先读取 Adapter 快照；Adapter 不可用时才退回 TeslaMateApi。
5. 当前充电状态请求失败时清空瞬时参数，不继续把旧值当成当前观测。
6. 充电费用估算拒绝负数和非有限充电量，保留真实零值。
7. 年度费用复用统一费用优先级；年度待机耗电直接读取 Adapter standby windows，并要求至少 80% 功率覆盖。
8. 待机能耗页使用导航传入的 `carId`，不再读取可能已切换的全局当前车辆。

## 数据真实性规则

- `false` 与真实 `0` 是有效观测值。
- `null`、NaN、Infinity、负充电量和非法坐标不可转成确定零值。
- 只有新鲜 MQTT/Fleet 证据可被归类为实时。
- `mqtt_latest` 归类为最近状态；数据库和 legacy API 归类为历史状态。
- 混合字段来源被显式标记，不把整份快照误称为全实时。
- 年度待机 kWh 只汇总具有有效非负能量且 coverage ratio >= 0.8 的窗口。

## 自动检查

本轮当前执行环境完成：

- 精确候选文件 trailing-whitespace 检查：PASS；
- secret/private-key/Authorization 字面扫描：PASS；
- Android 无关的纯 Kotlin 生产辅助文件 `kotlinc` 编译：PASS。

本轮当前执行环境没有完整 Android SDK checkout、Gradle 工程或真实设备，因此没有重新声称：

- `testDebugUnitTest`；
- `assembleDebug` / `assembleRelease`；
- `lintRelease`；
- Adapter 全量 `go test`；
- 覆盖安装与真车状态验证。

这些门禁必须在 Draft PR 合并前由本地 Codex/Windows Android 环境补齐。当前提交不直接合并 `main`。

## 精确文件边界

- `android/app/src/main/java/com/matelink/domain/telemetry/SnapshotEvidence.kt`
- `android/app/src/test/java/com/matelink/domain/telemetry/SnapshotEvidenceTest.kt`
- `android/app/src/main/java/com/matelink/ui/screens/dashboard/DashboardViewModel.kt`
- `android/app/src/main/java/com/matelink/ui/screens/charges/CurrentChargeViewModel.kt`
- `android/app/src/main/java/com/matelink/domain/analytics/EffectiveChargeCostResolver.kt`
- `android/app/src/test/java/com/matelink/domain/analytics/EffectiveChargeCostResolverTest.kt`
- `android/app/src/main/java/com/matelink/ui/screens/reports/AnnualReportMetrics.kt`
- `android/app/src/main/java/com/matelink/ui/screens/reports/AnnualReportViewModel.kt`
- `android/app/src/test/java/com/matelink/reports/AnnualReportMetricsTest.kt`
- `android/app/src/main/java/com/matelink/ui/screens/vampire/VampireViewModel.kt`
- `docs/audits/2026-08-29-pr1-live-data-analysis-truth.md`

## 留在 PR1 后续的项目

- 当前充电页面的可见实时/最近状态徽章与样本不足文案；
- Adapter 端坐标校验；
- Timeline 多车辆、部分数据错误和本地化；
- 真车驾驶、开口、TPMS、AC/DC 充电与 MQTT 断线恢复矩阵。
