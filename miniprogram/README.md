# MateLink 微信小程序（0.2.0，M2 账号与数据链路）

这是 MateLink 的微信小程序客户端。它复用既有 MateLink Go API 的业务语义，不复制 Android Compose、Room 或 Web DOM，也不直接访问 Tesla Fleet API、MQTT、mTLS 或服务器私钥。

## 当前边界

- 当前版本是 M2：Taro 4.2.1 + React 18.3.1 + TypeScript 5.7.3，版本已锁定在 `package.json`，安装后提交 `package-lock.json`。
- `project.config.json` 的 AppID 仍留空；主体、AppID、类目、业务域名、request 域名、Tesla OAuth/虚拟钥匙仍需取得真实平台证据。
- 页面使用服务端微信会话、Tesla OAuth 和车辆接口；没有配置 `TARO_APP_API_BASE_URL` 时会明确失败，不回退到 mock 数据。
- 后端已提供 `POST /v1/auth/wechat/session`：服务端用 AppID/AppSecret 换取微信 openid；首次微信身份只得到短期关联凭证，必须再完成 Tesla 官方授权，不能用 openid、VIN、车辆名或客户端 user id 自动认领旧账号。
- Tesla 关联在小程序 `web-view` 中经过自有 `/oauth/wechat/authorize` 桥接页回流；正式环境必须把授权域名配置为微信 `web-view` 业务域名，并允许 MateLink API request 域名。
- Tesla 密码、Tesla refresh token、微信 `session_key`、AppSecret、私钥、明文 VIN 和精确位置不得进入小程序代码、缓存或日志。

## 本地命令

```powershell
npm install
npm run typecheck
npm test
npm run build:weapp
```

开发工具请关闭“跳过域名校验”后再做发布态验证。没有真实 AppID、主体和两平台微信真机结果时，`AUTH_FEASIBILITY` 仍为 `UNVERIFIED`。

## 页面与数据规则

四个 Tab 为车辆、行程、充电、我的。车辆状态、数据准备、来源、更新时间和缺失原因优先于装饰性数据。车辆页读取 `key=telemetry`，不会把 `live_status` 当作持续采集就绪；它显示车型、状态、位置、来源和观测时间，并保留授权/配对/配置重试入口。行程/充电页支持分页、详情、当前充电和本地历史缓存，按微信用户与稳定车辆隔离；云端空响应不能清除合法本地缓存；不完整摘要不参与要求完整证据的分析。

## 服务端配置

在运行 Go API 的服务器环境设置以下变量，AppSecret 只留在服务器：

```text
WECHAT_APP_ID=<正式小程序 AppID>
WECHAT_APP_SECRET=<正式小程序 AppSecret>
WECHAT_SESSION_URL=https://api.weixin.qq.com/sns/jscode2session
```

`WECHAT_APP_ID` 与 `WECHAT_APP_SECRET` 必须同时存在；缺少任一项时接口 fail-closed。微信 `session_key` 只在服务端内存中短暂使用，不写入数据库、日志或小程序缓存。

## 当前放行边界

本地 typecheck、Vitest 和 `build:weapp` 已通过。正式 AppID、微信业务域名、真实微信安卓/ iOS、Tesla 官方授权、虚拟钥匙、首个 MQTT 事件和发布审核尚未验证，因此当前版本可以交给 Jovi 做平台配置与开发工具联调，不能宣称已上线或真实车辆链路已通过。
