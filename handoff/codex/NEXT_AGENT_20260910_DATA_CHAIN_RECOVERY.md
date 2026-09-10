# 本地 Codex 接手：MateLink 数据链修复与保留数据升级

日期：2026-09-10。以下是执行任务，不是要求重新规划或重写项目。

## 1. 唯一主线和权限边界

用户已授权修复、分层提交并推送源码。接续仓库 `Jovifei/tesla-master-mimo`，分支 `fix/20260907-onboarding-source-integrity`。不要改到父仓库的旧子模块，不要切回旧 main，不要重做 UI，也不要合并 main。PR #10 保持 Draft，直至本地门禁和真实车辆验收完成。

原基线：`e83e86eb711013bc280c1534c2cc76bffd1cef8a`。
本轮功能源码：`4e97691949ecef9a39fd135be3efb988907b0f61`。后续还有文档提交；必须 fetch 并以实际最新远端 HEAD 为准，不能回退到上述 SHA。

用户报告的本地目录：`E:\temp\matelink-onboarding-source-integrity`。先验证目录及 origin；不存在则查找实际工作副本，不假设聊天中的 E 盘文件已存在于当前运行环境。

```powershell
git remote -v
git status --short
git fetch --all --prune
git branch --show-current
git log -8 --oneline origin/fix/20260907-onboarding-source-integrity
# 确认工作区干净、无冲突后：
git switch fix/20260907-onboarding-source-integrity
git pull --ff-only origin fix/20260907-onboarding-source-integrity
git merge-base --is-ancestor 4e97691949ecef9a39fd135be3efb988907b0f61 HEAD
git rev-parse HEAD
```

不得 reset --hard、清理用户修改、强推、卸载 App 或 pm clear。若原 worktree 有用户修改，使用独立 worktree 审查；不要自动 stash 后覆盖。不要读取、打印或提交 .env、访问/刷新令牌、keystore、私钥、明文 VIN、精确位置或私人地址。现有包名、正式签名与用户数据必须保留。

## 2. 已实现的分层提交

```text
b96ba6dece04d02d13c9af60a03441dfd122c42a  fix(fleet): merge independent core and location snapshots
ea28dc7e59028ec62694cb593831602ed9bf26aa  fix(telemetry): recover transient setup failures and expose onboarding state
b9a0d856955864ac865848a80f8bbc4ee2826a9c  fix(ui): use generic placeholder for unidentified vehicles
dfe3624a33c404ce29ee99c1b6823205c1e4b142  fix(data): preserve confirmed metadata and expose discovery failures
2e72a5ce6f0b9caa54450cc41787d2193c8076cf  fix(history): restore cloud archives with scoped quality-preserving merges
4e97691949ecef9a39fd135be3efb988907b0f61  fix(sync): preserve cloud evidence across phone recovery and account changes
```

- Fleet：核心 vehicle_data 和 location_data 分别请求，位置成功不替代核心字段；位置权限/传输失败不清空已获得的核心信息，核心授权失败也不假装成功。观察到的 0/false 保留。元数据缺失不覆盖已确认车型。
- Telemetry：服务端借车辆状态/配对查询机会自动恢复可重试配置错误，使用持久时间戳、60 秒最小间隔和同账号同车辆单飞锁。缺钥匙、缺权限、账单阻塞及永久硬件/固件错误不无限重试。不是无访问时也会运行的定时任务。
- Android onboarding：失败、等待、权限、钥匙状态显式展示；使用已有会话重查，不因普通遥测配置异常反复发起 OAuth。用户明确选择后可先查看已有数据，不能自动静默跳过错误。
- 车型：未知车型使用通用图标及“车型待识别”，不默认为 Model 3/Model Y；首次实时查询填入车型后可刷新同一车辆元数据。
- 历史：读取全部可用页；后续页失败保留已取得页及本地历史，显示缓存/部分同步提示；不因云端为空或窗口缩短删除本地记录。
- 合并：账号/车辆作用域、规范化会话时间、稳定 ID、证据质量优先。较差导入摘要不能覆盖较完整记录；完整新记录也不能把未验证的本地字段变为 observed。前台与后台摘要入库都使用保留证据事务合并。
- 云端：重复导入保留已有字段/实际路线点、保持 PublicID，并保护原生遥测/隔离记录。所有 PostgreSQL 操作按账号车辆隔离；没有生产清理 SQL。
- 同步竞态：前台和后台请求前后检查账号/连接模式/服务身份；变更时取消或拒绝写入。禁止把旧账号的本地历史上传到新账号。

## 3. 云端历史与“两天”的精确定义

用户本轮再次确认云端应保存可恢复历史，因此旧 2026-08-28 “云端永不存行车数据”的限制不再描述当前实现。新设备仅恢复同一授权账号/车辆的数据；无关的新账号不得继承其他人的历史，不按 VIN 相同自动认领。

本轮的保守实现分成三件事：

1. 日常上传窗口：最近两个实际有数据的 UTC 日期；依据有效记录的 startedAt 归日、行程与充电联合取最近两个日期，不是现在减 48 小时。
2. 换机恢复：拉取服务端当前实际保留的所有页，并合并手机旧记录，包括已存在的更早云端存档。
3. 删除：本轮没有恢复自动两日删除，没有裁剪服务器现存旧摘要，也没有裁剪手机历史。两数据日上传窗口不等于服务器两日 TTL。

此前 e83 已取消服务器两日自动裁剪。当前首先防止数据丢失；严禁为了满足“两天”字面表述直接删除现存 66 条行程/2 条充电。若后续要严格云端有限保留，必须单独设计设备确认水位、保留元数据、备份与恢复验收，不能把“已收到服务器响应”当成所有设备都已持久化。

新手机只能恢复服务器确实拥有的数据。旧手机未上传且超出日常上传窗口的数据，不会因为新手机登录而凭空出现。`local_import_summary_only` 仍是不完整证据，只保留已有摘要；不得制造起终点、曲线、费用或电池健康。

## 4. 本轮实际验证及限制

功能源码 `4e97691949ecef9a39fd135be3efb988907b0f61` 在隔离 GitHub Actions 中执行 Go 测试，原始 JSON 统计：225 项、225 PASS、0 FAIL、0 SKIP；包含 PostgreSQL 16 集成测试。统计单位为带 Test 字段的 run/pass 事件，不含包级事件。

证据运行：`34424303295`、`34424360315`；最新证据工件 `10132138917`，名称 `data-chain-repair-evidence`。运行入口：
`https://github.com/Jovifei/tesla-master-mimo/actions/runs/34424360315`

下载后先比对 `reports/SOURCE_HEAD.txt`，再看 `reports/go-tests.json`，不要只看步骤显示绿色。工作流使用 continue-on-error 收集双端证据，最终总门禁仍失败。

Android 未完成本轮编译、JVM、lint 或 APK 构建：Gradle 8.9 wrapper 下载阶段出现 `java.net.ConnectException: Connection refused`；此前还遇到 Java CA 信任错误。没有关闭 TLS 校验。网络设置仅在隔离 CI 中，不进入产品代码。不能引用旧版“526 项通过”当作本轮验证。

旧 PostgreSQL 重放测试曾把“同值但新时间戳”当成 QoS1 原样重放并失败。已改为同时验证精确重放不推进、新时间戳推进、跨重启只完成一次；没有删除此门禁。

本轮未部署 ECS、未访问用户手机、未输入 Tesla 凭据、未收集真实车辆事件，未构建新正式 APK。代码中的版本仍为 2.1.11/build30；不要把旧 build30 APK 当成本轮修复包。

## 5. 本地第一任务：审查并完成可执行门禁

先阅读 `docs/audits/2026-09-10-data-chain-repair-and-cloud-merge.md` 和本文件。对已有修改做独立审查，发现编译/测试问题用最小增量修复并分层提交，不能只整理文档就结束。

Go：
```powershell
cd deploy/jourvolt-dev-mock
go test ./... -count=1
go vet ./...
go mod verify
go build ./...
```

使用全新临时 PostgreSQL 数据库设置 `JOURVOLT_TEST_DATABASE_URL` 后再次执行集成测试，严禁指向生产数据库。保留精确测试数、失败项、跳过项及源码 SHA；临时数据库可清理，用户现存数据不可清理。

Android：使用现有 JDK/SDK/Gradle 缓存和本地构建配置，必要时临时 4 GB 堆、两 workers；不能把关闭证书校验或任意镜像替换写入正式工程。
```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest --no-daemon
.\gradlew.bat :app:lintDebug :app:lintRelease --no-daemon
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
# 完成本地签名配置核对后，再运行正式 Release 构建：
.\gradlew.bat :app:assembleRelease --no-daemon
```

重点验证矩阵：
- 位置成功 + 核心成功；位置 403/503 + 核心成功；核心 403；合法 0/false；空车型及已知车型。
- 遥测临时错误限速自动重试，手动/自动并发互斥；缺钥匙、权限、账单、硬件不支持正确显示；取消、返回、进程重建不重复弹浏览器。
- 超过 50 条、多页重复、第二页网络错误、空云端 + 非空手机、恢复后再次同步不增重；行程和充电都验证。
- 原有费用、能耗来源、覆盖率、详情聚合不因摘要同步被降级或清空；摘要质量不被分析层错误升级。
- 同一账号新设备恢复、另一账号看不到旧记录；挂起请求期间切换账号或服务地址，旧结果不写入当前账号；上传亦需验证。
- 两个数据日窗口使用 UTC，日期有大间隔仍取实际数据日；手机早期历史不删除；云端重复导入不覆盖原生遥测、已有路线和有效字段。
- 旧版本已存在的同会话不同 ID：列表层做合并，但物理旧行未破坏性删除；额外检查直接 DAO 统计/分析是否重复计数，若发现须补幂等规范化及迁移测试，不能宣称所有旧别名已物理清理。
- 下拉刷新与自动刷新之后，云端失败/部分成功提示、车型刷新、详情点击和列表定位均一致。

## 6. 版本、安装与部署

完成本地门禁后，按实际远端版本递增版本号，建议下一候选为 2.1.12/build31，但远端已有更新时不得回退或撞号。记录应用包名、版本、正式证书指纹、APK SHA-256 和代码 SHA。

现有用户机只可使用同签名 `adb install -r`。安装前后比对 firstInstallTime、会话仍可用、服务/地图/语言配置和历史数量；首次安装时间不变不等于所有数据完整。不得 uninstall、pm clear 或以新包名替代。设备锁屏时等待用户自己解锁，不尝试绕过。

生产部署不在本轮网页端完成。遵守本地现有部署授权和 SOP；得到允许且门禁通过后，备份当前 API 源码/镜像和 PostgreSQL，再只升级匹配 SHA 的 API，保留原有数据库、MQTT、Fleet Telemetry、代理和私有配置；验证回滚路径。不要用 `docker compose down -v`，不要重建生产数据库。

分别记录 HTTP 服务存活、数据库就绪、官方 config_synced、首个 MQTT、业务采样状态。`readyz=awaiting_first_event` 或 HTTP 200 不能写成“真实遥测验收通过”。

## 7. 真实车辆最后验收

尽量复用现有会话/刷新令牌完成配置恢复；首次 Tesla 授权、缺失权限补授权、Tesla 要求的车辆虚拟钥匙确认，仍必须由车主在官方界面完成，不能代输凭据、绕过或伪造。

在同一候选上核对：核心 SOC/续航/车型 + 独立 GPS；官方配置 GET 确认同步；首个实际 MQTT；数据库/API/UI 来源与时间一致；一次含红灯停车的真实行程；一次完整充电。只能渲染实际收到的路线、速度和充电点。驾驶功率/能耗缺少合格来源时继续 unavailable，充电功率不能替代。

隐私页源码已纠正云端存储与 scopes 描述，但运营主体/联系渠道及线上实际页面仍需运营者补齐、发布并核验；不要把技术说明草稿视为法律审核或全产品上线批准。

## 8. 最终回报格式

返回：REMOTE/HEAD、CODE、GO、ANDROID、DEVICE、SERVER、TESLA、HISTORY、SECURITY、BLOCKERS、READY_TO_MERGE。每项写 PASS/FAIL/NOT_PERFORMED 和对应证据。区分源代码完成、构建通过、设备可用、真实业务通过；未验证的不得算 PASS。推送本地后续最小修复及验收报告到同一分支，更新 PR #10，不开竞争产品分支，不合并 main。
