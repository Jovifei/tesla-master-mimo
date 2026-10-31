# PR #27 Android 版本契约修复

日期：2026-10-31（Asia/Shanghai）。问题 ID：`OCT31-CI-RELEASE-CONTRACT`。发现版本：App `2.1.60 / build79`，PR 源码 `883f1722e281fb0e06a98bb5d9b29ee97fba42dd`。

## 根因与实际失败

[Android job 124167269965](https://github.com/Jovifei/tesla-master-mimo/actions/runs/41085835288/job/124167269965) 在 Debug 与 Release 都失败于 `SettingsExperienceContractTest.currentReleaseShowsVersionAndLocalizedRepairNotes` 第 57 行：`expected:<77> but was:<79>`。构建配置已升级为 `79 / 2.1.60`，测试仍固定为 `77 / 2.1.58`。

已下载并核对完整 Android artifact，SHA-256 为 `8222e78a9fe0a726b717269df0332bd024561c22cf195318fbe4f3c918b5a0d7`。其中 Debug、Release 各 343 份测试 XML、1712 项测试，均为 1 失败、0 错误；Release 有 8 项既有跳过。唯一失败均为上述版本契约。Actions 的 `android-summary` 只汇总证据，其成功不代表 `android` 成功；该 SHA 实际为 17 项成功、1 项失败，早先“18 项全部成功”的总结已纠正。

## 最小修复

只将该测试中的两个预期值同步为 `79 / 2.1.60`。仍然检查 Gradle 与 BuildConfig 版本相同、两处设置页版本说明使用 BuildConfig，以及中文和英文的更新说明。未删除或放宽业务断言，未调整生产 Kotlin、页面布局、资源、服务端或用户配置。

独立只读复审通过；对 build79 已有的自托管旧接口身份兼容、同一行程详情/缓存隔离及零功率、负功率测试未发现阻断问题。本次复审不是新的真机或生产验收，不将缺失的特定迟到响应场景当作已实测。

## 本地验证

- 定向回归：Debug、Release 各 11 项，0 失败、0 错误、0 跳过。
- 全量单测：Debug、Release 各 343 份 XML、1712 项，0 失败、0 错误；Release 的 8 项既有跳过保留，Debug 0 跳过。
- 双 lint：Debug 与 Release 均通过，0 错误；分别报告 330 和 311 项警告。
- 签名 Release 与当前提交的远端 CI 待构建及核验；测试与 lint 日志见 `E:\Claude_allow\Download\matelink-pr27-ci-fix-20261031`。
- 本轮差异检查：生产源码与页面资源没有改动；正式 APK、Git 提交、设备与生产状态分别记录。

先前缺 SDK 环境与缺显式 Release 参数的尝试已留存，均不计为业务测试通过；按既有 SDK 和正式公开地址参数运行，不修改构建保护规则。

## 接收与记录

Jovi 已授权修复、测试后提交及合并主分支。因原 E 盘目录有并行小程序写入，本轮继续使用现有 `E:\project\_worktrees\matelink-app-collection-recovery-20261029`，不在共享原目录切换分支。提交会推送至 PR #27 的 App 分支；合并前须逐个核对该提交的 Android、API35 及全部远端检查。

清理仅限已经合并、没有独有源码或配置的本轮 App worktree；原目录及其他任务的未提交内容不进入本轮删除范围。Hub 在正确 worktree 身份绑定后仍连接拒绝，未上报成功；脱敏待报摘要保存在本机 `matelink-pr27-ci-fix-20261031/hub-pending-summary.json`，不自动重试。
