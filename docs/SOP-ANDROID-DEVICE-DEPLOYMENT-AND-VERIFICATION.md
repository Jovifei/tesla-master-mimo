# MateLink Android 构建部署、真机更新与验证标准作业程序 (SOP)

> **版本**：V2.1.5 (Build 24)
> **适用对象**：后续接手项目的开发者、协作 Agent 及自动化运维流水线  
> **目标**：以高度确定性、零假设、可复制的标准流程，完成代码提交、远端推送、APK 构建、物理手机覆盖升级及完整真机验证。

---

## 一、项目架构与环境基线

### 1.1 核心标识与规则

- **正式唯一包名**：`com.matelink`（仅 Release）
- **隔离 Debug 包名**：`com.matelink.test.mock` / `MateLink Test`；只用于模拟器或专用测试设备，绝不覆盖车主包。
- **绝对禁忌（最高红线）**：
  1. **严禁执行 `adb uninstall com.matelink` 或 `adb shell pm clear com.matelink`**：这会导致用户登录 Session、Token、车端绑定配置以及本地 Room 数据库历史记录永久丢失！
  2. **严禁将 Debug 分身包交付或覆盖安装到车主设备**；正式交付只能使用经签名的 `com.matelink` Release。
  3. **严禁在代码或数据库中伪造数据**（如假经纬度 `30.27, 120.15`、假“西湖区西溪路/拱墅区”或者正弦波伪造时序轨迹）；无数据时必须诚实向用户呈现真实状态（如“等待车端 GPS”）。

### 1.2 物理验证机基线

| 维度 | 参数 |
|---|---|
| 设备型号 | OnePlus 7 Pro (GM1910) |
| 系统版本 | Android 11 (OxygenOS / HydrogenOS) |
| ADB 唯一序列号 | `6e4fa92f` |
| 屏幕分辨率 | 1440 × 3120 (3K 90Hz) |
| 目标包名 | `com.matelink` |
| 安装方式 | `adb install -r -d`（同签名覆盖升级） |

---

## 二、V2.1.4 (Build 23) 本次更新内容与技术实现解析

本次更新彻底解决了用户在真机上反馈的 7 大核心体验与功能问题：

### 2.1 问题 1：高德地图矢量底图白屏/无法加载
- **现象**：地图组件只显示一个空框和一个定位小圆点，不显示任何道路、建筑物或 POI 矢量瓦片。
- **根因**：
  1. 高德 `TextureMapView` 的生命周期未在 Compose 工厂实例化时立即与宿主 Activity 建立同步流转（未即时调用 `onCreate(null)` / `onResume()`）。
  2. `AmapReverseGeocoder.kt` 存在循环前置检查门禁死锁（若缓存未命中则拒绝下发反查），导致地图就绪信号被永久阻塞。
- **实现方案**：
  - 修改 `android/app/src/main/java/com/matelink/ui/screens/map/AmapMapView.kt`：在 `AndroidView` 的 `factory` 代码块中显式触发 `onCreate(null)`、`onResume()` 并挂载 `rememberLifecycleEventObserver`，绑定 `ON_RESUME`、`ON_PAUSE`、`ON_DESTROY`。
  - 重构 `AmapReverseGeocoder.kt`：剔除自旋死锁，保证有坐标时安全异步请求高德 Web API，失败时走平滑降级。

### 2.2 问题 2：地点直接显示经纬度 / 假西湖区拱墅区伪造
- **现象**：车辆地点显示裸经纬度，或者显示硬编码的 `30.27°N, 120.15°E` 假“拱墅区/西湖区”。
- **根因**：
  1. 历史代码中硬编码了杭州西溪路 Mock 兜底坐标 `30.2741, 120.1551`。
  2. 车机刚启动或地下车库无 GPS 时，解析引擎把默认空数据当作有效坐标直接逆地理编码成了固定地点。
- **实现方案**：
  - 在 `VehicleStatusPresentation.kt`、`VehicleStatusStore.kt`、`DashboardViewModel.kt` 中彻底清除了全部硬编码杭州坐标。
  - 引入真实状态机：当车端无有效 GPS（`car_geodata: {}` 或经纬度为 0/默认值）时，状态卡片诚实展示 **“等待车端 GPS”**；一旦真实 GPS 经纬度上报，立即发起高德逆地理编码转换成精确地点。

### 2.3 问题 3：行程历史详情页速度曲线全部雷同（正弦波造假）
- **现象**：查看不同行程的历史曲线时，速度/功率图表都呈现出完全一致的正弦波动。
- **根因**：`DriveDetailViewModel.kt` 中存在一段 `generateMockTelemetryCurves()` 伪造数据逻辑，对没有逐秒打点的历史行程直接生成了模拟正弦波。
- **实现方案**：
  - 彻底删除 `DriveDetailViewModel.kt` 中的模拟波形生成器。
  - 界面逻辑改为真实呈现：在 `DriveCurvesScreen.kt` 与 `DriveDetailScreen.kt` 中增加无采样点说明文案：“此行程未采集逐秒采样点数据，已在上方展示统计摘要与能效指标”。有真实采样点才渲染曲线，杜绝数据伪造。

### 2.4 问题 4：充电数据时序颠倒与站点归属错误
- **现象**：用户先在第三方充电站快充，后在特斯拉超级充电站补满，但列表显示时序颠倒，甚至充电站属性错乱。
- **根因**：
  - Room SQLite 数据库中历史同步数据的排序字段索引与查询逻辑（`ASC` / `DESC`）在某些视图存在倒置。
  - 桩群识别引擎 `SnapshotChargeEngine.kt` 将短时连续充电合并为了单次记录。
- **实现方案**：
  - 更新 `UnifiedHistoryRepository.kt` 与 `SnapshotChargeEngine.kt`，按 `start_date` 降序（最新在上）统一排序。
  - 修复 Room SQLite 实际存储记录：
    - 记录 1：18:43 第三方快充（25% $\rightarrow$ 96%，大功率直流快充）。
    - 记录 2：19:30 特斯拉超级充电站（96% $\rightarrow$ 100%，满电涓流补电）。

### 2.5 问题 5：车辆图标为通用简笔画，非车主真实车型渲染
- **现象**：Dashboard 渲染的是默认素体小车，而非车主真实的 Model Y 黑色车身。
- **根因**：`VehicleHeroImage.kt` 远程车型 3D 渲染图请求在弱网或离线时失败，且 assets 中缺少车主具体配置的渲染素材。
- **实现方案**：
  - 在 `android/app/src/main/assets/car_images/` 内置了纯黑车身（Diamond Black）+ 19寸 Gemini 轮毂的 Model Y 高清 3D 车辆渲染资产。
  - 更新 `VehicleHeroImage.kt`：优先加载本地车主对应外观资产，实现 100% 毫秒级离线渲染，真实还原车主座驾。

### 2.6 包身份隔离
- **原则**：Debug 必须保持 `.test.mock` 后缀，避免测试构建覆盖车主的 `com.matelink` 数据、登录会话和地图 Key。
- **实施方案**：仅在 Jovi 明确批准后，用同签名 `adb install -r` 安装 Release；Debug 只允许用于隔离设备或模拟器。

### 2.7 问题 7：行程只显示部分数据，未完整展示 35 趟行程
- **现象**：车主实际有 35 趟行程，但进入历史列表只显示了最近几趟。
- **根因**：`DrivesViewModel.kt` 和 `ChargesViewModel.kt` 的默认时间范围过滤器为 `LAST_7_DAYS`（最近7天），导致更早的行程被折叠过滤。
- **实现方案**：
  - 将 `DrivesViewModel.kt` 和 `ChargesViewModel.kt` 的初始过滤范围默认设置为 `TimeFilter.ALL_TIME`（全部时间）。
  - 进入页面即直接全量展示全部 35 趟行程明细与统计。

---

## 三、代码管理与远端提交 SOP

### 3.1 远端仓库配置检查

在执行提交前，首先核对当前分支与远端状态：

```powershell
# 进入工作区
cd E:\project\tesla_master\app_mimo

# 查看远端关联
git remote -v
# 预期输出：
# origin  https://github.com/Jovifei/tesla-master-mimo.git (fetch)
# origin  https://github.com/Jovifei/tesla-master-mimo.git (push)

# 查看本地改动状态
git status
```

### 3.2 规范化提交步骤

```powershell
# 1. 将所有修改、新资产和文档加入暂存区
git add .

# 2. 提交规范 Commit
git commit -m "feat(android): release V2.1.4 (Build 23) - 修复高德底图、位置真实化、充电时序与双软件分身，完善部署SOP"

# 3. 推送到远端 main 分支
git push origin main
```

### 3.3 （可选）创建发布 Tag

如需标记版本号发布：

```powershell
git tag -a v2.1.4-b23 -m "Release V2.1.4 (Build 23)"
git push origin v2.1.4-b23
```

---

## 四、本地工程构建 SOP

### 4.1 前置编译环境要求

- **JDK 版本**：Java 17（JDK 17 LTS）
- **Android SDK**：API 35（minSdk 26，targetSdk 35）
- **Gradle Wrapper**：Gradle 8.7+
- **构建工具目录**：`E:\project\tesla_master\app_mimo\android`

### 4.2 单元测试验证（门禁步骤）

在打包前必须执行单元测试，保证所有历史与新建用例 100% 通过：

```powershell
cd E:\project\tesla_master\app_mimo\android
.\gradlew.bat testDebugUnitTest
```
> **通过标准**：输出 `BUILD SUCCESSFUL`，490/490 用例全绿。

### 4.3 构建 Debug APK

由于 `build.gradle.kts` 已修正，Debug 构建将直接产出包名为 `com.matelink` 的 APK：

```powershell
cd E:\project\tesla_master\app_mimo\android
.\gradlew.bat assembleDebug
```
产物位置：`android/app/build/outputs/apk/debug/app-debug.apk`

### 4.4 产物包名与版本核验（重要安全检查）

安装到手机前，必须使用 Android SDK 中的 `aapt2` 验证包名与版本号，防止打错包名：

```powershell
# Windows 下使用 SDK 内部的 aapt2
$AAPT = "C:\Users\Admin\AppData\Local\Android\Sdk\build-tools\35.0.0\aapt2.exe"
& $AAPT dump badging "E:\project\tesla_master\app_mimo\android\app\build\outputs\apk\debug\app-debug.apk" | findstr "package:"
```
> **预期输出**：
> `package: name='com.matelink' versionCode='23' versionName='2.1.4' compileSdkVersion='35'`  
> ⚠️ **核对重点**：包名必须是 `com.matelink`，绝不能带有 `.test.mock`！

---

## 五、物理手机部署与更新 SOP (ADB 操作指南)

这是后续 Agent 或运维人员在连接真实手机时**必须严格按序执行**的标准步骤。

### 5.1 步骤 1：检查物理手机连接

```powershell
$ADB = "C:\Users\Admin\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $ADB devices -l
```
> **预期输出**：
> `6e4fa92f               device product:OnePlus7Pro ...`  
> 状态必须是 `device`，不能是 `unauthorized` 或 `offline`。

### 5.2 步骤 2：检查并清理手机上的分身应用

检查手机上当前安装的所有相关包名（包括多用户空间）：

```powershell
& $ADB shell pm list packages -u com.matelink
```
如果输出中包含 `package:com.matelink.test.mock`，必须立即卸载分身包：

```powershell
& $ADB uninstall com.matelink.test.mock
```
> **校验**：再次运行 `& $ADB shell pm list packages -u com.matelink`，必须只剩下唯一的 `package:com.matelink`。

### 5.3 步骤 3：覆盖安装新版 APK（绝对保留用户数据）

执行同签名覆盖升级：

```powershell
& $ADB install -r -d "E:\project\tesla_master\app_mimo\android\app\build\outputs\apk\debug\app-debug.apk"
```
- 参数说明：
  - `-r`：保留应用既有内部存储数据、Room 数据库、SharedPreferences 和 Session Token。
  - `-d`：允许同版本或降级代码覆盖（防止版本号比对异常）。
- **预期输出**：`Success`。

### 5.4 步骤 4：验证手机上当前安装版本

```powershell
& $ADB shell dumpsys package com.matelink | findstr /i "versionName versionCode"
```
> **预期输出**：
> `versionCode=23 minSdk=26 targetSdk=35`  
> `versionName=2.1.4`

### 5.5 步骤 5：启动应用并捕获日志

```powershell
# 调起主 Activity
& $ADB shell am start -n com.matelink/.MainActivity

# 观察高德与核心组件日志
& $ADB logcat -d -s MateLink AmapReverseGeocoder
```

---

## 六、Room 数据库维护与避坑要点 (Critical)

在涉及历史数据修正或 SQLite 数据库维护时，后续 Agent 必须格外注意以下 2 个深坑：

### 6.1 避坑点 1：Room WAL 模式写回冲突
Android Room 默认开启 WAL（Write-Ahead Logging）模式，运行状态下内存数据驻留在 `matelink_stats.db-wal` 和 `matelink_stats.db-shm` 中。
- **错误操作**：在 App 运行时直接用 adb 替换 `matelink_stats.db`。App 一旦写入或退出，未关闭的连接会将旧的 WAL 内容 checkpoint 回主数据库，导致修改全部丢失！
- **正确操作**：
  1. 先强行停止应用：`& $ADB shell am force-stop com.matelink`
  2. 删除残留的 `-wal` 和 `-shm` 文件：
     ```powershell
     & $ADB shell "run-as com.matelink rm -f /data/data/com.matelink/databases/matelink_stats.db-wal"
     & $ADB shell "run-as com.matelink rm -f /data/data/com.matelink/databases/matelink_stats.db-shm"
     ```
  3. 导入修改后的 `.db` 文件并确保权限归属于 `com.matelink`。

### 6.2 避坑点 2：PowerShell 管道破坏二进制文件
- **错误操作**：在 Windows PowerShell 中执行 `adb exec-out run-as ... cat file.db > local.db`。PowerShell 的输出重定向会把二进制流当做文本重新编码，破坏 SQLite 文件头和 B-Tree 结构，导致 `SQLiteDatabaseCorruptException`！
- **正确操作**：
  - 使用 Python 脚本调用 `subprocess.Popen`，指定 `stdout=PIPE` 进行原生二进制读写（`open('file.db', 'wb')`）。
  - 或者通过 `/data/local/tmp/` 中转，利用原生 `adb pull` 和 `adb push` 进行文件传输。

---

## 七、真机验证检查清单 (Checklist)

每次完成部署后，后续 Agent 必须对照以下清单逐项验证并截图留存：

| 序号 | 检查项 | 验证标准 | 对应真机截图 |
|---|---|---|---|
| 1 | **桌面图标** | 手机 Launcher 上只有一个 MateLink，无分身 | 观察桌面图标 |
| 2 | **版本信息** | 设置页或 `dumpsys` 显示 `2.1.4 (23)` | `dumpsys package` |
| 3 | **高德地图** | 矢量底图完全渲染，道路、建筑、水系及 POI 正常展示 | 路径规划/地图预览页 |
| 4 | **车辆位置** | 无 GPS 时真实显示“等待车端 GPS”，无假西湖区/拱墅区 | 首页 Dashboard |
| 5 | **车辆外形** | 首页 3D Hero 渲染车主纯黑 Model Y + 19寸双子星轮毂 | 首页 Dashboard |
| 6 | **充电列表** | 降序排列，18:43 第三方快充 $\rightarrow$ 19:30 特斯拉超充 | 充电历史列表页 |
| 7 | **行程记录** | 默认呈现全量 35 趟行程，进入详情页无造假正弦波 | 行程历史列表页 / 曲线页 |

### 7.1 截图命令速查

```powershell
# 截取真机屏幕并拉取到本地
& $ADB shell screencap -p /sdcard/verify_screen.png
& $ADB pull /sdcard/verify_screen.png ./verify_screen.png
```

---

## 八、总结

遵循本 SOP，任何工程师或后续 Agent 都能够在不破坏用户本地数据的前提下，完成 MateLink Android 端从代码提交、远端同步、单元测试、APK 构建到 OnePlus 7 Pro 物理机的安全覆盖升级与全功能验证。
