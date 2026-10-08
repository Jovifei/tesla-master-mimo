# 同一完整阶段恢复点：2026-10-08

远端首轮实际长阶段以网页“无法思考/回答已完成”结束，没有完整交接，不声称后台仍运行。已保留真实源码与CI提交：阶段分支codex/reliable-data-stage-20261007当前c1d83fb2bc342342cc4347cf0bbd310e8d693a41，包含8033e05、81cc79e、0bb3eaac、两个CI支持提交。继续同一个ENERGY-REPAIR-CLEANUP-STAGE-20261008.md完整任务，不重建聊天，不返回旧只读结论，不把CI/helper当整阶段完成。

## 全阶段剩余闭合门
1. 以现有源码继续完成行驶、驻车和充电的解析/持久化/API/UI/统计全部接线、既有产品兼容与代码精简。确认新的后台持久化已覆盖旧resolver无window的问题；不能只修函数忽略真正StatsRepository/DAO入口。
2. 现有DriveSummaryDao avg SQL仍按全部距离作分母；真实SQL已在一次性SQLite复现未知能量样本压低平均十倍，全未知转0。证据及原SQL oracle在codex/energy-validation-evidence-20261008@c02d490的docs/ENERGY-INDEPENDENT-VERIFICATION-20261008.md及verify_energy_dao_sql.py；6项独立旧源码实际回归FAIL也在该分支。
3. EnergyMetric通用valueForWindow允许session_counter_delta/ac_charger_input，netValueForWindow当前未限制其物理用途：完整AC充电输入放net_energy会被当驾驶净能量接受。必须按字段用途验合同，不共用宽泛门当全部正确。
4. DriveDetailMetrics.isEstimated识别estimated，但DriveDetailScreen旧source枚举标签不使用它：energy_remaining_delta归API会显示接口报告值而不是预估。剩余能量变化和充电平衡均须正确标推算/预估、来源及缺口。
5. 充电与停车全链及源单位/计数器/观测时刻/期间活动守卫仍需完成，已有archive没有EnergyRemaining，不能猜容量或把官方字段存在当实际收到；旧字段缺失保留未知。
6. 当前阶段Go目录相对已部署bb09仍有60文件/约9247增2018减的旧差异，不是只有本次能量修改。必须通过已建source-review导出的两个确切底座，完成可审兼容整合；不盲目把整个旧Stage2当生产bb09。完整Go/PG/race/Web和Android Debug/Release/lint/R8实际通过，不用环境/guard放宽掩盖产品错。
7. 安装用源码须升级versionCode/versionName（当前中间版仍45/2.1.26），保持包名签名数据登录。本地会以完整固定SHA编译并验证，不安装中间版本。
8. 完成精华Docs索引、确切清理清单和完整源码交接Prompt/文件/精确SHA及真实CI。主分支合并由本地安装验证后执行，源码合并不代表生产DDL/部署或自然Fleet通过。

首真实CI37790768184 Go/PG/race/Web PASS，Android SDK setup FAIL；之后CI支持在c1修工具包/编译内存。引用新run/job真实结果，不泛称没有dispatch不能执行：push已经实际触发。

## 已完成本地事项与安全
只补充独立测试/原SQL oracle及证据，不实施业务。手机现场45原APK哈希B7C46F及证书9AB144匹配、首次安装时间未变。13项旧缓存/失败日志可恢复归档1784862693字节，源码/有效测试/迁移/APK/report/rollback与Owner dirty保留。永久删除被策略阻止，不做绕过；Hub离线pending未发送。
生产DB/bridge升级/历史回填仍独立门；不唤醒、不造真实事件、不改网络、不清数据，不泄露VIN/token/轨迹。恢复完整任务代码阶段已授权，无需重复询问；完成后给一个集中固定源码交付，本地再独立测试安装合并。
