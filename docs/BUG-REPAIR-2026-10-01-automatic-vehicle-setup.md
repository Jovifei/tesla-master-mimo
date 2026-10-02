# 登录后自动车辆接入

## 已核验根因

- Command Proxy 原先只有 internal Docker 网络，访问 Tesla 返回 502；同一凭据直接访问官方读取接口为 200。添加专用出口网络后代理读取恢复 200，未开放代理端口。
- HTTP helper 在返回响应头时取消上下文，慢响应正文出现 context canceled。回归测试先失败，修复为关闭正文时取消后通过；修复提交 c1a44d7 已部署。
- 官方配置读取曾返回 synced=true、config=null。现在必须核验目标主机、端口和采集字段，空配置不能显示配置完成。
- 出口恢复后官方 fleet_status 明确返回目标车辆未配钥匙，签名配置返回 pairing_required。公钥和实际代理私钥指纹一致。此前 502 不能用来判断授权缺失。
- 真机原先选择自托管模式。切回云端后导航层仍缓存启动模式，自动接入未即时触发；改为订阅已保存的模式变化，不要求重启/重新登录。

## 自动流程

1. App 登录、车辆切换和回到前台检查当前账号拥有的车辆，按账号/车辆隔离结果。
2. 服务端每分钟检查未完成接入，沿用已有配置锁、令牌续期和失败分类；客户端只观察，不重复提交配置。
3. 仅明确缺权限/钥匙时显示官方入口；稍后不反复打断首页，不删除登录或历史。
   未带检查时间的默认 pairing_required 先作为自动检查状态，不能直接提示车主授权；新增断言在旧实现上实际失败。
4. 车主在 Tesla 确认后，App 返回检查；即使手机关闭，服务端也检查对应车辆的官方钥匙状态并继续配置。

## 验证和交付边界

- Go 完整测试（隔离 PostgreSQL）和 go vet 通过；包含正文生命周期、配置身份与钥匙归属/节流测试。
- Android Debug/Release 各 561 项，零失败/错误（Release 跳过 8）；Lint 无错误；初次签名构建通过。最终源码提交后重建并覆盖安装，真机证据待补记。
- 服务端提交 781c402 已推送并部署，readyz 核验相同 build_sha，fleet/postgres/ok；代理出口已固化。
- Fleet 真实首事件、真实行程/充电与七天观察仍待验收。TeslaMate 云归档不等于 Fleet 采集成功。
- 用户只需在必要时点击 App 的官方入口，由车主本人确认；不提交密码/令牌，不要求编辑 .env。

## 最终交付证据（2026-10-02）

- App 源码 1e743bf；2.1.21 / build 40，com.matelink，非 debuggable，原签名核验通过。
- 最终 APK SHA256：6060EF2B99BEF5B77EE85FF6692FB8889BD0E1551363353FCEF4E7CFFD4D6E77。
- 最终完整 Debug/Release 各 561 项，零失败/错误；Release 8 跳过；Lint 0 错误、239 既存警告；签名构建成功。
- 原 OnePlus 上仅同签名 install -r，firstInstallTime 保持 2026-08-31 22:36:47；启动样本存活、0 FATAL、0 ANR。未清数据、未卸载、未输入凭据、未点击 Tesla 授权。
- 原手机实际选择自托管；经现有设置按钮切回云端，保留原服务器设置和会话。真机已观察首页自动确认弹窗；最终覆盖安装后停在已保存的车辆确认面板，不是要求重新登录。
- 证据位于 E:/Claude_allow/Download/matelink-b40-final-install.json、matelink-b40-final-smoke.json、matelink-b40-final-confirmation.png。最后一次 UI XML dump 返回 null root，未使用旧 XML 作为最终证据，最终页面以新截图核验。
- API 781c402 在线且 readyz SHA 一致；代理专用出口存在、无发布端口。测试 PostgreSQL 容器和临时服务器诊断程序已清理，生产数据库/历史未删除。
- PENDING：由车主本人完成官方钥匙确认；确认返回后的自动配置、Fleet 首事件、真实新行程/充电和七天观察；最终模式热切换和历史详情页面复验也留到该人工门禁之后。
