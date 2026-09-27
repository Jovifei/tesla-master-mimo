# 家用主机 + Docker 部署模板

这套模板用于在家用主机、NAS、迷你电脑或长期在线 Windows 主机上部署：

```text
TeslaMate + PostgreSQL + Mosquitto + Grafana + TeslaMateApi
```

MateLink App 最终填写的是 `TeslaMateApi` 地址，不是 Grafana，也不是 TeslaMate Web UI。

## 1. 当前 Docker 状态

本机 Docker CLI 和 Docker Compose 已安装。若终端提示无法连接 `dockerDesktopLinuxEngine`，先启动 Docker Desktop。

检查命令：

```powershell
docker ps
docker compose version
```

## 2. 初始化配置

进入模板目录：

```powershell
cd E:\project\tesla_master\app_mimo\deploy\teslamate-home-docker
```

复制示例环境文件：

```powershell
Copy-Item .env.example .env
```

打开 `.env`，至少改这三项：

```text
TESLAMATE_ENCRYPTION_KEY=一段很长的随机字符串
TESLAMATE_DB_PASSWORD=强数据库密码
MATE_LINK_API_TOKEN=MateLink 访问 API 用的本地 token
```

不要把 `.env` 提交到 Git。仓库已忽略 `deploy/**/.env`。

## 3. 启动服务

```powershell
docker compose pull
docker compose up -d
docker compose ps
```

启动后默认端口：

| 服务 | 地址 | 用途 |
| --- | --- | --- |
| TeslaMate | `http://localhost:4000` | 首次登录 Tesla、采集车辆数据 |
| Grafana | `http://localhost:3000` | 仪表盘查看，不填进 MateLink |
| TeslaMateApi | `http://localhost:18080` | MateLink 要填写的 API 根地址 |

## 4. 首次使用顺序

1. 打开 `http://localhost:4000`
2. 按 TeslaMate 提示完成 Tesla 登录授权
3. 等 TeslaMate 开始采集车辆数据
4. 打开 `http://localhost:18080/api/v1/cars`
5. 如果能看到车辆 JSON，说明 API 可供 MateLink 使用

Tesla 登录授权由 TeslaMate Web 页面处理，不在 MateLink 里实现。MateLink 不保存 Tesla 账号密码，也不直接登录 Tesla；它只读取 TeslaMateApi 暴露出来的数据。

## 5. MateLink 里怎么填

如果手机和这台主机在同一局域网，先查主机局域网 IP，例如 `192.168.1.100`。

MateLink Settings 里填写：

```text
API 根地址: http://192.168.1.100:18080
API Token: .env 里的 MATE_LINK_API_TOKEN
```

不要填写：

```text
http://192.168.1.100:18080/api/v1
http://192.168.1.100:4000
http://192.168.1.100:3000
```

## 8. 个人 TeslaMate 长期云端归档（可选）

归档桥接是独立的个人数据源服务，不参与 Tesla 登录，也不修改 TeslaMate。它只用 PostgreSQL 只读账号读取已完成行程、路线点、充电采样、地址和费用，再按来源实例/车辆身份分批上传到 MateLink 云端；断点只在云端返回成功后推进。

先在 TeslaMate 数据库创建最小只读账号（密码不要提交到 Git）：

```sql
CREATE ROLE matelink_archive_reader LOGIN PASSWORD 'replace-with-a-strong-reader-password';
GRANT CONNECT ON DATABASE teslamate TO matelink_archive_reader;
GRANT USAGE ON SCHEMA public TO matelink_archive_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO matelink_archive_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO matelink_archive_reader;
```

在 `.env` 中填写云端绑定产生的 `ARCHIVE_TOKEN`、目标 `ARCHIVE_VEHICLE_ID`、固定的 `ARCHIVE_SOURCE_INSTANCE_ID` 和已核验的 `ARCHIVE_SOURCE_VEHICLE_ID`，并设置 `ARCHIVE_DATABASE_URL` 使用上述只读账号。不要用车型、昵称或数字车 ID 自动绑定来源。

启动归档桥接：

```powershell
docker compose --profile archive up -d matelink-archive-bridge
docker compose logs -f matelink-archive-bridge
```

没有云端绑定凭据时不要启动该 profile；空 token 不会被当作成功，桥接失败也不会推进断点。TeslaMate 本地读取和手机自托管模式不依赖这个服务。

## 6. 外网访问建议

家用主机不建议把裸 HTTP 直接暴露到公网。

优先选择：

- Tailscale
- VPN
- Cloudflare Tunnel
- HTTPS 反向代理

外网访问时，MateLink 仍然填写 API 根地址，例如：

```text
https://teslamate-api.example.com
```

不要追加 `/api/v1`。

本模板本地验证时，TeslaMateApi 的 `/api/v1/cars` 读接口即使不带 token 也会返回响应。因此不要把 `MATE_LINK_API_TOKEN` 当成公网唯一安全防线。要给手机外网访问，优先用 Tailscale/VPN，或在 HTTPS 反向代理上加 Basic Auth、访问控制、IP 限制等保护。

## 7. 常见问题

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| `docker ps` 连不上 Docker | Docker Desktop 没完全启动 | 打开 Docker Desktop，等 engine 变绿 |
| TeslaMateApi 容器反复重启 | 数据库密码或加密 Key 不一致 | 检查 `.env` 和 compose 环境变量 |
| App 提示 401 | API Token 不一致 | 确认 App 里填的是 `MATE_LINK_API_TOKEN` |
| 浏览器返回网页而不是 JSON | 访问成了 Web UI 或 Grafana | 用 `http://主机IP:18080/api/v1/cars` 测 API |
| App 连不上局域网 IP | 手机和主机不在同一网络 | 检查 Wi-Fi、VPN、防火墙 |
