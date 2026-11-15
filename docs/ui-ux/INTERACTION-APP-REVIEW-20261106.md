# App 交互视觉增量复审：2026-11-06

范围：仅 Jovifei/tesla-master-mimo 的 `codex/app-interaction-complete-20261106`，基线 `fc812ff8505dc72f6002023bc93c041d5de92b75`。完整 A—G 对照及本地接收见同目录 INTERACTION-COMPLETE-20261106.md 与 LOCAL-RECEIVE-INTERACTION-COMPLETE-20261106.md。

## 实际源码修复

`InteractionVisuals.kt` 是现有页面正在调用的消费者，不是新建的未接线样例。车辆连接补 stateDescription；四轮值先按已取得资格验证，非法/非有限/无时间/未知不显示成观测；真实 0 保留；独立轮位时间及来源示意明确。车身开口增加四类原生文字，未知与关闭不混同。充电时间轨未知结束为空心/虚线并有完整语义；历史错误不宣称没有记录。

保存的胎压上下限经真实 `TpmsTrendScreen` / ViewModel 接入。读取配置使用已核对的 `context.localHistoryCarId`，不是远端裸数字 ID。历史与配置读后都核对同一账号、源、车辆上下文。只有当前 request token 且仍同身份才发布 profile 和 history；源设置读取失败保留未知，不使用另一辆车或默认阈值。状态更新复用现有 TpmsCustomAlertEvaluator，原始保存值、schema、通知账本不改变。

刷新 job 在 start 之前记录所有权；同步回调启动下一次 refresh 时，外层任务不会覆盖新 job，invalidate 能真实取消最新请求。已有身份变化流保持清空旧结果，并补设置变化重新获取；切车期间不继承旧 series/profile。

## 受控实施证据（不替代最终全量）

Actions `42157126698` / job `127693827630` 先执行精确锚点增量修改，再实际运行 30 项 JVM 回归（0 failure/error/skip）及 AndroidTest APK 编译，随后正常快进推送源码：

- source `d746ef2ce57722fa030b53549fd19870d27e1f23`
- tree `a16bc5f7b07ead576d2b49ee06e00626eebc3666`
- parent `e5d49c66fae967590c262b3f5ec7259681a1233a`
- artifact `13475597024`，SHA-256 `08ef75ea992ad3682942256311630d0eefe173b98ff130294dbbcdde00ccd45c`

独立解析 XML：InteractionEvidencePresentationTest 7、ConfiguredPressureVisualTest 4、TpmsProfileVisualPipelineTest 4、TpmsTrendIdentityRecoveryTest 5、TpmsTrendPresentationTest 10。AndroidTest 编译不计为模拟器运行；最终提交仍须完整 Android Debug/Release/lint/R8 及 API35 原生测试。源归档/补丁/XML 保存在 artifact，失败不能用旧 baseline 的绿色代替。

本最终收尾移除了已完成的一次性写 runner 和 patch 脚本；实际业务修复、回归、只读永久验证工作流保留在源码。GitHub commit history 保留实施与证据，不让本地重新运行编辑脚本。

## 独立复审要点

- 新增设置不写入任何用户数据，只从已有同作用域设置读取；不新增默认阈值。规则停用或未知不叫“正常”。
- 高/低颜色有本地化文字，不依赖颜色辨识。旧观测不升级当前告警。
- side-car PNG 和中性轮位资源、真实地图、已有页面导航和主指标布局保留。
- 未把汇总车门/车窗扩成某个具体门窗；未从驻车会话空隙生成停充事件；未将连接动画当成功依据。
- Android 基线没有微信绑定流程；本次不虚构原生绑定能力，Mini 原有可选微信流程独立保留。
- 所有 production/backend/schema/data/versions 由精确基线差异守卫保护。App 和 Mini 不能整树互相覆盖。
- 全产品备份恢复、归档桥续绑、所有自然数据/通知及所有平台像素等同，不在本 UI 阶段谎报完成。

最终固定 HEAD/tree/parent、完整 Actions run/job/artifact、准确 XML totals 和本地验收门写在当前 PR 的最终回执。没有该同 SHA 完整结果之前，本文件不能单独被解释成最终全绿交付。
