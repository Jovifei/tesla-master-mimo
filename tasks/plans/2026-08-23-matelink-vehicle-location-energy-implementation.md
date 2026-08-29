# MateLink 车型、地点、充电驻车与待机能耗实施计划

## 当前目标与边界

- 目标：实施已获 Jovi 批准的方案 B，完成车型外观、中文地点、充电驻车跳转、充电列表直改总价、CNY 缺省迁移和可信待机能耗分析。
- 已有证据：`com.matelink` 已同签名覆盖到实体手机；Dashboard、行程、充电、更多、统计与设置真机回归通过；本轮起点的 Android JVM 测试为 570 个通过、lint 195/0 Error/`MissingTranslation=0`/无 baseline。
- 明确禁止：不下载 Tesla 官网/媒体图片；不使用 Tesla 密码、真实 OAuth 凭据或新地图 Key；不改服务器部署、域名、包名、签名、既有用户设置；不 stage/commit/push；完成模拟器/本地回归前不覆盖实体手机。
- 允许范围：`android`、`deploy/teslamate-home-docker/adapter`、`docs`、`tasks`、本项目 Obsidian 和允许下载目录中的原创图像资产。

## 增量 1：费用与地点状态的客户端收口

- 目的：把充电卡“修改总价”改成直接弹窗；修复缺失货币偏好错误推断 EUR；让地点识别状态可读且不再粉色误导。
- 测试先行文件：
  - `android/app/src/test/java/com/matelink/data/local/SettingsDataStoreCurrencyMigrationTest.kt`
  - `android/app/src/test/java/com/matelink/ui/screens/charges/ChargeListCostEditContractTest.kt`
  - `android/app/src/test/java/com/matelink/ui/screens/stats/LocationRecognitionCopyContractTest.kt`
- 生产文件：
  - `android/app/src/main/java/com/matelink/data/local/SettingsDataStore.kt`
  - `android/app/src/main/java/com/matelink/ui/screens/charges/ChargesScreen.kt`
  - `android/app/src/main/java/com/matelink/ui/screens/charges/ChargesViewModel.kt`
  - 新增 `android/app/src/main/java/com/matelink/ui/screens/charges/ChargePriceDialog.kt`
  - `android/app/src/main/java/com/matelink/ui/screens/charges/ChargeDetailScreen.kt`
  - `android/app/src/main/java/com/matelink/ui/screens/stats/StatsScreen.kt`
  - `android/app/src/main/res/values/strings.xml`、`values-zh/strings.xml`
- 证明命令：针对三个 JVM 测试先 RED 再 GREEN；随后 `:app:testDebugUnitTest`。
- 状态标签：`COST_LOCATION_UI_PASS`。
- 失败处理：保持原详情页编辑能力；新列表弹窗未通过时不改变已有覆盖金额。

## 增量 2：高德中文地点解析

- 目的：大陆坐标以缓存优先、高德 Android SDK 逆地理编码得到中文地点；不再对地址解析发送坐标到 Nominatim。
- 测试先行文件：
  - 新增 `android/app/src/test/java/com/matelink/data/repository/ChineseLocationResolverTest.kt`
  - 更新 `android/app/src/test/java/com/matelink/domain/map/AmapConfigurationTest.kt`
  - 新增 `android/app/src/test/java/com/matelink/data/repository/GeocodingExternalBoundaryTest.kt`
- 生产文件：
  - 新增 `android/app/src/main/java/com/matelink/data/repository/AmapReverseGeocoder.kt`
  - `android/app/src/main/java/com/matelink/data/repository/GeocodingRepository.kt`
  - `android/app/src/main/java/com/matelink/data/repository/GeocodingAccessPolicy.kt`
  - `android/app/src/main/java/com/matelink/data/sync/GeocodeWorker.kt`
  - `android/app/src/main/java/com/matelink/widget/CarWidgetUpdateWorker.kt`
  - 必要时 `android/app/src/main/java/com/matelink/di/NetworkModule.kt`
- 证明命令：JVM provider/缓存/未配置边界测试；源码契约确认地址解析路径不调用 Nominatim；`assembleDebug`。
- 状态标签：`AMAP_LOCATION_PASS`。
- 停止条件：没有已验证高德 SDK Key 或隐私同意时只显示未配置，不产生外部回退请求。

## 增量 3：充电驻车关联与详情视觉

- 目的：Adapter 用真实时间重叠关联驻车与充电；Android 在关联存在时一跳到正确充电详情，普通驻车采用语义卡片。
- 测试先行文件：
  - `deploy/teslamate-home-docker/adapter/cmd/adapter/main_test.go`
  - 新增 `android/app/src/test/java/com/matelink/ui/screens/drives/ParkedChargePresentationTest.kt`
  - 新增 `android/app/src/test/java/com/matelink/data/api/models/ParkedDetailCompatibilityTest.kt`
- 生产文件：
  - `deploy/teslamate-home-docker/adapter/cmd/adapter/main.go`
  - `android/app/src/main/java/com/matelink/data/api/models/AdapterModels.kt`
  - `android/app/src/main/java/com/matelink/ui/screens/drives/ParkedDetailScreen.kt`
  - `android/app/src/main/java/com/matelink/ui/screens/drives/ParkedDetailViewModel.kt`
  - `android/app/src/main/java/com/matelink/ui/navigation/NavGraph.kt`
- 证明命令：`go test ./... -count=1`、Adapter HTTP 契约测试、Android JVM 测试。
- 状态标签：`PARKED_CHARGE_ROUTE_PASS`。
- 回滚：缺少 `linked_charge` 的旧 Adapter/云响应保持普通驻车详情，不阻断历史浏览。

## 增量 4：待机能耗窗口与透明分析

- 目的：基于真实停车位置采样提供 7/30/365 天、全部/自定义待机分析；仅在足够覆盖时计算 kWh/W，并展示空调实测、哨兵未采集和数据不足原因。
- 测试先行文件：
  - 新增 `deploy/teslamate-home-docker/adapter/cmd/adapter/standby_test.go`
  - 新增 `android/app/src/test/java/com/matelink/domain/analytics/StandbyWindowAnalysisTest.kt`
  - 新增 `android/app/src/test/java/com/matelink/ui/screens/vampire/StandbyEvidencePresentationTest.kt`
- 生产文件：
  - `deploy/teslamate-home-docker/adapter/cmd/adapter/main.go`
  - `android/app/src/main/java/com/matelink/data/api/TeslaMateApi.kt`
  - `android/app/src/main/java/com/matelink/data/api/models/AdapterModels.kt`
  - `android/app/src/main/java/com/matelink/data/repository/TeslamateRepository.kt`
  - 新增 `android/app/src/main/java/com/matelink/domain/analytics/StandbyWindowAnalysis.kt`
  - `android/app/src/main/java/com/matelink/ui/screens/vampire/VampireViewModel.kt`
  - `android/app/src/main/java/com/matelink/ui/screens/vampire/VampireScreen.kt`
  - `android/app/src/main/res/values/strings.xml`、`values-zh/strings.xml`
- 证明命令：Go 单元/HTTP 测试；Android 纯函数和 ViewModel 测试；历史不足、充电重叠、覆盖不足、空调归因、无哨兵字段等边界。
- 状态标签：`STANDBY_EVIDENCE_PASS`。
- 停止条件：现有 TeslaMate 数据没有可验证功率覆盖时，不显示 W/kWh 或任何原因推断。

## 增量 5：原创车型图与用户图片覆盖

- 目的：实现自动车型/颜色外观图与用户相册覆盖，不使用 Tesla 官网素材。
- 先决条件：使用图像生成工具创建无 Logo、无官网图、无商标暗示的本地原创车型类别渲染资产；导出到允许下载目录后审阅，再复制到 Android assets。
- 测试先行文件：
  - 新增 `android/app/src/test/java/com/matelink/domain/model/VehicleProfileResolverTest.kt`
  - 新增 `android/app/src/test/java/com/matelink/data/local/VehiclePhotoStoreTest.kt`
  - 新增 `android/app/src/test/java/com/matelink/ui/screens/dashboard/VehicleProfileContractTest.kt`
- 生产文件：
  - 新增 `android/app/src/main/java/com/matelink/domain/model/VehicleProfileResolver.kt`
  - 新增 `android/app/src/main/java/com/matelink/data/local/VehiclePhotoStore.kt`
  - 新增 `android/app/src/main/java/com/matelink/ui/components/VehicleProfileImage.kt`
  - `android/app/src/main/java/com/matelink/ui/screens/dashboard/DashboardScreen.kt`
  - `android/app/src/main/java/com/matelink/data/local/SettingsDataStore.kt`
  - 新增 Android asset 文件和中英文资源。
- 证明命令：车型/颜色/回退/照片重置 JVM 测试；构建后资源清单检查不含 Tesla 官网 URL、Tesla logo 或测试图；截图审查 Dashboard。
- 状态标签：`VEHICLE_PROFILE_PASS`。
- 停止条件：图像生成结果不能满足原创和无 Logo 规则时，不打包图像，保留现有 Hero 并报告缺口。

## 统一验证与设备交付

- 命令：Android `:app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease :app:lintRelease`；Adapter `go test ./... -count=1` 与 `go vet ./...`；`git diff --check`。
- 质量门禁：`MissingTranslation=0`，不建立 lint baseline；lint 结果仅作为静态质量/多语言覆盖/发布门禁，不能当成运行时 Bug。
- 设备：先模拟器或专用环境验证完整交互；Jovi 已允许最终同签名覆盖，但只有所有门禁和模拟回归通过后，才重新比对证书并执行 `adb install -r`。不使用 instrumentation、卸载或清数据。
- 最终状态：`LOCAL_FEATURE_CANDIDATE_PASS` 或 `PHONE_SMOKE_PASS`；真实 Tesla OAuth/Fleet/公网 Pilot 单独保留外部门禁。
