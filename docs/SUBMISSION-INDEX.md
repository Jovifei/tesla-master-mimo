# MateLink 车迹小程序项目提交目录与版本履历台账 (Submission Index)

> **维护规范**：每次执行 `cli.bat upload` 之后，必须在此文档头部按格式追加一条完整的提交流档记录，严禁遗漏。

---

## 快速索引表

| 微信版本 | 提交日期 | Git Commit | 生产 API 入口 | 单测状态 | 模拟器巡检状态 | 当前后台状态 |
|---|---|---|---|---|---|---|
| **0.6.8** | 2026-11-13 | `1b42f9ac220e5371e688b1633abf15a3f96702b5` | `https://api.teslalink.joviluma.com/` | 1115/1115 + 资源 6/6 | 生产未登录与合成配置各 9 页，Console error 无命中 | **上传成功** / 体验版状态待核实 |
| **0.6.7** | 2026-11-07 | `f2e0a8e` | `https://api.teslalink.joviluma.com/` | 79/79 PASS (100%) | 6 核心页面 0 Error | **开发版已上传** / 待设为体验版 |
| **0.6.5** | 2026-11-04 | `799a41c` | `https://api.teslalink.joviluma.com/` | Vitest 4.1.12 PASS | 基础校验通过 | **当前体验版** |
| **0.6.4** | 2026-11-03 | `0e4cd56` | `https://api.teslalink.joviluma.com/` | PASS | 微信审核通过 | **审核通过待发布** |
| **0.5.0** | 2026-10-28 | `ee7cf81` | 历史过渡配置 | PASS | - | 历史开发版 |

---

## 详细版本提交流档记录

### [v0.6.8] - 2026-11-13

* **发布类型**：日期和未知值显示修复、驻车邻接保护、微信样式兼容与模拟环境隔离。
* **Git 分支**：`codex/mini-ui-delivery-20261112`
* **Git Commit Hash**：`1b42f9ac220e5371e688b1633abf15a3f96702b5`；上传时基线 `1a8cdbede3799a9d4f2bab39620e4c9b7e023fa6`，源码含未提交修改。
* **源码身份**：上传时暂存 `miniprogram/` 子树 `645b240a41879e52bd269288118f92010315e928`；工作区源码清单 SHA-256 `7ca72581304b30fbb7e20f30ba9350eeb2e4bc3f4a4cdb8fa722b5ff584f5f65`。
* **运行参数与环境**：`TARO_APP_API_BASE_URL=https://api.teslalink.joviluma.com/`、`TARO_APP_MOCK_FIXTURE=false`、`miniprogramRoot=dist/`、AppID `wxc84899ab6146f1be`。
* **微信上传回执包体积**：主包 `1,256,155` B；vehicle-assets-1 至 6 依次 `1,739,942 / 1,733,729 / 1,790,676 / 1,708,732 / 1,770,275 / 1,547,115` B；总包 `11,546,624` B。
* **构建内容身份**：生产编译内容 SHA-256 `9c7d6187079f9463e4763115bc92f409eac010ae3b70400f2bfba1b0d9008f87`；`common.js` SHA-256 `04cd23ef1a50eb24127f0731d2db853bb19ab08cdb5cc5305dbea2e1036f7d14`。
* **质量与实测门禁**：类型检查通过；Vitest 92 文件 / 1115 项通过，车辆资源检查 6/6 通过；洛杉矶时区日期与驻车 111 项通过；微信 WXSS 实际编译通过。生产未登录与合成配置各有 9 页截图，Console error 过滤无命中。合成数据不代表真实账户、车辆或服务端数据验收；最终构建未重新实操真实鼠标拖动。
* **证据路径**：`E:/Claude_allow/Download/matelink-mini-local-check/accepted-evidence-v068-20261113.json`；上传回执 `E:/Claude_allow/Download/matelink-mini-local-check/upload-success-v068-20261113.json`。
* **微信后台状态**：2026-11-13 08:40:37 +08:00 微信开发者工具异步回执显示上传成功；是否已成为体验版、是否提交审核或正式上线均待后台核实。

---

### [v0.6.7] - 2026-11-07

* **发布类型**：UI 重构、根目录配置修复与生产 API 编译固化
* **Git 分支**：`codex/miniprogram-e-migration-20261029`
* **Git Commit Hash**：`f2e0a8e`（修复根目录与生产 API） / `9a482e0`（Apple UI 重构主提交）
* **运行参数与环境**：
  * `TARO_APP_API_BASE_URL`: `https://api.teslalink.joviluma.com/`（已固化进 `dist/common.js`）
  * `miniprogramRoot`: `dist/`
  * `AppID`: `wxc84899ab6146f1be`
* **产物体积流档**：
  * 主包体积：`1,211,664 Bytes` (~1.2 MB，低于 2MB 门禁限制)
  * vehicle-assets-1 ~ 6: 分包体积各约 1.5 ~ 1.8 MB
  * 总编译包体积：`11,502,133 Bytes` (~11.0 MB，低于 20MB 上限)
* **质量与实测门禁**：
  * `npm run typecheck`：通过（0 errors）
  * `npm test`：79 测试文件，994 测试 100% 通过；6 项车辆原始资源哈希一致性通过
  * 微信开发者工具模拟器：成功启动，Console Error 为 0
  * 逐页截图验证：
    * 仪表盘页：`simulator_live_after_fix.jpg`（Apple 卡片与黑色 Model Y 焕新版外观）
    * 行程记录页：`simulator_page_drives.jpg`（未登录态引导）
    * 充电历史页：`simulator_page_charges.jpg`（空态与登录引导）
    * 更多页：`simulator_page_me.jpg`（iOS 设置式分组圆角列表）
    * 设置工具页：`simulator_page_tools.jpg`（本地存储与电价工具）
    * 统计分析页：`simulator_page_analysis.jpg`（分析入口卡片）
* **微信后台状态**：已调用 `cli.bat upload` 成功推送至开发版本列表。

---

### [v0.6.5] - 2026-11-04
* **发布说明**：升级测试工具链 Vitest 4.1.12，完成依赖安全审计与全测试回归。
* **开发者**：Jovi
* **状态**：微信公众平台当前体验版。

---

### [v0.6.4] - 2026-11-03
* **发布说明**：体验优化与数据一致性加固。
* **状态**：审核通过待发布。

---

## 规范：版本提交流档模板（供未来每次发布追加）

```markdown
### [vX.Y.Z] - YYYY-MM-DD

* **发布类型**：[Feature / Bugfix / Refactor / Release]
* **Git 分支**：`<branch-name>`
* **Git Commit Hash**：`<commit-sha>`
* **运行参数与环境**：
  * `TARO_APP_API_BASE_URL`: `https://api.teslalink.joviluma.com/`
  * `miniprogramRoot`: `dist/`
  * `AppID`: `wxc84899ab6146f1be`
* **产物体积流档**：
  * 主包体积：`X.X MB`
  * 分包体积明细：`vehicle-assets-1` ~ `6`
  * 总包体积：`XX.X MB`
* **质量与实测门禁**：
  * `npm run typecheck`：[PASS / FAIL]
  * `npm test`：[测试文件数/测试用例数] PASS
  * 模拟器控制台 Console grep -i error：0
  * 截图留档：[路径或文件名列表]
* **微信后台状态**：[已上传开发版 / 已设为体验版 / 已提交审核]
```
