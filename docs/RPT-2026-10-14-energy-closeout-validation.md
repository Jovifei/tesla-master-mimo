# Android能耗收尾与小程序接续验证

## 当前结论

原E目录、codex/per-drive-energy-20261012，基于29ac70c的最新45文件冻结。Jovi确认其他writer停写并授权统一修复、验证、手机升级、main合并及小程序接续。Owner611文件已逐hash备份。候选源码已提交并推送347d4fc8e3e7655f6fa7bedc834403509d0bb5e3，tree6e9c9cf7b0be1d01f579566a02f5aa1277cfd7c9，parent29ac70c815f3f4bb0cd755fa6cdc01858cc20437。请审查git diff 29ac70c..347d4fc与真实消费者，不以HEAD工作区无业务dirty当作无修复。当前Chat集中复审进行中；尚未安装本轮包或合并main。

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

## 固定候选与待验证门

候选Actions38494669865已经启动，head_sha347d4fc，当前in_progress，不能PASS。最终签名包正在从该SHA重建。只读源码审查与源层验证不会代替CI、手机或自然Fleet验收。

实际固定候选APK核验：49/2.1.30（BuildConfig与固定源码一致，前期48标签已过期），GIT_SHA347d4fc，非debuggable且Release签名有效，APK SHA25694E43378A918C0A7A61088D703C136F0304F5F23AEDD64A934BF118EA02826D3。固定SHA重建2m55s退出0；手机仍47，未安装。

## Iteration11新增404统计缺陷：实际修复

原CR1–CR4审核接受，CR4反证接受。新增P1真实diskRoom RED2/2断言失败：404后可信统计[2,12,1.5,2,10,2]变为[0,0,null,0,0,0]，原quality/reason被覆盖。修复只使用独立scoped本地envelope recovery标记，sourceJson/raw_json原字节和摘要资格保持；六个pending查询保持有界并不引入JSON1依赖，新实际回执可重入，旧legacy不自动升级，详情成功清标记。补充相同充电raw receipt不因本地元数据重新编码而冒充新receipt。

最终5文件增量只读审查APPROVE无P1/P2；聚焦38/0失败/0错误/0跳过，2m4s。先前347d候选Actions38494669865已实际13/13SUCCESS，Android artifact11864620990及后端11863357992保留；不能替本次5文件补充修复背书。最新45文件freeze已更新，前版另存cr-source-freeze-before-terminal404.json。

接下来提交补充源码并并行执行同SHA全量Debug/Release/lint/R8签名及LinuxCI；本轮49/2.1.30、手机现装47，新的最终包必须绑定补充提交。待同版本完整验证和集中SOURCE_APPROVED，才覆盖手机、合并main、转小程序。
