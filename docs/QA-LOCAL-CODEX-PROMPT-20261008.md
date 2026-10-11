# 本地 Codex 整轮测试交接 Prompt

用途：执行本次完整测试，不是接管业务实现，不是部署批准。

## 可直接交给本地 Codex 的指令

你是本地独立验证者。请在同一 Tesla_Matelink 项目，针对唯一仓库 Jovifei/tesla-master-mimo，执行一轮完整的用户旅程、真实数据链路、设备与后台通知资格测试。不要只确认收到或返回测试计划；读取下面固定测试规范，按顺序完成全部安全可执行项目，一次集中回传证据、失败和真正的人工/自然数据门。

### 一、固定输入与角色

完整测试规范：
- 文件：docs/QA-FULL-USER-JOURNEY-20261008.md
- 规范初始提交：f2b5e30923907c0648ddc035ec6140e9bbc3b96d
- 承载分支：codex/reliable-data-stage-20261007

被测版本必须独立冻结：
- Android：14ca59d54ba8c470abb45d01798a23919f47bb63
- Android tree：a4c439fd7101646b69a31f9644c8f9b5316c9647
- Android parent：2d1828be6a5e3021951a2dca8d3637a69ecae491
- 源码版本：2.1.26 / build45
- API：bb09fac04d11796ce676555dad094776cd1ef0ce
- API tree：e06e83b2dde8ba40601f7a931821f7a6c59d360f
- API parent：36d630a8ba72f40b72628294b5bba43f4efd9724

本次两个新文件是测试文档，不能把文档commit当成新的完整产品实现。不从手机树编译Go来覆盖API，也不从API树覆盖Android。分别新建独立worktree并核验HEAD/tree/parent，保留Owner dirty工作；不要reset/clean/stash现有工作。原始授权与既有验证文件仅为背景，旧测试计数不能抄成本轮通过数。

你负责复核、测试、编译、按批准范围签名/安装验证和证据回传。业务修复交远端，不能本地改业务函数、替换真实路径、关闭失败用例或放宽预期让结果变绿。可在隔离测试环境补充测试专用fixture/harness，但必须版本化并记录哈希，不接触生产，不把单独helper测试冒充完整产品测试。

### 二、先执行准入审查

1. 核验origin只指本仓库；核验本地实际手机APK来源/版本/签名匹配状态、API build/image、bridge来源。不同于指定SHA则在manifest中明确另一个实测版本元组，不默认为同一版本，不擅自切生产版本。
2. 阅读规范第2节安全边界以及第3节R01–R14路径发现；导出实际路由/控件与所有DataSyncWorker入队点。记录登录、车辆选择、provider、持久化、API、Room、ViewModel、UI、事件存储和系统通知的真实调用关系及各层user/vehicle/source键。
3. 特别注意：云模式syncCar会尝试uploadLocalHistory；TpmsPressureWorker成功路径会按手机now写provider_observation并做90天清理；Go openStore启动含DDL。这些都不是生产只读入口，不要在生产运行来复现。
4. 测试数据库、MQTT、假provider、包名、设备必须隔离；确认不是Jovi主手机、不是生产数据库/容器/卷。没有安全环境则标BLOCKED_ENV并继续可运行的离线/静态检查。

### 三、执行完整测试，不缩成冒烟测试

按主规范第5节全部case及variant建立结果表，依次运行：

G准入和构建 → A登录/回调/同意/恢复 → V用户/车辆/来源隔离 → S分页/缓存/取消/加载/恢复 → D行程/P驻车/C充电 → T胎压/F数值合同 → N持久通知/后台/权限/点击 → U全部页面/控件 → B Fleet/bridge/资源 → L正式包、升级与历史保留。

必须覆盖以下高风险复现，不能只读代码后打PASS：
- charge HTTP成功但persistDrives挂起/异常，及两个AggregateDao慢/失败：记录真正ChargesViewModel的发布与loading收敛。
- SOC有值、energy=null且默认短充电过滤开启；0/null、费用0/null、未知AC/DC，列表与详情/统计口径。
- TPMS源观测时间不变而手机时钟推进；legacy/未知来源禁止升级；30天真实分页增量、迟到点、重复点和历史保留。
- API能量0与负净回收、亚秒积分、稀疏/乱序样本、覆盖率、Wh/km与kWh/100km；用独立oracle，不调用产品同一个计算器当参考答案。
- 首次非空历史不轰炸；首次空基线后新事件不漏；迟到小ID与同ID完成修订；分页失败不误推进基线。
- 通知已写未发、已发未消费、系统/前台独立消费、并发、权限/channel拒绝和恢复；真实系统显示与notify()返回成功分开。
- localHistoryCarId和remoteApiCarId不相等时点击通知；不同账号同数字ID、多车/多来源、退出后迟到Intent不得错车或泄露。
- 没有人工refresh/runNow时，完整历史同步是否真的由后台执行；首页snapshot轮询、30秒当前充电状态检查不能代替完成行程通知链路。

Debug使用隔离包名和mock配置；Release按本地已批准配置分别运行testReleaseUnitTest、lintRelease和R8构建，不能跳过Release guard。JDK17/SDK35与wrapper记录版本。instrumentation、进程强杀/force-stop、数据库损坏和权限注入只在一次性环境。

API从bb09fac独立树运行Go完整单测、隔离PG16、-race、vet、mod verify和build；启用JOURVOLT_REQUIRE_HISTORY_PG=1并核验PG实际测试执行。资源/benchmark先枚举固定源码实际存在入口，缺失记GAP，不引用另一个分支的能力。Web从对应固定版本执行存在的分页测试与构建。

每条命令保留真实退出码、日志摘要、JUnit/Go JSON计数和环境。零测试、未连接PG、必要测试skip、没有Android job的旧CI成功均不能算完整通过。测试失败后继续其他安全且无依赖的项目，最后汇总，不要求Jovi逐文件派工。

### 四、真实行程和充电通知是独立验收

只利用原本自然发生的正常行程/充电，不唤醒车辆、不新增专门测试行程、不制造事件、不改胎压；Jovi驾驶中不操作手机。实际登录、同意、车辆确认和权限选择由本人完成。

按主规范第6节冻结场景：App前台、退桌面、普通锁屏分别记录，故意Doze/重启/force-stop仅在一次性设备。自然事件结束后不要打开App、不要手动刷新、不要ADB启动worker；先判定无干预自动送达，再另跑人工刷新恢复。

采集t0真实结束、t1provider观测、t2接收、t3持久完成、t4API可读、t5自动发现、t6本地持久、t7UI可见、t8a提交系统、t8b实际通知、t9正确详情。缺少时间或只能给发现区间就写UNKNOWN/区间，不能猜；记录时钟误差及取证连接对后台的干扰。真实来源对账只用已授权只读窗口，不做全库或轨迹导出。

建议目标用于差异评估，不冒称已实现：API已可读时前台列表≤30秒、前台通知≤60秒、普通后台/锁屏通知≤120秒，>300秒列关键时效缺口；正常自然事件结束到通知目标≤5分钟。是否达到、Jovi是否确认目标单独记录，不能事后放宽。系统限制不伪装成准点保证，但也不把“必须一直打开App”算满足产品目标。

缺自然行程/充电时，相关用例为PENDING_NATURAL；缺第二真实同意用户时为PENDING_HUMAN。完成其他项目并整包回传，不制造自然事件凑PASS，不无限等待，不声称仍在无任务后台执行。个人归档的事件只能证明归档链，不证明Fleet首事件。

### 五、禁止扩权

不清App数据或日志，不卸载解决签名问题，不强制降级，不改Jovi的网络/VPN/代理/DNS/TTL，不上传VIN/账号/轨迹/token/签名材料/原始系统日志。禁止生产DB写、候选DDL上线、bridge升级、六条旧SOC及7113历史回填。本测试Prompt不授权部署、历史修复或生产备份恢复。发现相关需求时给出精确范围、风险、备份/回滚方案和独立人工门。

### 六、一次整包通过GitHub回传

结果放同一仓库owned codex/证据分支的docs/qa-evidence/qa-YYYYMMDD-runNN/，不要改主分支，不覆盖阶段产品源码。提交前脱敏；公开仓库仅保留随机别名、必要数值/时延和安全摘要，私有映射、完整数据留本地且不提供给远端。

必须包含：
- README结论、manifest版本元组、route-coverage、case-results及全部variant计数。
- 构建/单测/PG/race/UI/Release真实退出码与fail/skip；实际CI才填run/job/SHA。
- 字段对账、自然/合成分别记录的latency、资源与迁移/历史保留摘要。
- defects：固定SHA、复现输入步骤、期望/实际、首次偏差层、证据、是否已复现；根因未确认写候选。
- human-natural-gates：缺人员、权限、事件、环境与独立生产授权分别列明。

统一状态：PASS / FAIL / BLOCKED_ENV / PENDING_HUMAN / PENDING_NATURAL / NOT_RUN。每个未执行/跳过有原因，不能混入PASS。给出证据分支完整commit/tree/parent与改动文件表。

最终结论必须区分：是否完成本轮可执行测试、是否发现阻断缺陷、是否满足建议时效、哪些自然/人工门仍开放、是否可进入下一资格阶段。不得把源码审查、签名安装或历史归档观察当完整自然通知/Fleet验收。

以上全部安全可执行步骤一次连续完成后回传整包；只对真实人工/安全门询问Jovi，业务缺陷回远端修复，不本地接管实现。
