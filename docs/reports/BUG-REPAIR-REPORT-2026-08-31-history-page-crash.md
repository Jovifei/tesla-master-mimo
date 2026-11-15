# MateLink 行程/充电历史页闪退修复记录（2026-08-31）

## 状态

- 源码：已修复，本次主分支同步提交包含此修复。
- 本地门禁：通过。
- ECS：已完成完整匹配服务端源文件同步与 API 容器重建，公网复核通过。
- 真实 Tesla 登录：未在记录过程中输入账号密码，仍需 Jovi 在手机上完成。

## 现象与证据

真机 OnePlus 7 Pro（serial `6e4fa92f`）在打开行程或充电页后进程退出。`logcat -b crash` 在 21:55:02、21:55:07、21:55:51 和 21:57:25 重复报告：

```text
Process: com.matelink
java.lang.IllegalArgumentException: cloud vehicle uid is required
```

Release mapping 将调用链定位为 `VehicleContextRepository.resolve` -> `VehicleContextStore.resolveCar` -> `cloudVehicleStableIdentity`。

## 根因链

1. `fleetProvider.Vehicles` 已从 Tesla Fleet 响应计算稳定的 `providerID`。
2. 构造 API `vehicle` 时遗漏 `VehicleUID`；该字段带 `omitempty`，所以 `/api/v1/cars` 省略了 `vehicle_uid`。
3. Dashboard 只按数字 `car_id` 读取实时状态，因此仍能刷新。
4. 行程、充电和分析页进入本地历史身份隔离链路，空 UID 不能生成稳定身份。
5. `UnifiedHistoryRepository` 原先未将这个预期的“身份暂不可用”状态转换为 `ApiResult.Error`，异常从 UI 协程逃逸并杀进程。

## 修复内容

- Fleet vehicle 映射返回 `VehicleUID: providerID`，不使用 VIN 或数字本地 ID。
- 空云身份统一抛出 `HistoryIdentityUnavailableException`。
- `UnifiedHistoryRepository` 将身份缺失转换为 `CONFIGURATION/history_identity_unavailable`。
- 行程、充电、效率、续航、费用和统计页面显示中英文等待提示；统计进度观察和范围明细读取也安全降级。
- 保持 fail-closed 历史隔离，不因缺失字段自动迁移或复用其它车辆历史。

## 验证门禁

- Go：`go test ./... -count=1`、`go vet ./...`。
- Android：Debug/Release 各 450 个 JVM 测试；AndroidTest 仅编译，不在正式用户手机运行 instrumentation。
- Release：assemble、lint（0 Error、0 MissingTranslation）、原签名 `com.matelink` APK 校验通过。
- 设备：使用 `adb install -r` 覆盖安装后冷启动正常；没有新的 `com.matelink` FATAL。真实历史页面点击仍需真实会话后补证。

## ECS 最小部署命令

以下流程只更新 `jourvolt-staging` 的 API 构建输入，只重建 `jourvolt-dev-api`，使用 `--no-deps` 防止重启 PostgreSQL；不触碰 `star-photo`。执行前先核对目标目录和当前健康状态。

本次实际执行时发现 ECS 目录比当前源码少了 Telemetry 相关生产文件，因此不能只上传单个 `fleet_provider.go`。正确做法是先把本地 `deploy/jourvolt-dev-mock` 的生产 Go 源、`go.mod`、`go.sum`、`Dockerfile` 和 `.dockerignore` 同步到受控临时目录，再原子替换并构建；`.env` 始终留在 ECS，不上传、不读取。

Windows 上传示例（本次已执行，目标文件仅为上述构建文件）：

```powershell
$key = 'C:\Users\Admin\.ssh\joviluma_jourvolt_deploy_ed25519'
$root = 'E:\project\tesla_master\app_mimo\deploy\jourvolt-dev-mock'
$files = @(Get-ChildItem -LiteralPath $root -File -Filter '*.go' |
  Sort-Object Name | Select-Object -ExpandProperty FullName)
$files += "$root\go.mod", "$root\go.sum", "$root\Dockerfile", "$root\.dockerignore"
ssh -i $key jourvolt@120.55.64.11 'mkdir -m 700 /home/jourvolt/jourvolt-staging/.deploy-source-sync-20260831'
scp -i $key @files `
  jourvolt@120.55.64.11:/home/jourvolt/jourvolt-staging/.deploy-source-sync-20260831/
```

上传后在 ECS 端执行：

```bash
cd /home/jourvolt/jourvolt-staging
sha256sum .deploy-source-sync-20260831/fleet_provider.go
curl -fsS --max-time 10 http://127.0.0.1:18090/healthz

# 备份现有构建输入，再将临时目录中的匹配源文件移入；不要触碰 .env
mkdir -m 700 .rollback-20260831-source-sync
for f in $(find .deploy-source-sync-20260831 -maxdepth 1 -type f -printf '%f\n'); do
  test ! -e ".rollback-20260831-source-sync/$f" || exit 1
  test ! -e "$f" || cp -p "$f" ".rollback-20260831-source-sync/$f"
  mv ".deploy-source-sync-20260831/$f" "$f"
done
rmdir .deploy-source-sync-20260831
docker compose -f docker-compose.yml build jourvolt-dev-api
docker compose -f docker-compose.yml up -d --no-deps jourvolt-dev-api

docker compose -f docker-compose.yml ps
curl -fsS --max-time 10 http://127.0.0.1:18090/healthz
curl -fsS --max-time 15 https://api.teslalink.joviluma.com/healthz
```

必须确认 `/healthz` 仍为 `mode=fleet`、`persistence=postgres`、`status=ok`；API 重建不应导致 PostgreSQL 重启。没有有效用户会话时，不对车辆接口臆测成功；真实车辆数据要在 Jovi 完成 Tesla 授权后验证。

## 本次实际部署结果

- 远端原源码仅有旧版 Go 文件；第一次单文件构建被安全地挡在容器替换前，旧 API 仍健康运行。旧 `fleet_provider.go` 已保存在远端回滚目录。
- 完整匹配源同步与 Docker build 通过；新 API 镜像为 `sha256:650a28ad2a23b0a7223fb803be00308867ca51480eccef9f7b9c3f7b8b02d9f7`。
- 内部与公网 `/healthz`、`/readyz` 均返回 `mode=fleet`、`persistence=postgres`、`status=ok`；未授权 `/api/v1/cars` 与 `/api/v1/cars/1/data-readiness` 均返回 `401`。
- 正确的 `GET /v1/auth/tesla/start` 在公网返回 `200`；只检查元数据时确认授权 host/path 为 `auth.tesla.cn/oauth2/v3/authorize`，回调 host/path 为 `api.teslalink.joviluma.com/v1/auth/tesla/callback`。此前 `POST` 得到 `404` 是方法错误，不是服务故障。
- `jourvolt-staging-jourvolt-dev-api-1`、PostgreSQL 和两个 `star-photo` 容器均保持运行；API 端口仍只绑定 `127.0.0.1:18090`。
- 真实 Tesla 登录、车辆 UID 的真实响应、行程/充电页面真机点击仍未执行；不能以健康检查替代真实 Pilot。

## 后续门禁

- [x] ECS 部署前后健康与容器边界复核。
- [ ] Jovi 在已安装的同签名 APK 中完成 Tesla 官方登录。
- [ ] 依次点击 Dashboard、行程、充电、统计，读取 `logcat`，确认无 FATAL。
- [ ] 记录真实 `vehicle_uid` 到 API/Android 解码链路（只记录是否存在，不记录 VIN、令牌或位置）。
- [ ] 以上完成后，另行授权 Git commit/push；本修复当前不自动提交。
