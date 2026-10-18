# Tesla 主登录与 App 数据合同修正

Jovi 要求小程序以已可用 App 为准，主登录是特斯拉账号。此记录取代此前将微信身份作为主登录前置的描述。原 App/生产未修改；未代用户勾选协议或使用真实账户。

## 已实现

- 更多页主按钮为“特斯拉账号登录”；直接开始 Tesla 官方授权，不调用 wx.login，不要求微信 openid/绑定。无协议勾选时按钮禁用。原 App 的无 channel start/callback/ticket exchange 保留。
- Mini 使用原 GET `/v1/auth/tesla/start` 加 `X-MiniProgram-Channel: wechat`。新增 `/v1/auth/miniprogram/status|claim|cancel`，以独立 transaction/client proof 回流；proof 哈希存储，Mini 使用 PKCE S256，保持 OAuth state、OIDC nonce 分离和校验。同 verified Tesla subject 共用 App 的用户映射；邮箱/VIN/显示名不参与认领。
- 回调页面不输出 ticket/token/proof。WebView 消息和路由参数仅提示重新查询服务端状态，ready 后才原子领取会话；页面/账号/origin/事务代际变化后拒绝迟到结果。微信无法打开官方页面时可复制官方入口到浏览器完成，再回小程序检查。
- 独立复审发现并修复注销竞态：注销前严格取消当前未完成授权，未知/网络/401 取消失败时保留账户，不发 DELETE。注销期间阻止新 start/claim/exchange；旧回调不能在注销后重建用户或凭据。
- 充电效率遵循 App qualifiedAcEnergyBalance：原始 charge_type=ac、同来源/共同非空 time_basis、精确观测起止窗口及合格计数器；缺证据显示未知，单项能量和真实零值保留。缓存合并不能把旧 AC 模式嫁接到新缺失证据。
- 首页 snapshot 前台轮询按 App 驾驶8s/充电15s/休眠60s/其它10s串行执行；隐藏/卸载/换车/换账号隔离旧结果。独立时钟让实时徽标自然过期，轮询不重载车图。

## 当前验证

| 层次 | 结果与边界 |
| --- | --- |
| Mini 单元/消费者 | 49 files / 661 tests PASS；另6项Node资源/原生组件测试PASS |
| 类型/构建 | TypeScript PASS；weapp构建PASS；97张原App PNG哈希一致；190编译文件，共11,302,830 bytes |
| 后端 | 真实PostgreSQL16：418 PASS / 0 FAIL / 0 SKIP；go vet PASS；Windows未运行Go -race |
| 并发/注销 | claim/cancel竞争、取消先赢不更新原App grant、cancel→DELETE→旧callback、provider在途cancel→DELETE→释放callback均已实际运行 |
| 独立复审 | 原P2修复后未发现新增明确P1/P2；不等于生产或实账号验收 |
| 微信原生入口 | 实际DevTools截图/元素确认“特斯拉账号登录”、disabled=true、无session，两项协议未勾选；黑色焕新版ModelY预览正常。首次automator连接超时后读取console无error匹配，目的导航随后正常 |
| 原生回流/实车/手机 | NOT_VERIFIED / NOT_RUN；Jovi选择暂不登录，未代同意协议 |
| 原App保护 | 1870个基线文件重新计算哈希，0变化、0缺失；本轮无法验证其Git HEAD |
| Git/线上 | 当前目录没有.git，未重建；新源码SHA/完整CI/PR更新不可确认。新后端接口未部署、无生产迁移/上传/发布/合并 |

## 证据与候选

证据目录：`E:/Claude_allow/Download/matelink-mini-app-exact-20261016`。

- `mini-tesla-first-full-20261018.log`、`go-tesla-first-delete-json-20261018.log`、`build-tesla-first-20261018.log`
- `native-tesla-first-entry.json/.png`、`tesla-first-native-initial.png`
- `owner-tesla-first-recheck-20261018.json`
- `tesla-first-local-source-manifest-20261018.json`：451源码文件内容摘要 `c81a9aae5e25ade3ceb6f37e0cf9b189a1d5b880dc99db421d316b3f79719d02`，这是内容哈希，不是Git SHA。
- `weapp-tesla-first-local-20261018.zip` SHA256 `a17d056b0d2b6efd6fd469491cd405967e269ddb804bf47df7b6f4923d824952`。编译内容摘要 `f9f978ebcfb9f6b3e10a528f9938c9014c3ef6979ba2c137922c495187c7d0eb`。
- 原生隔离副本与编译包仅project.config.json不同（项目名/基础库3.17.3）；其余189文件字节相同，未复制登录存储。

此候选只代表本轮登录和两项数据规则修复。完整App矩阵中的其它FIX_REQUIRED及真实平台验收缺口仍保留；不能声称全部页面已与App完全一致。下一步需恢复Git交付环境、取得固定SHA/CI，独立部署审核后的新后端合同，再由Jovi本人选择真实Tesla登录联调。
