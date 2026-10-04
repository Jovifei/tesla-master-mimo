# 2026-10-04 手机历史停在旧日期：诊断与读取链修复

当前状态（2026-10-04 09:06 UTC）：43 已完成本机完整 Android 门禁并同证书保数据升级；本轮行程/充电详情分别成功读取 2189/38 个真实样本，未知行程 SOC 不再显示 0%，充电显示 97%→100%。本轮未复现此前间歇失败，具体根因及持续同步仍未闭合，不能用成功样本抹去 07:51 的失败证据。


## 结论与证据边界

2026-10-04 实机在“全部时间”下仍显示 10 月 1 日行程、9 月 29 日充电，并提示云端历史同步失败、当前显示本地缓存。下拉刷新及改选近 7 天均未恢复。与此同时，隔离于手机 UI 的只读核对显示源数据库、Archive Bridge 与云端有效绑定的完成记录数量和最新时间一致。因此，本次已定位到手机的认证读取/车辆发现/展示链，不能归因于 Bridge 丢失后三天的数据。

最初诊断没有可归属于失败请求的 HTTP 状态；后来定位到 history_context 的无 HTTP 错误，但仍未捕获具体异常类别。下述门禁是已确认的源码缺陷；后续 42 的首次成功、07:51 复发和 43 本轮详情成功分别记载，均在保留登录、历史和配置的原安装上验证。

## 现场只读核对（UTC）

| 链路 | 2026-10-04 核对结果 |
| --- | --- |
| TeslaMate 源 | 完成行程 356 条，最新结束 10-04 02:00；充电 52 条，最新结束 10-03 08:53 |
| Archive Bridge | 使用稳定 runtime 挂载，运行中；游标 367/52，于当天 02:01 推进；已完成待传 0/0 |
| 云端有效绑定 | 完成记录 356/52，最新结束时间与源相符，隔离记录 0 |
| API | health/ready HTTP 200；部署版本仍为 `781c4025`；观察到最近一次 kernel OOM 为 10-02 09:01，不能据健康响应认定 OOM 已修复 |
| 手机 | `com.matelink` 2.1.22/build 41，正式 API 域名；行程全部时间仍为 10-01，充电仍为 09-29，显示缓存与同步失败提示 |

行程游标是源记录 ID，不能与完成记录数量直接相减。没有 Fleet 原生会话或 telemetry latest 也不否定个人 Archive 数据已经同步。诊断没有导出 token、VIN、轨迹或请求响应正文；服务端日志访问受限后未变更权限。

## 可借鉴的历史经验

- [10 月 1 日同步与充电费用修复](BUG-REPAIR-2026-10-01-sync-charge-cost.md)：Bridge 曾挂载已删除的工作树，写游标失败导致反复发送首条。此次正确 runtime 挂载、游标推进和待传 0/0 已排除该故障重演。
- [10 月 2 日登录 OOM 诊断](DBG-2026-10-02-login-oom.md)：API 曾发生 OOM 与 502；分支测试通过不等于生产部署。此次 `781c4025` 仍有整段历史读取成本，候选手机若遇到 history 502 必须继续区分服务端失败。
- [Fleet 与个人 TeslaMate 分工](ARCH-DECISION-2026-09-27-fleet-teslamate-hybrid.md)：Archive 历史、Fleet 实时数据与手机本地缓存是不同证据，不能相互冒充。

## 已确认的读取门禁

`UnifiedHistoryRepository.load` 原来先获取实时车辆列表。即使已经在当前账号/服务上下文中找到本地持久化的车辆身份，只要 `/cars` 出错，`car == null` 就跳过两个历史接口，直接合并旧 Room 缓存，并只提示 `history_cached`。

服务器 `/cars` 依赖 Tesla 的实时车辆发现；已知车辆的历史路由则先检查 `fleetVehicle(userID, carID)`，本地所有权存在即允许进入用户/车辆范围内的历史查询。这一点在当前源码及实际部署的 `781c4025` 中均成立。因此，实时 Tesla 发现失败并不等于当前应用会话失效或已授权的 Archive 历史不可读。

独立审查进一步发现，旧 App 的云缓存映射没有 API origin 来源证明；仅加入请求内的 origin 快照，不能证明旧缓存来自当前服务。现场虽然确认了正式域名，但未取得已安装 APK 的精确源码映射，因此不以版本号或新包内硬编码旧 hash 作为迁移证明。

正式修复增加 `GET /api/matelink/v1/cars/{id}/history-context`：沿用 App 会话认证，直接参数化查询当前 `user_id + car_id` 的持久 `provider_vehicle_id`，返回 `data.capability_version=1`、`car_id`、`vehicle_uid`。UID 与 `/cars` 保持同一原始语义，不改用 readiness 的哈希 UID。此接口不调用 Tesla、解密 VIN、注册绑定或写数据库。缺会话 401；缺失/他人车辆 404；数据库错误或无有效身份 503。仅接受精确 GET 路径，UID 在数据库侧限制为 1–256 UTF-8 字节，并拒绝空白或不规范空格。

云端历史读取优先获取此认证身份；旧服务返回 404 时可兼容尝试 `/cars`，但只有成功发现所选车辆及有效 UID 才授权本次远端历史读取。接口失败不把旧数字 ID 缓存当作授权证明。自建服务继续使用自己的服务身份范围。

新历史上下文按 API origin、账号及已认证车辆 UID 分配命名空间。无 origin 证明的旧 Room 记录、费用修正与分析仍保留，不自动并入新空间。该改动只接入历史读取，不重构其他业务的全局车辆身份入口。存在未关联的旧本机专属费用/分析时，历史页面需明确提示其仍保存而尚未关联，不能制造“已恢复全部本机附加信息”的假象。

每页读取前后及缓存持久化边界复核上下文；账号、服务或连接模式变化时不将旧请求结果显示为当前车辆。诊断只记录请求阶段、UTC 请求时间、HTTP 状态与固定类别，不记录 URL、响应正文、token、VIN 或车辆 ID。Tesla 重授权 401 与 App 会话失效不是同一个事实。历史失败仍标缓存/部分结果。

## 相关刷新缺陷

另外修复相对日期在跨日刷新时仍沿用旧截止日期、页面返回前台未刷新、切换车辆未重新读取行程，以及 Dashboard 两个状态接口失败却将旧状态标为近期数据。相对日期缺陷可独立复现，但因本次实机原筛选为全部时间，不能作为此次事件的根因解释。

取消旧请求并校验最新请求身份，避免快速切换车辆/筛选或重复刷新时由晚到结果覆盖新页面。自定义日期保持用户选择。

## 验证与剩余验收

后端新增 `TestHistoryPostgresAuthenticatedReadsSurviveDiscoveryFailure`，使用隔离真实 PostgreSQL、合成账号/会话/车辆和 10 月 4 日历史，验证：

- `/cars` 分别返回上游 503、Tesla 重授权 401、限流 429 时，已持久化的所属车辆历史仍可独立返回 200 和最新合成记录，且不再调用实时 Vehicles/Status
- 缺失或无效 App 会话均返回 401，另一有效账号不能用同一数字车辆 ID 读取归属他人的记录；另一个账号自身空历史仍为空
- 该测试验证原有历史授权边界，不证明现场 HTTP 状态或真实手机已恢复

新的 history-context 回归覆盖当前账号持久 UID、无 Vehicles/Status 调用、车辆行 hash 未变化、缺会话/其他账号、异常 UID 的 SQL 字节上限、严格路径和数据库取消错误。先在旧源码复现缺接口 404，再检查修复。最终后端源码的隔离 PostgreSQL 全 Go/race 为 **478/478 通过，零失败、零跳过**；receipt SHA-256 `e9b4967c28437287d75dc3d14b406d22a5eae21e23b949a9016b25b3264adcda`。另通过 vet、编译与 module verify；云工作树编译用 `-buildvcs=false` 避开工作树 VCS 探测限制，源码身份另由 manifest 固定。

后端六文件已单独提交 `c7eaa973b3f3c046d977a58de3cb2c35bb88f805`，精确 [CI 37179570728](https://github.com/Jovifei/tesla-master-mimo/actions/runs/37179570728) 全部通过：Go/PG 478、Web 13、race/vet/build/modules、8 项合成资源场景及证据上传。CI 不包含完整 Android 构建，也不代表生产最小候选已上线。

Android **2.1.23/build42 候选**在该后端提交上冻结 38 个文件，真实 Kotlin 分层回归 **55/55 通过**，包含实际 `UnifiedHistoryRepository.load`、单独身份解析、费用写入与返回、当前 origin 持久映射、分页/部分失败、账号/车辆/服务变化、取消和晚响应。恢复旧 cars-only 分支的受控副本在相同实际 load 回归下失败，确实未发出历史请求。全局 resolver 保持原 namespace；仅历史列表与 DriveDetail/ChargeDetail 使用共同的已验证历史上下文，避免新费用编辑在列表和详情之间分裂。切车会清除旧免费充电标记和单位。

## 2026-10-04 本机完整构建与原包升级验收

本机从 `fed2536874c30e03b4c858389742a75ba35ad37a` / tree `aec55c73b04b26fa8cc141cfb7976bba4e840ee0` 构建；唯一 tracked 差异为 `SettingsExperienceContractTest` 两条陈旧版本断言由 41/2.1.22 更新到 42/2.1.23，生产源码没有额外变更。本提交回传这两条断言，不能将此前 APK 的构建来源简写为未修改的 fed tree。

- Debug：608 通过、零失败/错误/跳过；Release：600 通过、零失败/错误，8 项 `StateScenarioFixturesTest` 因 DEBUG 条件按预期跳过
- KSP、Hilt、Compose、Release R8 与构建通过；Debug Lint 0 errors / 259 warnings / 9 info，Release Lint 0 errors / 240 warnings / 8 info。未证明这些 warning 数量没有退化
- APK SHA-256：`A22D7E628FAC1BF67CA3FEBE1FEB87392F04F2B592FEEAF42550765FDEF242A7`；证书 SHA-256：`9AB144E824ABF26A5941819ABB06831288C36A8BFE622657E3DC9D88281FC774`
- 实际反编译核对 API origin 为 `https://api.teslalink.joviluma.com/`，cloud login 开启、mock 关闭；`com.matelink` 2.1.23/build42 以同证书 `adb install -r` 覆盖，`firstInstallTime` 保持 2026-08-31 22:36:47，原登录保留
- 原全部时间页面已显示 10 月 4 日 13:58 行程、10 月 3 日 16:41–16:53 充电；重复刷新、返回和首页往返后仍在，原“同步失败/本地缓存”提示消失
- 旧手机专属费用/本地分析的“保留但未关联”提示仍可见，未自动合并来源不明旧映射；这不是旧记录被删除

该次验收确实恢复了新日期显示，但后续 07:51 复验再次失败，不能据此判断持续同步已经闭合。新报告的充电汇总次数为 0、能量/费用缺失仍在新包复现；最高速度、SOC 采样、月标签、胎压趋势与授权回跳作为下一批缺陷分别修复，不能据本次同步恢复标记整个产品完成。快速切账号、断网和手工费用跨列表/详情的全部组合仍需各自回归，不由上述成功路径推断通过。

## 后端部署边界

原分支包含此前 Stage2 的多批数据库改动，不能将“上线 identity 接口”解释为整条分支迁移。生产候选从已部署 `781c4025a720a257fdd6d2f8ee9a309655bfa020` 出发，仅应用 `main.go` 路由和新增 `history_context.go` 两个生产文件，共新增 59 行，候选 tree 为 `ee8d172ebe8f4a2eac7aeb75e7a620c41d29b955`，补丁 SHA-256 `6c6db0423c7f9e92990631484d1630a44900505ee15a47e2a53e15fba7553231`。

在新建隔离 PostgreSQL 数据库上由该旧基线初始化 schema，旧基线全测试加新增回归的 Go/race 为 **272/272 通过，零失败、零跳过**；receipt SHA-256 `a41e46f12b48d3ccd6009e3d5994a53769747402927543b25ae8922533eedc3d`。新增测试通过 overlay 注入，Go1.22 的虚拟新文件 vet 限制使该命令显式 `-vet=off`，另对实际生产候选运行 vet、编译、module verify 均通过。数据库 `history_summary_version` 列计数为 0，没有携入 Stage2 schema。

后端最小候选与原 PR 分支是两个明确的源码构建身份，发布记录必须各自记录精确 base、tree、文件 hash 和验证结果。建议最小候选使用 `BUILD_SHA=history-context-781c4025-tree-ee8d172ebe8f`。用户已批准测试通过后的现有服务小补丁部署、保留回滚和手机保数据升级。该最小后端已于 2026-10-04 05:43 UTC 完成部署核验，实际 build 为 `history-context-781c4025-tree-ee8d172ebe8f`，精确 tree/blob/hash 匹配；本机旧基线隔离 Go/race 272 项、vet/build 通过。health/ready 200，未授权新旧路由均 401；归档数量部署前后为 357 drives / 52 charges，schema/config 未变，回滚镜像保留。整条 Stage2 分支没有部署。本次不包含尚在开发的 compact bridge，不进行真实事件重放或数据清理，也不将读取修复描述为整体 OOM 已关闭。

## 07:51 再现与 2.1.24/build43 最小诊断候选

相同已安装 build42 / APK A22D7E62… 后续重开详情，发生 5 次 history_context 请求失败，日志仅有 http=none / transport_or_decode。行程曲线页没有采样，最近充电也缺曲线，缓存 SOC 显示 0→0；源/云已有真实速度、功率和充电 SOC 样本，因此不能只说“车辆没采集”。尚未拿到失败的具体异常类型。

07:54 只读旁证：手机当时 Wi-Fi INTERNET/VALIDATED，无portal；PC health/ready 200、未授权 identity 401；现服务自 05:43:01 UTC 启动，restart=0、OOMKilled=false，未见新内核 OOM。旁证不等于失败时网络正常，更不能用PC未授权401证明手机已登录请求成功。

根因证据分开：具体网络/解析失败原因未确认；已确认诊断层仅传HTTP code，丢失 ApiResult 的错误类型，导致所有无HTTP错误合并；同时详情离线fallback直接复制Room非空Int占位0，绕过已有evidence-aware mapper，将未知SOC伪装为0。

本片只修改：`SafeApiFailure` 固定异常类别及取消传播；`TeslamateRepository` 将factory异常纳入安全边界，记录详情读取阶段/类别/采样数量；`UnifiedHistoryRepository` 传递typed error；两个详情fallback复用原证据mapper。日志不使用异常message、response body、URL、token、VIN或车辆身份。保留未知null与真实观测0，原缓存数据不删除，无数据库迁移。

独立审查冻结的11文件补丁SHA-256为67a2f61dfd218793a5a2ffa719f27b9b523b62a4cb7a06722ead1e70508d1923；manifest为5c046687c865f511c13ec18b18d8f2532f68ceb4efab1e144146e9b0d454e747。独立重新编译执行61/61便携Kotlin检查通过，包括实际Unified编排和固定错误类别；这些测试有平台/codec边界替身，不是完整Android合格证。受控旧逻辑丢typed error的16项回归有1项失败；SOC旧接线红、修后绿。

候选的本机门禁要求执行HistoryCachedSocTest的3项真实JSON/KSP检查及完整Gradle/Hilt/Compose/Room/Lint/R8，核正式origin、非mock、签名/APK后保数据覆盖，再复验正常会话的列表/详情/返回/前台读取。这些本轮结果见下节；尚未捕获的新失败类别仍决定后续根因修复，不无证据重复授权。

43保留原branch已审UI14（579c607，CI37186681916通过），但不混入尚未发布的OAuth6文件、TPMS迁移、metrics字段/曲线或持久标量投影候选。新后端、bridge、生产数据回填仍需各自明确授权。版本、发布提交、APK与持续复验结果分别记录，详情本轮成功不等于全部遗留问题关闭。

## 08:59–09:06 UTC：43 本机完整验证与成功详情样本

源码为 `7f9038acaadb0b849e97d6b3ad50178a81e8f152`，tree `ae3b74a54b061d13ed00869e61050ffe20e7bd34`。精确 [CI 37188637309](https://github.com/Jovifei/tesla-master-mimo/actions/runs/37188637309) 为 Go/PG 478、Web 13、8 个合成资源场景通过；该 CI 本身不编译 Android。

本机唯一 tracked 差异是 `HistoryRefreshIntegrationContractTest.source()` 的 `.readText().replace("\r\n", "\n")`，本次原样回传。旧测试在 Windows CRLF checkout 下有两项多行字符串断言失败，生产源码没有变化；规范化后整组与完整套件通过。这是源码文本测试的跨平台修正，不应通过改 ViewModel 缩进或弱化业务断言处理。

- 聚焦真实测试 24 项通过：安全异常 4、实际发现恢复 16、真实 JSON 缓存 SOC 3、详情 SOC 接线 1，零失败/跳过
- 完整 Debug 634 通过；Release 626 通过、8 个 DEBUG 专用 fixture 跳过，零失败/错误。Hilt、Compose、Room、KSP、R8 与 APK 构建通过；两种 lint 0 errors/fatal，Debug 261 warnings、Release 241 warnings，不称警告已清零或无退化
- 实际 APK 为 `com.matelink` 2.1.24/build43；正式 API origin、非 mock 经 APK 入口反编译核验。APK SHA-256：`62fdebca1513f411bbf26fb4cc7b4206bcb61b99c7deb6e298c660c1bc945a1e`；证书 SHA-256：`9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`
- 08:59 UTC `adb install -r` 成功，已安装 APK hash 匹配；首装时间仍为 `2026-08-31 22:36:47`，原会话直接进入，未卸载、清数据或重授权
- 10 月 4 日 13:58–14:11 行程：两次 `drive_detail / 2xx / success / sample_count=2189`，速度曲线卡出现，首末未知 SOC 为“暂无数据”，不再误显示 0%
- 10 月 3 日 16:41–16:53 充电：`charge_detail / 2xx / success / sample_count=38`，SOC 97%→100%，电量与功率曲线卡出现
- 返回、前台和正常刷新观察保留最新日期，本轮没有捕获失败类别。收尾发生额外详情切换，原列表是否恢复未确认，因此停止追加触控；不把旧 UI 快照当当前结果

本机证据目录为 `tasks/android-diagnostic43-20261004/`：`VERIFIED.md`、`full-build-summary.json`、`phone-install-receipt.json`、`windows-source-contract.patch`。这些原始验收文件保存在执行电脑，不假称已随本提交上传。源码身份需写成 7f9038a 加上述测试一行，不能称未修改工作树；生产 APK 代码仍为 7f9038a。

剩余：此前 history_context 间歇性 transport_or_decode 的具体原因未查明；本轮没有失败可供分型。行程旧归档缺逐点 SOC、汇总缺值/时长精度、旧本机费用关联、TPMS 与 OAuth 各自继续独立修复。此次未再部署后端、重导真实数据或启用 Stage2。
