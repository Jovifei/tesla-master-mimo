# 聊天规划与本地执行：来源隔离、持久恢复完整阶段

## 最新授权与分工

2026-10-11 Jovi明确：聊天模式，不使用Work；远端GPT-6极高给完整执行方案和集中代码审核，本地Codex在 `E:\project\tesla_master\app_mimo` 实施、测试、按审核修正。覆盖早先remote-only执行合同；不创建C盘worktree、不回退Owner功能、不重复征询同一A-E源码范围。

远端原聊天 `6acb55df-2ad0-83ea-909d-45bf96bfc837` 保留。Work跟进自动化已PAUSED；旧Work创建只曾返回client3f67，未证实启动，不声称已成功取消远端执行，也不再使用它。若后来出现真实执行ID，应传达Jovi停止Work的明确决定，不能停止其他项目。

## 当前基线

- Owner `c9dea6414b25cff0569bbf2a456454f97953be9e` / `fix/20261011-cleanup-fake-data`，业务源已提交，AGENTS/文档/tasks还有dirty。
- 已fetch远端 `f0629d8f0a7b02b2b865fd83479a8c12be2f1838`。其CI是远端继承证据：796双变体（Release8skip）、Go303、13job成功，非本地执行结果。
- 五项因果与复现见 `docs/RPT-2026-10-11-persistent-recovery-stage-packet.md` / GitHub证据ref44690886。它的旧分工文字已被本文件最新授权覆盖。

## 整阶段计划（待远端完整方案补充）

- [x] 远端聊天一次给出整阶段实施方案：两树兼容、A-E整体设计、行为回归、验证顺序、最小文件范围、无真实迁移写的方案及风险。
- [x] 冻结并逐文件哈希备份481/481当前dirty/untracked；在原目录新建本地阶段branch，合并c9与f062但暂不提交，保留Owner代码与文档。未stash/reset/clean或丢失既有内容。
- [x] 复现A-E实际消费者缺陷，必要时纠正静态判断；集中真实行为失败作为修复依据。
- [x] A：验证account/origin/car身份贯穿Sync/Room/journal/prompt/detail/TPMS/trend；旧未知namespace保留且不能冒充新来源。
- [x] B/C：证据时间与租约执行时钟分离；持久pending独立于新鲜API压力的可恢复消费，scope/有效期/权限/取消/旧token守门。
- [x] D/E：warning独立真实时间与来源；异步四轮receipt正确消费；Room到queue中断恢复且最终一次，保留原receipt，优先复用现有JSON/事务能力。
- [x] 真实resolver/DAO/store重建/worker/adapters正负和故障注入回归，原d119/全部旧断言保留。先focused，再完整Debug/Release/lint/R8及隔离GoPG/race/TLS/Web。
- [ ] 形成一次完整代码/测试/风险/固定SHA证据包，远端集中审核；执行全部有效反馈、重跑受影响检查，直至整阶段闭合。
- [ ] 提交固定GitHub候选、核对同SHA CI和本地源码等价；设备/签名/真实数据门独立，不凭CI合main或部署。

## 边界

生产API/DB/Nginx/bridge、历史补入、车辆wake/制造事件、主机网络/CA/TLS、真实凭证/轨迹及实际数据迁移运行不在本次普通源码授权内。Source/schema候选与合成测试可准备；任何真实迁移/安装的新增风险先具体审查。原com.matelink数据配置、签名及回滚包保留。所有PASS必须有本次真实命令/日志，不将Mock/CI/缓存当provider/production验收。

## Review

远端完整PLANv2已到达，本地正在实施。实际Go警告丢失RED已复现并修复；当前未提交源码Go/隔离PG16通过317项，Web13项及构建通过，TLS临时Linux副本29项及策略通过，10个source audit与DAO SQL检查通过。Android新增回归及完整848双变体（Release8skip）/lint/R8已真实PASS，详见当前RPT。所有证据均不等于固定SHA或自然Fleet验收。Work不再参与。

## 远端PLAN v2已到达
普通聊天“持久恢复方案 Planning”6acb8405-88a8-83ea-b126-8339d81ca57a（GPT6极高）已核验C2C app_mimo/currentbranch/c9dirty，并读取本地PLAN及A-E包。确认完整合入f062业务修复、verified identity全消费者、receipt与execution clock分离（不采用跨reboot wall+elapsed AND）、原pending独立drain、Tesla软警告自己的source/time、最小Room/queue恢复协议及先红后绿真实消费者回归。原初稿未读源码且warning/lease方向错误，不作为实施依据。具体clock/outbox字段以真实实现和失败复现决定，避免不必要schema/新状态。静态merge-tree结果cec358764257558a7259b67859ca3b368415726d，无代码冲突；即将原目录安全合成，保留dirty文档。
