# MateLink 车迹小程序项目处理经验与踩坑复盘 (Troubleshooting & Lessons Learned)

> **导言**：本文档沉淀自 MateLink 微信小程序 v0.6.7 发布过程中的真实踩坑与故障排查经验。每一个踩坑点都对应着具体的系统机制与解决方案，后续开发必须将其作为“避坑指南”，绝不可重蹈覆辙。

---

## 踩坑一：微信开发者工具启动报错「在项目根目录未找到 app.json」

### 1. 现象回放
* 开发者工具模拟器启动失败，界面提示：
  `Error: app.json: 在项目根目录未找到 app.json`
* 无法进入页面，普通编译或刷新均无法恢复。

### 2. 底层机制与根因
* 本工程采用 Taro 4 + Webpack 5 架构。源代码在 `miniprogram/src/`，编译生成的实际小程序运行时产物均输出在 `miniprogram/dist/`。
* 微信开发者工具打开的工程根目录为 `E:\project\tesla_master\miniprogram_Weixin\miniprogram`。
* 原 `miniprogram/project.config.json` 中**漏掉了 `"miniprogramRoot"` 字段**。
* 微信开发者工具在缺少该字段时，默认将项目根目录视为小程序根目录，在 `miniprogram/app.json` 寻找入口；而该文件压根不存在（只有 `dist/app.json`），导致直接报错崩溃。

### 3. 经验沉淀与标准解法
1. 在 [miniprogram/project.config.json](file:///E:/project/tesla_master/miniprogram_Weixin/miniprogram/project.config.json) 中**必须显式写入 `"miniprogramRoot": "dist/"`**。
2. Taro 编译插件（`@tarojs/cli/dist/presets/files/generateProjectConfig.js`）在编译写入 `dist/project.config.json` 时，会自动将 `miniprogramRoot` 重写为 `"./"`。
3. 这样保证了双重兼容：
   * 开发者工具若打开 `miniprogram/`，读到 `"dist/"`，正确指向 `dist/app.json`；
   * 开发者工具若直接打开 `miniprogram/dist/`，读到 `"./"`，同样正确指向 `./app.json`。

---

## 踩坑二：小程序启动空白、无法连服务器（“服务地址尚未配置”）

### 1. 现象回放
* 单测 994 项 100% 全部通过，类型检查 0 错误；
* 但在真机或模拟器打开小程序时，界面没有任何数据，页面报错或提示请求失败。

### 2. 底层机制与根因
* 在 [miniprogram/config/index.ts](file:///E:/project/tesla_master/miniprogram_Weixin/miniprogram/config/index.ts) 中：
  ```ts
  const apiBaseUrl = process.env.TARO_APP_API_BASE_URL ?? ''
  defineConstants: {
    'process.env.TARO_APP_API_BASE_URL': JSON.stringify(apiBaseUrl),
  }
  ```
* Taro 是通过 Webpack `defineConstants` 在**编译期**进行字符串字面量替换的。微信小程序运行时根本没有 Node.js 的 `process.env` 动态环境。
* 当本地或脚本直接运行 `taro build` 而没有注入系统环境变量时，`apiBaseUrl` 直接被静态编译为 `""`（空字符串）。
* 当小程序启动并调用 [src/services/api.ts](file:///E:/project/tesla_master/miniprogram_Weixin/miniprogram/src/services/api.ts) 中的 `getApiOrigin()` 时，`requireApiBaseUrl()` 校验空字符串失败，直接抛出 `ApiConfigurationError`（“服务地址尚未配置”）。
* **后果**：所有页面在发起第一个网络请求前就被全局前置防御拦截，连不上云端，呈现空白态。

### 3. 经验沉淀与标准解法
1. **绝不裸奔环境变量**：在 `config/index.ts` 中必须提供生产默认兜底：
   ```ts
   const apiBaseUrl = process.env.TARO_APP_API_BASE_URL || 'https://api.teslalink.joviluma.com/'
   ```
2. **本地环境文件留存**：在 `miniprogram/.env` 中固化 `TARO_APP_API_BASE_URL=https://api.teslalink.joviluma.com/`，并确保其被 `.gitignore` 保护。
3. **打包后产物静态抽检**：构建完成后，不能看 Webpack 成功就完事，必须脚本抽检 `dist/common.js` 中是否包含目标 URL。

---

## 踩坑三：生产后端架构与自托管 502 认知误区

### 1. 现象回放
* 看到 `https://teslalink.joviluma.com` 报 `502 Bad Gateway`，容易误以为生产后端服务崩溃。

### 2. 底层机制与架构事实
根据《架构决策路线三（Tesla Fleet 多租户 + 本地数据）》：
* **生产服务器架构**：
  * Nginx 监听 443 端口，按域名精确路由：
  * `api.teslalink.joviluma.com` → 反向代理至 `127.0.0.1:18090`（云 API 服务，当前**运行中，健康状态 200**）。
  * `teslalink.joviluma.com` → 反向代理至 `127.0.0.1:18080`（历史自托管 Teslamate Adapter）。
* **502 的真相**：
  自托管 Adapter 容器已经主动停用，未运行任何容器，因此 18080 端口拒绝连接，Nginx 报 502。这是**架构停用的预期表现**，绝不是生产故障！

### 3. 避坑原则
1. 小程序与手机客户端通信的**唯一生产出口**是 `https://api.teslalink.joviluma.com/`。
2. 严禁将 502 误诊为服务器故障，更严禁去动生产服务器的 Nginx 配置把 18080 改成 18090 或改动无关容器。
3. 严禁使用已弃用的 `api.jourvolt.com`、局域网 `192.168.0.104:18080` 或本地假 IP `198.18.x.x`。

---

## 踩坑四：“单测跑通等于完事”的管理认知偏差与自动化截屏验收

### 1. 现象回放
* 逻辑单测跑通后，直接点击上传代码，就告诉用户“请在手机上扫码测试”。
* 结果用户扫码后发现小程序黑屏或无法使用，极大破坏了工程信任。

### 2. 开发者工具 CLI / Automator 赋能
微信开发者工具提供了完整的命令行与自动化工具，可在无人工干预下完成真实环境巡检：
* **Token 获取**：本地安全 Token 存放在 `C:\Users\Admin\AppData\Local\微信开发者工具\User Data\6415b9ce39988491068e4d787211d913\Default\mcp.json` 中。
* **模拟器启动**：`cli.bat open --project <project>`
* **模拟器页面跳转**：`wechatide.cmd -c cli simulator_open_page --project <project> --page <page_path> --token <token>`
* **模拟器截屏存证**：`wechatide.cmd -c cli simulator_screenshot --project <project> --token <token> --path <output_path>`
* **Console 日志抽检**：`wechatide.cmd -c cli get_simulator_console --project <project> --command "grep -i error" --token <token>`

### 3. 交付铁律
* **必须眼见为实**：Agent 或开发者必须在本地通过 `simulator_screenshot` 生成各页面实景图，确认页面渲染正常、无未捕获异常后，方可向 Jovi 交付。
