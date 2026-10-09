# 本地 Codex 接手：MateLink OOM止血、上线验证与存储分层

日期：2026-10-02。先接收已完成修复，不重新从旧WeChat分支规划，不推倒重写产品。

## 一、当前真实基线

仓库 `Jovifei/tesla-master-mimo`；原审核main为 `f0dcd447dfcfdaf3a479f78eebd4077cd2e52a45`。
修复分支 `fix/20261002-history-metadata-memory`；已测功能提交 `9bda94f4aa73ee038722a6b192e43e7604e780f3`。后续文档/CI提交仍在该分支，必须读取最新远端HEAD，不回退。

本轮网页端：已修源码、执行隔离PostgreSQL回归并推送修复分支；未合并main、未部署生产、未访问手机或Obsidian、未改Android/iOS/小程序、未重新打包APK、未改变数据保留期限。

旧现场资料：Android `2.1.22/build41`；生产API `781c4025a720a257fdd6d2f8ee9a309655bfa020`。这只是2026-10-02故障记录中的快照，不是本次接手时实时状态。主工作区据用户为 `E:/project/tesla_master/app_mimo`；请核验实际origin和worktree，不照抄路径后覆盖文件。

## 二、先核对代码，再执行

```powershell
git remote -v
git status --short
git worktree list
git fetch origin --prune
git log -8 --oneline origin/main
git log -8 --oneline origin/fix/20261002-history-metadata-memory
git merge-base --is-ancestor 9bda94f4aa73ee038722a6b192e43e7604e780f3 origin/fix/20261002-history-metadata-memory
```

在隔离worktree审查修复分支。若main后续前进，比较后增量合入，不重置、不强推、不自动stash覆盖用户工作。原功能/临时分支不删除；7条历史未合并分支不是本轮修复入口。除受控修复分支外不更新其他引用。

必须阅读：

- `docs/RPT-2026-10-02-history-memory-repair.md`
- `docs/DBG-2026-10-02-login-oom.md`
- `docs/RPT-2026-10-02-review-baseline.md`
- `docs/ADR-2026-10-02-tiered-history-storage.md`
- `docs/LESSONS-2026-10-02-history-resource-bounds.md`
- `docs/ARCH-DECISION-2026-09-27-fleet-teslamate-hybrid.md`

旧“云端永不保存历史”和“服务器两天自动删完”的叙述不能替代最新全量归档决策。分层ADR为新提案，未批准具体TTL，不能据此直接删原始数据。

## 三、已完成源码修复，不要重复造另一套

`history_queries.go`新增：SQL COUNT/EXISTS/来源/质量标量查询；按user+vehicle+kind约束；5秒读取context；数据库日期筛选、稳定排序、分页；默认50、最大100、极大页码安全；单次total与rows使用同一只读快照。

`vehicleItems`不再为计数读路线，失败返回null不冒充0。
`dataReadiness`每类一次元数据结果复用，归档来源显示teslamate_archive，故障不是collecting。
`hasHistory`用EXISTS；HTTP列表不再调用全量history或全库summary函数；详情仍单会话完整读取并继承请求context。

此阶段没有详情分块、并发准入、对象存储、本地完整归档ACK、点表写入重构或热库删除。发现剩余风险要如实登记，不能把新方案当成已实现。

## 四、已有可复核测试

Actions run `36977555192`，artifact `11214181421`，`history-memory-evidence`。
地址：`https://github.com/Jovifei/tesla-master-mimo/actions/runs/36977555192`。
工件ZIP SHA-256：`50d2361a0a161d301b51d09033517fbdcc3cd12c9d258f563fdb9d67e7240c33`。

274个带Test的run/pass事件，0fail、0skip，临时PG16。vet、mod verify、build通过；历史查询相关定向-race通过。此后文档CI如有新run，按实际SOURCE_HEAD区分。

大夹具为合成413行程/632055点/最大29583点/53充电。旧完整history一次TotalAlloc=545909824B；新metadata+exists预热5次均值=2252B；20条摘要页=151912B。完整详情点数、总点数和读前后哈希保持。

这是累计分配，不是峰值RSS/生产降幅；合成点不是真实用户轨迹。Android563项或小程序61项是此前记录，不能作为本轮后端修复的运行证据。

## 五、接手第一阶段：独立复审与本地门禁

先跑本轮新增用例与全量Go，再检查API契约变动对现有客户端的影响。

```powershell
cd deploy/jourvolt-dev-mock
# 将环境变量只设置为明确的临时测试数据库，绝不可指向生产：
# $env:JOURVOLT_TEST_DATABASE_URL = <isolated test DSN>
$env:JOURVOLT_REQUIRE_HISTORY_PG = '1'
$env:JOURVOLT_RUN_LEGACY_MEMORY_BENCH = '1'
go test ./... -count=1 -json
go vet ./...
go mod verify
go build ./...
$env:JOURVOLT_RUN_LEGACY_MEMORY_BENCH = '0'
go test -race -count=1 -run 'TestHistory(MetadataMemory|ReadCancellation|MemoryHTTP|QueriesPostgresScope|MetadataSQL)' ./...
```

优先复用本机已安装镜像/PG；没有Docker时可用专用本地PG或本仓库隔离CI，不继续长期以SKIP替代必需PG验证。旧全量基准会故意分配大量内存，只能在隔离测试机运行，不能在故障生产机上压它。

复审矩阵：

1. 行程、充电66条4页；省略show默认50、请求超大show实际100、最后空页、日期边界、同起始时间稳定排序、当前页与非当前页异常。
2. user与vehicle隔离、同数字carId跨账号访问拒绝；source/quality/coverage语义及null/0/false保持。
3. counts/readiness不引用路线列；生产HTTP元数据和列表没有full-history调用；取消后数据库工作停止。
4. 单详情完整29583点保持；不把首尾摘要画成完整轨迹；数值单位与坐标系不变。
5. 2、4、8、16并发的受控查询、详情和导入组合；采集p95/p99、峰值RSS、堆、数据库CPU/IO。不以平均分配替代并发内存证明。
6. 所有调用方必须读取服务端实际page/show/total_pages，不能请求show=1000后用1000判断终止，防止新上限让旧客户端漏页。
7. 原生客户端统计字段允许null；验证计数不可用不是0，旧会话和历史不会因服务503被清空。

确认没有回归后提交必要最小修复到同一分支。合入main按现有代码审核权限执行；没有明确合并授权时保持Draft PR。不要把历史CI编码补丁再手动apply；修复分支当前是正常源码。

## 六、第二阶段：生产止血发布（仅在明确授权范围内）

发布前单独读取真实生产build_sha、运行资源、PG连接、日志与已有备份。只记录脱敏摘要；不打印.env、token、AppSecret、私钥、精确位置、明文VIN或原始轨迹。

备份：数据库可恢复备份、旧API镜像/源码SHA、当前配置引用。敏感备份保存在授权私有位置，不提交Git。

按既有SOP仅升级API到审核后的实际SHA，保留数据库/Telemetry/MQTT/代理/源绑定和密钥。不做 `docker compose down -v`、drop/truncate、卸载或 `pm clear`。若本轮仅后端变动，不额外生成App版本或要求车主重新授权。

发布验收：
- 服务/反向代理真实build_sha一致，单独探测登录入口而不完成用户授权。
- 车辆列表、数据就绪、历史各页和一条完整详情可读，来源保持归档/Fleet区别。
- 对同一用户/车核对原摘要数量、总点数及抽样哈希；不要为了数点在API装载所有路线。
- 核对内核OOM、RestartCount/StartedAt、RSS/Go堆/分配和502；第五次旧重启原因不可自动写成OOM。
- 先记录受控验证，再建立24小时、7天观察；不得仅凭healthz200宣布故障终结。
- 失败按备份镜像回滚API，保持数据库兼容，不通过删除用户历史恢复服务。

私有pprof只能回环/认证隧道，禁止公网暴露；profile可能包含敏感数据，不放公开仓库。GOMEMLIMIT需要实际容器预算，不能盲目设置极小值制造GC thrashing，不能替代解除无界对象引用。

## 七、第三阶段：继续降负载，而非削掉历史

优先次序：
A. 将首尾坐标/点数/来源质量等摘要列独立持久化；小批回填，EXPLAIN验证索引，在线建索引须独立于启动事务并核验锁影响。
B. 详情和导入全局+租户并发/在途字节预算，短请求优先；明确429/503与Retry-After、队列可取消，客户端需退避重试。
C. 原始点按有界chunk追加，状态机只存固定大小运行状态；别每个Telemetry字段都重写整段路线JSON。补跨重启、QoS1幂等、红灯/挡位、充电基线与完成事件唯一性测试。
D. cursor/revision分页与增量同步；不能用简单时间相等跨来源合并，更不能按新用户同VIN认领旧用户历史。
E. 本地档案+云端轻量热库+完整冷归档的先双写方案。尚未实现的本地全量落盘、manifest/hash、ACK、归档恢复都必须做真实测试后再讨论热库清理。

## 八、用户本地大数据的明确答案

推荐原生App本地索引+分块压缩详情，云端热库保存精要摘要与归属/质量/恢复索引；完整冷归档保留换机恢复能力。小程序只作有限缓存，不作唯一长期副本。

只存手机也可以作为产品选项，但必须明确旧手机损坏/卸载后原始路线不能恢复。不能同时无条件承诺“云端只有两天”和“新手机恢复所有多年原始点”。一个设备ACK不代表备份，更不代表未来设备能恢复。

## 九、交付与文档更新

维护故障记录、整改报告、阶段待办和经验文档；主工作区的Obsidian镜像由本地按现有同步机制更新并核验，不宣称网页端已同步。本轮新增经验在Docs目录，接手时将相关规则追加到tasks/lessons.md并在tasks/todo.md登记，勿重写旧经验。

最终按 `REMOTE / CODE / GO / PG / PERF / CONTRACT / DATA_PRESERVATION / ANDROID / SERVER / SECURITY / STORAGE_MIGRATION / BLOCKERS` 分别报告PASS/FAIL/NOT_PERFORMED、源码SHA与证据。区分“可审核合入”“已部署止血”“长期存储迁移完成”。本轮仅第一项有修复和隔离验证，生产与长期迁移仍独立。
