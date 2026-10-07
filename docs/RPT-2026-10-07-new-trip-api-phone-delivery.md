# 2026-10-07 自然新行程/502：最小API与手机实际交付

## 当前结论与边界

用户确认当天自然新行程已在手机刷新显示；只读UI确认“周三·10月7日·12:42”。本次新行程同步恢复已经实际验收。不能把所有历史空字段、此前进程退出根因、所有未来502、旧六条SOC回填或整版驻车发布一并标成完成。

## 直接原因与已确认缺陷

手机13:54:57历史身份请求502。合法授权的服务端脱敏日志确认13:54:55–58有9条上游提前关闭/502，含history-context；API13:55:00退出、13:55:04重新启动。身份失败使UnifiedHistoryRepository只保留同身份已验证缓存，不请求新分页，因此新的云端记录不会进入这次刷新。该保护正确，本次没有绕过身份/缓存。

源云13:57–58已新增同一自然行程，372条完成记录、积压0；该行程3100个SOC/速度/功率采样逐一一致。它的距离高于旧卡片阈值，所以不是未安装的UI过滤补丁造成。

实际生产树3f774ce内vehicleItems/readiness为数量/来源调用完整history，已确认不必要的全路线物化；列表PG本来只读端点，但全部摘要在Go中分页；详情本来单条，但使用Background丢弃请求取消。还有drive摘要battery_details固定null、计数读取失败伪装0。最小修复改为COUNT/EXISTS/来源标量、DB分页、单详情与请求context，补首尾有效SOC标量和unknown计数状态。

本次退出没有查到相应kernel OOM证据，不能宣称OOM根因已被证实。代理上游关闭与进程重启已证实。以后其他网络、进程、数据库故障仍可能产生502；这次已删除已确认的重型读路径，保留故障时的正确身份保护。

## 远端提交、本地验证与实际上线

冻结生产基线a4d214b0，tree3f774ce；最小代码36d630a8ba72f40b72628294b5bba43f4efd9724，tree894ec4ceda4932c42fdec63cd612c6026f97f3c5。原Project远端实现并独立审核PASS；Actions37584926711 success。

本地Go/隔离PG16：264项PASS，零失败/跳过；vet/mod/build及LinuxGo1.22 focused race PASS。合成资源比较仅是合成证据，不外推生产性能。只读复审确认旧空source映射、日期、source及owner/car/kind隔离保持，真实SOC0保留、缺失/非法不填0。

16:14中国时间仅切换jourvolt-pilot-jourvolt-dev-api-1：运行build为api-bounded-36d630a8-tree-894ec4ceda49，镜像config339ad919ccd6af1f11bd22c650b36e238ef22eee39300ea2447beb476e7682e9。runtime继承原生产镜像，只换LinuxCGO0 binary；binarySHA561c7e98ca4e135c5bd9fbcc08f10251ceeb290d97a442afd4a9781a29a13a65。公网/回环health和ready200；未认证历史仍401。

原配置、有效运行环境盲哈希与数据库结构哈希前后一致，没有schema迁移、历史UPDATE、bridge升级、车辆命令、凭据或TTL改变。旧镜像与原配置保留，可只回滚API镜像。新容器restart0是新容器计数，不能和旧容器restart2直接比较为“永久稳定”。截至16:29后续观测仍健康，无新增重启。

## 手机实际交付

为避免直接安装整版驻车，从原手机build43精确源7f9038ac/treeae3b74回移卡片可见性及原停车资格分离，只升级版本44。手机源2d1828be6a5e3021951a2dca8d3637a69ecae491；版本2.1.25/build44；同签com.matelink Release。

Debug638零失败，Release638零失败/8跳过；Release lint0错误241警告8信息，R8/signed build PASS。四项卡片/停车回归通过。签名SHA2569AB144E824ABF26A5941819ABB06831288C36A8BFE622657E3DC9D88281FC774；APK SHA256E5CF5ADA3A1AA6F19A55AA544D3B709E5E492768CBBE37B3C2729D08AF92A584。

实际仅adb install-r覆盖；build43→44，firstInstallTime2026-08-31 22:36:47不变。启动进程存活，FATAL/ANR零。OEM/其他应用SQLite日志不能计为MateLink错误；本次不清数据、日志或卸载。

用户16:30前后确认新行程显示。16:29:15–18代理摘要有受保护history-context200及9个drive page200，与用户刷新时间一致；摘要不含客户端身份，结合真实UI与用户确认作关联证据，不仅凭200认定数据正确。详情起止电量手机展示未单独获得确认，不能升级为端到端SOC展示PASS。

## 可复用经验

入口docs/HISTORY-SYNC-SOC-502-DEBUG.md。每次先分源→bridge→云→认证身份→分页→缓存/UI，并标记真实观测时间。源码修复、CI、本地构建、生产部署、手机安装和自然数据验收分别记录。

普通角色PermissionError后应检查原有精确委派读取权限；有既有合法权限才在服务端按窗口输出脱敏摘要，无授权则停止。不能改权限或用host挂载绕过。

Docker跨engine image ID可能是不同manifest表示：本次原archive config仍49c272、本机载入显示950e61。必须验证归档config摘要及runtime身份，不能盲照搬ID。临时APK下载目录要先创建，不可绕过签名读取失败的门禁。

源点存在重复时间时，SOC赋值必须证明同时间组值一致或按可靠附加字段匹配；浮点/Decimal精度不能在恢复候选中丢失。这次旧六条只准备，不执行。

## 仍待独立处理

旧六条SOC恢复范围审批/最终执行；新行程详情SOC展示确认；此前退出原因完整RCA及更长稳定性观察；bridge可写层持久化；温度/直接能耗等未承接字段；整版驻车生产与真机验收。未经确认的未知值保持null。
