# iOS Apple 重设计交接 — 2026-08-30

> 分支：`feature/ios-apple-redesign`（禁止提交到 `main`）
> 远端：`origin/feature/ios-apple-redesign`
> 工作区：`app_mimo/ios/`
> 对照基线：Android `com.matelink` 核心车辆逻辑

## 当前完成了什么

本分支已完成两层工作：

1. **Apple 原生风格核心页**（此前已推送，HEAD 起点 `28ef539`）
   - 设计系统：`MateTheme` / `CarColorPalette` / `MateAnimation`
   - 类型安全导航：`Route` enum + `RouteDestinationView`
   - Dashboard / 行程列表与详情 / 充电列表与详情 / 电池健康 / Settings / Onboarding / More
   - 分页 API、`ChargePoint` / `DrivePosition`、LTTB 降采样、三语本地化

2. **Android 核心业务逻辑对齐**（本轮，待本提交入库）
   - 门/窗/前后备箱告警：新增 `VehicleStatusPresentation.swift`，对齐 Android `openVehicleOpenings()`
   - Dashboard 充电面板：仅 `CarStatus.isCharging` 时显示（不再用粗粒度 `state == .charging`）
   - 行程详情：走 `getDriveDetailWithPositions`，有 `positions` 时用真实曲线与完整轨迹折线
   - 充电详情：走 `getChargeDetailWithPoints`，有 `charge_points` 时用真实功率曲线
   - 当前充电：30s 轮询；TeslaMate 尚未建 charge 行时 4s 快轮询；DC 满电未拔枪警告；`UserDefaults` 记住 DC 会话
   - `getCurrentCharge`：无活跃充电返回 `nil`（对齐 Android `NoActiveCharge`），不再当解码失败
   - 换车：`AppState.selectCar` 写入 instance，启动时从 active instance 恢复 `currentCarId`

## 本轮修复的逻辑差异（根因）

| 症状 | 根因 | 修复 |
|------|------|------|
| Dashboard 门告警不准 | iOS 未解码 `doors_open` 等字段，也没有 Android 的 opening 集合逻辑 | `CarStatus` 补字段 + `VehicleStatusPresentation` |
| 充电面板在插枪未充电时也出现 | 用 `CarState.charging` 而不是 `charging_state == "charging"` | `isCharging` 计算属性 |
| 行程/充电详情是估算图 | 详情 API 只取摘要，丢掉 `drive_details` / `charge_details` | 带 points 的 detail API + 图表/地图消费真实点 |
| 行程地图只有起终点直线 | `DriveRouteMap` 只画两点 | 多点 polyline |
| 当前充电无刷新 / 无 DC 警告 | 页面一次性加载，无会话记忆 | 轮询循环 + DC 会话持久化 |
| 刚开始充电闪「未充电」 | `getCurrentCharge` 把 200+error 当失败；`isChargeStarting` 判断反了 | 解析 wrapper；starting = `isCharging && currentCharge == nil` |
| 换车重启丢失 | `currentCarId` 默认 1，未从 instance 恢复 | `selectCar` 写 instance；`init` 读回 |

## 明确未搬过来的能力（下一批，不是本轮 bug）

这些是 Android 已有、iOS 仍为缺口或占位，**不要当成已验收**：

1. DriveList / ChargeList 筛选（日期、距离、AC/DC、费用）与默认 7 天范围
2. Dashboard MateLink snapshot adapter（iOS 仍只用 `/status` + partial 降级）
3. Trips / TPMS 趋势 / Countries / WhereWasI 等仍为占位页
4. iOS Widget：源码在，`project.yml` 无 extension target，仍 deferred
5. 自定义字体 `.ttf` 可能未入库（`VERIFY_IOS.md` 检查项 5）
6. 通知、高德 SDK、Apple Watch（见父仓 `docs/TODO-mimo.md` I-1 / I-2 / I-5）

## 后面应该执行什么

按顺序做，不要跳到 `main` 上开发。

### 1. Mac 编译（必须，Windows 无法证明）

```bash
cd app_mimo/ios
xcodegen generate
pod install
xcodebuild -workspace MateLink.xcworkspace -scheme MateLink \
  -destination 'platform=iOS Simulator,name=iPhone 16' build
```

打开 `MateLink.xcworkspace`，不要打开裸 `.xcodeproj`。步骤见 `ios/VERIFY_IOS.md`。

### 2. Mock 模式手测

- Dashboard：打开门/窗时出现告警；仅主动充电时出现充电面板
- 行程详情：图表与地图为曲线/轨迹，不是两点直线
- 充电详情：功率曲线来自 `charge_points`
- 当前充电：30s 刷新；刚启动约 4s；DC 满电未拔枪显示警告
- 切换车辆后杀进程再开，仍是同一辆车

### 3. 推送本分支（确认编译/手测后再做）

```powershell
cd E:\project\tesla_master\app_mimo
git push origin feature/ios-apple-redesign
```

不要 `git push origin main`。`app_mimo` 是父仓 submodule，父仓指针更新是另一次提交。

### 4. 下一批功能（建议新 commit，仍在本分支或 `feature/ios-*`）

1. DriveList / ChargeList 筛选与默认日期范围（对齐 Android ViewModel）
2. Dashboard snapshot API 适配
3. 占位页：Trips、TPMS、Countries
4. Widget target wiring（独立任务）

## 关键文件

| 路径 | 角色 |
|------|------|
| `ios/MateLink/Core/Utils/VehicleStatusPresentation.swift` | 开口告警，对齐 Android |
| `ios/MateLink/Core/Models/CarStatus.swift` | 状态/行程/充电模型 |
| `ios/MateLink/Core/API/ApiClient.swift` | 详情 points、current charge 空态 |
| `ios/MateLink/Features/Charges/CurrentChargeView.swift` | 轮询 + DC 警告 |
| `ios/MateLink/App/AppState.swift` | 换车持久化 |
| `ios/VERIFY_IOS.md` | Mac 验证清单 |

## 验证边界

- Windows：仅源码级检查，**不能**声明 xcodebuild / 签名 / 真机通过
- 本轮未跑 Xcode
- mock 与 real 不可混淆；真实请求失败必须显示错误/空态，禁止静默回退 mock
