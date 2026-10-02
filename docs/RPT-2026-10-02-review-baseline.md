# 2026-10-02 工程审核基线

## 结论

审核请使用仓库 `Jovifei/tesla-master-mimo` 的 `main`，不要把旧功能分支当成最新工程。更新本文前已执行 `git fetch origin`，本地 main 与 origin/main 均为 `155056ee1a1b62fd8615c0cff4c6dadc0d8f25ce`，ahead/behind 为 0/0，工作区干净。本文及配套记录将形成其后的文档提交；审核时取最新 main，并记录实际 SHA。

## 代码、安装与部署分别核验

| 层次 | 已核验状态 | 边界 |
| --- | --- | --- |
| 本地与远端代码 | main 同步；当前 Android、加载动画、小程序、归档和自动接入交付已整合 | 不等于全部历史分支都采用 |
| Android | main 源构建 f9d4eff，2.1.22/build41；Debug/Release 各 563 项，0 失败，Release 8 项跳过；Release lint 0 错误/241 警告 | 不等于无警告或 Tesla 实车采集成功 |
| 真机 | 同签名 install -r；firstInstallTime 保留；启动样本无 FATAL/ANR | 车主确认及完整业务验收仍独立 |
| API/bridge/小程序 | 合并轮 Go test/vet、bridge test/vet、小程序 typecheck、61 项测试及构建通过 | 是此前合并轮证据，本次文档更新没有重新跑业务测试 |
| 线上 API | 本次 /healthz 实测 build_sha=781c4025a720a257fdd6d2f8ee9a309655bfa020，HTTP 正常 | 不是最新 main 部署；内存故障仍未修复 |
| Fleet Telemetry | 最近记录 awaiting_first_event | TeslaMate 导入、健康检查和登录不能替代 Fleet 首事件 |

APK：`E:/Claude_allow/Download/MateLink-main-f9d4eff-2.1.22.apk`。
SHA256：`48BD220E0EDA63BA5603DDD88925237C9E96FAA909AF5E238654247034BD5951`。

## 远端分支分类

当前交付分支已合入 main，旧分支引用仍保留用于追溯；分支数量不是本地未同步提交数量。未删除分支或 worktree。

下列 7 个远端分支尚非 main 的祖先，不能宣称已经全部合并：

| 分支 | 处理建议 |
| --- | --- |
| chore/20260909-data-recovery-verification | 历史 CI/编码补丁材料，保留，勿盲目合并 |
| chore/20260910-data-chain-repair-verification | 历史验证/补丁材料，独立审查 |
| feature/20260820-drive-completion-report | 独立行程报告功能，涉及数据库与通知，单独审核 |
| fix/20260829-live-data-analysis-truth | 旧修复及补丁材料，逐项比较当前实现 |
| fix/20260903-health-readiness-separation | 健康探针调整，独立评估部署语义 |
| tmp/20260830-main-source-export | 临时导出工作流，非当前发布入口 |
| tmp/20260830-pr1-finalize-clean | 编码补丁临时材料，非当前发布入口 |

## 远端审核重点与未完成项

1. P0：[登录 OOM 故障](DBG-2026-10-02-login-oom.md)，检查元数据路径全量加载、并发与内存预算；当前只有定位，没有修复提交。
2. 大历史分页、单会话详情和桥接批次，检查是否按账号/车辆约束、是否存在无界读取。
3. Fleet 自动配置状态和车主确认：不能把 502 误判为缺钥匙，也不能把 awaiting_first_event 标记成功。
4. 多账号隔离、真实首事件、三趟行程/一次充电及七天观察，仍需独立验收。
5. 旧文档的“历史不上传”等结论须服从后续完整云归档架构决策；不要直接从旧计划判断现状。

本轮范围仅 Git 状态核验和文档更新；未修改业务代码、未部署、未生成新 APK，也未把先前测试当作本轮新增修复证据。
