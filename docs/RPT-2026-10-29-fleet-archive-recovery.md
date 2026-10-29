# 2026-10-29 Fleet、归档与 App 恢复记录

## 当前结论

本次目标是恢复后续自然行程的上传及手机显示，并核查 Fleet 为什么尚无事件。已消除同一游标被双桥写入的运行冲突，部署只读诊断和续期 API，并安装带独立确认弹层的 App；归档绑定仍过期，等待本人确认，尚未声称端到端恢复。

## 版本与证据

| 项目 | 本轮核对 |
| --- | --- |
| 手机已安装包 | com.matelink 2.1.55 / build74，SHA256 7264FB76A1714EE033275609685747C730056E3BEE27353C5CD20964A8DD8AA0；首次安装时间 2026-08-31 22:36:47 |
| 旧包源码归属 | DEX 的 fd84f887 仅代表当时 HEAD；旧包实际含未提交工作。资源证据显示已内置 widget configure，而干净 aac6 没有。不能直接退回干净 App 树构建安装。 |
| App 设备与候选 | E:\project\_worktrees\matelink-app-collection-recovery-20261029，codex/app-collection-recovery-20261029。既有 29 个生产文件及 9 个测试路径按哈希复制并本地保存为 6b40db37；2.1.56 / build75，代码 26b7f6fe 已安装。不宣称已完整重建旧 APK 的逐文件源码。 |
| 当前线上 API | ab9a245c77658fd1adc051bb9218234a1ccc6182，基于原线上 a2bac 构建；HTTP readyz=200，telemetry=awaiting_first_event。 |
| 后端修复候选 | 从当前生产 a2bac 精确重放 finalizer 修复为 a0d51a9c，保留生产已有功能；离线 Go 全量测试/vet 与独立 PostgreSQL 16 真实集成回归通过；只读诊断后续提交 350e1b7f 已部署。 |

初次合并 App 定向测试 95 项中，1 项仍预期旧版本号；同步版本夹具后 95 项通过。一次 KSP 缓存读取失败以关闭增量方式重试，保留失败日志，未清原工作区或手机数据。一次 Git 命令的 GIT_WORK_TREE 变量拼写错误导致候选暂存误读其他工作树的版本夹具；已修正本地版本提交，逐项核对有效根和源码哈希，未写原工作树文件。

## 采集与云端

- 本轮早间只读采样：TeslaMate 当天已产生 2 条行程记录，其中 1 条完整结束，另有 381 个位置样本；最新源记录时刻为北京时间 08:49。后续验收可使用自然记录，不制造行程。
- 云端 Fleet latest、event_buffer、route_points 均为 0，没有 telemetry_mqtt 来源会话；现有配对记录 available/config_synced=true 的更新时间是 10 月 4 日，不能代替今天官方真值。
- receiver 内部 /status=200，内部 TLS 及主机名/CA 校验通过，MQTT 的接收器和 API 连接、topic 一致。宿主 4443 监听且 Docker 链规则为 ACCEPT。
- 本机存在代理/TUN。虽然网卡绑定测试的 443/22 可连而 4443 超时，但服务器抓包没有观察到对照流量，控制条件未通过；不能据此认定云安全组阻断，也不能认定公网已通。
- 当前已打开阿里云控制台登录页，登录由 Jovi 本人完成；未输入凭据或修改规则。

## 已执行：消除重复桥写入

经只读比较，两套不同镜像的桥接进程使用相同来源 scope 和可写状态目录，且都配置为 always 自动重启，存在重复上传及旧游标覆盖风险。

本轮仅将历史重复桥 matelink-archive-bridge-before-safety-20261015 的重启策略改为 no 并停止；现行 matelink-archive-production 项目的生产桥保持运行，历史容器/镜像保留。操作前后共享 state.json SHA256 一致（65045907234CE2F0E1E4F741694C903AC6C1730EDA69834D0F2B7A4572E77D2D），未删除或回退游标，未动 TeslaMate 数据库。回滚不能同时运行两桥。

## Fleet 诊断代码缺陷、部署与处理

现有 config_synced=true 路径会跳过官方配置刷新；配置比较未核对 CA；Data Readiness 加载 pairing 又可能自动配置。已在服务端新增受认证、按本地车辆归属校验的 GET telemetry/diagnostics，仅查询固定官方 fleet_status、fleet_telemetry_config、fleet_telemetry_errors，使用现有有效授权、不刷新或保存授权、不唤醒或配置车辆。返回值和日志须去除账号/车辆标识、Token、原始响应和含身份的URL，未知保持未知。

服务端提交 350e1b7fb8add01405e8189dc27739544ec0cdb7 已在北京时间 09:57 完成公网核验：readyz=200/status=ok/build_sha匹配，未认证诊断返回401，其他运行容器ID保持不变。部署保留原生产 compose 与完整运行参数，只定向重建 API；原镜像和回滚定义保留，未变更数据库 schema。随后 ab9a245c 已定向更新 API 并新增独立 enrollment 表；公网 readyz=200、build_sha匹配，未登录诊断/预览401、非法创建400，其他运行容器ID保持不变。Fleet仍awaiting_first_event，不冒称实时采集已恢复。

App 只复用现有诊断详情文字，不修改原页面的尺寸、间距、字体和排列。手机实际设置已核对为 JourVolt/Tesla 云端连接。复审发现旧 readiness、car 和 status 读取也可能触发配置；新增只读 readiness_context 以当前账号/车的持久证据替换云端普通读取，不改变旧接口和自托管流程。先以真实消费者回归证明打开/刷新不会触发 pairing，以及后台/换账号/换车迟到请求不会发布，再验证新接口。

## 归档绑定仍待完成

当前唯一归档绑定于北京时间 2026-10-28 22:05 到期。本轮现行桥实际出现 import_http_403，原游标未推进。现有 owner-authenticated bind 接口可以旋转同 scope 30 天 upload-only 凭据，并保留历史与游标；检查时 App 到本机 bridge 的安全 enrollment 通路缺失，本轮已实现并部署，实际续绑仍需本人确认。不能直接 SQL 延期，不能把手机完整会话交给桥，也不能要求 Jovi 在聊天中提供 Token。

Jovi 已明确接受独立的来源授权确认弹层（原页面布局不变）。同 scope、短时设备证明及可幂等重领取凭据的后端已合并为 ab9a245c，通过隔离 PostgreSQL 回归并上线；手机弹层及本机助手仍在验证。在本人实际批准并取得上传回执前，不伪称归档续期或新行程已经上传。

2026-10-29 联合复审另发现：未发布候选的确认请求在连接失败或 503 时可能被底层自动重发，绕过重发前的身份变化检查。确认预览和决定均改用禁止自动重发的客户端；旧候选的配置检查先出现 RED，修复后 8 项定向回归通过，包括 GET/POST 在身份变化时遭遇 401、503、断连的本地 socket 测试；完整回归正在执行。车辆摘要与 provider ID 也各自验证，再通过本地同车证明关联；不得把两种不同 ID 直接当作相等。

App 与本机助手源码已本地提交 `26b7f6fee6919474a0d81ce11d91af1f1c30962e`，候选版本 2.1.56 / build75，945 个已跟踪 Android 文件已制作构建前 SHA-256 清单。提交本身不代表手机已安装或生产归档已恢复。

同一提交的全量 Android 验证：Debug 1705 项、0 失败、0 跳过；Release 1705 项、0 失败、8 项既有 Debug 专用跳过；Debug/Release lint 各 0 错误、273 个警告。签名 release 构建通过，APK SHA-256 `68F71F0802FE956D0C6FD4EC1EBD74C9553F9C1E2AB3077B7E04D047384154B0`，包名、版本、非调试标记和原签名匹配；945 个文件构建前后哈希相同，APK 内含提交号。证据为 `app-26b7-full-tests.log`、对应 XML 报告及 `app-26b7-apk-verification.json`。

2026-10-29 手机从 2.1.55/build74 以 `adb install -r` 升至 2.1.56/build75；首次安装时间前后均为 `2026-08-31 22:36:47`，App 启动后显示原有主导航，5 秒进程定向采样未发现 FATAL、ANR 或 SQLite 异常。全局日志采集联合命令被自动审查拒绝，改为只读取本 App 进程并仅保留计数；这不代表所有页面和真实行程已验收。

本机助手对两种中断增加了可恢复性检查：重建时容器已被移除，以及服务端 ACK 成功但响应丢失。恢复前验证持久阶段、精确配置摘要、镜像和挂载指纹，以及容器缺失或正常运行的实际状态；74 项合成回归通过。只读预检通过，手机已展示同来源确认弹层；Jovi 表示暂不方便操作，等待本人之后核对并决定，临时请求到期后需重新发起。上述回归和弹层显示不是生产续绑或自然上传的证据。

北京时间 2026-10-29 中午重新只读统计 TeslaMate 源库，当天有 2 条已结束的自然行程。这里仅统计数量，不包含车辆、地点或轨迹；这两条可作为续绑后验证云端和手机的数据样本，当前还不能说明已经上传。

## 验收门

- [x] 当前手机、源码与生产版本核对；发现并保留已安装的旧 WIP。
- [x] 后端 finalizer 候选离线测试及隔离 PostgreSQL 回归。
- [x] 停用重复桥并验证游标未变。
- [x] Fleet 官方只读诊断、readiness_context 服务端与 App 查询代码已合并；服务端已部署、App 已安装，官方诊断实读及首事件待验。
- [ ] 同 scope 安全归档续期与单桥上传回执。
- [x] App 全量测试、签名包、保数据安装及启动采样；各页面和本人确认仍须单独验收。
- [ ] 自然新行程：源库→云端→手机 Room→列表/详情同车同窗核对。

本轮 Hub revision 读取仍为 WinError10061；不自动重试、不伪造上报。源码、包、设备、生产和自然事件验收分别记录。参考：[Tesla Fleet Telemetry](https://developer.tesla.com/docs/fleet-api/fleet-telemetry)、[Tesla 车辆诊断端点](https://developer.tesla.com/docs/fleet-api/endpoints/vehicle-endpoints)。
