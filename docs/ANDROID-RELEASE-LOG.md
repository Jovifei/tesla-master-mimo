# MateLink Android 版本发布台账

本文件记录 MateLink Android App 每次**正式包（release）**的版本更新与真机安装情况，倒序排列（最新在最上）。

适用范围：Release 正式包为 `com.matelink`；Debug 固定为隔离包 `com.matelink.test.mock`，不得覆盖车主包。
完整构建、部署与真机验证 SOP 请参见：[SOP-ANDROID-DEVICE-DEPLOYMENT-AND-VERIFICATION.md](file:///e:/project/tesla_master/app_mimo/docs/SOP-ANDROID-DEVICE-DEPLOYMENT-AND-VERIFICATION.md)

> 安全约定：本文件只记录构建配置的文件路径与公网地址，**不记录 keystore 口令**。
> 签名配置文件位于仓库外（`E:\Claude_allow\matelink-release.properties`），不提交远端。

---

## [2.1.12 / build 31] — 2026-09-10 本地验证候选（未安装）

- 版本从 2.1.11/build30 递增；源码提交为 `fe27f2b`（功能基线 `4e97691949ecef9a39fd135be3efb988907b0f61`），本阶段补充了不完整旧别名行的统计过滤契约与最小实现。
- Debug/Release JVM 各 543 项：0 failures / 0 errors，Release 8 项预期跳过；`lintDebug`、`lintRelease`、`assembleDebug`、`assembleDebugAndroidTest` 和签名 `assembleRelease` 均通过。
- Release APK：`android/app/build/outputs/apk/release/app-release.apk`；由 `fe27f2b` 重建，SHA-256：`98E763A01D699E43FFFC9A9C4A824B6A7FFA2DC099AA30BE2FDF5544639DA810`。
- 正式证书 SHA-256：`9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`；包名保持 `com.matelink`，未安装手机。
- 本轮没有部署 ECS；临时 PostgreSQL 因本机 Docker Linux 引擎不可用未执行，未连接生产数据库。

---

## [2.1.11 / build 30] — 2026-09-09 阶段版本（已构建，未重复安装）

- 基于已推送的 `6ced331` Telemetry 数据真实性修复和浏览器选择优化；仅更新 Android 版本号与本次更新说明。
- 保留 Tesla 登录浏览器选择；等待车辆、Telemetry 和历史数据继续按真实证据显示，不生成合成数据。
- 已构建并核验 `com.matelink` Release，非 debuggable；证书 SHA-256：`9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`。
- APK SHA-256：`C86C1FD079491B85143BF0604A6C4C88F9C8AB95A932CE2BD68DCF18817B54BA`。
- Debug/Release 各 526 项测试：0 failures / 0 errors，Release 8 项预期跳过；`lintDebug`、`lintRelease` 通过。
- 本阶段版本已提交并推送；没有再次安装设备，设备当前仍是 2.1.10/build29。

---

## [2.1.10 / build 29] — 2026-09-09 浏览器选择与 Telemetry 数据真实性修复

- Tesla 登录改为 App 内选择浏览器；显式启动所选应用，避免默认浏览器闪退阻断登录。取消后可重新发起登录；虚拟钥匙深链与 OAuth 回调保持原流程。
- 已构建并核验 `com.matelink` Release，非 debuggable；证书 SHA-256 与既有正式包一致：`9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774`。
- APK SHA-256：`45F920C6ADB6B9B971553319C6EEAFCF5453AE80CDBE49BEF0D8FC5868EC0EE5`。
- 源码提交：`6ced331`，已推送至 `fix/20260907-onboarding-source-integrity`。
- ECS 已部署同一提交：`/healthz`、`/readyz` 内外均返回 `build_sha=6ced331`、`mode=fleet`、`persistence=postgres`、`status=ok`；现有远端源码备份位于 `/home/jourvolt/jourvolt-staging/.rollback-6ced331-source-sync`，`.env` 未读取或修改。
- OnePlus 7 Pro（`6e4fa92f`）已用同签名 `adb install -r` 覆盖安装；version `2.1.10` / build `29`，`firstInstallTime=2026-08-31 22:36:47` 保持不变，未卸载或清理用户数据。
- Debug/Release 各 526 项测试：0 failures / 0 errors，Release 8 项预期跳过。首次组合构建因 2 GB Gradle 堆不足停止；独立打包使用临时 4 GB 堆参数成功，未修改项目 JVM 配置。
- 最终 `lintDebug`、`lintRelease` 均通过。
- 实机只读查询确认 MATCH_ALL 能返回 Chrome，普通查询仅返回默认 Heytap；安装后 `com.matelink` 进程启动存活，crash buffer 中未发现 `com.matelink` FATAL。手机仍锁屏，未猜测图案，浏览器列表点击和真实 OAuth 回流留待 Jovi 解锁后验收。
- 数据链路源码审查见 `docs/audits/2026-09-09-browser-and-telemetry-readiness.md`；不能承诺授权后自动补齐所有曲线及历史指标。

---

## [2.1.9 / build 28] — 2026-09-08 授权恢复重试与行程列表地址修复

### 已验证

- Go `go test ./... -count=1`、`go vet ./...`、`go mod verify` 通过。
- Android Debug/Release JVM 测试通过；Release lint 0 errors（现有 warnings 保留）。
- Release APK 已用现有 `com.matelink` 同签名证书构建并通过 `apksigner` V2 校验；SHA-256 为 `D080F637A259D661B64C55DCBEA8CD68432E75EB72ABBEF9D94D54CFB37703BD`。
- OnePlus 7 Pro（`6e4fa92f`）已执行 `adb install -r` 覆盖安装；version `2.1.9` / build `28`，`firstInstallTime=2026-08-31 22:36:47` 保持不变，证明未卸载、未清理本地数据。

### 本次修复

- `/v1/auth/exchange` 成功后，对历史 `telemetry_error`、`pairing_required`、`permission_required` 车辆触发一次去重 configure 重试。
- 行程列表从真实首尾坐标执行高德逆地理编码；已有地址和缺失坐标保持真实语义。

### 真实数据边界

- 当前 ECS 尚未收到新的 Tesla MQTT 首事件；GPS、今日行程、路线/速度/功率曲线和真实充电仍需 Jovi 完成 Tesla 官方授权、虚拟钥匙配对并产生车辆事件后验收。
- 安装后进程可启动且无 FATAL/ANR 日志；设备随后回到图案锁屏，未猜测用户图案，页面级点击验收留给 Jovi 解锁后执行。

---

## [2.1.6 / build 25] — 2026-09-06 历史归档与位置真实性修复

### 已验证

- 本地历史不再在 Android 或服务端按“两天”截断；上传按服务端 200 条/类型上限分批，保留完整有效归档。
- 服务端已部署本提交；现有 68 条 `local_import` 记录保留并转换为 `incomplete/local_import_unverified`，不会再被接口全量隐藏，也不会进入统计或曲线。
- 空的实时 GPS 快照不会覆盖有效缓存；缓存位置只能显示为 `database_latest` 与原缓存时间，不能冒充实时 Telemetry。
- 旧可信自托管 API 的缺失质量字段在远端边界归类为 `legacy_remote_api`；本地未验证导入仍严格排除分析。
- Android Debug/Release JVM 各 515 项：0 failures / 0 errors；Go test/vet 通过；独立代码复审无 Critical/Important。
- 已同签名 `adb install -r` 覆盖 OnePlus 7 Pro：`com.matelink` 2.1.6/build25，首次安装时间仍为 `2026-08-31 22:36:47`；冷启动仍进入既有车辆，不出现登录页或 FATAL。
- APK：`app-release.apk`，SHA-256 `2619874B8A708BCD6DA8CC3B2D43E28625104F0FCEB8CE7EB0CF4FD76F2763ED`。

### 真实数据边界

- 当前生产 Telemetry 仍为 `awaiting_first_event`，数据库仍为 pairing/latest/route points 均为 0；因此位置页正确显示“等待车端 GPS”。
- 旧导入摘要没有路线点、地址或速度/功率采样，不能诚实补出“未知→未知”、地图路线或曲线；电池健康接口仍由服务端明确标记 unsupported。
- 需要 Jovi 在 Tesla 官方流程完成虚拟钥匙/Telemetry 配置，并产生一段真实驾驶和一次真实充电后，才能验收实时 GPS、起终点、路线/速度/功率曲线、充电曲线与健康趋势。

---

## [2.1.5 / build 24] — 2026-09-06 数据真实性与 Fleet Telemetry 修复

### 证据状态

- 源码/本地门禁：Go test/vet、Android Debug/Release JVM、Debug/Release lint、Debug/AndroidTest APK 编译均通过。
- 包边界：Debug APK 静态核对为 `com.matelink.test.mock` / `MateLink Test`；Release 仍为唯一的 `com.matelink` 候选。
- 已安装：同签名 `adb install -r` 覆盖 OnePlus 7 Pro 的 `com.matelink`；安装后为 2.1.5/build24、非 DEBUGGABLE，首次安装时间保持为 2026-08-31 22:36:47，启动和进程级 FATAL 检查通过。
- APK：`app-release.apk`，SHA-256 `A041DCC1D1A5367017D15DF2A8E3B1F7A107E3D37A40F732ABA396E3447E5494`；签名摘要与设备安装前包相同。
- 未执行：Tesla 虚拟钥匙/Telemetry 配置及真实行程、充电验证；未删除生产数据。

### 服务端同步状态

- ECS API 已按 `b15960d` 部署；`/healthz` 和 `/readyz` 均返回 200，后者显示 `awaiting_first_event`。
- 旧云端记录未删除：66 条 drives、2 条 charges 已标记为 `local_import/quarantined`；真实 Telemetry 首事件仍未到达。

### 本次范围

- Fleet Telemetry 官方枚举和单位归一化、逐字段回退、行程/充电质量状态与隔离。
- Android 移除合成历史/曲线/假状态，地图只显示有效真实坐标并转换一次到高德坐标系。
- 行程/充电/统计在未观测或 quarantined 数据下保持不可用，不再把未知归类为 AC/DC 或填充零值。
- Debug 恢复隔离标识，Release 禁用备份；版本递增到 2.1.5/build 24。

---

## [2.1.4 / build 23] — 2026-09-06 物理真机7大核心体验修复与纯净交付

### 一、版本标识

| 项目 | 值 |
|---|---|
| versionName | `2.1.4` |
| versionCode | `23` |
| 包名 | `com.matelink`（正式包，杜绝分身） |
| minSdk / targetSdk | 26 / 35 |
| 构建类型 | debug / release（统一正式包名 `com.matelink`） |
| 发布日期 | 2026-09-06 14:30 |

### 二、本次核心修复（7大真机问题）

1. **高德地图底图渲染**：挂接直接生命周期与工厂流转，解除逆地理编码循环死锁，矢量瓦片与 POI 渲染恢复。
2. **真实位置与零伪造**：全链路清除硬编码 `30.27°N, 120.15°E` 及假西湖区/拱墅区；无 GPS 时诚实显示“等待车端 GPS”；一旦上报即时逆地理编码。
3. **行程曲线拒绝造假**：移除详情页正弦波生成器，对仅有聚合统计的行程诚实展示说明文案。
4. **充电站点与时序修正**：纠正 18:43 第三方快充（25% -> 96%）与 19:30 超充补满（96% -> 100%）的时序与站点归属。
5. **车主 3D 渲染图内置**：内置纯黑 Model Y + 19 寸 Gemini 轮毂 3D 渲染图，精准匹配车主车型。
6. **消除手机双软件**：默认取消 `.test.mock` 后缀，保证手机上只安装唯一正式应用 `com.matelink`。
7. **全量 35 趟行程展示**：时间筛选默认置为全部时间，完整呈现所有历史行程。

### 三、真机安装记录

| 项目 | 值 |
|---|---|
| 设备 | OnePlus 7 Pro（GM1910），`6e4fa92f`，Android 11 |
| 安装方式 | `adb install -r`（覆盖安装，保留用户原有配置与 Room 数据） |
| 安装后版本 | versionCode **23** / **2.1.4** ✅ |
| 卸载双开/测试包 | `com.matelink.test.mock` 已完全卸载 ✅ |
| 桌面状态 | 唯一 `MateLink` 图标 ✅ |
| 单元测试 | 490/490 passed (`BUILD SUCCESSFUL`) ✅ |

---

## [1.4.5 / build 17] — 2026-09-03 版本号递增（同代码，供设备资格验证）

### 一、版本标识

| 项目 | 值 |
|---|---|
| versionName | `1.4.5` |
| versionCode | `17` |
| 包名 | `com.matelink`（正式包） |
| minSdk / targetSdk | 26 / 35 |
| 构建类型 | release（`SIGNED_RELEASE`，R8 + 资源压缩） |
| 发布日期 | 2026-09-03 12:09 |

**本次变更性质**：**仅递增版本号，产品代码零改动**。
目的是让设备上的包可与上一版（同为 1.4.4/16 的旧包）明确区分，便于真机资格验证时判断装的是哪个包。

### 二、源码来源

| 项目 | 值 |
|---|---|
| 分支 | `fix/20260902-android-state-and-ux-reliability` |
| 本次提交 | `1fca1c428d02ac49555cb3da864c19ab3024d867` — *chore(android): bump version to 1.4.5 (build 17)* |
| 父提交 | `e51cbad`（含 `b6509ad` 主修复 + `e51cbad` mock 徽标收尾） |
| 基准 main | `11ba77d`（未被直接修改） |
| 改动量 | 1 文件，+2 / -2（仅 `android/app/build.gradle.kts` 的 versionCode / versionName） |
| 远端状态 | 已推送；`git ls-remote` 验证分支 HEAD = `1fca1c428d02...` ✅ |
| 关联 PR | **PR #4**（Draft，待真机验证后收口） |

### 三、构建产物

| 项目 | 值 |
|---|---|
| 文件大小 | 62,331,759 字节（≈59.4 MB） |
| SHA-256 | `6389840C81542D3DC2A90F21ACD54B6727DF042E58007C246F3CDD1DC3837C23` |
| 包名校验 | `com.matelink` ✅ |
| 签名校验 | `apksigner_verified` ✅ |
| 构建耗时 | 3m28s，`BUILD SUCCESSFUL`（60 tasks，29 up-to-date） |
| 归档位置 | `E:\Claude_allow\matelink-apk-archive\matelink-1.4.5-build17-20260903-release.apk` |

构建命令与签名配置同上一版（见 [1.4.4 条目](#三构建产物)），三个公网地址为 release guard 强制值。

### 四、真机安装记录

| 项目 | 值 |
|---|---|
| 设备 | OnePlus 7 Pro（GM1910），`6e4fa92f` |
| 安装方式 | `adb install -r`（**升级安装**，17 > 16） |
| 安装时间 | 2026-09-03 12:09:11 |
| 安装前 | versionCode 16 / 1.4.4 |
| 安装后 | versionCode **17** / **1.4.5** ✅ |
| firstInstallTime | 2026-08-31 22:36:47（**未变 → 覆盖升级，Room 历史数据保留**） |
| 手机端 APK SHA-256 | `6389840c...3837c23`，与本地产物**完全一致** ✅ |
| 启动验证 | `com.matelink/.MainActivity` 前台运行，进程存活（PID 911） |
| 崩溃检查 | 0 条 FATAL / has died 日志 ✅ |

### 五、与上一版（1.4.4/build 16）的关系

| 项 | 1.4.4 / build 16 | 1.4.5 / build 17 |
|---|---|---|
| 产品代码 | 修复版（PR #4 内容） | **完全相同** |
| 版本号 | 1.4.4 / 16 | 1.4.5 / 17 |
| SHA-256 | `6ea2b47a...` | `6389840c...` |
| 大小 | 62,331,755 B | 62,331,759 B |

两版功能等价，17 仅用于版本可辨识性。**验证功能请以 17 为准**。

### 六、遗留事项

1. **真机行为资格验证未开始**（PR #4 保持 Draft 的原因）：
   - Cloud ↔ Self-hosted 强停重启
   - token 过期 + 断网
   - 重新授权：取消 / 成功
   - 切车同时轮询（竞态）
   - MQTT live → recent → fallback
   - 真实 AC / DC 充电
   - 第二次启动配置 / 语言保持
2. PR #4 待上述矩阵通过后收口、进入合并审查；之后再基于新 `main` 推进行驶完成报告 PR2。

---

## [1.4.4 / build 16] — 2026-09-03 状态恢复与实时数据 UX 可靠性修复

### 一、版本标识

| 项目 | 值 |
|---|---|
| versionName | `1.4.4` |
| versionCode | `16` |
| 包名 | `com.matelink`（正式包） |
| minSdk / targetSdk | 26 / 35 |
| 构建类型 | release（`SIGNED_RELEASE`，R8 混淆 + 资源压缩） |
| 发布日期 | 2026-09-03 |

> 注：本次为**代码修复发布**，未递增 versionName / versionCode（仍为 1.4.4 / 16）。
> 因此单看版本号无法区分新旧包，须以**构建时间 + APK SHA-256** 作为判定依据。

### 二、源码来源

| 项目 | 值 |
|---|---|
| 远端仓库 | `https://github.com/Jovifei/tesla-master-mimo.git` |
| 分支 | `fix/20260902-android-state-and-ux-reliability` |
| 基准 main | `11ba77d3206828ce4dc745d03dbde7f21eeb0201`（未被直接修改） |
| 提交 1（主修复） | `b6509add52eb854a1a92eceaf1cb69d474106e65` — *fix(android): harden state restoration and live data UX* |
| 提交 2（收尾） | `e51cbad` — *fix(android): restore mock snapshot badge and tidy charge view model* |
| 关联 PR | **PR #4**（Draft，未合并；`mergeable=true`） |
| PR 改动量 | 20 个文件，+420 / -369（仅 Android 生产代码、对应测试、一份审核文档） |

### 三、构建产物

| 项目 | 值 |
|---|---|
| 产物路径 | `app/build/outputs/apk/release/app-release.apk` |
| 文件大小 | 62,331,755 字节（≈59.4 MB） |
| SHA-256 | `6EA2B47A310B49413E4238B37C10F1FE9BD6B6123B6232E977DADD065952628E` |
| 包名校验 | `com.matelink` ✅（脚本断言，不含 `com.matelink.test.mock`） |
| 签名校验 | `apksigner_verified` ✅ |
| 构建耗时 | 4m24s，`BUILD SUCCESSFUL` |
| 构建环境 | 本机 `/tmp` 干净 clone（绕开 workspace 坏仓库） |

**构建命令（可复现）**：

```powershell
cd <clone>/android
.\build-pilot-apk.ps1 `
  -ApiBaseUrl          "https://api.teslalink.joviluma.com/" `
  -AuthHost            "auth.teslalink.joviluma.com" `
  -PublicInfoBaseUrl   "https://auth.teslalink.joviluma.com" `
  -SigningPropertiesPath "E:\Claude_allow\matelink-release.properties"
```

三个公网地址均为 `build.gradle.kts` 的 release guard **强制校验值**，缺一即 fail-fast（guard 拒绝 localhost / 内网 / HTTP / example 域名）。

**签名配置**：`E:\Claude_allow\matelink-release.properties`（仓库外）
- `storeFile=C:/Users/Admin/.android/debug.keystore`
- 证书指纹 SHA-256 `9AB144E8...81FC774`，DN `CN=Android Debug`
- 与手机既有 `com.matelink` 签名一致 → **可覆盖安装，Room 历史数据不丢**
- 取舍：这是 Android 默认调试证书而非正式发布 keystore；换正式 keystore 需卸载重装（会丢本地历史），列为上架市场前的长期决策项。

### 四、本次修复内容

**状态恢复与连接模式**
- Self-hosted 不再因残留 Cloud Session 而被第二次启动自动切回 Cloud
- 临时断网 / 5xx 不再直接清空 Session（改为保留 + 标记待刷新）
- 云 Session 刷新路径加固

**授权流程**
- 重新授权改为**非破坏式**：用户取消授权不再先注销旧 Session
- 授权成功路径与旧 Session 平滑衔接

**实时数据 UX**
- Dashboard 数据新鲜度：旧瞬时数据不再被继续标记为「实时」
- Current Charge 页实时参数修正
- 切车竞态：切车与轮询并发时的数据归属修正
- 单位显示修正

**其他**
- Settings 两字段化
- 第二次启动的语言恢复

### 五、验证状态

| 阶段 | 结果 |
|---|---|
| 远端 CI（Debug JVM 单测） | 477 / 477 PASS（failures 0 / errors 0 / skipped 0） |
| 远端 CI（assembleDebug） | PASS |
| 远端 CI（assembleDebugAndroidTest） | PASS |
| 远端 CI（lintDebug） | PASS（Lint errors 0，warnings 238） |
| 远端 CI（git diff --check） | PASS |
| 本地 release 构建 | BUILD SUCCESSFUL（含 `:app:lintRelease`） |
| 本地 apksigner 校验 | PASS |
| **真机安装启动** | **PASS**（见下节） |

### 六、真机安装记录

| 项目 | 值 |
|---|---|
| 设备 | OnePlus 7 Pro（GM1910），序列号 `6e4fa92f`，USB 调试已授权 |
| 安装方式 | `adb install -r`（覆盖升级，非卸载重装） |
| 安装时间 | 2026-09-03 11:37:04 |
| firstInstallTime | 2026-08-31 22:36:47（**未变 → 覆盖升级，本地数据保留**） |
| 安装后 base.apk | 62,331,755 字节，与构建产物一致 ✅ |
| 启动验证 | `com.matelink/.MainActivity` 前台运行，进程存活（PID 28322） |
| 崩溃检查 | 无 FATAL / AndroidRuntime 崩溃日志 ✅ |

**安装命令**：

```bash
adb install -r "<产物路径>/app-release.apk"
```

### 七、遗留事项

1. **真机行为资格验证未完成**（PR #4 尚未收口的原因），需在实机跑完并回填结果：
   - Cloud ↔ Self-hosted 强停重启
   - token 过期 + 断网
   - 重新授权：取消 / 成功
   - 切车同时轮询（竞态）
   - MQTT live → recent → fallback
   - 真实 AC / DC 充电
   - 第二次启动配置 / 语言保持

2. **PR #4 仍为 Draft**，待上述真机矩阵通过后收口并进入合并审查；之后再基于新 `main` 继续行驶完成报告 PR2。

3. **版本号未递增**：下次发布建议递增 versionCode（如 17），避免同版本号覆盖时难以从包信息判断新旧。

---

## 附录：如何判定手机上跑的是哪个版本

由于 1.4.4/build 16 本次未递增版本号，判定安装是否为本次修复版请用：

```bash
# 1. 看更新时间（本次应为 2026-09-03）
adb shell dumpsys package com.matelink | grep -E "lastUpdateTime|versionName|versionCode"

# 2. 看 base.apk 大小（本次应为 62331755 字节）
adb shell stat -c '%y %s' $(adb shell pm path com.matelink | tr -d '\r' | cut -d: -f2)

# 3. 精确比对 SHA-256（拉取后与 6EA2B47A... 比对）
adb pull $(adb shell pm path com.matelink | tr -d '\r' | cut -d: -f2) phone.apk
sha256sum phone.apk
```

---

*本台账随每次正式包发布追加更新。*
