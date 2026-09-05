# MateLink / JourVolt / TeslaLink 项目长期记忆

## 项目身份
- 仓库：`https://github.com/Jovifei/tesla-master-mimo.git`
- 本地 workspace：`E:\project\tesla_master\app_mimo`（注意：`.git` 是 submodule 指针，真实 gitdir 在父仓库 `.git/modules/app_mimo`）
- App 正式包名：`com.matelink`；debug 测试包名：`com.matelink.test.mock`

## 关键公网地址（构建正式 release 包必需，release guard 强制校验）
- `JOURVOLT_API_BASE_URL` = `https://api.teslalink.joviluma.com/`
- `JOURVOLT_AUTH_HOST` = `auth.teslalink.joviluma.com`（guard 强制，App Link 主机须匹配生产 assetlinks）
- `MATELINK_PUBLIC_INFO_BASE_URL` = `https://auth.teslalink.joviluma.com`（guard 强制，唯一正确值）
- 域名解析：`teslalink.joviluma.com` / `auth.` / `api.` 均 → `120.55.64.11`（阿里云，域名 `joviluma.com`，备案 苏ICP备2026062639号-1）

## Release 签名配置（覆盖安装手机正式包用，与手机现有签名一致）
- 签名配置文件：`E:\Claude_allow\matelink-release.properties`（仓库外，四键齐全）
  - `storeFile=C:/Users/Admin/.android/debug.keystore`
  - `storePassword=android`
  - `keyAlias=androiddebugkey`
  - `keyPassword=android`
- 这是 **Android 默认调试证书**（非正式发布 keystore）。指纹 `9AB144E8...81FC774`（DN `CN=Android Debug`，SHA-1 `cfd9c28c...`），与手机现有 `com.matelink` 签名一致 → 可覆盖安装、不丢 Room 历史。
- 取舍（长期决策）：换正式 keystore 需卸载重装（丢手机本地历史），上架市场时再换官方 keystore。
- 构建命令：`build-pilot-apk.ps1`（强制传三个公网地址 + 可选 `-SigningPropertiesPath`，guard 拒绝 localhost/内网/HTTP）。

## 版本发布台账（重要，每次发布必须追加）
- 台账文档：`E:\project\tesla_master\app_mimo\docs\ANDROID-RELEASE-LOG.md`（倒序，最新在最上；只记正式包 `com.matelink`）
- APK 归档：`E:\Claude_allow\matelink-apk-archive\`（仓库外，命名 `matelink-<versionName>-build<versionCode>-<YYYYMMDD>-release.apk`）
- **当前线上版本（手机已装）**：**1.4.5 / build 17**，2026-09-03 12:09 发布
  - SHA-256 `6389840c81542d3dc2a90f21acd54b6727df042e58007c246f3cdd1dc3837c23`
  - 大小 62,331,759 字节；来源分支 `fix/20260902-android-state-and-ux-reliability`
  - 远端 HEAD = `1fca1c428d02ac49555cb3da864c19ab3024d867`（version bump 提交，PR #4 Draft）
  - 与 1.4.4/build 16（SHA `6ea2b47a...`）**产品代码完全相同**，17 仅递增版本号以便设备端辨识
- 版本递增约定：每次发版（哪怕只改配置）都应递增 versionCode，避免同版本号无法判断新旧。

## 构建/安装排错要点
- **debug 包 ≠ 正式包**：debug buildType 带 `applicationIdSuffix = ".test.mock"`，产物包名 `com.matelink.test.mock`，且走 mock 后端。**要装正式 `com.matelink` 必须走 release 构建**（`build-pilot-apk.ps1`）。
- 曾踩坑：`adb install -r` debug 包报 Success，但正式包 `com.matelink` 纹丝不动 —— 实际装的是测试包，两包名不同互不覆盖。
- adb / apksigner 是 Windows 原生程序，**不认 Git Bash 的 `/tmp`、`/c/...` 路径**，必须传 Windows 格式（`C:/Users/...`）。
- 构建需 `android/local.properties` 含 `sdk.dir=C:/Users/Admin/AppData/Local/Android/Sdk`（正斜杠，反斜杠会报"文件名、目录名或卷标语法不正确"）。
- 验证安装是否真的生效：比对 `lastUpdateTime` 是否刷新 + base.apk 大小/SHA-256 是否等于产物，不能只看 install 的 `Success`。

## 部署/服务器关键信息
- SSH 用户：`jourvolt`；**必用专用密钥** `-i ~/.ssh/joviluma_jourvolt_deploy_ed25519`（默认 id_ed25519 会 Permission denied）
- 服务器 IP：`120.55.64.11`；ECS 采购门禁：首年 ≤600 元（当前阿里云 2C4G 约 1733 元/年，未满足）
- Go API 容器：`jourvolt-staging-jourvolt-dev-api-1`，绑 `127.0.0.1:18090`；postgres：`jourvolt-staging-jourvolt-postgres-1`（postgres:16-alpine）
- 环境配置：`/home/jourvolt/jourvolt-staging/.env`
- TeslaMate 自托管：`cd /home/jourvolt/matelink-selfhost && docker compose -f docker-compose.selfhost.yml start`（必须带 `-f`）
- fleet-telemetry 桥：容器 `jourvolt-fleet-telemetry`，端口 4443（mTLS）；mosquitto 在 telemetry 内网，客户端 ID `jourvolt-telemetry-consumer`
- 环境监控：`curl -s https://api.teslalink.joviluma.com/healthz` 应返回 `"mode":"fleet"`
- APK 分发下载页：`https://auth.teslalink.joviluma.com/download/`

## 关键架构事实
- App **从不直连 Tesla**：唯一 Retrofit 接口 `TeslamateApi`（`api/matelink/v1/*` + `api/v1/*`），`TeslamateRepository` L151 按 `ConnectionMode` 只换 baseUrl（TESLA_CLOUD→`BuildConfig.JOURVOLT_API_BASE_URL`，SELF_HOSTED→用户自填），请求/解析两模式一致。
- 后端历史导入端点：`POST /api/v1/cars/{carId}/history/import`，写 `jourvolt_telemetry_sessions`（`source='local_import'`），滚动保留最新两个自然日（按账户隔离）。

## 本环境 git 顽疾（已多次记录，务必规避）
- `git stash push -u` 会触发 SIGTERM，随后环境的清理机制会**清空 submodule 的 `objects/`（commit 对象）和整个 `refs/` 目录**，导致 `fatal: not a git repository` / `bad object HEAD`。
- 规避：需要干净代码时在 `/tmp` 做 `git clone --depth 1 --branch <分支>`，绕开 workspace 坏仓库；改动用文件备份到 `/tmp` 兜底。

## 待办 / 卡点
- 正式 release 覆盖安装手机正式包 `com.matelink`：三要素已齐（签名 properties + 三个公网地址），可执行 `build-pilot-apk.ps1` 构建并 `adb install`。
- 真机行为验证矩阵（PR #4 收口前）：Cloud↔Self-hosted 强停重启、token 过期+断网、重新授权取消/成功、切车竞态、MQTT live→recent→fallback、真实 AC/DC 充电、第二次启动配置/语言保持。
- 历史同步功能改动（本地/云上传、按账户保留最新两自然日）尚未提交/推送（工作区坏仓库 + 混杂改动待处理）。
