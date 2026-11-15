# Auth / onboarding / Dashboard 全数据链审计

审计日期：2026-10-26。审计者：`/root/audit_auth_home`。目录：`E:\project\tesla_master\app_mimo`。独立只读核验 HEAD=`cdfe0b26a7abfe593df9e30b17be60d5cdfd70da`，分支=`codex/app-build72-original-20261025`；本文依据当前含未提交修改的源码，不能仅凭 HEAD 重放本结论。只写本文件，未修改产品、布局、资源或测试，未执行构建、测试、设备操作、网络或生产查询。主代理转交的 build1356 各 variant 单测通过属于继承证据，本审计没有将其算作新执行证据。

## 1. 覆盖范围与判定规则

逐位置覆盖：TeslaLogin 同意条款/隐私、登录按钮、错误/加载态、浏览器授权及 App Link 回调、ticket 去重、账号会话存储/刷新/退出/重授权、首次车辆配置与配对返回/跳过/重试/进程恢复；StartDestination 与登录后/失效导航；Dashboard 正常/部分数据/加载态、车名/车型/轮毂/车色/自定义照片、当前车辆路由、来源/时间、驾驶及充电 HUD、车辆状态、SOC/进度条/续航/目标SOC、锁/插枪/空调/哨兵、车门/车窗/前后备箱、里程/位置/地图、内外温度、四轮胎压/四轮警告、底部充电功率/充入电量/剩余时间、历史导航及 readiness 引导；首次加载、轮询、刷新、scope 切换、缓存和地址解析迟到结果。

`SOURCE_OK` 只表示所审源码有完整且合理的数据通路，不表示已安装包、实车或上游账户已验收。`BUG` 表示存在具体可达的错误消费者分支；本文发现的 BUG 都是当前源码控制流证据，没有宣称已运行复现。`SOURCE_DATA_MISSING` 仅用于当前上游输出结构明确未提供的数据；没有现场样本时不猜生产缺数据。`UNVERIFIED` 表示仍需实际消费者或设备/上游证据。表中“证据 Tn”均为**现有测试源码已阅读/定位，本次未运行**。

路径缩写均相对仓库根：

- `D`=`android/app/src/main/java/com/matelink/ui/screens/dashboard/DashboardScreen.kt`；`DV`=同目录 `DashboardViewModel.kt`；`VP`=同目录 `VehicleStatusPresentation.kt`。
- `L`=`android/app/src/main/java/com/matelink/ui/screens/auth/TeslaLoginScreen.kt`；`LV`=同目录 `TeslaLoginViewModel.kt`。
- `CM`=`android/app/src/main/java/com/matelink/data/api/models/CarModels.kt`；`AM`=同目录 `AdapterModels.kt`；`API`=`android/app/src/main/java/com/matelink/data/api/TeslaMateApi.kt`。
- `R`=`android/app/src/main/java/com/matelink/data/repository/TeslamateRepository.kt`；`SR`=同目录 `SettingsRepository.kt`；`VF`=`android/app/src/main/java/com/matelink/domain/model/UnitFormatter.kt`。
- `VS`=`android/app/src/main/java/com/matelink/data/local/VehicleStatusStore.kt`；`VC`=同目录 `VehicleContextRepository.kt`；`SS`=同目录 `JourVoltSessionStore.kt`；`RF`=同目录 `JourVoltSessionRefresher.kt`；`PH`=同目录 `VehiclePhotoStore.kt`。
- `NG`=`android/app/src/main/java/com/matelink/ui/navigation/NavGraph.kt`；`SD`=同目录 `StartDestinationViewModel.kt`；`SE`=`android/app/src/main/java/com/matelink/domain/telemetry/SnapshotEvidence.kt`。
- `BG`=`deploy/jourvolt-dev-mock/main.go`；`FP`=同目录 `fleet_provider.go`；`TH`=同目录 `telemetry_http.go`；`TS`=同目录 `telemetry_service.go`。

存储/作用域缩写：

- `LIVE`：Dashboard StateFlow；请求捕获 `HistoryReadScope(source, effectiveApiOrigin, accountNamespace, sessionGeneration, modeGeneration/settingsGeneration)`，同时检查 current selected numeric carId + requestGeneration。`VC:36-57`，`DV:236-257`。
- `CACHE`：`vehicle_cached_status` 私有 SharedPreferences，仅保存 `CarStatus` JSON 和快照时间；key 绑定 source/origin/account/localHistoryCarId/stableIdentity SHA256，cloud UID 由当前 `/cars` 验证；`VS:31-40,57-99`。未保存单位，见 H02。
- `AUTH`：EncryptedSharedPreferences `jourvolt_session_secure`，同步保存本应用 access/refresh/user/expiry，identity generation/epoch fence；`SS:30-75,98-102`。不读取本机凭证。
- `PHOTO`：`PH:60-81,85-134` 以 source/origin/account/stable vehicle identity 命名与校验的本地文件/pointer，采用 staged/rollback 与 commit；未读取用户照片。

共同数据入口：`API:43-46` snapshot，失败回退 `API:74-77` status；`R:449-485` 解析 DTO；`DV:307-361` 选择响应。`API:66-72` `/cars` 提供元数据。云端 `BG:851-919` 显式封装各字段和 km/bar/C；Fleet 原始里程/续航/mph 在 `FP:311-316,342` 规范化为 km/kmh，温度和压力直传；`TH:310-330` 混合新鲜 telemetry 与 provider，逐字段 stale fence 在 `TS:360-396`。自托管 adapter 返回规范 km/bar/C（`deploy/teslamate-home-docker/adapter/cmd/adapter/main.go:328-348`），legacy TeslaMate API 返回 preferred units，Android formatter **只加标签，不二次转换**。

## 2. 参数逐位置矩阵

### 2.1 登录、账号与首次车辆配置

| 页面/可见字段或交互 | source / wire / DTO | 直传或计算 | 存储+作用域 | UI绑定 file:line | 空/零规则 | 现有具体证据 | 判定 |
|---|---|---|---|---|---|---|---|
| 登录：条款勾选 | 本地 `termsAccepted`；consent current version | 独立 checkbox；当前 consent 可恢复勾选 | rememberSaveable；JourVoltConsentStore | L:91,108-111,233 | false 不启动授权 | T1 `loginRequiresSeparatePublishedTermsAndPrivacyBeforeCloudAuthorization` | SOURCE_OK |
| 登录：隐私勾选 | 本地 `privacyAccepted`；consent current version | 同上，独立状态 | 同上 | L:92,247 | false 不启动授权 | T1 同测试 | SOURCE_OK |
| 条款/隐私链接、登录按钮 enabled | PublicInfoLinks + BuildConfig；`GET v1/auth/tesla/start` X-JourVolt-Terms/Privacy-Version | URL合法 + 两项已勾 + 非Loading | consent记录；请求generation | L:82-90,281-288；LV:167-206；TeslaAuthApi.kt:13-17 | 未配置/未勾选进入明确错误 | T1:119；T2:9-18 | SOURCE_OK |
| 授权浏览器/回调 | authorization_url；callback `/oauth/callback?ticket=` | 授权 Tesla HTTPS endpoint/redirect/scope校验；callback配置host校验 | VM ticket inFlight/handled，非持久 | LV:196-205,219-270；NG:431-435 | 空ticket不exchange；callback error用有限本地化映射 | T3 endpoint/redirect否定组；T4 ticket去重 | SOURCE_OK |
| 授权加载/错误 | HTTP映射或 caught exception.message | HTTP及callback分类本地化；异常消息直接给Error | VM uiState | LV:207-213,322-330；L:291 | 空异常消息有通用fallback | T2覆盖HTTP/callback，未覆盖网络异常UI内容 | UNVERIFIED（transport异常文案/脱敏仍需检查） |
| 账号/session是否已登录 | exchange access_token/refresh_token/expires_in/user.id | `session != null`；不以过期时间直接清登录 | AUTH；LV:97-101 | L:94-99；NG:303 | 缺session进入登录；合法refresh401/403才清 | T5 late refresh/new login；T6导航条件 | SOURCE_OK（导航例外H04） |
| token刷新 | `POST v1/session/refresh`；same-account rotation | 单飞Mutex；只重试一次 `X-JourVolt-Retry` | AUTH，expected session + generation原子compare | RF:40-89；NetworkModule.kt:268-285 | 401/403清当前；传输/5xx保留；空token/expiry<=0拒绝 | T5 `concurrentOldRequestsCanUseOnlyTheSameRefreshRotation`、late unauthorized/success/cancel/redirect | SOURCE_OK（refresh store消费者，不涵盖logout H05） |
| 本地退出 | `POST v1/session/logout`，当前Bearer | 等最多3秒后比较整个旧session再clear | AUTH；请求generation | LV:360-383；SettingsScreen消费回调 | 远端失败仍拟清本地；并发rotation会直接return | T5只测refresher，不测真实logout；静态路径H05 | BUG H05 |
| 重新授权/取消 | 现有session + 新OAuth ticket | 保留旧会话，invalidate旧request；cancel不清session | AUTH + VM generation/commitLock | LV:387-409,294-321 | 取消保留原账号，迟到exchange不应覆盖 | T7 `loginViewModelPreservesSessionWhenPairingIsCancelled`为源码合同；缺真实VM竞态 | UNVERIFIED（关键逻辑可见，真实VM取消/commit竞态待跑） |
| 首次配置：正在检查/待收集/就绪 | `/cars`，telemetry pairing status config_synced/status/updated_at | bounded自动观察，非车主确认的configSynced不伪造配对动作 | onboarding store按accountId；VM generation | LV:611-633,698-751；L:142-205 | 无车blocked；临时错误pending；owner action不自动重试 | T8 `delayedConfirmationCompletesWithoutAnotherTap`、`explicitOwnerActionsAreNeverRetriedAutomatically` | SOURCE_OK |
| 首次配置：实际选中的车辆 | `/cars` row.car_id | 旧selected匹配否则取first配对，但未保存first | SR `current_car_id` 全局，默认1 | LV:627-633；SD:48-49；DV:289-305 | 旧ID不存在时Dashboard拒绝显示；多车没有可调用selector | 全main精确搜索：switchCar只有定义；H01完整触发链 | BUG H01 |
| 配对“打开Tesla”/返回 | pairing_required + virtual_key_url + updated_at | 只接受官方虚拟钥匙URL；launchPending；首次返回最多一次configure | TeslaOnboardingStateStore account/car/url/retryUsed/launchPending | L:328-365；LV:412-437,646-680,722-729 | 非官方/空URL不用任意链接；跳过保留登录pending | T7、T8；官方URL策略在readiness | SOURCE_OK（生产钥匙确认未观察） |
| 权限不足/计费/服务阻断与继续 | pairing/errorClass finite state | 本地化有限panel；continue允许pending首页 | onboarding store + AUTH | LV:771-800；L:370-412 | 不把未收集当0或注销；可重试 | T7:11-31、76-81；T8 transport bounded | SOURCE_OK |
| 进程重开起始页 | connectionMode + session + persisted onboarding account | SELF_HOSTED→Dashboard；cloud pending→Login；其余session→Dashboard | settings/instance/mode/AUTH/onboarding | SD:51-77 | 空session→Login，错account onboarding不使用 | T7源码合同；无本次设备重开证据 | UNVERIFIED（源码合理，设备恢复待证） |
| 登录后session失效路由 | session flow + initial startDestination | only initial Dashboard才redirect，fresh-login不满足 | startDestination为一次赋值 | NG:250-260,437-445,467-485 | cold-start Login→后续Dashboard→失效未导航 | T6所有正例startDashboard；H04 | BUG H04 |

### 2.2 首页基本信息、实时位置与状态

| 页面/可见字段或交互 | source / wire / DTO | 直传或计算 | 存储+作用域 | UI绑定 file:line | 空/零规则 | 现有具体证据 | 判定 |
|---|---|---|---|---|---|---|---|
| 顶部车名 | `/cars` CarData.name→displayName；缺则model/Tesla | 去空白fallback；未用status.display_name | LIVE，cars当前请求 | D:215-221；CM:29-32 | null显示fallback，不填别车名称 | T9 selected metadata/UID rebound | SOURCE_OK，受H01选车门约束 |
| 车型/性能/长续航副标题 | car_details.model/trim_badging→hero profile/vehicleTrimFor | 仅已知3/Y/S/X与trim信号映射 | LIVE | D:185-196,222-229,905-919 | unknown不冒充Model3/特定trim | T10四车型与未知；T11:86 | SOURCE_OK |
| 车色/轮毂/车型默认车图 | car_exterior.exterior_color/wheel_type + model/trim | resolveVehicleHeroProfile→VehicleHeroImage | LIVE，无实车图证据 | D:368-380 | 未知模型generic fallback | T10 model3Performance...、unknownModel... | SOURCE_OK（示意外观资源，不是实拍） |
| 自定义车图选择/重置 | Android photo picker inputStream→PH | scoped staged save/reset，不认领旧unscoped图 | PHOTO；DV activePhotoProof + current scope | D:106-118,374-380；DV:518-553 | 无照片用默认图；失scope丢弃迟到结果 | T12 同numeric不同账号/UID；rename recovery；failed staged retains prior | SOURCE_OK；真实picker重开未执行 |
| 车辆状态标签 | status.state | finite vehicleStateLabel | LIVE/CACHE | D:353-364,1125-1132 | null不画；unknown本地化 | T11驾驶state gate；无现场状态枚举证据 | SOURCE_OK |
| 来源/新鲜度badge | snapshot.source/observed_at/field_sources→SnapshotEvidence | 120秒/未来5秒fence；mixed标记；UI将RECENT/503当asleep | LIVE/CACHE | D:234-237,1035-1052；SE:31-36 | telemetry_mqtt未识别；RECENT误称休眠 | T13只覆盖live_mqtt/teslamate/mixed，漏cloud source与badge消费者 | BUG H06/H07 |
| 顶部观测时间 | snapshot.observed_at；cache observed_at | 设备时区MM-dd HH:mm | LIVE/CACHE | D:250-257；VP:114-117 | 空/不可解析隐藏；legacy status无时间 | T13 freshness（未测时区UI） | SOURCE_OK（时间是快照/receipt，不是全部字段传感器时间） |
| 主卡SOC数字 | battery_details.battery_level Int? | 直接百分号 | LIVE/CACHE；FP:312；BG:881-885 | D:440-466 | null `--`；真实0保留；数值显示未做0..100验证 | T14 JSON null/0/false；T15 SOC端点 | SOURCE_OK（上游非法域仍需负面测试） |
| SOC进度条 | 同上 | coerceIn(0,100)/100f | LIVE/CACHE | D:489-496 | null不画；真实0空条 | T14只DTO；未测实际Compose值 | SOURCE_OK |
| 续航数字/单位 | rated_battery_range→ideal→est | null优先回退；API已经preferred，不二转 | LIVE/CACHE；FP:314-316 cloud→km | D:432,474-485 | 三者null `--`；真实0保留 | T15 canonicalStoredMetrics...；H02 cache单位 | BUG H02（缓存回退）；在线SOURCE_OK |
| 当前SOC→目标SOC文案 | battery_level / charge_limit_soc | classifyBatteryCharge，缺current显示limit | LIVE/CACHE | D:498-511 | 缺target不算百分比目标；真实0需保留 | T14 null/0；未找到完整UI消费者证据 | UNVERIFIED（classify/渲染完整组合待验） |
| 锁状态 | car_status.locked Boolean? | nullable三态，图标+文案 | LIVE/CACHE；FP:308；BG:863 | D:518-527 | true已锁/false解锁/null未知 | T14 JSON 0/false/null；T11 flags | SOURCE_OK |
| 插枪状态 | charging_details.plugged_in | nullable三态；cloud由非Disconnected charging_state推导 | LIVE/CACHE；FP:360-363；BG:887 | D:528-537 | true/false/null分开 | T14；无实车多种charging_state样本 | SOURCE_OK（cloud推导口径需上游样本确认） |
| 空调状态 | climate_details.is_climate_on | nullable三态 | LIVE/CACHE；FP:336；BG:878 | D:538-547 | on/off/unknown分开 | T14 null/false | SOURCE_OK |
| 哨兵状态 | car_status.sentry_mode | nullable三态 | LIVE/CACHE；FP:309；BG:864 | D:548-557 | armed/off/unknown分开 | T14 null/false | SOURCE_OK |
| 车门开启提醒 | car_status.doors_open | only true→openVehicleOpenings.DOORS | LIVE/CACHE；FP:347 anyOpen | D:560-561,923-929；VP:81-87 | null/false不报开启，不声明已关闭 | T11 openingPanelContainsOnlyTheFourAllowedOpenings | SOURCE_OK |
| 车窗开启提醒 | car_status.windows_open | only true→WINDOWS | LIVE/CACHE；BG:864；Fleet mapper未赋WindowsOpen | D:560-561；VP:84 | null不画；cloud provider无对应赋值，不能推断关闭 | T11允许WINDOWS；无上游观测 | SOURCE_DATA_MISSING（当前Fleet路径结构性未接入；生产实际未知） |
| 前备箱开启提醒 | car_status.frunk_open | only true→FRUNK | LIVE/CACHE；FP:348；BG:866 | D:560-561；VP:85 | null/false不报开启 | T11四opening | SOURCE_OK |
| 后备箱开启提醒 | car_status.trunk_open | only true→TRUNK | LIVE/CACHE；FP:349；BG:865 | D:560-561；VP:86 | 同上 | T11四opening | SOURCE_OK |
| 总里程 | status.odometer | preferred-unit直格式化 | LIVE/CACHE；FP:311 miles→km；BG:861 | D:573-580 | null `--`；0保留；不拿历史和替代 | T15 raw preferred单位；H02 | BUG H02（缓存单位）；在线SOURCE_OK |
| 位置文本：围栏 | car_geodata.geofence | 优先trim非空geofence | LIVE/CACHE；cloud BG:870显式nil | D:584-593；VP:102-121 | 缺围栏用地址/坐标；不伪造住址 | T11 location precedence；无生产围栏观测 | SOURCE_DATA_MISSING（cloud无围栏为可选能力，fallback正常） |
| 位置文本：地址 | live经验证GPS→Amap reverse，再本地geocoding cache | addr→城市/区域/国家组合 | geocoding cache按坐标；发布再检查scope/vehicle/generation | DV:374-380,595-620；D:584-593 | 无地址用坐标；不复用旧车位置 | T16 snapshot identity谓词；无真实挂起geocode VM测试 | UNVERIFIED（源码fence可见，真实消费者竞态待验） |
| 位置文本：经纬度/配置提示 | car_geodata.latitude/longitude | 4位坐标；无GPS提示readiness/未配Amap提示setup | LIVE/CACHE | D:583-607；VP:107-111 | UI只检查非null；geocoder有合法域/0,0过滤 | T13 sentinelCoordinateRejected只测helper；未测试此UI旁路 | UNVERIFIED（0,0/越界UI旁路须补负面用例） |
| 首页地图点 | 同一status latitude/longitude | AmapPointView直接消费 | LIVE/CACHE | D:678-687 | 任意非null都挂载；合法域检查未在本页调用 | T13 helper；map真实SDK未运行 | UNVERIFIED（上游严格过滤与UI防御需联合验证） |
| 车内温度 | climate_details.inside_temp | preferred °C/°F格式化1位 | LIVE/CACHE；FP:337；BG:878 | D:614-620 | null等待车端；真实0保留 | T14 DTO；T15 raw不二转；H02 | BUG H02（缓存单位）；在线SOURCE_OK |
| 车外温度 | climate_details.outside_temp | 同上 | LIVE/CACHE；FP:338；BG:879 | D:622-628 | 同上；不造温度趋势点 | 同上 | BUG H02（缓存单位）；在线SOURCE_OK |
| 左前胎压 | tpms_details.tpms_pressure_fl | preferred pressure→1位 | LIVE/CACHE；FP:350；BG:901 | D:642-648 | null readiness占位；0显示真实0 | T11 pressureFormattingUsesTheReportedUnit；T17 raw collector | BUG H02（缓存单位）；在线SOURCE_OK |
| 右前胎压 | tpms_pressure_fr | 同上 | LIVE/CACHE；FP:351；BG:901 | D:650-656 | 同上 | T11、T17 partialPressureChange... | BUG H02（缓存单位）；在线SOURCE_OK |
| 左后胎压 | tpms_pressure_rl | 同上 | LIVE/CACHE；FP:352；BG:902 | D:658-664 | 同上 | T11、T17 | BUG H02（缓存单位）；在线SOURCE_OK |
| 右后胎压 | tpms_pressure_rr | 同上 | LIVE/CACHE；FP:353；BG:902 | D:666-672 | 同上 | T11、T17 | BUG H02（缓存单位）；在线SOURCE_OK |
| 左前胎压警告 | tpms_soft_warning_fl | only true→warning tint | LIVE/CACHE；warning独立receipt不借pressure receipt | D:647；VP:93 | false/null不告警，不推导压力阈值 | T11 tpmsWarningsRemainSeparate...；T17独立warning时钟 | SOURCE_OK |
| 右前胎压警告 | tpms_soft_warning_fr | 同上 | 同上 | D:655；VP:94 | 同上 | T11、T17 | SOURCE_OK |
| 左后胎压警告 | tpms_soft_warning_rl | 同上 | 同上 | D:663；VP:95 | 同上 | T11、T17 | SOURCE_OK |
| 右后胎压警告 | tpms_soft_warning_rr | 同上 | 同上 | D:671；VP:96 | 同上 | T11、T17 | SOURCE_OK |
| 胎压/温度历史入口 | selected carId + exteriorColor route | 不拿当前温度或压力造趋势；只导航 | route使用当前numeric car | D:613,636-640 | 历史为空与当前有值可共存 | T18轮询不写伪历史；T17当前原始TPMS有资格时保存独立receipt | SOURCE_OK（历史详情交由对应审计） |
| 数据就绪引导/胎压缺值说明 | getDataReadiness(effectiveCarId) | 有verified UID才读取/显示；hasSeen current readiness | DataReadinessStore account/server/car/vehicle/source/collection identity | DV:406-427；D:132-160,645 | 请求失败不填0；缺pressure readiness文案 | T17无额外HTTP；无当前readiness endpoint样本 | UNVERIFIED（endpoint实证未取） |

### 2.3 驾驶/充电、刷新与空态

| 页面/可见字段或交互 | source / wire / DTO | 直传或计算 | 存储+作用域 | UI绑定 file:line | 空/零规则 | 现有具体证据 | 判定 |
|---|---|---|---|---|---|---|---|
| 驾驶HUD显示门槛/折叠 | state==driving OR speed>0；isHudDismissed | state/speed判断；折叠仅改VM布尔 | LIVE/CACHE，VM保留 | D:264-275,282-314 | 驾驶state可在speed null时进入 | T11仅测主卡helper非HUD；H03 | BUG H03（门槛可达缺速分支） |
| 驾驶HUD速度 | driving_details.speed | formatSpeed preferred，null→0.0 | LIVE/CACHE | D:1230 | 缺失被伪装0 km/h/mph | T14只DTO；H03具体反例 | BUG H03 |
| 主卡驾驶速度 | 同一speed，要求state=driving | 非null才加指标strip | LIVE/CACHE | D:383-393；VP:45-53 | null隐藏；真实0保留 | T11 drivingMetricsAppearOnly... | SOURCE_OK（受H02缓存单位影响） |
| HUD/主卡挡位 | driving_details.shift_state | trim uppercase D/R/N/P→本地化 | LIVE/CACHE；FP:340；BG:874 | D:411-424,1242-1269 | unknown/null不伪造P | T11 shiftState/驾驶telemetry | SOURCE_OK |
| HUD/主卡功率、回收方向/颜色 | driving_details.power signed kW | abs显示，正consuming/负regenerating/0steady | LIVE/CACHE；FP:341；BG:874 | D:395-408,1273-1288 | null隐藏，真实0保留，负号由回收文案表达 | T11负功率/0；T14 DTO | SOURCE_OK |
| 充电HUD显示/折叠 | charging_details.charging_state==charging | 忽略大小写派生isCharging；驾驶HUD优先 | LIVE/CACHE | D:265-281 | null不伪装充电；可真实charging但其他字段null | T14；H03 | SOURCE_OK（子字段H03） |
| 充电HUD功率 | charger_power Double? | 1位kW，优先Double accessor | LIVE/CACHE；FP:331；BG:895 | D:1338-1344 | null隐藏；0保留 | T14 chargingSnapshotKeepsDecimalValues... | SOURCE_OK |
| 充电HUD充入电量 | charge_energy_added Double? | 1位kWh，null→0.0 | LIVE/CACHE；FP:318；BG:888 | D:1364-1366 | 缺失伪0；底部卡却显示-- | T14精度/JSON不覆盖HUD；H03 | BUG H03 |
| 充电HUD电压 | charger_voltage Double?→rounded Int accessor | roundToInt V | LIVE/CACHE；FP:332；BG:895 | D:1378-1383；CM:116-117 | null整列不画；0保留；小数电压舍入 | T14原值精度；真实HUD精度未验 | SOURCE_OK（显示整数为当前规格） |
| 充电HUD电流 | charger_actual_current Double?→rounded Int | null→0 Int，与电压配对显示 | LIVE/CACHE；FP:329；BG:894 | D:1379-1383；CM:118-119 | 有电压无电流会显示0A | T14不覆盖消费者；H03 | BUG H03 |
| 充电HUD剩余时间 | time_to_full_charge hours Double? | hours*60取整，>=60拆h/m | LIVE/CACHE；FP:335；BG:898 | D:1395-1407 | null隐藏；0m可真实0；<1min截断0m | T14模型字段；未找到时长边界测试 | SOURCE_OK（负值/极端值待上游负面验证） |
| 底部充电卡功率 | charger_power | 1位kW | LIVE/CACHE | D:725-753 | null `--`；真实0保留 | T14小数精度 | SOURCE_OK |
| 底部充电卡充入电量 | charge_energy_added | 直接Double kWh | LIVE/CACHE | D:756-758 | null `--`；真实0保留，与HUD H03冲突 | T14 DTO | SOURCE_OK |
| 底部充电卡剩余时间 | time_to_full_charge | 原hours+`h` | LIVE/CACHE | D:760-762 | null `--`；0保留，与HUD时分是格式差异 | T14 DTO；无双卡快照比较测试 | SOURCE_OK |
| 电池趋势入口 | selected carId/exteriorColor→Stats | 只导航，不画模拟电量历史 | route，无合成缓存 | D:691-723 | 缺历史不会用当前SOC合成曲线 | T18轮询不合成历史 | SOURCE_OK |
| 部分数据页车名/车型/trim/车色/轮毂 | 当前/cars元数据；status=null | 显示metadata + localized error category | LIVE，getCars失败car=null | D:772-859,861-871 | 缺字段 `-`；无状态不填实时0 | T9 metadata；T19 error映射 | SOURCE_OK |
| 部分数据页总行程/总充电 | teslamate_stats.total_drives/total_charges | 整数直传 | LIVE，当前cars row | D:874-883 | null `-`；真实0保留 | 无真实云端统计输出样本 | UNVERIFIED（上游可选统计） |
| 部分数据页来源/刷新/设置 | 本地化静态source说明、refresh/loadDashboard | 不把ping/readyz成功当状态成功 | VM触发网络/worker | D:887-899 | 不拼接任意服务端error正文 | T19 unknownFailureUsesSafeGenericCopy | SOURCE_OK |
| 手动刷新 | refresh→triggerDataSync + loadDashboard | generation++；unique worker KEEP | LIVE；CACHE仅qualifiedUID | D:120-129；DV:274-443,564-592 | status unavailable 可用同scope已存状态；cars失败不能读缓存 | T16身份谓词；T20 scoped cache；见下节限制 | UNVERIFIED（离线优先/真实并发刷新未跑） |
| 轮询更新/失败保留 | snapshot→legacy fallback；8/15/60/10秒 | raw status直接替换，不merge旧GPS，不写snapshot历史 | LIVE；请求scope/generation/currentcar fence | DV:448-515 | failure保留当前status但下调freshness；不制造0 | T18源码合同；T16纯谓词；缺真实VM轮询竞态 | SOURCE_OK（source mapping见H06） |
| 身份变化清空旧UI | VC.historyScopeChanges，含session generation | 清StateFlow、照片proof、++generation、reload | LIVE+scopedCACHE | DV:195-205 | 新身份不可保留旧车字段；无session时会卡defaultLoading见H04 | T16 delayedAlice...为抽取策略测试；T20 cache跨账号 | BUG H04；跨账号scope隔离源码基本正确 |

## 3. 可行动的确定性发现

### H01 — P1：实际首车没有写回全局selected car，导致新账号/多车首页与所有主导航仍请求旧ID

- 位置：`SR:23,31,45-46`，`LV:627-633`，`DV:280-305`，`SD:48-49`，`android/app/src/main/java/com/matelink/ui/navigation/MateLinkNavHost.kt:80-85`。
- 触发：干净安装默认selected=1；当前账号 `/cars` 返回第一辆ID=2（或旧账号selected=7，新账号只有8）。Onboarding选first为2并完成pairing；没有任何 `setCurrentCarId(2)`。Dashboard只查 `cars.find(id==1)`，成功车单中缺1就 `vehicle_not_found` 并提前返回。主导航仍使用1。
- 不是假定server永远1：`BG:101-113` 的车辆ID为全表 GENERATED IDENTITY；`FP:206`公开 `stored.ID`。全 `android/app/src/main` 搜索 `switchCar`（包括方法引用及无括号文本）只有 `DV:557` 定义；`setCurrentCarId` 唯一生产调用在该未绑定方法 `DV:559`。当前没有UI可达的更换车辆入口。
- 最小非布局修复：在经过当前scope/generation验证的成功 `/cars` 后，若旧selected不在当前列表，选择有效车并原子持久化；prefer按 origin/account/UID保存选择，而不是长期复用跨账号numeric ID。现有导航用持久化后的同一个ID。若多车选择已有产品规格，应绑定现有控件/流程，勿擅增布局。
- 验证门：真实VM或组件集成测试覆盖首车2、旧账号7→新账号8、选车保存期间旧请求返回；首页、Drives、Charges、More路由一致。已有T9/T16并不覆盖此写回缺口。

### H02 — P1：缓存只保存数值，丢失同一响应单位，降级回退把mi/°F/psi标成km/°C/bar

- 位置：`VS:26,73-79` 只序列化 `CarStatus`；`DV:339-350,397` 状态失败时读取cachedStatus，但units落到 `Units()`。`VF` raw格式化只按units贴标签，空units默认metric。
- 触发：自托管legacy接口曾返回100mi里程、70°F、35psi并成功缓存；后续 `/cars`仍成功、snapshot与status都不可用（如车辆休眠/状态endpoint故障）。同scope cacheVerified=true，cachedStatus显示原数字100/70/35，units却空，变成100km/70°C/35bar。错误影响续航、里程、温度、胎压及可能的驾驶速度。云端当前固定km/bar/C不触发此具体换标签，但不消除self-host兼容缺陷。
- 最小非布局修复：将status、units、source、observedAt作为一份版本化scoped receipt持久化/读取。旧无units缓存不能猜单位；未知时保持未可用。不可用后来别次响应的units给旧数值重贴标签。
- 验证门：同scope写入imperial receipt→重开→cars成功/status失败→实际Dashboard消费者仍为mi/F/psi；metric对照；缺units旧缓存；账号/UID/origin互不读取。

### H03 — P1：可达HUD分支将未知速度、充入电量、电流伪装为观测0

- 位置：`D:264,269-281,1230,1364-1366,1378-1383`；DTO来源字段均nullable：`CM:168,110,118-119,203-208,229-242`。
- 触发A：`state=driving` 且 `driving_details.speed=null`。HUD挂载使用state OR speed>0，因此仍挂载并显示0km/h。主卡strip `D:385`却正确隐藏null速度。
- 触发B：`charging_state=Charging`、非驾驶、HUD未折叠，`charge_energy_added=null`→0.0kWh；`charger_voltage=230`、`charger_actual_current=null`→230V/0A。底部充电卡 `D:758`对同一null energy显示 `--`。
- 最小非布局修复：保留nullable，使用现有未知占位/读屏语义；实际0继续显示0。电压/电流分别格式化，不由一侧存在推断另一侧0。保持当前参数位置和控件尺寸。
- 验证门：直接挂载真实Dashboard/HUD的null、0、非零三组，要求主卡、HUD、底部卡一致；单纯JSON DTO测试T14和helper测试T11不能关闭此门。

### H04 — P1：从登录页启动后会话失效不会自动返回登录，可能停在无限首页加载

- 位置：`SD:72-77` initial destination只赋值一次；`NG:256-259` redirect硬依赖 `startDestination==Dashboard`；登录成功只导航 `NG:437-445`；会话清理 `RF:71-74`。
- 触发：冷启动无session → startDestination=TeslaLogin → 成功登录并到Dashboard → 同一进程refresh返回401/403使session=null。当前在Dashboard，但startDestination仍TeslaLogin，redirect谓词false。`DV:197-204`收到scope变空后清成默认 `isLoading=true` 并reload；captureScope失败得null，`DV:318/372/432`的liveScope fence会拒绝发布结束态。`D:164-166`因此保持加载页。
- 最小非布局修复：失效导航依据**当前route/connectionMode/session是否存在**，而不是初始路由。明确保留重授权窗口的suppress规则。scope不可用的Dashboard需结束本地loading并等待导航，不发布旧数据。
- 验证门：initialLogin→login→Dashboard→authoritative失效；initialDashboard失效；selfhost无session；reauthorizing取消四条真实Nav/VM消费流程。T6目前未覆盖initialLogin路径。

### H05 — P1：退出等待期间同账号token rotation会让退出直接放弃，本地仍登录

- 位置：`LV:360-383` 捕获完整session后等待远端；`LV:377-380`要求 `sessionStore.current()==session`；`RF:85`通过`SS:56-62`合法rotation必然替换完整session。
- 触发：按退出时有Dashboard轮询/其他读取正在refresh；logout先捕获S，等待remote最多3秒；refresh先成功将同账号token换成S'。logout返回后 generation仍本次有效，但 `current()!=S`，直接return，既不clear也不调用onComplete。账号并未换人，用户退出操作却失效。deleteAccount `LV:497-499`有相同完整session比较，成功远端删除与本地清理可分离。
- 最小非布局修复：明确区分**账号/登录身份世代**与同一身份的token rotation。退出的本地失效应在一个原子session-store操作中线性化并使旧refresh不可再写；保留新登录不被旧logout清掉的fence。不要简单删除session检查，也不要非原子“检查后无条件clear”。
- 验证门：实际TeslaLoginViewModel logout + 受控refresher并行；同账号rotation后必须完成退出；较新登录不得被旧退出清理；remote timeout同样本地退出；deleteAccount成功后本地session结果一致。T5证明store refresh自身防护，未测logout调用方。

### H06 — P2：正式cloud telemetry source字符串与Android freshness分类不一致

- 位置：`TH:310-325`、`TS:414-415`、`deploy/jourvolt-dev-mock/telemetry_store.go:474`发布 `source=telemetry_mqtt`；`BG:913-915`原样输出；`SE:31-36`只识别live_mqtt/fleet_api/mqtt_latest/database_latest/teslamate_api/mock_fixture，遗漏telemetry_mqtt。
- 触发：正常新鲜cloud telemetry overlay/snapshot，source=telemetry_mqtt，有真实字段及时间。Android数值照常显示，但freshness落UNAVAILABLE，首页来源badge显示不可用，造成“数据未打通”的错误呈现。
- 最小非布局修复：在共享source分类接受明确的telemetry_mqtt语义并按observedAt判新鲜/最近，保留source原值及field_sources；不把任意未知source都视为live。
- 验证门：生产mapper构造telemetry_mqtt快照→JSON→生成Moshi→Dashboard source/badge；新鲜/过期/未来/混合source分别断言。T13只验证另一条selfhost MQTT命名。

### H07 — P2：最近数据或服务不可用被错误显示成“车辆休眠”

- 位置：`D:234-237` 把 `SERVICE_UNAVAILABLE` 或 `SnapshotFreshness.RECENT`都纳入isAsleep；`D:1039,1043`直接用snapshot_source_asleep。资源 `android/app/src/main/res/values-zh/strings.xml:208,212`已有区分“最近状态”与“车辆休眠”。
- 触发：在线车辆收到最近MQTT缓存，或provider503且保留旧数据；没有观测到 `state=asleep`，UI仍显示“车辆休眠”，并且isAsleep使mixed标记 `D:1052`被隐藏。服务故障/旧数据不能证明车睡了。
- 最小非布局修复：只有实际state=asleep才显示休眠；RECENT使用已有recent资源；service unavailable保持已知车态并用现有来源/错误状态表达。布局和参数顺序不变。
- 验证门：state=online+RECENT、state=driving+503、state=asleep、mixedRecent四个真实badge消费者断言。T13/T11策略测试未覆盖此UI错误映射。

## 4. 上游缺失、可选能力与未观测证据分开

- **源码结构已明确缺失/可选**：cloud `BG:870` geofence始终nil；Fleet `FP:302-359`未映射WindowsOpen，typed vehicle_state亦未解码window字段。geofence是可选能力，已有地址/坐标fallback；windows缺值只能代表未知，不能说“车窗已关”。两者均未核实生产样本，不能从源码推定Jovi实车正缺哪一项。
- **来源时间不是传感器时间**：Fleet `FP:304`使用服务收到响应时的now，cloud `BG:860` state_since=nil；TPMS DTO另带独立pressure/warning collector receipt时间（`CM:275-290`），collector严守source/unit/time/current UID。顶部快照时间不证明内外温度、所有胎压、SOC同时采样，当前首页也未逐字段显示age。需要实测样本关联，不应把快照fresh标成逐字段绝对新鲜。
- **当前有值不证明历史已经采集**：DV完整loader会调用合资格TPMS collector `DV:334`；polling故意不写snapshot history（T18）；温度、胎压趋势是否已持久化与同车历史读取正确，由趋势审计与真实Room证据分别关闭。不能拿live值补造过去曲线。
- **持久缓存并不等于完整离线首页**：Dashboard的cacheContext需要本次 `/cars`成功提供当前UID（`DV:335-339`）。完全离线cars失败时即使存在旧scoped cache，也不会读取，此时回到partial/no-data。该策略避免numeric ID越权，但尚不符合“已验证本车缓存0ms先呈现”的项目体验目标。可评估复用明确origin/account/UID持久proof；不能撤销identity校验来换离线体验。标为待确认产品/持久消费者门，未另虚报为数据丢失。
- **现场门均未执行**：没有当前实车/SOC/速度/充电/胎压/温度/GPS响应、设备截图、恢复后持久值、账号切换录屏或生产最新版本证据。源码中的mock fixture不会被本文算成正式车辆数据。网络连接成功、健康检查或继承单测不能关闭这些门。

## 5. 现有测试证据索引（只读源码，本次均未运行）

以下均在 `android/app/src/test/java/com/matelink/`：

| ID | 文件及关键已有断言 | 实际覆盖边界 |
|---|---|---|
| T1 | `ui/screens/auth/TeslaAuthNavigationContractTest.kt:10,58,74,119,133,152` | 多为source.contains合同；浏览器/原Dashboard/consent/source-set/deletion URL存在，不能替代真实Nav/VM |
| T2 | `ui/screens/auth/TeslaLoginErrorMappingTest.kt:9,18,23` | HTTP/callback有限错误映射 |
| T3 | `ui/screens/auth/TeslaCallbackSecurityTest.kt:14,24,32,40,50` | 官方endpoint、错误host/scheme/path/redirect/scope |
| T4 | `ui/screens/auth/TeslaCallbackReplayGuardTest.kt:10,21,32,43,54,67,80` | ticket重放策略；不是持久化服务器exchange实测 |
| T5 | `data/local/JourVoltSessionConcurrencyTest.kt:109,119,133,157,181,204` | Store/refresher真实受控并行：不跨账号retry、单rotation、迟到成功/401、取消、redirect；不含LV.logout |
| T6 | `ui/navigation/SessionExpiryNavigationTest.kt:10,22,34,46` | shouldRedirect谓词；缺initialLogin→login→expire |
| T7 | `ui/screens/auth/TeslaPostLoginOnboardingContractTest.kt:11,34,51,76` | 现有source合同；配对先于成功、取消保留session、recheck配置 |
| T8 | `ui/screens/auth/AutomaticTelemetrySetupTest.kt:12,24,31,40` | bounded策略、owner action、transport重试、account change |
| T9 | `ui/screens/dashboard/DashboardCarMetadataTest.kt:9,15,26` | 选中metadata enrich、UID rebound失效；未覆盖current_car_id持久化 |
| T10 | `domain/model/VehicleHeroProfileTest.kt:9,24,31` | 四车型、颜色轮毂性能信号、未知generic |
| T11 | `ui/screens/dashboard/VehicleStatusPresentationTest.kt:17,35,54,67,73,81,86` | helper的驾驶零/负功率/openings/TPMS警告/单位/地址优先级；未挂载HUD |
| T12 | `data/local/VehiclePhotoStoreScopeTest.kt:31,42,55` | 同numeric跨账号/UID、rename recovery、失败保旧图 |
| T13 | `domain/telemetry/SnapshotEvidenceTest.kt:9-13` | selfhost source/时间/mixed/sentinel helper；无cloud telemetry_mqtt/badge |
| T14 | `data/api/models/CarStatusMqttPrecisionTest.kt:12,29` | decimal、scheduled、generated DTO null/0/false；UI旁路不在范围 |
| T15 | `domain/model/UnitFormatterTest.kt:9,19,27,40,45` | canonical vs raw preferred、SOC0、能耗/海拔；未测cache单位上下文 |
| T16 | `ui/screens/dashboard/DashboardStatusIdentityTest.kt:21,38` | 抽取dashboardStatusMayPublish策略+延迟协程；不是完整DashboardViewModel |
| T17 | `ui/screens/dashboard/DashboardTpmsReceiptCollectorTest.kt:33,56,85` | 真实collector/Room fixture，raw receipt source/unit/time/scope，独立warning时间，dedupe与无额外HTTP |
| T18 | `ui/screens/dashboard/DashboardHistoryWriteContractTest.kt:9` | 源码合同：polling不造snapshot历史 |
| T19 | `ui/screens/dashboard/DashboardErrorMappingTest.kt:10,18` | finite errorKind→safe localized copy |
| T20 | `data/local/VehicleStatusStoreScopeTest.kt:29,42,56,64,84,98,127` | 同numeric隔离、durable missingGPS、不认领legacy、旧GPS原时间、UIDdigest与selfhost消费者；未保存units |

## 6. 发布门与审查结果

- [ ] 修复并用真实消费者回归关闭H01/H02/H03/H04/H05；当前为P1发布阻断项，不因继承build1356全绿而关闭。
- [ ] 修复H06/H07并以服务端实际source字符串走JSON→DTO→Dashboard断言，不能只测未被UI用到的分类helper。
- [ ] 在最终固定源码对应包验证cold login、existing login、authoritative session expiry、remote logout timeout、同账号rotation与新账号重登竞态、source/vehicle switch；包含原 `com.matelink` 既有数据/配置不丢失。
- [ ] 用同车/同账号/同origin的非1 ID验证首页和每个主导航；多车选择路径须有可达证据。
- [ ] 用same receipt验证metric/imperial在线→状态失败→重开；null与真实0逐位置检查（主卡/HUD/底部卡/四轮）。
- [ ] 当前实车/受控provider数据分别核实SOC、续航、里程、行驶速度/负功率、充电状态/功率/累计kWh/剩余时间、内外温度、四轮压力及其独立time/source、GPS/地址/地图；缺事件保持未收集，不猜零。
- [ ] 当前scope确认后先呈现持久缓存的体验与完全离线策略，需要同身份proof与真实VM/重开验证；不得牺牲UID/origin/account隔离。
- [ ] 重新核验本次最终包的所有已覆盖页面可见像素和最长未知文案，证明只改逻辑且布局/参数排列保持现有版本；本审计没有提供像素验收。

审查结果：发现 **5类P1消费者缺陷、2类P2来源/状态语义缺陷**；未发现可独立确认的P0。其余参数存在静态通路不等于当前生产观测齐备。上游可选缺失、历史采集、生产/设备验收均按独立门保留，不能宣称全链已通过。
