# 一次完整远端执行包：来源隔离与持久告警恢复

状态：待当前 CI 完结后集中派发。项目仅 `Jovifei/tesla-master-mimo` / PR17；本地接收仅 `E:\project\tesla_master\app_mimo`。继续现有远端聊天 `6acb55df-2ad0-83ea-909d-45bf96bfc837`，GPT-6 极高、GitHub 插件已附加。不新开微任务、不本地接管业务实施。

## 任务规模与最终交付

这是一个连贯的产品恢复阶段：身份解析、历史同步、Room 持久化、DataStore journal/租约、WorkManager、前台/系统通知及详情/趋势消费者贯通。不是修一个函数或加两个hash测试。预计本地实施与复审需约1–2小时，完整Android CI另需约20–30分钟；估计用于核对范围，不强制等待凑时长。

远端自行完成：审核以下源码因果证据并用真实编译行为复现 → 制定整个阶段方案 → 实际实现 → 测试及失败修复 → 最终复审 → GitHub固定源码/同SHA全量CI/交接。本地最后独立构建、replay、签名和既有门内验证。每次最终答复都表示本轮已结束，不能用“继续推进”冒充后台实施。

## 固定基线及不可回退底座

- 检查点 `f0629d8f0a7b02b2b865fd83479a8c12be2f1838`，tree `41861d4e30b641bc67f87f5223a4c6a0cd35bfbc`，parent `965faaa9c9c8d346f4b35ff2c64feb4391004ced`，Actions `38129340409`。实际CI结论在派发前补记，不能继承041a旧绿。
- `CompletionStateTest.kt` 原blob必须保持 `5b8feaeb69294661d8e1596f4340979a32470d68` 或在保留全部旧行为断言基础上有审查理由的增强。旧测试损失已恢复，不再复盘，也不再缩减覆盖。两个scope hash测试不是本阶段交付。
- 原E盘Owner现有提交 `c9dea6414b25cff0569bbf2a456454f97953be9e`，分支 `fix/20261011-cleanup-fake-data`，是另一已完成工作：hybrid summaries、invite codes、小程序配置与合规文字等。读取并评估与PR17的合入兼容，保留其已提交功能，不回退至早先f0；本地还有文档/AGENTS dirty。远端为最终接收准备兼容候选和冲突说明，不覆盖本地Owner。任何最终合成树都必须有独立固定SHA与完整CI，不能将两棵各自绿色树推断为集成绿色。
- 旧原始receipt/合格显示projection、d119实际Java回归、已实现journal及TPMS来源时间/cancellation均保留，不重做已解决项。

## 集中发现：源码因果证据，尚非Android运行证据

以下由两项独立只读审查在精确f062 Git对象核对，父审另读真实关键调用。未执行Kotlin/Room/DataStore/WorkManager候选行为测试；远端必须先建立可运行复现，允许通过证据纠正判断，不能直接照搬猜测。

### A. 历史与通知消费者使用不同的Cloud Room身份

`UnifiedHistoryRepository.kt:90-91` 走 `resolveVerifiedHistoryCar/cachedVerifiedHistoryContext`，含 account + API origin + vehicle UID。`SyncRepository.kt:105` 仍走legacy `resolve(car,scope)`；`VehicleContextStore.kt:111` 的 `cloudVehicleStableIdentity(account,uid)` 未含origin；`CompletedChargePrompt.kt:40,59` 走 `cachedContextForRemote`，`VehicleContextRepository.kt:70` 查legacy account+remote map，再按legacy local ID取Room。

复现：纯合成origin A/B使用相同account/UID/remote numeric ID。Legacy两者同L，而Unified分配OA/OB。A保留未消费charge12；B以相同source/start/end/ID但不同内容写L；回A离线，A的隔离journal仍可能被B覆盖的L行资格认证/消费。单origin也可能Sync写L而详情读OA导致提示成功但离线详情缺缓存。Detail VM会重新解析origin-aware ID，不能宣称B内容已泄漏到详情UI；实际待证为通知资格/消费与详情恢复断链。

验收：identity proof→Sync持久summary→journal记录→前台/系统资格→同一来源详情恢复必须使用一致的验证namespace；A→B→A、同numeric ID/source/window、重启离线，B行不能认证/消费A事件，A事件可恢复A详情。保留legacy数据，不自动重解释未知origin历史，不清空或盲迁移。

### B. 告警租约误用采样receipt时间作执行时钟

`TpmsPressureWorker.kt:278` 传 `acceptedSample.observedAt`；`:435,462-468` 又传给Tesla/custom claim的now；`TpmsCustomAlertStateStore.kt:188-193`、`TpmsStateDataStore.kt:126-129` 以now-startedAt判5分钟租约。

复现：receipt固定t0，首次claim后进程死亡，墙钟t0+15分钟重启、重放同receipt，worker lease age仍0，旧claim持续InFlight。旧store单测直接输入推进时间，未覆盖真实worker参数。

验收：证据时间保持t0、执行/租约时钟独立前进；真实持久store重建后过期换token，旧token不能commit/release；不制造新的TPMS观测、不重复告警。

### C. Durable pending恢复被新鲜压力字段挡住

后端 `telemetry_core.go:22` freshness 2分钟；`telemetry_service.go:375` 去掉过期压力与provenance。worker Tesla/custom重试只在 `accepted` 循环 `TpmsPressureWorker.kt:269-309`，无独立pending drain。

复现：有效低压形成pending，权限关闭发送失败；无新collector事件。15分钟后恢复权限，API已无合格压力，accepted为空，持久pending永远不重试。仅修B不够。

验收：基于持久真实告警证据和独立重试时钟，在无新压力事件的后续同步/重启恢复发送；证据时间不刷新，跨scope失效、成功一次、失败不消费、取消可安全恢复。定义告警有效期和恢复契约，不随意发送任意陈旧告警。

### D. 四轮receipt时间不相等时Tesla告警被整体清掉

`verifiedTpmsSamples` 按时间分partial行；worker `:276` 每次只把单个sample传 `onlyVerifiedWheels`，`TpmsObservationEvidence.kt:37-42` 要同一个sample四轮齐全才保留warning；worker `:433` complete guard因此全部拒绝。

复现：FL/FR/RL/RR压力receipt分别t0、+1s、+2s、+3s，完整软警告中FL=true；四partial rows全部warning=null，Tesla消费者零通知。

验收：压力与warning各自真实来源/时间合同，完整合格warning不要求四轮压力collector瞬时相等；缺失warning不能清除已有warning，不伪造Tesla传感器原生时间。需要Go输出合同→真实Android消费者跨层测试。

### E. Room novel admission与独立提醒queue有崩溃窗口

worker `:254`先Room提交novel，`:256-259`后DataStore record变化。重放novel=null，不再record。

复现：FL2.8→2.4 bar、阈值delta0.3；Room提交后、queue record前中断，重启重放同receipt永远没有pending；后续恒定2.4无法再产生变化。

验收：最小可恢复outbox/处理状态或可靠重建协议，故障注入覆盖commit后中断、重启、重复poll，提醒最终一次且receipt不变。不要靠两个数据库“通常连续调用”当原子性，不未经授权迁移真实数据。

补充同阶段核对：TPMS worker/trend legacy resolver与origin-aware历史ID是否一致；trend刷新捕获remote Int/window但sample/drives分别解析账户，检查账户切换后的异步结果与高速行程证据join。将它们与A统一设计，不叠加另一套identity helper。

## 必须真正执行的资格矩阵

1. 使用真实resolver/store、Room DAO/事务、持久journal重建、生产worker/prompt/sync adapter；依赖注入时钟及权限/发送器即可，不把测试写成源码字符串检查或helper镜像。
2. 正负矩阵：A/B/account/origin/car/source/window，旧API缺字段，0/未知/负能量，乱序/重复/迟到记录，分页完成与取消，拒绝权限→恢复，重启/租约token，Room提交后故障，部分轮胎receipt、warning来源时间，legacy缓存安全读取。
3. 保留原全部回归和原d119独立Java/Moshi真实编译replay。新增有失败证明的实际调用链回归；不得改生产可空类型迁就错误fixture、弱化阈值或删旧测试。
4. 现有 `.github/workflows/history-memory-review.yml` push覆盖原阶段分支，可真实执行Android Debug/Release/lint/R8、Go隔离PG16/race/资源/TLS/Web/源码审计。无本地shell/dispatch不是阻塞。读取push runs应使用GitHub fetch的REST URL，PR-only查询空列表不能说明无CI。
5. 固定最终SHA/tree/parent，实际每个job、日志/XML计数/失败修复及artifacts，最终代码复审、计划完成对照、本地原E盘接收兼容矩阵、尚未执行的自然/人类门。只给中间commit/CI启动/两hash测试或“下一读取点”不合格。

## 授权与边界

Jovi已授权远端完整源码阶段的审查、修复、下一阶段规划与实际实施、GitHub提交和隔离CI；本地最终原E盘接收/保留dirty/构建验证已授权。禁止生产API/DB/Nginx/bridge部署、旧SOC/胎压历史补入、唤醒车辆/制造事件、私密身份轨迹凭证、主机网络/CA/TLS降低。任何新增迁移/生产写动作先给具体范围、备份、幂等、回滚方案，不执行。真实Fleet首事件/多用户/自然胎压/通知、设备签名安装与源码CI分别验收。PR17保持Draft，不凭CI合main。

## 派发前实际CI收据
2026-10-11 18:04北京时间独立REST+实际job核验：Actions38129340409，精确f0629d8f，completed/success，13/13 SUCCESS；Android114440281076真实日志BUILD SUCCESSFUL19m45s，verify114440281333真实Go/隔离PG303run/303pass/0fail/0skip。当前CI没有失败，不捏造CI失败；上面A-E是待真实编译行为复现的SOURCE_CHANGES_REQUIRED。实际Android测试计数以android-summary/原XML再核对，不能从Go计数推断。
Android实际artifact11698439401由summary job114443850824解析原TEST XML：Debug796/0fail/0error/0skip，Release796/0fail/0error/8skip。Summary不替代已同时核实成功的actualAndroid114440281076；本轮无本地产品测试，不称本地PASS。