# JourVolt 本机开发服务

该目录包含独立 Go JourVolt API 与 PostgreSQL。默认 Compose 仍运行 `MockTeslaProvider`，用于原 Android App 的本机回归；当 Tesla 中国应用配置完整时，同一服务会启用官方 OAuth 和 `FleetTeslaProvider`。

无论哪种模式，JourVolt 都不提供 Tesla 密码输入框，也不接收 Tesla 账号、密码或 MFA。真实登录凭据只输入 Tesla 官方授权页面。

## 默认 Mock 调试

```powershell
cd E:\project\tesla_master\app_mimo\deploy\jourvolt-dev-mock
docker compose up --build -d
Invoke-RestMethod http://127.0.0.1:18090/healthz
Invoke-RestMethod http://127.0.0.1:18090/readyz
.\smoke.ps1
```

默认 health 的 `mode` 为 `mock_only`。服务只绑定宿主机回环地址，固定模拟车仅用于 Debug 证据。

`smoke.ps1` 只使用本地 Mock 账号，验证车辆列表、兼容快照、18 条行程、5 条充电和注销后的 `401`；输出只能记为 `LOCAL MOCK PASS`，不会打印 access/refresh token。

Compose 默认同时设置 `JOURVOLT_ENABLE_MOCK_HISTORY=true`，提供 18 条行程、5 条充电和对应详情。该数据只对固定 `mock-user` 生效，用于验证原 MateLink 的同步、Room、统计和建议页面；其他用户不能读取。health 会显式返回 `mock_history=true`，能力端点会声明 `history_fixture`。

需要回归云端尚未采集历史时的空状态，可临时关闭并重建：

```powershell
$env:JOURVOLT_ENABLE_MOCK_HISTORY='false'
docker compose up --build -d
```

这套 fixture 只能记为 `LOCAL MOCK HISTORY PASS`，不得作为 Tesla OAuth、Fleet API 或真实车辆证据。

## 服务器自包含 Pilot bundle

如果服务器不准备按完整仓库路径部署，可以从 Windows 工作树生成一个不含密钥的自包含目录。生成器会把 `web_matelink/public` 中的条款、隐私页、样式和相关静态文件复制到 bundle 的 `public/`，并将 bundle 的 `JOURVOLT_PUBLIC_ROOT` 固定为 `./public`。

```powershell
cd E:\project\tesla_master\app_mimo\deploy\jourvolt-dev-mock
.\package-pilot.ps1 -OutputDirectory E:\Claude_allow\Download\jourvolt-pilot-bundle
```

生成器拒绝覆盖已存在目录、拒绝在仓库内输出，并在源目录存在 `.env` 时停止；bundle manifest 会明确记录 `secrets_included=false`。将生成的目录安全复制到服务器后，在 bundle 根目录执行：

```bash
cp .env.example .env
# 只在服务器私密 .env 中填写正式值
bash ./preflight.sh --env-file .env --verify-dns --verify-app-link
bash ./pilot-up.sh --env-file .env --verify-app-link
```

bundle 仍必须先取得正式域名、Tesla 应用批准、HTTPS callback、签名证书指纹和私密配置；打包成功不等于真实 Tesla Pilot 通过。

Android Emulator 默认使用 `http://10.0.2.2:18090/`。实体测试设备应使用 ADB 反向端口，不开放局域网端口：

```powershell
adb -s <测试设备序列号> reverse tcp:18090 tcp:18090
cd E:\project\tesla_master\app_mimo\android
.\gradlew.bat :app:assembleDebug `
  -PJOURVOLT_MOCK_BASE_URL=http://127.0.0.1:18090/
adb -s <测试设备序列号> install -r .\app\build\outputs\apk\debug\app-debug.apk
```

不要在保存用户数据的手机上运行 instrumentation。

## 真实 Tesla 配置模式

代码已实现官方授权码流程、OIDC `sub`/nonce 校验、一次性 App ticket、加密 Tesla token、事务内 refresh token 轮换，以及只读车辆列表和 `vehicle_data` 映射。真实模式需要先获得 Tesla 批准并提供公网 HTTPS 回调。

1. 复制 `.env.example` 为已被 Git 忽略的 `.env`。
2. 只在本机私密文件中填写：
   - `DATABASE_URL` 与 `POSTGRES_PASSWORD`
   - `TESLA_CLIENT_ID`
   - `TESLA_CLIENT_SECRET`
   - `TESLA_REDIRECT_URI`
   - `JOURVOLT_APP_LINK_URI`
   - `JOURVOLT_API_DOMAIN`、`JOURVOLT_APP_DOMAIN` 与 `JOURVOLT_ACME_EMAIL`
   - `JOURVOLT_TOKEN_KEY_BASE64`（标准 Base64 编码的随机 32 字节）
3. 将 `JOURVOLT_ENABLE_MOCK=false`。
4. Android Debug Pilot 使用：

```powershell
.\gradlew.bat :app:assembleDebug `
  -PJOURVOLT_CLOUD_LOGIN=true `
  -PJOURVOLT_DEBUG_API_BASE_URL=https://<JourVolt API 域名>/ `
  -PJOURVOLT_AUTH_HOST=<已验证 App Link 域名>
```

Tesla 配置五项必须同时存在；缺一项服务会拒绝启动。所有值都不得粘贴到聊天、Git、日志或 Obsidian。

真实模式启用前还必须完成：Tesla 中国开发者应用审核、域名公钥托管、伙伴注册、HTTPS 回调，以及 Android `assetlinks.json` 与正式签名绑定。API 域名必须对应 `TESLA_REDIRECT_URI`，App Link 域名必须对应 `JOURVOLT_APP_LINK_URI`。未完成时 `/v1/auth/tesla/start` 明确返回 `503 oauth_not_configured`。

服务端配置还会在启动时 fail-closed：`TESLA_REDIRECT_URI` 必须是 `/v1/auth/tesla/callback`，`JOURVOLT_APP_LINK_URI` 必须是 `/oauth/callback`；两个地址不能含用户信息、query、fragment 或非 443 端口。

Android 正式包仍需由签名持有人在本机签名。仓库提供 `android/keystore.properties.example` 字段模板和 `android/build-pilot-apk.ps1 -SigningPropertiesPath` 入口；不要把 properties、keystore、密码或正式证书指纹写入 Git 或聊天。未传 `-SigningPropertiesPath` 时脚本明确输出 `UNSIGNED_RELEASE`，不会误报可发布。

## Pilot 配置预检

### 可选 Caddy HTTPS 入口

拿到受控域名后，可启用 Pilot Compose 的 edge profile。Caddy 负责公网 80/443、自动 HTTPS、App Link 文件、用户协议/隐私政策静态页面和到 API 的反向代理；API 仍不直接暴露公网。法律页面来自仓库的 `web_matelink/public/terms/` 与 `web_matelink/public/privacy/`，正式运营前必须补齐运营主体、联系渠道和 SDK 清单后再开放 Tesla 云授权。

~~~powershell
New-Item -ItemType Directory -Force .\public\.well-known | Out-Null
Copy-Item .\assetlinks.json.example .\public\.well-known\assetlinks.json
# 将正式 com.matelink SHA-256 指纹写入 public\.well-known\assetlinks.json
# 在 .env 中填写 JOURVOLT_API_DOMAIN、JOURVOLT_APP_DOMAIN 和 JOURVOLT_ACME_EMAIL
docker compose --profile edge -f docker-compose.pilot.example.yml up --build -d
~~~

也可以用脚本校验并生成正式关联文件；`-WhatIf` 只预览，不写文件：

```powershell
.\write-assetlinks.ps1 -Sha256CertificateFingerprint '<正式签名证书 SHA-256>' -WhatIf
.\write-assetlinks.ps1 -Sha256CertificateFingerprint '<正式签名证书 SHA-256>'
```

Linux 服务器可使用等价入口，不需要安装 PowerShell：

```bash
bash ./write-assetlinks.sh --fingerprint '<正式签名证书 SHA-256>' --what-if
bash ./write-assetlinks.sh --fingerprint '<正式签名证书 SHA-256>'
```

指纹必须是正式 `com.matelink` 签名证书的 SHA-256，不是 APK 文件哈希，也不能使用 Debug/Mock 签名。

启用前必须先运行 preflight.ps1；缺失或占位的正式 `assetlinks.json`、示例域名和未完成的 Tesla 配置不得进入公网入口。预检现在会在启动前检查本地关联文件。

真实单车 Pilot 使用独立的 `docker-compose.pilot.example.yml`，默认关闭 Mock、历史 fixture 和公网直出；API 只绑定回环地址，外部 HTTPS 反向代理由部署方单独配置。

```powershell
cd E:\project\tesla_master\app_mimo\deploy\jourvolt-dev-mock
Copy-Item .env.example .env
# 仅在本机私密 .env 中填写值，不要把值发到聊天或 Git
.\preflight.ps1 -SkipCompose
docker compose -f docker-compose.pilot.example.yml config --quiet
docker compose -f docker-compose.pilot.example.yml up --build -d
```

拿到正式域名、Tesla 配置和签名指纹后，可用一条命令执行同一套预检、Compose 校验、edge HTTPS 启动和容器内 readiness 检查：

```powershell
.\pilot-up.ps1 -VerifyAppLink
```

该脚本默认启动 Caddy edge profile，并在启动前检查两个入口域名的 A/AAAA DNS；`-NoEdge` 只适用于尚未接公网入口的内部检查，`-SkipBuild` 仅复用已有镜像。预检失败时不会启动任何服务，且不会打印密钥。

若公网 HTTPS 和 App Link 已就绪，可额外验证正式包关联文件：

```powershell
.\preflight.ps1 -VerifyAppLink
```

预检只输出变量名和失败原因，不输出任何密钥值；同时检查本地法律页面是否存在。启用 `-VerifyAppLink` 时还会从公网检查 `assetlinks.json`、`/terms/` 和 `/privacy/`。`assetlinks.json.example` 只允许替换正式 `com.matelink` 签名指纹；不能把 `com.matelink.test.mock` 作为正式回调目标。

Linux 服务器可直接使用等价的 Bash 入口，不需要安装 PowerShell：

```bash
bash ./preflight.sh --env-file /srv/jourvolt/.env --verify-dns --verify-app-link
bash ./pilot-up.sh --env-file /srv/jourvolt/.env --verify-app-link
```

`pilot-up.sh` 默认启用 Caddy edge profile；内部检查可显式使用 `--no-edge`。两个 Bash 入口与 PowerShell 入口都在预检失败时停止，不打印密钥，也不启动服务。

## 验证

```powershell
go test ./...
go vet ./...
docker compose config
```

当前本机验证覆盖 OAuth state/ticket 重放、并发 Tesla token 单次轮换、401 后重试、429 映射、用户内车辆 ID、Mock Dashboard 合同、空历史、显式历史 fixture 与跨用户隔离、JourVolt session 轮换和退出失效。它不等于真实 Tesla Pilot。

## 加密数据库备份与恢复

真实 Pilot 运行时，备份密钥必须放在备份目录之外。Linux 主机需要安装 Docker Compose 和 `age`，并让 Pilot API/数据库已经运行。

```bash
export JOURVOLT_PILOT_ENV_FILE=/srv/jourvolt/.env
export JOURVOLT_BACKUP_DIR=/srv/jourvolt-backups
export JOURVOLT_BACKUP_AGE_RECIPIENT='age1...'
bash ./backup-db.sh
```

`backup-db.sh` 从 PostgreSQL 容器导出 custom-format dump，再用 age 公钥加密；输出目录权限为 `700`，备份文件权限为 `600`，不会输出数据库密码或私钥。若设置 `JOURVOLT_BACKUP_RCLONE_REMOTE`，脚本会使用已配置的 `rclone` remote 上传加密文件；上传失败会使任务失败。生产 systemd 模板使用 `--require-upload`，没有对象存储配置时不会把“只落本机”误报成完整备份。对象存储应使用另一家中国境内云并配置服务端生命周期，保留7个日备份和4个周备份；rclone 配置文件和 age 私钥均不能放入备份目录。

备份脚本默认不删除历史文件。日任务可显式使用 `bash ./backup-db.sh --prune` 保留最近7个日备份；周任务使用 `bash ./backup-db.sh --weekly --prune` 保留最近4个周备份。生产模板额外使用 `--require-upload`。`--prune` 是显式删除门槛，不会由普通备份调用隐式触发。

恢复会覆盖数据库，只能在维护窗口显式执行：

```bash
export JOURVOLT_BACKUP_AGE_IDENTITY=/srv/jourvolt-secrets/age-identity.txt
export JOURVOLT_RESTORE_CONFIRM=I_UNDERSTAND_DATABASE_OVERWRITE
bash ./restore-db.sh /srv/jourvolt-backups/jourvolt-postgres-<timestamp>.dump.age
```

恢复脚本会拒绝位于备份目录内的 age 私钥，不会自动运行；本机本轮只验证脚本语法，没有执行恢复、删除或覆盖任何数据库。

### Linux systemd 定时备份模板

`systemd/` 提供每日和每周两个 systemd service/timer 模板。它们默认以低权限 `jourvolt` 用户运行，通过 `docker` 组访问 Docker socket；只允许写入 `/srv/jourvolt-backups`，不会把 age 私钥放入服务环境。定时器使用服务器本地时区，建议先将 Linux 主机时区设置为 `Asia/Shanghai`。

在目标服务器上完成 Pilot 启动并确认 `age`、`rclone` 已安装后，先为低权限 `jourvolt` 用户配置只读的 `/etc/jourvolt/rclone.conf`（远端指向另一家中国境内对象存储），再由管理员执行以下安装步骤；这些命令不会在开发机自动执行：

```bash
sudo useradd --system --home-dir /srv/jourvolt --shell /usr/sbin/nologin jourvolt || true
sudo usermod --append --groups docker jourvolt
sudo install --directory --owner jourvolt --group docker --mode 0750 /srv/jourvolt-backups
sudo install --directory --owner root --group jourvolt --mode 0750 /etc/jourvolt
sudo install --owner root --group jourvolt --mode 0640 /secure/source/rclone.conf /etc/jourvolt/rclone.conf
sudo install --owner root --group jourvolt --mode 0640 systemd/backup.env.example /etc/jourvolt/backup.env
sudo install --owner root --group root --mode 0644 systemd/jourvolt-backup-daily.service /etc/systemd/system/jourvolt-backup-daily.service
sudo install --owner root --group root --mode 0644 systemd/jourvolt-backup-daily.timer /etc/systemd/system/jourvolt-backup-daily.timer
sudo install --owner root --group root --mode 0644 systemd/jourvolt-backup-weekly.service /etc/systemd/system/jourvolt-backup-weekly.service
sudo install --owner root --group root --mode 0644 systemd/jourvolt-backup-weekly.timer /etc/systemd/system/jourvolt-backup-weekly.timer
sudoedit /etc/jourvolt/backup.env
sudo systemctl daemon-reload
sudo systemctl enable --now jourvolt-backup-daily.timer jourvolt-backup-weekly.timer
systemctl list-timers 'jourvolt-backup-*'
```

将 `/etc/jourvolt/backup.env` 中的公钥和路径改为真实值后，先手工执行一次 service，再检查备份目录和日志：

```bash
sudo systemctl start jourvolt-backup-daily.service
sudo systemctl status jourvolt-backup-daily.service --no-pager
sudo journalctl -u jourvolt-backup-daily.service -n 50 --no-pager
sudo find /srv/jourvolt-backups -maxdepth 1 -type f -name '*.dump.age' -printf '%f %m\n'
```

定时模板会强制执行加密归档上传；对象存储生命周期删除和恢复演练仍是独立门禁。正式启用前必须确认 `/srv/jourvolt/.env`、`/etc/jourvolt/rclone.conf` 仅对 root 和 `jourvolt` 可读、`age` 私钥位于备份目录之外，并完成一次隔离数据库恢复验证。上面的 `install` 命令要求管理员先在目标机创建并审查 `/etc/jourvolt/rclone.conf`，不会从仓库复制凭据。
