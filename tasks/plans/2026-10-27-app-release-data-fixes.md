# App数据链路修复与版本留痕（已授权）

Jovi于2026-10-27授权按上线审查结果修改，并要求在App分支、提交代码、按日期和版本记录问题与改法。实际目录`E:\project\_worktrees\matelink-release-data-audit-20261026`；分支`codex/app-release-data-fixes-20261027`，从PR27固定基点cdfe0b26和已审954文件候选继续，不切换/覆盖原目录或其他App任务。

发现版本：2.1.53/build72候选（不是声称首次引入）；本分支修复候选：2.1.54/build73。版本号/提交/测试/安装分别记，不把源码提交当手机升级或上线通过。沿用不改页面布局、参数排列、字体/尺寸/间距/颜色的约束；只修来源、计算、持久化、formatter和路由绑定。

- [x] 核对AGENTS、lessons、审查与版本台账，切换独立App修复分支，954文件before哈希一致。
- [x] 保存明确的既有候选基线与审查文档，导入变化单列，不认领为本轮修复。
- [x] F1 登录车辆选择/会话与缓存单位；TPMS旧profile作用域、部分status状态转换。
- [x] F2 充电费用/三态/当前会话/跨窗合并，补真实wire及Room正反例。
- [x] F3 行程能量来源、曲线时间/单位、路线与驻车nullable端点。
- [x] F4 我的分析：Vampire作用域、Battery同端点/单位、温度与standby真实零/排序。
- [x] F5 地理SQL/单位/年份/字段覆盖、Timeline分钟回退；不重改已反证正确的能量归一化。
- [x] F6 创建/长途/报告/导出作用域、记录保留、路段对应及冻结输出。
- [x] F7 更新采集状态及源缺失边界，未开放实现不擅开入口，不用更多Fleet轮询填空。
- [x] 分批聚焦RED→GREEN；最终双变体、lint/R8、必要原生编译/隔离验证，需求及质量复审。
- [x] 按已授权范围提交，并回填问题版本历史、Git提交和未验门。

文档：`docs/APP-ISSUE-VERSIONS.md`为逐问题账本，`docs/releases/2026-10-27-app-data-fixes.md`记本批修订与测试。每项记录发现日期、发现版本/证据、状态、修复候选版本、改法、回归及实际提交。共用根因可以统一修复，但每个原审查ID保留映射；撤回/历史/源缺失不能写成已修复代码Bug。

授权包含本轮代码及本地Git提交，不自动扩大为push/main合并、手机安装、生产部署/DDL/历史重放、车辆或账号操作。

## 执行记录（父任务）

- 2026-10-27：基线导入提交 `e53a2d01a54d55c554019fb861d69f3737d4b5bb`，不把导入变化记作本轮已修。
- 分组首批真实RED `groups-red-03`：49方法/36失败/0error/0skip；日志在批准的全局下载目录。另后端当前充电3例先RED后GREEN；adapter待机5子例先3失败，再全部通过。
- Hub本轮 revision 再次返回 WinError10061（服务拒绝连接），未发送成功；不自动重试。待服务恢复后人工核对最新revision再生成事件，未把报告失败当代码阻断。

- 本地最终单位/双lint/AndroidTest源码检查已通过，详见verification.json；R8/实际签名包和本地提交正收尾。

## 2026-10-27 本次授权交付Review

代码161d77cea4d110aac93b52956644ab3ea5603733；com.matelink 2.1.54/build73签名候选已核验。Debug1556/0/0/0，Release1556/0/0/8，双lint0error，AndroidTest源码编译、Go tests/vet和R8通过。原布局参数净差0。问题84条版本history已回填，Obsidian checkpoint已成功。真实手机/自然provider/生产及地图配置验收仍单列，未执行安装/部署/push；不能把本次软件交付当上线验收。Hub离线摘要保留而未发正式事件。

- 最终Obsidian：MEMORY_UPDATED，3 slots + 28 Markdown；实际源码manifest重验一致。signed APK核验PASS；本次代码与文档交付完成，外部实机/配置/provider/生产门未冒称通过。
