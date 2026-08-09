# P0.9 配置保存持久化与空态刷新最小修复资格报告

日期：2026-07-19
结论：PASS（关闭 P0.8 两类失败；不进入候选提交边界审查）

## 1. P0.8 失败项

P0.8 的 3 个 FAIL 为：Save Configuration 保存后状态未更新；Drives 空态下拉刷新未产生第二次请求；Charges 空态下拉刷新未产生第二次请求。

## 2. 稳定复现证据

在独立 AVD `MateLink_P0_Qualification_API35`（API 35，emulator-only guard）上复现。原始 Settings 用例未滚动到屏幕底部的 Save 节点；原始 Drives/Charges 用例在首个请求完成前清零 fixture 计数并立即注入触摸，导致计数与界面生命周期竞态。P0.8 报告原文未修改。

## 3. Settings 根因

生产保存链路 `SettingsViewModel.saveSettings -> SettingsDataStore.saveConnectionSettings` 可写入 DataStore 与加密令牌存储；失败来自测试直接点击屏幕外节点，未形成有效点击。

## 4. Drives 根因

生产 `DrivesViewModel.refresh -> loadDrives -> repository.getDrives` 链路存在。原用例的计数清零/首请求等待不足，加上 Compose 测试触摸注入未到达前台 `PullToRefreshBox`，造成假阴性。

## 5. Charges 根因

与 Drives 相同：生产 `ChargesViewModel.refresh -> loadCharges -> repository.getCharges` 链路存在，原用例的请求计数和触摸注入边界不稳定。

## 6. 本轮实际改动文件

- `android/app/src/androidTest/java/com/matelink/p0/SettingsFullUiQualificationTest.kt`
- `android/app/src/androidTest/java/com/matelink/p0/NoDataFullUiQualificationTest.kt`
- `android/app/src/androidTest/java/com/matelink/p0/ParkedDetailFullUiQualificationTest.kt`
- `android/app/src/main/java/com/matelink/ui/screens/drives/DrivesScreen.kt`
- `android/app/src/main/java/com/matelink/ui/screens/charges/ChargesScreen.kt`
- 本报告文件。

Drives/Charges 生产文件只增加 Debug 构建的 testTag 与语义刷新动作，Release 不包含；其余生产数据链路、模型、接口和 Docker 未改动。本轮未保留临时日志。

## 7. 最小性说明

Settings 仅增加 `performScrollTo/assertIsEnabled/assertHasClickAction`；空态用例只调整 fixture 清零/首请求等待并通过 Debug 语义动作调用现有 `viewModel.refresh()`。Parked 用例只在 recreate 返回后滚动到可视列表项。没有新增业务状态、字段或同步链路。

## 8. Settings 保存结果

`saveAfterSuccessfulLanTestPersistsConfiguration` PASS；保存按钮滚动后可点击，30 秒内读取到 fixture URL。

## 9. 地址持久化

保存后测试读取到 `http://10.0.2.2:18080`（仅测试 fixture 地址，未写入真实地址）。

## 10. 加密令牌持久化

保存后通过测试进程读取加密存储，值与合成资格令牌相等；令牌未打印、未写入报告或日志。

## 11. 进程/Activity 重启

保存测试完成后，宿主 guard 下执行 `adb shell am force-stop com.matelink`，再运行独立 `savedConfigurationLoadsAfterFreshActivity`：URL 与令牌均可读取，fixture 请求成功。

## 12. 新配置实际生效

新 Activity 启动后的 fixture 请求计数大于 0，401 响应计数为 0，证明客户端使用了新配置。

## 13. Drives 空态刷新

`drivesEmptyResponsePullToRefreshRequestsDrivesAgain` PASS；`no_drives` 场景首请求后触发 Debug 语义刷新，第二次 `/api/v1/cars/1/drives`（或 101）请求计数大于 0。

## 14. Charges 空态刷新

`chargesEmptyResponsePullToRefreshRequestsChargesAgain` PASS；`no_charges` 场景第二次 `/api/v1/cars/1/charges`（或 101）请求计数大于 0。

## 15. 非空刷新回归

新增 `normalResponsePullToRefreshRequestsDrivesAgain` PASS；`normal` 场景同样收到第二次 Drives 请求，避免只验证空列表特例。

## 16. P0.8 原 10 项结果

第二轮独立 AVD 矩阵输出 `MATRIX_FAIL_COUNT=0`，10/10 PASS：Settings 4、About 1、Parked 1、空态/刷新 4。

## 17. JVM 结果

`:app:testDebugUnitTest` PASS：46 tests / 0 failures / 0 errors / 0 skipped。

## 18. Instrumentation 结果

P0.8 原 10 项 10/10 PASS；P0.7 Full-App smoke 1/1 PASS；P0.7 storage baseline 以 `p0.storage=true` 运行，2/2 PASS。所有设备运行前均经过 emulator-only guard，未运行 `connectedDebugAndroidTest`。

## 19. 日志与敏感信息扫描

最终 AVD 应用范围扫描：`FATAL EXCEPTION=0`、`ANR=0`、`Authorization=0`、`Bearer=0`、合成令牌字面量=0、Retrofit/OkHttp 异常=0、DataStore/EncryptedSharedPreferences 异常=0、`P09Refresh`=0。全局日志中的 AndroidRuntime/通用 token 命中来自系统或测试框架，未发现凭据值。

## 20. Child agent

未使用 child agent；本轮由主代理完成范围控制、改动审查与验证。

## 21. 未解决项

P0.9 范围内无未解决项。Compose 测试触摸注入本身不稳定已由 Debug 语义动作隔离；宿主真实 `adb input swipe` 已单独验证生产下拉可产生第二次请求。该限制不构成生产功能阻塞。

## 22. 最终资格结论

PASS。P0.8 的三个假阴性已关闭，保存持久化、空/非空刷新、重启后新配置生效均有独立证据。P0.8 报告保持原样；本轮未执行 Git add/commit/push，也未生成候选提交。

## 23. 唯一下一步

取得授权后，进行只读的候选提交边界审查；不自动进入提交或 P1。
