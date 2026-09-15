# M3.1 测试与产物证据

日期：2026-09-15（M3.1 复审后续收口）
工作树：`E:\temp\matelink-wechat-miniprogram`  
基线：`bf6425d846d7bc1850892f64af20dbda3df9c3c4`  
验证代码提交：`5097a90`
Node：`v24.18.0`；npm：`11.16.0`；Go：`go1.22.10 windows/amd64`。

## 实际执行

| 检查 | 结果 | 证据/边界 |
| --- | --- | --- |
| `npm run typecheck` | PASS | 当前源码；TypeScript 5.7.3 |
| Vitest | PASS，8 files / 54 tests | `src/services/api.test.ts` 含授权回包竞态、退出捕获 token、URL globals；`history_controller.test.ts` 覆盖行程/充电四页、失败重试、重复页和元数据矛盾；`history_cache.test.ts` 覆盖匿名归档拒绝；不是候选包的片段计数 |
| `npm ci --ignore-scripts --no-audit` | PASS | 隔离目录 `E:\temp\matelink-m3-clean-install-20260914-b`，实际安装 1183 packages |
| 隔离依赖 `typecheck` | PASS | 使用上述干净 `node_modules` |
| 隔离依赖 Vitest | PASS，8 files / 54 tests | 使用上述干净 `node_modules` |
| 隔离依赖 `build:weapp` | PASS | `TARO_APP_API_BASE_URL=https://api.teslalink.joviluma.com` |
| 当前依赖 `build:weapp` | PASS | Taro 4.2.1；产物 30 files |
| 微信业务产物扫描 | PASS | 自有 JS 排除 Taro vendor 后 `URLSearchParams=0`、`new URL=0`、动态 env=0、secret/token/private-key/password markers=0；API host 1 |
| `go test ./... -count=1` | PASS with 16 SKIP | JSON：`E:\temp\matelink-m3-evidence-20260914-go-test.json`；失败 0，跳过均为缺少 `JOURVOLT_TEST_DATABASE_URL` 的 PostgreSQL 集成路径；新增未知授权错误不泄露内部详情和回调消费后通道保留用例通过 |
| `go vet ./...` | PASS | 当前 Go 源码 |
| `go build ./...` | PASS | 当前 Go 源码 |
| `go mod verify` | PASS | 输出 `all modules verified` |
| 分支盘点 | PASS | `BRANCH_INVENTORY_20260914.md`；只读 20 分支 |

## 候选与完整工程的计数边界

独立复审包的历史 22 + scope 8、API 片段 11 只用于定位缺陷，不能与完整工程计数相加。当前完整工程是 Vitest 54 项；Go 报告是测试结果 0 fail、16 skip，不是 16 项通过。没有真实微信、Tesla、钥匙、设备、MQTT、生产或历史恢复证据。
