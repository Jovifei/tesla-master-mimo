# A0 Android 行驶报告基线审计

> 日期：2026-08-21  
> 仓库：`Jovifei/tesla-master-mimo`  
> 功能分支：`feature/20260820-drive-completion-report`  
> 主分支基线：`1d861b8167baa906114b6b0fb6d480bcd42d0491`  
> 计划提交：`c439ae4b2e937d4307813680fcd4417383a5d183`  
> CI 工作流提交：`02d50cd69be1cefbdd862f0bfadaedd20771cd89`  
> Draft PR：`#1`  
> 结论：`A0_AUDIT_COMPLETE_WITH_LEGACY_LINT_DEBT`

---

## 1. 审计结论

A0 已完成仓库结构、数据层、同步、通知、导航、Adapter 和安全边界审计，并通过 GitHub Actions 获得第一轮自动验证证据。

第一轮 CI（run `32436984107`）结果：

| 验证项 | 结果 |
| --- | --- |
| Java/Gradle 环境 | PASS：Temurin 17.0.20、Gradle 8.9 |
| Android JVM 单测 | PASS：156 tests，0 failures，0 errors，0 skipped |
| Adapter `go mod verify` | PASS |
| Adapter `go test ./... -count=1 -v` | PASS |
| Adapter `go vet ./...` | PASS |
| Android Lint | 基线失败：883 errors、270 warnings |
| Debug APK | 第一轮因 Lint 串行失败而跳过 |
| AndroidTest APK | 第一轮因 Lint 串行失败而跳过 |
| WorkManager manifest 门 | 第一轮因 Lint 串行失败而跳过 |
| Room/KSP | JVM 测试阶段编译通过；`copyRoomSchemas NO-SOURCE` |
| 源码/秘密 | 本轮未写入任何 token、Key、地址或 `.env` |

Lint 首个错误是既有地图资源缺少德语、日语、法语翻译：

```text
amap_settings_section is not translated in de / ja / fr
```

全部 883 个 error 均归类为现有 `MissingTranslation` 债务。该债务在本功能分支创建前已经存在，不是行驶报告功能引入的回归。

为避免“既有 Lint 债务导致 APK 和后续独立门永远不执行”，CI 已调整为：

- 完整运行并上传 Lint 报告；
- Lint 继续显示失败证据，但不阻断独立的 JVM、APK、AndroidTest APK、Manifest、Room drift 和 Go 门；
- 不创建 Lint baseline，不隐藏债务；
- 后续行驶报告新代码仍要通过范围内资源和格式审核。

A0 不包含任何 Android、Adapter、iOS 或 Web 业务源码修改。

---

## 2. Git 基线

| 项目 | 结果 |
| --- | --- |
| `main` | `1d861b8167baa906114b6b0fb6d480bcd42d0491` |
| 功能分支 | `feature/20260820-drive-completion-report` |
| Draft PR | `#1` |
| 计划提交 | `c439ae4b2e937d4307813680fcd4417383a5d183` |
| CI 调整提交 | `02d50cd69be1cefbdd862f0bfadaedd20771cd89` |
| Android/Adapter/iOS 业务源码变更 | 0 |
| 是否合并 `main` | 否 |

计划提交严格只包含：

```text
docs/superpowers/plans/2026-08-20-drive-completion-report-ios-execution.md
tasks/drive-report-ios-execution-ledger.md
```

CI 提交严格只包含：

```text
.github/workflows/drive-report-ci.yml
```

---

## 3. 工具链基线

| 项目 | 声明/验证值 |
| --- | --- |
| GitHub runner | Ubuntu 24.04 |
| Java | Temurin 17.0.20 |
| Gradle Wrapper | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| KSP | 2.1.0-1.0.29 |
| Java/Kotlin target | 17 |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 26 |
| Room | 2.6.1 |
| WorkManager | 2.9.0 |
| Go module | Go 1.22 |
| App version | 1.4.2 / versionCode 14 |
| applicationId | `com.matelink` |

Connected Android tests 默认受保护，只有显式传入：

```text
-PallowConnectedDeviceTests=isolated-device
```

才允许执行，避免测试影响真实用户设备。

---

## 4. Room 数据库基线

- 当前数据库版本：15；
- 已导出 schema：12、13、14、15；
- `DatabaseModule` 使用 `StatsDatabase.ALL_MIGRATIONS`；
- 未使用 destructive fallback；
- 已有迁移测试覆盖：
  - 旧 V13 含能耗列 → V14；
  - 旧 V13 缺能耗列 → V14；
  - 旧 V14 identity hash → V15。

行驶报告投递状态必须采用：

```text
StatsDatabase V16
MIGRATION_15_16
16.json
MigrationTestHelper 回归
```

并满足：

1. 不删除现有表；
2. 不重建无关数据；
3. 不清除 App 数据；
4. 迁移后保留服务器配置、API token 和历史行程；
5. `fullResetSync()` 不得删除报告水位线和已投递记录，除非产品明确提供“重置通知历史”。

---

## 5. 同步与检测基线

当前同步入口：

- 保存连接配置后调度 expedited `DataSyncWorker`；
- Dashboard 刷新时调度 `DataSyncWorker`；
- Worker 依次同步 summaries、details，并在成功后调度 geocoding。

当前不存在：

- completed-drive 检测器；
- 首次启用水位线；
- `carId + driveId` 一次性去重；
- 待查看报告队列；
- 设备重启后的报告检测恢复。

A3/A4 必须实现：

```text
同步成功
→ 检测完成行程
→ 事务写入投递记录
→ 判断前后台和通知权限
→ 前台提醒或后台通知
→ 打开/已读/关闭状态
```

检测、持久化和通知必须分层，避免通知失败导致报告永久丢失。

---

## 6. 数据真实性基线

现有 Drive detail DTO 可提供：

- 起止时间和地址；
- 里程表起止、距离；
- 时长；
- 平均/最高速度；
- 最大/最小功率；
- 起止 SOC；
- 额定/理想续航；
- 车内/车外温度；
- 净能耗和能耗率；
- 位置、速度、功率、SOC、海拔时序。

但 `SyncRepository.toSummary()` 当前会把部分缺失值替换为：

```text
distance -> 0.0
duration -> 0
address -> ""
speed / power / battery -> 0
```

因此 Drive Report V1 必须：

- 优先使用 nullable detail；
- 只有带明确 provenance 的 summary 值才可作为回退；
- summary 零值不能单独证明真实零；
- 空地址视为不可用；
- 无真实坐标时不生成 `(0,0)`；
- 无时序数据时不画曲线；
- 无能耗或电价时费用为不可用。

---

## 7. 通知和导航基线

可复用能力：

- Manifest 已声明 `POST_NOTIFICATIONS`；
- App 已有一次性通知权限引导；
- `MainActivity` 为 `singleTop`；
- `onNewIntent()` 会把通知 Intent 注入 Compose；
- NavGraph 已处理若干通知 deep link；
- 已有 Charging/Sentry NotificationManager。

缺口：

- 无 `DriveReportNotificationManager`；
- 无 `Screen.DriveReport(carId, driveId)`；
- 无 `drive_report` Intent 路由；
- 无前台提醒和后台通知互斥；
- 无待查看报告队列；
- BootReceiver 不恢复报告检测。

通知必须默认不显示地址或坐标。

---

## 8. Adapter 基线与 A2 决策前提

现有 Adapter 只有：

```text
GET /api/matelink/v1/capabilities
GET /api/matelink/v1/cars/{carId}/snapshot
GET /api/matelink/v1/cars/{carId}/parked/{olderDriveId}/{newerDriveId}
```

其他 `/api/` 请求走 legacy proxy。

现有 drive-detail DTO 已覆盖报告页面的大部分原始数据，因此 A1 先冻结共享 Drive Report V1 契约；A2 再判断是否需要新增 Adapter report endpoint。

默认优先级：

1. 如果现有 API 能稳定提供所需 detail，Android 端组装报告；
2. 如果 iOS/Android 一致性、响应大小或接口稳定性无法保证，再新增只读 Adapter V1 endpoint；
3. 不为“看起来统一”而重复已有接口。

---

## 9. 安全与秘密

`.gitignore` 已忽略：

```text
android/.gradle/
android/.kotlin/
android/**/build/
android/local.properties
android/.idea/
deploy/**/.env
deploy/*.env
```

A0 未读取或提交：

- `.env`；
- MateLink token；
- 高德 Key；
- 家庭地址；
- 经纬度；
- 真实车辆数据；
- 本地 SDK 路径。

GitHub Actions 使用合成/仓库数据，不读取部署 `.env`。

---

## 10. A0 审核发现

### P0

无新的行驶报告 P0 代码缺陷。A0 允许进入 A1 契约阶段。

### P1

1. 现有 Lint 债务：883 errors、270 warnings，主要为多语言缺失；
2. `SyncRepository.toSummary()` 的缺失补零不得进入报告语义；
3. 无 completed-drive 检测、去重、恢复；
4. 无 report deep link、通知管理器和待查看队列；
5. Room 新表需要 V16 前向迁移；
6. `fullResetSync()` 对投递记录策略未定义。

### P2

1. Manifest 重复声明 WorkManager `SystemForegroundService`；
2. Kotlin/Java 有少量既有 deprecated warning；
3. 可在后续独立维护任务清理全库 Lint，不能把 883 项翻译债务混入本功能。

---

## 11. A0 结论和下一阶段

A0 结论：

```text
A0_AUDIT_COMPLETE_WITH_LEGACY_LINT_DEBT
```

允许进入 A1：

- 冻结 Drive Report V1；
- 提交 JSON Schema、完整 fixture、降级 fixture 和标准库验证器；
- 不修改生产代码；
- CI 验证共享契约；
- A1 审核通过后再进入 A2/A3。

设备安装、真车行驶和 foreground/background 通知证据仍留给 A6 或后续 Codex 善后，不影响 A1–A5 的源码、单测、构建和静态验收。
