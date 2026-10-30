# 2026-10-27 App数据链路修订记录

状态：IMPLEMENTING，未发布、未安装。

- App分支：`codex/app-release-data-fixes-20261027`。
- 起点：PR27固定cdfe0b26 + 954文件已审候选；导入/本轮修复/测试证据分开记录。
- 发现版本：2.1.53/build72候选；发现日期为2026-10-26至27的本轮审查，不推断首次引入版本。
- 目标修复版本：2.1.54/build73候选，仅本分支预留，正式打包前复核其他App版本；本次没有改变手机已装版本。
- 授权：Jovi明确允许源码修改及提交，仍禁止改页面/参数布局。

逐问题生命周期见`docs/APP-ISSUE-VERSIONS.md`。修复前保留实际失败；只有经过消费者回归才标验证通过，实际commit后再填提交引用。

## 批次执行

| 日期 | 修订 | 范围 | 状态/证据 | 实际提交 |
|---|---|---|---|---|
| 2026-10-27 | BASE | 导入本次已审公开候选及文档，不是新修复 | 954文件before哈希一致 | 待记录 |
| 2026-10-27 | F1–F7 | 来源/计算/保存/展示数据绑定；布局保持 | 实施与回归中 | 未提交 |

## 本轮测试

审查继承证据：11项Kotlin条件8失败/3对照、7项DAO SQL条件4失败/3对照；这不是修复后的通过结果。后续只写本轮实际执行的命令/XML/退出码，不复用旧1356绿CI为新代码背书。

## 未验门

源码修复不证明提供方已采集所有历史字段；缺采样/未支持保持明确原因。手机、签名、真实Fleet、生产、同SHA远端CI和发布分别验收。

### 父任务验证进度

2026-10-27：基线checkpoint `e53a2d01a54d55c554019fb861d69f3737d4b5bb`。首批联合RED 49方法/36失败/0error/0skip。后端current-charge 3例由父执行先RED后GREEN；adapter standby 5子例先3失败后GREEN。Android组04在KSP阶段因CreateTripViewModel多余闭合括号失败，未执行测试、不能记作产品RED/GREEN；修正后组05在执行。Hub revision本轮仍WinError10061拒绝连接，未成功发送，停止自动重试。

- 联合05：77方法/17失败/0error/0skip（新增RED与已修GREEN并跑）；联合06：86/31失败/0error/0skip。F1重复GREEN、Battery/Vampire共12方法GREEN、GeoRepository6方法GREEN；06新增Current4、Tariff3、CloudPlan1、Sentry1、GeoConsumer8、F6追加4、F3 chart/parked5等实际RED。
- build.gradle候选版本已更新2.1.54/build73；仍未安装。后端全Go测试父执行PASS（8.880s），不表示生产部署。
- 联合07只到编译失败：Worker helper名拼写和TripDetailScreen缺import，属本轮集成错误，修正后重跑；无JUnit结果，不能记作产品RED或GREEN。

### 全量QA回执（父执行）

- 最后focus21：22/0fail/0error/0skip（Current9、CanonicalC8、ChargesRoom4、ChargeDetailRoomFirst1）。
- full-debug-22：1555/17fail/0error/0skip。分组通过未被写成全量通过；失败分为 processing version/已知单位/显式AC与free证明/版本73旧fixture，以及完整缓存header别名与字段保留的真实回归。按原断言目的补齐前提或修生产投影，不回退已加的未知/窗口/身份门。
- 正式构建所需SDK/工具链检测PASS；既有签名公钥指纹已核对，外部配置不进入源树或报告。待全部检查通过后再回填APK与提交。

### 冻结候选最终本地检查

2026-10-27父执行：full-debug-24为1556/0fail/0error/0skip；release-lint-26整条退出0，Release1556/0/0/8（既有skip），Debug lint 0 error（276 warning/9 information）、Release lint 0 error（275 warning/8 information），AndroidTest Kotlin源码编译通过。Go backend/adapter全量tests及vet退出0。所有输入为源码/隔离合成/Room/VM/Notification，未声称真实provider或设备验收。

现有布局参数与e53a2d01逐文件净差0；两处新增错误Panel已撤回，保原布局并以已有消息反馈。未知/真实0/估算、输出单位及真实曲线形状是数据绑定变化。

代码提交后构建R8签名APK，确保BuildConfig.GIT_SHA指向真正修复commit；包hash/签名和后续文档回填commit另记。正式配置沿用既有发布产物的显式API/auth/publicInfo地址，未修改项目绑定。私有签名配置在仓库外；没有安装、生产部署或push。

### 代码与签名候选

- 代码commit：161d77cea4d110aac93b52956644ab3ea5603733，App分支本地提交。
- R8/assembleRelease实际退出0；build wrapper的PARTIAL仅表示SkipTests避免重复已经独立完成的单测/lint，不是构建失败。
- APK com.matelink 73/2.1.54，debuggable=false，signature PASS；SHA256 425072A9A5C46DD9F54901FEE07170A6FEAE3C1DA2C6BFE3A48214B68A726482；cert SHA256 9AB144E824ABF26A5941819ABB06831288C36A8BFE622657E3DC9D88281FC774（历史一致）。
- 文件：E:\Claude_allow\Download\matelink-app-release-fixes-20261027\MateLink-2.1.54-build73-161d77ce.apk，DEX内已找到161d77ce。最终docs-only回填提交不改变该APK源码身份。
- 实机、自然Fleet/旧历史缺测、provider source-instance及事件脉冲、地图运行时配置仍是独立门，未写成通过；本轮无手机安装、生产部署或远端push。

### Obsidian与报告边界

既有tesla-master-mimo项目的progress/decisions/workflow经DryRun、hash复核与no-arg wrapper成功MEMORY_UPDATED；历史managed内容保留并追加本候选。详细Markdown镜像已过滤校验，实际结果以verification.json为准。Hub因未取得当前revision而未能生成/发送正式事件，离线摘要保留在本次task evidence，不自动重试或伪报成功。
