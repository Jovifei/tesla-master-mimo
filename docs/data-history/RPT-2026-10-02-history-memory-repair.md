# 2026-10-02 历史读取内存整改：实施与验证报告

## 0. 状态与入口

状态：`SOURCE_FIXED / GO_POSTGRES_PASS / TARGETED_RACE_PASS / PRODUCTION_NOT_DEPLOYED`。

仓库：`Jovifei/tesla-master-mimo`。审核基线：`main@f0dcd447dfcfdaf3a479f78eebd4077cd2e52a45`。
修复分支：`fix/20261002-history-metadata-memory`。
经过下述测试的功能提交：`9bda94f4aa73ee038722a6b192e43e7604e780f3`；交接时还会有随后文档和只读 CI 提交，应 fetch 实际最新分支，不回退。

本报告是增量审核，重点覆盖历史元数据、列表、详情与相关写入风险，不代表全仓每一行或所有安全问题都已审完。没有修改 Android/iOS/小程序源码，没有删除真实历史、修改保留期限、合并 main 或部署生产。

## 1. 原始事故证据，不改写为推测

依据 `DBG-2026-10-02-login-oom.md`：内核确认四次 global OOM，API RSS 约706–817 MB；Nginx 在重启窗口连接拒绝，登录入口502。第五次容器重启原因尚未单独核验。原事故没有逐请求分配剖析，因此不能断言每次 OOM 都只由同一个函数引发。

归档清单为413条行程、632055路线点、单条最多29583点，另有53条充电；数据库路线 JSON 存储约9.13 MB。数量本身不能证明重复导入、伪造或实际采样频率。数据库存储压缩量不是 Go 展开对象后的内存量。

原现场线上 API 为 `781c4025a720a257fdd6d2f8ee9a309655bfa020`。本轮没有连接生产读取新状态，不能将旧现场快照称为本轮实时探测。

## 2. 已确认的问题及本轮处理

| 问题 | 原行为 | 修复后的行为 |
| --- | --- | --- |
| vehicleItems 计数 | 两次 full history，再 len | 每类一个标量元数据查询；SQL COUNT，不传路线或充电点到 Go |
| hasHistory 存在性 | full history 后判断非空，丢弃调用方 context | SQL EXISTS，继承 context，5秒数据库读取上限 |
| data-readiness 来源/存在性 | 同类重复 full history；归档来源可能落到 telemetry 回退值 | 每类一个轻量元数据结果复用，识别 teslamate_archive，错误不假装暂无历史 |
| 历史列表 | 已有摘要 SQL，但先读全部摘要、提取所有行的 JSON 端点，再在内存过滤分页 | 日期过滤、稳定排序、LIMIT/OFFSET 在数据库完成；只映射本页摘要 |
| 默认页/极大页码 | 省略 show 可返回全部；乘法可能溢出 | 默认50，最大100；先与总数比较，再计算 OFFSET |
| 计数查询失败 | 可能显示0 | total_drives/total_charges 为 null；不能把数据库不可用说成没有记录 |
| 详情取消 | HTTP context 没有传入数据库读取 | 单会话详情继承请求 context 与5秒读取超时，仍返回完整原始点 |

新增 `deploy/jourvolt-dev-mock/history_queries.go`，修改 `main.go`、`readiness.go`、`telemetry_http.go`、`telemetry_service.go`，新增 `history_queries_test.go`。

元数据查询只读取 user/vehicle/kind、计数、时间、来源和质量列，完全不引用 route_json/charge_points_json。COUNT 的数据库成本仍会随会话行数增长，不是O(1) CPU；本轮消除的是全部历史点进入应用内存的放大。

列表使用只读 Repeatable Read 事务，让单次返回中的 total 和 rows 属于同一快照；跨请求并发插入造成的 OFFSET 翻页漂移仍待后续 cursor 方案。SQL先 MATERIALIZED 分页，然后只提取本页首尾坐标；这仍可能让 PostgreSQL解压本页 JSON，后续应持久化独立端点列。

旧 full-history 帮助函数暂留给内部兼容/回归，生产元数据与 HTTP 列表不再调用它。不能以后为了复用代码重新接回去。

## 3. 实际执行的证据

运行：`https://github.com/Jovifei/tesla-master-mimo/actions/runs/36977555192`
工件：`history-memory-evidence`，ID `11214181421`。
工件ZIP SHA-256：`50d2361a0a161d301b51d09033517fbdcc3cd12c9d258f563fdb9d67e7240c33`。
工件内 `SOURCE_HEAD.txt` 为上述9bda94f功能提交；六个Go文件逐一与本地候选SHA-256比对一致。

- `go test ./... -count=1 -json`：274个 Test run，274 PASS，0 FAIL，0 SKIP；包含临时PostgreSQL16。
- `go vet ./...`、`go mod verify`、`go build ./...`：通过。
- 历史元数据/取消/HTTP分页/数据库隔离相关定向 `go test -race`：通过；不是全仓 -race。
- 修复前基线另一次隔离运行268项通过；不把这268项当成本次新增验证。
- Android 563项、小程序61项是用户此前合并轮证据，本轮没有重新执行，不能冒充本轮测试数。

### 合成大历史回归

夹具只匹配规模，不使用用户数据：413条行程、632055点、最大29583点、53条充电。坐标和时间是重复的合成值，不代表真实采样分布或压缩比。

| 操作 | Go TotalAlloc增量 |
| --- | ---: |
| 旧全量history一次 | 545909824 bytes，约520.62 MiB |
| 新元数据+EXISTS一对查询，预热后5次均值 | 2252 bytes，约2.20 KiB |
| 新20条摘要页 | 151912 bytes，约148.35 KiB |

这是特定测试进程的累计分配增量，不是峰值RSS、稳态堆、整个登录请求分配、PostgreSQL内存或线上容器实际下降比例。不得对外宣称服务器已从800MB降到2KB。

测试同时验证：完整29583点详情仍可恢复；路线读前后哈希未变；总点数仍632055；行程与充电4页/66条流程、极大页码、日期边界、合法0/false、隔离记录、账号隔离、8个并发元数据读取、取消上下文和数据库错误语义。非当前页的异常JSON不会因扫描全库端点阻塞当前正常页。

## 4. 仍需处理的风险，不与本轮完成项混淆

P1：单条大详情仍完整解码，多个详情/归档并发可叠加。下一步做全局+租户公平限流、按字节限制在途大请求、超时及压测；健康/登录不能排队在详情任务后面。

P1：`applyPostgresSessionEvent` 每个字段事件重新加载当前会话整段 route_json/charge_points_json，再整体编码UPDATE。长行程存在累计写放大，应改为追加点/有界块与固定大小会话状态；涉及状态机和去重，不能在此次止血中仓促重写。

P1：列表首尾坐标仍从本页JSON取；下一轮添加首尾坐标、采样点数、版本等独立摘要字段，分批回填并校验。大 OFFSET 和每车计数 N+1 后续优化为 cursor/批量查询，必要时由索引与经过校验的汇总表支持。

P1：Android 当前同步代码能处理详情和聚合，但没有证据保证每一条原始路线已完整、校验、可离线恢复。因此不能把“同步完成”当成允许云端删原始点的设备ACK。

P2：存储分层是新提案，见 `ADR-2026-10-02-tiered-history-storage.md`。本轮没有实现对象存储、本地全量原始档案ACK、严格两日TTL或云端清理。

## 5. 部署硬门禁

由本地Codex复审及按授权SOP执行：确认生产实际SHA和资源限额；备份数据库与旧镜像；测试数据库绝不指向生产；仅更新API，保留数据库/Telemetry/MQTT/密钥/来源归档。不要 `down -v`、卸载App或清数据。

核验真实车辆列表和readiness、分页/完整详情、登录入口、来源与账号隔离；采集Go堆/分配、进程RSS、容器重启、内核OOM、Nginx502及延迟。私有pprof仅回环或认证隧道，不暴露公网。先受控回归，再观察24小时与后续7天；healthz=200不能代替这些证据。

本地接手入口：`handoff/codex/NEXT_AGENT_20261002_HISTORY_MEMORY.md`。
