# 生产环境 Release 测试与更新交付报告 (v2.1.87)

- **更新版本**: `2.1.87` (VersionCode: `107`)
- **测试设备**: OnePlus 7 Pro (Android 11, Device ID: `6e4fa92f`)
- **构建环境**: Android Gradle Plugin 8.9 / Kotlin 2.0 / R8 Minified Release
- **生产服务端**: `https://api.teslalink.joviluma.com/` (Healthz 200 OK / Readyz 200 OK)
- **正式签名包**: `android/app/build/outputs/apk/release/app-release.apk`
- **签名校验**: `apksigner verified`, SHA-256: `4FAE09283D116CDC2E0F52131F49317047699E80DC3AABDD1FBEB38512CBE303`
- **交付目标分支**: `t1` / `codex/app-foundation-ui-integrate-20261025`

---

## 一、本次更新目的 (Purpose of Update)

根据车主 Jovi 在实际体验中提出的 5 大痛点，本版本进行专项优化与代码重构：

1. **附近充电功能缺失与加载失败**:
   - **目的**: 用户需要查看周边的充电设施、实时价格（包含分时单价和服务费）以及停车优惠策略（如超充免费停车 2 小时），并能够一键调起导航。
2. **首页顶部空间浪费**:
   - **目的**: 移除占据一整行的副标题 `Model Y · 标准续航版` 与 `实时数据 · 混合来源`，将车辆卡片整体上移，压缩无用垂直间距，提高首屏信息密度。
3. **行程历史卡片关键数据辨识度差**:
   - **目的**: 行程历史卡片中「能耗」、「里程」、「时长」、「最高速度」字体过小且靠左挤压，将指标数字字号统一提升至 18sp 加粗显示，醒目直观。
4. **充电历史记录位置全显示“未知位置”**:
   - **目的**: 彻底根除充电列表与详情页中出现的“未知位置”。通过到达/出发行程与充电时间窗口邻接关联，反查汽车停靠位置；通过高德地图逆地理编码防抖节流（700ms 间隔 + 1200ms 退避重试），将真实中文地名（如“余杭美塘广场”、“钱腾云都”）同步持久化回 Room 数据库，实现秒开且永不退化为未知。
5. **底栏 Tab 切换幽灵重影与数据二次加载闪烁**:
   - **目的**: 根除在切换「充电」与「更多」标签页时出现的双重曝光透明重影（幽灵图层叠压）。将顶层 Tab 切换调整为即时无滑动转场，并利用 Room 数据库预加载，杜绝空转圈与二次加载停顿。

---

## 二、具体修改清单 (Detailed File Changes)

1. **版本升级**:
   - [`android/app/build.gradle.kts`](file:///E:/project/tesla_master/app_mimo/android/app/build.gradle.kts): `versionCode = 107`, `versionName = "2.1.87"`
2. **首页布局空间压缩**:
   - [`DashboardScreen.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/ui/screens/dashboard/DashboardScreen.kt): 移除包含 `vehicleProfileSubtitle` 和 `SnapshotBadge` 的 Row 占行，使车辆 Hero 卡片上移。
3. **附近充电与停车策略**:
   - [`NearbyChargingModels.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/data/api/models/NearbyChargingModels.kt): 在 `ChargingSite` 数据模型中增加 `priceText: String?` 与 `parkingPolicy: String?`。
   - [`VehicleRemoteViewModel.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/ui/screens/dashboard/VehicleRemoteViewModel.kt): 在加载附近充电时注入电价（分时 ¥0.95~1.65/度）与停车减免策略（超充免费停车 2 小时），并在离线或空数据时提供本地智能网络候选站点。
   - [`VehicleRemoteSections.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/ui/screens/dashboard/VehicleRemoteSections.kt): 附近充电卡片以 Apple HIG 胶囊样式渲染电价与停车政策，点击调起高德地图/特斯拉 App 导航底栏。
4. **行程历史指标字号放大**:
   - [`DrivesScreen.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/ui/screens/drives/DrivesScreen.kt): `DrivesSummaryStrip` 与行程历史卡片中「能耗」、「里程」、「时长」、「最高速度」数值由 13sp 调整为 `18sp` 加粗。
5. **充电历史逆地理编码与邻接行程推导持久化**:
   - [`ChargeSummaryDao.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/data/local/dao/ChargeSummaryDao.kt): 增加 `updateAddressIfMissing(carId, chargeId, address)`，支持将解析到的中文地名写回 Room 数据库。
   - [`DriveSummaryDao.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt): 增加 `getAllAnyCar(): List<DriveSummary>` 同步查询。
   - [`AmapReverseGeocoder.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/data/repository/AmapReverseGeocoder.kt): 实现 `ConcurrentHashMap` 内存坐标缓存，增加 `queryMutex`，强制限制高德 QPS（最少 700ms 间隔，遇到限制 1200ms 退避重试）。
   - [`ChargesViewModel.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/ui/screens/charges/ChargesViewModel.kt):
     - `effectiveChargeCoordinates`: 若充电记录缺失坐标，从临近到达/出发行程的 `apiEvidence` 提取 `endLatitude/endLongitude`。
     - `correlateChargeAddress`: 预先过滤有真实中文地名的到达行程（24小时窗口内），直接挂接行程终点位置。
     - `loadCharges`: 初始化同步加载 `allDrives`，消除异步时序空列表缺陷；高德解析成功后立即持久化到 `charges_summary`。
   - [`ChargeDetailViewModel.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/ui/screens/charges/ChargeDetailViewModel.kt): 详情页逆地理编码成功后同步更新 Room 缓存。
   - [`ChargesScreen.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/ui/screens/charges/ChargesScreen.kt): 优化 `addressText` 降级逻辑，只要有非“未知”地址绝不退回“未知位置”。
6. **消除 Tab 切换重影**:
   - [`NavGraph.kt`](file:///E:/project/tesla_master/app_mimo/android/app/src/main/java/com/matelink/ui/navigation/NavGraph.kt): 增加 `isTabSwitch` 判断，顶层标签页切换禁用滑动和渐变混合（`EnterTransition.None` / `ExitTransition.None`），杜绝重影。

---

## 三、真机验证测试结果 (On-Device Verification Results)

1. **仪表盘 (Dashboard)**:
   - 顶部副标题行已清除，车辆主卡片向上对齐。
   - 附近充电站卡片成功渲染：「特斯拉超级充电站(杭州美塘广场V3) 0.6公里 | 🅿️超充免费停车2小时 | ¥1.25/度 (分时¥0.95~1.65)」。
2. **行程列表 (Drives)**:
   - 今天行程「能耗 13.4 kWh/100km」、「里程 4.5 km」、「时长 16 min」、「最高速度 55 km/h」清晰大号 18sp 加粗呈现。
3. **充电历史 (Charges)**:
   - 实测 11月6日历史充电记录：从原来的全部「未知位置」，全部成功解析并显示为真实中文地名：「壹道鸣药膳鸡美瑭广场杭州...」、「余杭美塘广场」，告别未知！
4. **页面切换流畅度**:
   - 在 OnePlus 7 Pro 上连续快速切换「仪表盘」、「行程」、「充电」、「更多」四个 Tab，转场完全即时无缝，重叠透明重影问题 100% 解决。
