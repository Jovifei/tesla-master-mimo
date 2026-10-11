# 2026-10-02 工程审核基线

## 最新增量入口

原审核main为 `f0dcd447dfcfdaf3a479f78eebd4077cd2e52a45`。本次OOM源码修复另在 `fix/20261002-history-metadata-memory`，已测功能提交 `9bda94f4aa73ee038722a6b192e43e7604e780f3`，见 [内存整改报告](RPT-2026-10-02-history-memory-repair.md)。修复分支已通过隔离Go/PG验证，但本轮未合并main、未部署生产；不要继续把旧WeChat功能分支当新工程，也不要把修复分支误认成线上构建。

下面保留原基线核验内容，它描述原提交时的状态。

## 原结论

审核请使用仓库 `Jovifei/tesla-master-mimo` 的 `main`，不要把旧功能分支当成最新工程。更新原本文前已执行 `git fetch origin`，本地 main 与 origin/main 均为 `155056ee1a1b62fd8615c0cff4c6dadc0d8f25ce`，ahead/behind 为 0/0，工作区干净。原本文及配套记录随后形成文档提交 `f0dcd44`；审核时取最新 main，并记录实际 SHA。

## 代码、安装与部署分别核验（原基线记录）

| 层次 | 已核验状态 | 边界 |
| --- | --- | --- |
| 本地与远端代码 | main 同步；当前 Android、加载动画、小程序、归档和自动接入交付已整合 | 不等于全部历史分支都采用 |
| Android | main 源构建 f9d4eff，2.1.22/build41；Debug/Release 各 563 项，0 失败，Release 8 项跳过；Release lint 0 错误/241 警告 | 不等于无警告或 Tesla 实车采集成功 |
| 真机 | 同签名 install -r；firstInstallTime 保留；启动样本无 FATAL/ANR | 车主确认及完整业务验收仍独立 |
| API/bridge/小程序 | 合并轮 Go test/vet、bridge test/vet、小程序 typecheck、61 项测试及构建通过 | 是此前合并轮证据，原文档更新未重跑业务测试 |
| 线上 API | 原现场 /healthz 实测 build_sha=781c4025a720a257fdd6d2f8ee9a309655bfa020，HTTP 正常 | 不是最新 main；本轮网页审核未读取生产新状态 |
| Fleet Telemetry | 最近记录 awaiting_first_event | TeslaMate导入、健康检查和登录不能替代Fleet首事件 |

APK：`E:/Claude_allow/Download/MateLink-main-f9d4eff-2.1.22.apk`。
SHA256：`48BD220E0EDA63BA5603DDD88925237C9E96FAA909AF5E238654247034BD5951`。

## 远端分支分类（原基线记录）

交付分支已合入main，旧分支引用保留追溯；分支数量不等于未同步提交数。本次未删除分支或worktree。以下7条尚非原main祖先，不能宣称全部合并：

| 分支 | 处理建议 |
| --- | --- |
| chore/20260909-data-recovery-verification | 历史CI/编码补丁材料，保留，勿盲目合并 |
| chore/20260910-data-chain-repair-verification | 历史验证/补丁材料，独立审查 |
| feature/20260820-drive-completion-report | 独立行程报告功能，涉及数据库与通知，单独审核 |
| fix/20260829-live-data-analysis-truth | 旧修复及补丁材料，逐项比较当前实现 |
| fix/20260903-health-readiness-separation | 健康探针调整，独立评估部署语义 |
| tmp/20260830-main-source-export | 临时导出工作流，非当前发布入口 |
| tmp/20260830-pr1-finalize-clean | 编码补丁临时材料，非当前发布入口 |

本次新增一条明确用途的OOM修复分支，不等于新增产品主线；无需重新激活旧功能分支。

## 审核重点与未完成项

1. P0：[登录OOM故障](DBG-2026-10-02-login-oom.md)。新修复已移除三个元数据入口的全量读取，生产仍须备份发布与真实资源观察。
2. 列表数据库分页与单详情保持完整；重详情/归档并发和实时写放大仍需下一阶段处理。
3. 自动配置和车主确认：502不能误判缺钥匙，awaiting_first_event不能标成功。
4. 多账号、真实首事件、三趟行程/一次充电及七天观察仍独立验收。
5. 当前全云归档决策优先于旧本地-only计划；[新分层存储提案](ADR-2026-10-02-tiered-history-storage.md)尚未批准TTL或执行迁移。

本轮新增后端源码与274项隔离Go/PG验证。没有重新构建App、没有生产部署、没有本地Obsidian镜像同步；这些只能由本地按实际执行补记。
