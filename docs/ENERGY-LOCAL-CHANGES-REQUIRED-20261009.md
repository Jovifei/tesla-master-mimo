# 本地独立接收：CHANGES_REQUIRED

产品源fafeac2661712565a0409846cead3d44724222d9，tree0e06ebcadd647b8cd8bc63b95cb025eb57345eb9，parent9c202491。收到PR17整包候选及两份交接文档后开始独立验证，不改业务源码。生产API仍bb09、手机仍45，未装未合并。

## Android真实编译失败
JDK17及既有Android SDK，本地执行testDebugUnitTest筛选6+1独立回归。生产compileDebugKotlin先失败，Gradle退出1、BUILD FAILED in1m5s；因此7项独立测试NOT_RUN，不把编译失败计为测试断言失败。
AnalysisSummary.kt:78、83、110共6个编译诊断：kotlin.Double?只能safe/non-null调用，nullable operator不可用。真实编译错误需修实现及调用者的unknown/0/negative语义，不能强制!!或填0掩盖未知。完整Debug/Release/lint/R8仍待复验。

## Go三项真实独立回归FAIL
本地go test -count=1 -run '^TestIndependent' ./...退出1，三项全FAIL，7秒内完成，仅内存机器/合成记录，不运行openStore或连接生产。
1. startDC100@start → DC102@end → DC1reset@end → Complete@end，各event ID不同。复位清空EnergyAdded但未保存无效标记/复位点，完成重算丢掉reset，实际battery_input quality=reported/value_kwh=2；期望unknown。复位证据需持久到完成、restart和读列表/详情，不仅active状态内。
2. AC计数器全程0、battery DC计数0→8，完整窗口无实际AC模式证据，publishChargeEnergyContract实际宣称charge_type=ac；期望不据零AC字段确定AC模式。
3. 完整窗口AC计数0→10、battery0→9，未证明整段AC（可能混合AC/DC），实际发布ac_loss与90%效率；期望未知，须同模式/同用途/同窗口观测。单独battery-side计数值可保留，不能把全会话battery能量当仅AC部分。

测试文件energy_independent_counter_test.go及两个Android独立回归作为版控夹具回传。日期/计数全合成，独立期望不调用产品函数计算。

## 恢复同一个完整阶段
远端自行修完整源码和回归、真实CI，更新同一PR17固定SHA/交接；本地再整包复验、签名安装、真实已有记录显示和数据保留、main整合，不微派或本地接管业务。已授权源码/候选CI/main验证后合并；生产写/bridge/旧六SOC/7113仍独立门。不要把Go/CI绿或Android-summary信息job当整体软件通过。当前真实Android编译及三个物理计量问题阻止安装、main合并。
