# Debug 状态验证页实施计划

## 目标与边界

- 用独立 `com.matelink.test.mock` Debug 包验证本轮 Dashboard/当前充电的状态化 UI。
- 仅触及 `android/app/src/debug/`，以及将既有 `CurrentChargeParametersCard` 和 `VehicleOpeningAlert` 放宽为 `internal` 以原样复用；不得改变 Release 行为、正式包、DataStore、网络配置或车辆数据。
- 不运行 instrumentation；不卸载正式 `com.matelink`；不 stage、commit 或 push。

## 实施与证据

1. 在 `src/test` 先增加 Debug 场景 fixture 合约测试，涵盖驾驶回收、开口+TPMS、AC、DC 和缺失字段；初次运行必须因 fixture 尚不存在而失败。
2. 在 `src/debug` 新增 `StateScenarioFixtures`、`StateScenarioReviewActivity` 和 Compose 页面。页面只消费固定本地对象，并调用既有 `VehicleHeroGraphic`、`drivingTelemetryFor`、`openVehicleOpenings`、`warningTires`、`chargePhaseFor`、`formatChargeMetric` 与 `CurrentChargeParametersCard`。
3. 最小放宽 `CurrentChargeParametersCard` 与 `VehicleOpeningAlert` 的可见性，不改变参数、格式化或 Release 调用路径。
4. 重跑 Debug/Release JVM 定向测试并构建 Debug APK；核对 applicationId 为 `com.matelink.test.mock`。
5. 在实体机仅安装该独立包，显式启动 Debug Activity，依次验证五种场景；正式包保持已安装且不启动 instrumentation。

## 停止条件

- Debug 包名不是 `com.matelink.test.mock`、Release 产物包含该 Activity，或任何测试/构建失败时停止并报告。
- 设备不再可用时只保留本地测试证据，不改正式配置。
