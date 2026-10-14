# Jovi：能耗收尾 → main 合并 → 小程序阶段

## 最新指令与分工

2026-10-13 Jovi 授权：先完成本次能耗，再合并主分支；App 功能开发暂告一段落，随后从合并后的 main 创建独立小程序开发分支。原 E:\project\tesla_master\app_mimo 继续作为工作目录，保留 Owner dirty/数据/既有改动，不使用 C 盘 worktree。

本地统一实施、验证与 Git 操作；普通 GPT-6 极高 Chat 负责一次整体规划和集中源码复审，不使用 Work、不重复微派工。以前“main 不合并”约束由本次明确指令更新，但合并必须在修复与验证完成之后。

## 事实基线

- 当前工作分支 codex/per-drive-energy-20261012，HEAD29ac70c 加未提交 Android 更新。
- 2026-10-13 本地审核：现有能耗聚焦61项通过；独立真实编译回归4项失败，见仓库外完整报告 E:\Claude_allow\Download\matelink-energy-review-20261013\REVIEW.md。
- F1：SOC fallback 遇局部功率样本被错误归为 time，持久结果null。
- F2：刷新后 nominal/SOC 来源被重新标为 API。
- F3：后台默认60kWh覆盖前台车型/校准容量，0.784→0.6kWh。
- F4：nominal合同缺活动/无充电证据仍被接受。
- F5：前台 get→upsert 非事务且最终挂起点后 scope fence 不足，可能覆盖新receipt。
- origin/main=f0dcd447，当前HEAD是其后代；fetch后 main...HEAD为0/130。主分支尚未合并。
- 已有 miniprogram/（Taro/React/TypeScript）及 origin/feature/wechat-miniprogram=4ca83b86；不能推断旧分支全部修改已在当前主线。需比较小程序/共享API相关增量，避免覆盖较新的 Android/Go。
- 当前小程序 project.config.json 已配置 AppID，旧README“留空”描述过时；配置存在不等于真机/域名/OAuth验收。

## 阶段一：只收尾 Android 能耗

- [x] 将本地 Go nominal schema 实验补丁完整保存为独立候选，逐hash核对；不混入本次 Android 收尾。
- [x] 集中最小修复F1–F5；统一容量依据/来源/版本，前后台/离线一致；保留原始APIreceipt、真实0/负值/unknown。
- [x] 将4个实际失败纳入回归并先验证转绿；补真实Room并发/upsert/scope取消及列表观察回归。
- [x] 新能力的估算标签不因列表同步变化；容量假设在详情可见，不宣称车机精确计量。
- [ ] 冻结源码，Debug/Release unit、lint、R8/正式签名及相关全量检查；固定源提交与同SHA CI。
- [ ] 集中Chat代码复审并修复反馈；同签保留数据安装、检查已有自然行程列表/详情/返回/重开，区分源测试与设备/provider。
- [ ] 提交明确版本/回执；App阶段不新增无关功能。

## 阶段二：合并主分支

- [ ] 备份并逐hash核对Owner dirty、回滚APK和本轮源码；只提交本阶段文件，不盲目git add全部。
- [ ] fetch并核对main/PR/CI，审查实际合成树。按仓库保护规则合并，保留Owner c9及后续提交；不force-push、reset、clean或删除数据。
- [ ] 验证合并后的精确源码与绿色源码一致，记录main SHA/tree。待实际合并成功才标完成。

## 阶段三：小程序独立分支与可执行首阶段

- [ ] 从验证合并后的main创建 codex/miniprogram-stage-20261013，旧小程序分支/聊天保留。
- [ ] 对比/接续现有小程序来源，不重建新项目；确认登录、Tesla关联、车辆选择、行程/充电、分页/缓存和来源显示的现状及实测缺口。
- [ ] 小程序第一阶段：四Tab（车辆/行程/充电/我的）与详情闭环；将已验证能耗规则、容量依据与估算/零/负值/缺失语义移植到TypeScript，不复制Compose/Room。
- [ ] 小程序本地缓存按origin/account/车辆稳定身份隔离，有界保存摘要/按需详情；核对微信平台存储、web-view和登录规则。
- [ ] API/session/callback沿现有合同，AppSecret/Tesla token/session_key留服务端；不改生产配置、不绕过域名或证书校验、不替用户授权登录。
- [ ] typecheck/test/build、官方开发工具编译/已有配置联调；真实微信/授权/车辆行为与发布审核单列验收。未授权发布不提交平台审核。
- [ ] 小程序本阶段固定GitHub源码、测试/预览证据与下一阶段计划；不继续扩展App功能。

## 权限边界

源码、隔离测试、已授权同签安装、合并main及新小程序分支属于此次执行范围。真实数据迁移、生产API/DB/Nginx变更、车辆配置/唤醒、凭证/用户授权操作、微信发布仍需具体授权或用户完成。

## 小程序首阶段已核实的具体缺口（只读准备）

- 当前 RawDrive/Drive 缺少 energy_contract、容量依据与详情能耗输入；normalizeDrive直接展示scalar，Fleet资格需加入。
- 行程专用合并需区分明确unknown撤销和弱摘要缺字段；来源/时间窗变化不能移植旧详情证明。
- 详情当前只更新selected，须在同scope下同步canonical摘要/itemsRef/pagesRef/cache，防刷新/重开丢估算。
- 现有scopeGate、origin/account/stableVehicle缓存隔离和pagination保留；缓存只持久化有界摘要及能耗证据，不保存完整positions。
- 现有0/负数未被过滤；保留此行为，增加unknown/容量估算/净回收的统一呈现。
- Taro4官方存储文档要求单key最多1MB、总体10MB，因此摘要数量/字节均须上界，并处理写入失败。来源：https://docs.taro.zone/docs/apis/storage/setStorage
- 测试覆盖：API/0/负值、明确unknown撤销、nominal活动与时间、partial power/SOC、容量依据、UTC等价/亚毫秒不同、详情→列表→刷新→重开、scope/session ABA、分页及旧cache兼容。

## 2026-10-14 Jovi补充验收要求

小程序每项显示数据需逐项审查来源、单位、原始字段、计算公式/版本、时间窗、未知/零/负值语义和origin/account/稳定车辆作用域。保留不可冒充的raw receipt，并覆盖列表/详情/缓存/刷新/重开整链，不能只测纯计算函数。

安全通道检查沿既有HTTPS、合法域名、服务端token/凭证边界、session-generation及响应作用域、错误脱敏；不降低TLS、不把Tesla token/AppSecret/session_key移到客户端。

交互以App核心流程为对照：车辆选择、筛选/时间范围、列表→详情→返回、刷新、分页和离线；微信平台限制明确记录，不能以平台差异掩盖数据/流程回退。

此前版本成功的路径保留回归矩阵，并在每次新改动后重跑相关历史用例；最终以完整同版本数据链与交互验证关闭阶段，避免后续修复回退旧能力。

## 2026-10-14 集中Chat复审 CR1–CR4（安装/main前必要收口）

- CR1：raw_json只取实际收到的source receipt字节；融合值只用于独立展示投影。新增稀疏新地址、缺SOC/能量、explicitunknown及已有完整SOC纠正回归。
- CR2：行程+充电全车共享20总详情预算、有界DAO查询、公平推进；身份/权限和429停止，单条永久404不能无限阻塞别的合法记录，历史保留。
- CR3：FULL_SYNC与DETAIL_CONTINUATION意图区分；续跑身份/取消重验但不重复上传归档或重扫历史。实际WorkManager持久任务/假API/重启/ABA证据。
- CR4：容量同rank替换须可比新鲜度/会话请求代次；未知时间不算较新，不取最大容量。延迟双响应、同basis不同容量、车型修订、SOC纠正、账号ABA实际回归。
- 统计混合报告值/估算汇总文案复核，不新建统计系统。887双变体及独立d119原断言保留。
