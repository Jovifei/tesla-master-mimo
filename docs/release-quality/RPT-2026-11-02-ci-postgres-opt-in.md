# 2026-11-02 PR #27 PostgreSQL 验证门禁失败

## 发现与影响

- 发现日期：2026-11-02；审查对象为 PR #27 的 App 分支提交 `cf9b488733cab41d54af4f4a8a8f1654de985446`。
- GitHub Actions [run 41333602851、verify job 124928651338](https://github.com/Jovifei/tesla-master-mimo/actions/runs/41333602851/job/124928651338) 的 `Go and isolated PostgreSQL tests` 步骤成功，但 Go JSON 日志中有 506 个 run、484 个 pass、22 个 skip 事件；22 项跳过的原因均为未设置 `explicit isolated PostgreSQL opt-in`。这些是测试与子测试事件计数。
- 随后的 `Verify mandatory PostgreSQL evidence` 按原规则拒绝任何跳过或失败，以 `AssertionError: Skipped or failed tests` 结束；后续该 job 的资源测试及 Web 步骤未执行。旧 run 的其他步骤结果不代表修复后的新提交已经通过。

## 根因与修复

`verify` job 配置的临时 PostgreSQL 库名和连接 URL 使用 `matelink_test`，也没有设置 `MATELINK_TEST_ISOLATED_POSTGRES`。受保护的 PostgreSQL 测试要求显式设置该变量为 `1`，并要求连接到本机地址且数据库名以 `matelink_partial_test_` 开头。只添加开关而不调整库名仍会触发安全拒绝。

本次仅修改 `.github/workflows/history-memory-review.yml` 的 `verify` job：将服务库、健康检查及连接 URL 一致改为 `matelink_partial_test_ci`，并明确设置 `MATELINK_TEST_ISOLATED_POSTGRES: '1'`。保留原测试断言、禁止跳过的门禁和其他 job。只读搜索服务测试及工具未发现对旧数据库名 `matelink_test` 的其他固定依赖；测试用户名仍保持不变。

## 本地验证（2026-11-02）

验证使用单独创建、唯一标签的 PostgreSQL 16 临时容器：数据目录和运行目录为 tmpfs，无已有卷或用户目录挂载，端口仅绑定本机随机端口；使用独立合成测试数据库。测试结束后已核对容器 ID、标签、无卷与本机绑定，再仅清理该临时容器。未连接或修改源数据库和生产数据库。

- 按旧库名、未设置开关运行 `TestBatteryTrendGETUsesOnlyStoredOwnership`：测试按原安全设计跳过。
- 旧库名但设置开关：测试按原安全检查拒绝；合格前缀的专用库加开关：同一测试通过。
- 在专用库以 `JOURVOLT_REQUIRE_HISTORY_PG=1`、`JOURVOLT_RUN_LEGACY_MEMORY_BENCH=1` 和隔离开关运行 `go test ./... -count=1 -json`：568 个测试与子测试 pass 事件，0 skip、0 fail；按 workflow 原清单核对的 22 项必选测试全部通过。
- `go vet ./...` 和 `go mod verify` 通过。YAML 可解析；与原 workflow 对照确认仅改变上述数据库、健康检查、URL 和开关，原有所有 steps 及禁止跳过门禁未改。

## 尚未验证

本地测试环境为 Windows，未配置 gcc 且 CGO 为 0，因此本轮未在本机执行 `go test -race`；也未在本机复跑整个 GitHub Actions 工作流、Web 步骤或全部其他 job。修复尚未提交、推送或触发新的远端检查；必须以新提交的实际 GitHub 检查结果为准，不将旧 run 的通过步骤视为新提交已通过。
