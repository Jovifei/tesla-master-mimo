# app_mimo 未完成任务实施计划

> 状态日期：2026-07-09
>
> 目标：把当前“UI 基本可用”的 `app_mimo` 推进到“用户可清楚配置真实 TeslaMate 数据，并能区分真实 / Mock / 降级数据”的可验收阶段。

## 当前结论

本阶段已经把“自托管 TeslaMate + TeslaMateApi/MateLink-compatible API + 高德 Key 用户自备”的产品口径写入 README、Android、iOS、Web 和交接文档。下一步不再重复做说明文案，而是围绕真实配置链路做验证、补洞和交付确认。

关键边界：

- MateLink 不直接登录 Tesla，也不替代 TeslaMate。
- 真实数据需要用户自托管服务器、NAS、VPS 或长期在线 Docker 主机。
- App 只填写 API 根地址，例如 `https://teslamate-api.example.com`，不要填写 `/api/v1`。
- 用户应填写 TeslaMateApi 或 MateLink-compatible API 地址，不是 Grafana 地址，也不是 TeslaMate Web UI 地址，除非该服务明确暴露兼容 `/api/v1`。
- 高德 Web 服务 Key 由用户自行申请；仓库不得内置真实 Key。
- Mock Mode 不需要服务器；Real Mode 请求失败时必须清楚显示错误，不能静默显示 mock 数据。

## 已完成

- README 已补充真实数据部署方式、推荐拓扑、安全建议和高德 Key 说明。
- Android / iOS / Web 的配置文案已统一为自托管 TeslaMate API 根地址。
- Android Manifest 中的 AMap Key 已改为 Gradle placeholder，避免写死示例 Key。
- Web 端已完成 lint/build 验证。
- XML / JSON 静态解析、敏感 Key/Token 扫描、`git diff --check` 已通过。
- `E:/project/tesla_master/docs/git_ref/` 保持只读。
- Git staging / commit 必须等待 Jovi 明确审批。

## 未完成总览

| 优先级 | 模块 | 未完成事项 | 当前阻塞 / 证明边界 |
| --- | --- | --- | --- |
| P0 | Android | 原生单元测试和真实设备 smoke 未完成 | 当前 Windows 环境无 `JAVA_HOME` / `java` |
| P0 | Android | 首次配置、三段连接测试、保存规则需要设备级验收 | 需要 JDK + Gradle + Android 设备或模拟器 |
| P0 | Web | 真实 TeslaMateApi 环境 smoke 未完成 | 需要可访问的测试 API 根地址 |
| P0 | Cross-platform | 数据来源标签需要逐屏确认，不允许 Mock/降级数据混淆 | 需要页面级人工核查 |
| P1 | iOS | 多实例能力已做源码层实现，但未做 Xcode 编译和模拟器验收 | 需要 Mac / Xcode / CocoaPods 或 XcodeGen 环境 |
| P2 | Android Widget | 设备级验证未完成 | 需要 Android 设备/模拟器 |
| P2 | iOS Widget | target、entitlements、App Group、timeline provider 未完整验证 | 需要 Mac / Xcode |

## P0 实施计划

### 1. Android 首次配置与连接验证

- [ ] 配置 JDK，并确认 `java -version` 和 `JAVA_HOME` 可用。
- [ ] 运行 Android 单元测试：`.\gradlew.bat testDebugUnitTest`。
- [ ] 覆盖连接测试用例：URL 为空、缺少 `http://` / `https://`、`/api/ping` 失败、`/api/readyz` 404、`/api/v1/cars` 401、车辆为空、车辆成功。
- [ ] 新安装 App，确认未配置且非 Mock 时进入“连接 TeslaMate”引导态，而不是普通 Settings 长表单。
- [ ] 验证连接测试步骤：先 `/api/ping`，可用时 `/api/readyz`，最后 `/api/v1/cars`。
- [ ] 验证 `/api/readyz` 不支持时只显示 warning，不直接判定失败。
- [ ] 验证首次保存规则：首次配置必须测试成功后保存；高级跳过必须显示风险提示。
- [ ] 验证实例编辑器：新增实例前要求测试成功或明确跳过；切换实例后取消同步、清缓存、触发同步行为保持。

验收标准：

- 错误 token 明确提示 401 和检查 API Token。
- 成功连接后 Dashboard 显示真实车辆。
- Mock Mode 下启动进入 Dashboard，并带 Mock 来源提示。
- 未配置且非 Mock 时不会误导用户以为已有真实数据。

### 2. Web 真实数据配置

- [ ] 准备一个可访问的 TeslaMateApi / MateLink-compatible API 测试根地址。
- [ ] 首次打开 Web，确认未配置时进入 onboarding。
- [ ] 在 Onboarding 输入 API 根地址和 token，确认调用 store 的 `setServer(url, token)` 并持久化到 `localStorage`。
- [ ] 刷新页面，确认 `serverUrl`、`apiToken`、`onboardingDone` 保留。
- [ ] 确认 API 请求路径统一拼接 `/api/v1/cars`、`/api/v1/cars/{id}/status` 等 native 文档口径。
- [ ] Real Mode 请求失败时显示错误 / 不可用状态，不静默回退 mock。
- [ ] “Use Mock Data” 只在 Mock Mode 下进入 Dashboard，并显示 Mock banner / 来源标签。

验收标准：

- Web 可以清楚区分未配置、真实连接成功、真实连接失败、Mock Mode 四种状态。
- 所有用户可见 URL 示例都是根地址，不带 `/api/v1`。

### 3. 跨端数据诚实统一

- [ ] 逐屏检查 Android、iOS、Web 的 Dashboard、Charts、Timeline、Range、Battery、Map、Widget 显示。
- [ ] 所有估算、摘要、图表、地图、Mock 数据、降级数据增加数据来源标签。
- [ ] 真实数据不可用时显示“不可用 / 需要配置 / 请求失败”，不要显示看起来像真实结果的 mock 数值。
- [ ] URL 帮助文案统一为“输入 API 根地址，不要附加 `/api/v1`”。
- [ ] About / Data Source 明确写出 Requires self-hosted TeslaMate + TeslaMateApi-compatible API。

验收标准：

- 用户能一眼区分 Real、Mock、Fallback、Estimated。
- 不再出现“Grafana 地址”“TeslaMate Web UI 地址”与 API 地址混淆。

## P1 实施计划

### 4. iOS 多实例验收

- [ ] 在 Mac 上打开 iOS 项目并完成依赖生成 / 安装。
- [ ] 编译 iOS App。
- [ ] 验证 Settings 中实例列表包含 active 标记、切换、编辑、删除。
- [ ] 验证每个实例保存独立 URL/token。
- [ ] 切换实例后刷新 `AppState.real`、车辆列表和当前车辆。
- [ ] 删除 active 实例时确认 fallback 行为清晰，不导致 Dashboard 残留旧车数据。

验收标准：

- iOS 能完成新增、切换、编辑、删除实例的完整用户路径。
- 切换实例后 Dashboard 使用新实例车辆，不残留旧实例数据。

## P2 实施计划

### 5. Widget 验证

- [ ] Android Widget 在真实设备或模拟器上验证数据刷新、空状态、Mock/Real 标签。
- [ ] iOS Widget 验证 target 是否存在。
- [ ] iOS Widget 验证 entitlements、App Group、timeline provider 是否全部接通。
- [ ] 只有设备或模拟器验证通过后，才能把 Widget 状态从 deferred 改为 complete。

验收标准：

- Widget 不展示过期或伪真实数据。
- Widget 的数据来源标签与 App 内一致。

## 文档与交接

- [ ] 更新 `docs/PHASE-HANDOFF-2026-07-09.md`，把已完成项、待验证项、阻塞项分开写。
- [ ] 更新 `docs/STITCH_PAGE_MAPPING.md`，记录 Android/Web/iOS 数据配置变化和剩余页面级核查项。
- [ ] 在 `tasks/todo.md` 追加本轮执行结果和 proof boundary。
- [ ] 若 Jovi 审批提交，先列出候选文件，再 staging，再提交。

## 不提交项

以下属于环境、缓存或编译产物，默认不提交：

- `android/.gradle/`
- `android/.idea/`
- `android/app/build/`
- `android/local.properties`
- `web_matelink/dist/`
- `web_matelink/node_modules/`

## 推荐提交范围

待 Jovi 审批后，优先提交以下源码与文档：

- `README.md`
- `docs/PHASE-HANDOFF-2026-07-09.md`
- `docs/STITCH_PAGE_MAPPING.md`
- `docs/superpowers/plans/2026-07-09-app-mimo-data-setup.md`
- `android/app/build.gradle.kts`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/matelink/**`
- `android/app/src/main/res/values*/strings.xml`
- `ios/MateLink/Features/**`
- `ios/MateLink/Resources/**/*.strings`
- `web_matelink/src/api/client.ts`
- `web_matelink/src/messages/*.json`
- `web_matelink/src/pages/*.tsx`
- `tasks/todo.md`

`tasks/lessons.md` 是流程规则文件，是否纳入提交需 Jovi 单独审批。
