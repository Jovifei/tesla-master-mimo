# MateLink 完整用户旅程与真实数据链路测试方案

版本：QA-20261008-v1。交付对象：Jovi、本地 Codex、远端代码审核者。

## 0. 本轮的性质和结论边界

这是依据实际仓库关键路径制定的测试规范，不是测试结果，也不是此前完整实现阶段的完成声明。本次文档提交不改变 Android、Go、数据库、bridge 或部署配置。所有用例初始状态均为 NOT_RUN。用户现在可以开始分层资格测试；不能因为页面看起来正常、旧 CI 通过或安装成功，就宣布可靠数据链路和自然通知已经验收。

最终产品目标：不同用户通过官方授权和车辆确认，获得彼此隔离、来源真实、及时可恢复的车辆、行程与充电数据，并在手机正确显示和提醒。个人 TeslaMate 历史是独立归档，绝不能证明 Fleet 首事件、多用户接入或新的自然通知。

本地 Codex 负责独立审查、复现、测试、编译、签名核验、授权范围内安装，以及通过 GitHub 回传整包证据；不接管业务源码修复。失败交远端按精确 SHA 修复。测试扩展只能是隔离测试/外部测试夹具，必须记录其内容哈希和测试差异，不能用测试替身替换被测业务路径后声称产品通过。

## 1. 固定版本元组，禁止混用两个源码底座

唯一仓库：Jovifei/tesla-master-mimo。

| 项目 | 本轮指定值 |
| --- | --- |
| 阶段分支 | codex/reliable-data-stage-20261007 |
| Android 被测源码 | 14ca59d54ba8c470abb45d01798a23919f47bb63 |
| Android tree | a4c439fd7101646b69a31f9644c8f9b5316c9647 |
| Android parent | 2d1828be6a5e3021951a2dca8d3637a69ecae491 |
| 源码声明版本 | 2.1.26 / versionCode 45 |
| API 被测产品源码 | bb09fac04d11796ce676555dad094776cd1ef0ce |
| API tree | e06e83b2dde8ba40601f7a931821f7a6c59d360f |
| API parent | 36d630a8ba72f40b72628294b5bba43f4efd9724 |
| 完整阶段范围文档 | docs/REMOTE-FULL-DATA-STAGE-MANDATE-20261007.md @ f61b484fdc574e8320dea59664aef623500caefe |
| 既有验证记录 | docs/REMOTE-PRIMARY-WORKFLOW-VERIFICATION-20261007.md @ a601c011c21cb7063f7740be40a1f37eef7f1f75 |

文档 SHA 和产品源码 SHA 是不同概念。读取本方案后，Android 从 14ca59d 完整提交建立独立检出；API 从 bb09fac 完整提交建立另一个独立检出。不得把手机树的 Go 目录覆盖已部署 API，也不能把 API 树覆盖手机修复。无需为文档变化重新安装 App。

执行前只读核验实际手机 APK/版本/构建来源、API build/image、bridge 来源与配置摘要。只输出安全的版本、哈希和匹配状态，不输出凭证。若实际版本不同，建立新的被测版本元组并明确差异；本方案中的源码发现只对上述固定版本成立。无法证明 APK 来源时标为 SOURCE_UNVERIFIED，不能仅凭同版本号认定是相同代码。

既有 656、282 等历史测试计数只能作为既有记录，不得复制成本轮结果。本轮重新记录 run/pass/fail/skip 及命令退出码。

## 2. 安全边界与测试环境

### 2.1 必须先完成的保护

1. 保留 Owner dirty 工作、现有历史、照片、设置、日志和备份。只读记录工作树状态；在新目录创建独立 worktree 或同仓库独立克隆，不 reset/clean/stash 现有工作。
2. 禁止在保存用户数据的手机上执行 instrumentation、卸载、pm clear、清日志、强制降级或故障注入。首次安装、迁移、进程强杀、权限组合和损坏数据库用一次性模拟器/专用测试设备。
3. 禁止使用生产 DATABASE_URL 运行测试或启动候选 Go 服务。main.go 的 openStore 启动路径会执行建表/ALTER 和 ensureTelemetrySchema；所谓“只启动一下检查健康”也不等于只读。
4. 云模式 SyncRepository.syncCar 包含 uploadLocalHistory；TPMS 成功处理路径含 90 天清理。二者不能作为生产只读测试入口。对真实设备仅观察既有正常使用行为；不手动触发相关写入/清理来取证。发现既有保留策略风险，记录并交远端，不擅改设备设置或历史。
5. 所有合成事件、分页异常、故障注入、历史导入、迁移、恢复和压测只在独立测试数据库、内部测试 MQTT、隔离账号和测试包中进行。测试服务仅使用新建隔离资源，不借用现有生产容器、端口绑定或卷。
6. 不唤醒车辆，不下发驾驶/充电/胎压操作，不为了验收新增真实行程、充电或放气。自然验证只利用 Jovi 和其他明确同意用户原本会发生的正常使用。
7. 官方登录、同意条款、车辆确认、通知授权由本人完成；不请求 Tesla 密码/MFA/token。测试不得自动同意，不触碰其他真实用户。
8. 生产 DB 写、生产 bridge 升级、配置变更、六条旧 SOC、7113 条历史胎压回填仍是独立精确授权门。本测试计划不是这些操作的授权。
9. 不改 Jovi 的网络、VPN、代理、DNS、TTL；弱网/断网/证书异常用测试传输层或一次性模拟器。真实手机仅记录已有网络状态。
10. GitHub 是唯一代码/证据桥梁。本仓库为公开仓库，上传前人工/程序脱敏；不得上传原始轨迹、VIN、账号、设备序列号、完整系统通知、token、URL 查询凭证、私钥、keystore、私密配置或原始数据库。

### 2.2 环境分层

| 层 | 环境与允许动作 | 不能证明的事情 |
| --- | --- | --- |
| S | 固定源码、路由/调用图、静态检查 | 运行成功、真实时延 |
| U | JVM/Go 单元、虚拟时钟、合成夹具 | 实车来源、真实系统通知 |
| I | 一次性 PostgreSQL 16、假 provider、隔离 MQTT、真实业务 handler | 生产部署、真实授权 |
| D | 隔离 Android 包/模拟器或专用设备的 UI、权限、重启测试 | 正式包/实车自然事件已验收 |
| R | 当前正式签名包、用户批准的正常操作与只读取证 | 全部用户/所有系统版本可靠性 |
| N | 可关联的自然 Fleet/归档事件及实际手机展示/通知 | 其他来源的验收，不可相互替代 |

Android 覆盖源码支持的 API 26 边界、API 33 通知授权边界、API 35 目标环境，以及 Jovi 实际手机 OS/厂商；新增更高系统版本按实际设备补测。Debug 和 Release 分开统计。模拟器不覆盖所有厂商后台行为。

合成租户至少 A/B，各两辆车；故意让不同租户存在相同外部车辆编号、相同行程/充电编号、相同时间戳，并使 localHistoryCarId 与 remoteApiCarId 不相等。来源至少区分 Fleet、TeslaMate archive、mock_fixture、legacy_unverified。真实第二用户缺失时，多用户自然接入标 PENDING_HUMAN，不用两组模拟账号冒充。

## 3. 已审查的实际路径与优先风险

下列路径为本次源码读取结果，不表示整库已完成逐行审计。Android 路径前缀均为 android/app/src/main/java/com/matelink/；Go 路径前缀为 deploy/jourvolt-dev-mock/。文件以第 1 节对应完整 SHA 为准。

| 编号 | 源码依据与路径 | 结论级别 | 对应用例 |
| --- | --- | --- | --- |
| R01 | ui/screens/auth/TeslaLoginViewModel.kt：条款/隐私门、可信授权 URL、callback/ticket、账号与 requestGeneration、pairing 状态 | 已有实现，仍需完整回调/竞态测试 | A01–A09 |
| R02 | data/repository/UnifiedHistoryRepository.kt：resolveContext → 本地 drives/charges → 全部 drives 页 → 全部 charges 页 → persistDrives → persistCharges → 返回 | 充电返回耦合行程读取/写入是源码事实；未证明它就是此前真实手机卡住的根因 | S05–S09、C01 |
| R03 | ui/screens/charges/ChargesViewModel.kt：发布前还等待两个 AggregateDao 查询；默认短充电过滤将 eligible 记录的未知能量按 0 处理 | 有 SOC 但 energy=null 的记录可能被默认列表隐藏；DAO 空结果还需检查 AC/DC 是否错误降级 | C02–C05 |
| R04 | data/sync/SyncRepository.kt：DriveSummarySyncRunner 完整分页后 record(kind=drive)；充电完整分页后 record；系统成功后 consume(system=true) | 完成事件已有接线，但后台调度到此路径、晚到与中断恢复没有实机证明 | N01–N12 |
| R05 | data/local/CompletedChargeEventStore.kt：DataStore 持久状态，system/app 两个消费标记，baseline/cutoff/seen | 存在持久机制，不等于完整去重/恢复已通过 | N02–N08 |
| R06 | data/sync/ChargingNotificationWorker.kt：30 秒延迟一次性检查 + 15 分钟周期兜底，处理当前充电/哨兵 | 不是完成行程同步的等价证明；注释里的间隔不是送达上限 | N09、N10、B04 |
| R07 | ui/screens/dashboard/DashboardViewModel.kt：状态按 8/15/60/10 秒轮询；refresh 显式触发一次性 DataSyncWorker | 首页状态刷新不等于历史完成事件已同步。必须追踪所有自动入队点 | S01、N09 |
| R08 | data/sync/TpmsPressureWorker.kt：observedAt=System.currentTimeMillis()；样本又标 provider_observation；成功处理后 pruneOlderThan90Days | 手机采样时间被赋予 provider 标签，不能据此证明真实源观测时刻。清理策略亦必须隔离审查 | T01–T04、T09 |
| R09 | data/repository/TpmsHistoryRepository.kt：本地 DAO 7/30 天查询、provider_observation 过滤 | 30 天视图存在不等于已从云端完成 30 天分页增量同步 | T05–T08 |
| R10 | domain/analytics/DriveEnergyResolver.kt：API 能量只接收 finite && >0；否则尝试 power samples | 真实 0 与负净回收可能失去 API 来源/数值；需贯通计算及展示测试 | F01–F05 |
| R11 | SyncRepository 向 TripNotificationManager 传 localHistoryCarId；notification Intent 用它作 EXTRA_CAR_ID；NavGraph 进入 DriveDetail | 本地/远端 ID 不同的通知点击是重点风险；不能未测试就断言已串车 | N11、V04 |
| R12 | main.go openStore 含 DDL；SyncRepository 云模式首先尝试 uploadLocalHistory | 测试副作用是明确风险，不能把启动或同步一概叫只读 | G01、S10 |
| R13 | Go history_context.go：按 user_id 和 car id 查询，严格 GET 路径，5 秒 context timeout，404/503 区分 | 身份接口有边界设计，不是所有详情/导出/通知已经隔离的证明 | V01–V05 |
| R14 | android/app/build.gradle.kts：Debug 隔离包名且 mock=true/cloud=false；Release 显式地址守卫、R8、可选正式签名 | Debug 通过不能替代正式官方登录；不得用 README 旧参数绕过真实配置 | G02、L01–L05 |

完整执行路径需输出实际调用者、传递的 user/vehicle/source、写入点、取消点和发布点。尤其搜索整个固定 checkout 内 DataSyncWorker 的入队者，检查首次登录、前后台转换、定期调度、重启、权限恢复及账号切换。没有找到必须调用者时记录路径缺口，不能只测试被孤立调用的 helper。

目标链路分两种，不混淆：

- Fleet：官方授权 → 本用户车辆确认/配置状态 → 自然 provider 观测 → 接收/校验/隔离 → 持久化/会话完成 → API 摘要/详情 → 手机同步/Room → UI/持久事件 → 系统通知/前台消费。
- 归档：个人 TeslaMate 既有观测 → 被授权归档/映射 → 归档 API → 手机。归档内容可验证显示与一致性，但不计入 Fleet 首事件。

## 4. 每个用例的共同规则与数据夹具

每个用例执行前记录环境层、完整 SHA、账户/车辆/来源测试别名、前置基线和唯一 case_id；执行后记录实际值、期望值、证据路径、退出码、状态。表中的多个变体要拆成 case_id.variant，不能只执行最简单的一个就把整行标 PASS。

正常夹具至少包括：无车、单车、多车；0/1/49/50/51/100/101 条分页；重复页、重叠页、空页、循环 cursor、迟到小 ID、同 ID 修订、非法记录；SOC 0→10、10→0、0→0、null→40、40→null、越界；温度负值/0/null；费用 0/null/已付/人工估算；能量 0/负净值/null/非有限；速度 0/null；亚秒、重复、逆序、大间隔观测。夹具全为合成，不复制生产身份轨迹。

日期在可注入时钟/测试环境检查 UTC、Asia/Shanghai、America/Los_Angeles 的日期边界、跨月年与夏令时 23/25 小时日。不修改 Jovi 手机系统时钟或时区。真实操作同时记录 UTC 与实际设备时区。

通用 UI 流程：进入 → 查看正常/空/部分/错误 → 刷新 → 变更筛选 → 进入详情 → 返回 → 切车/切账号 → 重新进入。每个可见按钮、开关、菜单、图表触点、导出入口必须有结果断言；不可点/无操作/假成功均记录。

## 5. 用例矩阵

优先级：P0 为身份泄露、破坏数据、伪造真实来源等不可接受风险；P1 为核心登录/同步/准确性/通知/恢复失败；P2 为次要显示、易用性及非核心兼容。这里的优先级不是已发生事故评级。

### 5.1 准入与登录

| ID / 优先级 | 前置与步骤 | 必须满足的结果 / 证据 |
| --- | --- | --- |
| G01 / P0 | 新 worktree、一次性 PG；检查全部测试入口的读写行为、目标服务及 DB | 没有生产连接、隐式历史写/清理、Owner 变更；保存安全的环境核验表 |
| G02 / P1 | 从两个固定 SHA 编译，检查有效 BuildConfig、包名、Mock 与签名状态 | Android/API 不串树，Mock 不冒充正式，签名缺失显式失败而非可安装 PASS |
| G03 / P1 | 记录测试命令、发现的测试列表、JUnit/Go JSON 和退出码 | 零测试/跳过/未连接 PG 不能算通过；旧结果不得代用 |
| A01 / P1 | 干净测试包：不勾选、只勾一个、全部勾选条款/隐私后登录 | 未同意不能启动；同意后跳可信官方入口，版本记录正确 |
| A02 / P1 | 隔离 provider：正常完成、拒绝、取消、浏览器返回、网络失败 | 状态清楚、可重试、不无限 loading、不假登录 |
| A03 / P0 | 隔离回调：错误 scheme/host/path、额外恶意参数、错误 state/nonce、过期或重复 ticket | 拒绝非法/重放，合法一次性消费；日志无凭证 |
| A04 / P1 | 登录中旋转/进程恢复；旧回调晚于新登录/退出返回 | 恢复一致，仅当前账号请求可提交 session/导航 |
| A05 / P1 | 假时钟令 access 过期；并发触发 refresh；旧 refresh 重用 | 一致轮换、重放拒绝、无刷新风暴、未误清其他账号 |
| A06 / P1 | 模拟登录但没有车辆、权限不足、provider 429/503 | 无车/授权不足/限流区分；不能凭默认 carId=1 展示他车 |
| A07 / P1 | pairing_required→外部确认→返回；再测 permission_required、pending、blocked | 只提供可信车主确认入口，状态可恢复；未确认不假装已接入 |
| A08 / P0 | 退出 A、登录 B，令 A 的在途响应/通知/回调迟到 | A 的任何数据不发布给 B，不伪造跨账号的“可用” |
| A09 / P1 | 专用真实用户本人完成官方登录/车辆确认；记录来源状态 | 只计真实授权证据；缺用户/批准记 PENDING_HUMAN，不代操作 |

### 5.2 车辆、身份与授权

| ID / 优先级 | 前置与步骤 | 必须满足的结果 / 证据 |
| --- | --- | --- |
| V01 / P0 | A/B 相同数字 car/event id；以 A 请求 B 车辆的状态、历史、详情、胎压、统计和导出 | 服务端拒绝或返回无权限资源；B 的字段、计数、来源不泄露 |
| V02 / P0 | 同租户两车轮流切换底栏、更多、详情、照片、费用覆盖与通知 | 缓存、设置、统计按用户/车辆/来源隔离 |
| V03 / P0 | Fleet、archive、mock、legacy 使用冲突外部 ID | 来源不可覆盖/升级混淆；archive 不变成 Fleet 首事件 |
| V04 / P1 | localHistoryCarId != remoteApiCarId；从列表/通知/小组件分别打开 | 路由最终映射到正确车辆与事件，不错详情/404/默认车 |
| V05 / P0 | history-context 超时、404、503、无 UID；恢复后再取 | 只有已验证同 scope 的缓存可离线展示；缓存不授权远端数值 ID 回退 |
| V06 / P1 | 模拟车辆解绑/权限撤销/无权车辆从列表消失 | 停止它的新请求和通知，旧历史按产品合同保留且不向新用户暴露 |

### 5.3 同步、加载、取消与缓存

| ID / 优先级 | 前置与步骤 | 必须满足的结果 / 证据 |
| --- | --- | --- |
| S01 / P1 | 分别首次登录、冷启动、回前台、仅停留首页、后台等待 | 记录完整历史同步的真实触发者与调用图；状态 polling 不充当历史同步证明 |
| S02 / P1 | 0/1/49/50/51/100/101 条；比较 API、Room、列表与统计 | 完整分页、无漏无重；真实空集不是失败，有错误不是空成功 |
| S03 / P1 | 重复页、跨页重复、循环 cursor、全部无效记录、排序中插入 | 进度有界、去重正确、明确失败/部分状态；不能无限请求 |
| S04 / P1 | 第一页成功、第二页 500/断链，再恢复重试 | 已成功页保留；不标全量完成；通知基线不被半次导入破坏 |
| S05 / P1 | 充电 HTTP 正常，挂起 persistDrives 或行程读取；只观察真实 ViewModel | 充电不能无限空白/loading；需要可用数据/明确受阻及重试，不是 helper 单测 PASS |
| S06 / P1 | 对本地读、persistCharges、两个 AggregateDao 分别抛错/挂起 | loading/refresh/filter 状态在受控期限内收敛，错误不伪200，旧正确数据不被错误覆盖 |
| S07 / P1 | 快速更改日期/车辆/账号 20 次，使响应逆序返回 | 最后请求胜出；取消可传播；旧请求 finally 不清新请求 loading |
| S08 / P1 | 冷/热缓存、无网、服务器异常，再恢复 | 同 scope 缓存可用并标缓存/过期；空缓存报错与真实无数据区分 |
| S09 / P1 | 详情加载中返回、旋转、进后台、再进；模拟 DAO 长等待 | 无后台残留无限工作、不崩溃、不丢既有历史；恢复可重试 |
| S10 / P0 | 仅隔离云模式触发 syncCar，观测 uploadLocalHistory 及来源过滤 | 上传不跨用户/源、不重复复制、不把未验证历史升级；生产不执行此注入 |
| S11 / P1 | 详情若干失败而摘要成功，再次同步 | 进度/数据质量区分摘要完成与详情完成，失败项可恢复，不永久假绿 |
| S12 / P1 | 长历史同时新增最近事件，详情耗时很长 | 最近摘要/完成提醒不被全量详情处理无限饿死；记录资源/时延 |

### 5.4 行程与驻车

| ID / 优先级 | 前置与步骤 | 必须满足的结果 / 证据 |
| --- | --- | --- |
| D01 / P1 | 行程列表→详情→曲线→返回；逐个日期与长短行程筛选 | 时间、距离、SOC、速度、能耗字段按同一事件来源对齐；过滤不无声删除 |
| D02 / P1 | 0/单端未知 SOC、0/未知速度、能量未知或负净回收 | 真实0保留，未知显示未知，净回收不当错误正能耗 |
| D03 / P1 | 路线点缺失/乱序/重复/稀疏/亚秒；曲线缩放与图表触点 | 不伪造连续实测；时间/单位正确，缺口可识别；最高速度标采样口径 |
| D04 / P1 | 同日多行程、跨午夜、月末、时区转换、往返与零距离 | 归属日期与合计口径一致，无多计/漏计/除零 |
| P01 / P1 | 同用户/车/源相邻完整行程之间生成驻车 | 起点取前行程结束，终点取后行程开始；详情/列表一致 |
| P02 / P0 | 两端来自不同用户/车/源；中间另有合格记录/重叠/未结束记录 | 不生成假驻车或跨车边界，不把非相邻数据拼接 |
| P03 / P1 | 两端 SOC=0/null、温度未知、驻车期间充电或采样缺失 | 不把充电增量算静置耗电；不以猜容量×SOC冒充 kWh |
| P04 / P1 | 驻车详情刷新取消、旧记录修订、离线回看 | 合格边界重验证，过时详情不以新来源发布 |

### 5.5 充电

| ID / 优先级 | 前置与步骤 | 必须满足的结果 / 证据 |
| --- | --- | --- |
| C01 / P1 | 从冷启动进充电、切日期、下拉、后台返回，故意延迟行程缓存 | 列表/加载状态可收敛；记录 HTTP→DAO→发布耗时，不只看200 |
| C02 / P1 | eligible charge 的 SOC 有值、energy=null；开关短充电过滤 | 未知能量不等于短充电/零充电，不能被默认无声隐藏 |
| C03 / P1 | energy 0、0.05、0.1、0.1001 与 unknown；不同 qualityState | 边界符合明确过滤口径，未验证记录可见但不污染统计 |
| C04 / P1 | 详情缺失、DC ID DAO 失败/为空；AC/DC/全部轮换 | 未知类型不自动等价 AC；如现行实现如此记录差异而非改预期 |
| C05 / P1 | cost=null、合法0、provider付费、人工总价；切费用筛选 | 免费与未知呈现可区分，列表/详情/统计覆盖率一致 |
| C06 / P1 | 已完成/进行中充电，AC/DC、拔线前后状态、低功率暂停 | 实时卡与完成记录不混淆，不提前或重复发完成提醒 |
| C07 / P1 | 多车：一车充电一车闲置，轮流刷新/切车/后台检查 | 闲置车不能使另一车的监控与通知错误停止 |
| C08 / P1 | 插入跨日会话、多页历史、同 ID 更正，再次打开详情 | 日期合同明确、数据修订可见，无重复会话或过期缓存 |
| C09 / P1 | 隔离设备编辑费用、撤销/重启、切车与货币设置 | 人工字段明确标识、持久/隔离正确，不猜汇率合并多币种 |

### 5.6 胎压与趋势

| ID / 优先级 | 前置与步骤 | 必须满足的结果 / 证据 |
| --- | --- | --- |
| T01 / P0 | provider观测时刻早于手机读取；重复返回旧快照，推进假时钟 | 不能产生新的真实观测时间或假增量；fetchedAt与observedAt分开 |
| T02 / P0 | provider时间缺失、来源unknown/legacy；数值正常 | 保留但不能标verified/provider_observation参与真实趋势提醒 |
| T03 / P1 | 同事件重复、同时间冲突、迟到/逆序点、不同轮位独立更新 | 正确定义去重/修订；不能以手机now消除冲突或倒退当前值 |
| T04 / P1 | bar/kPa/psi、0/null/负值/NaN、轮位单位不同或单位缺失 | 单位转换一次且有依据；0不自动当缺失；无单位时不猜 |
| T05 / P1 | 30天跨页数据与本地空/部分历史；期间中断/重启 | 真正从相应源完成有界分页增量并持久，不能只查询已有Room当云同步通过 |
| T06 / P1 | 超过页面大小、最近增量和迟到点；恢复同步多次 | 30天结果无漏重，游标可恢复，采集时间不替代观测时间 |
| T07 / P1 | 小波动、达到阈值、大变化、回落；阈值设置重启恢复 | 提醒基于合格相邻观测、轮位和真实变化，不因温度/时间缺失编造结论 |
| T08 / P1 | 拒绝通知、channel off、恢复；系统与前台各消费 | 不丢待处理事件，不重复轰炸；恢复按明确资格策略处理 |
| T09 / P0 | 一次性数据库含>90天legacy及已验证样本；执行成功处理路径 | 检查实际删除范围；与“保留完整历史”冲突必须报缺陷，禁止生产试删 |
| T10 / P1 | 自然行驶后的真实观测增量与默认30天趋势对应 | 记录源/单位/观测时间证据；无自然点 PENDING_NATURAL；7113回填不执行 |

### 5.7 统一数值与数据质量

| ID / 优先级 | 前置与步骤 | 必须满足的结果 / 证据 |
| --- | --- | --- |
| F01 / P1 | API能量=0或负净回收，有/无功率样本两种 | 原值及来源不被错误丢弃或换成猜测；缺失才走允许的估算 |
| F02 / P1 | 功率积分：恒定、线性、正负混合、亚秒、乱序、重复与大缺口 | 独立参考计算一致；不跨不合格缺口补实测，覆盖率可审 |
| F03 / P1 | Wh/km、kWh/100km、km/mile、km/h/mph、摄氏/华氏切换 | 统一一次转换，无10倍/1000倍错误；全页面/导出一致 |
| F04 / P1 | API报告值本身带估算来源；详情与摘要具有不同完整度 | API来源不自动升级成实测；保留测量/报告/估算/未知和覆盖率 |
| F05 / P1 | 空分母、0距离、无时间、NaN/Inf、超范围SOC、缺失温度/费用 | 不崩溃、不显示非有限数字，不用0掩盖未知；合理负温保留 |
| F06 / P1 | 汇总有部分已知能量/费用和部分未知；切日期/车辆 | 合计标明已知覆盖情况；不得把已知子集合包装成完整总量 |
| F07 / P1 | 所有当前数据页面读取同一受控事件集 | 页面、图表、统计、导出按书面口径可对账；不只比两个共用同一错误helper的页面 |

### 5.8 自然完成通知、后台与点击

| ID / 优先级 | 前置与步骤 | 必须满足的结果 / 证据 |
| --- | --- | --- |
| N01 / P1 | 隔离完整分页同步后新增一条完成drive和charge | 各生成持久事件并由真正系统/前台路径消费，不靠直接调用notifier |
| N02 / P1 | 第一次非空历史导入；重复导入；先空基线再首次新事件 | 不轰炸旧历史；空基线后的自然新增不能永久漏报 |
| N03 / P1 | 新事件ID小于旧最大ID、晚到完成、同ID从未完成变完成 | 不能只按maxID吞掉合法新事件；完成条件时间/来源合格 |
| N04 / P1 | 第一页正常、后续失败；恢复后完整同步 | 半次同步不错误推进通知基线或漏事件；重试不重复 |
| N05 / P1 | 持久写后进程死、notify后consume前死、consume后死 | 恢复后不丢待发；同一系统通知保持幂等且无反复响铃；不伪称分布式exactly-once |
| N06 / P1 | 前台先消费再后台通知；反向顺序；两个同步任务同时结束 | app/system标记互不吞事件，并发下各通道至多有效呈现一次 |
| N07 / P1 | POST_NOTIFICATIONS拒绝、全局禁用、单channel禁用后恢复 | 未成功投递不能标systemConsumed；恢复可投递合格待处理事件，历史导入不补炸 |
| N08 / P0 | A生成待发后切B/退出，B收到迟到Intent，再回A | 通知内容/点击不跨账号/车/源；待处理状态隔离 |
| N09 / P1 | 不手动刷新，分别前台、退桌面、正常锁屏；按真实调度触发新增 | 完整历史同步确实发生且通知出现；30秒状态检查不充当证据 |
| N10 / P1 | 一次性设备Doze/省电/进程回收/重启/force-stop分开测 | 报实际系统限制和恢复行为；force-stop不等同普通后台，不能一律声称常驻即时 |
| N11 / P1 | localID≠remoteID、多车大ID、两个事件同时通知、冷/热启动点击 | 不碰撞覆盖、不打开错误详情；登录门与身份复核正确 |
| N12 / P0 | 锁屏/通知历史/日志查看；深链过期、伪造id及来源 | 默认内容保护身份/轨迹/凭证，未授权Intent不能泄露详情 |
| N13 / P1 | 正常自然行程，按第6节不打开App、不手动补同步取证 | UI可读、通知实际出现、点击正确、时延可解释；否则不能宣称自然通过 |
| N14 / P1 | 正常自然充电，按第6节取证，再前后台切换/重开 | 进行中/完成事件区分，完成只提醒一次；来源与时间可追溯 |

### 5.9 所有用户页面与附属功能

先从固定源码 NavGraph、更多页、设置页和 manifest 导出 route-coverage 表。以下是已见路由/功能，执行时补齐新增/条件可见入口。每项至少跑正常、空、部分缺失、错误、返回恢复、切车/切账号六种状态；表内某变体不存在时写明原因，不能静默忽略。

| ID / 优先级 | 页面/行为 | 重点步骤和断言 |
| --- | --- | --- |
| U01 / P1 | 首页/车况/就绪页 | 休眠/离线/在线/行驶/充电切换；观测时间与缓存来源真实；休眠不强制唤醒 |
| U02 / P1 | 电池/续航 | 未知容量与续航、SOC0、温度缺失；估算明确，不承诺无证据的健康度 |
| U03 / P1 | 里程/能效/统计 | 日期切换及明细合计对账；短行程、未知值、负净能耗不错误排除 |
| U04 / P1 | 成本/电价配置 | 免费、缺费用、分时电价、人工总价、多币种；估算不覆盖源账单 |
| U05 / P1 | 静置耗电/时间线 | 与合格驻车区间一致，不跨车/跨来源拼接，不混入充电 |
| U06 / P1 | 温度/胎压趋势及设置 | 单位、轮位、日期、缺口、30天默认、提醒开关重启后保持且隔离 |
| U07 / P1 | 地图/历史位置/访问国家地区 | 缺坐标、坐标系、地图SDK不可用、无权限；不返回(0,0)假位置或泄露轨迹 |
| U08 / P1 | Trips分组/创建/详情 | 合成数据内创建编辑/重启；不把用户分组当自然驾驶事件，不重复累计底层行程 |
| U09 / P2 | 软件版本/哨兵历史 | 版本顺序和未知日期；哨兵开关不等于真实告警事件，不凭fixture冒充事件 |
| U10 / P1 | 年报/报表PDF/导出分享 | 逐字段与当前筛选对账、跨页不漏行、权限取消/空间不足安全；只在测试数据中分享 |
| U11 / P1 | 小组件/当前充电通知 | 添加、刷新、重启、切账号、无权限；旧车快照不泄露，后台不无界轮询 |
| U12 / P2 | 主题/语言/单位/大字体/旋转 | 中英文、深浅色、大字号、TalkBack关键标签；小屏文本不遮挡核心数值/操作 |
| U13 / P2 | 自定义照片/3D车辆图/关于/外链 | 取消选图/无存储、错误资源、可信外链；图像不阻塞数据页，不变更真实车况 |
| U14 / P0 | 连接模式与账号设置 | SELF_HOSTED/Fleet/Mock切换、失效会话；不绕过官方同意、不跨来源缓存 |

### 5.10 Fleet、bridge、资源、恢复与发布资格

| ID / 优先级 | 前置与步骤 | 必须满足的结果 / 证据 |
| --- | --- | --- |
| B01 / P0 | 隔离provider/telemetry：无授权、未确认、有确认但无首事件、错误和限流 | 各状态真实；archive和fixture不可推动真实Fleet available |
| B02 / P0 | 两租户同车标识/同时间同值，重复/乱序MQTT消息 | 授权隔离、来源识别、QoS1重复幂等，不刷新伪observedAt |
| B03 / P1 | 一次性bridge/receiver在接收、写入、ack边界被终止后重启 | 确认过的事件持久可恢复，未确认消息重放幂等；源数据不丢不重 |
| B04 / P1 | idle drive无下一条事件、充电结束、离线间隙再连 | 会话有界完成且不凭猜测编造；真实来源时间和完成原因可解释 |
| B05 / P1 | 并发2/4/8/16、长历史、详情/导入与当前摘要并发 | 限流/预算有效、公平；记录CPU/RSS/DB连接/队列/响应，不用生产压测 |
| B06 / P1 | 请求取消、PG锁等待、资源拒绝、磁盘满、部分chunk失败 | 回滚/幂等/重启可恢复；无无限重试或OOM，无假200 |
| B07 / P0 | 候选迁移/备份/回滚只在一次性PG执行两次 | 保留原历史，重复执行幂等，回滚后可读；不能以生产DDL试验 |
| B08 / P1 | 受控长跑至少2小时并完成100次页面进出/同步周期；后续24小时资格观察另记 | 无持续内存/队列增长、错误重试风暴；短采样不能代长期稳定 |
| B09 / P1 | 真实授权用户产生自然Fleet首事件，第二真实用户独立重复 | 每用户/车/来源证据独立；缺事件/人员分别PENDING_NATURAL/HUMAN |
| L01 / P1 | Debug单测/静态检查/构建和Release单测/R8分别跑 | 两套报告、命令退出码、跳过说明齐全，Mock不算真实登录 |
| L02 / P0 | 只读比对实际APK来源、签名匹配、安装时间、历史/设置摘要 | 不因测试清数据；签名不符不卸载，回本地询问 |
| L03 / P1 | 一次性设备旧版本数据库→被测版本，升级中断/再次打开 | 迁移保持已知0、unknown/legacy来源、通知状态及历史，不破坏结构 |
| L04 / P1 | 仅需安装时同签覆盖；无破坏性降级；候选API与旧App交叉合同测试 | 生产安装需既有授权覆盖；API字段新增/缺省兼容，不混底座 |
| L05 / P1 | 独立测试恢复备份、回到旧可读版本/前滚兼容方案 | App schema不支持安全降级时禁止adb -d硬降；生产回滚单独审批 |

## 6. 最重要的真实自然行程/充电验收协议

### 6.1 不被人为刷新污染的流程

步骤 1：冻结实际 Android/API/bridge 版本与来源，确定事件是 Fleet 还是 TeslaMate archive。记录当前权限、通知channel、App前后台/锁屏状态、网络可用性及允许的后台状态。不得为测试关闭用户现有保护或优化策略。

步骤 2：记录已有事件集合和通知基线的安全摘要。先观察是否存在真实自动同步入口。取证不得触发新的 provider fetch、车辆唤醒或历史上传；优先现有安全日志和本地只读数据。需要补充诊断能力时标证据缺口交远端，不能伪造时间点。

步骤 3：使用本来就会发生的正常行程/充电。Jovi 不在驾驶中操作手机；由被动日志记录，必要的手工时间确认在停车后完成。没有自然事件就结束本轮可执行测试并明确 PENDING_NATURAL，不要求额外驾驶。

步骤 4：事件完成之后不要打开 App、不要下拉刷新、不要调用runNow或ADB触发worker。保持预定的后台/锁屏场景，记录通知真正出现的时间。由系统日志接受notify只能证明已提交系统，不自动证明用户看见。

步骤 5：在既定观察窗口结束后，再记录“无干预是否送达”。之后允许进入独立的人工刷新恢复用例；即使刷新后立即出现，也不能把之前的自动送达用例改成PASS。

步骤 6：点击通知，核对账号、车辆、来源、event id及详情内容；再前后台切换、重新打开，检查系统提醒与前台提示各自只消费一次。真实设备不强杀/清数据来补边界测试。

步骤 7：对同事件进行源→持久化→API→Room→UI独立对账，建立时延链。查不到某环节写UNKNOWN/测量区间，不能凭相邻时间猜值。

步骤 8：分别输出自然行程和自然充电结果。建议利用正常使用积累至少3次自然行程、2次自然充电，覆盖至少一个后台/锁屏场景；这是覆盖目标，不是额外制造事件的指令，也不足以声称整体高可靠百分比。首个完整事件可给单例PASS，整体仍按缺失覆盖保留未验收项。

### 6.2 时间点、时延与测量质量

| 时间 | 含义 |
| --- | --- |
| t0 | 真实事件结束时刻：注明源、精度、是否车主停车后确认 |
| t1 | provider对应观测时刻；如果只知接收时间，单列receive时间，不当观测时间 |
| t2 | 接收器/bridge首次接受事件 |
| t3 | 持久会话完成/可提交读取时刻 |
| t4 | 当前用户API首次可读该完整记录 |
| t5 | 手机自动同步发现/开始处理该事件 |
| t6 | 手机Room/事件存储完成持久化 |
| t7 | UI可见记录时刻；后台未渲染时此项不臆造 |
| t8a / t8b | 提交系统通知 / 系统实际展示时刻，必须区分 |
| t9 | 点击后正确详情可见时刻 |

分别计算：源采集与完成延迟t3−t0、API可见延迟t4−t3、手机发现t5−t4、本地处理t6−t5、前台显示t7−t4、系统投递t8b−t6、用户整体体验t8b−t0。UI可见与通知可见是两个终点，先后无固定假设。

跨机器使用UTC和已测时钟误差；同设备处理耗时使用单调时钟。源时间不准或只按间隔查询发现时，报告上下界，例如t4属于“最后未见、首次见到”区间；禁止把首次轮询读到当精确入库时刻。不能把负耗时直接截为0掩盖时钟问题。取证轮询必须有明确上限和副作用审查，不向真实车辆密集轮询。

每条记录保留全部样本，不只选最快一次。样本很少时报告每次值与最大值；受控条件至少30次重复后才给描述性p50/p95，并声明不代表总体SLA；真实自然少量样本不外推99%可靠性。

### 6.3 本轮建议性能目标（不是既有成绩或Android保证）

将以下目标作为测试前冻结的“建议产品目标”，实际报告必须写明是否已由Jovi确认，不能为通过而事后放宽。功能正确性门无须等待时延目标确认即可执行。

| 场景 | 建议目标 | 超标解释 |
| --- | --- | --- |
| 同scope热缓存打开 | 首批可见数据≤1秒 | 记录设备/样本量，不接受全量详情阻塞 |
| 正常测试后端、普通列表首次打开 | 首批有效内容≤5秒 | 详情渐进加载可单列，不得假全量完成 |
| 故障/挂起 | 在15秒观察点已有明确超时/部分/重试状态，不持续空白旋转 | 记录底层超时合同，未满足标差异 |
| 记录已在API可读、App前台 | 自动列表更新≤30秒；系统完成通知≤60秒 | 不能由人工刷新达成自动指标 |
| 正常联网、普通后台/锁屏，API已可读 | 完成通知目标≤120秒；>300秒列关键时效缺口 | 统计场景、权限与调度，不能当作Android承诺 |
| 正常自然事件结束→通知实际出现 | 目标≤5分钟，并拆分上游与手机段 | API段快但上游慢仍是用户体验问题 |
| 允许执行后/权限恢复后的首个成功同步 | 合格待发事件≤60秒内处理，去重不轰炸 | 定义资格/积压策略；策略缺失记合同缺口 |
| API受控摘要读取 | 固定夹具和并发条件下p95≤2秒 | 列出硬件、规模与请求类型；不冒称生产容量 |

Android WorkManager 周期任务最小重复间隔为15分钟，实际执行受约束和系统优化影响；一次性initialDelay也不是准点保证。Doze会延后后台CPU/网络活动。故“代码写30秒/15分钟”不能证明快速送达。若依赖延迟任务无法满足产品的普通后台时效目标，必须报告架构/调度缺口，不能要求用户一直打开App或把15分钟改叫实时。Doze、用户force-stop、通知明确拒绝分别报告限制、提示与恢复，不把它们混成普通后台PASS。

Android 13及以上的通知运行时权限、全局开关、单channel开关分别测试；被用户拒绝时尊重拒绝，不绕过。此时正确的“未发出且可恢复”可通过权限处理用例，但不是“已送达”用例通过。

官方平台参考（用于约束解释，不是本产品性能承诺）：
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- https://developer.android.com/training/monitoring-device-state/doze-standby
- https://developer.android.com/develop/ui/compose/notifications/notification-permission

## 7. 数值链路的独立对账方法

同一事件必须用内部(user, vehicle, source, event)键关联；公开证据只用随机别名，私有映射不上传。逐字段比较原始合格观测、存储、API JSON、Room 和 UI。源不存在的字段不能凭别的层补成真实值。原始数据读取须限定已授权来源/时间窗口/行数并只读，不能导出整个生产库。

| 字段 | 参考口径与必须断言 |
| --- | --- |
| SOC | 真实起止端点各自校验0–100；0有效，单端未知独立保留；不得重复旧SOC修复 |
| 距离/速度 | 标明采样最高速度/平均方式，单位一致；轨迹重建或里程计差属于不同口径，不无标识互换 |
| 能量 | 有合格API报告值保留其原始来源；无值时仅按合同允许的样本积分估算 |
| 功率积分 | 独立参考实现按时间排序/重复规则和采样缺口门计算；允许时用E=Σ((P_i+P_{i+1})/2)×Δt/3600，P为kW、Δt为秒；不跨不合格缺口，亚秒保留；不能直接调用产品同一个calculator作为oracle |
| 能效 | Wh/km=1000E/d，kWh/100km=100E/d；d未知/0不计算；净负能量不随意钳成0 |
| 覆盖率 | 合格覆盖时间/明确事件持续时间；期间重叠不重复覆盖，稀疏/缺口显式不足 |
| 温度 | 负值和0可以有效；单位有依据，缺失不是0℃ |
| 费用 | 已知0、unknown、源账单、人工输入、按电价估算分别标识；多币种不猜汇率 |
| TPMS | 四轮位、压力单位、来源、实际观测时间、采集时间、质量门独立核验 |
| 总计 | 只按明确纳入规则对账，同时披露未知/排除条数，不把已知子集包装成完整总量 |

容差必须预先按源精度和显示精度制定：整数SOC和标识要求精确；单位换算在固定参考容差内；UI四舍五入误差通常不得超过最后显示位的半单位。不得用泛化的“差5%算正常”掩盖错误。每个估算值记录公式、输入点数、时间覆盖、排除原因及来源标签。

## 8. 本地执行顺序与命令入口

### 8.1 顺序

先G准入→身份/来源P0→构建与单元/PG→数据合同/故障注入→全部路由UI→通知调度/权限/恢复→资源与迁移→现有正式包安全验证→自然事件。某个测试失败后继续不依赖它的隔离测试，最后整包反馈。发现串用户、真实数据破坏、意外生产写或凭证泄漏时停止相关有害路径，先保存脱敏证据并询问Jovi；不阻止其他安全离线检查。

### 8.2 固定检出

在已确认origin指向唯一仓库的本地仓库执行，路径由本地选择两个全新目录，不覆盖现有目录。以下为Bash示意，PowerShell使用等价git命令，保留同样断言。

```bash
git remote -v                   # 只在本地核验，不上传可能含凭证的remote URL
git status --porcelain          # 保存Owner状态，不改动
git fetch origin codex/reliable-data-stage-20261007
# 确认两个commit对象存在，必要时从同一origin取对应公开提交
git cat-file -e 14ca59d54ba8c470abb45d01798a23919f47bb63^{commit}
git cat-file -e bb09fac04d11796ce676555dad094776cd1ef0ce^{commit}
git worktree add --detach /NEW/qa-android 14ca59d54ba8c470abb45d01798a23919f47bb63
git worktree add --detach /NEW/qa-api bb09fac04d11796ce676555dad094776cd1ef0ce
# 两个目录分别执行并把安全输出写入manifest
git rev-parse HEAD HEAD^{tree} HEAD^1
```

不要编译文档分支HEAD来代替冻结产品SHA，避免BuildConfig把文档SHA当成产品来源。若对象确实不可取，记录真实命令失败，不用近似分支替代。

### 8.3 Android

源码要求JDK17、SDK35、仓库Gradle wrapper。先记录Java/Gradle/SDK版本，然后在Android检出的android目录执行；Windows用gradlew.bat。

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# 仅专用一次性模拟器/测试设备，核验目标不是Jovi主设备
./gradlew :app:connectedDebugAndroidTest
```

Debug的默认applicationId为com.matelink.test.mock，JOURVOLT_MOCK_LOGIN=true、JOURVOLT_CLOUD_LOGIN=false。当前真实参数来自build.gradle.kts；API地址由JOURVOLT_API_BASE_URL控制，不照抄旧README中不再对应的参数。只设置隔离测试API，确认没有真实Tesla配置/生产后端。

Release必须使用已批准的本地配置：显式JOURVOLT_API_BASE_URL；MATELINK_PUBLIC_INFO_BASE_URL和JOURVOLT_AUTH_HOST通过源码守卫；需要签名时使用本地MATELINK_SIGNING_PROPERTIES_FILE。不得打印该文件、密码、keystore或token。

```text
gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
        [已批准的本地Release属性配置，不在证据中展开私密值]
```

这是命令目标而非可复制的占位配置。配置缺失应列BLOCKED_ENV并继续其他隔离测试，不能修改Release guard凑通过。检查有效包名、mock=false、cloud=true、R8产物、APK哈希、来源与签名匹配结果。仅需要且已有授权时同签install-r；不允许卸载解决签名冲突，不允许清数据。正式包默认不跑instrumentation。

### 8.4 Go/API与Web

在API独立检出的deploy/jourvolt-dev-mock中运行。先建立一次性PG16和专用测试数据库；测试DSN只指新建隔离服务，确认主机、端口、库名和容器身份不属于任何生产资源。不要source现有生产.env。

```bash
# JOURVOLT_TEST_DATABASE_URL 由本地隔离fixture提供，不从生产复制
export JOURVOLT_REQUIRE_HISTORY_PG=1
go test ./... -count=1 -json
go test -race ./... -count=1
go vet ./...
go mod verify
go build ./...
```

每条命令保存独立输出及真实退出码；使用tee时启用pipefail。解析Go JSON的fail/skip和实际PG测试执行记录；没有PG不能记全套PASS。优先全套race而非只挑旧正则；资源矩阵按固定源码实际存在的benchmark枚举执行，缺失记GAP，不能把另一个分支的重历史能力记到bb09fac产品。

已读取的手机底座history-memory-review.yml包含Go/PG、race、资源矩阵和Web步骤，但没有Android job；push分支过滤也不包含本阶段分支。因此既有工作流成功不能证明Android或者当前阶段完整通过。执行者只汇总确实运行且SHA匹配的CI，未运行注明NOT_RUN。

Web兼容回归只在对应固定源码检出执行存在的脚本：node --experimental-strip-types --test tests/history-pages.test.mjs，npm ci --no-audit --no-fund，npm run build。先核验package脚本与Node版本；源码缺失的测试入口记缺口，不从其他项目补入。

### 8.5 设备和恢复证据

优先使用现有允许的诊断手段。read-only adb package/version/系统日志查询仅在本地筛选目标包和时间窗口，上传前脱敏；不导出整个logcat/notification dump。后台时效观察不要始终USB供电而声称已覆盖自然Doze，记录取证连接的干扰。新安装/重启/损坏/force-stop仅在一次性设备模拟；真实设备权限切换/退出账号等需由本人确认后执行。

备份与回滚先在隔离数据验证。正式APK升级如改变Room schema，旧APK未必能安全读新schema，禁止硬降级代替恢复设计。生产API/bridge不因本轮测试重启或升级，生产备份/恢复也须在既有精确授权范围内。

## 9. 结果格式、退出准则与整包回传

在本地临时证据目录完成全部安全可执行步骤，再以同一仓库owned codex/证据分支集中回传；不能写主分支、生产配置或原始敏感证据。每次回传提供精确commit，不用“本地最新”作来源。

建议目录：docs/qa-evidence/qa-YYYYMMDD-runNN/。

```text
README.md                 结论、版本元组、完成范围、自然/人工门
manifest.json             Android/API/bridge/测试harness哈希、设备OS、时间区与取证条件
route-coverage.csv        路由→控件→case→状态，所有入口覆盖
case-results.csv          case_id,variant,priority,layer,status,expected,actual,evidence,defect_id
counts.json               planned/executed/pass/fail/blocked/pending/not_run，禁止零测试假绿
chain-evidence.csv        随机事件别名、来源、字段对账、来源精度、各层安全摘要
latency.csv               场景、t0…t9、误差/区间、权限、自动/人工干预标记
build-tests.md            命令、退出码、JUnit/Go统计、CI run/job/SHA与artifact摘要
resource-summary.csv      规模、并发、CPU/RSS/DB连接、队列、错误、恢复
migration-preservation.md 仅隔离迁移结果；正式包数据保留的允许只读证据
defects.md                复现步骤、期望/实际、优先级、源代码候选位置
human-natural-gates.md    所缺真实确认/事件/独立生产授权
```

状态统一为PASS、FAIL、BLOCKED_ENV、PENDING_HUMAN、PENDING_NATURAL、NOT_RUN。测试缺失/不可达用例写GAP原因并保持非PASS；无日志不等于无错误。不可适用变体给明确依据，不混入PASS数量。跳过测试必须逐项说明、关联是否为发布阻断项。

缺陷单最小格式：固定版本元组 + case_id + 输入夹具/场景 + 操作步骤 + 期望 + 实际 + 是否重复复现 + 哪一层首次偏差 + 安全证据路径。根因未被实验确认就写“源码候选”，不要用猜OOM/日期/网络代替定位。修复只能由远端针对固定SHA交付后，本地再复测失败项及影响矩阵，不局部改业务逻辑让结果变绿。

### 发布/验收门

- G0：可开始测试。版本/环境明确、安全条件满足即可；不是发布批准。
- G1：核心正确性。所有P0与核心P1完成且无未解决FAIL，必需测试无未解释skip，身份/来源/历史保护满足。
- G2：正式包与兼容性。Release独立通过、真实签名与设备安装来源可证、历史保留、两个产品底座兼容。
- G3：自然数据与通知。真实行程/充电的自动同步、实际通知、正确点击、字段对账与时效分别完成；手动刷新、mock或旧归档不得替代。
- G4：Fleet多用户。至少两个独立同意的真实用户分别完成自己的授权/确认与新Fleet事件证据；缺一个就不声称多用户上线验收。
- G5：生产变更。任何DDL、bridge升级、历史修复/回填、部署和回滚实施均需单独精确范围授权。本方案绝不自动打开该门。

整轮报告必须分别写“测试执行覆盖度”“软件缺陷结论”“自然/人工未完成门”“是否达到建议时效目标”。可以完成一轮全面测试并得出不通过/待自然数据结论；不能为了交付一个PASS省略失败或人工门。

## 10. 本次源码证据索引

以下URL固定到本轮实际读取源码；不是指向可移动分支。

- Android identity/history: https://github.com/Jovifei/tesla-master-mimo/blob/14ca59d54ba8c470abb45d01798a23919f47bb63/android/app/src/main/java/com/matelink/data/repository/UnifiedHistoryRepository.kt
- Charges publication/filter: https://github.com/Jovifei/tesla-master-mimo/blob/14ca59d54ba8c470abb45d01798a23919f47bb63/android/app/src/main/java/com/matelink/ui/screens/charges/ChargesViewModel.kt
- Completion sync: https://github.com/Jovifei/tesla-master-mimo/blob/14ca59d54ba8c470abb45d01798a23919f47bb63/android/app/src/main/java/com/matelink/data/sync/SyncRepository.kt
- Durable event store: https://github.com/Jovifei/tesla-master-mimo/blob/14ca59d54ba8c470abb45d01798a23919f47bb63/android/app/src/main/java/com/matelink/data/local/CompletedChargeEventStore.kt
- TPMS worker: https://github.com/Jovifei/tesla-master-mimo/blob/14ca59d54ba8c470abb45d01798a23919f47bb63/android/app/src/main/java/com/matelink/data/sync/TpmsPressureWorker.kt
- Energy resolver: https://github.com/Jovifei/tesla-master-mimo/blob/14ca59d54ba8c470abb45d01798a23919f47bb63/android/app/src/main/java/com/matelink/domain/analytics/DriveEnergyResolver.kt
- Notification: https://github.com/Jovifei/tesla-master-mimo/blob/14ca59d54ba8c470abb45d01798a23919f47bb63/android/app/src/main/java/com/matelink/notification/TripNotificationManager.kt
- Release guard: https://github.com/Jovifei/tesla-master-mimo/blob/14ca59d54ba8c470abb45d01798a23919f47bb63/android/app/build.gradle.kts
- API identity: https://github.com/Jovifei/tesla-master-mimo/blob/bb09fac04d11796ce676555dad094776cd1ef0ce/deploy/jourvolt-dev-mock/history_context.go
- API startup: https://github.com/Jovifei/tesla-master-mimo/blob/bb09fac04d11796ce676555dad094776cd1ef0ce/deploy/jourvolt-dev-mock/main.go

阅读本方案后的直接执行入口：docs/QA-LOCAL-CODEX-PROMPT-20261008.md。
