# 驻车详情：恢复已知行程间隔，保留未知遥测

## 身份与状态

- 独立云端源码候选，底座为 `58b529474c45a85f6bf7e042606b1b92ad3c92d0`
- 最终 `parked_interval.go` SHA-256：`a1c4462f53d5b5e5c2643e28f90e509e7023d87f4bd3f7e31f35643964344c5a`
- 不包含充电汇总 build44、输入电量 energy_used、旧781兼容包或其他未提交候选
- 已完成 Go 1.24.13 编译、专项 RED/GREEN、模块测试/race/vet/build；已完成 PostgreSQL 17.11 单用户离线SQL/执行计划验证；PostgreSQL 16、网络/pgx/认证/并发集成、Android 构建/测试及设备验收仍未完成。没有发布、部署、安装到用户设备或修改生产数据
- 此修复不依赖 archive bridge 升级，但正式交付仍需要后端与 Android 独立验收；不能直接把58b整包当作旧781生产底座的兼容补丁

## 根因与可复现路径

58b 中 `adapterResource` 的 parked 分支不读取任何行程，始终返回 HTTP 200、`data:null`、`error:history_not_collected`。Android Repository 只接受非空 data，因此转为 Error，ViewModel 将错误文本传给界面，Screen 原样显示。

restored、forward-SOC、monthly-labels、charge-energy-used 及两个旧781兼容候选均保留这段分支。月图和充电汇总已有独立候选，不属于本次修复。

合成复现输入：同一用户/车辆/归档源下，前一行程 08:00–08:30、后一行程 10:00–10:30，两个公开行程ID为11和12，中间无其它已记录行程。请求 `/api/matelink/v1/cars/7/parked/11/12`。旧分支丢弃08:30–10:00这段已知时间。已执行真正 Go HTTP RED：仅将隔离副本 main.go 恢复为58b旧路由，其它候选代码和同一测试保持一致，得到200/data:null/history_not_collected失败；新路由同一用例通过。测试提供合成车辆归属，让旧路由到达硬编码响应，而不是因nil provider导致无关失败。其它路由用例继续使用nil provider，以检查没有实时发现回退。

## 最小实现

1. 请求严格校验 GET、完整规范路径、正32位车辆/行程ID、两个不同ID。历史读取绕过会触发 Tesla provider discovery 的 `requireVehicle`，使用数据库行的 user_id/vehicle_id/kind 证明记录作用域；不调用实时车辆接口。
2. 一条 PostgreSQL SELECT 读取两个标量记录，并在同一语句快照检查其它行程。两个端点必须已经结束、时间有效、顺序正确、来源一致；归档实例和源车辆身份必须一致且非空。source只接受规范白名单；空值、空格包裹和大写别名都拒绝，不把缺来源默认为MQTT。
3. 中间记录检查不沿用列表的距离、短行程、质量或来源过滤。隐藏短行程、quarantined、跨源记录、重叠或未结束行程都会阻止将该pair当作相邻区间。完全嵌套在前一行程内的记录、相同起点的重复记录、从更早时间开始但与前一行程重叠的记录也保守拒绝，不能用“开放间隔里没活动”代替严格记录相邻性。外租户/外车辆及充电记录不阻断区间。
4. 返回前一行程结束、后一行程开始的时间及各自地址；两个地址保持独立。`address` 保持 null，不把不同端点当作一个确定驻车地点。
5. `source=drive_history_interval`。SOC、SOC变化、能耗、功率、温度、关联充电全部保持 null。采样数/覆盖时长/覆盖率0只表示此响应没有驻车遥测证据，不表示零消耗。
6. Android 新增可选 start_address/end_address，旧响应仍可解析。新增中英文说明，明确这是相邻已有行程推导的间隔，不代表完整驻车历史；分别标注上次终点和下次起点。404与503在UI分别解释为无法确认区间、暂时读取失败，其它错误保留原处理。

真实相邻性是“当前已存记录内未发现中间或重叠行程”，不能证明源历史无遗漏。此候选不会补造中间轨迹，不从SOC差或电池容量推断能耗，也不复用自托管 adapter 的未校验pair实现。

## 资源与安全边界

- 查询不读取、克隆或解码 route_json/charge_points_json；不触发摘要补齐、写入或schema变更
- 复用现有5秒读取超时；只返回两行标量，内存实现不深拷贝路线
- 单语句避免分开读取pair和中间记录导致并发插入穿过校验
- 两行输出不等于数据库扫描有界。阻挡检查分为archive与非archive两路，各自先限定user/car/kind，再按现有partial索引顺序检查标量；OFFSET 0只保留优化边界，没有LIMIT截断或遗漏隐藏记录。实际验证见下文。scope内仍可能线性读取，5秒预算未放宽；没有添加索引、迁移或关闭顺序扫描。优化屏障效果与PG版本/统计有关，不能承诺所有数据分布都使用同一计划
- 404表示无法确认该pair；底层读取异常返回503受控错误，不能把存储失败伪装成没有历史

## 已执行与未执行验证

已执行：

- 六个现有候选及58b的原硬编码分支一致性、Android错误传播链的源码断言
- 初版原SQL在SQLite内存数据库运行19个合成场景；最终辅助脚本使用明确的SQLite方言模型（OFFSET前加无限LIMIT -1、外层时间引用改为同一唯一记录的标量查询）：有效pair、外用户/外车/不存在ID、隐藏/隔离/跨源/未结束/重叠/嵌套/同时开始的中间行程、其它用户/车辆/充电记录及区间外记录
- 两个Android语言资源XML可解析，资源名无重复，新7个资源键一致
- SQL不含路线JSON、充电JSON、Stage2摘要列的检查

可重复运行：`python3 tasks/parked-interval-20261006/verify_sql_contract.py`。

SQLite模型只辅助检查选择/否决逻辑，不是最终PostgreSQL原文的执行证据。最终SQL原文已在下述官方PG引擎执行，但网络、pgx Scan、事务并发及认证集成仍未验证。

Go运行验证（2026-10-06续行）：

- 官方Go1.24.13 Linux amd64包SHA-256 `1fc94b57134d51669c72173ad5d49fd62afb0f1db9bf3f798fd98ee423f8d730`核对通过，在独立云目录解压；项目go.mod最低1.22。没有修改系统PATH、go.mod或go.sum
- 依赖通过Go官方模块代理获取，使用独立HOME/GOCACHE/GOMODCACHE/GOPATH、`GOTOOLCHAIN=local`与`-mod=readonly`；`go mod verify`通过
- 专项Go测试42个测试事件（含子用例）通过、2个PG测试跳过；旧路由同一HTTP用例RED为预期数据丢失
- 最终双屏障SQL改动后重新完成全模块普通测试与race，各377个通过、135个跳过、零失败（均含子用例；顶层分别276通过/57跳过）。全部135个跳过项都是缺少`JOURVOLT_TEST_DATABASE_URL`，不可理解为PG集成通过
- gofmt、go vet与go build通过。归档副本缺Git元数据导致第一次构建无法自动标注VCS身份，随后明确使用`-buildvcs=false -trimpath`构建；构建产物仅用于验证，不是发布包

已编写的覆盖：

- 8个Go顶层测试及子用例，涵盖实际adapter路由、严格路径/作用域、未知遥测null、同源身份、隐藏/嵌套/重复/重叠/开放行程、大路线不进入响应、地址缺失与取消。6个非PG顶层测试及其子用例已运行通过；真实PG认证与错误响应两个测试仍跳过
- 2个新增Moshi模型测试覆盖新字段与旧响应兼容，1个新增UI源码契约测试覆盖明确来源/端点标签；Android测试仍未运行，源码契约不等于Compose视觉验收

初次环境中的 `/usr/bin/go` 是同名棋类程序，官方Go release元数据读取曾被工具审核取消。用户再次授权后，仅重试同一官方调用一次并成功，再按已确认的最小范围安装官方工具链。未换用不明软件或替代下载路线。

## PostgreSQL离线引擎与资源反证

- 从Debian trixie官方签名仓库取得PG17.11/server、client及libpq三个包。系统Debian archive keyring验证InRelease成功；Packages及三个deb的SHA-256逐级匹配。只解压在独立云目录，没有安装系统服务、修改系统网络设置或读取生产凭据
- initdb成功，但临时私有Unix socket启动被运行环境返回Operation not permitted；一次工具级许可续行仍同样失败。两次均自行退出，没有改端口、改权限或切换TCP绕过
- 经批准改用官方postgres --single离线模式。加载候选中原始openStore、telemetry、summary、native shadow等schema，所有数据仅为合成。该模式不提供真实IPC、pgx、认证或并发锁验证
- 最终SQL原文通过20个真实PG选择/阻断场景，包括NULL归档身份、跨源隐藏记录、嵌套/重复/未结束行程与外用户/外车。没有把未知SOC或驻车遥测补为零
- 初版双EXISTS即使满足partial索引谓词，在220,002行样本仍出现archive全表Seq Scan；只修archive后，高scope占比时native分支也出现Seq Scan。保留反证，不把SQLite计划外推为PG保证
- 最终两路均采用scope子查询、各自既有索引顺序和OFFSET 0优化边界。约1%/18%/91%/100%目标scope、全部无阻挡记录的最坏读取样本，custom/generic共8计划均为Index Scan，没有Seq Scan、Sort或临时写入。没有新建索引、设置enable_seqscan=off或缩小应校验记录范围
- 本轮custom/generic实测时间依次为0.940/1.074 ms、45.610/31.820 ms、45.937/50.909 ms、81.264/90.577 ms。最大目标scope为200,002条；这只是此临时实例/缓存状态下的观测，不是线上性能SLA，也不是扫描量具有固定上限
- 最终Go专项、全模块、race、vet/build/module验证均在最终SQL改动后重新执行。独立只读复核将当前SQL逐字与20场景脚本及scope矩阵核对，并复核8份原始计划与Go终态日志
- 仓库Compose使用postgres:16-alpine。本次PG17.11证据不能替代PG16的实际计划和应用集成。所有合成fixture已清理，离线进程已退出，没有留下PG监听服务

## 下一步门禁

1. 已完成官方Go工具链gofmt、专项RED/GREEN、模块test/vet/build及race；若后续改源码需重新执行受影响门禁
2. 在支持合法本机连接的隔离PG16环境运行当前跳过的135项集成事件，覆盖pgx Scan、认证、取消、并发及原版本执行计划；禁止使用生产DSN。PG17离线结果只能作为补充证据
3. 对最终源码运行Android Debug/Release测试、Moshi/KSP/Compose、lint与构建，再审中文/英文、未知字段、返回/重复进入和旧后端兼容界面
4. 若要用于旧781生产API，另做精确底座适配与独立回归，不携带58b的Stage2变更
5. 发布安装之后，对获准真实pair验收时间/地址/推导提示；桥接SOC和其它数据问题保持独立
