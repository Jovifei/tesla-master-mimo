# MateLink 微信小程序人工申请前清单

日期：2026-09-12

工程分支：`feature/wechat-miniprogram`

工程提交：`225fc4d834f94dd93ac3b649d985d53c942fe916`

本清单只覆盖人工申请/审核前可以由本地 Codex 准备的材料。微信主体注册、AppID 申请、类目选择、实名/企业资料、平台勾选和最终提交必须由项目负责人在官方后台完成。

## 已完成的技术准备

- Taro 4.2.1 + React 18.3.1 + TypeScript 5.7.3 + Webpack 5 已锁定。
- `npm ci --dry-run --ignore-scripts`、`npm run typecheck`、Vitest 6 项测试、`npm run build:weapp` 已通过。
- 四个 Tab 页面、车辆/状态/readiness API 适配、真实值/缺失值/质量边界和安全 README 已提交。
- `project.config.json` 保留空 AppID；未写入 AppSecret、微信 `session_key`、Tesla token、私钥、VIN 或精确位置。
- M0、框架 ADR 和 M1 接口契约已记录：`M0_FEASIBILITY_20260911.md`、`ADR-0001-client-framework.md`、`INTERFACE-CONTRACT-M1.md`。

## 需要 Jovi 人工完成

### 微信平台账号

- 注册或确认小程序主体（个人/企业/组织）和实名/认证状态。
- 创建小程序并取得正式 AppID；确认首版类目、名称、简介、服务内容和联系人资料。
- 在微信后台配置 MateLink API 的 HTTPS request 业务域名；如使用导出或远程图片，再分别配置 download/upload 域名。
- 确认是否具备 `web-view`、地图、隐私协议和相应类目能力；不要在开发工具中开启“跳过域名校验”后直接提交。
- 将正式隐私政策、运营主体、联系方式、账号注销/解绑和数据导出说明填入平台资料，并与 MateLink 实际云端历史保留规则一致。

### Tesla 授权可行性

- 用真实 AppID 和正式微信环境验证安卓微信、苹果微信的首次流程：微信会话 → 绑定事务 → Tesla 官方 OAuth → 回流 → 虚拟钥匙确认（如车辆需要）→ 车辆选择。
- 验证取消、超时、切后台、进程重建、授权撤销、账号冲突和重新授权；确认不会以 openid、VIN、车辆名或邮箱直接继承旧账号历史。
- 验证 Tesla 官方请求的 `openid`、`offline_access`、`vehicle_device_data`、`vehicle_location` 范围，以及 `config_synced`、首个 MQTT 的后端状态。

## 获得 AppID 后的本地动作

1. 在本地私有环境填入 AppID 和已核验的 HTTPS API 地址；不提交 AppSecret 或任何 token。
2. 重新执行 `npm ci`、`npm run typecheck`、`npm test`、`npm run build:weapp`。
3. 将 `dist/` 导入微信开发者工具，关闭域名跳过选项，确认四个 Tab 和空数据提示正常。
4. 只在两平台人工授权链通过后，才进入 M2 微信会话和 Tesla 绑定接口开发。

## 当前不能提交的理由

`AUTH_FEASIBILITY=UNVERIFIED`。正式 AppID/主体/域名、微信后台能力、Tesla OAuth/虚拟钥匙在两平台真机上的可行性，以及后端微信会话/账号关联接口都没有证据。M1 开发态构建通过不等于小程序已注册、可预览、可发布或已通过审核。
