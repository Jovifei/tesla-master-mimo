# MateLink 变更与版本索引

## 2026-10-29 当天行程缺失排查（尚未发布）

- 10 月 28 日实机为 2.1.55/build74；经源库与云端只读核对，当天反馈的三次行程缺少原始记录。个人归档绑定 22:05 过期，Fleet 尚无首事件。
- App 源码提交 `4e9d5cb4`，独立服务端源码提交 `1d4fdb58`；两者均未安装或部署，不修改小程序和页面布局参数。
- 干净源码树 Debug/Release 各 1,592 项 0 失败（Release 8 跳过），双 lint 0 错误；提交后关键回归 34 项通过；服务端全量 tests/vet 与隔离 PostgreSQL 16 集成测试通过。[问题、日期、版本、未解决事项](RPT-2026-10-28-missing-drives.md)。

## 2026-10-27 显示验收续查（2.1.55 / build74候选）

- 发现于build73：新ticket失败误用旧session导航、缺电压丢电流、年详情漏计无行程月份费用。
- 只修三处数据消费者与版本元信息，页面布局参数不改。21项回归先7失败后全通过；最终全量、签名、提交与设备状态见[本轮验收记录](releases/2026-10-27-display-acceptance.md)。
- 版本台账追加ACC-AUTH-01/HUD-01/MILEAGE-01三条，保留原build73的84条历史。真实手机未连接，尚未取得安装版本或逐页显示证据；不视为上线通过。

## 2026-10-27 App数据链路修复候选（2.1.54 / build73）

- 发现基准为2.1.53/build72已审候选；本轮在E盘 `matelink-release-data-audit-20261026`、App分支 `codex/app-release-data-fixes-20261027` 实施，原候选基线提交 `e53a2d01`。
- [逐问题版本台账](APP-ISSUE-VERSIONS.md)保存发现日期、版本、改法、验证与提交；[批次记录](releases/2026-10-27-app-data-fixes.md)及F1–F7分组记录对应来源→资格/计算→持久化→展示链。
- 本轮只修数据、元信息、计算、并发及现有位置formatter/路由绑定；保持原页面参数排列、尺寸、字体、间距与既有颜色。
- **代码提交**：`161d77cea4d110aac93b52956644ab3ea5603733`。Debug/Release各1556方法0fail/0error（Release8既有skip），双lint0error，AndroidTest源码编译、两个Go模块tests/vet和R8通过。
- **签名候选**：`com.matelink` 73/2.1.54，debuggable=false，证书与既有包一致；APK SHA256 `425072A9A5C46DD9F54901FEE07170A6FEAE3C1DA2C6BFE3A48214B68A726482`。包内GIT_SHA=161d77ce，文件位于批准下载目录。安装、provider和生产验收分别保留待验。
- 软件修复不等于真实历史补采；未曾采集的旧样本、未提供的来源实例/事件脉冲保持明确边界。本轮未安装手机或部署生产，Hub当前拒绝连接未成功上报。


本页把**源码提交、Android 包、测试和手机安装**分别绑定。Git 提交历史是逐提交的完整事实；本页为近期关键变更提供可检索入口，不能用旧 SHA 的绿色 CI 为新提交背书。Bug 根因与复发检查见 [BUG-REGISTER.md](BUG-REGISTER.md)，Android 旧版本详情见 [ANDROID-RELEASE-LOG.md](ANDROID-RELEASE-LOG.md)。更新日期：2026-10-25。

## 已安装检查点与正在开发的版本

| 状态 | 版本／源码 | 改动与证据边界 |
| --- | --- | --- |
| **已安装检查点** | Android `2.1.52 / build 71`，源码 `af6fe089a57c95b3839dc304bb0385c3ba4e8e29`，tree `d400bc920bd6e05205022d40459da2ac4f965dea`，parent `24a1141ad1bcf7eb7a01ab8e7baafd0995ac68d2` | [PR #27](https://github.com/Jovifei/tesla-master-mimo/pull/27) 保持 Draft；[同 SHA Actions](https://github.com/Jovifei/tesla-master-mimo/actions/runs/40174354397) 18/18 SUCCESS，Debug/Release 各 1271 测试 0 fail/error，Release 8 既有 skip，双 lint 0 error，R8 PASS；[API35](https://github.com/Jovifei/tesla-master-mimo/actions/runs/40174354371) XML 80/0/0/2、三张非黑合成截图。已同签 `adb install -r` 保留安装身份并启动；正式 APK SHA256 `BB8DFBB30C8A2DE1721C74DA9942B81AF22D640CBF84355BFAC5AC378E8C6FF1`。该检查点有 [Bug 台账](BUG-REGISTER.md)所列新发现，不等于页面功能验收完成。 |
| **后续远端提交，未安装** | [`eec809af`](https://github.com/Jovifei/tesla-master-mimo/commit/eec809af9b9e942d01efc5b2410edd31c9687819) 等 PR #27 后续源码 | `eec809af` 增强 Room-first 网络异常恢复及 ViewModel 回归；它晚于手机 `af6` 包。完整同 SHA CI、独立复审和安装资格须分别核对，不能沿用上行结果。 |
| **本地未提交修复** | 本 E 盘独立 App 工作树；新 SHA / APK **尚无** | 正在修驻车指标/颜色、费用估算汇总、行程与充电日期窗口、本地旧日读取和筛选入口；对应 `ML-UI-*`、`ML-COST-20261025-01`、`ML-HISTORY-*`。文件存在或聚焦测试开始不等于构建/发布完成。原 E 盘小程序分支、Owner 数据及生产保持。 |

## 近期提交到行为的索引

| 提交 | 版本／范围 | 实际变化与关联 Bug |
| --- | --- | --- |
| [`db41ac710`](https://github.com/Jovifei/tesla-master-mimo/commit/db41ac710ba0a2b262bde757b02c8e974aa5302e) | build67 / 2026-10-24 | Room 即时缓存和无限滚动；后续暴露自动扩窗却仍写“近7天”的 `ML-HISTORY-20261025-01`。 |
| [`34320288`](https://github.com/Jovifei/tesla-master-mimo/commit/34320288) | build68 / 2026-10-25 | 仪表盘缺温度的“等待车辆”提示并升版；同版包含历史超时与显示修复，见发布台账。 |
| [`f84af473`](https://github.com/Jovifei/tesla-master-mimo/commit/f84af473) | build69 / 2026-10-25 | 驻车相邻 Room 行程先显示、云端超时保留本地；修弱网空页。 |
| [`f810ace5`](https://github.com/Jovifei/tesla-master-mimo/commit/f810ace5) | build70 / 2026-10-25 | 驻车分层页面与 `localHistoryCarId` 冷启动缓存键；旧六项遥测为本轮恢复的视觉参照，见 `ML-CACHE-20261025-01`。旧固定容量伪能耗不作为恢复目标。 |
| [`bffc4044`](https://github.com/Jovifei/tesla-master-mimo/commit/bffc4044) | PR #27 整合 | 将数据底座与 build70 页面整合；不等于安装包。 |
| [`84e3ca91`](https://github.com/Jovifei/tesla-master-mimo/commit/84e3ca91) | build71 源码 | 停车窗口内分开多次充电，分别按本次时长显示 kWh/hour，停车放电按合格 SOC 边界算百分点/小时。 |
| [`674f2ba7`](https://github.com/Jovifei/tesla-master-mimo/commit/674f2ba7) | PR #27 Room-first | 电池、效率、费用三页开始消费账号/车辆作用域的 Room 证据；后续依真实消费者回归补强。 |
| [`1deeb3cb`](https://github.com/Jovifei/tesla-master-mimo/commit/1deeb3cb) | PR #27 缺口修复 | 驻车跨来源会话隔离、Battery UID 缓存映射、Cost 临时失败后缓存保留及实测 VM 回归；见 `ML-SCOPE-20261025-01`、`ML-COST-20261025-02`。 |
| [`5845b5a9`](https://github.com/Jovifei/tesla-master-mimo/commit/5845b5a9)、[`4d50af93`](https://github.com/Jovifei/tesla-master-mimo/commit/4d50af93) | PR #27 API35 | 以真实 Compose 像素代替全黑 framebuffer，上传可核对截图；见 `ML-CI-20261025-01`。 |
| [`af6fe089`](https://github.com/Jovifei/tesla-master-mimo/commit/af6fe089a57c95b3839dc304bb0385c3ba4e8e29) | 安装版固定源码 | 加第三个“充电→停充→复充”模拟器页面断言与截图；仅本行顶部列出的同 SHA CI、签名包和手机启动适用。 |
| [`eec809af`](https://github.com/Jovifei/tesla-master-mimo/commit/eec809af9b9e942d01efc5b2410edd31c9687819) | 后续 PR #27，未安装 | 停车本地缓存遇 provider 异常仍保留、Efficiency Room 读取异常后继续远端获取；需自己的同 SHA 结论。 |

## 每次提交／发包如何追加

1. 产品或回归提交：记录完整 SHA、父提交、影响页面/数据合同、对应 Bug ID、源代码测试与仍未验证项；纯 CI/文档提交也写明其作用，不写成产品修复。链接 GitHub commit 可查看**每个文件的精确 diff**。
2. 形成安装包：在 [发布台账](ANDROID-RELEASE-LOG.md)记录 `versionCode/versionName`、固定源码 SHA/tree、同 SHA Android+API35/Go/Mini 实际结果、签名包 SHA256 与是否安装；装机后才记录首次安装时间是否保留和启动证据。
3. 修复完成：只在对应 commit、测试和页面消费者都验证后，把 [Bug 台账](BUG-REGISTER.md)状态从 `WIP` 改成 `FIXED@SHA`。合成、模拟器、手机、自然 Fleet、生产分别验收；PR 仍 Draft 时不得写“已合并主分支”。
