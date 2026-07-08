# MateLink 数据目录 — 全量字段清单

> 最后更新: 2026-07-01
> 用途：展示 app 可呈现的所有数据字段，供原型设计和功能开发参考

---

## 1. 车辆信息 (Car)

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `carId` | Int | ✅ | ✅ | ✅ | 车辆 ID |
| `name` | String | ✅ | ✅ | ✅ | 车辆名称 |
| `vin` | String | ✅ | ✅ | ✅ | 车辆识别码 |
| `model` | String | ✅ | ✅ | ✅ | 车型（Model 3/Y/S/X） |
| `trimBadging` | String | ✅ | ✅ | ✅ | 配置标识（Long Range / Performance） |
| `exteriorColor` | String | ✅ | ✅ | ✅ | 外观颜色 |
| `spoilerType` | String | ✅ | ✅ | — | 尾翼类型 |
| `wheelType` | String | ✅ | ✅ | — | 轮毂类型 |
| `efficiency` | Double | ✅ | ✅ | ✅ | 能效系数 |
| `freeSupercharging` | Bool | ✅ | ✅ | — | 免费超充 |
| `totalCharges` | Int | ✅ | ✅ | ✅ | 累计充电次数 |
| `totalDrives` | Int | ✅ | ✅ | ✅ | 累计行驶次数 |
| `totalUpdates` | Int | ✅ | ✅ | — | 累计固件更新 |

---

## 2. 实时状态 (CarStatus)

### 2.1 基础状态

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `state` | Enum | ✅ | ✅ | ✅ | online/offline/asleep/charging/driving |
| `since` | String | ✅ | ✅ | ✅ | 状态起始时间 |
| `healthy` | Bool | ✅ | ✅ | — | 车辆健康状态 |
| `odometer` | Double | ✅ | ✅ | ✅ | 总里程 (km) |

### 2.2 电池

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `batteryLevel` | Int | ✅ | ✅ | ✅ | 电量百分比 (0-100) |
| `usableBatteryLevel` | Int | ✅ | ✅ | — | 可用电量百分比 |
| `usableBatteryRangeKm` | Double | ✅ | ✅ | ✅ | 实际续航 (km) |
| `idealBatteryRangeKm` | Double | ✅ | ✅ | ✅ | 理想续航 (km) |
| `chargeLimitSoc` | Int | ✅ | ✅ | ✅ | 充电上限设置 (%) |

### 2.3 充电状态

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `pluggedIn` | Bool | ✅ | ✅ | ✅ | 是否插枪 |
| `chargePortDoorOpen` | Bool | ✅ | ✅ | ✅ | 充电口是否打开 |
| `chargeEnergyAdded` | Double | ✅ | ✅ | ✅ | 已充电量 (kWh) |
| `chargerPower` | Double | ✅ | ✅ | ✅ | 当前充电功率 (kW) |
| `chargerActualCurrent` | Int | ✅ | ✅ | — | 充电电流 (A) |
| `chargerVoltage` | Int | ✅ | ✅ | — | 充电电压 (V) |
| `chargerPhases` | Int | — | ✅ | — | 充电相数 (0=DC, 1=单相, 2/3=三相) |
| `chargeCurrentRequest` | Int | — | ✅ | — | 电流请求 (A) |
| `chargeCurrentRequestMax` | Int | — | ✅ | — | 最大电流请求 (A) |
| `chargingState` | String | — | ✅ | — | 充电子状态 |
| `timeToFullCharge` | Double | ✅ | ✅ | — | 充满剩余时间 (min) |

### 2.4 温度与环境

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `insideTemp` | Double | ✅ | ✅ | ✅ | 车内温度 (°C) |
| `outsideTemp` | Double | ✅ | ✅ | ✅ | 车外温度 (°C) |
| `isClimateOn` | Bool | ✅ | ✅ | ✅ | 空调开关状态 |
| `isPreconditioning` | Bool | — | ✅ | — | 预空调状态 |

### 2.5 安全与锁定

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `locked` | Bool | ✅ | ✅ | ✅ | 车门锁定 |
| `sentryMode` | Bool | ✅ | ✅ | ✅ | 哨兵模式 |

### 2.6 胎压 (TPMS)

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `tirePressureFrontLeft` | Double | ✅ | ✅ | ✅ | 左前胎压 (bar) |
| `tirePressureFrontRight` | Double | ✅ | ✅ | ✅ | 右前胎压 (bar) |
| `tirePressureRearLeft` | Double | ✅ | ✅ | ✅ | 左后胎压 (bar) |
| `tirePressureRearRight` | Double | ✅ | ✅ | ✅ | 右后胎压 (bar) |
| `tpmsSoftWarningFl` | Bool | — | ✅ | — | 左前胎压软警告 |
| `tpmsSoftWarningFr` | Bool | — | ✅ | — | 右前胎压软警告 |
| `tpmsSoftWarningRl` | Bool | — | ✅ | — | 左后胎压软警告 |
| `tpmsSoftWarningRr` | Bool | — | ✅ | — | 右后胎压软警告 |

### 2.7 位置与行驶

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `latitude` | Double | ✅ | ✅ | ✅ | 纬度 (WGS-84) |
| `longitude` | Double | ✅ | ✅ | ✅ | 经度 (WGS-84) |
| `elevation` | Double | ✅ | ✅ | — | 海拔 (m) |
| `speed` | Int/Double | ✅ | ✅ | — | 当前速度 (km/h) |
| `power` | Double | ✅ | ✅ | — | 当前功率 (kW) |
| `heading` | Int/Double | ✅ | ✅ | — | 航向角 (°) |
| `shiftState` | String? | ✅ | — | — | 挡位 (D/R/N/P) |

### 2.8 固件

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `carVersion` | String | — | ✅ | — | 当前固件版本 |
| `updateAvailable` | Bool | — | ✅ | — | 是否有更新 |
| `updateVersion` | String | — | ✅ | — | 可更新版本号 |

---

## 3. 行程记录 (Drive)

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `id` | Int | ✅ | ✅ | ✅ | 行程 ID |
| `carId` | Int | ✅ | ✅ | ✅ | 车辆 ID |
| `startDate` | String | ✅ | ✅ | ✅ | 出发时间 (ISO 8601) |
| `endDate` | String | ✅ | ✅ | ✅ | 到达时间 |
| `distanceKm` | Double | ✅ | ✅ | ✅ | 行驶距离 (km) |
| `durationMin` | Int | ✅ | ✅ | ✅ | 行驶时长 (min) |
| `efficiency` | Double | ✅ | ✅ | ✅ | 能效 (Wh/km) |
| `consumptionKwh` | Double | ✅ | ✅ | ✅ | 能耗 (kWh) — iOS 计算属性 |
| `startAddress` | String | ✅ | ✅ | ✅ | 出发地址 |
| `endAddress` | String | ✅ | ✅ | ✅ | 到达地址 |
| `startLatitude` | Double | ✅ | ✅ | ✅ | 出发纬度 |
| `startLongitude` | Double | ✅ | ✅ | ✅ | 出发经度 |
| `endLatitude` | Double | ✅ | ✅ | ✅ | 到达纬度 |
| `endLongitude` | Double | ✅ | ✅ | ✅ | 到达经度 |
| `startBatteryLevel` | Int | ✅ | ✅ | ✅ | 出发电量 (%) |
| `endBatteryLevel` | Int | ✅ | ✅ | ✅ | 到达电量 (%) |
| `startIdealRangeKm` | Double | ✅ | ✅ | ✅ | 出发理想续航 (km) |
| `endIdealRangeKm` | Double | ✅ | ✅ | ✅ | 到达理想续航 (km) |
| `outsideTempAvg` | Double | ✅ | ✅ | — | 平均车外温度 (°C) |
| `speedMax` | Double | ✅ | ✅ | — | 最高速度 (km/h) |
| `powerMax` | Double | ✅ | ✅ | — | 最大功率 (kW) |
| `powerMin` | Double | ✅ | ✅ | — | 最小功率 (kW，回收) |
| `elevationGain` | Double | ✅ | ✅ | — | 累计爬升 (m) |
| `elevationLoss` | Double | ✅ | ✅ | — | 累计下降 (m) |

---

## 4. 充电记录 (Charge)

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `id` | Int | ✅ | ✅ | ✅ | 充电 ID |
| `carId` | Int | ✅ | ✅ | ✅ | 车辆 ID |
| `startDate` | String | ✅ | ✅ | ✅ | 开始充电时间 |
| `endDate` | String? | ✅ | ✅ | ✅ | 结束充电时间 |
| `chargeEnergyAdded` | Double | ✅ | ✅ | ✅ | 充入电量 (kWh) |
| `startBatteryLevel` | Int | ✅ | ✅ | ✅ | 开始电量 (%) |
| `endBatteryLevel` | Int? | ✅ | ✅ | ✅ | 结束电量 (%) |
| `startIdealRangeKm` | Double? | ✅ | ✅ | ✅ | 开始理想续航 |
| `endIdealRangeKm` | Double? | ✅ | ✅ | ✅ | 结束理想续航 |
| `startRatedRangeKm` | Double? | ✅ | ✅ | — | 开始额定续航 |
| `endRatedRangeKm` | Double? | ✅ | ✅ | — | 结束额定续航 |
| `durationMin` | Int | ✅ | ✅ | ✅ | 充电时长 (min) |
| `cost` | Double? | ✅ | ✅ | ✅ | 费用 (¥) |
| `address` | String? | ✅ | ✅ | ✅ | 充电地点 |
| `latitude` | Double | ✅ | ✅ | ✅ | 纬度 |
| `longitude` | Double | ✅ | ✅ | ✅ | 经度 |
| `chargingType` | String | ✅ | ✅ | ✅ | 充电类型 (AC/DC) |
| `powerMax` | Double | ✅ | ✅ | ✅ | 最大功率 (kW) |
| `powerMin` | Double | ✅ | ✅ | ✅ | 最小功率 (kW) |
| `outsideTempAvg` | Double | ✅ | ✅ | — | 平均车外温度 |

---

## 5. 电池健康 (BatteryHealth)

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `carId` | Int | ✅ | ✅ | ✅ | 车辆 ID |
| `date` | String | ✅ | ✅ | ✅ | 测量日期 |
| `batteryLevel` | Int | ✅ | ✅ | ✅ | 电量 (%) |
| `ratedRangeKm` | Double | ✅ | ✅ | ✅ | 额定续航 (km) |
| `idealRangeKm` | Double | ✅ | ✅ | ✅ | 理想续航 (km) |
| `odometer` | Double | ✅ | ✅ | ✅ | 里程 (km) |
| `outsideTemp` | Double | ✅ | ✅ | ✅ | 车外温度 |
| `usableBatteryLevel` | Int | ✅ | ✅ | ✅ | 可用电量 |
| `capacityDegradationPercent` | Double? | ✅ | — | — | 容量衰减 (%) |
| `originalCapacityKwh` | Double? | ✅ | — | — | 原始容量 (kWh) |
| `currentCapacityKwh` | Double? | ✅ | — | — | 当前容量 (kWh) |
| `history` | [BatteryHealthPoint]? | ✅ | — | — | 历史趋势 |
| `mileageKm` | Double | ✅ | — | — | 计算属性 = odometer |

---

## 6. 固件更新 (UpdateItem)

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `id` | Int | ✅ | ✅ | ✅ | 更新 ID |
| `carId` | Int | ✅ | ✅ | ✅ | 车辆 ID |
| `startDate` | String | ✅ | ✅ | ✅ | 安装开始时间 |
| `endDate` | String | ✅ | ✅ | ✅ | 安装结束时间 |
| `version` | String | ✅ | ✅ | ✅ | 固件版本号 |

---

## 7. 分时电价 (TariffConfig)

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `isEnabled` | Bool | ✅ | ✅ | — | 是否启用分时电价 |
| `peakPrice` | Double | ✅ | ✅ | — | 峰时电价 (¥/kWh) |
| `flatPrice` | Double | ✅ | ✅ | — | 平时电价 |
| `valleyPrice` | Double | ✅ | ✅ | — | 谷时电价 |
| `peakStart` | Int | ✅ | ✅ | — | 峰时开始小时 (0-23) |
| `peakEnd` | Int | ✅ | ✅ | — | 峰时结束小时 |
| `flatStart` | Int | ✅ | ✅ | — | 平时开始小时 |
| `flatEnd` | Int | ✅ | ✅ | — | 平时结束小时 |
| `valleyStart` | Int | ✅ | ✅ | — | 谷时开始小时 |
| `valleyEnd` | Int | ✅ | ✅ | — | 谷时结束小时 |

---

## 8. 多实例 (Instance)

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `id` | String | ✅ | ✅ | — | 实例唯一 ID |
| `name` | String | ✅ | ✅ | — | 实例名称 |
| `serverUrl` | String | ✅ | ✅ | — | 服务器 URL |
| `apiToken` | String | ✅ | ✅ | — | API Token |
| `carId` | Int | ✅ | ✅ | — | 默认车辆 ID |

---

## 9. 全局设置 (GlobalSettings)

| 字段 | 类型 | iOS | Android | Web | 说明 |
|---|---|---|---|---|---|
| `units` | String | ✅ | ✅ | — | 单位制 (km/mi) |
| `timezone` | String | ✅ | ✅ | — | 时区 |
| `language` | String | ✅ | ✅ | — | 语言 |
| `theme` | String | ✅ | ✅ | — | 主题 (light/dark/auto) |

---

## 10. 通知配置

| 字段 | 类型 | Android | 说明 |
|---|---|---|---|
| `chargingNotification` | Bool | ✅ | 充电完成通知 |
| `sentryNotification` | Bool | ✅ | 哨兵模式通知 |
| `tpmsNotification` | Bool | ✅ | 胎压低告警 |
| `softwareUpdateNotification` | Bool | ✅ | 固件更新通知 |
| `mileageAchievementNotification` | Bool | ✅ | 里程成就通知 |
| `batteryHealthNotification` | Bool | ✅ | 电池健康告警 |

---

## 11. Widget 数据

| 字段 | 类型 | Android | iOS | 说明 |
|---|---|---|---|---|
| `batteryLevel` | Int | ✅ | ✅ | 电量 (%) |
| `range` | Int | ✅ | ✅ | 续航 (km) |
| `state` | String | ✅ | ✅ | 车辆状态 |
| `isPluggedIn` | Bool | ✅ | ✅ | 是否插枪 |
| `isClimateOn` | Bool | ✅ | ✅ | 空调状态 |
| `location` | String | ✅ | — | 位置描述 |
| `carImage` | Data | — | ✅ | 车辆图片 (base64) |

---

## 12. 统计聚合

| 指标 | 数据源 | Android | iOS | 说明 |
|---|---|---|---|---|
| 总里程 | odometer | ✅ | ✅ | 累计 km |
| 总行程数 | drives.count | ✅ | ✅ | 累计次数 |
| 总充电数 | charges.count | ✅ | ✅ | 累计次数 |
| 总能耗 | drives.consumptionKwh | ✅ | ✅ | 累计 kWh |
| 总费用 | charges.cost | ✅ | ✅ | 累计 ¥ |
| 平均能效 | efficiency | ✅ | ✅ | Wh/km |
| 最高速度 | speedMax | ✅ | ✅ | km/h |
| 最长行程 | distanceKm | ✅ | ✅ | km |
| 驾驶天数 | unique days | ✅ | ✅ | 天 |
| AC/DC 比例 | chargingType | ✅ | ✅ | % |
| 月度距离 | monthly aggregation | ✅ | ✅ | km/month |
| 7天趋势 | daily batteryLevel | ✅ | ✅ | % |

---

## 13. 图表数据

| 图表 | 数据字段 | 平台 |
|---|---|---|
| 电池环形图 | batteryLevel, idealBatteryRangeKm | Android + iOS |
| 7天电量趋势 | batteryLevel (7日) | Android + iOS |
| 行程速度曲线 | speed over time | Android + iOS |
| 行程功率曲线 | power over time | Android + iOS |
| 行程海拔曲线 | elevation over time | iOS |
| 行程温度曲线 | insideTemp / outsideTemp | Android + iOS |
| 行程胎压曲线 | tirePressureFL/FR/RL/RR | Android + iOS |
| 充电功率曲线 | powerMax over time | Android + iOS |
| 充电电压曲线 | voltage over time | Android + iOS |
| 充电温度曲线 | outsideTempAvg | Android + iOS |
| 月度距离柱状图 | monthly km | Android + iOS + Web |
| 月度能耗柱状图 | monthly kWh | Android + iOS + Web |
| 月度充电能量柱状图 | monthly charge kWh | Android + iOS + Web |
| 效率散点图 | speed vs efficiency | Android + iOS + Web |
| 热力图 | hour × day activity | Android + iOS + Web |
| 电池健康趋势 | capacityKwh over time | iOS |
| 固件更新时间线 | version, dates | Android + iOS + Web |
| 国家/地区地图 | latitude/longitude | Android + iOS |

---

## 14. API 端点映射

| 端点 | 数据模型 | 用途 |
|---|---|---|
| `/api/v1/cars` | CarData | 车辆列表 |
| `/api/v1/cars/{id}/status` | CarStatus | 实时状态 |
| `/api/v1/cars/{id}/drives` | [Drive] | 行程历史 |
| `/api/v1/cars/{id}/drives/{id}` | Drive | 行程详情 |
| `/api/v1/cars/{id}/charges` | [Charge] | 充电历史 |
| `/api/v1/cars/{id}/charges/{id}` | Charge | 充电详情 |
| `/api/v1/cars/{id}/charges/current` | Charge | 当前充电 |
| `/api/v1/cars/{id}/battery-health` | BatteryHealth | 电池健康 |
| `/api/v1/cars/{id}/updates` | [UpdateItem] | 固件更新 |
| `/api/v1/cars/{id}/settings` | GlobalSettings | 全局设置 |
| `/api/ping` | — | 服务器可达性 |
| `/api/readyz` | — | 服务健康检查 |
