# build73 数据修复独立消费者复核

目录：`E:\project\_worktrees\matelink-release-data-audit-20261026`；复核者`/root/audit_auth_home`。本轮依据当前App候选build73 / 2.1.54源码，未构建、提交、安装、操作生产或读取私人数据。父F2正在修改Current/ChargeDetail mapper，本文不将已补源码的旧RED重复当新发现。下列三项由独立实际消费者链确认，父已将其转交本组F7实施；本文件先固定修复前触发和边界，测试结果另记。

IR各项行号对应本组开始实现前的源码快照；本组实现已改写这些函数，不能把历史行号当作修复后仍存在缺陷的当前定位。实际RED/GREEN和最终diff由父候选总账绑定。

## IR-01 / P1：同一哨兵显示7可被新global timestamp反复当新事件

`found_version = build73 candidate WIP`：本项针对本轮新增Cloud saved-only消费者与全局watermark的组合，尚未上线；底层repo仅65秒debounce是继承逻辑，不声称Cloud新路径已在旧包生产发生。

源码：`android/app/src/main/java/com/matelink/data/sync/ChargingNotificationWorker.kt:280-321`，`android/app/src/main/java/com/matelink/data/repository/SentryStateRepository.kt:67-121`。

Cloud路径确实只读已保存status，没有新增Fleet轮询；问题是其去重凭据只有整份snapshot.observedAt。Worker:299-300以该全局时间跳过旧receipt，:311-312把`status.isSentryAlerted`交给repo；repo:100-121用手机处理时间的65秒debounce，未保存显示字段事件序号或0/other→7跃迁。

确定触发：t0保存mode=true、display=7，首次消费计数1；t70仅SOC等字段更新snapshot全局observedAt，而原display=7仍在120秒可用窗口；watermark允许、65秒debounce已过，同一个旧7再次计数并通知。不是所有每次poll都必然重复，必须新全局timestamp及超过debounce。

另有同根并发要求：Worker:120-125的immediate check普通enqueue可与已排队worker重叠。两个消费者可以同时通过旧watermark/latch；repo getState→increment→save跨suspend，单次SharedPreferences.commit并不能构成整个claim的CAS。修复须序列化“读latch/watermark→处理→提交”。

建议：持久local-scoped display latch；明确mode=true时首次7或明确0/other→7才提交一次事件；未知display/mode不清latch，明确mode=false重置。整段以同实例/同local key Mutex串行，并把latch与watermark同次commit。仍只称本机观察，不声称提供方完整历史；无独立事件ID时不重放持平7。

## IR-02 / P1：remote worker/service未冻结source，切换后可错写旧local key并绕过Cloud saved-only

`found_version = build72`：旧remote worker/service的动态source与裸local Int跨await问题，本轮build73候选修复范围。

源码：`ChargingNotificationWorker.kt:133-175,190-206,219-264`；动态API选择见`android/app/src/main/java/com/matelink/di/NetworkModule.kt:189-221`。

doWork:135只计算一次NotificationReadPlan；:172解析A的VehicleContext后，将remote ID与oldLocalA裸Int传入checkCarStatus。该方法:192重新调用动态getCars、:206重新调用动态getCarStatus，没有保存/复核原scope。之后:220-222写session，:232/247启动monitor service，:238/264发通知。

确定触发：已在SELF_HOSTED A进入remote分支、获取A local key后，在后续await间切换到B（同remote=1）或Cloud。后续R调用按当前配置重新选API，拿B/Cloud状态却仍可使用oldLocalA写session/通知；切Cloud还绕过先前SAVED_CLOUD分支触发新HTTP或启动polling service。无需假设跨账号服务端越权，错的是客户端已捕获local key与后来动态source混用。

建议：冻结SELF_HOSTED HistoryReadScope、origin与本次已认证车单，使用pin模式/来源的client；每次I/O和服务启动/写入/通知前复核same scope+vehicle+generation。构造client后不按动态mode重选。mode/source变化退出；Cloud持续只消费已有scoped receipt。service同样不能在已启动后随配置切到Fleet轮询。

## IR-03 / P2：通知将缺失SOC/目标/功率和未知类型伪装成0/80/AC

`found_version = build72`：旧通知manager默认数绑定，本轮build73候选新Cloud消费者亦可到达；尚未宣称任何新候选已安装。

源码：`android/app/src/main/java/com/matelink/notification/ChargingNotificationManager.kt:71-125`；可达入口`ChargingNotificationWorker.kt:303-308`。

manager:81-84将SOC null→0、limit null→80、power null→0、未知phases经Boolean isDcCharging→false。:98-103及progress/fallback消费这些默认值。合法partial status只要charging_state=Charging就可被Cloud worker传入，所以会显示“0%→80%、0kW、AC”，这些都不一定是已观测数据。

建议：nullable格式化并保留真实0；缺少电量/目标时indeterminate进度，未知mode保留UNKNOWN；只变现有通知数据绑定，不重排布局/style。真实Notification consumer测试需检查title/content/progress extras与0对照。

## 已检查的正确边界与不重复问题

- 最新`TeslamateRepository.chargeDetailResultForResponse/currentChargeResultForResponse`保留原detail.copy并检查显式car/charge ID；父正对current-car身份负例收口。最新`cachedChargeDetail`已保留durationMin/durationStr/坐标/source instance/record/currency/energy contract，不把该旧问题再次报告为未修。
- 当前counter通过`current_energy_receipt`与whole-window batteryInput合同分离，Go发布者明确标provider_current_plug_counter；不能据此把历史整窗能量填满或猜plug-in start。
- ToU算法按session总能量/时长均匀分配到App本地时区，仅产生ESTIMATE，已知manual/provider账单优先。未观察到该实现把估计输出标为原始账单；真实时段能量与提供方账单仍需独立证据。
- TpmsProfileBinding当前使用verified local key且前后校验scope；旧未归属profile没有自动删除/猜归属。真实DataStore与换账号时序需最后候选回归，不用helper存在替代运行。
- F6内部GPS gap后续可考虑F3 timed/gap helper，本轮没有借单测通过声称自然路线/设备像素验收。

F2附注（父持有，不由本组修改）：CurrentChargeScreen:527-529/692仍从curve最后点读取instantPower。Go mergeCurrentChargeStatus:297-299现正确保留已存curve，所以“旧11kW曲线点 + fresh scalar2kW”可能仍显示11kW。应在原位置消费新status scalar并标时间，缺少当前观测保持未知；不要将旧curve点冒充现在。

该F2附注`found_version = build73 candidate WIP`：正确保留整条curve的生产者改动暴露了当前消费者误读；不是已经上线的build73回归。父已持有并收口，不由本组跨文件修补。

## 实施/验证状态

- [x] 三项独立source反证及触发边界已固定，已报父。
- [x] 等价seam与真实Notification/Cloud并发消费者测试，父已实际RED（14/19各轮记录如下）。
- [x] Bound remote client、whole-claim latch Mutex、nullable通知及identity tags/URI均有父实际focused GREEN。
- [ ] 父统一实际GREEN、固定候选全量与设备/生产各自验收；本组不执行build/git。

实际RED补充（执行角色parent）：`runner11`联合114例/14fail；`AppReleaseSentryObservationTest.unrelatedNewSnapshotCannotReplayTheSameSentryScreenAfterDebounce`经过真实SentryRepository/DataStore消费者失败：期望count1，实际2。缺mode保留旧active session的既有对照仍GREEN。这是IR-01的运行证据；pin/concurrent claim/通知extras回归还待本组准备与父后续执行。

本组首批新RED包已冻结交父（本组未运行）：

- `ChargingNotificationNullableConsumerTest`：真实Notification title/text/progress extras；partial不冒充0/80/AC，明确0/AC对照。
- `SentryConcurrentConsumerTest`：真实Repository/DataStore/Room两个消费者；第一个在读旧state后挂起，第二个可先处理，要求同一首次7计数/日志各一次。使用现有ensureCurrent守护回调作确定性并发门，不是只测纯predicate。
- `NotificationPinnedApiTest`：真实Factory在Cloud拒绝构造selfhost pin；真实已构造client遇mode ABA在HTTP admission前拒绝。后者使用明确127.0.0.1隔离loopback且先成功读取一次，避免因fixture不可达造成假绿，不访问用户API。

仅新增`Factory.create(requiredCloudMode: Boolean? = null)`的未使用同义参数seam使测试可编译，尚未实现pin。该批5例预期4缺陷FAIL、1合法0对照PASS；以父实际JUnit结果为准。

编译门：父runner13产品编译PASS，新增Notification测试因旧版Robolectric的`RuntimeEnvironment.getApplication()`不支持类型参数而未进入JUnit。父仅移除两处`<Application>`，断言未变，runner14将同batch重跑；13不算业务RED/GREEN。后续fixture统一调用无泛型`getApplication()`，避免重复此编译错误。

## F7实际RED后实现（冻结15，GREEN待父执行）

父runner14：联合130例/13fail/0error/0skip；本组新增Pinned2、Concurrent1、Notification partial1为 **4个实际缺陷RED**，明确0/AC对照PASS。Concurrent断言期望1实际2，partial通知实际title含`0 kW AC`；父旧global timestamp replay再次RED。随后本组得到GREEN窗口，实施以下代码，当前尚不称已通过。

- `TeslamateApiFactory.create`新增可选requiredCloudMode，仅pin调用要求相同mode/epoch；pin cache key与默认client分开，构造后及HTTP admission重验模式代次，ABA不能复用旧client。普通调用默认不pin，认证/TLS/redirect策略保持原合同；cache访问同步，未把pin client归入默认cache键。
- 新`BoundNotificationSource/Session`在SELF_HOSTED scope内构造固定API；每次await前后复核scope和vehicle；本次bound已认证car list直接授权local key，不另做动态discover。Worker与Service不再跨await重新选择Repo来源；Service持有同source session并监听scope变化退出，不随模式变Cloud而轮询Fleet。
- Cloud仍只读已保存scoped receipt，未新增Fleet请求或启动monitor service。原SharedPreferences水位保留兼容；新主水位在Sentry DataStore，counter、display latch、watermark同一次edit，观察claim/处理/派发全程同一个静态Mutex串行。
- Sentry只把明确mode=true下首次已观察7或0/other→7计事件；未知mode/display不clear latch，明确false重置本次session/latch。legacy active有已记录事件但无latch时保守继承已观察7，不因升级虚构首次脉冲。坐标仍需合法域，0/0不写为真实位置。不称provider完整事件历史。
- Notification缺SOC、target、power保留nullable，未知phases显示UNKNOWN，缺SOC/target为indeterminate；明确0和已知AC/DC仍显示实际值。未更改通知渠道、优先级、布局或style。

实现后又追加真实消费者回归，待同次父QA执行：`BoundNotificationConsumerTest`3（origin迟到、Cloud前置拒绝、旧车单不能重绑新scope）；`SavedSentryObservationConsumerTest`3（组件重开latch、unknown不rearm、两个并发saved消费者只派发一次）。这些是新增实际consumer正反例，**不能倒称已在修改前运行RED**；原4个负例的实际RED与父replay RED如上独立记录。

必要后续参数点已报父，尚未改动：`SentryNotificationManager.showSentryAlert`旧carId同时作notification local ID与Intent EXTRA_CAR_ID；Worker已传local负ID，而NavGraph通知入口要remote正ID，可能导致点击被拒绝（build72既存）。应区分两个ID后补实际PendingIntent路由回归，不改布局。

该参数点已纳本轮：新增`ScopedNotificationIntentTest`2个真实PendingIntent断言，期望`EXTRA_CAR_ID=remote7`、`EXTRA_LOCAL_HISTORY_CAR_ID=local-21`。Sentry增加未使用remote参数、Charging builder增加未使用local参数作为RED seam，Intent行为尚未改变。父独立持有cached-only NotificationTargetViewModel及NavGraph守护；producer准确key如上，既有navigate类型不变。

同一通知身份根因的进一步确定触发已纳父计划：Sentry数值ID=`4000+local`，首个local=-1即3999，与ChargingMonitorService loading占位3999碰撞。不能只数值偏移，须使用稳定type tag并匹配notify/cancel；PendingIntent须type/local data URI，避免长期local key增长及同remote不同local复用。coexist/PI防复用新负例待当前runner16结束再补，尚不称关闭。

父runner15因其他分组新CostUiState测试属性名错误停在test编译，未进入JUnit；父仅修该属性名后runner16重跑完整batch。不得将15记为本组业务GREEN。

## runner16实际结果（parent执行）

联合159例/14fail/0error/0skip。F7 **Pinned2、旧Replay2、Concurrent1、Nullable2、Saved consumer3全部GREEN**。Bound consumer3中两项GREEN，一项fixture切Cloud前缺合法Cloud session；父认可在切换前保存合成登录态再复跑，不能将该fixture错误认作生产consumer故障。

`ScopedNotificationIntentTest`2项是本轮实际RED，父cached-only NotificationTarget2项亦实际RED（父持有实现）；因此通知身份链尚未宣称完成。下一窗口只收口这些已确认项、同ID共存/type-local PendingIntent负例，不继续扩展新研究。以上均为源码/JVM/隔离loopback证据，无安装/生产/真实手机通知验收。

## 通知身份链收口（冻结18，未先改tags/URI）

16实际ScopedIntent2 RED后，本组将Sentry通知local ID与remote route ID分开；Charging show/build及Service生产调用同样传local owner。生产extra固定：`EXTRA_CAR_ID`为remote正ID，`EXTRA_LOCAL_HISTORY_CAR_ID`为local负key；navigate类型保留原`sentry_history/current_charge`。父receiver负责cached-only来源/账号/UID复核，不新增HTTP。

同时修正Bound consumer fixture：两处切Cloud前先保存合成合法next-account session，保留所有原断言。不能用无会话导致的scope异常当作正常Cloud场景控制。

新增`NotificationNamespaceIsolationTest`3项，保持现有untagged/无URI行为待父真实RED：

1. 实际Sentry local=-1与Service loading使用同数值3999仍须共存；Sentry cancel不能删loading。
2. 同remote7对应两个local -21/-22时，Charging PendingIntent不得复用/改写前一个owner。
3. Charging/Sentry内容Intent须不同type/local data URI，均携带对应local尾段，不靠数值偏移避免碰撞。

截至冻结18，tags/URI逻辑未提前绿色化；本组没有运行该三项，不以预期失败代替父JUnit结果。

## runner19真实RED与最后实现冻结

父runner18因父Tariff nullable Instant编译集成错误未进入JUnit；修正后runner19实际178例/17fail/0error/0skip。`NotificationNamespaceIsolationTest` **3/3实际RED**：3999覆盖、跨local PI共享、type/local URI缺失。`ScopedNotificationIntentTest`2、父Target2、Bound3、Pinned2、Concurrent、Saved、Replay、Nullable均已实际GREEN，先前Bound fixture缺session已由合法切换控制收口。

在这3项真实RED后，本组最后实现：

- Sentry `notify/cancel`匹配固定`matelink_sentry` tag，保持原数值ID，避免与native foreground untaged 3999槽相撞。
- legacy清理严格限定同数值ID、untagged、本Sentry channel，保留同ID的charging service占位/其他类型通知。
- 已绑定的Charging/Sentry内容PendingIntent分别使用`matelink://notification/charging/{local}`、`matelink://notification/sentry/{local}`，Charging requestCode使用local key；未绑定的旧默认caller保持原remote行为。两个类型与同remote不同local不能互相覆写owner。
- remote/local extras不变，父cached-only receiver守护不依赖新URI格式；未增加HTTP、渠道、操作按钮或布局/style。

以上最后代码已冻结交父全量QA；这3项的实际GREEN仍待下一轮，不能以源码自审代替测试或真实设备验收。

## focused终态与永久源码冻结

父runner20实际182例/4fail/0error/0skip；F7 **所有相关classes全GREEN**，包括Namespace3、ScopedIntent2、父Target2、Bound3、Pin2、Saved3、Concurrent、Replay、Nullable。剩余4项为父Current consumer新3个实际RED与F5 formatter空格断言，不能记成本组或全App通过。执行角色parent，本组未build/git。

IR-01、IR-02、IR-03及同根通知ID/Intent归属问题已完成源码与focused验证收口。F7产品源码永久冻结，仅响应父全量QA的具体失败；不再扩研究。父cached-only Target负责当前来源/账号/UID验证，producer只传remote/local归属，不把local键当remote路由。

本组只读确认Worker:180-183、Service:248-251对明确active AC phases1/2/3均写lastDc=false，未知phase不改，明确unplug才clear，与父新Current active-AC问题的正确策略相同；未改父Current文件。父后续全量单元/lint/R8/API35及真实设备/提供方验收仍独立，本组不宣称安装、生产或完整自然事件历史通过。

## F7 focused冻结源码 SHA256

全量Debug22后单个测试适配（本组仅修改测试，产品9文件哈希不变）：父回执1555例/17fail/0error，F7仅`AuthenticatedApiRedirectTest.bearerApiNeverVisitsRedirectTarget`因反射旧private `createOkHttpClient(String,boolean)`签名失败。新增pin参数后该private方法已有4参数，属于测试耦合而非followRedirect策略被改。

已最小改为Robolectric Application/隔离HistoryResourceFixture，通过真实public `apiFactory.create(origin.baseUrl, apiTokenOverride="fixture-token")`的`api.getCars()`发起loopback请求；移除Unsafe和private reflection。301/302/303/307/308的原始status、origin仅一次、origin Bearer精确值、target零访问断言全部保留；exchange/logout两组原断言保留。redirect target提供合法cars JSON，使误跟随后得到明确状态断言失败，不以坏payload代替安全验证。生产followRedirect/TLS策略未修改，本组未build/git，等待父focused/full QA回执。

下列哈希为本组在父runner20回执后的只读冻结记录；不是Git提交SHA，也不冒充全App/APK版本身份。

| 相对 com/matelink 主源码 | SHA256 |
|---|---|
| data/sync/BoundNotificationSource.kt | 1B1A4743AB00BCE064F4A7B47252F50DAE262DC5C4DEED9BCA67BE4D28F66095 |
| data/sync/ChargingNotificationWorker.kt | 009E182466A6A71C998908699C30EC53D126D5DAA5E78ADE62DBB18F0D13F86D |
| data/sync/NotificationReadPlan.kt | 2DDA7FA8260358CEBC336512400E96B744FDA8906176D6764F9169C5F3644020 |
| service/ChargingMonitorService.kt | 462380F6BD1A954D97013CE41B8894F67194A1740D36C30FC2DE9BC5801AF552 |
| notification/ChargingNotificationManager.kt | ECB9FFE1CC20190997BEAED4E093CEBCCF269C2DCAC9793AE1B6EA10F7E43732 |
| notification/SentryNotificationManager.kt | B05CCE23878F9E5CED6E69E6797C91025DCD751133F28BD85B37D5CC2F607DD5 |
| data/repository/SentryStateRepository.kt | 0147F06DFE502535D8C659E50669FD0BE3DADFE32543CB866C40480DFD9919FD |
| data/local/SentryStateDataStore.kt | A13CE6D2D5C0D2B75A159C6F826E0627E606473560F0803BF0D07EDB13F47E45 |
| di/NetworkModule.kt | AA9703E47A536AC1101B2CE12126B39B3B14427AB5A9E0DCB165FBC3CD92263D |
