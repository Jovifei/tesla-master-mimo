# Android能耗收尾与小程序接续验证

## 当前结论

原E目录、codex/per-drive-energy-20261012，基于29ac70c的最新45文件冻结。Jovi确认其他writer停写并授权统一修复、验证、手机升级、main合并及小程序接续。Owner611文件已逐hash备份。当前尚未提交新源码、安装本轮包或合并main。

## 集中修复与审查

- CR1：当前raw只来自实际输入；投影融合独立。最多保留一份既有真实receipt归档，按origin/account/car/id/source/window校验，不把归档作为当前能耗证明。完整SOC及四端点纠正可替代旧详情，弱摘要保留合格详情，明确unknown撤销。
- CR2：行程和充电跨车共享20详情预算，真实DAO查询有界、轮转公平。单条404保留历史并停止重复阻塞，更新receipt可重考虑；429/认证失败及身份改变立即停止。
- CR3：FULL_SYNC与DETAIL_CONTINUATION分开。详情续跑不重新上传本地归档或扫摘要；持久身份epoch允许同配置重建、拒绝账号/来源ABA，取消正确传播且不自动复活用户取消的任务。
- CR4：当前实现同rank容量保护条件为>=，5项实际消费者回归通过，晚到容量覆盖未复现。API没有可比容量观察stamp；未知新鲜度不判较新，也不按容量大小仲裁。集中回传反证，等待Chat审核结论。
- 能耗保留具体方法/容量/basis/版本、0及负净值与unknown区别；85%功率覆盖的Owner改动保留。列表、详情、Room及离线统一估算标识。

## 最新实际本地主机证据

- 45/45源码hash一致，0漂移；最终只读源码复审无剩余实际P1/P2。
- Debug912：0失败、0错误、0跳过；Release912：0失败、0错误、8预期跳过。
- 独立javac引用最新真实编译类，原4项回归4/4通过；d119原fixture源码blob4e4252aa30803a83c171af22ae9fea61ec332323未改，独立回执/Room/离线/同ID回放通过。
- 最新双lint零错误（Debug268/Release248 warnings）、R8、Release正式签名构建全部通过，wrapper退出0，9m9s；结果对应当前45文件。
- WorkManager测试是实际消费者、持久WorkSpec和本地运行时重建；不是操作系统杀进程或手机验收。HTTP fixture隔离，不是自然Fleet。
- Go源码本轮未改；最终固定SHA的Linux Go/PG/race/TLS/Web与审计尚待CI。WindowsTLS环境不适配结果保留。

## 证据位置与下一步

仓库外证据：E:/Claude_allow/Download/matelink-energy-final-20261013，cr-source-freeze-final.json、cr-final-full-android48.log、各XML及独立Java日志。当前手机47的rollback APK已保存；签名配置仅在仓库外。

完成当前构建后一次集中GPT-6极高Chat复审；固定GitHub源码并完成同SHA CI，重新构建匹配SHA的Release，核对同证书后保数据覆盖安装、核查已有自然行程交互。绿色版本按授权合并main，再从该main开codex/miniprogram-stage-20261013。

小程序继续现有Taro/M3.1，逐项审查数据来源、单位、公式、时间、scope与安全；详情到列表/刷新/离线闭环，核心交互与App对照。旧61测试通过但typecheck有2既有响应类型错误，纳入实施。微信开发者工具存在且AppID匹配；未扫码、未上传发布。生产、真实迁移、车辆动作与平台授权仍是独立人工门。
