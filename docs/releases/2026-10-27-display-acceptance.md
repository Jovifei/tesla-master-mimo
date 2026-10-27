# 2026-10-27 实际显示验收续查

状态：LOCAL_QA_PASS / SIGNED_BUILD_PENDING / DEVICE_PENDING。最新源码候选2.1.55/build74。

本轮由Jovi反馈仍有“账户数据”触发，原意与具体页面/字段尚待确认，不能预先认定手机发生了串账号。基线为App分支codex/app-release-data-fixes-20261027的161d77ce产品代码、9d79a568文档，com.matelink 2.1.54/build73。已重新核验该签名APK及943个源码输入。上轮未安装该包，本轮ADB同样未检出设备，尚无当前手机安装版本/画面证据。

## 新增发现

| ID | 发现日 / 版本 | 具体复现 | 最小改法 | 验证状态 |
|---|---|---|---|---|
| ACC-AUTH-01 | 2026-10-27 / 2.1.54 build73 | Alice已登录，重新授权的新ticket返回401/403；原代码仍以Alice做onboarding并触发首页导航 | 新ticket兑换失败保持失败；相同成功ticket/在途ticket幂等保留，旧会话不清除 | 实际VM两项RED→GREEN，完整正例通过 |
| ACC-HUD-01 | 2026-10-27 / 2.1.54 build73 | 同一真实观测有16A或0A、缺电压，整个HUD电压/电流位置不显示 | 现有位置以任一真实字段存在为显示条件；未知电压写--V，电流真实0保留 | 两项消费者RED→GREEN，全空控制通过 |
| ACC-MILEAGE-01 | 2026-10-27 / 2.1.54 build73 | 10月有行程+3CNY充电，9月无行程+7CNY充电；年列表10，年详情3 | 年详情从该年全部合格充电费用求和；不改里程月/日列表及距离计算 | 已知费用/跨年/真实0三项RED→GREEN，未知控制通过 |

## 已取得证据

- consumer-regression-01：20类95方法，0fail/0error/0skip，Gradle exit0；是合成接口/Room/JVM消费者结果，非手机显示验收。
- acceptance-red-02：3类21方法，7fail/0error/0skip，Gradle exit1（断言失败）；失败逐一对应上表三项缺陷。既有成功授权ticket重放和全部未知控制仍通过。原产品代码保持build73时完成RED，随后才改产品逻辑。
- full-03：1566方法/1fail/0error；上述三类21方法均已GREEN。唯一失败是SettingsExperienceContractTest仍断言旧版本73/2.1.54，而候选已升74/2.1.55，随后同步版本夹具；该失败使Release测试和lint未执行，不能记作通过。日志/XML单独保留，最终以重跑结果为准。
- full-04：Gradle exit0，7m36s。Debug1566/0fail/0error/0skip，Release1566/0fail/0error/8既有skip。双lint均0error（Debug276warning/9info，Release275warning/8info）。三项实际回归及既有用例完整通过；完整XML和lint XML已归档，详见build74-verification.json。
- apk-verification-01.json：build73签名包PASS，SHA256 425072A9A5C46DD9F54901FEE07170A6FEAE3C1DA2C6BFE3A48214B68A726482。
- source-check-01.json：原build73 manifest的943个文件一致。
- device-preflight-01.json：device_count=0，未安装、未操作用户会话或清理数据库。
- 三组只读复核：充电/行程冷重开及曲线未证实其他新增漏值；Range/Battery/Efficiency/Cost/Temperature/Stats未证实其他新增漏值。这些结论仅限源码与用例定义，不等于真实提供方有完整历史字段。
- 最终最小diff独立复审APPROVE：只检查本轮授权范围；确认旧会话保留、成功/在途同ticket幂等、0/null及所选年份费用资格保持。复审未运行构建或设备操作，父任务集中运行验证。
- layout-diff-check-01.json：仅三处产品文件发生数据逻辑变化，布局/字体/颜色/间距参数净改动0；不是手机像素验收。

原始证据目录：E:\Claude_allow\Download\matelink-display-acceptance-20261027。

## 尚缺实际验收

手机接入后先读取com.matelink版本和证书，再保数据同签升级已核验Release。首页、充电列表/同ID详情、行程列表/同ID详情及速度曲线、“我的”各汇总应逐项记录原始来源值、参与计算的样本、UI显示值、返回/冷重开与离线结果。有权限/登录提示由Jovi操作。未取得这些证据前保持DEVICE_PENDING。

Hub本轮revision再次连接被拒10061；没有正式事件被接收，停止自动重试。此前离线摘要保留。
