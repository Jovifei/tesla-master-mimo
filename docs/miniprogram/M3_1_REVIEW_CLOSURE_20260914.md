# 微信小程序 M3.1 复审缺陷收口

日期：2026-09-14  
分支：`feature/wechat-miniprogram`  
M3.1 版本：`0.2.2`  
实施基线：`bf6425d846d7bc1850892f64af20dbda3df9c3c4`  
代码验证 SHA：`4a111c9`（文档提交前的最后代码/门禁提交）；最终分支 SHA 在交付报告中记录。

## 收口范围

本轮只修复独立复审发现的 M3 缺陷，没有重写后端、改变真实数据来源、删除旧云端/手机历史或把小程序授权回流改成 Android intent。

- 授权事务：claim、cancel、logout 和页面卸载均绑定 API origin、canonical user、会话代次和 transaction proof；claim single-flight，旧成功/失败/finally 不能覆盖新账号或新事务。服务端补齐 pending、ready、failed、cancelled、claimed、expired 终态，短期 ticket 过期后回查失败；grant、微信关联、ticket、ready artifact 和 claim 状态分别在 PostgreSQL 事务中原子提交。
- 微信回流：小程序只接受当前 proof 对应的 callback reference；授权页不读取 URL ticket 或调用裸 ticket 兑换。原生 `/v1/auth/exchange` 保留为 Android 兼容通道，并与微信页面代码隔离。微信桥接使用 `wx.miniProgram.postMessage` 和手动返回，不生成 Android `intent://`。
- 历史分页：`validPrefix`、`complete`、`hasMore` 和错误原因分开；合法中间页继续加载，页码/重复页/空页带 hasMore/总页数矛盾不推进成功水位，保留已有真实记录并允许从正确页重试。
- 历史缓存：长期键只使用 schema、API origin、canonical user、stable vehicle 和类型；会话代次只用于在途请求失效。旧 v2 代次键严格限定同一服务、账号和车辆后迁移，旧物理行保留；写入失败只显示提示，不丢在线结果。
- 运行时：授权 URL 使用不依赖 `URL` 的精确 HTTPS origin/路径解析；业务代码不依赖 `URLSearchParams`。构建产物扫描不包含 API 动态环境表达式、AppSecret、session key、token、私钥或密码。

## 仍然是外部门禁

PostgreSQL 集成测试需要独立测试 DSN；当前工作站没有可用隔离 PostgreSQL，因此 15 个 Go 集成测试事件为 SKIP，不能标记 PG PASS。真实 AppID、微信 request/web-view 域名、安卓微信、iOS 微信、Tesla 官方同意、虚拟钥匙、`config_synced=true`、首个真实 MQTT、真实行程/充电与换机恢复仍未验证。`npm audit` 仍有生产树 3 critical、8 moderate、1 low，发布门禁保持阻断；没有用升级破坏 Taro 的方式伪造通过。

## 交付边界

本文件证明源码整改和本地可离线验证已完成，不证明平台审核、真实账号闭环、生产部署或上线。只有 PostgreSQL 必需用例 0 skip、依赖风险有明确处置、两平台微信/Tesla/MQTT/历史证据齐全后，才可把 `READY_FOR_PLATFORM_TEST` 或 `READY_FOR_RELEASE` 改为 PASS。
