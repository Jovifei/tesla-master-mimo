# MateLink Android 版本发布台账

本文件记录 MateLink Android App 每次**正式包（release）**的版本更新与真机安装情况，倒序排列（最新在最上）。

适用范围：`com.matelink` 正式包。debug 测试包（`com.matelink.test.mock`）不入此台账。

> 安全约定：本文件只记录构建配置的文件路径与公网地址，**不记录 keystore 口令**。
> 签名配置文件位于仓库外（`E:\Claude_allow\matelink-release.properties`），不提交远端。

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
