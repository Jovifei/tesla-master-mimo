# 2026-10-07 字段修复实际执行回执

## 已完成
- API产品源bb09fac04d11796ce676555dad094776cd1ef0ce；CI-only头5ff1d49497b1cfd25be9f96d8d5c88ff3d491b94。PR15 Draft。
- CI37611040504 verify全部成功（full Go/PG、focused、race、vet/build）；实施agent本地282 test records零失败/跳过，父源码审查通过。
- 19:13:20–19:13:42北京时间（11:13:20–11:13:42 UTC）实际切换API-only，build api-fields-bb09fac-tree-e06e83b2dde8，image config5ee77870f46a584201cc16d4564d3113ade3f502dbbe97f08d5891c26c2e09bf。
- schema、原配置、有效环境盲hash保持；历史UPDATE0，无bridge重复升级；旧image339ad919与原三compose配置保留回滚。下次重建须复用现用四配置。
- 手机源码14ca59d54ba8c470abb45d01798a23919f47bb63，PR16 Draft。Debug656/Release656零失败（Release8跳过），lint0err242warn8info，signed/R8成功。
- 实际同签adb install-r44→45（2.1.26），证书9AB144…匹配，firstInstallTime仍2026-08-31 22:36:47，data_dir不变；未卸载/清数据/清日志/实机instrumentation。
- APK SHA256 b7c46f7fbffb88febb9477ae888da4be0f96a6347200a059175ef108546a3fd4。
- 修复：分页速度/充电SOC摘要、安全相邻驻车时间和观测SOC；手机kWh/100km及亚秒积分/90%覆盖限制、起止SOC、胎压去模拟/30天默认/旧记录来源隔离、真实变化提醒、行程与充电持久通知和前台提示。

## 仍未验收
- 自然新增行程/充电实际弹窗和系统通知：OPEN；不制造事件代替验收。
- 真实30天胎压手机历史：OPEN。19:01:11北京时间只读观测源最近30天7113条真实胎压；是否补入历史的用户范围答复待到，不默认扩大历史写权限。
- 手机全页面真实认证数据复验：OPEN。通用启动采样launch成功、PID16039alive、FATAL0，但全局ANR1/OEM SQLITE_OK归属仍需按进程和包名复核，不能直接判为MateLink ANR，也不能写启动全PASS。
- 本机执行通道随后失去响应，连只读echo无回执；未改系统/网络或绕权限。原远端源码审核PASS WITH REMAINING ACCEPTANCE GATES，不能将安装/CI绿升级为自然数据PASS。
- 旧六条SOC、bridge持久化及PR12整版验收仍独立，不在本次回填/发布范围。

## 入口与证据
详细排查见docs/RPT-2026-10-07-field-notification-audit.md（原目录实际记录）及docs/HISTORY-SYNC-SOC-502-DEBUG.md。
API回执：E:/Claude_allow/Download/matelink-api-fields-20261007/actual-api-deployment-receipt.json。
手机回执：E:/Claude_allow/Download/matelink-phone-fields-20261007/actual-device-install.json、apk-verification.json、phone-signed-build.log。
合成SQLite迁移确认旧行/真实0/null保留，Room21 schema匹配；不替代用户数据库和登录会话验收。

