# 独立完整复验：84abb526（仍CHANGES_REQUIRED）

固定源84abb526d4e6c92edf66a7d820746758b46a9013，treef167124275500a7d03d2b7ca6887c28d1ec2b660，parent9f91a25e。没有本地业务源码改动。

- 三个原样独立TestIndependent*计数器回归PASS。
- Windows Go全包及一次性PG16：295 run /295 pass /0 fail /0 skip，退出0。
- Linux go1.22.12/gcc既有测试镜像，隔离PG16，完整-race PASS；vet、mod verify（all modules verified）、build均退出0。第一次离线镜像缺依赖为ENV失败；以既有Windows模块缓存只读挂载后实际复验通过，没有下载替代源/改网络。
- Android完整Debug真实执行709，707 PASS/2 FAIL/0 skip，Gradle退出1。失败后Release/lint/R8/签名APK尚未运行，不能算PASS。
- 原独立EnergyContractIndependentRegressionTest 6/6 PASS、EnergyPhysicalPurposeRegressionTest 1/1 PASS。

## 两个未关闭用例
1. EnergyWindowAndParkingRegressionTest.legacyChargeEnergyPairsCannotImplyACLossOrEfficiency：NullPointerException，行66。旧case只有batteryInput/acInput端点和chargeType=ac，就强制efficiency!!=80；新合同需要完整合格AC窗口/平衡证据，此fixture未给。请远端维护其完整物理用途覆盖：无模式/平衡证据必须null；完整有效AC fixture仍应验证80，不能简单删除有用断言。
2. SettingsExperienceContractTest.currentReleaseShowsVersionAndLocalizedRepairNotes：AssertionError，行51。测试仍硬编码versionCode45、2.1.26，候选已46/2.1.27。请绑定真实版本并核中文/英文发布说明，不以改回旧版本或关用例过关。

本地未调整业务、关闭失败或放宽预期；源码/fixture应远端修完整阶段并真实CI，交新的固定SHA。手机45及生产bb09未变、未安装/合main。合成和工具证据不是自然Fleet/新通知验收。
