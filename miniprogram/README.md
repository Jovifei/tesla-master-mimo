# MateLink 微信小程序（0.3.0，数据与能耗阶段）

这是 MateLink 的微信小程序客户端。它复用既有 MateLink Go API 的业务语义，不复制 Android Compose、Room 或 Web DOM，也不直接访问 Tesla Fleet API、MQTT、mTLS 或服务器私钥。

## 当前边界

- 当前版本延续既有四 Tab：Taro 4.2.1 + React 18.3.1 + TypeScript 5.7.3。项目配置已包含 Jovi 提供的 AppID；主体、类目、业务/request 域名、真实微信登录和 Tesla 授权仍须取得平台证据。
- 当前测试工具独立锁定在 `test-tooling`，避免 Taro 可选 Vite 4 编译器与现代 Vitest/Vite 的依赖冲突；实际小程序编译器仍为 Webpack 5。
- 页面使用服务端微信会话、Tesla OAuth 和车辆接口；没有配置 `TARO_APP_API_BASE_URL` 时会明确失败，不回退到 mock 数据。
- 后端已提供 `POST /v1/auth/wechat/session`：服务端用 AppID/AppSecret 换取微信 openid；首次微信身份只得到短期关联凭证，必须再完成 Tesla 官方授权，不能用 openid、VIN、车辆名或客户端 user id 自动认领旧账号。
- Tesla 关联在小程序 `web-view` 中经过自有 `/oauth/wechat/authorize` 桥接页回流；正式环境必须把授权域名配置为微信 `web-view` 业务域名，并允许 MateLink API request 域名。
- Tesla 密码、Tesla refresh token、微信 `session_key`、AppSecret、私钥、明文 VIN 和精确位置不得进入小程序代码、缓存或日志。

## 本地命令

```powershell
npm ci --ignore-scripts
npm ci --prefix test-tooling --ignore-scripts
npm run typecheck
npm test
npm run build:weapp
```

开发工具请关闭“跳过域名校验”后再做发布态验证。没有真实 AppID、主体和两平台微信真机结果时，`AUTH_FEASIBILITY` 仍为 `UNVERIFIED`。

## 页面与数据规则

四个 Tab 为车辆、行程、充电、我的。车辆页保留数值 0、布尔 false 和负功率；状态报告时间不能证明每个字段的更新时间。在线有效坐标可显示，缓存不保存位置。服务器配置就绪与用户确认车辆钥匙分别显示。

行程卡展示耗时、km、净 kWh 和 Wh/km（kWh × 1000 / km），负净值保留并用绿色标示。有效 API 合同、完整功率积分、无充电污染的 nominal 端点以及明确容量的 SOC 估算遵循 Android 同样的资格规则；估算明确标示，缺失或无效输入不填 0。充电 AC 输入与电池输入分别验证，费用保留实际币种，未知币种不默认人民币。

历史页支持日期/距离筛选、刷新、分页、详情返回和本地重开。详情写回同作用域列表及缓存；同输入弱摘要保留详情，明确 unknown 撤销旧能量。缓存按 HTTPS origin、微信账号与稳定车辆 UID 隔离，并限制条数和字节；轨迹数组不缓存，配额失败不阻断在线阅读。离线车单只能阅读缓存；再次真实验证车辆列表前不能凭旧 numeric ID 发在线请求。账号/换车/隐藏页面后，迟到响应不得写回当前界面或缓存。

## 服务端配置

在运行 Go API 的服务器环境设置以下变量，AppSecret 只留在服务器：

```text
WECHAT_APP_ID=<正式小程序 AppID>
WECHAT_APP_SECRET=<正式小程序 AppSecret>
WECHAT_SESSION_URL=https://api.weixin.qq.com/sns/jscode2session
```

`WECHAT_APP_ID` 与 `WECHAT_APP_SECRET` 必须同时存在；缺少任一项时接口 fail-closed。微信 `session_key` 只在服务端内存中短暂使用，不写入数据库、日志或小程序缓存。

## 当前放行边界

缓存组件中的句点会严格转义。旧未转义的歧义键保持原样，但不读入新scope、不自动迁移或猜测删除；在线数据可以建立新的无歧义缓存。这不会清理已有账号历史。坐标剥离也作用于旧缓存的读取投影；隔离记录不进入可信行程/充电列表、计数或详情。

项目级数据合同和发布门见 [`docs/`](../docs/README.md)。旧阶段固定测试报告已归档；以当前小程序分支的实际提交与同 SHA CI 核对。代码测试与编译不等于微信开发工具、真实微信安卓/iOS、Tesla 官方授权、虚拟钥匙、首个自然 MQTT 事件或发布通过。

构建依赖审计仍须记录未消除的工具链告警；不使用 `npm audit fix --force`、远程模板/插件下载、H5 公网开发服务器或不可信压缩包解包来规避。当前只编译受控本地源码及隔离测试，不自动上传或发布。开发工具 `urlCheck` 保持 true；本机官方 CLI 初始化失败需由 Jovi 在工具 GUI 登录/导入工程，不能降低 TLS/域名校验或改系统网络来绕过。
