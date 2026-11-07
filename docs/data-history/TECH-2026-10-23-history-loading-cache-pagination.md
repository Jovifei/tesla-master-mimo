# 充电历史与行程页面加载体验、本地持久化与无限滚动方案 (Build 67 / v2.1.48)

## 1. 概述与背景
在先前的版本中，用户在进入或刷新“充电历史”和“行程记录”页面时存在以下痛点：
1. **空白全屏遮挡**：首屏或切 Tab 时直接触发全屏 Loading 占位（`MateLinkLoadingPlaceholder`），原有数据完全消失，呈现整屏灰白单调等待。
2. **本地未即时呈现**：虽然本地 SQLite (Room) 有持久化数据，但页面未在网络发起前同步提取渲染，导致每次进入页面都需要等待网络下发才能看清列表。
3. **时间跨度与充电频次不符**：充电历史默认展示近 7 天，而新能源车主往往几天才充一次电，7 天内记录稀少；
4. **历史数据查阅受限**：缺乏触底向上滑动时的无缝分页追加机制，用户无法直观翻阅更早的历史。
5. **文案冗余**：顶部常驻“此列表使用已验证的云端来源，原手机记录…”等无实质价值的提示，干扰视觉。

经用户实机确认与技术路线测试通过，实施本方案并正式交付 `Build 67 (v2.1.48)`。

---

## 2. 关键架构与技术实现

### 2.1 彻底消除空白遮挡（图一方案）
- **根因与重构**：排查并移除了 `ChargesScreen.kt` 和 `DrivesScreen.kt` 根布局中的 `if (uiState.isLoading && !uiState.isRefreshing) { MateLinkLoadingPlaceholder(...) }` 分支；
- **内容常驻**：无论当前是否处于初次加载或后台刷新状态，根布局始终渲染列表与图表内容；
- **轻量半透明浮层**：后台或跨时间段刷新时，使用 `rememberDebouncedLoading` 驱动的居中遮罩浮层：
  - 遮罩透明度调整为 `Color.Black.copy(alpha = 0.20f)`，背景既能清晰辨认又具备层次感；
  - 旋转指示器尺寸扩大至 `MateLinkLoadingMark(size = 150.dp)`，居中流畅旋转。

### 2.2 本地 Room 数据库优先读取（0ms 秒开）
- 在 `ChargesViewModel.loadCharges` 与 `DrivesViewModel.loadDrives` 开始网络请求之前：
  1. 检查内存列表若为空，直接从本地数据库同步读取全量缓存（`chargeSummaryDao.getAllForCar(id)` 与 `driveSummaryDao.getAllChronological(id)`）；
  2. 映射为展示模型并立即触发 `applyFiltersAndUpdateState()` 刷新 UI 状态；
  3. 用户进入界面或切换 Tab 即可在 **0 毫秒** 内看到上次保存的完整记录，无任何空白或重置跳动；
  4. 网络成功返回后无缝合并更新并落库。

### 2.3 差异化默认时间跨度
- **充电历史 (`ChargesScreen`)**：默认值严格调整为 **近 30 天**（`DateFilter.LAST_30_DAYS`），覆盖用户一个月内的完整充电频次。
- **行程记录 (`DrivesScreen`)**：默认值保持为 **近 7 天**（`DriveDateFilter.LAST_7_DAYS`），聚焦近期出行规律。

### 2.4 触底无限加载（滑动翻页）
- **滚动监听**：通过 `LazyListState` 配合 `derivedStateOf` 实时计算可见项与总列表长度：
  ```kotlin
  val shouldLoadMore by remember {
      derivedStateOf {
          val totalItems = listState.layoutInfo.totalItemsCount
          val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
          totalItems > headerCount + 1 && lastVisibleIndex >= totalItems - 2
      }
  }
  ```
- **自动步长扩展**：
  - 充电历史：触底时自动向更早时间回溯 30 天（`stepDays = 30L`），拉取并追加历史充电记录；
  - 行程记录：触底时自动向更早时间回溯 7 天（`stepDays = 7L`），拉取并追加历史行程详情；
- **底部加载状态**：列表底部提供原生小巧的加载指示器（`CircularProgressIndicator` + `正在加载更多历史记录…`）。

### 2.5 首页与细节优化
- 首页车辆形状与图片尺寸放大约 2/3，车辆比例更协调居中；
- 移除充电与行程页面顶部的数据来源说明卡片，界面纯净简洁。

---

## 3. 验证与交付物

### 3.1 自动化测试
- 执行 `testDebugUnitTest` 核心回归覆盖：
  - `SettingsExperienceContractTest`：版本号 67、`v2.1.48` 严格契约通过；
  - `DrivesViewModelTest` / `ChargesViewModelTest`：状态映射与分页流控通过。
- 编译结果：`BUILD SUCCESSFUL`，所有测试 100% PASS。

### 3.2 实机安装与运行截图
- **设备**：OnePlus 7 Pro（Android 11，设备序列号 `6e4fa92f`）；
- **安装状态**：Streamed Install `Success`；
- **核心验证截图记录**：
  1. `b67_verify_launch.png`：首页车辆饱满大图，仪表盘与各项数据即时在线；
  2. `b67_verify_drives.png`：行程记录默认选中“近 7 天”，本地数据 0ms 秒出，行程与驻车卡片完整；
  3. `b67_verify_drives_scrolled.png`：行程记录向下滑动顺畅，触发翻页追加更早记录；
  4. `b67_verify_charges.png`：充电历史默认严格选中“近 30 天”，30 天柱状图与统计数据完整展现；
  5. `b67_verify_charges_scrolled.png`：充电历史滑动翻页加载更早记录正常；
  6. `b67_verify_about_page.png`：关于页面确认版本号为 `MateLink v2.1.48 (Build 67)`。

- **正式交付物**：
  `E:\Claude_allow\Download\MateLink-2.1.48-build67-release.apk`
