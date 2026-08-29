# JourVolt ECS Tesla Secret 填值指引（给 Jovi）

> 目标读者：Jovi（唯一的 secret 操作人）。
> 本指引只包含命令与键名，**不含任何真实 secret 值**。
> 服务端其余配置已由工程师就绪；只剩 `TESLA_CLIENT_ID`、`TESLA_CLIENT_SECRET`
> 两个值必须由你亲手填入服务器私密 `.env`。

## 现状（工程师已完成的边界）

- ECS `120.55.64.11` 上 `/home/jourvolt/jourvolt-staging/.env` 已写入除两个
  Tesla secret 外的全部真实值（域名、redirect、ACME 邮箱、DATABASE_URL、
  32 字节 `JOURVOLT_TOKEN_KEY_BASE64`），权限 `600`，属主 `jourvolt`。
- `docker-compose.pilot.ecs.yml` 与 `.env.ecs.example` 已随包就位。
- `preflight.sh` 当前结果为 `PREFLIGHT=FAIL`，且**失败项应只有
  `Missing TESLA_CLIENT_ID` 与 `Missing TESLA_CLIENT_SECRET` 两条**。
  如果你看到的失败项多于这两条，先停下并把完整输出发给工程师，不要继续。

## 第 1 步：填入两个 Tesla secret

```bash
ssh jourvolt@120.55.64.11          # 用你的常用方式登录即可
cd /home/jourvolt/jourvolt-staging

# 只编辑这两个键，其他行保持不变：
#   TESLA_CLIENT_ID=<Tesla 开发者控制台的 Client ID>
#   TESLA_CLIENT_SECRET=<Tesla 开发者控制台的 Client Secret>
nano .env

# 确认权限（应显示 -rw------- jourvolt jourvolt）：
chmod 600 .env
ls -l .env

# 自检：确认两个键非空、且没有多打引号或空格（只看键名与长度，不打印值）：
awk -F= '/^TESLA_CLIENT_ID=|^TESLA_CLIENT_SECRET=/ {print $1 " -> " length(substr($0, index($0,"=")+1)) " chars"}' .env
```

注意事项：

- 值本身**不要加引号**（`.env` 按裸值解析，引号会被当成值的一部分）。
- `TESLA_REDIRECT_URI` 必须与 Tesla 开发者控制台登记的 callback
  **逐字符一致**，当前已写好为
  `https://api.teslalink.joviluma.com/v1/auth/tesla/callback`；
  如果你改过控制台登记值，这里也要同步改。
- secret 不要粘贴到聊天、Git、日志或 Obsidian。

## 第 2 步：预检

```bash
cd /home/jourvolt/jourvolt-staging
bash ./preflight.sh --env-file .env --verify-dns --verify-app-link
```

期望输出：`PREFLIGHT=PASS`（脚本不打印任何密钥值）。
仍为 `FAIL` 时按输出逐条处理；涉及非 Tesla 项的失败先反馈给工程师。

## 第 3 步：以 pilot 模式重启 API（切到 fleet）

```bash
cd /home/jourvolt/jourvolt-staging

# 1) 停掉旧的 mock API 容器（18090 端口让位；不动 PostgreSQL）：
docker compose -f docker-compose.yml stop jourvolt-dev-api

# 2) 校验并启动 pilot compose（只含 API，外部连接 staging PostgreSQL）：
docker compose --env-file .env -f docker-compose.pilot.ecs.yml config --quiet
docker compose --env-file .env -f docker-compose.pilot.ecs.yml up -d --build

# 3) 等容器 healthy 后验证：
docker ps --filter name=jourvolt-pilot --format '{{.Names}}\t{{.Status}}'
curl -s http://127.0.0.1:18090/healthz
```

期望 `/healthz` 返回 `{"mock_history":false,"mode":"fleet","persistence":"postgres","status":"ok"}`。

## 第 4 步：公网复核

```bash
curl -s https://api.teslalink.joviluma.com/healthz
# 期望同上：mode=fleet
curl -s https://auth.teslalink.joviluma.com/.well-known/assetlinks.json | grep -c 'com.matelink'
# 期望 >= 1
```

完成后即可进行真机 `com.matelink` 云登录验收（P1-9）。
注意：Tesla 应用审核未通过期间，只有开发者本人账号能完成授权，
其他账号会在 Tesla 官方授权页被拒绝——这不是服务端配置问题。

## 回滚（如需恢复 mock）

```bash
cd /home/jourvolt/jourvolt-staging
docker compose --env-file .env -f docker-compose.pilot.ecs.yml down
docker compose -f docker-compose.yml start jourvolt-dev-api
```

mock 容器会回到 `mode=mock_only`；staging PostgreSQL 与数据不受影响。
