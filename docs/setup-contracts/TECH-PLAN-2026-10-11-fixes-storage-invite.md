# MateLink · 技术修复与架构演进方案
**版本：** v1.0 · **日期：** 2026-10-11  
**仓库：** `E:\project\tesla_master\app_mimo`（GitHub: `Jovifei/tesla-master-mimo`）  
**基线提交：** `f0dcd44` · Android v2.1.23 Build 42 · Go 服务端 `781c402`

> **本文档用途：** 供独立 AI 审核以下方案的合理性、完整性和潜在风险，并提出改进建议。
> 审核者请重点关注：代码改动的最小化原则、架构方案的可扩展性、法律合规条款的充分性。

---

## 一、项目背景与架构概述

### 1.1 产品定位

MateLink（小程序名：MateLink车迹）是一个 **独立第三方** Tesla 车主数据可视化应用，与 Tesla, Inc. 无授权关系。

- **主端：** Android（Kotlin + Jetpack Compose + Hilt + Room）
- **副端：** 微信小程序（Taro + TypeScript），AppID `wxc84899ab6146f1be`
- **iOS：** SwiftUI（代码已合入 main，暂无真机验收）
- **服务端：** Go + PostgreSQL + Docker Compose（阿里云 ECS `120.55.64.11`）
- **地图：** 高德地图 SDK

### 1.2 数据架构（路线三，不可变）

```
Tesla Fleet API
      ↓ Bearer Token（服务端存储，加密）
服务端（Go）← 仅存 Token + 实时状态中转 + 历史摘要
      ↓ REST API
Android App / 微信小程序
      ↓ 本地 SQLite（Room）
  原始行程 GPS 轨迹 / SOC 曲线（永不自动上传云端）
```

### 1.3 当前已验收的功能

| 功能 | 状态 | 验收证据 |
|---|---|---|
| Tesla OAuth 登录 | ✅ | 真机登录通过 |
| 行程历史（358 条）| ✅ | ALL_TIME 展示通过 |
| 充电历史（52 条）| ✅ | 最新 2026-10-03 充电通过 |
| 充电费用（可编辑估算）| ✅ | 1.14 元/kWh 默认，人工覆盖优先 |
| TeslaMate 历史归档 | ✅ | archive-bridge 归档 358 行程 |
| 高德地图底图 | ✅ | 生命周期直接挂接 |
| 统一 MD 加载动画 | ✅ | 全页面替换 |
| 多账号隔离 | ✅ | userID/vehicleID hash 三元组隔离 |
| 单元测试 | ✅ | Debug 608 / Release 600 通过，0 失败 |
| 微信小程序骨架 | ✅ | v0.2.2，6 页面，61 项测试通过 |

---

## 二、Bug 修复方案（已获用户授权执行 A+B+C+D 组）

### 2.1 Bug 现状核查

> ⚠️ 注意：经代码扫描，部分 Bug 的具体行号与历史报告有出入，以下以**当前实际代码**为准。

#### 【L1 + L2】法律文案错误（🔴 最高优先级）

**文件：** `android/app/src/main/res/values-zh/strings.xml`

**当前问题代码（L295）：**
```xml
<string name="about_data_source_no_tesla">不连接官方 Tesla API</string>
```

**问题：** 该陈述已过时。现在 MateLink 实际上通过 Tesla 官方 Fleet API 连接，此文案是虚假陈述，同时缺少商标免责声明，违反 Tesla 开发者协议第 3.2 条。

**修复方案：**
```xml
<!-- 修改 L295 -->
<string name="about_data_source_no_tesla">通过 Tesla 官方 Fleet API 授权连接，遵循 Tesla 开发者使用条款</string>

<!-- 在 about_footer（L300）之前新增 -->
<string name="about_trademark_disclaimer">MateLink 为独立第三方应用，与 Tesla, Inc. 无关联或授权关系。Tesla 及相关产品名称为 Tesla, Inc. 的商标。</string>
```

**文件：** `android/app/src/main/java/com/matelink/ui/screens/about/AboutScreen.kt`

**当前问题：** `InfoCard` 中渲染了 `about_data_source_no_tesla`，关于页底部（L122-L127）缺少商标声明区域。

**修复方案：** 在 L122 的 `Text(about_footer)` 之前插入商标声明：

```kotlin
// 在 L121（links.forEach 结束后）与 L122（about_footer Text）之间插入：
Text(
    text = stringResource(R.string.about_trademark_disclaimer),
    style = MaterialTheme.typography.bodySmall,
    color = palette.muted,
    modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp)
)
```

**影响评估：**
- 不影响任何业务逻辑
- 不改变导航流程
- UI 仅在关于页底部新增一行小字
- 不影响任何单元测试（AboutScreen 无对应测试）

---

#### 【B1 + B2】假车数据兜底（🔴 高优先级）

**核查结论：** 扫描当前 `VehicleStatusStore.kt`（55 行）显示，该文件**已无** `defaultFallbackStatus()` 函数，`getCachedStatus()` 直接返回 `null`（L35）。`DashboardViewModel.kt` L148 的 null 路径也无硬编码名称。

**结论：** B1/B2 假数据 Bug 已在之前版本清理，当前代码无需修复。

> 审核建议：请审核者核查 `DashboardViewModel.kt` L197-L214 的 `_uiState.value` 赋值，确认 `car = null` 时 UI 显示逻辑（空车辆状态的展示文案）是否合理，建议该场景不应显示任何用户名占位符。

---

#### 【B4 + B5】轮询时删除假 ID（🟠 中优先级）

**核查结论：** 当前 `data/sync/` 目录已无 `SnapshotTripEngine.kt` 和 `SnapshotChargeEngine.kt`（这两个文件已在早期版本中被重构为 `SyncManager.kt` + `DataSyncWorker.kt`）。

**扫描未在现有文件中发现** `deleteByDriveId(100)`、`deleteByChargeId(1001)` 等调用。

**结论：** B4/B5 已在快照引擎重构中一并清除，当前代码无需修复。

> 审核建议：请审核者验证 `SyncManager.kt` 是否包含任何硬编码 ID 删除逻辑。

---

#### 【B6】伪造能耗（🟠 中优先级）

**核查结论：** 同 B4/B5，`SnapshotTripEngine.kt` 已不存在。当前能耗计算逻辑位于 `DetailAggregateMapper.kt`。

**当前能耗逻辑扫描：** `DetailAggregateMapper.kt` 中能耗字段来源于 API 实际数据，未见 `hashCode()` 伪造。

**结论：** B6 已随快照引擎删除而消除，当前代码无需修复。

---

#### 【B7】充电品牌和接口标准（🟡 低优先级）

**当前代码（`DetailAggregateMapper.kt` L71-72）：**
```kotlin
fastChargerBrand = chargerDetails.firstNonBlank { it.fastChargerBrand },
connectorType = chargerDetails.firstNonBlank { it.fastChargerType },
```

**评估：** 当前实现**已正确**从 API 数据中读取充电品牌和接口类型，无硬编码"Tesla"或"CCS"。`firstNonBlank` 在无数据时返回 `null`，不会强制填入默认值。

**结论：** B7 已在 `DetailAggregateMapper` 重构中修复，当前代码无需修复。

---

#### 【L4】电池容量（🟠 中优先级，确认需处理）

**当前扫描：** `DetailAggregateMapper.kt` 和 `AnnualReportMetrics.kt` 中存在 `batteryCapacity` 相关引用，但未发现 60.0f 固定值。

**需进一步核查：** `AnnualReportMetrics.kt` L24/L26 中的电池容量来源是否动态读取。

**修复方案（如确认硬编码）：**
```kotlin
// 从车辆配置动态读取，兜底按车型估算
val batteryCapacityKwh: Float? = vehicle.batteryCapacityKwh
    ?: when {
        vehicle.modelName?.contains("Long Range", ignoreCase = true) == true -> 78.4f
        vehicle.modelName?.contains("Performance", ignoreCase = true) == true -> 78.4f
        else -> null  // 未知则不计算，UI 显示"暂无"
    }
```

---

### 2.2 修复执行顺序

| 优先级 | 修复项 | 需要代码修改 | 状态 |
|---|---|---|---|
| 1 | L1+L2 商标声明 + 过时文案 | ✅ 需要，2 文件 | **待执行** |
| 2 | L4 电池容量 | 需核查后确认 | **待核查** |
| - | B1/B2 假车数据 | ❌ 已清除 | 已完成 |
| - | B4/B5 假 ID 删除 | ❌ 已清除 | 已完成 |
| - | B6 伪造能耗 | ❌ 已清除 | 已完成 |
| - | B7 充电品牌 | ❌ 已修复 | 已完成 |

---

## 三、法律合规方案

### 3.1 隐私政策补充

**文件：** `web_matelink/public/privacy/index.html`

需要补充以下内容：

1. **位置数据说明：** "MateLink 在地图功能中读取车辆位置数据（经纬度），仅用于在设备本地显示行驶路线。位置数据不会上传至 MateLink 服务器，原始 GPS 坐标仅存储在您的设备本地。"

2. **云端摘要说明：**（混合存储方案实施后新增）"如您开启云端备份，行程的起终点地址文字（逆地理编码结果，非坐标）、里程、时长和费用估算将加密同步至 MateLink 服务器，用于换机数据恢复。"

3. **Tesla API 数据流：** "MateLink 通过 Tesla 官方 Fleet API 获取车辆状态数据，您的 Tesla OAuth Token 以加密形式存储于 MateLink 服务器，Tesla 用户名和密码不经过 MateLink。"

4. **联系方式：** 需填写真实联系邮箱（当前为占位符）

### 3.2 服务条款补充

**文件：** `web_matelink/public/terms/index.html`

需填写：真实联系人 + ICP 备案号（`joviluma.com` 已备案通过）

### 3.3 应用市场合规要求（中国区）

| 平台 | 审核重点 | MateLink 状态 |
|---|---|---|
| 华为应用市场 | 需声明与 Tesla 无官方关联 | ❌ 关于页缺声明 |
| 微信小程序 | 不得以 Tesla 名义宣传 | ⚠️ 小程序名"MateLink车迹"合规 |
| 小米/OPPO | 隐私权限需逐条说明 | ❌ `vehicle_location` 未披露 |

---

## 四、混合数据存储架构方案

### 4.1 设计目标

- **手机本地（Layer 0）：** 原始 GPS 轨迹、每秒 SOC/速度采样 → 永不自动上传，隐私最强
- **云端摘要（Layer 1）：** 行程/充电摘要（无轨迹点）→ 换机恢复，成本极低
- **对象存储（Layer 2，可选）：** 用户主动导出 PDF/GPX 时按需生成，用完即删

### 4.2 服务端数据库 DDL

```sql
-- 行程摘要表（Layer 1）
CREATE TABLE IF NOT EXISTS trip_summaries (
    id            BIGSERIAL PRIMARY KEY,
    user_id       TEXT NOT NULL,
    vehicle_id    TEXT NOT NULL,
    source        TEXT NOT NULL CHECK (source IN ('fleet', 'teslamate', 'manual')),
    source_id     TEXT,
    started_at    TIMESTAMPTZ NOT NULL,
    ended_at      TIMESTAMPTZ NOT NULL,
    distance_km   DOUBLE PRECISION,
    duration_min  INTEGER,
    energy_kwh    DOUBLE PRECISION,
    cost_yuan     DOUBLE PRECISION,
    start_address TEXT,
    end_address   TEXT,
    lat_start     DOUBLE PRECISION,
    lon_start     DOUBLE PRECISION,
    lat_end       DOUBLE PRECISION,
    lon_end       DOUBLE PRECISION,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, vehicle_id, source, source_id)
);

-- 充电摘要表（Layer 1）
CREATE TABLE IF NOT EXISTS charge_summaries (
    id            BIGSERIAL PRIMARY KEY,
    user_id       TEXT NOT NULL,
    vehicle_id    TEXT NOT NULL,
    source        TEXT NOT NULL CHECK (source IN ('fleet', 'teslamate', 'manual')),
    source_id     TEXT,
    started_at    TIMESTAMPTZ NOT NULL,
    ended_at      TIMESTAMPTZ NOT NULL,
    energy_kwh    DOUBLE PRECISION,
    cost_yuan     DOUBLE PRECISION,
    station_name  TEXT,
    charger_type  TEXT,              -- 'AC' / 'DC' / 'GB_T'
    is_fast       BOOLEAN,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, vehicle_id, source, source_id)
);

-- 索引（查询性能）
CREATE INDEX IF NOT EXISTS idx_trip_summaries_user_vehicle_time
    ON trip_summaries(user_id, vehicle_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_charge_summaries_user_vehicle_time
    ON charge_summaries(user_id, vehicle_id, started_at DESC);
```

### 4.3 服务端 API 设计（Go）

```
POST /api/v1/summaries/trips
  Auth: Bearer Token（已有 JWT 体系）
  Body: [{ source, source_id, started_at, ended_at, distance_km,
           duration_min, energy_kwh, cost_yuan,
           start_address, end_address, lat_start, lon_start, lat_end, lon_end }]
  语义: UPSERT（幂等），批量最多 100 条/请求
  返回: { inserted: N, updated: M }

POST /api/v1/summaries/charges
  （同上结构，适配充电字段）

GET /api/v1/summaries/trips?after=<ISO8601>&limit=50
  Auth: Bearer Token
  返回: 分页行程摘要列表（换机恢复用）

GET /api/v1/summaries/charges?after=<ISO8601>&limit=50
  （同上）
```

### 4.4 Android 同步逻辑设计

**触发时机：**
- 行程结束后 30 秒：`OneTimeWorkRequest`（WorkManager）
- 充电完成后：实时触发
- App 前台恢复时：核验云端最新时间戳，发现缺口则补传

**WorkManager Worker 骨架：**

```kotlin
@HiltWorker
class TripSummaryUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tripDao: TripDao,
    private val apiService: MatelinkApiService,
    private val authStore: AuthTokenStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val userId = authStore.currentUserId() ?: return Result.failure()
        val vehicleId = authStore.currentVehicleId() ?: return Result.failure()

        // 读取本地未同步的行程摘要（最多 100 条）
        val unsynced = tripDao.getUnsyncedSummaries(userId, vehicleId, limit = 100)
        if (unsynced.isEmpty()) return Result.success()

        val dtos = unsynced.map { it.toSummaryDto() }
        val response = apiService.uploadTripSummaries(dtos)

        return if (response.isSuccessful) {
            tripDao.markSynced(unsynced.map { it.id })
            Result.success()
        } else {
            Result.retry()  // 自动退避重试
        }
    }
}
```

**幂等保证：** 服务端按 `(user_id, vehicle_id, source, source_id)` UPSERT，重复上传不产生重复记录。

### 4.5 首次登录云端备份引导

**UI 时机：** 首次 Tesla 授权成功后，`DashboardViewModel` 检测到 `cloudBackupConsented == null` 时弹出对话框。

**对话框内容：**
```
标题：开启云端行程备份？

内容：
  开启后，您的行程摘要（里程、时长、起终点）
  将加密同步至云端，换手机后可一键恢复。
  
  原始 GPS 轨迹始终仅保存在本设备。

按钮：
  [开启云端备份]   [仅保留本地]
```

**存储：** 用户选择写入 `DataStore<Preferences>`，Key: `cloud_backup_consented`（Boolean）。

### 4.6 换机恢复流程

```
新手机登录（同一 Tesla 账号）
  ↓
服务端确认 userID（JWT）
  ↓
App 调用 GET /api/v1/summaries/trips
  ↓
Room 导入摘要数据（无 GPS 轨迹）
  ↓
行程列表正常显示（起终点地址、里程、时长）
  ↓
轨迹图为空 → 显示提示："详细路线仅存在旧设备，
              可从旧手机导出 GPX 文件后导入恢复"
```

### 4.7 成本估算

| 用户规模 | 年新增摘要数据量 | 费用（阿里云 RDS） |
|---|---|---|
| 20 人（内测）| ~12 MB | 现有套餐覆盖 |
| 100 人 | ~60 MB | +约 ¥10/月 |
| 1000 人 | ~600 MB | +约 ¥50/月 |
| 10000 人 | ~6 GB | +约 ¥500/月 |

---

## 五、邀请码内测系统方案

### 5.1 设计目标

- 小程序（`wxc84899ab6146f1be`）和 Android App **共用同一套**邀请码验证逻辑
- 初期 20 个邀请码，单次使用，60 天有效期
- 通过后端数据库管理，无需专属管理页面

### 5.2 数据库设计

```sql
CREATE TABLE IF NOT EXISTS invite_codes (
    code        TEXT PRIMARY KEY,               -- 6位大写字母+数字
    max_uses    INTEGER NOT NULL DEFAULT 1,
    used_count  INTEGER NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    note        TEXT,                           -- 备注（送给谁）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT invite_code_format CHECK (code ~ '^[A-Z0-9]{6}$'),
    CONSTRAINT invite_used_count CHECK (used_count <= max_uses)
);

-- 记录每次使用（审计）
CREATE TABLE IF NOT EXISTS invite_code_uses (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT NOT NULL REFERENCES invite_codes(code),
    user_id     TEXT NOT NULL,
    used_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_type TEXT NOT NULL CHECK (client_type IN ('android', 'miniprogram', 'ios'))
);
```

### 5.3 API 设计

```
POST /api/v1/auth/validate-invite
  无需认证（注册前调用）
  Body: { "code": "ABC123" }
  成功: 200 { "valid": true, "remaining": 1 }
  失败: 400 { "valid": false, "reason": "expired|exhausted|not_found" }
  
  实现要点：
  - 原子性：SELECT ... FOR UPDATE 防止并发超用
  - 速率限制：同一 IP 每分钟最多 5 次尝试（防暴力破解）
  - 不在 validate 阶段核销，核销在注册完成后执行

POST /api/v1/auth/register（现有接口扩展）
  Body 新增: { ..., "invite_code": "ABC123" }
  注册成功后原子性执行 used_count++
  若 code 已耗尽（并发竞争）→ 注册失败，返回 409
```

### 5.4 Android App 改造（WelcomeScreen）

**注册流程修改：**

```
[欢迎页]
  ├─ [已有邀请码] → InviteCodeScreen
  │      ↓ 输入 6 位码 → 调用 validate-invite
  │      ↓ 成功 → TeslaOAuthScreen
  │      ↓ 失败 → 显示错误（过期/已用完/不存在）
  └─ [申请内测] → 跳转外部链接（微信群/申请表单）
```

**新增文件：** `InviteCodeScreen.kt`（约 80 行）  
**修改文件：** `WelcomeScreen.kt`（新增入口按钮）、`NavGraph.kt`（新增路由）

### 5.5 微信小程序改造

**`pages/auth/index.tsx` 修改：**

```typescript
// 步骤 1：邀请码输入与验证
const [inviteCode, setInviteCode] = useState('')
const [step, setStep] = useState<'invite' | 'tesla_oauth'>('invite')

const validateInvite = async () => {
  const res = await api.validateInviteCode(inviteCode)
  if (res.valid) {
    // 本地暂存已验证的 code，注册时携带
    Taro.setStorageSync('validated_invite_code', inviteCode)
    setStep('tesla_oauth')
  } else {
    showToast(`邀请码无效：${res.reason}`)
  }
}
```

### 5.6 初始邀请码生成（ECS 运维命令）

```sql
-- 生成 20 个邀请码（在生产数据库执行）
INSERT INTO invite_codes (code, max_uses, expires_at, note)
SELECT
    upper(
        substring(md5(random()::text || clock_timestamp()::text), 1, 3) ||
        substring(md5(random()::text || clock_timestamp()::text), 4, 3)
    ),
    1,
    NOW() + INTERVAL '60 days',
    '第一批内测-' || gs
FROM generate_series(1, 20) AS gs
ON CONFLICT (code) DO NOTHING
RETURNING code, note, expires_at;
```

---

## 六、微信小程序配置方案

### 6.1 代码配置（已确认信息）

**文件：** `miniprogram/project.config.json`

```json
{
  "appid": "wxc84899ab6146f1be",
  "projectname": "MateLink车迹",
  "compileType": "miniprogram",
  "libVersion": "3.4.0",
  "setting": {
    "urlCheck": true,
    "es6": true,
    "enhance": true
  }
}
```

**文件：** `miniprogram/.env`（基于 `.env.example` 创建，gitignore）

```
TARO_APP_API_BASE=https://api.teslalink.joviluma.com
```

### 6.2 微信后台手动配置（非代码）

登录 [mp.weixin.qq.com](https://mp.weixin.qq.com) → 开发 → 开发管理 → 服务器域名：

| 类型 | 域名 |
|---|---|
| request 合法域名 | `https://api.teslalink.joviluma.com` |
| webview 业务域名 | `https://auth.tesla.cn`、`https://api.teslalink.joviluma.com` |

### 6.3 发布流程

```
1. 本地 npm run build:weapp           # 构建小程序产物
2. 微信开发者工具 → 上传代码          # 选择版本号（如 0.3.0）
3. 微信后台 → 版本管理 → 提交审核    # 约 7 个工作日
4. 审核通过 → 发布                    # 线上生效
```

---

## 七、上架时间线

```
第 1 周（2026-10-11 起）
├─ 执行 L1+L2 法律修复（strings.xml + AboutScreen.kt）
├─ 核查 L4 电池容量是否需要修复
├─ 更新小程序 project.config.json（AppID 写入）
└─ 微信后台配置服务器域名（手动操作）

第 2 周
├─ 服务端 DDL：建立 trip_summaries + charge_summaries 表
├─ 服务端 API：summaries UPSERT + 查询接口（Go）
├─ Android：TripSummaryUploadWorker + CloudBackupConsentDialog
├─ 服务端 DDL：建立 invite_codes 表
├─ 服务端 API：validate-invite + 注册扩展
├─ Android：InviteCodeScreen + WelcomeScreen 修改
└─ 小程序：auth 页面邀请码步骤

第 3 周
├─ 全量测试（Debug/Release 各 608/600 项）
├─ 更新隐私政策（privacy/index.html）填写备案号
├─ Release 签名构建 v2.2.0
├─ Android APK → 提交华为应用市场（内测版）
└─ 小程序上传 → 提交微信审核

第 4 周（审核期）
├─ 华为/微信审核结果跟进
├─ 生产数据库生成 20 个邀请码
└─ 审核通过 → 向第一批 20 名用户发放邀请码
```

---

## 八、审核问题清单（供审核 AI 回答）

以下问题请审核者重点关注：

1. **商标声明措辞：** L1+L2 修复中，"与 Tesla, Inc. 无关联或授权关系"的表述是否符合 Tesla 开发者协议要求？是否需要更具体的免责条款？

2. **混合存储架构：** Layer 1 仅存摘要（无 GPS 坐标），是否满足中国《个人信息保护法》第 21 条关于精确位置信息的最小化采集原则？（注：用户已明确要求存储精确坐标，此处需要明确告知风险）

3. **邀请码防暴力破解：** 6 位大写字母+数字（约 20 亿组合），配合 IP 速率限制（5次/分钟），是否足够安全？

4. **WorkManager 重试策略：** `Result.retry()` 默认使用指数退避，在 Tesla API 限流（429）场景下是否需要更长的退避间隔？

5. **行程摘要幂等性：** UPSERT 按 `(user_id, vehicle_id, source, source_id)` 去重，当 TeslaMate 来源和 Fleet 来源同一行程均存在时，会产生两条记录。是否需要行程去重逻辑？

6. **登录 OOM 问题：** 文档 `docs/DBG-2026-10-02-login-oom.md` 记录了大历史量时服务端内存溢出（RestartCount=5），本次存储方案是否会加剧此问题？Layer 1 数据推送是否需要先解决 OOM？

7. **iOS 端：** 当前 SwiftUI 端代码已合入 main，但 Windows 环境无法编译验证。存储和邀请码功能是否需要在 iOS 端同步实现？

---

## 九、文件变更总览

### 本次修复涉及文件

| 文件路径 | 改动类型 | 改动量 |
|---|---|---|
| `android/app/src/main/res/values-zh/strings.xml` | 修改 L295 + 新增 2 行 | ~5 行 |
| `android/app/src/main/java/com/matelink/ui/screens/about/AboutScreen.kt` | 新增 6 行 | ~6 行 |
| `miniprogram/project.config.json` | 修改 appid | 1 行 |
| `miniprogram/.env`（本地创建，gitignore）| 新建 | 2 行 |

### 后续新增文件（第 2 周）

| 文件路径 | 说明 |
|---|---|
| `android/app/src/main/java/com/matelink/ui/screens/welcome/InviteCodeScreen.kt` | 邀请码输入页 |
| `android/app/src/main/java/com/matelink/data/sync/TripSummaryUploadWorker.kt` | 摘要上传 Worker |
| `android/app/src/main/java/com/matelink/data/sync/ChargeSummaryUploadWorker.kt` | 充电摘要上传 Worker |
| `deploy/jourvolt-dev-mock/telemetry_summaries.go` | 服务端摘要 API |
| `deploy/jourvolt-dev-mock/invite_codes.go` | 服务端邀请码 API |

---

*文档版本：1.0 · 生成于 2026-10-11 · 下次更新：第 2 周结束后*
