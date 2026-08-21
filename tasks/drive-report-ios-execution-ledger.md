# Drive Report 与 iOS 执行审核台账

> 仓库：`Jovifei/tesla-master-mimo`  
> Android 分支：`feature/20260820-drive-completion-report`  
> 主分支基线：`main@1d861b8167baa906114b6b0fb6d480bcd42d0491`  
> 当前总状态：`A3_IMPLEMENTED_PENDING_VERIFY`

## 总阶段表

| 阶段 | 名称 | 状态 | 提交 SHA | 审核结论 |
| --- | --- | --- | --- | --- |
| P0 | 执行方案与台账 | `COMMITTED` | `c439ae4b2e937d4307813680fcd4417383a5d183` | 正好两个计划文件 |
| A0-CI | GitHub Actions 基线 | `COMMITTED` | `02d50cd69be1cefbdd862f0bfadaedd20771cd89` | 保留 Lint 债务证据并运行独立门 |
| A0 | Android 基线审计 | `COMMITTED` | `ffe241814c2536c47d4db61ba734494d3008558e` | 允许进入契约和实现 |
| A1 | Drive Report V1 契约 | `COMMITTED` | `750ce20bf800d73736979ddf399d8af3cf4b2771` | 完整/降级 fixture 本地验证通过 |
| A2 | Adapter/API 最小扩展 | `CLOSED` | — | 现有 drive detail 足够，首版不重复增加接口 |
| A3 | Android 检测、持久化、去重 | `IMPLEMENTED_PENDING_VERIFY` | `74fd996fa477655f0eb4647dd4a9b6e0e4e81771` | 等待 CI |
| A4 | Android 通知和 Deep Link | `NOT_STARTED` | — | — |
| A5 | Android 报告 UI | `NOT_STARTED` | — | — |
| A6 | Android 集成与真机验收 | `NOT_STARTED` | — | 设备部分留待 Codex |
| A7 | Android PR 与最终审计 | `NOT_STARTED` | — | Draft PR #1 |
| B0-B6 | iOS 原生应用 | `NOT_STARTED` | — | Android 合并后独立分支 |

---

## A0 自动基线

第一轮 CI run：`32436984107`

- Android JVM：156 tests，0 failures，0 errors，0 skipped；
- Adapter：`go mod verify`、`go test ./...`、`go vet ./...` 全部通过；
- Lint：既有 883 errors / 270 warnings，首项为地图文案缺失 de/ja/fr 翻译；
- Manifest：既有重复 `SystemForegroundService` warning；
- Artifact SHA-256：
  `bc351cb0264a87e7eac2c20b558d477a03bca5cf6b3c2142cb68b8fdea81e6d8`。

CI 已改为完整记录 Lint，但继续执行 APK、AndroidTest APK、Manifest 和 schema 门；不创建 baseline 隐藏债务。

---

## A1 Drive Report V1

提交文件：

```text
docs/architecture/DRIVE_REPORT_V1.md
shared/contracts/drive-report-v1.schema.json
shared/contracts/fixtures/drive-report-v1-complete.json
shared/contracts/fixtures/drive-report-v1-partial.json
tools/validate_drive_report_contract.py
```

关键冻结规则：

- 缺失值保持 null；
- 真实零值必须有明确来源；
- 能耗区分 `api` / `power_samples` / `unavailable`；
- 费用只允许标记为平段电价估算；
- 通知不含地址；
- 报告地址默认遮挡；
- 路线和时序最多 360 个真实下采样点；
- `(0,0)` 不作为有效路线；
- 无时序不画模拟曲线。

A2 结论：Android 首版使用现有 drive-detail 与本地 provenance 组装报告；不新增重复 Adapter endpoint。未来 iOS 复用同一契约和组装语义。

---

## A3 检测、持久化与去重

### 实施文件

```text
android/app/src/main/java/com/matelink/data/report/DriveReportEntities.kt
android/app/src/main/java/com/matelink/data/report/DriveReportDao.kt
android/app/src/main/java/com/matelink/data/report/DriveReportDatabase.kt
android/app/src/main/java/com/matelink/data/report/DriveReportDeliveryRepository.kt
android/app/src/main/java/com/matelink/domain/report/CompletedDriveDetector.kt
android/app/src/main/java/com/matelink/di/DriveReportDatabaseModule.kt
android/app/src/test/java/com/matelink/domain/report/CompletedDriveDetectorTest.kt
```

### 设计审核

- 使用独立 `matelink_drive_reports.db`，不修改现有 StatsDatabase V15；
- 新数据库仅存游标和投递状态，不存地址、坐标、token 或 Key；
- `carId + driveId` 为复合主键；
- 首次运行建立最高有效 driveId 水位线，不补发历史行程；
- 重复同步由复合主键和 `INSERT IGNORE` 双重去重；
- 崩溃发生在插入后、游标更新前时，重试不会产生第二条记录；
- `fullResetSync()` 只重置统计缓存，不删除报告游标和投递状态；
- 检测候选要求：正 carId、正 driveId、非空结束时间、正时长、有限且正距离；
- 不使用 summary 的能耗/速度/电量零值决定报告内容。

### 已添加 JVM 矩阵

- 首次有历史数据；
- 首次无历史数据；
- 后续多条新行程排序；
- 无效距离/时长/结束时间；
- 其他车辆隔离；
- 重复 driveId；
- 游标不后退。

### 当前门

状态：`IMPLEMENTED_PENDING_VERIFY`

下一步 CI 必须证明：

- Room/KSP 编译；
- Hilt module 编译；
- 新 Detector JVM 测试通过；
- Debug APK、AndroidTest APK 构建；
- 独立数据库 schema 生成；
- 无 tracked source/schema 漂移；
- Adapter 回归继续通过。

A3 未通过 CI 前不进入 A4 提交。
