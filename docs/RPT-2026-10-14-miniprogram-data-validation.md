# 小程序数据阶段执行与验收

原 E:/project/tesla_master/app_mimo；codex/miniprogram-stage-20261013；基线 main 44bfb8baaa4528078ffb88a4f3ff622b570a6469。本文与小程序源码一起提交；完整 SHA/tree/parent 以 Git 与同 SHA CI 的 source.txt 为准。

## 完成内容

- 延续 Taro 4.2.1 四 Tab，版本 0.3.0；Android/Go 产品源码相对 44b 未改。
- 行程列表/详情显示耗时、km、净 kWh、Wh/km、SOC 和估算标识；0、负值保留，缺失不是 0。日期/距离筛选、刷新、分页、详情返回和重开一致。
- 移植 Android 能耗资格：API 合同、功率梯形积分、无充电污染的 nominal 端点、明确容量的 SOC 估算；积分仍是估算。同等级容量保留先到依据。
- 详情写回同 origin/account/stableUID 列表/cache；弱摘要保留合格能量，明确 unknown 撤销；输入/source/ID/精确窗口改变不得借用旧证明。充电电池/AC 输入独立验证，费用保留实际币种。
- 真实 getCars 验证 numeric ID→UID；缓存车单不授权远端请求。账号/车辆/页面生命周期的迟到响应不写回当前页面。状态保留 0/false/负功率，报告时间不冒充逐字段观测时间；缓存不保存坐标/轨迹。
- HTTPS authority、授权 URL 白名单、关联事务 proof、取消/logout/ABA 保留；服务端错误正文和任意状态不直出 UI。车辆钥匙确认与服务端配置分开，不代用户授权。
- 历史缓存最多 500 行/800 KiB，真实 UTF-8 限额，v2/v3 同scope兼容；配额失败不阻断在线阅读、不清其他账号数据。
- CI 新增小程序类型/页面测试/weapp，保留 Android、Go/隔离 PG/race/TLS/Web 和 10 项源审查。

## 实际本地主机证据

| 验证 | 结果 |
| --- | --- |
| 独立两份锁干净 npm ci，禁止生命周期脚本 | PASS，无 force/legacy-peer |
| 严格 TypeScript | exit 0 |
| Vitest | 128/128，17 文件，0 失败/跳过 |
| 实际页面→API adapter→控制器/storage→模拟 Taro | 历史 7、车辆 6 场景通过 |
| 实际微信编译 | SUCCESS，4.41 秒，urlCheck true |
| 编译包 | 约 505 KB，不是微信真机验收 |
| Owner 备份复核 | 570 个非本阶段文件无意外漂移 |

实际场景覆盖负能耗返回/刷新/重开、unknown 撤销、充电 5/0 的 absent/null 弱摘要、延迟关闭/换车/换账号、离线禁止 numeric 请求、配额失败与非法 origin；不替换 controller/API/能耗实现。

原始 XML、构建、审计、冻结哈希在 E:/Claude_allow/Download/matelink-energy-final-20261013。手机私密截图/日志/现装包只留本地，不上传聊天或仓库。固定提交后的 CI 与 GPT-6 极高完整源复审仍待独立核验，不能提前 PASS。

## 依赖安全限制

Taro 的可选 Vite 4 peer 与现代测试工具冲突，已将 Vitest4.1.11/Vite7.3.7 放入独立 test-tooling 包/锁；编译仍用 Webpack5。两包实际干净安装，类型/测试/weapp 通过；不宣称支持 Taro Vite 编译器。

最新 audit：测试工具 0 告警；Taro 构建树仍 58 个传播包告警（1 critical、28 high、29 moderate；17 个直接 advisory 包），保留完整 JSON。原基线72是传播包数量，不是72个独立缺陷。Vite、adm-zip、proxy-addr、shell-quote 等兼容修复已锁；不 force 降级。

decompress/模板 git-clone/解包、H5 dev-server、旧 Webpack/CSS 等剩余告警仍是发布债务。当前仅编译受控本地源码及隔离测试，不 taro init/远程模板/不可信插件、不解包不可信压缩包、不启动公网 H5/Vite 服务器；编译成功不能代表工具链零漏洞或正式发布通过。上游证据：[Vite advisory](https://github.com/advisories/GHSA-p9ff-h696-f583)、[decompress advisory](https://github.com/advisories/GHSA-mp2f-45pm-3cg9)。

## 平台与下一步

App44b 已 SOURCE_APPROVED、同 SHA CI13/13、正式同签49保数据安装/启动、main快进验证。手机历史自动输入未切换成功，真实列表/详情/离线与自然Fleet保持OPEN，不标手机全验收。

官方微信工具存在、AppID配置与Jovi提供值一致；CLI两次初始化失败，内部错误不能用exit0掩盖。不改系统网络或降低TLS/域名校验规避。忽略的本机.env配置API origin；CI未配置API时明确失败，不回退mock。

完整源复审及固定SHA CI后，请Jovi在开发工具GUI登录/导入 miniprogram/dist、保持域名校验，补微信会话/请求域名/Tesla关联与真实平台交互。扫码、条款/隐私同意、Tesla车辆确认、生产配置/迁移、车辆操作、上传/发布未代办。本轮交付受控源码/构建阶段，未上线。
