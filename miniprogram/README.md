# MateLink 微信小程序（M1 隔离骨架）

这是 MateLink 的新增微信小程序客户端骨架，来源于 2026-09-11 交接任务。它复用既有 MateLink Go API 的业务语义，不复制 Android Compose、Room 或 Web DOM，也不直接访问 Tesla Fleet API、MQTT、mTLS 或服务器私钥。

## 当前边界

- 当前阶段是 M1：Taro 4.2.1 + React 18.3.1 + TypeScript 5.7.3，版本已锁定在 `package.json`，安装后提交 `package-lock.json`。
- `project.config.json` 的 AppID 留空；主体、AppID、类目、业务域名、request 域名、Tesla OAuth/虚拟钥匙在 M0 尚未取得真实平台证据。
- 页面只显示真实数据边界和等待状态；没有配置 `TARO_APP_API_BASE_URL` 时会明确失败，不回退到 mock 数据。
- `/v1/auth/wechat/session` 和 `/v1/account-links/tesla/transactions` 是交接中提出的新增接口，当前后端尚未实现；本骨架不会用 openid、VIN、车辆名或客户端 user id 自动认领旧账号。
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

四个 Tab 为车辆、行程、充电、我的。车辆状态、数据准备、来源、更新时间和缺失原因优先于装饰性数据。行程/充电分页与历史合并必须按账号、稳定车辆和规范会话身份隔离；云端空响应不能清除合法本地缓存；不完整摘要不参与要求完整证据的分析。
