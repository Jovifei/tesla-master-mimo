# JourVolt P0 外部门禁执行单

状态：`BLOCKED`（仓库内准备已完成；外部账号、备案和官方授权尚未完成）

本文件只记录启用真实 Tesla Pilot 所需的外部前置条件。独立 Go OAuth/Fleet 代码已经配置就绪；未通过 P0 前继续 fail closed，不把旧 TeslaMate adapter 迁移为公开版 OAuth 后端，也不在仓库中放置任何真实密钥。

## Jovi 需要执行的事项

### 1. 品牌和域名

- [ ] 查询并记录 `JourVolt` 在中国大陆第 9、42 类的商标冲突情况。
- [ ] 注册并控制 `jourvolt.com`；如预算允许同时注册 `jourvolt.cn`。
- [ ] 确认最终品牌仍使用 `JourVolt`，或在代码发布前给出替代名称。

交付给开发侧的信息只需要域名和查询结果，不要发送域名账号密码。

### 2. 单车 Pilot 公网 HTTPS 入口

App 优先阶段不要求先购买生产服务器。Go API 与 PostgreSQL 可以继续运行在本机 Docker，但 Tesla 回调、公钥和 Android App Link 必须能通过受控域名从公网 HTTPS 访问。Pilot 可二选一：

- 本机 Docker + 稳定的受控域名 HTTPS 安全入口；不得使用每次变化的临时回调域名。
- 最小云端测试入口；如购买中国大陆轻量云服务器，仍遵守 `网站服务器部署.md` 的 2 核 4 GB 与年度成本门禁。

本机入口只用于受邀单车 Pilot，不作为中国大陆公开生产部署。正式公开前仍需完成境内服务器、备案、备份和安全加固。交付给开发侧只需说明入口方式、公开回调地址和 DNS 是否可控；不要向聊天或 Git 提供私钥、密码或控制台 Cookie。

### 3. ICP 和 App 备案路径

- [ ] 在阿里云控制台确认 `jourvolt.com` 的 ICP 备案主体和材料要求。
- [ ] 确认 Android App 备案入口、应用名称、正式包名 `com.matelink` 和开发者主体；`com.matelink.test.mock` 仅为本地测试包。
- [ ] 记录备案受理号/状态，不把身份证、营业执照或验证码提交到仓库。
- [ ] 在备案完成前不进行面向公众的正式运营；邀请测试也只限于已确认的合规范围。

### 4. Tesla 官方 Fleet API

- [ ] 以 `JourVolt` 名义提交 Tesla 中国开发者应用审核。
- [ ] 申请首版只读 scope：`openid`、`offline_access`、`vehicle_device_data`。
- [ ] 暂不申请 `user_data`、`vehicle_cmds`、`vehicle_charging_cmds`。
- [ ] 地图功能稳定后，再单独评估 `vehicle_location` 和虚拟钥匙/Telemetry 审核。
- [ ] 明确确认 Tesla 是否接受个人主体向受邀车主提供第三方应用；若不接受，暂停公开版并转公司或合作主体。
- [ ] 在受控域名托管 Tesla 要求的公钥并完成伙伴注册。
- [ ] 为 `com.matelink` 的正式签名托管 Android `assetlinks.json`，使 OAuth ticket 只能回到已验证 App Link；测试包不作为正式回调目标。

官方入口和规则：

- [Fleet API 入门](https://developer.tesla.cn/docs/fleet-api/getting-started/what-is-fleet-api)
- [OAuth scope](https://developer.tesla.cn/docs/fleet-api/authentication/overview)
- [第三方令牌](https://developer.tesla.cn/docs/fleet-api/authentication/third-party-tokens)

交付给开发侧只需要：审核结果、允许的 scope、回调域名要求和应用状态。`client_secret`、私钥和 refresh token 不要发送，也不要提交 Git。

仓库已提供 [真实 Pilot 预检说明](JOURVOLT-REAL-PILOT-PREFLIGHT.md) 与 `deploy/jourvolt-dev-mock/preflight.ps1`。预检只检查变量名、HTTPS/域名、Mock 开关、32 字节密钥长度和可选 App Link，不输出密钥，也不会自动部署。

### 5. 地图服务（P3 前置，不阻塞 P1）

- [ ] 以正式包名 `com.matelink` 和正式签名申请高德 Android Key。
- [ ] 确认高德隐私同意接口和 SDK 版本要求。
- [ ] 未取得 Key 前，公开版保持无地图或地图禁用状态。

## 6. 服务器采购核对（不自动下单）

当前仓库只完成本机 Docker 调试和真实 Pilot 的 fail-closed 预检；服务器采购仍需要 Jovi 在控制台确认最终价格后单独授权。

既定成本门禁：首年服务器实际价格≤¥600，正常续费≤¥700/年；服务器、两个域名和境内异地备份合计≤¥850/年。官方价格核对结果：

- 腾讯云大陆轻量应用服务器官方价格表列出的 2C4G、60GB、5Mbps、500GB/月约 ¥65/月，即约 ¥780/年。技术规格合适，但标价不满足当前成本门禁，只有结算页优惠价和续费价同时满足时才可采购。[腾讯云轻量应用服务器价格](https://cloud.tencent.com/document/product/1207/73452/)
- 阿里云官方产品页确认大陆 Linux 轻量实例可用，但本轮没有把搜索结果当作固定报价；必须在结算页同时核对首年、续费和备案购买时长。[阿里云轻量应用服务器](https://cn.aliyun.com/product/swas)、[阿里云续费说明](https://help.aliyun.com/zh/simple-application-server/product-overview/upgrade-and-renew-a-simple-application-server)
- Oracle Always Free 可用于开发实验，但不作为真实 Pilot 主入口，原因是官方列出的容量、区域、信用卡验证、无 SLA/支持等限制。[Oracle Cloud Free Tier](https://www.oracle.com/cn/cloud/free/)

服务器选择不绕过 Tesla 应用审核、域名控制、公开 HTTPS callback、App Link、ICP/App 备案或真实车辆授权。未满足上述门禁前，继续使用本机 Docker Mock。

## P0 通过条件

全部满足后，Jovi 只需回复以下非敏感摘要：

```text
品牌：JourVolt / 其他
正式域名：example.com
Pilot HTTPS 入口：本机安全入口 / 测试云端；公网地址
ICP/App 备案：已受理 / 已完成 / 不适用但已确认
Tesla 审核：通过 / 拒绝 / 待审；允许 scope
回调地址：可使用的 HTTPS 域名路径
App Link：已验证的 HTTPS 域名路径
```

不需要回复任何密码、Token、私钥、身份证件内容或 Tesla 账号信息。P0 通过后只需把密钥写入本机私密配置，启用现有代码并进入单车真实车辆验收。
