# MateLink 三端 UI 对齐阶段交付 · 2026-11-13

Jovi 本轮确认：C 中性白默认，A 奶白可切换；日期面板默认左右滑动，可选上下；一周保留七个日期层，点后层抬起，再点收回；正文滚动不误切天。保留首页和前三页结构、正确能耗与丰富 A→B 地点；保留驻车；行程柱状图、充电日历格。小程序不仅功能对齐，也按 App 的布局、语义色和数据次序对齐。网页使用真实 React/HTML 产品工程，不以离线原型替代。

本交付是客户端源码与隔离 CI 候选，不是生产部署、真机像素验收或自然 Fleet 数据验收。未合并 main、未安装手机、未上传小程序、未部署网页、未唤醒/控制车辆、未改生产数据库或历史。iOS 与 Figma 本轮未改动。Draft PR49/50/51 的基线是本轮各端原始 SHA 的 owned 审查分支，不指向 main。

## 1. 固定测试源码（接收这些 SHA，不以浮动 branch HEAD 代替）

| 端 | 分支 | 被实际检出测试的完整 SHA | Tree | Parent |
|---|---|---|---|---|
| Android | codex/ui-product-app-20261109 | 95bda618c7d9d09ff50985c9befe8558fb24a0e7 | d3490230d29d91dd0473ca66caf6068cafed0625 | 26372a3096afd0894dc739815a6d53fae6321f54 |
| 小程序 | codex/ui-product-mini-20261109 | 6debf2527d6ca7c39d7257909eed4f06d8e481ba | b77db370b22e433c4dddeffda8f3a7bb98b0ed37 | 270ce051bd464f8ceb7f3190aad24a3339cdc818 |
| Web | codex/ui-product-web-20261109 | 4b622f98eb5a5c7a6d715155146add9ea6fb296c | 917e63b0abdb66a8d05f0c8d9d90f8793f908804 | fb53e77b79d9e3401899bfd5b37f53beb9be857a |

工作流触发 SHA 与 source 作业提交后真正测试的 SHA 不同，必须使用制品 SOURCE_HEAD.txt 对照上表。后续交接文档提交不改变这些产品源码。Android 的只读诊断工具另固定在审计提交 3a7c44fd30132ad4d11b4597f377f951dc13f1ae；该提交 android 子树与上表完全相同。

基线：Android a75ae213a9cbd91d153e45c6236536e6c0b1327f；Mini 1a8cdbede3799a9d4f2bab39620e4c9b7e023fa6；Web 9cece6f0ff5d9b24f6c026a7f6d5331becac41c3。

产品目录树：Android 8115a7ddb95fbf6d3dda851f9bd83b8bfbce02e9；Mini 3bc1b32806d4cc804b15cf6f978c7cbd0c3430f4；Web b750814cbcc3530be27fdfd9743e66683f4f6e2d。接收时可用 git rev-parse <SHA>:android/miniprogram/web_matelink 分别核对，不能把其他端目录互相覆盖。

## 2. 已实现内容与计划对照

| 要求 | 实际落地 | 验证边界 |
|---|---|---|
| C 默认 / A 可换 | 三端持久化外观偏好，主数据不受影响；保留系统深色与减少动画 | Android native、Mini component、Web browser |
| 日期切换不冲突 | 默认横向；可选纵向；日期抬头识别主轴、阈值、取消；正文继续上下滚动，另保留可点击箭头 | 原生6项 / Mini组件 / Web真实页面 |
| 七日轻堆叠 | 近一周七个日期，后层露日期；点后层抬起，再点/收回恢复前层；无记录日明确说明，不制造行程 | 日历日期与实际记录分别建模 |
| App 与 Mini 视觉对齐 | 首页原有层次保留；日期标题左/箭头右、触控尺寸、卡片、白/奶白/深色语义色、充电渐变色阶与 App 对齐 | 源码/组件验收完成；微信真机像素与原生组件差异仍需并排核验 |
| 保留正确数据 | 受保护能耗、费用、地点解析、来源与缓存核心文件对比不变；四指标和丰富地点仍沿用原产品 | 详见审计 preservation.json |
| 驻车和小车图标 | App 既有 Nov12 修复保留，仅移除路线右侧装饰小车；Mini 保留真实驻车管线；Web 显示相邻行程间隔与可请求的匹配明细 | 不将间隔推断当成完整驻车能耗证据 |
| 行程详情 | 主页面速度统计紧接速度曲线、功率统计紧接功率曲线；保留详细曲线入口；其他曲线留完整页面/展开区 | 缺测断开/孤立采样点，不插值补造 |
| 充电日历格 | 只看按日充入电量，一格一天七列；0、未知、部分覆盖区分；60 kWh 为固定最深阈值；无费用/次数切换 | App/Mini 采用同语义表面与感知颜色插值；Web 响应式月份日历 |
| 设置/登录 | App 保留既有紧凑账户+主题入口；Mini 账户/退出/外观与语言、胎压、地图、电价二级区分；Web 极简真实 OAuth、退出确认、外观及二级导航 | 平台不支持的设置明确说明，不画成已实现原生功能 |
| 数据状态太长 | 先显示已收到的数据与摘要，技术诊断默认折叠；“未验证”不伪装成失败/成功；不把有历史等同 Fleet 授权完成 | 未访问真实身份/车辆/证书状态 |
| Web 更美观 | 手机紧凑展开导航，桌面日历/记录双栏；充电卡片直读，完整参数表折叠保留；窄屏、焦点、减少动画 | 实际 build 的 320/393/1440 Chromium 截图已检查 |
| 历史缺口诊断 | Mini 显式相对时间预设在重开时滚动；手动/旧固定范围不擅改；Web 显示最新已返回日期、筛选隐藏条数与分页；提供离线日期计数核查工具 | 生产 10月27日—11月12日充电恢复尚未证明 |

三端使用原技术栈和真实入口。公共画面已实现本轮对应改动，但不能把编译/组件测试说成所有手机画面已逐像素验收。

## 3. 实际 CI / 制品证据

| 检查 | Run / Job | 实际结果 | Artifact |
|---|---|---|---|
| Android 全量 | 43369452093 / 131701595709 | Debug 1969 项零失败零跳过；Release 1969 案例零失败、8项跳过；Debug/Release lint 和 APK 构建通过 | 14012344976 |
| Android 隔离原生 | 43370279757 / 131704173285 | API35 aosp_atd x86_64；6项原生测试通过 | 14012900498 |
| Mini 最终严格检查 | 43374965068 / 131719247332 | typecheck；1033 Vitest 零失败/错误/跳过，另6项原车型资产 Node 检查；WeChat build 与预算通过 | 14014008183 |
| Web 最终严格检查 | 43375124759 / 131719748751 | TS/Vite、59 Node 测试、lint零错误；25项真实构建浏览器检查全过 | 14013758153 |
| 源码保全/离线诊断 | 43375985404 / 131722475110 | 受保护路径不变、三端范围隔离、8项只读诊断测试通过 | 14014526885 |

重要区别：Android run43369452093 的 build 作业成功，但当时另一个 native 作业 SDK 安装失败，因此不称整次 run 全绿。之后单独 run43370279757 原生测试通过。Release 的8项跳过不是通过。原生测试卸载隔离测试包后截图拉取未保留，因此只有测试报告，没有本轮新 Android 截图。没有使用 Jovi 手机执行 connected tests。

早期 Mini run43371130859 被 tee 管道遮蔽失败而显示绿色，已作废并明确纠正；最终使用 bash pipefail、独立 JUnit 零失败检查及类型检查。后续真实错误逐项修复，未删除安全测试。1033项最终报告与编译报告已下载核验。

Web ESLint 0错误但仍14条 warning，Vite 有既有大 bundle 提示；不是零警告、性能认证或全部浏览器兼容证明。实际截图使用合成账号与数据，所有外部请求拦截；除合成退出接口外无 POST，无真实车控。

Mini 主包 1,305,137 bytes，总包 11,595,714 bytes；97张原始车型 PNG 哈希未变；既有资产预算通过。构建文件清单哈希 02df0fd2d03fac502b30c077b6b92b47e639769d55e9193effe98735e8821bb4。并非手机微信安装/发布证据。

下载的 ZIP SHA256：
- Android build: b2303cb2f8a70b826d1fea251d6c46843979bff3c3ca24e6ad2587569f2b0d79
- Android native: 5c66abb20b4f3f6887e9067b7801f37fbc05c35e8a5387d20552ec17831be346
- Mini: f4a91b7e4eccfd3490e7e8251a1ef3734e28de89cb5e0608e48edd6fe835f8f7
- Web: 20b146b26cb449c76e7156fc8c35667960fc247c95569fc511bb370d6e937907
- Preservation audit: 78f3d782e92ad56c68219cc9cc9a67f51fc458ab9d8f7e8ee10f99361bb31ec9

前三类产品制品保留至约 2026-11-20（以 artifact expires_at 为准），审计至2026-11-27。应在有效期内通过 GitHub 保存到获授权的本地证据目录；本仓库不上传私密截图或任何字体文件。

## 4. 充电缺口：已证明与仍未知

已证明的客户端问题：Mini 先前保存相对范围为固定日期；现在仅对明确标记 relativeDays 的预设滚动更新。旧固定筛选不能判断是否用户故意设置，因此保留原值。重新选择近一月或明确10月27日—11月12日即可做只读核对；不得清历史解决。

仓库既有 docs/data-history/RPT-2026-11-01-charge-energy-filters.md（本轮 Android 基线内）曾记录云端能读取10月31日四个充电片段；这仅是历史记录说明，不证明当前账号/当前源或11月12日前的数据完整，更不证明四次自然充电。不能说10月27日之后服务端从未有数据。

缺少当前生产同账号/同车辆/同来源的只读分页、缓存、同步水位证据，故无法判定本次是筛选、客户端缓存、API返回、上游同步或不同数据源。没有用假的零、补点、修改历史或放宽身份/能量契约来“修复”。

固定工具提交3a7c44f…中的 tools/ui-alignment-20261113/charge-window-audit.py 仅处理已获授权导出的本地 JSON。输入需 schema_version=1、display_timezone、evidence_class='synthetic'或'production-readonly'、scope_verified(account/vehicle/source均true)、page_responses=[{status:200,requested_page:1,payload:{data:{charges:[...],meta:{total_pages:1}}}}]、可选ui.start_date/end_date。原始输入留本地，不能上传身份/坐标/token。

命令：python3 tools/ui-alignment-20261113/charge-window-audit.py --input <私有只读导出.json> --output <全新日期计数报告.json> --start 2026-10-27 --end 2026-11-12

工具不做网络请求，不覆盖输入或已有输出，不写生产。只有日期/数量/未知/覆盖结论；不输出 ID、VIN、轨迹、token。scope_verified 是外部证据声明，工具不会自称验证身份；api_pagination_complete 不等于 provider_history_complete。输入校验失败即停止。8项测试覆盖零/缺失、时区、分页、去重、隐私、范围和不修改输入。

## 5. 本地验证 / 安装 / 发布 / 回滚矩阵

| 环节 | 本轮可做 | 必须另行满足的门禁 | 回滚/保护 |
|---|---|---|---|
| 独立源码核验 | 原会话中三个独立 clean worktree 检出上表 SHA，对照 tree/parent/产品树 | 不覆盖 Owner dirty work | 不 reset/clean 原目录，不互相复制工程 |
| Android 构建 | 既有本地环境独立单测/lint/Debug/Release 构建；无需执行远端一次性编辑 workflow | 不修改本机代理、签名、版本政策 | 保存原签名包/版本/数据兼容证据 |
| 原生 UI 测试 | 仅隔离测试模拟器，并证明目标非 Owner 手机 | connected tests 会卸载目标；必须限定隔离设备 | 不在当前手机运行、不清日志和数据 |
| 手机安装 | 当前尚未执行 | Jovi明确允许该固定构建，签名一致、保留数据、可审查备份及回退路径 | 禁止卸载降级或清数据；不假定旧包可直接回滚新数据 |
| Mini 本地 | typecheck/test/build，开发者工具导入合成测试产物 | 真实授权与手机体验扫码由Jovi决定；上传/体验版/发布独立审批 | 保留旧制品与账号/本机缓存，不清微信数据 |
| Web 本地 | build/test/lint，loopback预览+合成API，320/393/1440交互和截图 | 线上部署需旧静态制品备份、明确版本切换及回退方案 | 不改Nginx、域名、生产服务和数据 |
| 充电历史 | 先检查已有只读证据，只有确认请求不唤醒/控制车辆才可用既有授权读存储历史 | 缺授权/不确定副作用则报具体门禁，不能索要密码/MFA/token | 不DDL、不重放、不补写、不回填、不清缓存 |

## 6. 发给原本地 Codex 的一次性交接 Prompt

继续当前原 MateLink 会话。只使用 Jovifei/tesla-master-mimo。本轮远端已完成三端客户端源码候选，按本文件第1节完整SHA分别接收Android/Mini/Web；本地只独立审查、测试、构建、在已有精确授权范围签名/安装，不承担业务实现。不得另建云任务或让Jovi逐页发微任务。

先保护Owner未提交工作、历史、缓存、会话、设置、备份；记录原分支/安装版本/dirty，仅在独立worktree操作。不得reset/clean/强推/合并main/覆盖原工作区。三个分支分别是codex/ui-product-app-20261109、codex/ui-product-mini-20261109、codex/ui-product-web-20261109。固定测试源码为95bda618c7d9d09ff50985c9befe8558fb24a0e7、6debf2527d6ca7c39d7257909eed4f06d8e481ba、4b622f98eb5a5c7a6d715155146add9ea6fb296c。核对git show与产品目录树，不把后续docs提交当成新的已测试产品。

核验第3节Actions run/job/artifact/SHA256/SOURCE_HEAD.txt。直接解析JUnit/浏览器报告，不只看绿色图标。Android Release8项跳过须列出；Web14条lint warning须保留。复核原始97张车型PNG哈希。不要执行任何一次性apply/source-repair workflow。

独立运行现有本地构建与测试：Android相应testDebugUnitTest/testReleaseUnitTest、lintDebug/lintRelease、assembleDebug/assembleRelease；Mini npm ci与既有test-tooling锁定依赖、typecheck、test、build:weapp；Web npm ci、build、test、lint。所有环境调整限已授权测试环境，不改Jovi网络、VPN、代理、DNS、TTL。connected测试只能在已证明为隔离设备的模拟器；真机安装与小程序上传未获本轮明确发布许可，不自行执行。

逐项检查：默认C/可换A/重开保存；默认横向切日、可改纵向、正文上下滚动不误切；一周七个日期；后层仅露日期、抬起/再点收回、减少动画；行程间P驻车保留；原A→B富地点及四指标不变；只删除右侧小车；速度下速度曲线/功率下功率曲线/顶部详细曲线保留；充电仅日kWh七列日历格，60固定深浅，真实零与未读到区分；设置账号/退出/主题与二级配置分开；数据状态技术项折叠。App与Mini按同一合成夹具、逻辑宽度、字体比例、浅深色逐页并排比对，不因有同名功能就判UI通过。

针对2026-10-27至2026-11-12充电缺口：先记录当前源/账号车辆的本地比对结论（外发不含身份）。检查有效筛选、时区、最新返回日期、加载分页、被隐藏和隔离数量；保留旧固定范围，另以明确日期范围只读比较。只有确认现有授权和存储历史GET无车辆副作用后，才采集只读分页/已有服务端同步证据。不能访问则写出具体门禁，不要求秘密凭据。可在工具固定提交3a7c44fd30132ad4d11b4597f377f951dc13f1ae运行离线audit，仅上传日期/计数报告。记录API是否有11月数据、UI是否过滤、是否存在来源切换，不能用能耗估算/历史归档证明Fleet首事件。不得执行生产回填或变更。

最后一次性通过GitHub返回合并证据：三个源码/产品树/本地制品哈希、CI对照、逐页脱敏截图和短录屏、通过/失败/未执行清单、充电缺口分层结论、真实授权/安装/发布门禁。任何失败发回远端修复，不自行改业务代码，不把当前候选宣称生产验收通过。

## 7. 下一阶段（不属于已完成的生产工作）

本地返回固定版本的设备视觉/手势证据与只读充电链路分层证据后，远端按可复现失败修复；只有定位到具体API/同步代码缺陷才提交受测候选。生产修复需独立审查备份、幂等与回滚；旧六条SOC及7113点TPMS历史写入仍未获本轮授权。本次源码CI、原生合成测试、签名或安装均不是自然充电/通知/TPMS/Fleet多用户验收。
