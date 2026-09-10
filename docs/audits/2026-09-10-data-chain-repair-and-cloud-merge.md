# 2026-09-10：数据链修复与云端历史合并

## 状态

`SOURCE_IMPLEMENTED / GO_LOCAL_PASS_WITH_OPTIONAL_PG_SKIPS / ANDROID_LOCAL_PASS / REAL_ACCEPTANCE_REQUIRED`
`READY_TO_MERGE = NO`

仓库 `Jovifei/tesla-master-mimo`；目标分支 `fix/20260907-onboarding-source-integrity`；功能源码 `4e97691949ecef9a39fd135be3efb988907b0f61`，从 `e83e86eb711013bc280c1534c2cc76bffd1cef8a` 线性增加六个提交。此文档与隐私技术说明随后提交，不改功能源码。没有合并 main。

## 已实现的修复

| 层 | 行为与边界 |
| --- | --- |
| Fleet | 独立请求核心状态与位置；位置成功不再替代核心数据；位置失败保留核心数据；核心授权失败不能被位置掩盖；0/false 保留。 |
| 元数据 | 数据库存储保留已确认车型/颜色等；首页首次状态刷新后补读同一车辆元数据。 |
| Telemetry | 读取车辆状态/配对时触发后台配置恢复；可重试错误最小间隔 60 秒、同账号车辆单飞；缺钥匙/权限/账单或硬件固件不支持不得死循环。不是没有请求也运行的周期任务。 |
| Onboarding | 遥测错误/等待显式呈现，使用已有会话重查；仅由用户明确继续时进入有限数据首页，不能静默假装全部完成。 |
| 车型图片 | 未知车型通用图标及“车型待识别”，不默认 M3/MY。 |
| 手机历史 | 全页恢复，部分页失败仍保留数据并提示；不因云端空集合或范围变小删除手机历史；按账号车辆及证据质量合并。 |
| 云端历史 | 重复导入不清空已有字段/真实路线点，保持规范记录 ID；原生遥测/隔离记录不被导入覆盖；前后台同步均检查账号作用域。 |
| 分析 | 混合远端成功/失败不标为全量 fresh；不完整导入不能升级成实测曲线、能耗、成本、健康度。 |

详细提交：

```text
b96ba6dece04d02d13c9af60a03441dfd122c42a  fix(fleet): merge independent core and location snapshots
ea28dc7e59028ec62694cb593831602ed9bf26aa  fix(telemetry): recover transient setup failures and expose onboarding state
b9a0d856955864ac865848a80f8bbc4ee2826a9c  fix(ui): use generic placeholder for unidentified vehicles
dfe3624a33c404ce29ee99c1b6823205c1e4b142  fix(data): preserve confirmed metadata and expose discovery failures
2e72a5ce6f0b9caa54450cc41787d2193c8076cf  fix(history): restore cloud archives with scoped quality-preserving merges
4e97691949ecef9a39fd135be3efb988907b0f61  fix(sync): preserve cloud evidence across phone recovery and account changes
```

## 用户本轮确认的产品方向与保守保留策略

本轮用户明确表示“之前说过要保留历史数据到云端”，并要求合并到新手机。以此为当前云端恢复方向；2026-08-28 架构文档“服务器永不保存行程”的表述属于历史决策，不能再当作当前实现的说明。

“两天”当前只恢复为常规上传请求的最近两个实际有数据 UTC 日期，不是 48 小时 TTL。本轮不删除既有云端存档，不删除手机历史；恢复读取全部服务端可用页面，包含更早存档。服务器强制两日自动裁剪在原基线已取消，本轮没有重新启用。两日上传窗口、服务器保留策略、手机长期合并是不同概念。

新手机必须登录同一授权账号/车辆。无关新用户不能继承别人数据；同 VIN 或同数字 carId 不是跨账号所有权证明。云端没有的旧数据不能凭空恢复，旧摘要不能补成真实曲线。

## 先前 CI 证据（本轮不复用）

以下工件只记录修复提交当时的 CI 边界；本轮本地验证以文末附录为准，不把旧 CI 的 PostgreSQL 或 Android 结论重复计入。

GitHub Actions 运行 `34424303295` 与 `34424360315` 均生成相同功能源码。最新工件 `10132138917`，名称 `data-chain-repair-evidence`，下载档案 SHA-256：`516f31e976abe897ae8a32bb4ac806d2c3a3de7e0f018b5e39a4f4ae073716a0`。

- `reports/SOURCE_HEAD.txt`：`4e97691949ecef9a39fd135be3efb988907b0f61`。
- `reports/go-tests.json`：225 个带 Test 字段的 run 事件，225 PASS、0 FAIL、0 SKIP；包含隔离 PostgreSQL 16 测试。包级 PASS 不另外计数。
- `reports/android-build.log`：Gradle 8.9 下载被 `java.net.ConnectException: Connection refused` 阻断，未进入应用编译/测试。没有 Android 本轮 JVM/lint/安装通过证据。
- 整体工作流为 FAILURE；continue-on-error 的子步骤可能显示 success，不得据此认定整体通过。

旧 PostgreSQL 精确重放测试的预期与上一版本“同值新时间戳刷新”规则冲突；本轮保留并扩展该测试，同时覆盖原样重放不前进、更新观测前进、重启后不重复完成。

测试数以下载的原始 JSON 为准：本轮早期口头进度中“346 项”是错误计数，不能用作证据；旧 Android 526 项也不能算到本轮。

## 仍未验收的外部边界

本地 Android 编译/JVM/lint/签名 Release 已由下方附录完成；新版本 `2.1.12/build31` 已用同签名 `adb install -r` 覆盖到验证手机并保留首次安装时间。页面级会话/历史保留、ECS 部署、官方配置同步、首个 MQTT 和真实行程/充电仍未完成，不能用本地门禁代替。

既有同会话不同 ID 的物理缓存行仍未做破坏性删除；列表合并与云端未来导入保持幂等，直接 DAO/Stats 对旧别名的统计过滤已在本轮补齐并通过契约测试。服务器严格两日 TTL 没有实现为本轮的新删除规则。

隐私页修正的是源码中的事实矛盾，不代表已上线或通过法律审核；运营主体、联系方式、线上页面与账号删除仍有发布验收要求。

## 下一步

下一步是按 SOP 在获得部署/设备授权后做同签名 `adb install -r` 与备份，再完成真实 Tesla OAuth、虚拟钥匙、`config_synced=true`、首个 MQTT、行程和充电验收。不要合并 main，不绕过 Tesla 用户授权。

## 本地 Codex 验证附录（2026-09-10）

- `git fetch --all --prune` 后，独立 worktree 以 `cee88304709c36c0f2f97a187203fdd90732aa21` 为基线；源码/测试/版本提交 `fe27f2b` 与文档提交 `09c3aaf` 已推送，最终本地/远端 HEAD 均为 `09c3aafd5f2f7e56489943d0f86ba1959c874ece`；`4e97691949ecef9a39fd135be3efb988907b0f61` 为功能基线祖先。父目录旧 `main` 的用户未提交文件未触碰。
- Go 新鲜 `test ./... -count=1` 为 225 个 Test 事件：212 PASS、13 SKIP、0 FAIL；`go vet ./...`、`go mod verify`、`go build ./...` 通过。13 个跳过含可选临时 PostgreSQL，因为本机 Docker Linux 引擎 named pipe 不可用；未连接 ECS/生产数据库。
- Android 使用交接允许的命令级 `-Xmx4g`、2 workers、`--no-daemon` 完成 Debug/Release JVM、lint、Debug、AndroidTest 和 Release 构建。Debug/Release JVM 各 543 项，Release 8 项预期跳过，失败/错误均为 0。
- 两个旧契约断言已按当前目标更新：全量历史读取后按日期过滤、未知车型返回通用占位；另补充直接 DAO/Stats 对不完整旧别名行的统计过滤契约和最小实现。版本候选为 `2.1.12/build31`，Release APK 从 `fe27f2b` 重建，SHA-256 为 `98E763A01D699E43FFFC9A9C4A824B6A7FFA2DC099AA30BE2FDF5544639DA810`，已覆盖安装到 `6e4fa92f`，ECS 尚未部署。
