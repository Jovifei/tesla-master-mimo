# TeslaMate 安全、兼容与诊断优化：已授权实施阶段

## 本轮范围
Jovi已明确批准完整方案并要求实施。原C2C规划失败后本地负责执行：认证安全→离线兼容工具与隔离真实组合→共享回放→双端只读诊断→完整测试/独立复审/固定源码CI/签名新候选。禁止生产部署、数据库迁移、历史重算、手机安装、车辆唤醒和发布。

## 当前基线
- 原目录 E:\project\tesla_master\app_mimo，分支 codex/miniprogram-stage-20261013。
- HEAD 1156f32fe4004039f3623f487ffa47347c67c19e；build55 / 2.1.36保持独立。
- 既有电脑验证见 docs/RPT-2026-10-15-computer-visual-validation.md。它是前轮回执，本轮不重跑、不冒称新执行。
- 前轮远端Iteration19针对783ddc4；CR-1/CR-2后续已修复。最终1156网页SOURCE_APPROVED未确认，与本优化规划独立。
- Owner dirty、备份、原始receipt、个人归档与生产数据全部保留。

## 必须给出的一次完整方案
1. 先调用本项目 workspace_info 验证身份；只使用 Codex with ChatGPT · app_mimo。读取当前相关源码，不能把研究中的潜在问题当已验证漏洞。
2. 核验官方release/集成文档。区分TeslaMate本体、第三方TeslaMateApi、archive bridge、Fleet API及App/Mini五个边界；实际运行版本/摘要未核实则明确UNKNOWN。
3. 安全：检查token refresh/authorization exchange HTTP client、redirect 301/302/303/307/308同/跨host、日志脱敏、timeout/429/5xx/invalid grant/异常JSON错误分类和重试预算。给出真实调用链、最小修复位置和隔离负面测试；不读取真实凭证。
4. 兼容：模板latest固定版本与digest的验证流程；字段/单位/null/0/负值/时间窗/分页/旧ID纠正兼容矩阵。MQTT不取代历史API，禁止全历史重灌/重置游标。先确定合理有界纠正机制和副本比较，不默认新schema。
5. 可靠性：共享合成场景穿过Go实际store、Android Room/worker、Mini cache/page；含休眠旧时间戳、乱序/重复/纠正、账号车辆切换、崩溃重启、权限恢复、跨窗口统计。独立物理预期，不能旧输出即正确。
6. 产品：在已有冷灰蓝绿图文状态页上增量解释 授权→车辆→接收→资格→历史→展示/通知；缓存、实时、合格历史、通知权限/实际送达分开。App/Mini交互保持一致；能力不支持要说明，避免重复实施已有视觉。
7. 功能排序：温度/能耗对照、多车辆排序、充电账本，列数据前置条件、公式/单位/来源、缺样和覆盖规则、收益与延期理由；没有数据不造图，不强推因果。
8. 来源与许可：官方链接、版本/文件来源登记；独立实现与复制区分，不未经关系审查断言整体许可义务。
9. 输出建议的一整个首阶段和后续阶段、逐文件范围、风险/依赖、测试与CI验收、回滚与人工门、估计工作量。首阶段必须足够完整但不把所有后续功能混装。

## 优先核查入口（由远端进一步追踪调用链）
- deploy/jourvolt-dev-mock/main.go 与 tesla_tokens.go；本地只确认共享client超时及refresh Do入口，未执行攻击或泄露证明。
- deploy/teslamate-home-docker/archive-bridge/archive.go。
- deploy/teslamate-home-docker/docker-compose.yml、docker-compose.selfhost.yml。
- Android/Mini现有readiness、通知、TPMS/energy/units和缓存消费者。

## 完成标准
交付固定SHA/tree/parent、Android双变体/lint/R8/签名及模拟器、Mini测试/types/build、Go隔离PG/race/Web/TLS和同SHA完整CI。来源/兼容矩阵、失败→通过、备份hash、新候选56及回滚清单齐全。运行服务/真机/生产/主分支合并仍独立验收。