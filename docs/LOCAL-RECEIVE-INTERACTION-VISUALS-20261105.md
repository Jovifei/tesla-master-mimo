# MateLink 交互视觉阶段：本地接收与独立验证

仓库只允许 `Jovifei/tesla-master-mimo`。先 App，后小程序。这是同一远端阶段的接收验证，不是交给本地重新实现业务功能。

## 固定源码，不用浮动分支替代

- App 新分支：`codex/app-interaction-visuals-20261104`，原始基线 `984196d8c1a6deaff124eaa8dd6c45f55c40e700`。
- 小程序新分支：`codex/miniprogram-interaction-visuals-20261104`，原始基线 `55d49b921f541c5c1d793c88e64dfaac51ee32b2`。
- 使用远端最终回执中的两个完整 SHA、tree、parent。检查 `git show -s --format='%H%n%T%n%P' HEAD`、基线祖先关系和完整差异。更新后的分支尖端不是自动替代品。
- 两个独立干净 worktree；不要 reset/clean/stash 原 E 盘工作区，不覆盖 Owner 已改或未跟踪文件。不得将 App 后端覆盖小程序后端或相反。不得合并 main、force-push 或改变原开发分支。
- 读取实际 worktree 的 AGENTS.md。仅在本地确有项目 hub 安装时按其规则报告，身份必须对应实际 worktree。远端没有调用本地 hub。

## 实际实现范围与验证

| 区域 | 已接入的交互 | 必须保留的反例 |
| --- | --- | --- |
| 车辆绑定 | 本地既有车型插图与连接状态提示，使用原流程结果 | 插图不代表当前绑定车辆；动画结束不能变成成功 |
| Tesla / 微信 | 紧凑连接图；原同意、官方授权、会话恢复和解绑按钮不变 | MateLink 会话不等于新授予 Tesla 权限；待确认、失败、退出均不能沿用成功 |
| 胎压 | 240×460 车辆位置示意；点轮位聚焦原趋势，再点恢复全部 | 真实零保留，旧/缺失/未来/来源冲突不标为正常；320 宽和 1.8 倍字号不碰撞 |
| 行程 A/B | 原起终点区域可点选，强调当前焦点 | 明示“顺序示意，非实际轨迹”；不能补坐标或制造车辆移动 |
| 驻车 | 原记录起止区间轨道，独立会话分别展示 | 未知结束、重复/冲突来源、会话间空白不得制造停充事件或 kWh |
| 车门/车窗/行李箱 | 原警告区域展开类别位置图 | 只有汇总布尔值时，不判断具体哪扇门窗打开；未知不能画成关闭 |
| 行程/充电空态 | 小型本地图饰，保留原原因和按钮 | 不加入示例记录、充电桩搜索等不存在的操作 |
| 加载 | 原品牌 Logo 保留，新增/现有动效受生命周期、减少动态和边界限制 | 不增加请求，不清缓存，不改账号/授权状态 |

车型主视觉复用仓库原有真实车型目录。俯视图是中性车辆位置示意，不声称精确年款或用户车辆照片。文字、数据、选中与异常状态均由原生组件绘制，不写入静态图片。

## App：先独立测试，再考虑设备

1. 在隔离构建环境运行 `tools/energy-stage/android-ci.sh`，检查完整 Debug/Release、lint 两种变体、R8 与 APK。显式提供原非秘密 Release 域名参数；不能移除 Release guard，不能索要或导出签名秘密。
2. 按 XML 目录区分 Debug/Release，记录真实 tests/failures/errors/skips。不能使用旧 SHA 的绿色结果替代。
3. 在独立 API35 模拟器执行 `tools/energy-stage/android-instrumentation-ci.sh`。只允许模拟器与合成夹具，不指向 Owner 手机。检查真实 Compose 点击、焦点、四轮 0/旧/未知、作用域重置、窄屏和大字体、亮暗截图。
4. 本轮完整门禁同时关闭了继承的身份与取消问题以及 Fleet 未核实标量回退。复核 `VisualStageEnergyReceiptTest`、`LatestHistoryLoadTest`、`AnalysisCloudPublicationRoomTest` 和 Oct28 真实消费者测试；不得删除断言或把缺失能量填零。
5. 挂载隔离测试必须在对应隔离容器运行（`tools/interaction-stage/bridge-isolated-ci.sh` 仅限 GitHub Actions），不是在 Owner 系统建立实验挂载。其只读与可写别名保护不能为通过 CI 而移除。

## 小程序：锁定依赖与平台边界

1. 在 `miniprogram/` 运行 `npm ci --ignore-scripts --no-fund`、`npm ci --prefix test-tooling --ignore-scripts --no-fund`，保留 0.6.5 的既有锁文件，不自动升级或 audit fix。
2. typecheck、完整 `npm test`（含资源测试）、`npm run build:weapp`；检查 `dist/project.config.json` 的 `urlCheck === true`，生产未启用 mock。
3. 按 `.github/workflows/mini-interaction-final.yml` 运行 UTC、上海、东京、洛杉矶四时区消费者。
4. `tools/interaction-stage/browser/` 渲染实际 React 组件，用于点击与布局回归，不是微信原生验收。必须有可读 CJK 字形，截图不能是方框；当前脚本查询真实渲染字体并记录 46 张 PNG 哈希。字体仅装在测试机，不打包或上传字体文件。
5. 微信 DevTools 原生包再核对胶囊安全区、真实按钮、滚动、弹窗、返回、字体、暗色、前后台。不得上传小程序、改体验版或发布。

## 数据、安装、发布与回滚边界

本轮不改变包 ID、用户会话键、Room 版本、存储键、生产 schema、历史数据或 API 地址。保留全部本地费用、档案、备份和原品牌资源。

- 普通接收只验证/测试/构建，不签名安装、不登录真实账号、不改生产。
- 只有另外获得安装授权后，才能用既有受保护证书做同包覆盖安装；不能卸载、清应用数据或降级数据库。比较安装前后历史数量、零值费用、设置、有效会话与图片加载。
- 小程序上传、真实 WeChat/Tesla 授权、生产部署/DDL/回填、live bridge 更新均是独立门。源码 revert 不等于数据回滚。
- 未通过时保留证据与当前数据；本地不要代写业务修复，集中返回本聊天。不要改网络、代理、DNS、TTL，不唤醒车辆，不公开 VIN/轨迹/令牌。

## 一次性返回材料

通过 GitHub 返回一个脱敏证据回执：两个 full SHA/tree/parent、实际命令和退出码、XML 数量与 skip、Actions/job/artifact ID 及 SHA-256、原生/模拟器/浏览器截图、所有失败。明确区分源码、合成 JVM、API35、浏览器、微信原生、手机、provider 与生产。自然 Fleet 首条行程/充电、多用户、TPMS 和通知不能由个人归档或合成图证明。
