# JourVolt 真实单车 Pilot 预检

状态：`CODE READY / EXTERNAL GATE REQUIRED`

这份预检把已经完成的 App/本机服务和必须由 Jovi 在外部完成的事项分开。它不接收 Tesla 密码，不自动购买服务器，不自动部署公网服务。

## 代码侧已准备

- API 提供 /healthz 进程探针和 /readyz PostgreSQL 就绪探针；Pilot Compose 已配置容器健康检查。
- Pilot Compose 提供可选 edge profile：Caddy 终止公网 HTTPS、托管 assetlinks.json 并反向代理到只绑定回环的 API。

- 正式包名为 `com.matelink`；Debug Mock 包为 `com.matelink.test.mock`。
- Go 服务已实现官方 OAuth state/nonce、一次性 ticket、加密 Tesla token、原子 refresh 和 Fleet 只读 Provider。
- Pilot Compose 默认关闭 `JOURVOLT_ENABLE_MOCK` 与 `JOURVOLT_ENABLE_MOCK_HISTORY`，API 只绑定回环地址。
- `preflight.ps1` 检查必需变量、HTTPS 回调、非示例/非私网域名、32 字节 token key、关闭 Mock、本地正式 `assetlinks.json` 的包名和证书指纹，以及可选的 API/App Link 公网 DNS。
- `assetlinks.json.example` 只声明正式 `com.matelink`，不声明 Debug 测试包。
- Android 在 ticket exchange 前再次校验 BuildConfig 中的正式 host、HTTPS scheme 和精确 `/oauth/callback` 路径。

## Jovi 需要先完成

1. 准备可控制 DNS 的域名，并确认 Tesla 中国开发者应用审核通过。
2. 取得允许的 scope：`openid`、`offline_access`、`vehicle_device_data`。
3. 准备 Tesla 要求的域名公钥/伙伴注册和公开 HTTPS callback。
4. 使用正式签名证书 SHA-256 指纹生成并托管 `/.well-known/assetlinks.json`。
5. 在部署服务器的私密 `.env` 中填写 `DATABASE_URL`、`POSTGRES_PASSWORD`、Tesla 配置和随机 32 字节 `JOURVOLT_TOKEN_KEY_BASE64`。

`client_secret`、私钥、refresh token、Tesla 账号密码和完整车辆标识都不要发送到聊天、提交 Git 或写入 Obsidian。只需把“审核通过、域名、公开 callback、App Link、scope”这些非敏感结果告诉 Codex。

## 执行命令

域名入口准备好后，先将正式签名的 assetlinks.json 放到 public\.well-known\assetlinks.json，并在私密 `.env` 填写 `JOURVOLT_API_DOMAIN`、`JOURVOLT_APP_DOMAIN` 与 `JOURVOLT_ACME_EMAIL`，再运行 edge profile。API 域名必须对应 `TESLA_REDIRECT_URI`，App Link 域名必须对应 `JOURVOLT_APP_LINK_URI`；两个域名也可以相同。

~~~powershell
docker compose --profile edge -f docker-compose.pilot.example.yml up --build -d
~~~

```powershell
cd E:\project\tesla_master\app_mimo\deploy\jourvolt-dev-mock
.\preflight.ps1 -SkipCompose
docker compose -f docker-compose.pilot.example.yml config --quiet
docker compose -f docker-compose.pilot.example.yml up --build -d
.\preflight.ps1 -VerifyAppLink
```

也可以在私密 `.env` 完成后执行一键收口脚本：

```powershell
.\pilot-up.ps1 -VerifyAppLink
```

它会按同一个 `-EnvFile` 运行预检、检查 edge 所需的两个公网 DNS、校验 Pilot Compose、启动 edge profile，并在 API 容器内检查 `/readyz`；任何预检失败都会在启动前停止。

服务入口准备好后，使用正式 API 域名和 App Link 域名构建 `com.matelink` Release（产物仍需由签名持有人签名）：

```powershell
cd E:\project\tesla_master\app_mimo\android
.\build-pilot-apk.ps1 `
  -ApiBaseUrl https://<API域名>/ `
  -AuthHost <AppLink域名> `
  -PublicInfoBaseUrl https://<公开条款隐私域名>/
```

脚本只接受公网 HTTPS API 地址和主机名，自动执行 `lintRelease`、检查正式包名并输出 APK SHA-256；不读取 Tesla secret 或 keystore 密码。默认输出 `UNSIGNED_RELEASE`。

取得正式签名持有人提供的私密 properties 文件后，可以用同一个入口构建签名包；properties 文件和 keystore 必须位于 Git 忽略范围或仓库外：

```powershell
.\build-pilot-apk.ps1 `
  -ApiBaseUrl https://<API域名>/ `
  -AuthHost <AppLink域名> `
  -PublicInfoBaseUrl https://<公开条款隐私域名>/ `
  -SigningPropertiesPath D:\private\matelink-release.properties
```

该模式只把私密文件路径传给 Gradle，自动寻找 `app-release.apk` 并用 `apksigner verify` 验证；不会创建密钥、打印密码或安装 APK。`keystore.properties.example` 仅是字段模板。

正式签名证书指纹准备好后，用 `write-assetlinks.ps1` 生成 `public\.well-known\assetlinks.json`；先加 `-WhatIf` 预览。这里填写的是证书指纹，不是 APK 文件 SHA-256。

当前本机构建证据：`com.matelink`、`UNSIGNED_RELEASE`，APK SHA-256 为 `5D2EA3338DF4B9F3AA64D0BE003945D23D9092D355A9D019F743921BB3698F03`。未签名 APK 不能直接发布或安装到正式用户设备。

## 2026-08-22 原分析页有历史数据回归证据

隔离模拟器 `emulator-5554` 已在本地 Mock history fixture 下进入原 MateLink `More -> Statistics`，不是独立新壳或单页 Mock 首页。统计页实际显示总览、加权效率、充电成本、派生结论、数据覆盖和数据驱动建议；建议包含样本量、距离覆盖、观测天数、置信度、节省区间、动作和方法。

- `LOCAL MOCK HISTORY PASS`：`420 km`、`215 Wh/km`、`90 kWh`、`58 kWh`、`42.50 ¥`、23 条来源记录。
- `LOCAL MOCK HISTORY PASS`：18/18 行程距离和能量、5/5 充电能量和成本，观测期 56 天。
- 截图：`C:/Users/Admin/.codex/visualizations/2026/08/22/jourvolt/matelink-stats-populated.png`、`C:/Users/Admin/.codex/visualizations/2026/08/22/jourvolt/matelink-stats-populated-lower.png`。

这证明的是本地 fixture 到原分析页的链路和 UI 状态，不是 Tesla Fleet/Telemetry 真实数据证明；真实数据仍需外部 Pilot 门禁。

## 2026-08-22 自包含 Pilot bundle

`deploy/jourvolt-dev-mock/package-pilot.ps1` 已提供无密钥 bundle 生成入口。它从完整工作树复制 Go API、Compose、Caddy、预检/启动/备份脚本和 `web_matelink/public` 静态内容；输出包内 `.env.example` 设置 `JOURVOLT_PUBLIC_ROOT=./public`，不再依赖服务器上的 `../../web_matelink/public` 路径。

bundle 生成器拒绝仓库内输出、拒绝覆盖已有输出目录，并在部署目录存在 `.env` 时 fail-closed。生成成功只证明打包内容完整，不证明 Tesla 凭据、DNS、ACME、签名 App Link 或真实车辆可用。

## 2026-08-22 当前最新本地发布候选

- Android Release：`com.matelink`，未签名，APK SHA-256：`E8AE046F54641197C3565FB868400F8C1C104BFE81BCB7DB4EECCF0E5E8CB855`。
- Android Debug/Release JVM：各 `237` tests，failures/errors/skips 均为 `0`；Release lint `258` findings、`0` errors、`MissingTranslation=0`、无 baseline。
- Go `test ./... -count=1`、`go vet ./...`、Pilot Compose、Caddy、Bash/PowerShell 预检和 bundle 内容检查通过。
- 隔离模拟器 `emulator-5554` 已验证原 Dashboard、More 页和有历史数据的 Statistics；最近日志无 FATAL/ANR。
- 交付边界：以上是本机/模拟器/配置验证，不是正式签名、服务器公网、Tesla OAuth 或真实车辆证明。

预检通过只表示部署配置满足静态门禁，不表示 Tesla OAuth 或真实车辆已通过。真实验收仍需在真机/专用设备完成：Tesla 官方登录、车辆列表、刷新、退出失效和跨用户隔离。

## 2026-08-21 最新本机验证

- Android 182/182 JVM tests、assembleRelease 和 lintRelease PASS；Release lint 为 0 errors、255 warnings、8 information，MissingTranslation=0，未使用 baseline。
- Docker API 与 PostgreSQL 均 healthy；/healthz 和 /readyz 均返回 ok。Caddy edge profile 可解析，但没有在公网启动。
- 模拟器仅证明 LOCAL MOCK PASS：原 Dashboard、车辆状态、底部导航和来源标识一致；不证明 Tesla OAuth、Fleet API 或真实车辆。
- 外部门禁仍未完成：Tesla 应用批准、受控域名、公网 HTTPS callback、正式签名 SHA-256、assetlinks.json 发布和真实单车授权。

## 2026-08-21 下一阶段发布候选复核

- Android 全量门禁重新通过：193/193 JVM tests，0 failures/errors/skips；Debug、AndroidTest APK、Release 和 Release lint 均通过。
- Release lint 为 0 errors、`MissingTranslation=0`，无 lint baseline；这是多语言覆盖/发布门禁状态，不是运行时 Bug 数量。
- 正式构建入口重新输出 `com.matelink / UNSIGNED_RELEASE`，APK SHA-256 为 `5D2EA3338DF4B9F3AA64D0BE003945D23D9092D355A9D019F743921BB3698F03`。签名密钥不在仓库，未签名 APK 不可分发。
- Go `go test ./... -count=1`、`go vet ./...`、Docker health/ready、PowerShell 脚本解析和 `git diff --check` 均通过。
- 使用 `.env.example` 试跑 `pilot-up.ps1` 在预检阶段失败，未启动或改变本机服务；这是预期的 fail-closed 结果。
- 当前默认 `api.jourvolt.com`、`auth.jourvolt.com` 均无 A 记录，因此真实 edge Pilot 尚未启动。下一步仍需正式域名/DNS、Tesla 应用批准、公开 HTTPS callback、签名指纹、私密配置和真实单车授权。

## 2026-08-21 云模式位置隐私门禁

- Android 外部地理编码现在只允许 `SELF_HOSTED` 模式；`TESLA_CLOUD` 和尚未解析的连接模式会 fail-closed。
- 云模式不会读取地理编码队列、加入新的坐标请求，也不会调用旧 Nominatim 反向地理编码或国家边界接口；已存在的本地缓存仍可读取。
- `SyncRepository` 已从已有的 drive/charge aggregate 中收集待处理坐标，但仅在自托管模式入队；不把云端车辆坐标发送到境外服务。
- 云模式的地图地址能力尚未替换为境内合规服务，因此公开版仍不能宣称精确地址/路线已完成；这项能力待正式位置授权和服务选型后单独接入。

## 2026-08-21 云模式位置门禁后全量回归

- Android `testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease lintRelease --no-daemon` PASS；195/195 JVM tests，0 failures/errors/skips。
- Release lint PASS，`MissingTranslation=0`，无 lint baseline；该项记录为多语言覆盖/发布门禁，不是运行时 Bug 数量。
- `build-pilot-apk.ps1` 重新验证 `com.matelink / UNSIGNED_RELEASE`，APK SHA-256 为 `CBC65EB9A22A5B08F9A6C5A6F3FAC7D4902624CB643063AF46B62019FD46EA4C`。
- Go test/vet、Docker health/ready、PowerShell 脚本解析和 `git diff --check` PASS。
- 真实 Pilot 外部门禁没有变化：正式域名/DNS、Tesla 应用批准、公网 HTTPS callback、assetlinks release 指纹、签名 APK 和真实车辆仍未完成。

## 数据备份运维入口

- `deploy/jourvolt-dev-mock/backup-db.sh` 已提供 age 公钥加密的 PostgreSQL custom-format 备份；私钥不进入备份目录，脚本不打印数据库密码。
- `deploy/jourvolt-dev-mock/restore-db.sh` 已提供显式确认的覆盖恢复入口；必须同时提供备份外的 age identity 和 `I_UNDERSTAND_DATABASE_OVERWRITE`，本机未执行恢复。
- 备份脚本默认不删除历史；显式 `--prune` 时日备份保留7个，`--weekly --prune` 时周备份保留4个。
- 7个日备份、4个周备份和境内异地对象存储仍需在实际云厂商控制台/主机定时任务配置；脚本只完成导出与加密，不把未选择的对象存储实现假装成已部署。

## Linux 服务器入口

- `deploy/jourvolt-dev-mock/preflight.sh` 和 `pilot-up.sh` 是不依赖 PowerShell 的 Linux 等价入口；它们复用同一套正式域名、HTTPS、Mock 关闭、App Link、密钥长度和 Compose fail-closed 检查。
- PowerShell 预检同时补齐 `POSTGRES_PASSWORD` 必填项，避免 Compose 到启动阶段才报告缺失数据库密码。
- `deploy/jourvolt-dev-mock/systemd/` 已提供每日/每周备份 service、timer 和 `backup.env.example`；定时任务以 `jourvolt` 用户运行，仅通过 `docker` 组访问 Docker socket，并只允许写入 `/srv/jourvolt-backups`。
- systemd 模板仅完成静态检查，未在本机或服务器启用；目标服务器仍需管理员安装 `age`、填写真实 age 公钥、配置 `.env` 权限，并手工完成一次 service 运行和隔离恢复演练。

## 2026-08-21 外部状态复查

- 本机 Docker `/healthz` 与 `/readyz` 继续正常。
- `api.jourvolt.com`、`auth.jourvolt.com` 的 A/AAAA/CNAME 当前均未解析；因此没有启动 edge、公网 callback 或真实 Tesla OAuth。

## 2026-08-21 最新本地门禁

- Android 全量门禁现为 `202` JVM tests，failures/errors/skips `0`；`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、`lintRelease` 均通过。
- Release 仍为未签名 `com.matelink` `1.4.2`，SHA-256 为 `704593A8EEC463DBABCAF20E9BD338016708C9901CEC9906C9121A69F84972F1`；`MissingTranslation=0`，Lint `256` 项仍按多语言覆盖/发布门禁记录。
- 当前 DNS 复查：`api.jourvolt.com`、`auth.jourvolt.com`、`jourvolt.com`、`jourvolt.cn` 均无 A/AAAA 记录，公网 HTTPS 不可用；真实 Pilot 仍未启动。

## 2026-08-21 分析详情未知值保真修复

- Android 分析详情模型将海拔、温度和充电功率记录改为可空值；`StatsRepository` 不再用 `0`/`0.0` 替代缺失观测。
- `UnitFormatter` 和统计页对缺失值显示不可用标记，不把未采集数据呈现为真实零值。
- Android JVM `207` tests，failures/errors `0`；`assembleRelease`、`lintRelease`、Go test/vet 通过。
- Release lint `256` warnings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 未签名 Release：`com.matelink` `1.4.2`，SHA-256 `52C33114887629FA293D33DF5D07AEA1143816EB4E21F870F092B547A7E91AD6`；`git diff --check` 为 `0`。
- Boundary：只验证本地统计模型与构建门禁；真实 Fleet、Tesla OAuth、公网 DNS/HTTPS、正式签名和实体设备仍未验收。

## 2026-08-21 里程成本零值保真修复

- 年、月、日和生命周期里程成本现在统一走 `observedCostSumOrNull`；真实 `0` 元保留，无成本来源不再渲染 `0.00`。
- 里程页缺失成本显示 `—`，与统计成本聚合和充电成本页保持一致。
- 新增成本规则回归测试；Android JVM `207` tests，failures/errors `0`；`lintRelease`、`assembleRelease`、Go test/vet 和 `git diff --check` 通过。
- Release lint `256` warnings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 未签名 Release：`com.matelink` `1.4.2`，SHA-256 `4C32A593EF10C988170CD57A55333699D63ED7B9FDBF7984811DE7342552E825`；本机 Mock `/healthz`、`/readyz` 返回正常。
- Boundary：只完成本地分析逻辑和构建门禁；真实 Tesla OAuth、Fleet、正式签名、公网 Pilot 和实体手机仍未验收。

## 2026-08-21 分析报告缺失效率显示收口

- 统计页、年度报告和 PDF 报告在最高效率缺失时显示 `N/A`，不再把缺失值格式化为 `0` 或 `null`。
- 最终 Android JVM `208` tests，failures/errors `0`；Go test/vet、`lintRelease`、`assembleRelease` 和 `git diff --check` 通过。
- Release lint `256` warnings、`0` errors、`MissingTranslation=0`、无 baseline；该数量属于多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 当前未签名 Release：`com.matelink` `1.4.2`，SHA-256 `7578E86536462BA639E763243B3DBC58321C21AE7F6076E8F3571A928415A4D6`；本机 Mock `/healthz`、`/readyz` 正常。
- Boundary：真实 Tesla OAuth、Fleet、公网 DNS/HTTPS、正式签名、服务器和实体设备仍未验收。
- Clarification：当前 `256` 是 lint findings 总数，主要由 `UnusedResources`、`GradleDependency`、`DefaultLocale` 等规则组成，`MissingTranslation=0`；此前的 `883 MissingTranslation` 应归类为多语言覆盖/发布门禁，不应描述为运行时 Bug，也不应把当前 256 全部归入该单一类别。

## 2026-08-21 部署包执行性验证

- 使用仅存在于当前进程的临时假值校验 `docker-compose.pilot.example.yml`，Compose 配置解析通过；没有写入 `.env` 或输出秘密值。
- 在隔离 `node:22-bookworm-slim` 容器中执行 Bash 语法检查，`preflight.sh`、`pilot-up.sh`、`backup-db.sh`、`restore-db.sh` 全部通过。
- 示例配置缺少正式 Tesla 参数时 PowerShell 预检按预期 fail-closed；assetlinks 生成器对错误长度指纹拒绝，对正确的 64 位测试指纹 `-WhatIf` 预览通过。
- Windows 本机没有 Bash，属于开发环境能力差异；Linux 入口已完成容器内语法验证。没有启动 Pilot、读取 Tesla 凭据或写入签名文件。

## 2026-08-21 Linux assetlinks 生成器

- 新增 `write-assetlinks.sh`，与 PowerShell 版本保持相同的 `com.matelink`、正式证书指纹和 public 目录边界。
- Linux 隔离容器验证通过：正确指纹 `--what-if` 成功，错误指纹和 public 目录外输出路径均拒绝；没有写入正式资产。

## 2026-08-21 正式 Release 启动页验证

- 为验证正式构建而非 Debug Mock，曾使用仅存在于当前进程的 Android Debug Keystore 临时签名配置，并只安装到隔离模拟器 `emulator-5554`；`apksigner` v2 验证通过。
- 临时签名 Release 显示原 MateLink 登录页和官方授权流程入口，包含 `Connect your Tesla`、`Use Tesla login`、`Advanced: connect a self-hosted service`；没有 Mock 入口、`10.0.2.2`、设计审查入口或日志 FATAL/ANR。
- 该次验证没有公网 DNS/HTTPS、Tesla 应用配置或真实车辆，因此不等同于真实 OAuth/Pilot 通过。
- 临时签名属性已删除；关闭配置缓存并强制重建后，当前 Release 目录为未签名 `app-release-unsigned.apk`，包名 `com.matelink`、version `1.4.2`、SHA-256 `7BDF20FED7F2FA4D2193B6C6E1A8CA9A085E92F3C54A04303D342F0E97D90A89`。正式签名证书仍需由发布方提供，未签名包不可直接发布。

## 2026-08-21 正式 Pilot 构建入口复跑（当前权威证据）

- 执行 `android/build-pilot-apk.ps1 -ApiBaseUrl https://api.jourvolt.com/ -AuthHost auth.jourvolt.com` 成功；脚本完成 `lintRelease`、`assembleRelease`、`aapt` 包名校验和未签名状态检查。
- Android JVM 当前为 `202/202`，failures/errors/skips 均为 `0`；Go `test ./... -count=1`、`go vet ./...` 均通过；本机 Docker/PostgreSQL `/healthz`、`/readyz` 返回 `200`。
- Release lint 为 `256` 个 Warning、`0` 个 Error、`MissingTranslation=0`，无 lint baseline；这些是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 脚本输出 `UNSIGNED_RELEASE`，`com.matelink` `1.4.2`，SHA-256 `7BDF20FED7F2FA4D2193B6C6E1A8CA9A085E92F3C54A04303D342F0E97D90A89`。
- 当前四个 JourVolt 域名均无 A/AAAA 记录；本地验证不等同于真实 Tesla OAuth、真实车辆或公网 Pilot。

## 2026-08-21 服务器预算重新核对

- 腾讯云官方价格表当前列出中国内地 2核4G/100GB/7Mbps/1000GB 为 `90 元/月`；官方购买页展示 2核2G 入门套餐年付约 `459 元/年`。2核2G 才符合原固定预算，但需要在不启用 Telemetry 的情况下做内存压力试跑。[腾讯云价格总览](https://cloud.tencent.com/document/product/1207/73452/)、[腾讯云购买页](https://cloud.tencent.com/product/lighthouse?Is=sdk-topnav)
- 阿里云官方活动页面当前展示的 2核4G/40GB/2Mbps 年付促销示例约 `1733.04 元/年`，不满足原 600 元/年服务器门槛，且活动价不等于续费价。[阿里云轻量应用服务器](https://promotion.aliyun.com/ntms/act/swas.html)
- Oracle Always Free 的官方上限可达到 2 OCPU/12GB 等价 Ampere 资源，但要求 home region，可能遇到容量不足，且免费账户无 SLA/Oracle 支持；不作为中国大陆真实 Pilot 主服务。[Oracle Always Free](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)、[Oracle FAQ](https://www.oracle.com/cn/cloud/free/faq/)
- 决策：不按“99/199 元可以买到稳定 2核4G”执行。预算不变时选择腾讯云 2核2G仅作邀请制、无 Telemetry 技术公测候选；需要稳定 2核4G时，应先把预算和续费门禁调整到约 918 元/年以上，再采购。
- 本轮没有下单、改 DNS 或启动公网服务。

## 2026-08-21 2GB 候选本地容量烟测

- 本机 Mock Compose 临时将 API 与 PostgreSQL 各限制为 `1GiB`，合计模拟 `2GiB` 服务器；合法 Mock 会话下对车辆、兼容车辆和能力接口并发请求 `1000` 次，全部 `200`，耗时约 `100ms`。
- 限制期间 API 约 `20.41MiB`、PostgreSQL 约 `76.08MiB`；测试后通过 Compose 重建恢复原始无限制配置，`/healthz`、`/readyz` 正常，Mock 的 `1` 辆车、`18` 条行程和 `5` 条充电记录仍在。
- 该结果只支持“2核2G可作为无持续 Telemetry 邀请制公测候选”的初步判断，不是生产性能、带宽、备份或 SLA 证明。

## 2026-08-21 隔离 Pilot Caddy Edge 烟测

- 使用独立 Compose 项目 `jourvolt-edge-smoke` 和进程内临时假配置启动 Fleet 模式 API、PostgreSQL 与 Caddy；未写入正式 `.env`、Tesla 凭据或签名文件。
- HTTPS 入口验证通过：`/healthz`、`/readyz` 经 Caddy 反代返回 `200`；`/.well-known/assetlinks.json` 返回 `com.matelink`；未认证车辆接口返回 `401 session_required`。
- 测试项目的容器、网络和卷已按项目名清理；本地 `jourvolt-dev-mock` 保持运行且健康。
- 边界：本次使用 `*.localhost`、Caddy 内部测试证书和占位 SHA-256 指纹，只证明部署包的 edge 路由结构，不能证明公网 DNS/ACME、正式签名 App Link、Tesla OAuth 或真实车辆。

## 2026-08-21 统计成本零值保真修复

- `ChargeSummaryDao` 新增有费用字段记录数查询；`StatsRepository` 现在保留有来源的真实 `0` 元费用，同时把 SQL `COALESCE` 产生但没有任何费用字段的 `0` 视为不可用。
- 新增 `observedAggregateCostOrNull` 及零值、无来源、NaN、Infinity、负值测试。
- `:app:testDebugUnitTest`、`:app:lintRelease`、`:app:assembleRelease` 均通过；lint `256` 项、`MissingTranslation=0`、`0` Error。
- 当前未签名 `com.matelink` `1.4.2` Release SHA-256：`BC4C282ED34FC30E27853021C336FA63A27A13760AF41CB470FF15C99D217BF7`。
- 该修复只覆盖本地统计数据诚实性，不改变真实 Tesla OAuth、服务器、公网 DNS、签名和真实车辆外部门禁。

## 2026-08-21 隐私页面与账户删除外部门禁（当前最新）

- Pilot Release 构建现在强制传入 `-PublicInfoBaseUrl`，并拒绝非 HTTPS 根地址；它用于绑定 App 内的《用户协议》和《隐私政策》入口，避免构建出无法向用户展示文件的云登录包。
- 上线前必须把 `terms/`、`privacy/` 及实际运营主体/联系渠道发布到该受控 HTTPS 域名；当前仓库文本仅为待发布版本，未代表已完成法律审核、ICP备案或 App 备案。
- 本地账户删除已通过 PostgreSQL/Docker 验证，但真实 Pilot 仍必须另行验证 Tesla 授权撤回、真实用户车辆隔离和正式备份自然过期。没有 Tesla 应用批准、DNS、HTTPS callback、正式签名/App Link、服务器私密配置和真实单车授权时，不启动公网 Pilot。
- 最新候选只通过本地构建：`com.matelink` `1.4.2` / versionCode `14` / `UNSIGNED_RELEASE`，SHA-256 `ABE80587FF6328D7D95FEB62E1038386583D4D63200AE3661AACAC06AC96A77A`；Android `211` JVM tests 全部通过，Release lint `256` findings、`0` errors、`MissingTranslation=0`。未签名候选不可以安装给正式用户或视为发布包。

## 2026-08-22 异地加密备份上传门禁收口

- `backup-db.sh` 仍先生成 age 加密 PostgreSQL custom-format 归档；设置 `JOURVOLT_BACKUP_RCLONE_REMOTE` 后使用 `rclone copyto` 上传，上传失败会返回失败，不会把本机文件误报为异地备份。
- systemd 每日/每周模板现在显式使用 `--require-upload`；没有配置对象存储 remote 或缺少 `rclone` 时定时任务失败关闭。对象存储凭据由目标服务器 `/etc/jourvolt/rclone.conf` 私密管理，不写入仓库或聊天。
- `restore-db.sh` 现在强制拒绝位于备份目录内的 age 私钥；恢复仍需显式 `I_UNDERSTAND_DATABASE_OVERWRITE`，没有在本机执行覆盖操作。
- Bash/PowerShell 脚本语法、Go test/vet、Docker API/PostgreSQL `/healthz`/`/readyz` 已复核通过。真实对象存储生命周期配置和服务器独立恢复演练仍属于外部门禁，不能用本机 Mock 代替。

## 2026-08-22 最终本地门禁复核

- 本地代码、Docker、Go、Android 构建和隔离模拟器回归均已完成；当前状态仍为 `APP STRUCTURE READY / LOCAL MOCK HISTORY PASS / REAL TESLA PILOT BLOCKED`。
- 生产备份脚本已 fail-closed：systemd 日/周任务强制 `--require-upload`；没有真实 `rclone` remote、上传失败或私钥位置不安全时不能报告成功。
- 外部待办没有被本地证据替代：正式服务器、对象存储生命周期、独立恢复演练、域名/DNS、Tesla 应用批准、公开 HTTPS callback、正式 Release 签名/assetlinks 和真实车辆授权仍需 Jovi 后续提供或确认。

## 2026-08-22 法律页面静态发布门禁

- Caddy edge 现在独立静态提供 `/terms/` 和 `/privacy/`，不会把用户协议/隐私政策请求转给 API；页面来源为仓库 `web_matelink/public`。
- `preflight.sh` 与 `preflight.ps1` 会检查本地法律页面存在；启用公网 App Link 验证时，还会检查 `assetlinks.json`、`/terms/` 和 `/privacy/` 的 HTTP 内容。
- Pilot Compose 配置和 Caddy 配置验证通过；正式运营主体、联系方式、第三方 SDK 清单和法律文本审核仍是外部发布门禁，未因本地文件存在而视为完成。

## 2026-08-22 自包含 Pilot bundle 运行烟测

- 使用 bundle 结构的独立 Compose 项目 `jourvolt-bundle-smoke-20260822` 启动 API 与 PostgreSQL；未使用真实 Tesla secret、正式 `.env` 或正式签名文件。
- `/healthz` 返回 `mode=fleet`、`persistence=postgres`、`status=ok`；`/readyz` 返回 `mode=fleet`、`persistence=postgres`、`status=ok`。
- 烟测后的临时容器、网络和卷已按项目名清理；现有本机 Mock 服务保持不变。
- 该结果证明无密钥 bundle 的 API/PostgreSQL 启动链可运行，不证明公网 DNS/HTTPS、Tesla 应用审核、正式 App Link、真实 OAuth 或真实车辆。

## 2026-08-22 Pilot Release 参数化构建收口

- 运行 `android/build-pilot-apk.ps1`，传入 `https://api.jourvolt.com/`、`auth.jourvolt.com` 和 `https://api.jourvolt.com/` 作为 API、App Link 与法律页根地址；入口成功完成 Release lint、assemble、包名和 APK 状态检查。
- 当前产物为 `com.matelink`、`UNSIGNED_RELEASE`，SHA-256：`7EAE5407DA3538626767FEFAD7CCE5FADF3E0282E7B0F3F8A2008CF32532DE1F`。
- 这确认正式配置参数能够进入构建并使云登录配置路径可生成；由于域名尚未解析、Tesla 应用尚未批准、APK 未正式签名，不能分发或称为真实登录通过。

## 2026-08-22 Release 登录页模拟器边界

- 只读启动隔离模拟器已有的 `com.matelink` `1.4.2`，可见官方 Tesla 授权说明、用户协议/隐私勾选和 `Use Tesla login`；勾选协议后按钮变为可用，最近 500 条日志无 FATAL/ANR。
- 当前新生成的未签名 APK 未覆盖安装：模拟器已有 `com.matelink` 的签名不同。没有卸载、清除数据或触碰实体手机；因此不能把上述 UI 结果升级为当前候选 APK 已安装证明。
- 正式签名后，应在空模拟器或经单独授权的同签名设备执行一次安装和登录页回归。

## 2026-08-22 目标恢复后的外部状态复核

- 本机 DNS 查询显示 `api.jourvolt.com` 与 `auth.jourvolt.com` 当前均无 A/AAAA 记录。
- 因此尚不能执行公网 HTTPS、Tesla callback 或 App Link 验证；本次没有修改 DNS、启动公网服务或读取任何 Tesla 凭据。

## 2026-08-22 本地登录回流保护验证

- `TeslaAuthNavigationContractTest` 已锁定真实产品路径的关键回流语义：session authenticated 后执行 `onLoginSuccess()`，清除登录路由返回栈并进入原 `Dashboard`；运行中的 Activity 收到新的 App Link 时更新 Compose intent。
- Debug/Release 各 `239` 项 JVM 测试通过；Release lint/build 和 `git diff --check` 通过；`MissingTranslation=0`，无 lint baseline。
- 最新未签名 Release APK SHA-256：`BB2A7D64D8B45E9B2DA866E84CD906D47D292C24C80C6858B52425A3FE933AD1`。
- 该证据只证明本地代码回流契约，不证明公网 DNS/HTTPS、Tesla 应用审核、正式签名、真实 OAuth 或真实车辆；外部阻塞条件保持不变。

## 2026-08-22 当前工作树 Pilot bundle 刷新

- 从当前工作树生成最新无密钥 bundle：`E:/Claude_allow/Download/jourvolt-pilot-bundle-current`；manifest 标记 `secrets_included=false`，静态公开根为 `./public`。
- bundle 内容核对通过：法律页、`.well-known/`、Caddy、Compose、预检、启动、备份和恢复脚本均随包携带；`.env`、证书、私钥和 keystore 文件均未包含。
- 独立 Compose 烟测返回 `/healthz`、`/readyz`：`status=ok`、`mode=fleet`、`persistence=postgres`；临时项目 `jourvolt-bundle-current-smoke-20260822` 已清理。
- 该证据只证明当前部署包的本地启动链，不证明服务器、公网 DNS/HTTPS、Tesla 审核、正式签名或真实车辆；外部 Pilot 门禁保持不变。

## 2026-08-22 注销回流与最新 bundle

- `DELETE /v1/account` 的服务端行为已补充为：级联删除 JourVolt 侧 session、加密 Tesla grant、车辆和同意记录；Fleet OAuth 配置存在时返回 `tesla_consent_revoke_url`。
- Android 注销成功后打开 Tesla 官方 consent 管理页；这与 [Tesla Third-Party Tokens](https://developer.tesla.com/docs/fleet-api/authentication/third-party-tokens) 描述的用户撤销路径一致。没有把本地 token 删除写成 Tesla 远端撤销已完成。
- 最新无密钥 bundle：`E:/Claude_allow/Download/jourvolt-pilot-bundle-revoke-20260822`。bundle Compose 使用隔离端口 `18190` 启动通过，`/healthz`、`/readyz` 均为 `status=ok`、`mode=fleet`、`persistence=postgres`；临时容器、卷和网络已清理。
- bundle 文件核对：`secrets_included=false`、`.env/.jks/.p12/.pem/.key` 文件计数 `0`。服务器仍必须在正式域名、Tesla 批准、正式签名指纹和私密 `.env` 准备后运行预检。

## 2026-08-22 隔离模拟器宿主环境门禁

- 当前 AVD `MateLink_P0_Qualification_API35` 的启动过程未向 ADB 注册 `emulator-5554`，宿主也没有 5554/5555 监听；日志曾报告 `too many emulator instances are running`，随后标准/只读启动均未形成可用设备。
- 不依赖设备的证据仍通过：`assembleDebug` 成功，包名为 `com.matelink.test.mock`；`TeslaAuthNavigationContractTest` 7/7 通过。
- 本轮没有运行 instrumentation、卸载或清除 AVD 数据，也没有操作实体手机；因此当前候选的运行时页面回归仍待模拟器宿主修复后执行。

## 2026-08-22 临时 AVD 排除数据问题

- 新建全新 Android 35 临时 AVD `JourVolt_Temp_Verify_20260822` 后仍未注册 `emulator-5554`，宿主也没有 5554/5555 ADB 监听；该结果排除了原 AVD 用户数据损坏作为主要原因。
- 临时 AVD 已删除，原 `MateLink_P0_Qualification_API35` 未清除、未删除；没有运行 instrumentation 或触碰实体手机。
- 运行时页面回归仍需宿主 Android Emulator/ADB 修复后执行，不能用当前静态构建证据替代。

## 2026-08-22 ADB 连接参数复核

- 已复核标准启动、只读启动、显式 `-ports 5554,5555`、`-skip-adb-auth`、`-no-direct-adb` 和同版本 `-adb-path`；均未形成可用 `emulator-5554`。
- 结论是宿主 Android Emulator/ADB 通道故障，不是当前 APK 的构建或登录契约失败；没有继续修改 AVD 数据或 App 代码绕过该问题。

## 2026-08-22 无设备门禁复核

- Android Debug JVM `239` tests 全通过，`assembleDebug` 成功；Go `test ./... -count=1` 与 `go vet ./...` 全通过。
- 本机 Mock/PostgreSQL `/healthz`、`/readyz` 均返回 `status=ok`、`mode=mock_only`、`persistence=postgres`、`mock_history=true`。
- `preflight.sh`、`pilot-up.sh`、`backup-db.sh`、`restore-db.sh` 使用 Git Bash `bash -n` 全通过；Windows 系统 `bash.exe` 失败是 WSL 无 bash 的宿主配置问题。
- 设备运行时页面回归仍未执行，原因保持为宿主 ADB 通道，不影响代码、服务和部署包门禁。

## 2026-08-22 注销回流地址修正后的 bundle 烟测

- `consentRevokeURL` 的官方 Tesla 撤销页保留 Tesla 区域域名，完成撤销后的 `back_url` 改为 JourVolt App Link 域名 `/privacy/`，避免回到 Tesla 自有隐私页。
- 从当前工作树重新生成无密钥 bundle：`E:/Claude_allow/Download/jourvolt-pilot-bundle-app-link-20260822`；`secrets_included=false`，`.env/.jks/.p12/.pem/.key` 文件计数 `0`。
- 隔离 Compose 项目 `jourvolt-bundle-app-link-smoke-20260822` 使用临时端口 `18191` 启动 API/PostgreSQL，`/healthz` 和 `/readyz` 均返回 `status=ok`、`mode=fleet`、`persistence=postgres`；临时资源已清理。
- 仍未上传服务器、修改 DNS、读取 Tesla 凭据、生成正式签名或执行真实 OAuth；该证据只证明当前部署包和注销回流代码在本机可启动。

## 2026-08-22 当前工作树契约 bundle

- 包含当前工作树的最新无密钥 bundle：`E:/Claude_allow/Download/jourvolt-pilot-bundle-contract-20260822`；manifest `secrets_included=false`，敏感文件计数 `0`。
- PostgreSQL 集成测试在临时隔离数据库上实际执行账号删除 HTTP 契约并通过；bundle 只作为服务器部署输入，不含正式 `.env`、Tesla secret、证书或 keystore。

## 2026-08-22 当前本地发布门禁

- Android Release 重新构建并完成静态扫描：包名 `com.matelink`，Mock/回环/Debug 标记命中 `0`；未签名 APK SHA-256 `649C31CDC0932A8D81A2B4050793E12EF7A65FECCD8BA0AD8A6B80CEC789A5FA6`。
- Android Debug/Release 各 `243` JVM tests、Debug/Release 构建、Release lint 和 `git diff --check` 通过；lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 tracked baseline。
- AVD 运行时门禁本轮未通过：两次显式启动 `MateLink_P0_Qualification_API35` 均没有 ADB 注册 `emulator-5554` 或 5554/5555 监听。未执行 instrumentation、未清理 AVD、未触碰实体手机；页面回归保持 `NOT_PERFORMED`。
- 外部 Pilot 门禁不变：必须由 Jovi 提供已批准的 Tesla 应用非敏感结果、受控 DNS/HTTPS、正式签名指纹和服务器私密配置后，才可进行真实单车 OAuth。

## 2026-08-22 本机 API 与 ADB 诊断补充

- 本机 Docker HTTP smoke 通过：Mock 登录、车辆列表、兼容快照、18 条行程、5 条充电、注销和旧 token `401` 均通过；不含 Tesla 凭据。
- AVD 在补齐 `ANDROID_SDK_HOME` 后能够启动并报告 Android boot completed，但 ADB server 没有形成 `emulator-5554` 或 5554/5555 监听；重启 server、外部 adb 和 `-no-direct-adb` 均未修复。
- 因此页面交互、截图和安装验证继续标记 `NOT_PERFORMED`；不要把本机 API smoke 或 APK 静态扫描描述成模拟器页面通过。

## 2026-08-22 最新 bundle

- 当前工作树已重新生成无密钥 bundle：`E:/Claude_allow/Download/jourvolt-pilot-bundle-runtime-20260822`，`PILOT_BUNDLE=PASS`，`secrets_included=false`。
- 包内静态根为 `./public`，包含法律页、Caddy、Compose、预检、备份恢复和 systemd 模板；正式 `.env`、Tesla 密钥、证书、正式 `assetlinks.json` 指纹仍需部署时注入。

## 2026-08-22 当前部署输入包

- 最新无密钥 bundle：`E:/Claude_allow/Download/jourvolt-pilot-bundle-local-completion-20260822`。
- manifest 明确为 `secrets_included=false`；敏感文件计数 `0`，公开静态根为 `./public`，条款与隐私页已随包携带。
- bundle Compose 配置核对通过。服务器部署前仍必须由 Jovi 注入正式 `.env`、Tesla 凭据、证书和正式 `assetlinks.json` 指纹，并通过 DNS/HTTPS/App Link 预检。

## 2026-08-22 本地候选更新

- Android 本地候选已重新构建：`com.matelink` / `1.4.2`，未签名 APK SHA-256 `1E28924C7C81CEA02FE31EC63C6F064F60D36970B10BD96C66F5EF21DD945A9D`。
- 本轮只收口统计覆盖期和充电缺失值表达；未改变真实 OAuth、服务器配置或生产安全边界。
- 发布前仍必须由 Jovi 提供已批准的 Tesla 应用非敏感结果、受控 DNS/HTTPS、正式签名指纹/ `assetlinks.json` 和服务器私密配置；本地 Mock 与未签名 APK 不能替代真实单车 Pilot。

## 2026-08-22 本地分析与充电详情边界收口

- 分析摘要只在存在对应记录时把数值标记为可用；空集合不会把默认 `0`、`0 kWh` 或 `0` 费用误报为观测值，真实观测到的零值仍保留。
- 充电详情的功率、电压、电流、温度、电量和时长在源字段缺失时显示不可用；电网能耗不再用充入电量兜底，充电效率只在两侧数据同时存在且满足约束时计算。
- 新增 `AnalysisSummaryTest` 和 `ChargeStatsCalculatorTest` 边界覆盖。Android Debug/Release 全量 JVM 各 `252` 项，failures/errors/skips 均为 `0`；Go `test ./... -count=1`、`go vet ./...` 通过。
- `assembleDebug`、`assembleRelease`、`lintRelease` 通过。Release lint 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 lint baseline；该数量只记录多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 当前 Release APK 为 `com.matelink` / `1.4.2`，未签名 SHA-256：`CE77253B830036C691BDD603482FD445F531B65C11E73231C59DA515DD429802`。静态扫描未命中回环地址、Mock provider、Mock 登录标记或 `com.jourvolt.app`。

## 2026-08-22 采购与 HTTPS 最小路径

- 技术公测只需要一个主域名即可：推荐先购买 `jourvolt.com`，用 `api.<domain>` 承载 API、`auth.<domain>` 承载 OAuth/App Link；`.cn` 可作为品牌保护域名后购，不是服务启动前置条件。
- 服务器候选为阿里云中国大陆 Linux、2 vCPU/4 GiB、至少 50 GB 磁盘、独立公网 IPv4。只有结算页的首年价格不高于 ¥600、正常续费不高于 ¥700/年，才符合本项目预算门禁；活动价和长期续费价必须分开确认。
- 当前不建议单独购买 SSL。Caddy 可通过 ACME 自动申请和续期 HTTPS；阿里云个人测试证书也可用于单域名短期测试，但通常为 90 天且不自动续期，必须建立更换流程。不限量生产再评估商业证书或托管 HTTPS。
- 域名和服务器只是部署基础，不能替代 Tesla 应用批准/凭据、可解析 DNS、公网 HTTPS callback、正式签名 `assetlinks.json`、ICP备案/App备案和真实单车 OAuth。没有这些条件不能把部署标记为真实 Tesla 登录完成。
- Jovi 采购后只需提供域名、服务器公网 IP、备案状态和 Tesla 应用审核的非敏感结果；不要发送控制台密码、`client_secret`、私钥、refresh token 或 Tesla 账号密码。

参考：
- [阿里云轻量应用服务器](https://cn.aliyun.com/product/swas?from_alibabacloud=&userCode=xrvm8bf1)
- [阿里云个人测试证书（免费版）](https://help.aliyun.com/zh/ssl-certificate/purchase-an-individual-test-certificate)
- [阿里云 HTTPS 配置](https://help.aliyun.com/zh/simple-application-server/user-guide/quickly-configure-https)
- [阿里云备案服务器检查](https://help.aliyun.com/zh/icp-filing/basic-icp-service/user-guide/icp-filing-server-access-information-check)
- [工信部 App 备案通知](https://www.miit.gov.cn/zwgk/zcwj/wjfb/tz/art/2023/art_920db564162e4312916a01bed6540ad8.html)

## 2026-08-22 建议证据判断补充（当前最新）

- 建议引擎不再用数值兜底表达缺失速度；只有有限的实际速度才会进入高速、基准或低温分组。新增缺失速度测试，避免样本不足时产生分组建议。
- Android Debug/Release 全量 JVM 各 `253` 项通过，failures/errors/skips 均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 通过。
- Release lint 仍为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`，无 baseline；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 Release APK SHA-256：`F03C42FEEBABE8309BC85D4185366BB00579BB3814C79FCF89701630DEC240F5`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。

## 2026-08-22 Room 分析归一化与部署包（当前最新）

- Room 摘要在进入建议与覆盖率计算前统一映射到中性 API 数据模型，旧零占位值保持不可用；建议距离和充入电量支持缺失状态，不再由上游强制填零。
- Android Debug/Release JVM 各 `253` 项、构建、Release lint、Go test/vet 和 Docker health/ready 通过；`git diff --check` 退出码 `0`。
- 最新未签名 Release APK SHA-256：`FB632B7E23F93F06C96A7A9590CB17EECF6ACA0240C1CE9D6A58C51595B7A26F`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- 最新无密钥 Pilot 部署包：`E:/Claude_allow/Download/jourvolt-pilot-bundle-20260822-recommendation`；`PILOT_BUNDLE=PASS`、`secrets_included=false`、无 `.env`、Tesla secret、私钥或正式签名指纹。它是部署输入包，不代表公网 Pilot 已完成。

## 2026-08-22 分析摘要覆盖率候选（当前最新）

- `StatsRepository` 进入建议/覆盖率前先经过中性 API 映射，`AnalysisSummary` 再消费有效样本覆盖率；旧 Room 零占位值不会绕过不可用状态。
- Android Debug/Release JVM 各 `254` 项通过，构建、Release lint、Go test/vet、Docker health/ready 和 `git diff --check` 通过；Release lint `258` findings 仍归类为多语言覆盖/发布门禁问题，`MissingTranslation=0`，无 baseline。
- 最新未签名 APK SHA-256：`EF0A6E7BD2673E7622159E0FC8AB50BE95726564C967CD13A78341097C74E61C`；候选 APK：`E:/Claude_allow/Download/matelink-1.4.2-release-unsigned-20260822-summary-coverage.apk`。该 APK 未签名，不能替代正式发布包。
- AVD 第三次不同启动参数尝试仍未形成 `emulator-5554` ADB 设备，因此页面交互验收保持 `NOT_PERFORMED`；实体手机未操作。真实 Tesla、DNS/HTTPS、公网服务器、正式签名和 Git 发布仍是外部门禁。

## 2026-08-22 概览卡覆盖率与购买路径（当前最新）

- 统计页概览卡和年度报告摘要已统一使用 `AnalysisCoverage`；未形成有效源样本时不显示假零值，成本和效率派生值要求输入证据同时存在。
- Android Debug/Release JVM 各 `254` 项通过；构建、Release lint、Go test/vet 和 `git diff --check` 通过。最新未签名 APK SHA-256：`D40A5F7A4C70CAC13C829DCE46C706DB8C0E353EC615CC271B94FD55D5D90F72`；候选文件：`E:/Claude_allow/Download/matelink-1.4.2-release-unsigned-20260822-summary-coverage-v2.apk`。
- 阿里云官方资料确认中国内地轻量应用服务器可用于备案；推荐规格为 Linux、2 vCPU、4 GiB、50 GiB、1 个公网 IPv4，备案实例包年包月累计至少 3 个月。最终付款前仍以结算页首年/续费价格和备案控制台资格为准。
- 不建议另购 SSL：生产部署采用 Caddy ACME 自动签发/续期，前提是 DNS 指向服务器且公网 80/443 可达；域名和服务器之外，备案、Tesla 应用批准、正式签名/App Link 和真实 OAuth 仍是独立门禁。

## 2026-08-22 Release Mock 来源构建隔离与设备门禁（当前最新）

- Release 构建不再携带 `mock_fixture` 字面量：Mock 来源仅由 Debug `JOURVOLT_MOCK_SOURCE` 注入，正式版 `JOURVOLT_MOCK_LOGIN=false` 并 fail-closed；原 `com.matelink`、原 Dashboard 和原视觉结构不变。
- Android Debug/Release JVM 各 `254` 项通过，`assembleDebug`、`assembleRelease`、`lintRelease` 通过；Go test/vet 和 `git diff --check` 通过。Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量是多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 最新未签名 APK：`com.matelink` / `1.4.2`，SHA-256 `B1FF4C3F16991EA0B79969B4E641D222CA88C90BE4339FEC41D636279A161044`；候选文件 `E:/Claude_allow/Download/matelink-1.4.2-release-unsigned-20260822-pdf-v6.apk`。APK 静态扫描 0 命中 Mock、回环地址、Debug 登录标记和错误包名。
- ADB `devices -l` 当前为空，未能安全读取手机包名/签名；因此没有安装 APK，也没有执行 instrumentation、卸载或清数据。待手机在 ADB 出现后，先做只读签名一致性检查；正式签名和真实单车 Pilot 仍是外部门禁。

## 2026-08-22 本地分析证据收口后的 Pilot 门禁

- 本轮本地实现已通过：分析页区分无记录、采集中、筛选无记录和字段覆盖不足；电池满电续航不再从缺失 SOC/额定续航推导；正在充电卡片不再把缺失字段显示为 `0%` 或 `0.00 kWh`。
- Android Debug/Release 各 `255` 项 JVM 测试通过，构建、Release lint、Go test/vet、Docker `/healthz`/`/readyz` 和 `git diff --check` 通过。Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`；该数量继续按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- Release APK `com.matelink` / `1.4.2` SHA-256 为 `AF71E1E85600C9CA377E532BB0180D8BD5B0DE49981A70DE19F4536FFCDCABD1`，候选文件 `E:/Claude_allow/Download/matelink-1.4.2-release-unsigned-20260822-analysis-state-v7.apk`；它未签名，不能用于正式分发或同签名覆盖安装。
- ADB 当前没有任何设备；因此页面运行时回归、手机安装和包签名一致性仍是 `NOT_PERFORMED`。不因 APK 构建成功或本地 Mock 健康就宣称真实 Tesla 登录/车辆通过。
- 下一步外部门禁仍为：Jovi 提供受控域名/DNS、Tesla 应用批准及私密配置、正式签名指纹和 App Link 配置；条件具备后先做只读签名核对，再申请同签名 `adb install -r` 和真实单车 OAuth。

## 2026-08-22 里程指标证据收口后的 Pilot 门禁

- 里程页现按有效距离、能耗、电量差样本分别判断可用性；记录存在但字段为空时显示不可用，缺少有效距离的月份/日期不再绘制零值图表。电池趋势缺少有效基线时保持趋势/不可用，不计算假退化百分比。
- Android Debug/Release 各 `258` 项 JVM 测试通过，构建、Release lint、Go test/vet、Docker `/healthz`/`/readyz` 和 `git diff --check` 通过。Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`；该数量继续按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- Release APK `com.matelink` / `1.4.2` SHA-256 为 `FCCB8DFDC92C783C22C95BACD3E1B7F4435756F2F7EE8F56DE41A0B8220F9566`，候选文件 `E:/Claude_allow/Download/matelink-1.4.2-release-unsigned-20260822-mileage-evidence-v8.apk`；它未签名，不能用于正式分发或同签名覆盖安装。
- ADB 当前没有任何设备；页面运行时回归、手机安装和包签名一致性仍是 `NOT_PERFORMED`。本地 Mock/服务健康和 APK 静态扫描不等于真实 Tesla 登录或真实车辆通过。
- 外部下一步不变：受控域名/DNS、Tesla 应用批准及私密配置、正式签名指纹/App Link 和服务器条件具备后，先做只读签名核对，再进入真实单车 OAuth。

## 2026-08-22 行程详情与摘要证据收口后的 Pilot 门禁

- 行程详情的速度、功率、海拔、电池、距离和时长现在保留缺失状态；行驶摘要不再用 `0 km/h` 代表缺少最高速度，无记录筛选显示不可用。原 MateLink UI、导航和自托管连接行为未重做。
- Android Debug/Release 各 `263` 项 JVM 测试通过，构建、Release lint、Go test/vet、Docker `/healthz`/`/readyz` 和 `git diff --check` 通过。Release lint 为 `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量继续按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- 最新未签名 Release APK `com.matelink` / `1.4.2` SHA-256 为 `23973805D55A94E8F09AC0533A16DAB2402809104045AD6059AE168252C784DE`，候选文件 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-drive-detail-evidence-v9.apk`；静态扫描 0 命中 Mock、回环地址、Debug 登录标记和错误包名。
- ADB 当前没有任何设备；页面运行时回归、手机安装和包签名一致性仍是 `NOT_PERFORMED`。本地 Mock/服务健康、单元测试和 APK 静态扫描不等于真实 Tesla 登录或真实车辆通过。
- 外部下一步不变：Jovi 准备受控域名/DNS、Tesla 应用批准及私密配置、正式签名指纹/App Link 和服务器；条件具备后先做只读签名核对，再申请同签名 `adb install -r` 和真实单车 OAuth。

## 2026-08-22 设备验证尝试后的门禁状态

- `adb devices -l` 仍为空；专用 AVD `MateLink_P0_Qualification_API35` 启动后未注册 ADB 并退出。
- 本轮未安装、卸载、清数据或运行 instrumentation。没有实体设备运行时证据，不能把本地 Mock、Docker 健康、单元测试或 APK 静态扫描写成手机通过。
- 设备恢复后，顺序固定为：显示唯一 ADB 序列号 → 只读核对 `com.matelink` 包名与签名 → 仅在签名一致时执行 `adb install -r` → 手工观察原 Dashboard、导航和数据状态。

## 2026-08-22 本地 API 契约复核

- Docker Mock API 健康与就绪保持 `200`；Mock 登录、车辆列表、`/api/ping` 和能力接口均通过。
- refresh token 轮换后旧 access token 被拒绝，新 token 可访问；退出后 token 被拒绝。未输出任何 token 值。
- 该证据只证明本地服务契约，不推进真实 Tesla Pilot 门禁；正式签名、域名/HTTPS、Tesla 应用批准和实体设备仍未完成。

## 2026-08-22 App Link ticket replay 防护后的门禁

- Android 已增加一次性 callback ticket 的 in-flight/已处理防重复交换保护；Debug/Release JVM 各 `267` 项通过，Release 构建和 lint 通过。
- 最新 Release 候选仍未签名，不能覆盖实体手机；当前 ADB 为空，实体设备运行时验证仍为 `NOT_PERFORMED`。
- 该修复只收口 App Link 重复回调边界，不改变真实 Tesla OAuth、域名/HTTPS、正式签名和服务器外部门禁。

## 2026-08-22 OAuth callback 并发状态隔离后的最终门禁

- callback ticket 处理同时具备 in-flight/已处理防重复和旧失败回调隔离；旧请求不能覆盖新登录请求的状态。Android Debug/Release 各 `267` 个 JVM 用例通过，failures/errors/skips 均为 `0`。
- `testDebugUnitTest`、`testReleaseUnitTest`、`assembleDebug`、`assembleRelease`、`lintRelease` 全部 PASS，Gradle 退出码 `0`。Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量继续按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- 最新候选为未签名 `com.matelink` / `1.4.2`：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-callback-replay-guard-v12.apk`，SHA-256 `541F88C8C6AED2833C47093E86C767224983D5FC4D8879E499880C54DC326221`。Release 静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- ADB `devices -l` 仍为空；没有安装、卸载、清数据或 instrumentation。正式签名、受控域名/HTTPS、App Link、Tesla 应用批准和真实单车 OAuth/Fleet 仍是独立外部门禁。

## 2026-08-22 Tesla 登录请求代次隔离后的最终本地门禁

- `TeslaLoginViewModel` 现在同时隔离 `/start` 和 callback exchange 的过期异步请求；取消旧登录不会覆盖新登录状态，也不会重复打开旧 Tesla 授权页或写入旧 session。新增 `TeslaRequestGenerationTest`。
- Android Debug/Release 各 `269` 个 JVM 用例通过，failures/errors/skips 均为 `0`；构建、Release lint、Go test/vet、Docker health/ready 和 `git diff --check` 全部通过。
- Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量继续按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- 最新未签名候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-login-generation-v13.apk`，包名 `com.matelink`，SHA-256 `3C7588D70F418E9C29124BCFB2A7D0C0CAC7866A34B808D743923EFBCD8C86BB`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- ADB 仍为空；未安装、卸载、清数据或 instrumentation。正式签名、受控域名/HTTPS、App Link、Tesla 应用批准和真实 OAuth/Fleet 仍未完成。

## 2026-08-22 Debug Mock 登录代次隔离后的最终本地门禁

- Debug-only Mock 登录也加入请求代次和取消异常隔离；该逻辑不进入正式 Release，但保证本机完整 App 回归时快速重复点击不会写回旧 session。
- Android Debug/Release 各 `269` 个 JVM 用例通过，failures/errors/skips 均为 `0`；构建、Release lint、Go test/vet、Docker health/ready 和 `git diff --check` 全部通过。
- Release lint `258` findings（250 Warning、8 Information、0 Error），`MissingTranslation=0`、无 baseline；该数量继续按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- 最新未签名候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-login-generation-v14.apk`，包名 `com.matelink`，SHA-256 `3C7588D70F418E9C29124BCFB2A7D0C0CAC7866A34B808D743923EFBCD8C86BB`；静态扫描未命中 Mock、回环地址、Debug 登录标记或 `com.jourvolt.app`。
- ADB 仍为空；未安装、卸载、清数据或 instrumentation。正式签名、受控域名/HTTPS、App Link、Tesla 应用批准和真实 OAuth/Fleet 仍未完成。

## 2026-08-22 无密钥 Pilot bundle 可上传交付物

- 已生成自包含服务器 bundle：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-local`；manifest 明确 `secrets_included=false`，扫描未发现 `.env`、keystore、证书或私钥。
- bundle 内 `go test ./... -count=1`、`go vet ./...` 通过；使用进程级占位值进行 `docker compose -f docker-compose.pilot.example.yml config --quiet` 校验通过，真实密钥没有写入文件。
- 上传压缩包：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-local.zip`，SHA-256 `84C433701FEFFF44B49C0109FAFFB70103F184C781075D7E253233AFC01FC4C4`。
- 该 bundle 只完成部署输入准备，不代表真实 Tesla OAuth/Fleet、域名 HTTPS、正式签名或公网 Pilot 已通过；服务器上仍须由 Jovi 私密填写 `.env` 并运行 preflight。

## 2026-08-22 Locale 发布质量修复后的本地门禁（v15）

- 显式 Locale 修复后，Android Debug/Release 各 `269` 个 JVM 用例通过，`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- Release lint 为 `231` findings，`MissingTranslation=0`、无 baseline、0 Error；已清除 `DefaultLocale` 与 `ConstantLocale`，其余 finding 仍按多语言覆盖/发布门禁问题记录。
- 未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-locale-v15.apk`，SHA-256 `B11042EB9B8C9138BA68341E3AA085C12332AD545A90C1D58C4D20B5C045524E`；隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-locale-v15.apk`，SHA-256 `D65DE024D3894807582DDD0C910A74173E5F0105D5A649B003F70FEADBC5E48E`。
- ADB 仍为空；未进行实体设备安装。v15 仍是未签名本地候选，不代表真实 Tesla Pilot。

## 2026-08-22 发布门禁进一步收口（v16）

- Release lint 为 `194` findings，`MissingTranslation=0`、无 baseline、0 Error；Locale、Typography 和 API 26 资源目录问题已收口。
- 最新未签名 Release：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-lint194-v16.apk`，SHA-256 `0090C25FCF3D0D69FED597389FAEB23045E8BA4A963C616F13AAB76DBDDB03CF`；隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260822-lint194-v16.apk`，SHA-256 `54420A3C8BF9D829F5D2962185B2D3D619E536D6DEE4F4C433DABAB81FDFB8CC`。
- 两套各 `269` 个 JVM 测试、构建和 Release 静态扫描通过；ADB 仍为空，v16 仍未签名，不代表真实 Tesla Pilot。

## 2026-08-22 v17 统计页接入验证边界

- 综合分析页已接入原有里程四级钻取入口；本次只验证代码编译、单元测试、构建和资源门禁，没有把本地静态证据写成页面运行时通过。
- Debug/Release 各 `269` 个 JVM 测试通过；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- Release lint `194` findings，`MissingTranslation=0`、无 baseline、0 Error；这是多语言覆盖/发布门禁问题，不是运行时 Bug 计数。
- v17 Release 未签名 APK：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-analysis-drilldown-v17.apk`，SHA-256 `BD1999A77B5444584F948B2D3543CF3E50FAD3F1663D79BDB2BCF36AE01E338E`。
- `adb devices -l` 仍为空；正式签名、实体设备 UI、域名/HTTPS、Tesla 应用批准和真实 OAuth/Fleet 仍是独立外部门禁。

## 2026-08-22 v18 本地服务与 Pilot 输入复核

- 当前 Docker Mock 服务已重新构建并通过 `/healthz`、`/readyz`；无密钥 smoke 脚本通过登录、车辆、快照、历史和注销回收。
- 新 bundle `E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v18.zip` 的 SHA-256 为 `60740D9757EB1DC2AC2BD2769DED4774B71F333DDD6F140C072E3EB68C2B4260`；Go test/vet、Compose 配置、manifest 和敏感文件扫描通过。
- 该证据仍只证明本地部署输入准备；真实 Pilot 仍需要正式域名/HTTPS、Tesla 批准、正式签名指纹、私密配置和真实车辆授权。

## 2026-08-22 v19 部署输入最终复核

- 修正版 smoke 已确认 18 条行程、5 条充电、车辆快照和注销回收；旧 v18 bundle 不再作为最新输入。
- v19 ZIP：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v19.zip`，SHA-256 `CD2E1299F61C058BB37AB254A13DF5A27A27F43A85389AC87BBB2336DC4AC844`；Go test/vet、Compose 配置、manifest 和敏感文件扫描通过。
- 真实 Pilot 外部门禁没有变化：域名/HTTPS、Tesla 批准、正式签名和真实车辆授权仍未完成。

## 2026-08-22 云端配置候选 APK（v26）

- 已生成绑定 `https://api.jourvolt.com/`、App Link host `auth.jourvolt.com` 的原 MateLink Release 候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260822-pilot-config-v26.apk`。
- `aapt dump badging`：`com.matelink`、`MateLink`、versionName `1.4.2`、versionCode `14`；SHA-256 `B427F37EA42219A95E4878B154D1E8A824206098AF44615BA195D0B9E1B8886A`。
- 该文件未签名，`apksigner verify` 未通过；不能覆盖安装已有正式签名 App。只有取得原 MateLink 正式 keystore 后才能进入设备安装门禁。
- DNS/公网 HTTPS、Tesla 应用批准、App Link 数字资产验证、真实 OAuth/Fleet 和实体手机 UI 仍为未完成事项；本地配置候选不得标记为真实登录通过。

## 2026-08-22 当前工作树复验（v27）

- Android Debug/Release JVM 各 `272` 个测试通过，failures/errors/skips 均为 `0`；Go test/vet 通过。
- Docker smoke 重新通过 `LOCAL MOCK PASS`：1 台车辆、18 条行程、5 条充电和注销回收通过。
- v26 APK 仍未签名，ADB 仍为空；真实 Tesla OAuth/Fleet、正式签名、DNS/HTTPS、App Link 数字资产和实体设备 UI仍未完成。

## 2026-08-22 AVD ADB 通道复核（v28）

- ADB server 已恢复本地监听，但专用 AVD 即使显式指定 `-ports 5554,5555`，仍只报告系统 `Boot completed`，未出现在 `adb devices` 中。
- 诊断启动进程已停止；未删除 AVD、未清理模拟器数据、未安装 APK 或运行 instrumentation。该状态不能升级为页面运行时证据。

## 2026-08-22 本地 Android 分析增量门禁（v29）

- 原统计分析卡保留原有 UI，仅新增“日均驾驶里程”和“行程次数”；日均值由有效总里程/观测驾驶天数派生，数据不足时显示不可用，不生成假零值。
- Debug/Release 各 `272` 个 JVM 用例通过；Release lint `194` 项，`MissingTranslation=0`、无 baseline、0 Error。该数量按多语言覆盖/发布门禁问题记录，不是运行时 Bug 数量。
- `com.matelink` Release 候选未签名；隔离 `com.matelink.test.mock` Debug APK 已 v2 签名。ADB 仍为空，因此没有安装或实体设备 UI 结论。

## 2026-08-22 综合分析证据样本门禁（v30）

- 原统计分析卡不改布局；可用指标的依据行增加样本数，效率、费用和充入/行驶能量比使用配对输入的保守有效样本数。
- Debug/Release 各 `273` 个 JVM 用例通过；Release lint `194` 项，`MissingTranslation=0`、无 baseline、0 Error。该数量按多语言覆盖/发布门禁问题记录，不是运行时 Bug 数量。
- `com.matelink` Release 候选未签名，`com.matelink.test.mock` Debug 候选 v2 签名有效；ADB 仍为空，没有安装或实体设备 UI 结论。

## 2026-08-22 年度报告货币一致性门禁（v31）

- 年度报告历史实现中的固定 `€`/`¥` 已改为当前货币符号；活动页面和 PDF 继续沿用用户设置，不改变页面布局。
- Debug/Release 各 `273` 个 JVM 用例、assembleDebug、assembleRelease、lintRelease 通过；Release lint `194` 项，`MissingTranslation=0`、无 baseline、0 Error。该数量按多语言覆盖/发布门禁问题记录，不是运行时 Bug 数量。
- v31 Release 未签名，Debug Mock v2 签名有效；ADB 仍为空，没有安装或实体设备 UI 结论。

## 2026-08-22 无密钥 Pilot bundle 复验（v32）

- Go `test ./... -count=1`、`go vet ./...` 和 Docker `smoke.ps1` 通过；结果为 `LOCAL MOCK PASS`，1 台车辆、18 条行程、5 条充电和注销回收通过。
- bundle：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v32`；manifest `secrets_included=false`，敏感文件计数 `0`；ZIP SHA-256 `5995469FF438E4CE96B0E9420937A0F8F3DECF95967BEF0D2AFEE07F94ED956B`。
- 真实 Tesla 配置、域名/HTTPS、正式签名、App Link 和公网服务器仍未完成；该 bundle 不包含任何密钥。

## 2026-08-22 本地服务端回归收口（v33）

- 修复开发模式账号注销在 Tesla OAuth 未配置时的空指针路径；真实 Pilot 的 OAuth 撤销 URL 行为不变。
- Go `test ./... -count=1`、`go vet ./...`、Docker `smoke.ps1` 和全 Retrofit 兼容接口巡检通过；新增结果为 `LOCAL COMPATIBILITY PASS`。
- v33 无密钥 bundle 已生成并通过敏感文件扫描：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260822-v33.zip`，SHA-256 `432A12F3B3B3CCC0859D14580473C4B6FF4D5B45AD3E7747EB3872F422CAA964`。
- 外部门禁没有变化：正式 Tesla 配置、受控域名/HTTPS、App Link 数字资产、正式 `com.matelink` 签名和实体设备仍未完成；不得把本地巡检写成真实车辆登录证据。

## 2026-08-23 本地 Android 分析门禁（v34）

- 原统计分析卡新增带证据的充电损耗指标；实现与已有推荐引擎保持同一成对样本规则，不改变真实 Pilot 的 OAuth、域名或服务端门禁。
- Debug/Release 各 `275` 个 JVM 用例通过，失败/错误/跳过均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 和实际 `git diff --check` 问题行数 `0` 通过。
- Release lint `194` 项、0 Error、`MissingTranslation=0`、无 baseline；该数量继续归类为多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- v34 Release 仍未签名，ADB 仍为空；不得覆盖安装到实体手机。真实 Tesla OAuth/Fleet、正式签名、DNS/HTTPS、App Link 数字资产和设备页面验收仍是未完成外部门禁。
- 已另行生成开启 `JOURVOLT_CLOUD_LOGIN=true` 的配置候选 `E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260823-pilot-config-charging-loss-v34.apk`，SHA-256 `C4A3C45420B90A494C793939B01CCEC57173BB589BD245E0E45B48BEDBFC1A97`；该文件只证明构建配置正确，不证明域名、服务端或 Tesla 授权可用。

## 2026-08-23 待机窗口本地门禁（v35）

- 待机算法补上至少 `2` 小时窗口门槛；没有容量或 Telemetry 证据时仍不计算 kWh/W。
- Debug/Release 各 `276` 个 JVM 用例通过，失败/错误/跳过均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 和实际差异检查问题行数 `0` 通过。
- Release lint `194` 项、0 Error、`MissingTranslation=0`、无 baseline；该数量继续归类为多语言覆盖/发布门禁问题，不是运行时 Bug 数量。
- 云登录配置候选：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260823-pilot-config-standby-window-v35.apk`，SHA-256 `2B30E7119B32E7A4A74F32B5012DECEAB1FDC4DE89B0BE92DBE40C182CE28777`；未签名，不能进入实体覆盖安装。
- ADB、正式签名、DNS/HTTPS、Tesla 应用批准、App Link 数字资产和真实单车 OAuth/Fleet 仍未完成。

## 2026-08-23 服务器、域名与 HTTPS 采购门禁

- 技术最小采购为：1 个可备案域名 + 1 台有独立公网 IPv4 的中国大陆 Linux 服务器；`api.<domain>` 与 `auth.<domain>` 可使用同一主域名的子域，不需要为每个入口再买域名。`.com` 与 `.cn` 同时注册属于品牌保护，不是 App 运行条件。
- 阿里云中国大陆服务器用于 App 后台时，必须按阿里云备案流程完成 ICP 备案；网站备案和 App 备案是不同记录，原生 Android App 仍需单独走 App 备案路径。购买大陆备案服务器前应确认账号实名、域名实名认证、包名/正式签名信息和备案主体资料。
- 当前核到的阿里云轻量应用服务器官方活动页中，2 核 4G/40GB/2M 年付显示约 `1733.04 元/年`，不满足 JourVolt 原首年 `600 元`门禁；网上社区文章声称的 `199 元/年`不能替代结算页证据。购买门禁仍为首年不超过 `600 元`、结算页正常续费不超过 `700 元/年`、有公网 IPv4、磁盘不少于 50GB。以实际结算页的产品类型、续费价、地域和流量规则为准；轻量服务器到期会停机，超过保留期可能释放数据。
- 不需要购买付费 SSL。Pilot Compose 已准备 Caddy ACME 入口，可用 Let's Encrypt 免费 DV 证书自动签发和续期；证书私钥只留在服务器，不能提交 Git。公网 HTTPS、DNS、App Link 和 Tesla 回调仍需在正式域名具备后执行。
- 参考：阿里云 App 备案、App 备案快速入门、轻量服务器续费说明；Let's Encrypt Getting Started。活动价格只作预算参考，不作为采购承诺。

## 2026-08-23 手机隔离 Mock 测试包准备（v36）

- 本地 Go/Docker 回归重新通过：`LOCAL MOCK PASS`，1 台模拟车、18 条行程、5 条充电，注销回收通过。
- 已准备实体测试设备专用隔离 Debug APK：`E:\Claude_allow\Download\matelink-test-mock-debug-20260823-phone-reverse-v35.apk`，包名 `com.matelink.test.mock`，SHA-256 `F9E6CD56C7D0FB946262F68945D4C73354A895486458379338B515CD25C8A5C3`。设备出现后只使用 `adb reverse tcp:18090 tcp:18090`，不覆盖正式 MateLink。
- 当前 `adb devices -l` 仍为空；没有安装、卸载、清数据或 instrumentation。正式签名、域名/HTTPS、Tesla 应用批准、App Link 和真实单车 OAuth/Fleet仍是独立外部门禁。

## 2026-08-23 无密钥 Pilot 部署包复建（v37）

- 最新自包含部署目录：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260823-v36`。
- ZIP：`E:\Claude_allow\Download\jourvolt-pilot-bundle-20260823-v36.zip`，SHA-256 `9A4E3B3556AA5AD96D18B5C390B555922F6EAAE401909777A8344CD484D0E5EF`。
- Manifest 明确 `secrets_included=false`；真实敏感文件和 ZIP 敏感条目均为 `0`。未启动服务器、未读取或写入 Tesla 密钥、未修改 DNS。
- Bundle 内 Go test/vet 通过；默认 Compose 与 Pilot Compose 使用仅存在于当前进程的占位值完成结构校验，正式配置仍保持 fail-closed。

## 2026-08-23 部署预检 fail-closed 复核（v38）

- PASS：bundle 内 Go `test ./... -count=1` 与 `go vet ./...` 均通过。
- PASS：使用 `preflight.ps1 -EnvFile .env.example -SkipCompose` 按脚本真实参数运行；示例配置被正确拒绝，未启动服务，也未写入任何正式配置。
- PASS：拒绝原因覆盖缺失 Tesla 配置、示例域名/占位值、缺失 32 字节令牌密钥和缺失正式 App Link 资产；这属于上线前安全门禁的预期结果。
- NOTE：不存在的 `-SkipDocker` 参数未计入证据；预检成功仍需 Jovi 在服务器私密 `.env` 填入真实配置后执行。

## 2026-08-23 电池趋势曲线补强与 v39 门禁

- 原电池趋势卡增加标准化续航曲线；样本按日期聚合并取中位数，至少两个有效日期点才绘制，算法证据边界不变。
- Debug/Release 各 `277` 个 JVM 用例通过，失败/错误/跳过均为 `0`；`assembleDebug`、`assembleRelease`、`lintRelease` 和 `git diff --check` 通过。
- Release lint `194` 项、0 Error、`MissingTranslation=0`、无 baseline；该数量继续按多语言覆盖/发布门禁记录，不是运行时 Bug 数量。
- v39 Release 未签名：`E:\Claude_allow\Download\matelink-1.4.2-release-unsigned-20260823-battery-trend-v39.apk`，SHA-256 `6E580E84C97436885D30075A5A8F6D9FE3A0DE08F4572820BA67261868210F41`。
- v39 隔离 Debug：`E:\Claude_allow\Download\matelink-test-mock-debug-20260823-battery-trend-v39.apk`，包名 `com.matelink.test.mock`，SHA-256 `9316D4A7E89A1A5E6130732E1AA8656E613848A08D3216839F8B19954CAD1794`；设备出现后使用 `adb reverse tcp:18090 tcp:18090`。
- NOT_PERFORMED：ADB 仍为空；没有实体安装或真实 Tesla Pilot。正式签名、服务器/DNS/HTTPS、Tesla 应用批准和 App Link 仍待外部条件。

## 2026-08-23 实体设备隔离包安装验证（v40）

- PASS：serial `6e4fa92f` 的 OnePlus 7 Pro 已被 ADB 识别；本机 Mock 端口 18090 可用，`adb reverse tcp:18090 tcp:18090` 成功。
- PASS：`matelink-test-mock-debug-20260823-battery-trend-v39.apk` 以 `adb install -r` 安装成功；`com.matelink.test.mock` 与正式 `com.matelink` 并存。
- PASS：隔离包显示原 Dashboard、车辆状态和底部导航；当前前台正式包也仍为 `com.matelink/.MainActivity`。
- EVIDENCE_BOUNDARY：本次没有清除数据、卸载或 instrumentation；隔离包沿用既有测试会话，因此不能把该结果写成新鲜登录或真实 Tesla OAuth/Fleet 通过。
- NOT_PERFORMED：公网服务、正式签名、Tesla 应用批准、App Link、真实车辆 OAuth/Fleet 仍未完成。

## 2026-08-23 本地回归与 fail-closed 预检（v41）

- PASS：Go `test ./... -count=1`、`go vet ./...` 和本地 `smoke.ps1` 通过；Mock smoke 返回 1 台车辆、18 条行程、5 条充电，logout/revocation `PASS`。
- PASS：Android Debug/Release 合计 `554` 个 JVM 用例，失败/错误/跳过均为 `0`；构建成功；Release lint `194` 项、0 Error、`MissingTranslation=0`、无 baseline。
- PASS（预期拒绝）：`preflight.ps1 -EnvFile .env.example -SkipCompose` 返回 `PREFLIGHT=FAIL`，拒绝缺失 Tesla client 配置、令牌密钥、正式域名和 `assetlinks.json`；未启动公网服务。
- NEXT_EXTERNAL_GATE：准备真实域名/DNS、服务器私密 `.env`、Tesla 应用批准/凭据、正式 `com.matelink` 签名和 App Link 后，才能进入真实单车 Pilot；任何 Mock/本地证据都不能替代该门禁。

## 2026-08-23 实体统计页数据回归（v42）

- PASS：实体 `com.matelink` 的“统计概览”可以打开并显示历史行程、充电、交流/直流和温度数据；截图已保存到 `E:\Claude_allow\Download\matelink-original-v41-phone-stats.png` 及其下半页截图。
- EVIDENCE_BOUNDARY：费用/距离为 `N/A` 是成本输入不可用的真实状态，不应改成零值；本次是既有自托管数据页面证据，不是 Tesla OAuth/Fleet 证据。

## 2026-08-23 原 MateLink 覆盖升级策略确认（v43）

- PASS：仅删除 `com.matelink.test.mock`，正式 `com.matelink` 保留并可启动；没有清除正式 App 数据。
- REQUIRED_UPGRADE：原签名 keystore → 构建签名 `com.matelink` Release → 验证签名一致 → `adb install -r`；该流程用于保留 Room、DataStore、服务器地址、Token 和历史数据。
- STOP_GATE：未提供原 keystore 时，不安装未签名 Release，也不把隔离 Debug 包作为用户升级包。

## 2026-08-23 原包签名覆盖与迁移修复（v44）

- PASS：修复版 `com.matelink` 使用与手机原包一致的证书签名，`adb install -r` 返回 `Success`；隔离测试包已删除，正式包未卸载。
- PASS：迁移回归测试覆盖“已持久化云模式 + 旧自托管地址”场景，修复后优先恢复 `SELF_HOSTED`；Debug/Release 合计 `556` 个 JVM 用例通过。
- PASS：Release lint `194` 项、0 Error、`MissingTranslation=0`、无 baseline。
- BOUNDARY：原 API 地址和 Token 掩码仍在设置页；连接测试报告旧自托管服务暂时无法访问，实时车辆刷新仍待网络/服务恢复后复验。

## 2026-08-23 局域网 HTTP 与 AMap Release 修复（v45）

- PASS：正式自托管模式恢复可信局域网 HTTP；公网 HTTP 仍由 `UrlSecurity` 拒绝，云端 JourVolt 仍只接受 HTTPS。
- PASS：AMap JNI 反射类保留后，Release mapping 含 `com.autonavi.base.amap.mapcore.ClassTools`；实体冷启动不再触发之前的 AMap native SIGABRT。
- PASS：最终 `com.matelink` 签名 APK 已覆盖安装，手机原 Dashboard、车辆数据和地图恢复可见；测试包不存在。
- PASS：Debug/Release 合计 `556` 个 JVM 用例通过；Release lint `195` 项、0 Error、`MissingTranslation=0`、无 baseline。新增 `InsecureBaseConfiguration` 是可信局域网 HTTP 的静态安全提醒，已保留记录。

## 2026-08-23 公网 DNS/HTTPS Pilot 门禁复核（v46）

- NOT_READY：`api.jourvolt.com` 和 `auth.jourvolt.com` 当前解析到 `198.18.0.x`，属于本机代理常见 fake-IP 保留网段，不能作为真实公网服务器 A 记录证据。
- NOT_READY：对两个 HTTPS 入口执行 `/healthz` 探测超时；当前没有可证明的公网 JourVolt API、OAuth callback 或 App Link。
- PASS：实体设备仍只有正式 `com.matelink`，进程和原 Dashboard 稳定；本轮没有修改 DNS、服务器或 Tesla 凭据。
- NEXT_EXTERNAL_GATE：关闭/绕过 fake-IP DNS 后，用真实公共 DNS 解析到服务器公网 IPv4，再验证 HTTPS、`assetlinks.json` 和 Tesla OAuth callback。

## 2026-08-23 Release 底部导航路由修复（v47）

- PASS：修复 Release 混淆后底栏路由前缀失配；实体设备四个一级入口均已点击验证成功。
- PASS：最终 `com.matelink` 签名 APK 已覆盖安装，原数据、Dashboard 和地图保留；测试包不存在。
- PASS：Debug/Release 合计 `560` 个 JVM 用例通过；lint `195`、0 Error、`MissingTranslation=0`、无 baseline。
