# docs/ 文档索引

> 索引更新：2026-11-15（随小程序 AI 统一基线 v2.0 落稿更新）。基线：main `ef885888`。本索引列清 `docs/` 下全部文档；原入口内容全文保留于文末“原入口内容”一节。

## 现行文档

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [miniprogram-ai-unified-baseline-v2.0.md](miniprogram-ai-unified-baseline-v2.0.md) | MateLink 小程序 AI 功能统一基线 v2.0（双边共识版，业主终审通过 2026-11-15 20:16；执行未授权） | 现行 | 2026-11-15 |
| [PROJECT-REQUIREMENTS.md](PROJECT-REQUIREMENTS.md) | MateLink 车迹小程序项目最高要求与开发纪律 | 现行（AGENTS.md 强制规范） | 2026-11-07 |
| [SUBMISSION-PLAYBOOK.md](SUBMISSION-PLAYBOOK.md) | MateLink 车迹小程序项目提交与发布备忘录 (Submission Playbook) | 现行（AGENTS.md 强制规范） | 2026-11-07 |
| [SUBMISSION-INDEX.md](SUBMISSION-INDEX.md) | MateLink 车迹小程序项目提交目录与版本履历台账 (Submission Index) | 现行（AGENTS.md 强制规范） | 2026-11-15 |
| [TROUBLESHOOTING-LESSONS.md](TROUBLESHOOTING-LESSONS.md) | MateLink 车迹小程序项目处理经验与踩坑复盘 (Troubleshooting & Lessons Learned) | 现行（AGENTS.md 强制规范） | 2026-11-07 |

状态说明：**现行** = 当前有效的规范/基线；**保留（历史/阶段记录）** = 内容未被取代、继续留存，其归档/删除处置待业主定（与 open 的 docs 清理提案 PR #23 一并定，本次不擅自删）；**已取代/已删除**见下文“已删除记录”。

## 全部文档清单

### `docs/`

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [ADR-2026-10-02-tiered-history-storage.md](ADR-2026-10-02-tiered-history-storage.md) | ADR提案：用户本地详细档案 + 云端轻量索引 + 可选完整冷归档 | 保留（历史/阶段记录） | 2026-10-07 |
| [ANDROID-RELEASE-LOG.md](ANDROID-RELEASE-LOG.md) | MateLink Android 版本发布台账 | 保留（历史/阶段记录） | 2026-10-25 |
| [ARCH-2026-08-28-jourvolt-deploy.md](ARCH-2026-08-28-jourvolt-deploy.md) | ARCH：JourVolt / MateLink 部署架构设计与任务分解（2026-08-28） | 保留（历史/阶段记录） | 2026-08-29 |
| [ARCH-DECISION-2026-08-28-route3-fleet-multitenant.md](ARCH-DECISION-2026-08-28-route3-fleet-multitenant.md) | 架构决策：路线三 — Fleet API 多租户 + 手机本地历史（2026-08-28） | 保留（历史/阶段记录） | 2026-09-27 |
| [ARCH-DECISION-2026-09-27-fleet-teslamate-hybrid.md](ARCH-DECISION-2026-09-27-fleet-teslamate-hybrid.md) | 最终架构决策：Fleet API 多用户主链路 + Jovi 个人 TeslaMate 归档 | 保留（历史/阶段记录） | 2026-09-27 |
| [BUG-REGISTER.md](BUG-REGISTER.md) | MateLink Bug 根因索引 | 保留（历史/阶段记录） | 2026-10-25 |
| [BUG-REPAIR-2026-10-01-automatic-vehicle-setup.md](BUG-REPAIR-2026-10-01-automatic-vehicle-setup.md) | 登录后自动车辆接入 | 保留（历史/阶段记录） | 2026-10-02 |
| [BUG-REPAIR-2026-10-01-sync-charge-cost.md](BUG-REPAIR-2026-10-01-sync-charge-cost.md) | 重新登录后历史补同步与费用展示 | 保留（历史/阶段记录） | 2026-10-01 |
| [BUG-REPAIR-2026-10-04-phone-history-stale.md](BUG-REPAIR-2026-10-04-phone-history-stale.md) | 2026-10-04 手机历史停在旧日期：诊断与读取链修复 | 保留（历史/阶段记录） | 2026-10-07 |
| [BUG-REPAIR-REPORT-2026-07-11.md](BUG-REPAIR-REPORT-2026-07-11.md) | App Mimo Bug Repair Report - 2026-07-11 | 保留（历史/阶段记录） | 2026-08-09 |
| [BUG-REPAIR-REPORT-2026-08-31-history-page-crash.md](BUG-REPAIR-REPORT-2026-08-31-history-page-crash.md) | MateLink 行程/充电历史页闪退修复记录（2026-08-31） | 保留（历史/阶段记录） | 2026-09-01 |
| [BUG-REPAIR-REPORT-2026-09-01-data-readiness-standby-amap-settings.md](BUG-REPAIR-REPORT-2026-09-01-data-readiness-standby-amap-settings.md) | MateLink 首次登录、待机能耗与设置体验修复记录（2026-09-01） | 保留（历史/阶段记录） | 2026-09-01 |
| [BUG-REPAIR-REPORT-2026-09-02-login-amap-device.md](BUG-REPAIR-REPORT-2026-09-02-login-amap-device.md) | MateLink 登录、高德配置与设备回归记录（2026-09-02） | 保留（历史/阶段记录） | 2026-09-02 |
| [CHANGE-INDEX.md](CHANGE-INDEX.md) | MateLink 变更与版本索引 | 保留（历史/阶段记录） | 2026-10-25 |
| [CLOUD-DATA-RECOVERY-GATE-REPORT-2026-09-07.md](CLOUD-DATA-RECOVERY-GATE-REPORT-2026-09-07.md) | CLOUD_DATA_RECOVERY_LOCAL_GATE_REPORT | 保留（历史/阶段记录） | 2026-09-07 |
| [DATA-CATALOG.md](DATA-CATALOG.md) | MateLink 数据目录 — 原型字段清单 | 保留（历史/阶段记录） | 2026-10-24 |
| [DATA-FOUNDATION.md](DATA-FOUNDATION.md) | MateLink 数据来源与保存边界 | 保留（历史/阶段记录） | 2026-10-25 |
| [DBG-2026-10-02-login-oom.md](DBG-2026-10-02-login-oom.md) | 2026-10-02 Tesla 云登录 502 与内存耗尽 | 保留（历史/阶段记录） | 2026-10-07 |
| [DELIVERY-20261103-MINI-REVIEW.md](DELIVERY-20261103-MINI-REVIEW.md) | Mini review implementation delivery — 2026-11-03 | 保留（历史/阶段记录） | 2026-11-03 |
| [ENERGY-LOCAL-CHANGES-REQUIRED-20261009.md](ENERGY-LOCAL-CHANGES-REQUIRED-20261009.md) | 本地独立接收：CHANGES_REQUIRED | 保留（历史/阶段记录） | 2026-10-09 |
| [ENERGY-LOCAL-DEVICE-QUALIFICATION-20261009.md](ENERGY-LOCAL-DEVICE-QUALIFICATION-20261009.md) | Energy stage local qualification — 2026-10-09 | 保留（历史/阶段记录） | 2026-10-09 |
| [ENERGY-REPAIR-CLEANUP-STAGE-20261008.md](ENERGY-REPAIR-CLEANUP-STAGE-20261008.md) | 能耗、驻车与充电完整修复及仓库整合阶段 | 保留（历史/阶段记录） | 2026-10-08 |
| [ENERGY-STAGE-FIXED-SHA-HANDOFF-20261009.md](ENERGY-STAGE-FIXED-SHA-HANDOFF-20261009.md) | Energy, parking and charging full-stage release candidate — 2026-10-09 | 保留（历史/阶段记录） | 2026-10-09 |
| [ENERGY-STAGE-LOCAL-CODEX-PROMPT-20261009.md](ENERGY-STAGE-LOCAL-CODEX-PROMPT-20261009.md) | Local Codex fixed-SHA energy release validation prompt — 2026-10-09 | 保留（历史/阶段记录） | 2026-10-09 |
| [ENERGY-TLS-DEVICE-DIAGNOSTIC-20261009.md](ENERGY-TLS-DEVICE-DIAGNOSTIC-20261009.md) | Authenticated history TLS qualification — energy stage / PR17 / 202… | 保留（历史/阶段记录） | 2026-10-09 |
| [ENERGY-TRANSPORT-COMPATIBILITY-FINAL-STAGE-20261009.md](ENERGY-TRANSPORT-COMPATIBILITY-FINAL-STAGE-20261009.md) | 2026-10-09 TLS transport, deployed bb09 compatibility and remaining… | 保留（历史/阶段记录） | 2026-10-11 |
| [GPT56_COMMANDER_HANDOFF_2026-07-16.md](GPT56_COMMANDER_HANDOFF_2026-07-16.md) | GPT-5.6 指挥官交接：Tesla MateLink MIMO | 保留（历史/阶段记录） | 2026-08-09 |
| [HANDOFF-2026-10-24-C2C-8F2D.md](HANDOFF-2026-10-24-C2C-8F2D.md) | C2C 8f2d — full remote stage / exact-source independent verification | 保留（历史/阶段记录） | 2026-10-24 |
| [HANDOFF-2026-10-25-PR27-ROOM-FIRST-AND-PARKED-UI.md](HANDOFF-2026-10-25-PR27-ROOM-FIRST-AND-PARKED-UI.md) | PR #27 — Room-first analytics and parked-session UI handoff (2026-1… | 保留（历史/阶段记录） | 2026-10-25 |
| [IOS-APPLE-REDESIGN-HANDOFF-2026-08-30.md](IOS-APPLE-REDESIGN-HANDOFF-2026-08-30.md) | iOS Apple 重设计交接 — 2026-08-30 | 保留（历史/阶段记录） | 2026-08-30 |
| [JOURVOLT-ANDROID-IMPLEMENTATION.md](JOURVOLT-ANDROID-IMPLEMENTATION.md) | MateLink 单一 App 登录改造状态 | 保留（历史/阶段记录） | 2026-08-23 |
| [JOURVOLT-CLOUD-API-CONTRACT.md](JOURVOLT-CLOUD-API-CONTRACT.md) | JourVolt 云端接口与安全契约（P1 草案） | 保留（历史/阶段记录） | 2026-09-01 |
| [JOURVOLT-ECS-SECRET-SETUP.md](JOURVOLT-ECS-SECRET-SETUP.md) | JourVolt ECS Tesla Secret 填值指引（给 Jovi） | 保留（历史/阶段记录） | 2026-09-08 |
| [JOURVOLT-LOCAL-DOCKER-DEBUG.md](JOURVOLT-LOCAL-DOCKER-DEBUG.md) | JourVolt 本机 Docker 调试 | 保留（历史/阶段记录） | 2026-08-23 |
| [JOURVOLT-P0-EXTERNAL-GATE.md](JOURVOLT-P0-EXTERNAL-GATE.md) | JourVolt P0 外部门禁执行单 | 保留（历史/阶段记录） | 2026-08-23 |
| [JOURVOLT-REAL-PILOT-PREFLIGHT.md](JOURVOLT-REAL-PILOT-PREFLIGHT.md) | JourVolt 真实单车 Pilot 预检 | 保留（历史/阶段记录） | 2026-08-23 |
| [LESSONS-2026-10-02-history-resource-bounds.md](LESSONS-2026-10-02-history-resource-bounds.md) | 经验规则：历史数据必须按资源边界读取 | 保留（历史/阶段记录） | 2026-10-07 |
| [LOCAL-RECEIVE-APP-MINI-20261103.md](LOCAL-RECEIVE-APP-MINI-20261103.md) | App then Mini — independent local reception (2026-11-03) | 保留（历史/阶段记录） | 2026-11-03 |
| [MATELINK-P0-UI-ANALYTICS-2026-08-13.md](MATELINK-P0-UI-ANALYTICS-2026-08-13.md) | MateLink P0 - UI and analytics foundation | 保留（历史/阶段记录） | 2026-08-23 |
| [MP-ALIGNMENT-AUDIT-20261028.md](MP-ALIGNMENT-AUDIT-20261028.md) | 小程序与 App 逐页对齐复核报告（2026-10-28） | 保留（历史/阶段记录） | 2026-10-29 |
| [MP-APP-PARITY-FOLLOWUP-20261028.md](MP-APP-PARITY-FOLLOWUP-20261028.md) | App / Mini 冻结源码复核 | 保留（历史/阶段记录） | 2026-10-29 |
| [NOTIFICATION-JOURNAL-STAGE-LOCAL-RECEIVE-20261011.md](NOTIFICATION-JOURNAL-STAGE-LOCAL-RECEIVE-20261011.md) | Original PR17 full-stage continuation — scoped completion journal a… | 保留（历史/阶段记录） | 2026-10-11 |
| [P0-P05-DEVICE-QUALIFICATION-2026-07-17.md](P0-P05-DEVICE-QUALIFICATION-2026-07-17.md) | P0 + P0.5 Android Device Qualification — 2026-07-17 | 保留（历史/阶段记录） | 2026-08-09 |
| [P0-P07-ISOLATED-DEVICE-QUALIFICATION-2026-07-18.md](P0-P07-ISOLATED-DEVICE-QUALIFICATION-2026-07-18.md) | P0.7 Isolated Android Qualification Report | 保留（历史/阶段记录） | 2026-08-09 |
| [P0-P08-FULL-UI-QUALIFICATION-2026-07-19.md](P0-P08-FULL-UI-QUALIFICATION-2026-07-19.md) | P0.8 最小可测试性支撑与剩余 Full-UI 资格闭环 | 保留（历史/阶段记录） | 2026-08-09 |
| [P0-P09-MINIMAL-FIX-QUALIFICATION-2026-07-19.md](P0-P09-MINIMAL-FIX-QUALIFICATION-2026-07-19.md) | P0.9 配置保存持久化与空态刷新最小修复资格报告 | 保留（历史/阶段记录） | 2026-08-09 |
| [PHASE-HANDOFF-2026-07-09.md](PHASE-HANDOFF-2026-07-09.md) | MateLink app_mimo Phase Handoff - 2026-07-09 | 保留（历史/阶段记录） | 2026-07-09 |
| [PLAN-2026-10-11-local-persistent-recovery.md](PLAN-2026-10-11-local-persistent-recovery.md) | 聊天规划与本地执行：来源隔离、持久恢复完整阶段 | 保留（历史/阶段记录） | 2026-10-11 |
| [PLAN-2026-10-13-energy-to-miniprogram.md](PLAN-2026-10-13-energy-to-miniprogram.md) | Jovi：能耗收尾 → main 合并 → 小程序阶段 | 保留（历史/阶段记录） | 2026-10-14 |
| [PLAN-2026-10-14-miniprogram-data-stage.md](PLAN-2026-10-14-miniprogram-data-stage.md) | 小程序首阶段：数据、能耗、安全和交互 | 保留（历史/阶段记录） | 2026-10-14 |
| [PLAN-2026-10-14-unified-visual-data-stage.md](PLAN-2026-10-14-unified-visual-data-stage.md) | 统一视觉与数据状态完整阶段 | 保留（历史/阶段记录） | 2026-10-15 |
| [PLAN-2026-10-15-safety-compat-diagnostics.md](PLAN-2026-10-15-safety-compat-diagnostics.md) | TeslaMate 安全、兼容与诊断优化：已授权实施阶段 | 保留（历史/阶段记录） | 2026-10-15 |
| [PLAN-2026-10-18-compact-drive-history.md](PLAN-2026-10-18-compact-drive-history.md) | 行程历史紧凑布局 | 保留（历史/阶段记录） | 2026-10-18 |
| [PLAN-2026-10-24-data-foundation-remote-stage.md](PLAN-2026-10-24-data-foundation-remote-stage.md) | MateLink 数据底座与真实空态：完整远端阶段交接 | 保留（历史/阶段记录） | 2026-10-24 |
| [PLAN-2026-10-25-room-first-analytics.md](PLAN-2026-10-25-room-first-analytics.md) | PR #27 — 2026-10-25 offline-first analytics stage | 保留（历史/阶段记录） | 2026-10-25 |
| [PRD-2026-08-28-jourvolt-deploy-login.md](PRD-2026-08-28-jourvolt-deploy-login.md) | PRD：JourVolt / MateLink 部署与登录（2026-08-28） | 保留（历史/阶段记录） | 2026-08-29 |
| [PROGRESS-GAP-REVIEW-2026-08-28.md](PROGRESS-GAP-REVIEW-2026-08-28.md) | MateLink / JourVolt 进度与缺口盘点（2026-08-28） | 保留（历史/阶段记录） | 2026-08-29 |
| [PROJECT-REQUIREMENTS.md](PROJECT-REQUIREMENTS.md) | MateLink 车迹小程序项目最高要求与开发纪律 | 现行 | 2026-11-07 |
| [QA-FULL-USER-JOURNEY-20261008.md](QA-FULL-USER-JOURNEY-20261008.md) | MateLink 完整用户旅程与真实数据链路测试方案 | 保留（历史/阶段记录） | 2026-10-08 |
| [QA-LOCAL-CODEX-PROMPT-20261008.md](QA-LOCAL-CODEX-PROMPT-20261008.md) | 本地 Codex 整轮测试交接 Prompt | 保留（历史/阶段记录） | 2026-10-08 |
| [README.md](README.md) | MateLink 工程审核与故障记录入口 | 现行 | 2026-10-25 |
| [REMOTE-AUDIT-MINI-20261103.md](REMOTE-AUDIT-MINI-20261103.md) | Mini review implementation — 2026-11-03 | 保留（历史/阶段记录） | 2026-11-02 |
| [REMOTE-DESIGN-HANDOFF-MINIPROGRAM.md](REMOTE-DESIGN-HANDOFF-MINIPROGRAM.md) | Tesla_MateLink 微信小程序 UI 现状取证与远端设计交接总案 (Master Handoff Dossier) | 保留（历史/阶段记录） | 2026-11-09 |
| [REMOTE-FULL-DATA-STAGE-MANDATE-20261007.md](REMOTE-FULL-DATA-STAGE-MANDATE-20261007.md) | 远端完整执行阶段授权与交接：2026-10-07 | 保留（历史/阶段记录） | 2026-10-11 |
| [REVIEW-2026-10-25-PR27-PARKED-ADJACENCY.md](REVIEW-2026-10-25-PR27-PARKED-ADJACENCY.md) | PR #27 — 2026-10-25 remote final-review repair: parked adjacency | 保留（历史/阶段记录） | 2026-10-25 |
| [REVIEW-20261103-MINI-CI-CALENDAR.md](REVIEW-20261103-MINI-CI-CALENDAR.md) | Mini full-repository compatibility gate — calendar assertion correc… | 保留（历史/阶段记录） | 2026-11-03 |
| [RPT-2026-10-02-history-memory-repair.md](RPT-2026-10-02-history-memory-repair.md) | 2026-10-02 历史读取内存整改：实施与验证报告 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-02-local-handoff-acceptance.md](RPT-2026-10-02-local-handoff-acceptance.md) | 2026-10-02 远端 OOM 修复本地接收与兼容验收 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-02-review-baseline.md](RPT-2026-10-02-review-baseline.md) | 2026-10-02 工程审核基线 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-02-stage2-decode-admission-validation.md](RPT-2026-10-02-stage2-decode-admission-validation.md) | Stage2 decode admission local acceptance — 2026-10-02 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-02-stage2-import-archive-validation.md](RPT-2026-10-02-stage2-import-archive-validation.md) | Stage 2 import/archive local verification — 2026-10-02 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-02-stage2-local-admission-validation.md](RPT-2026-10-02-stage2-local-admission-validation.md) | Stage 2 local acceptance — 2026-10-02 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-02-web-pagination-compat.md](RPT-2026-10-02-web-pagination-compat.md) | 2026-10-02 Web历史分页兼容补充 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-03-compact-bootstrap-contract.md](RPT-2026-10-03-compact-bootstrap-contract.md) | Compact bootstrap contract before persistence integration — 2026-10-03 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-03-compact-native-transition.md](RPT-2026-10-03-compact-native-transition.md) | Compact native transitions, before persistence integration — 2026-1… | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-03-native-detail-shadow-chunks.md](RPT-2026-10-03-native-detail-shadow-chunks.md) | Native session detail shadow chunks — 2026-10-03 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-03-native-identity-idle-finalizer-repair.md](RPT-2026-10-03-native-identity-idle-finalizer-repair.md) | Native session identity and idle finalizer repair — 2026-10-03 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-03-native-shadow-bounded-comparison.md](RPT-2026-10-03-native-shadow-bounded-comparison.md) | Bounded native shadow comparison — 2026-10-03 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-03-native-shadow-generation-rebuild.md](RPT-2026-10-03-native-shadow-generation-rebuild.md) | Bounded native detail generation rebuild — 2026-10-03 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-03-stage2-persisted-summary-validation.md](RPT-2026-10-03-stage2-persisted-summary-validation.md) | Stage 2 persisted PostgreSQL summaries — 2026-10-03 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-03-stage2-restart-resource-qualification.md](RPT-2026-10-03-stage2-restart-resource-qualification.md) | Stage 2 process/cancellation/resource qualification — 2026-10-03 | 保留（历史/阶段记录） | 2026-10-07 |
| [RPT-2026-10-11-local-persistent-recovery.md](RPT-2026-10-11-local-persistent-recovery.md) | MateLink 本地实施与聊天复审接力 | 保留（历史/阶段记录） | 2026-10-11 |
| [RPT-2026-10-11-persistent-recovery-stage-packet.md](RPT-2026-10-11-persistent-recovery-stage-packet.md) | 一次完整远端执行包：来源隔离与持久告警恢复 | 保留（历史/阶段记录） | 2026-10-11 |
| [RPT-2026-10-14-energy-closeout-validation.md](RPT-2026-10-14-energy-closeout-validation.md) | Android能耗收尾与小程序接续验证 | 保留（历史/阶段记录） | 2026-10-14 |
| [RPT-2026-10-14-miniprogram-data-validation.md](RPT-2026-10-14-miniprogram-data-validation.md) | 小程序数据阶段执行与验收 | 保留（历史/阶段记录） | 2026-10-14 |
| [RPT-2026-10-21-build62-integrity-review.md](RPT-2026-10-21-build62-integrity-review.md) | MateLink build62 数据与页面收口复审包 | 保留（历史/阶段记录） | 2026-10-21 |
| [RPT-2026-10-21-build62-iteration2-integrity-review.md](RPT-2026-10-21-build62-iteration2-integrity-review.md) | MateLink build62 数据真实性第二轮复审包 | 保留（历史/阶段记录） | 2026-10-21 |
| [RPT-2026-10-24-data-foundation-remote-execution.md](RPT-2026-10-24-data-foundation-remote-execution.md) | MateLink data foundation: remote execution, evidence and gates | 保留（历史/阶段记录） | 2026-10-24 |
| [RPT-2026-10-29-mini-e-migration.md](RPT-2026-10-29-mini-e-migration.md) | 2026-10-29 Mini migration to the E original directory | 保留（历史/阶段记录） | 2026-10-29 |
| [RPT-2026-10-30-miniprogram-parameter-source-calculation-audit.md](RPT-2026-10-30-miniprogram-parameter-source-calculation-audit.md) | MateLink 小程序逐页参数、数据来源与计算审核 | 保留（历史/阶段记录） | 2026-10-30 |
| [RPT-2026-10-30-miniprogram-repair-final.md](RPT-2026-10-30-miniprogram-repair-final.md) | MateLink 小程序审核问题修复与验证回执 | 保留（历史/阶段记录） | 2026-10-30 |
| [RPT-2026-10-30-miniprogram-repair-reaudit.md](RPT-2026-10-30-miniprogram-repair-reaudit.md) | 小程序修复回执独立复审与逐页运行审核 | 保留（历史/阶段记录） | 2026-10-30 |
| [RPT-2026-10-31-codex-with-chatgpt-local-mcp.md](RPT-2026-10-31-codex-with-chatgpt-local-mcp.md) | Codex with ChatGPT 本地直连核验 | 保留（历史/阶段记录） | 2026-10-31 |
| [RPT-2026-10-31-miniprogram-android-ui-alignment.md](RPT-2026-10-31-miniprogram-android-ui-alignment.md) | MateLink 微信小程序与 Android App 页面对齐审核报告 | 保留（历史/阶段记录） | 2026-10-31 |
| [SAFETY-COMPAT-20261015.md](SAFETY-COMPAT-20261015.md) | Safety stage: upstream compatibility lane (2026-10-15) | 保留（历史/阶段记录） | 2026-10-15 |
| [SOP-ANDROID-DEVICE-DEPLOYMENT-AND-VERIFICATION.md](SOP-ANDROID-DEVICE-DEPLOYMENT-AND-VERIFICATION.md) | MateLink Android 构建部署、真机更新与验证标准作业程序 (SOP) | 保留（历史/阶段记录） | 2026-09-06 |
| [SPEC-2026-11-05-app-page-display-and-address-formatting-rules.md](SPEC-2026-11-05-app-page-display-and-address-formatting-rules.md) | MateLink 小程序与 App 页面展示规范与地址动态裁剪规则 | 保留（历史/阶段记录） | 2026-11-06 |
| [STITCH_PAGE_MAPPING.md](STITCH_PAGE_MAPPING.md) | MateLink Stitch 1:1 — Baseline Page Mapping | 保留（历史/阶段记录） | 2026-07-09 |
| [SUBMISSION-INDEX.md](SUBMISSION-INDEX.md) | MateLink 车迹小程序项目提交目录与版本履历台账 (Submission Index) | 现行 | 2026-11-15 |
| [SUBMISSION-PLAYBOOK.md](SUBMISSION-PLAYBOOK.md) | MateLink 车迹小程序项目提交与发布备忘录 (Submission Playbook) | 现行 | 2026-11-07 |
| [TECH-2026-10-23-history-loading-cache-pagination.md](TECH-2026-10-23-history-loading-cache-pagination.md) | 充电历史与行程页面加载体验、本地持久化与无限滚动方案 (Build 67 / v2.1.48) | 保留（历史/阶段记录） | 2026-10-24 |
| [TECH-PLAN-2026-10-11-fixes-storage-invite.md](TECH-PLAN-2026-10-11-fixes-storage-invite.md) | MateLink · 技术修复与架构演进方案 | 保留（历史/阶段记录） | 2026-10-11 |
| [TELEMETRY-CONFIGURATION-STATUS-2026-09-07.md](TELEMETRY-CONFIGURATION-STATUS-2026-09-07.md) | MateLink Telemetry Configuration Status | 保留（历史/阶段记录） | 2026-09-07 |
| [TESLAMATE-ARCHIVE-BRIDGE.md](TESLAMATE-ARCHIVE-BRIDGE.md) | TeslaMate 个人归档桥接契约 | 保留（历史/阶段记录） | 2026-09-27 |
| [TESLA_ONBOARDING_AND_DATA_INTEGRITY_GATE_REPORT-2026-09-08.md](TESLA_ONBOARDING_AND_DATA_INTEGRITY_GATE_REPORT-2026-09-08.md) | TESLA_ONBOARDING_AND_DATA_INTEGRITY_GATE_REPORT | 保留（历史/阶段记录） | 2026-09-08 |
| [TLS-ENERGY-FIXED-STAGE-LOCAL-RECEIVE-20261009.md](TLS-ENERGY-FIXED-STAGE-LOCAL-RECEIVE-20261009.md) | Immutable-source local receipt — full TLS, energy and history stage… | 保留（历史/阶段记录） | 2026-10-11 |
| [TROUBLESHOOTING-LESSONS.md](TROUBLESHOOTING-LESSONS.md) | MateLink 车迹小程序项目处理经验与踩坑复盘 (Troubleshooting & Lessons Learned) | 现行 | 2026-11-07 |
| [UI-DESIGN-PROMPT.md](UI-DESIGN-PROMPT.md) | MateLink App UI 设计提示词 | 保留（历史/阶段记录） | 2026-07-08 |
| [USER-DEPLOYMENT-SETUP-GUIDE.md](USER-DEPLOYMENT-SETUP-GUIDE.md) | MateLink 普通用户部署与配置指南 | 保留（历史/阶段记录） | 2026-07-11 |
| [WECHAT-BINDING-IMPLEMENTATION-20261028.md](WECHAT-BINDING-IMPLEMENTATION-20261028.md) | 0.6.0 微信绑定实现与验收边界 | 保留（历史/阶段记录） | 2026-10-29 |
| [miniprogram-ai-unified-baseline-v2.0.md](miniprogram-ai-unified-baseline-v2.0.md) | MateLink 小程序 AI 功能统一基线 v2.0（双边共识版，业主终审通过 2026-11-15 20:16；执行未授权） | 现行 | 2026-11-15 |

### `docs/assets/ui-evidence/`

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [assets/ui-evidence/01-dashboard.jpg](assets/ui-evidence/01-dashboard.jpg) | UI 取证截图/预览图（资产） | 保留（历史/阶段记录） | 2026-11-09 |
| [assets/ui-evidence/02-drives.jpg](assets/ui-evidence/02-drives.jpg) | UI 取证截图/预览图（资产） | 保留（历史/阶段记录） | 2026-11-09 |
| [assets/ui-evidence/03-charges.jpg](assets/ui-evidence/03-charges.jpg) | UI 取证截图/预览图（资产） | 保留（历史/阶段记录） | 2026-11-09 |
| [assets/ui-evidence/04-more.jpg](assets/ui-evidence/04-more.jpg) | UI 取证截图/预览图（资产） | 保留（历史/阶段记录） | 2026-11-09 |
| [assets/ui-evidence/05-tools.jpg](assets/ui-evidence/05-tools.jpg) | UI 取证截图/预览图（资产） | 保留（历史/阶段记录） | 2026-11-09 |
| [assets/ui-evidence/06-analysis.jpg](assets/ui-evidence/06-analysis.jpg) | UI 取证截图/预览图（资产） | 保留（历史/阶段记录） | 2026-11-09 |
| [assets/ui-evidence/v067-preview-qr.png](assets/ui-evidence/v067-preview-qr.png) | UI 取证截图/预览图（资产） | 保留（历史/阶段记录） | 2026-11-09 |

### `docs/audits/`

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [audits/2026-09-02-android-state-ux-reliability.md](audits/2026-09-02-android-state-ux-reliability.md) | Android Core UX & State Reliability Repair | 保留（历史/阶段记录） | 2026-09-02 |
| [audits/2026-09-07-onboarding-source-integrity.md](audits/2026-09-07-onboarding-source-integrity.md) | Tesla MateLink onboarding & data-source integrity audit — 2026-09-07 | 保留（历史/阶段记录） | 2026-09-07 |
| [audits/2026-09-07-post-login-keypair-gap.md](audits/2026-09-07-post-login-keypair-gap.md) | Post-login virtual-key acceptance gap — 2026-09-07 | 保留（历史/阶段记录） | 2026-09-07 |
| [audits/2026-09-09-browser-and-telemetry-readiness.md](audits/2026-09-09-browser-and-telemetry-readiness.md) | Browser choice and post-authorization data review | 保留（历史/阶段记录） | 2026-09-09 |
| [audits/2026-09-10-data-chain-repair-and-cloud-merge.md](audits/2026-09-10-data-chain-repair-and-cloud-merge.md) | 2026-09-10：数据链修复与云端历史合并 | 保留（历史/阶段记录） | 2026-09-10 |

### `docs/evidence/2026-10-03-history-qualification/`

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [evidence/2026-10-03-history-qualification/resource-matrix.json](evidence/2026-10-03-history-qualification/resource-matrix.json) | 结构化证据数据 | 保留（历史/阶段记录） | 2026-10-07 |

### `docs/miniprogram/`

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [miniprogram/ADR-0001-client-framework.md](miniprogram/ADR-0001-client-framework.md) | ADR-0001：微信小程序客户端采用 Taro React TypeScript | 保留（历史/阶段记录） | 2026-09-12 |
| [miniprogram/BRANCH_INVENTORY_20260914.md](miniprogram/BRANCH_INVENTORY_20260914.md) | feature/wechat-miniprogram 分支盘点 | 保留（历史/阶段记录） | 2026-09-15 |
| [miniprogram/INTERFACE-CONTRACT-M1.md](miniprogram/INTERFACE-CONTRACT-M1.md) | 微信小程序接口契约边界（M2 修订） | 保留（历史/阶段记录） | 2026-09-13 |
| [miniprogram/M0_FEASIBILITY_20260911.md](miniprogram/M0_FEASIBILITY_20260911.md) | MateLink 微信小程序 M0 可行性记录 | 保留（历史/阶段记录） | 2026-09-11 |
| [miniprogram/M1_ANDROID_PARITY_AUDIT_20260912.md](miniprogram/M1_ANDROID_PARITY_AUDIT_20260912.md) | M1 微信小程序与 Android 业务契约审计 | 保留（历史/阶段记录） | 2026-09-12 |
| [miniprogram/M2_AUTH_AND_DATA_REPAIR_20260913.md](miniprogram/M2_AUTH_AND_DATA_REPAIR_20260913.md) | 小程序 M2 账号与数据链路整改 | 保留（历史/阶段记录） | 2026-09-13 |
| [miniprogram/M3_1_DEPENDENCY_REMEDIATION_20260914.md](miniprogram/M3_1_DEPENDENCY_REMEDIATION_20260914.md) | M3.1 依赖审计与处置记录 | 保留（历史/阶段记录） | 2026-09-15 |
| [miniprogram/M3_1_HANDOFF_20260918.md](miniprogram/M3_1_HANDOFF_20260918.md) | MateLink 微信小程序 M3.1 当前交接 | 保留（历史/阶段记录） | 2026-09-18 |
| [miniprogram/M3_1_REVIEW_CLOSURE_20260914.md](miniprogram/M3_1_REVIEW_CLOSURE_20260914.md) | 微信小程序 M3.1 复审缺陷收口 | 保留（历史/阶段记录） | 2026-09-15 |
| [miniprogram/M3_1_TEST_EVIDENCE_20260914.md](miniprogram/M3_1_TEST_EVIDENCE_20260914.md) | M3.1 测试与产物证据 | 保留（历史/阶段记录） | 2026-09-15 |
| [miniprogram/M3_AUTH_SCOPE_REPAIR_20260913.md](miniprogram/M3_AUTH_SCOPE_REPAIR_20260913.md) | 小程序 M3 账号、车辆、历史与授权事务整改 | 保留（历史/阶段记录） | 2026-09-13 |
| [miniprogram/PREAPPLICATION_CHECKLIST_20260912.md](miniprogram/PREAPPLICATION_CHECKLIST_20260912.md) | MateLink 微信小程序人工申请前清单 | 保留（历史/阶段记录） | 2026-09-15 |

### `docs/openspec/`

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [openspec/config.yaml](openspec/config.yaml) | 配置 | 保留（历史/阶段记录） | 2026-08-29 |

### `docs/prototypes/`

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [prototypes/index.html](prototypes/index.html) | 原型页面 | 保留（历史/阶段记录） | 2026-07-08 |

### `docs/superpowers/plans/`

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [superpowers/plans/2026-07-09-app-mimo-data-setup.md](superpowers/plans/2026-07-09-app-mimo-data-setup.md) | app_mimo 未完成任务实施计划 | 保留（历史/阶段记录） | 2026-07-11 |
| [superpowers/plans/2026-07-11-app-mimo-crash-ui-data-repair.md](superpowers/plans/2026-07-11-app-mimo-crash-ui-data-repair.md) | app_mimo 崩溃、详情页与真实数据修复计划 | 保留（历史/阶段记录） | 2026-08-09 |
| [superpowers/plans/2026-07-11-app-mimo-vehicle-data-and-analytics.md](superpowers/plans/2026-07-11-app-mimo-vehicle-data-and-analytics.md) | Vehicle Data and Analytics Implementation Plan | 保留（历史/阶段记录） | 2026-07-11 |
| [superpowers/plans/2026-08-01-history-analytics-implementation.md](superpowers/plans/2026-08-01-history-analytics-implementation.md) | History and Analytics Reliability Implementation Plan | 保留（历史/阶段记录） | 2026-08-23 |
| [superpowers/plans/2026-09-06-matelink-data-integrity-telemetry-repair.md](superpowers/plans/2026-09-06-matelink-data-integrity-telemetry-repair.md) | MateLink Data Integrity and Fleet Telemetry Repair Implementation Plan | 保留（历史/阶段记录） | 2026-10-02 |

### `docs/superpowers/specs/`

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [superpowers/specs/2026-07-11-vehicle-data-analytics-design.md](superpowers/specs/2026-07-11-vehicle-data-analytics-design.md) | Vehicle Data and Analytics Design | 保留（历史/阶段记录） | 2026-07-11 |
| [superpowers/specs/2026-08-01-history-analytics-design.md](superpowers/specs/2026-08-01-history-analytics-design.md) | History and Analytics Reliability Design | 保留（历史/阶段记录） | 2026-08-01 |
| [superpowers/specs/2026-08-23-matelink-vehicle-location-energy-design.md](superpowers/specs/2026-08-23-matelink-vehicle-location-energy-design.md) | MateLink 车型展示、地点识别与驻车能耗设计 | 保留（历史/阶段记录） | 2026-08-29 |
| [superpowers/specs/2026-08-23-pearl-drive-motion-design.md](superpowers/specs/2026-08-23-pearl-drive-motion-design.md) | MateLink 珍珠电驱动效与浅色视觉优化设计 | 保留（历史/阶段记录） | 2026-08-29 |

### `docs/ui-alignment-20261113/`

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [ui-alignment-20261113/HANDOFF.md](ui-alignment-20261113/HANDOFF.md) | Mini UI 对齐阶段 · 固定接收 | 保留（历史/阶段记录） | 2026-11-13 |

### `docs/ui-product-20261110/`

| 文档 | 用途 | 状态 | 最后更新 |
|---|---|---|---|
| [ui-product-20261110/MINIPROGRAM.md](ui-product-20261110/MINIPROGRAM.md) | Mini Program product UI stage | 保留（历史/阶段记录） | 2026-11-10 |

## 已删除记录

本次清理（2026-11-15，随 v2.0 落稿）：**删除 0 个文件**。判定方法：以 main 尖端 `ef885888` 的 `docs/` 全量树为 inventory，逐文件核对——仓内此前没有任何小程序 AI 功能（CloudBase / deepseek / hy-image / Consent / 额度熔断）相关文档，也没有 v1.0 计划稿，v2.0 定稿是新主题首稿，没有任何既有文件被其完全取代；按“拿不准的保留”原则，全部既有文档保留，归档处置待业主定。PR #23（docs 清理提案）未动。

## 待业主定事项

- 历史/阶段记录的归档与删除：与 PR #23 提案一并由业主定，本次索引仅标注保留，未删除任何文件。
- 小程序 AI 基线 v2.0 的 Phase 0 执行授权与执行者：执行未授权，待业主定（见基线第十节决议记录）。

---

## 原入口内容（保留，2026-10-25）

# MateLink 工程审核与故障记录入口

当前最新交付：[驻车详情重设计与冷启动缓存修复 (Build 70 / v2.1.51)](ANDROID-RELEASE-LOG.md)。2026-10-25（第三轮）：驻车详情分层卡片重设计（概要/电量/云端遥测，消除满屏"暂无数据"）；充电/行程列表冷启动经离线映射直读 Room 缓存（实测杀进程 2 秒渲染 67 次充电记录）。

上一交付：[驻车详情本地优先修复 (Build 69 / v2.1.50)](ANDROID-RELEASE-LOG.md)。2026-10-25（第二轮）：驻车详情由相邻本地行程秒渲染 + 20s 云端增强 + 降级横幅/重试；充电段不再冒充能耗估算；同签覆盖安装并实机验证。

上一交付：[实机 UI 审计修复 (Build 68 / v2.1.49)](ANDROID-RELEASE-LOG.md)。2026-10-25：充电/行程页 20s 云端同步超时降级、行程卡片完整 kWh/100km 单位、驻车 SOC 格式统一、能耗徽标对齐、仪表盘温度占位；全量测试 1026×2 通过，同签覆盖安装并实机逐项验证。

上一交付：[充电历史与行程页面加载体验、本地持久化与无限滚动方案 (Build 67 / v2.1.48)](TECH-2026-10-23-history-loading-cache-pagination.md)。2026-10-24：完成彻底消除整屏空白遮罩、本地 SQLite 优先读取 0ms 呈现、充电默认 30 天 / 行程默认 7 天、触底无限自动分页及真机（OnePlus 7 Pro）实测通过。

历史阶段入口：[能耗最终源码实测与待确认门](ENERGY-LOCAL-FINAL-QUALIFICATION-8625D28E-20261009.md)。2026-10-09：能耗候选2.1.27/build46已同签保留数据安装；真实认证历史仍遇TLS失败，main尚未合并，生产API仍是独立底座。不要将构建、安装或health200等同真实数据验收。

- [完整能耗修复范围与官方公式依据](ENERGY-REPAIR-CLEANUP-STAGE-20261008.md)。
- [固定源码交接与根因、回滚](ENERGY-STAGE-FIXED-SHA-HANDOFF-20261009.md)。
- [实机TLS分型与安全排查](ENERGY-TLS-DEVICE-DIAGNOSTIC-20261009.md)。
- [历史基线：2026-10-02部署与待修复状态](RPT-2026-10-02-review-baseline.md)。

当前阶段入口：[能耗传输、部署TLS与已部署bb09兼容性终审](ENERGY-TRANSPORT-COMPATIBILITY-FINAL-STAGE-20261009.md)；[旧设备签名实测、认证TLS待确认门](ENERGY-TLS-DEVICE-DIAGNOSTIC-20261009.md)。源码CI通过不代表手机认证或Fleet自然数据通过。

- [完整固定SHA本地接收、独立签名安装与人类验收矩阵](TLS-ENERGY-FIXED-STAGE-LOCAL-RECEIVE-20261009.md)：由PR17最终完成评论获取不可变版本；原本地Codex只作独立验证，不代远端修业务代码。

历史审核入口：[2026-10-02 主分支、部署与待修复状态](RPT-2026-10-02-review-baseline.md)。

- [10月4日历史同步与详情复发](BUG-REPAIR-2026-10-04-phone-history-stale.md)：首次恢复、07:51再现、安全异常分型、未知SOC及43候选验收边界。
- [云登录 502 与历史读取内存故障](DBG-2026-10-02-login-oom.md)：运行证据、源码风险、优化方案和验收条件。
- [充电费用与历史同步验收](BUG-REPAIR-2026-10-01-sync-charge-cost.md)。
- [TeslaMate 归档桥接](TESLAMATE-ARCHIVE-BRIDGE.md)。
- 最新执行清单位于 `../tasks/todo.md`，经验规则位于 `../tasks/lessons.md`。

日期较早的文档是历史记录，不代表当前部署或完成状态。代码已合并、测试通过、手机已安装、云端已部署、Fleet 实车采集通过是五个独立门禁。
