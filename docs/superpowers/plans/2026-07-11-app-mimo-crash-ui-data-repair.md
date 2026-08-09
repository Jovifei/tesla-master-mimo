# app_mimo 崩溃、详情页与真实数据修复计划

## 1. 已确认事实

- 当前基线已提交为 `7f01e4d`，分支为 `codex/app-mimo-data-setup`。
- 设备 `6e4fa92f` 的系统退出记录包含多次 `APP CRASH(EXCEPTION)`。
- 首页点击“刷新”已稳定复现崩溃。异常为：
  `foregroundServiceType 0x1 is not a subset of foregroundServiceType 0x0`。
- `DataSyncWorker` 以 `FOREGROUND_SERVICE_TYPE_DATA_SYNC` 启动 WorkManager 前台服务，但合并后的 Manifest 中 `androidx.work.impl.foreground.SystemForegroundService` 没有声明 `dataSync` 类型。
- 桌面首次进入的偶发闪退与同一个后台同步时序高度一致：工作任务在首屏期间启动时触发崩溃，第二次进入时任务时序不同，所以可能暂时成功。
- Docker 中 TeslaMate、PostgreSQL、Mosquitto、Grafana、TeslaMateApi 均正常运行。历史接口已有 11 段行程和 1 次充电，因此不是“Docker 未启动”。
- `/api/v1/cars/1/status` 当前返回 `no info on this car ID`；现有 TeslaMateApi 没有可用的实时 MQTT 快照。
- 11 段行程的 `energy_consumed_net` 均为空；电池健康接口有续航历史，但容量字段为 `0`。App 不能把这些空值显示成真实的 0。
- 电池页独立崩溃尚未取得单独堆栈：当前 P0 崩溃会在导航前杀死进程。修复 P0 后必须单独复现，不提前下结论。

## 2. 实施顺序

### P0：阻止启动、刷新与同步崩溃

1. 在 `AndroidManifest.xml` 合并声明 WorkManager 的 `SystemForegroundService`：
   `android:foregroundServiceType="dataSync"`，保留已有 `FOREGROUND_SERVICE_DATA_SYNC` 权限。
2. 增加 Manifest 合并检查，验证最终 Debug Manifest 的服务类型不是 `0`。
3. 审查 `MateLinkApplication`、`DashboardViewModel.refresh()`、`SettingsViewModel` 和 `StatsViewModel` 的 WorkManager 调度：
   - 初始化只发生一次；
   - 唯一任务名称保持幂等；
   - 连续点击刷新不创建并发全量同步；
   - 前台通知失败不得杀死主进程。
4. 设备验证：冷启动 20 次、首页刷新 5 次、设置页强制同步 3 次；检查 Crash buffer 和退出原因无新增异常。
5. P0 通过后再复现电池页：进入、刷新、打开详情、返回各 5 次，记录独立堆栈并按根因修复。

### P1：让首页从“车辆列表已连接”升级为完整状态

1. 在 Docker Compose 增加 MateLink Adapter：外部继续使用 `:8080`，TeslaMateApi 改为仅内部访问。
2. Adapter 同时读取：
   - TeslaMateApi 历史接口；
   - Mosquitto 最新车辆主题并持久化快照；
   - PostgreSQL 最新 `positions` 作为非实时降级数据。
3. 新增能力与状态接口，返回字段级来源：`live_mqtt`、`database_latest`、`history`、`unavailable`。
4. Android 先探测 Adapter capability；不存在时继续兼容原 TeslaMateApi，但明确显示降级状态。
5. Dashboard 采用状态优先紧凑布局：车辆、实时/降级标签、电量、续航、锁车/车门、胎压、里程、平均能耗。未知字段显示 `--`，不伪造 `0` 或 `false`。

### P1：行程能耗、驻车与统计数据

1. 将 `DriveEnergyCalculator` 接入详情同步：按位置时间与功率梯形积分，间隔上限 30 秒，并记录采样覆盖率。
2. 将计算出的 `energyConsumed` 和 `efficiency` 回写 `DriveSummary`；API 有可信值时优先使用 API，否则使用计算值。
3. 每段行程列表和详情显示：能量消耗、平均能耗、数据来源和覆盖率。
4. Adapter 增加驻车时间线接口，读取 `drive_id IS NULL` 的位置样本，计算驻车时长、电量变化、平均/峰值功率和温度范围。
5. 小于 500 米的移动并入相邻驻车区间；大于等于 500 米才显示驾驶行程。
6. 新增 `Screen.ParkedDetail`、驻车详情 ViewModel/Screen；驻车卡可点击进入，不复用驾驶详情。
7. Stats 在少于 7 天时显示真实 N 天统计并单独标注 7 天估算；不得因为样本不足显示“暂无统计数据”。

### P1：行程、驻车与充电视觉整理

1. `DrivesScreen` 的历史卡增加垂直留白和稳定最小高度；标题、时间、指标统一字号与行高。
2. 地址使用结构化中文地理编码结果。中国地址仅组合中文道路/街道/区县/城市，不再拼接 `District` 等英文片段；原始地址仅作为降级值。
3. 行程详情：A → B 改为单行紧凑中文文本，起止时间同一行并固定 24 小时制。
4. 行程详情的距离、时长、能耗、平均能耗、电量、功率和温度使用较大的数字层级与有限的语义色。
5. 充电详情采用同样的信息层级：起止时间单行，能量、电量、功率、温度和费用突出显示。
6. 禁止页面内嵌套卡片；统一复用 `EditorialListItem`、指标网格和现有车辆配色体系。

### P1：充电费用可编辑

1. Room 新增充电费用覆盖实体和迁移，字段包括 `chargeId`、金额、是否明确免费、币种、更新时间和来源。
2. 有效费用优先级固定为：人工金额 > 人工明确免费 > TeslaMate 正费用 > `充电量 × 1.10` 估算。
3. 充电列表与详情提供“编辑本次费用”对话框；支持保存金额、标记免费和恢复自动值。
4. 列表、详情、统计共用同一个费用解析器；`null` 或 API `0` 不得自动显示为免费。

### P2：地理编码与后续能力

1. 中国地区优先 Adapter 侧高德 Web 服务，Key 从 Docker `.env` 注入；App 端 Key 仅作为加密本地降级配置。
2. 缓存结构化地址与语言，显示优先级为：高德中文 > 本地缓存 > TeslaMate 原地址。
3. 哨兵历史先实现事件时间线；现有 MQTT/TeslaMate 不提供视频文件，画面能力需要后续接 Tesla USB/NAS 文件源。

## 3. 关键文件边界

- 崩溃：`android/app/src/main/AndroidManifest.xml`、`MateLinkApplication.kt`、`DataSyncWorker.kt`、各 WorkManager 调度入口。
- 首页：`DashboardViewModel.kt`、`DashboardScreen.kt`、Adapter client/repository。
- 行程：`DrivesScreen.kt`、`DriveDetailScreen.kt`、`DriveDetailViewModel.kt`、`SyncRepository.kt`。
- 驻车：新增 timeline domain model、Adapter endpoint、`ParkedDetailViewModel.kt`、`ParkedDetailScreen.kt` 和导航路由。
- 充电：`ChargesScreen.kt`、`ChargeDetailScreen.kt`、费用覆盖实体/DAO/repository。
- 电池：`BatteryViewModel.kt`、`BatteryScreen.kt`；只根据 P0 后取得的独立堆栈修复。
- 统计：`StatsRepository.kt`、`StatsViewModel.kt`、`StatsScreen.kt`。
- 部署：`deploy/teslamate-home-docker/docker-compose.yml` 和新增 Adapter 目录。

## 4. 验证清单

- 单元测试：Manifest 配置、分页停止、能耗积分、500 米分类、费用优先级、统计窗口和中文地址格式化。
- Room 测试：费用覆盖迁移、覆盖保存/删除、计算能耗持久化。
- Adapter 测试：无 MQTT、MQTT 新鲜、MQTT 过期、DB 降级、短移动合并、驻车采样覆盖。
- Android：`testDebugUnitTest`、`lintDebug`、`assembleDebug`。
- Docker：`docker compose config`、服务健康检查、真实历史接口与 Adapter 状态接口。
- 设备：冷启动、刷新、强制同步、电池页、行程详情、驻车详情、费用编辑和统计页完整冒烟。
- UI：桌面与手机截图检查中文地址、24 小时时间、字号层级、文字截断和触控区域。

## 5. 完成定义

- 不再出现首次进入、首页刷新或同步导致的系统“屡次停止运行”。
- Dashboard 在实时状态不可用时仍展示可信历史/数据库降级信息，并标注来源。
- 每段驾驶和驻车都有可解释的能耗结果；无样本时明确不可用。
- 驾驶、驻车、充电详情均具备统一、舒展且可读的视觉层级。
- 充电费用可在 App 内编辑，并正确进入统计。
- 所有结论均由测试、Docker 检查和真机路径共同证明。
