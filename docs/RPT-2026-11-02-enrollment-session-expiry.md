# 归档续期确认：会话到期窗口与绑定身份设计

- 日期：2026-11-02；本地候选基线：`bfe60d5fac5aa298bbfba55b2f216c9f3f751f6e`。
- 范围：仅本地候选与合成 PostgreSQL；没有部署、修改线上数据库、访问用户车辆或发送消息。

## 已复现与候选修复

归档 enrollment 的审批事务先以 `FOR SHARE` 锁定手机登录 session，并在绑定锁之后检查一次有效期。合成 PG 在这一检查之后、写审批状态期间设置确定性等待：session 在提交前到期时，旧代码仍返回 200 并提交 approved。此为合成复现，不能推断生产发生过。

候选在审批状态写入后、紧接提交前，以同一 access token hash 和 owner 查询锁定的 session，使用数据库 `clock_timestamp()` 重新核对未撤销且未到期；失败则返回 401，事务回滚为 pending，不更改原归档 binding 或 token。原有锁序与正常审批路径保留。并发登出测试证实 logout 会等待该 session 的 `FOR SHARE`，审批完成后登出再提交；先前把它描述成“可先登出后批准”的静态推断已纠正。

这是紧邻提交的时点校验，不意味着 SQL 校验与实际 commit 的时间完全原子。enrollment 自身过期和设备 claim 在事务执行中跨过截止时间的情况不属于本次已验证修复。

## 本地验证

- 旧代码 RED：隔离 PG 中 session 到期用例返回 200，预期 401；logout 等锁场景通过。见 `approval-red.log`。
- 候选 GREEN：到期返回 401，审批字段保持空、状态 pending、旧 binding 仍有效；logout 场景保持原行为。见 `approval-green.log`。
- Linux PG16 `go test -race -count=1 -run '^TestArchiveEnrollment' ./...`：37 个通过事件（11 顶层测试及 26 子场景），0 fail/skip，未见 data race，stderr 为空。`enrollment-race.jsonl` SHA-256：`1bde8294d8fd9a1f7b391968105f6123555886cae500af9a1022090e4f531a75`。
- 候选及测试的 114 个 Go 文件与实际工作树统一换行后内容一致；`go vet ./...` 通过。隔离容器使用唯一标签、network-none、tmpfs，核对后已删除。
- 没有运行服务端全量、线上部署或真实手机验证。

## 后续设计：稳定绑定身份，尚未实现

旧 `scope_fingerprint` 只覆盖车辆及来源、截断为 16 个 hex 字符，不包含账号或 binding，不能证明旧 pending 属于当前账号。可评审的新合同是在已认证绑定范围内返回域分隔的 64 位 HMAC 指纹：以高熵随机 binding id 为密钥，长度分帧绑定 owner、vehicle、source type/instance/vehicle；客户端另固定 HTTPS origin。普通手机确认续期保持 binding id 与指纹，新建/重新绑定会更换 id，不能静默接续旧状态。该指纹不是用户签名，也不单独抵抗数据库被任意篡改或跨实例克隆。

新版本的趋势写入必须携带此前确认的 expected identity，并在 `vehicle → binding` 锁内重新计算、常量时间比对；仅 GET 后发旧 POST 有 TOCTOU。旧 v1 接口若保留兼容，本身仍不具备这一身份保证，旧 worker 需要隔离；旧 pending 无历史 owner 证明时须封存，不能以当前 token 或当前来源反推原账号。续期应先停止发送、由已登录用户确认账号/车辆/来源、核对新旧指纹、可靠安装新凭据后 ACK，失败/身份变化不得推进游标或覆盖旧状态。

新 identity/v2、旧状态授权迁移和旧行程 insert-only 补扫均未实现。现有 JSONB 路线比较与跨来源并发安全问题未闭合，不得把只验证来源/时间的 `present_unverified` 当作完整恢复成功。
