# 小程序首阶段：数据、能耗、安全和交互

基线：远端main44bfb8baaa4528078ffb88a4f3ff622b570a6469/treeb609d635；当前原E分支codex/miniprogram-stage-20261013。App SOURCE_APPROVED、同SHA CI13/13、正式49同签保数据安装与启动通过；行程实机导航尚待手动点入，设备离线/自然Fleet独立验收保持OPEN。不再新增App功能。

Jovi已授权完整本地实施，普通GPT-6极高Chat继续审核；现有M3.1保留，不重建、不用Work。方案依据原Chat iteration9/10/12及本地当前源码审查。

## 可执行验收

- [x] 旧61回归保留，2个既有邀请响应类型错误修复。
- [x] 与Android相同的来源/方法/单位/窗口/容量规则，明确估算、0、负净值、unknown；功率积分不可标measured。
- [x] RawDrive解析保留有界真实输入字段与合同，不把字段融合说成API receipt；Taro当前返回解析对象，不宣称保存原HTTP字节。
- [x] 详情→同scope canonical列表/pages/cache→刷新→重开一致；明确unknown撤销，弱摘要保留合格详情，SOC/距离或source/window改变正确重算/失效。
- [x] origin/account/stableUID/request-generation隔离贯穿响应、选择、详情、缓存；provided car_id/UID不匹配拒绝。缓存不能授权未重新验证的远端numeric carID请求。
- [x] 缓存同时限制条数及序列化字节（低于1MB单key/10MB总体），旧cache兼容、配额失败不阻断在线数据，不清其他账号历史，不缓存整条轨迹。
- [x] 车辆/充电每个数值有限且单位/范围明确，0不变null；报告时间不当作逐字段真实观测时间，AC输入/电池充入分开，未知币种不显示人民币。
- [x] HTTPS origin严格解析（凭据/空白/fragment/query/歧义authority拒绝）；授权URL白名单、事务proof与旧会话取消回归保留。服务端任意错误信息不直出UI；不含Tesla token/AppSecret/session_key。
- [x] 四Tab车辆选择一致，日期/距离筛选、刷新、分页、详情关闭/返回与App对照。离线车单只允许缓存阅读，重新getCars验证前禁止用旧numeric ID发历史请求。
- [x] 真实API adapter＋实际控制器/storage＋页面操作跨层测试；不要只测纯函数或源文字。
- [x] 依赖按root advisory兼容修复：Taro保持4.2.1，Vitest4.1.11/Vite7.3.1及已核验适当overrides；实际旧新测试、typecheck、weapp build、再audit；不force降级。
- [ ] 增加小程序CI typecheck/Vitest/weapp，固定GitHub SHA及完整源复审；编译/模拟证据与微信真机/登录/发布严格分开。

2026-10-14 补充：实际依赖维护暴露 Taro 的可选 Vite 4 peer 与现代测试工具冲突。测试工具改为独立 `test-tooling` 包和锁，编译仍用原 Webpack；不使用 force/legacy-peer。构建与测试分别干净安装，并各自审计。车辆缓存不保存坐标，在线真实坐标正常展示。

## 数据对应

| 页面 | 输入与算法 | 约束 |
| --- | --- | --- |
| 车辆 | cars/status真实API字段，SOC%、range/odometer km、speed km/h、power kW | UID当前验证、有限范围、tri-state、报告/source/缓存状态 |
| 行程 | odometer_distance km；合格net_energy；功率梯形积分；合格nominal两端点；SOC×明确容量/100 | Wh/km=kWh×1000/km，km<=0未知；精确UTC/offset/亚毫秒窗口；0/负保留 |
| 充电 | energy_contract的battery/DC与AC各自合同、真实费用及币种 | 不把charger_power当kWh，不臆造损耗或费率；当前/历史分开 |
| 我的 | 本机MateLink会话及Tesla关联事务状态 | 不宣称本机token状态是自然Fleet或车辆权限验收 |

## 一致的展示

行程卡顶栏日期时间、SOC起止、估算/报告来源微标；底栏耗时、里程、总kWh、Wh/km。负净值绿色，unknown显示缺失而非0。详情可见容量/basis/方法/覆盖和缺失原因，返回后数值及来源一致。使用现有App/Taro绿色与浅底风格，不引入无关动画、字体或新流程；微信平台差异明确记录。

## 并行源码边界

1. 能耗/types：新domain/drive_energy.ts及测试、services/types.ts；真实Android资格和公式移植、共享接口。
2. 历史消费：domain/history/controller/query、history_cache、drives/charges页面及消费回归。
3. API安全：services/api、scoped车单缓存及安全测试；类型变更与能耗lane协调，不同文件不交叉写。
4. Parent：包/锁/CI、车辆/我的页面与样式、编译/依赖安装、全量复审和固定提交。子代理不运行npm/Gradle/Git、不安装手机。

共同接口：getDrives(carId,page,show,car?) / getDriveDetail(carId,id,car?)兼容原参数；Drive保留energyConsumedKwh并新增可选energy/energyReceipt/efficiencyWhKm。能耗模块输出有界投影与解析输入，缓存不保存positions。泛型旧历史消费者保留原分页/质量保护，drive新证明不能跨source/window graft。

## 平台与权限

官方开发工具存在且编译配置urlCheck true；CLI两次初始化因Windows保留端口冲突失败，不能通过改系统网络/降低TLS或关闭域名校验解决。先完成可独立验证的完整源码交付，再具体交接GUI/微信登录/Tesla授权与正式平台门。不得上传/发布、实际迁移、生产配置改动或车辆操作。
