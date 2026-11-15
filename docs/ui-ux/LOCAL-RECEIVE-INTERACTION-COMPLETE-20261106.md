# 本地独立接收 Prompt：方案 I 完整交互视觉

继续同一 Tesla_Matelink 阶段；仅使用 `Jovifei/tesla-master-mimo`。本地 Codex 只接收、独立复审/测试/构建，并按单独授权签名安装；不要接手业务实现。任何失败集中回传当前远端阶段，不删除断言或自行补业务代码。

## 接收顺序与固定身份

先 App `codex/app-interaction-complete-20261106`，后 Mini `codex/miniprogram-interaction-complete-20261106`。从两条 PR 最终完成回执取得各自完整 40 位 SHA/tree/parent；浮动分支尖端或旧 green run 不替代。使用两个新的干净 worktree，不改变原 E 盘分支、Owner dirty/untracked、手机登录/Room/费用/照片、微信缓存、生产及备份。

每个 worktree 执行并保留输出：

```text
git rev-parse HEAD HEAD^{tree} HEAD^
git status --short
git merge-base --is-ancestor <对应下面的完整基线> HEAD
git diff --check <对应基线> HEAD
git diff --stat <对应基线> HEAD
```

App 基线 `fc812ff8505dc72f6002023bc93c041d5de92b75`；Mini 基线 `51d6514a7d897ec2890e60bc37d00c43c4d87a71`。禁止 reset --hard、clean、force push、覆盖另一工作树、合 main 或借源码回退删除用户数据。

## 独立测试

App：读取本提交 `.github/workflows/interaction-complete-full.yml` 和 `data-foundation-emulator.yml` 的准确命令与配置。在 JDK17/SDK35 环境执行 `android/gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`；Windows 使用 gradlew.bat。保留 Release 环境保护，缺少授权配置就明确报告，不能降低校验或索要秘密。JUnit 按 testDebugUnitTest/testReleaseUnitTest 目录分变体，不按类名含 Release 来分类。全量保留原有跳过及新增回归真实计数；编译 AndroidTest 不等于已运行原生测试。

原生 UI 在新的隔离 API35 模拟器执行仓库 fixture + connectedDebugAndroidTest 的受控流程，不能把脚本指向手机。重点验证 320 宽/1.8 倍字体、浅深色、四轮点选、未知/有效0、已保存高低阈值/停用/变化后重读、切车切号及迟到回调、开口文字、未知结束时间、失败并非空历史。截图仅用仓库合成数据。后端/桥接/TLS/Web 复跑必须用独立合成 PostgreSQL、内部网络及一次性卷，绝不使用真实数据库或凭据。

Mini：在 miniprogram 下运行 `npm ci --ignore-scripts --no-fund`、`npm ci --prefix test-tooling --ignore-scripts --no-fund`、`npm run typecheck`、`npm test`、`npm run build:weapp`。按 mini-interaction-final.yml 复跑 UTC/Asia-Shanghai/Asia-Tokyo/America-Los_Angeles 时区测试及浏览器实际消费者，检查中文实际字形、52 个截图状态、320/412 宽、1.8 倍字体、浅深色、背景暂停和作用域清空。不得将 DOM 渲染当作微信原生；在微信开发者工具另核对图片、胶囊、安全区、按钮与原导航，不自动上传/发布。不得为开发工具修改 Owner 网络栈。

## 发布 / 恢复 / 人工门矩阵

| 门 | 需核对的证据 | 失败或回滚处理 |
| --- | --- | --- |
| 源码 | 精确 SHA/tree/parent、基线祖先、差异、原页面/后端/数据结构保留 | 身份不符即停止该版本接收，不覆盖原目录 |
| CI/本地 | 全量实际 XML、lint/R8、原生测试与截图、Mini bundle | 保留错误、退出码、原始脱敏日志；回传远端，不弱化测试 |
| 手机候选 | 单独获准后同包名/同证书 install-r；先核对版本单调性、来源与签名 | 未获安装授权不安装；不卸载/清数据；不把旧APK覆盖称为数据库回滚 |
| 数据保护 | 已有历史、手工费用、登录、照片、来源/车辆选择、关键数量升级前后核对 | 先有可审核备份与恢复范围；源码回退不等于Room/云端恢复 |
| 小程序 | 开发工具与真实微信可用性、权限与真实绑定 | 原生失败保留现场，不自动发布或清空缓存 |
| 生产 | 独立服务基线、备份、明确部署/DDL/桥接授权 | 此 UI 交付不包含生产动作；不得用 App 树覆盖现用 API |
| 自然数据 | 真正自然行程/充电、TPMS增量、通知去重与权限、双真实账号 | 合成/归档/安装/health 都不能代替这些门 |

## 集中回传格式

在两条交付 PR 各留一份脱敏证据：full SHA/tree/parent、环境、命令/退出码、各变体 totals/fail/error/skip、产物 SHA-256、截图类别与真实失败。不要写 VIN、轨迹、私人地址、账号标识、token、签名材料或原始生产响应。回报 source / synthetic-CI / emulator / native-WeChat / device / provider / production 哪一层被验证；没有执行的明确写 NOT RUN。

此 Prompt 不授权新聊天/云任务、生产变更、历史回填或车辆动作。需要车主同意、权限或资源决策时由原本地流程问 Jovi。远端负责修复业务代码，本地不逐文件派工。
