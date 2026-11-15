# 2026-10-27 build73显示验收续查

## 范围与现状

Jovi要求执行实际验收，并反馈仍有部分“账户数据”（原意待确认）。在已授权App分支 `codex/app-release-data-fixes-20261027` 和E盘隔离工作树继续，基线代码161d77ce、文档9d79a568，候选com.matelink 2.1.54/build73。上轮单测/lint/打包是历史本地证据，不能代表本轮手机显示验收。

允许数据链路修复、必要回归、文档及本地提交。布局参数/排列/字体/尺寸不改，原目录不覆盖，不清用户数据、不执行车辆控制、不输入凭证、不推送或部署。共享Git配置不改；命令显式绑定GIT_WORK_TREE。

## 执行和证明

1. 固定现场：检查 `adb devices -l` 和技能 `device_preflight.ps1`，有设备再读取已安装包versionCode、首次安装时间及证书；当前无连接设备。请Jovi接入并明确页面/字段。本轮原始证据存 `E:\Claude_allow\Download\matelink-display-acceptance-20261027`。
2. 并行只读复核三组：登录/首页与账号车辆缓存；充电/行程列表详情曲线；“我的”分析统计汇总。优先完整合法数据正例和冷重开，输出具体消费者、值来源、已有回归缺口与可复现条件。Codegraph查询结果是旧索引，当前VehicleStatusStore实际行号/内容不同，后续以工作树精确rg和限定范围读取为准。
3. 仅对复现成立的源头缺陷改动：先用真实消费者最小RED，再最小修复和GREEN，更新发现日期/版本/变更/回归/提交。新增产品diff前复核布局保护。不能凭歧义反馈批量改过滤/资格。
4. 真机：只在device状态与包/证书核对成功后按最新安全脚本执行同签 `adb install -r`，保留首次安装时间。遇解锁/登录/权限交由Jovi操作；不运行清库instrumentation。核对首页→充电→同ID详情→行程→同ID详情/速度曲线→我的各子页，检查来源/计算/显示、返回重开和离线。云端请求失败、缺原始点与消费者丢值各自记录。
5. 交付：文档记录实际执行命令/结果/证据等级。无手机证据时保持DEVICE_PENDING，不得写上线通过；Obsidian仅同步验证事实。Hub revision本轮再次10061失败，停止自动重试，离线记录该失败。

## 失败处理

设备未连接、未授权、账号登录和权限提示需Jovi现场操作，独立源码复核继续。产品改动失败回到单一假设和RED，不回退他人内容。版本台账沿用既有文件，不覆盖上轮已签包或重写旧证据。

## 本轮限定实施增量

20类现有消费者95/0fail/0error/0skip实际重跑完成（consumer-regression-01.log/XML），此为合成/JVM/Room证据。独立复核确认三项源码缺陷，已只添加测试，等待实际RED：

- ACC-AUTH-01：新授权ticket返回401/403，只因旧session存在被认成成功并打开旧账号首页。改TeslaLoginViewModel.kt失败分支，保留已成功相同ticket和在途ticket的既有幂等。测试AppReleaseAuthSelectionTest.kt与TeslaCallbackReplayGuardTest.kt。
- ACC-HUD-01：已观测电流在电压缺失时被整体丢弃。只改DashboardScreen.kt现有HUD的值函数及显示谓词，不改任何Column/Text的布局参数；测试AppReleaseDashboardValuesTest.kt覆盖16A/0A/全未知。
- ACC-MILEAGE-01：年详情费用从仅含行程月份求和，漏算其他月份真实充电。只改MileageViewModel.kt该年份费用汇总，保留现有里程月/日行；测试MileageChargeCostConsumerTest.kt覆盖10对3、跨年、0和null。

新增候选预留2.1.55/build74（本轮只读核对原目录仍72、当前分支73），最终是否打包及安装依实际记录。修改build.gradle.kts仅版本元信息。回归RED→GREEN后运行全量Debug、Release、lint与R8，源码和APK各留独立hash。发现日期2026-10-27、发现版本73，三项历史追加到既有版本账本，不重写原84条。
