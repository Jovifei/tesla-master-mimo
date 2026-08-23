# Lessons

## 2026-07-09 Git Approval

- Pattern: The user requires explicit approval before any Git commit.
- Prevention rule: Before staging or committing, list the exact candidate files and rationale, then wait for Jovi's explicit approval. Do not run `git add` or `git commit` proactively.

## 2026-07-09 Connection Success Versus Data Readiness

- Pattern: A successful TeslaMate API connection can still leave Dashboard without live status if `/api/v1/cars/{id}/status` returns an API-level "no info" response.
- Prevention rule: When debugging refresh/no-data issues, separately verify connectivity endpoints (`/api/ping`, `/api/readyz`, `/api/v1/cars`) and data endpoints (`/status`, `/drives`, `/charges`) before describing the cause to Jovi.

## 2026-07-09 Partial Data UI and Trip Semantics

- Pattern: Showing raw fallback/debug English on a Chinese UI makes a connected-but-partial state look like an app error.
- Prevention rule: For user-facing fallback states, localize the copy and label the exact data source; for trip history, treat parked gaps between drives as timeline items instead of assuming drives alone equal the full trip.

## 2026-07-11 Vehicle Data Truthfulness

- Pattern: Several screens turned missing realtime or historical fields into `0`, `false`, fabricated capacity, or a free charge, making unavailable data look authoritative.
- Prevention rule: Preserve nullable values through repository, calculation, and UI layers. Every derived metric must declare its source and sample coverage; only an explicit user override may mark a charge as free.

## 2026-07-26 Map Localization

- Pattern: New map configuration copy was added only to the default English resources, breaking consistency in the Chinese UI.
- Prevention rule: Add user-facing map strings to `values-zh` with Chinese text, keep English in the default resource set, and eliminate hardcoded UI copy so the existing language setting controls both variants.

## 2026-07-27 UI Localization Completeness

- Pattern: A localized section heading can hide untranslated child action labels until the full scrollable screen is exercised.
- Prevention rule: During UI polish, inspect every reachable screen state in the active locale, including content below the initial viewport, and verify each referenced string has a locale-specific value.

## 2026-07-26 Device Crash Verification

- Pattern: A process that survives initial launch can still crash shortly afterward when an asynchronous Room query opens the database.
- Prevention rule: Treat startup as passing only after the target workflow is exercised and a post-interaction log check shows no application crash; never clear user data to mask a migration failure.

## 2026-07-26 Default Repair Delivery

- Pattern: Pausing for a second repair authorization after Jovi assigns a defect delays the requested end-to-end delivery.
- Prevention rule: Treat a task assignment as authorization to diagnose, implement the minimal repair, build, preserve-data install, and complete device verification; ask only before materially expanded or destructive work.

## 2026-07-26 Device Configuration Preservation

- Pattern: Instrumented tests and APK deployment can affect user-owned on-device configuration even when source changes target an unrelated feature.
- Prevention rule: Before any connected Android test or install, snapshot configuration metadata without reading secrets, exclude tests that mutate stores, and prove the configured-state flags remain unchanged after the operation. Stop all writes immediately if a user reports lost configuration and perform read-only recovery triage first.

## 2026-07-26 First Configuration Sync and Map Key Verification

- Pattern: Persisting a first connection configuration and immediately scheduling a foreground worker can crash the process if the manifest omits the exact requested service type; directly storing an untested map Key leaves the user unable to distinguish a valid configuration from a failed one.
- Prevention rule: For every foreground WorkManager path, verify the merged manifest declares its exact foreground-service type and exercise the post-save return path on an isolated emulator. Treat a map Key as pending until an isolated SDK test reports success; show explicit pass/fail, preserve an existing verified Key on candidate failure, and never display the stored value.

## 2026-07-26 Room Versioned Metadata Recovery

- Pattern: A repaired Room schema can still crash if an installed database already reports the repaired version while retaining an older identity hash, because the earlier migration will not run again.
- Prevention rule: When correcting Room identity metadata in a shipped version, add a subsequent no-schema-change migration and regression test from the already-shipped version so Room validates the tables and refreshes only its own master metadata without deleting user rows.

## 2026-07-29 Android Resource Format Contracts

- Pattern: Compose `stringResource` formats can crash at render time when a literal percent is not escaped or a placeholder type does not match its Kotlin argument.
- Prevention rule: For every touched formatted resource, add a JVM format-contract test for each supported locale; before a release, enumerate formatted `stringResource` calls and verify literal `%` and numeric placeholder types against the call site.

## 2026-07-30 Dense Telemetry and Metric Coupling

- Pattern: Large two-column cards, year-heavy timestamps, separated metrics and charts, and inconsistent cost fallbacks make history screens sparse and harder to verify.
- Prevention rule: Prefer adaptive three- or four-column metric strips while retaining touch targets; use Chinese-first compact addresses and no-year history timestamps; always render the real duration; place each metric group directly beside its corresponding curve; never fabricate an unavailable curve; resolve manual, free, backend, and estimated charge costs through one shared function across list, summary, chart, and detail.

## 2026-08-01 AMap Integration Reachability

- Pattern: A verified standalone SDK preview can coexist with legacy page-level map wrappers that silently discard coordinates and routes, making a successful Key look broken everywhere else.
- Prevention rule: When adding a native SDK integration, inventory every wrapper and click entry, route them through one lifecycle-safe renderer, and verify point, route, and multi-marker variants on the installed app. A location action must open the intended in-app map; it must not navigate to an unrelated list, become conditional no-op, or silently fall back to another provider.

## 2026-08-01 Non-blocking user choices

- Pattern: A choice that only affects presentation or the next local step should not pause the entire repair and verification flow.
- Prevention rule: Surface such choices in an in-app or inline popup when available, keep the approved default moving, and stop only for destructive actions or materially expanded scope.

## 2026-08-01 Do not close a partially implemented design

- Pattern: A successful build, install, and smoke test does not prove that every item in an approved multi-page design is implemented.
- Prevention rule: Track each approved requirement to code, test, and device evidence; report partial completion explicitly and continue until every required item has its own proof.

## 2026-08-09 End-to-end repair must include device proof

- Pattern: A build-only result is not enough when the user reports a runtime Docker or Android configuration problem.
- Prevention rule: After the smallest safe fix, verify the live service boundary, build the APK, install with data-preserving replacement, exercise the affected UI path, and inspect crash markers before reporting completion. Keep the exact host-port migration visible when an OS reservation prevents the original port.

## 2026-08-09 Missing translations are a release-gate classification

- Pattern: `MissingTranslation` lint counts describe multilingual coverage and release-gate completeness; they are not a count of runtime product bugs.
- Prevention rule: Decide the supported-language strategy before remediation. If only Chinese and English remain supported, remove the optional `de`/`ja`/`fr` language exposure and stale resources. If five languages remain supported, complete all three resource sets. Never add a lint baseline to conceal the coverage problem, and do not claim new crash or data-correctness evidence without runtime proof.

## 2026-08-09 Login smoke must prove the post-login destination

- Pattern: A mock login response and a visible success card can pass while the user still remains on the login screen with no vehicle-home/session navigation.
- Prevention rule: For every login flow, verify the full path `tap login -> session state -> destination screen -> vehicle data refresh -> logout`; classify response rendering alone as partial, never as product entry completion.

## 2026-08-10 JourVolt delivery target is the original full App

- Pattern: A successful local response card is not the requested product when the user expects Tesla login to open the original Dashboard and all existing vehicle pages.
- Prevention rule: Keep the login screen as an auth boundary only; after session exchange, route to the original Dashboard and verify refresh, vehicle selection, history empty states and logout before installing an APK.

## 2026-08-11 External gate is not a reason to leave production code as a stub

- Pattern: Stopping at a working Mock UI while OAuth/Fleet endpoints still return fixed placeholders does not advance the requested real-login delivery, even when credentials and domains are legitimately unavailable.
- Prevention rule: Implement and test every credential-independent part of the real path behind fail-closed configuration, then state the smallest external action needed for true provider proof. Keep Mock, config-ready code and real-provider evidence as three separate statuses.

## 2026-08-11 Verify account controls from the populated state

- Pattern: Settings/logout can appear reachable in an empty or partial Dashboard while disappearing once full vehicle status renders.
- Prevention rule: Exercise account controls after successful vehicle refresh, not only from login/error states; keep a fixed Settings entry visible in the normal populated Dashboard and verify logout returns to the login page.

## 2026-08-12 Navigation callback thread safety

- Pattern: A background login request can complete successfully but crash when its success callback directly mutates Compose Navigation state from `Dispatchers.IO`.
- Prevention rule: Keep network/session work off the main thread, but dispatch every UI state change and navigation callback to `Dispatchers.Main.immediate`; verify the post-login destination on an emulator.

## 2026-08-12 Single-app product boundary

- Pattern: A new Consumer package and shell can satisfy a mock login check while violating the requested MateLink product identity, local-data continuity and original navigation contract.
- Prevention rule: Keep `com.matelink` as the only formal package, use runtime cloud/self-hosted mode inside the original app, and isolate Mock in `com.matelink.test.mock`; never treat a separate app shell as the delivery path.

## 2026-08-14 Preserve the original MateLink analysis UI

- Pattern: A new visual sample can be technically clean while violating the actual product request when the user wants the original MateLink information density, layout and visual language preserved.
- Prevention rule: Treat the populated original `Stats`/analysis page as the visual baseline. Make only local hierarchy, spacing, overflow, loading and wording corrections; do not replace the theme, remove existing analysis content, or present a design prototype as the production UI without explicit approval.

## 2026-08-15 Controlled cohorts for comparative recommendations

- Pattern: Reusing every normal-speed drive as a high-speed baseline allowed cold-weather samples to contaminate the speed comparison, so one factor could be mistaken for another.
- Prevention rule: Build comparative recommendations from controlled cohorts: hold temperature constant when comparing speed, hold speed constant when comparing temperature, and suppress the recommendation when either controlled cohort lacks the required sample or distance coverage.

## 2026-08-21 Windows PowerShell script encoding

- Pattern: A newly added PowerShell preflight script without a UTF-8 BOM failed to parse under Windows PowerShell 5.1 because it contained Chinese string literals.
- Prevention rule: Keep executable `.ps1` source ASCII-only unless the file is deliberately emitted with a PowerShell 5.1-compatible BOM; localized Chinese belongs in adjacent Markdown documentation, not in the executable script.

## 2026-08-21 Build scripts must own the working directory and artifact freshness

- Pattern: A wrapper invoked Gradle from the caller's repository root, so Gradle failed in the wrong directory; the stale release APK then made a later package check look successful.
- Prevention rule: Build wrappers must push the directory containing the Gradle wrapper, capture the child exit code immediately, and only inspect an artifact after a successful build from that same invocation.

## 2026-08-21 Deployment generators must support a clean checkout

- Pattern: The App Link generator assumed the optional `public` directory already existed, so even a non-writing preview failed on a clean checkout.
- Prevention rule: Treat generated deployment directories as absent until the write branch creates them; preview and validation paths must not require runtime output directories.

## 2026-08-21 Debug Mock session lifecycle must remain explicitly debug-only

- Pattern: The Debug Mock account-deletion path initially reused the formal cloud URL validator, so it rejected the intentionally local Mock API despite the local service supporting deletion correctly.
- Prevention rule: Select the loopback auth API only when `JOURVOLT_MOCK_LOGIN` is true, and pass a narrowly scoped local-HTTP exception only in that branch. Release must continue to reject loopback, public HTTP and Mock routes.

## 2026-08-22 Preserve populated original analytics as the implementation baseline

- Pattern: Replacing the original MateLink analysis page with a clean visual prototype loses the information density and content the product actually needs.
- Prevention rule: Keep the populated original page as the baseline; implement only local state, evidence, algorithm and overflow corrections inside existing cards and navigation unless Jovi explicitly authorizes a visual redesign.

## 2026-08-22 Keep debug session endpoints isolated from production cloud

- Pattern: A shared session refresher can silently use the production base URL even when the Debug Mock login flow uses a local API.
- Prevention rule: Select the Mock base URL only behind the Debug Mock build flags; keep Release on the fixed HTTPS cloud URL and cover both branches with a source contract test.

## 2026-08-22 Persist the server-selected vehicle identity

- Pattern: A cloud vehicle list can return a user-scoped stable ID that differs from an old local TeslaMate car ID; rendering the first vehicle without saving that ID makes the next polling request target the wrong vehicle.
- Prevention rule: When the selected fallback vehicle is known, persist its server-provided stable ID before starting live polling; keep the local setting as the source for subsequent refreshes.

## 2026-08-22 Do not hide dashboard transport errors in a partial state

- Pattern: The original Partial Dashboard accepted an error string but did not render it, so authorization, rate limiting and service failures looked like ordinary first-sync waiting.
- Prevention rule: Keep legacy error message/code compatibility, add a stable error category, and map transport failures to explicit localized UI states before claiming that a vehicle is syncing.

## 2026-08-22 Map OAuth HTTP failures before rendering login errors

- Pattern: Showing raw `HTTP 503` or transport text on the login page makes a cloud configuration/service problem look like a user credential problem.
- Prevention rule: Keep the official OAuth flow unchanged, map response classes to localized actionable resources, and retain raw server details out of the normal user-facing login copy.

## 2026-08-22 Formal release must exclude Mock at the source-set boundary

- Pattern: A Debug-only Mock entry placed in `src/main` can disappear from the visible UI while still relying on R8/resource shrinking to remove test behavior from Release.
- Prevention rule: Put Mock UI, ViewModel, and Mock-only resources in `src/debug`; provide only a no-op same-signature entry in `src/release`, then inspect the final APK for Mock classes/resources.

## 2026-08-22 Legal pages must be part of the edge contract

- Pattern: An app can expose Terms and Privacy links while the reverse proxy only serves App Link metadata, causing the required documents to fall through to the API and return 404.
- Prevention rule: Serve legal pages statically at the edge, verify local files during preflight, and verify `/terms/` and `/privacy/` remotely together with `assetlinks.json` before enabling OAuth.

## 2026-08-22 Shared status copy must not hardcode one connection mode

- Pattern: A More page banner described TeslaMate as the prerequisite for live data, which was false for the planned JourVolt cloud connection and confusing for users who had already connected another source.
- Prevention rule: Shared UI status copy must describe the actual data availability contract, not name one backend. Keep source-specific details in source-aware state or diagnostics, and verify both English and Chinese resources after changing the copy.

## 2026-08-22 Deployment bundles must carry their static legal content

- Pattern: The full checkout could serve legal pages from `../../web_matelink/public`, but a server receiving only `deploy/jourvolt-dev-mock` would lose the login-linked Terms and Privacy pages.
- Prevention rule: Make the static public root configurable, provide a no-secret bundle generator that copies the reviewed public content, and let preflight verify the resolved root before startup.

## 2026-08-22 Verify the final Release artifact, not only source-set separation

- Pattern: A Debug-only Mock entry can disappear from the visible UI while shared code still packages a Mock source label or honors a persisted Mock preference in Release.
- Prevention rule: Make production Mock behavior fail-closed at runtime, then scan the final APK for Mock/provider/loopback markers after `assembleRelease`; source contract tests alone do not prove the packaged artifact.

## 2026-08-22 Separate emulator boot from ADB readiness

- Pattern: QEMU can finish Android boot while the host ADB server has no emulator device or 5554/5555 listener; treating process existence or boot logs as an installable emulator would overstate runtime evidence.
- Prevention rule: Require both `adb devices` with the explicit emulator serial and `sys.boot_completed=1` before installing or running UI checks; record boot-only states as `NOT_PERFORMED`.

## 2026-08-22 Preserve missing battery fields separately from real zero readings

- Pattern: Flattening nullable Tesla battery fields into `0` made an incomplete battery response look like a real `0%` or `0 km` reading, especially when opening the detail overlay.
- Prevention rule: Carry observed battery/range fields separately to the UI, gate each card on field availability, and keep an observed zero value valid; test missing, zero, and partially populated responses independently.

## 2026-08-22 Keep charge summary missing values distinct from observed zero

- Pattern: A charge record with no energy or cost field was aggregated with nullable values coerced to `0`, making incomplete history look like zero energy or free charging.
- Prevention rule: Aggregate only finite observed non-negative values, keep summary metrics nullable, expose coverage for charts, and render unavailable when no source value exists; an observed zero remains valid.

## 2026-08-22 Keep persisted Room placeholders separate from API observations

- Pattern: A legacy Room row may use zero as an unknown placeholder, while a live API response can legitimately observe zero. Treating both paths identically either fabricates data or hides a real reading.
- Prevention rule: Keep the legacy mapper conservative for persisted placeholders; preserve nullable fields through live response and calculator layers, and test missing, observed-zero and partial-response cases separately.

## 2026-08-22 Missing grouping inputs must stay unavailable

- Pattern: Replacing an absent speed with a numeric default can make a rule engine look complete even when no speed evidence exists.
- Prevention rule: Use explicit finite-value predicates for every recommendation grouping input; missing speed or temperature must remove the sample from that group and be covered by a boundary test.

## 2026-08-22 Normalize legacy summaries before analytics

- Pattern: Room compatibility entities can contain zero placeholders even after the live API models became nullable; reading entities directly in a new analytics path bypasses the evidence contract.
- Prevention rule: Every analytics path that starts from persisted summaries must pass through the neutral API mapper first, then aggregate only nullable/finite values.

## 2026-08-22 Summary cards must consume coverage, not raw aggregates

- Pattern: Even after nullable mapping, a non-empty legacy dataset can leave aggregate fields at zero while no valid source sample exists; using only record count makes the summary look observed.
- Prevention rule: Pass explicit valid-sample coverage into summary/conclusion builders. Record count, observed zero, and unavailable must remain separate states and each boundary needs a test.

## 2026-08-22 Keep adjacent overview cards on the same evidence contract

- Pattern: The primary analysis card used coverage-aware metrics while neighboring quick-stat and annual-report cards still gated on record count, so one screen could show unavailable in one place and a placeholder zero in another.
- Prevention rule: Every presentation of the same metric must use the same coverage/evidence decision, including secondary cards and reports; do not repair only the first visible card.

## 2026-08-22 Release 字面量门禁不能只依赖 R8

- 现象：Release 的逻辑分支已经通过 `BuildConfig` 关闭 Mock，但共享 Repository 中的 `mock_fixture` 字面量仍可能被 R8 保留，导致 APK 静态扫描不满足正式版边界。
- 根因：运行时偏好和 suspend Repository 方法让编译器无法可靠证明 Debug 分支在 Release 永远不可达。
- Prevention rule：Debug-only provider 标识和本地调试地址通过 Debug 专属 `BuildConfig` 注入；Release 使用空值并在 Repository/页面层双重 fail-closed。每次 Release 构建都要对 APK 二进制扫描 Mock、回环、Debug 登录标记和错误包名，不能只看源码或 lint。

## 2026-08-22 已定义的空状态必须真正贯穿页面

- 现象：`INSUFFICIENT_COVERAGE` 已存在于领域模型，但页面没有渲染分支；筛选无记录和已有记录但字段不足会落入同一个“尚未采集”状态。
- Prevention rule：新增状态必须同时完成分类函数、每个相关 ViewModel 的状态赋值、UI 分支、双语文案和边界测试；只定义 enum 不算功能完成。

## 2026-08-22 实时遥测缺失不能默认成零

- 现象：电池或充电接口字段为 `null` 时，UI 直接用 `0`、`100` 或默认进度继续展示，用户会把未知误认为真实读数。
- Prevention rule：保持 nullable 观测值直到 UI 边界；只有完整输入才计算派生值或绘制进度，真实观测零值单独保留并测试。

## 2026-08-22 记录数不等于里程指标覆盖率

- 现象：年度/月份/日期里程聚合有记录就会生成卡片，但距离、能耗或电量字段可能缺失；直接对 nullable 字段使用 `?: 0` 会把“不知道”显示成零。
- Prevention rule：每个指标单独统计有效样本数；图表只接收有有效观测的时间桶，UI 只有在对应样本存在时才格式化数值，真实零值通过单独边界测试保留。

## 2026-08-22 趋势算法不应以零值兜底缺失基线

- 现象：趋势计算中用默认 `0.0` 填充缺失的 baseline/current，会让缺少输入的退化结果看起来像真实计算。
- Prevention rule：先验证 baseline/current 均为有限正数，再计算百分比；任一输入缺失时返回趋势-only 或不可用状态。

## 2026-08-22 详情统计的数据类也必须保留缺失证据

- 现象：列表摘要已处理缺失值后，单次行程详情仍用非空 `DriveDetailStats` 和 `?: 0`，导致速度、功率、海拔、电池和距离缺失被显示成真实零值。
- Prevention rule：领域/展示统计类型的字段保持 nullable 到 UI 边界；聚合只在有观测时格式化，真实零值单独保留，并为“全缺失、真实零、矛盾输入”补纯函数测试。

## 2026-08-22 一次性 OAuth ticket 必须防重复交换

- 现象：Activity 重建或重复 App Link 可能再次提交已经消费的一次性 callback ticket，产生误导性的 `401` 或覆盖当前登录状态。
- Prevention rule：客户端同时记录 in-flight 与已成功处理的 ticket；重复 intent 直接忽略，只有新的非空 ticket 才进入交换，并用纯函数测试四种边界。

## 2026-08-22 已取消的 OAuth 请求不能覆盖新请求状态

- 现象：用户快速重试或 Activity 收到新 callback 时，旧请求的 `onFailure` 仍可能把当前登录流程改写为错误状态。
- Prevention rule：异步回调写入 UI 状态前必须确认 ticket 仍是当前 in-flight ticket；旧请求只清理自己的状态，不得覆盖新请求的 Loading、Success 或 Error。

## 2026-08-22 登录启动请求也必须隔离过期回调

- 现象：只保护 callback ticket 仍不够；快速再次点击登录时，旧的 `/start` 请求取消异常可能覆盖新流程，甚至继续打开过期授权 URL。
- Prevention rule：所有可取消的认证请求共享请求代次；完成、失败、打开 Custom Tab 和写入 session 前确认仍是当前代次，并显式忽略 `CancellationException`。

## 2026-08-22 Debug Mock 登录也不能绕过认证并发规则

- 现象：正式 OAuth 已有请求代次保护，但 Debug Mock 登录仍可能在快速重复点击时写回旧 session，导致本地回归结果不稳定。
- Prevention rule：Debug-only 认证入口必须复用正式认证的取消/代次边界；Mock 只能替换 provider，不能降低会话状态机的并发要求。

## 2026-08-23 测试 APK 不能冒充原包覆盖升级

- 现象：用户要求在原 `com.matelink` 上覆盖安装并保留配置，但我安装了带 `.test.mock` 的隔离 Debug APK，导致手机出现第二个 MateLink 图标。
- Prevention rule：设备安装前必须先向用户明确显示 `applicationId`、签名状态和是否覆盖原包；只有使用原签名、`com.matelink`、可验证的 Release APK 才允许执行覆盖升级。隔离测试包只能在用户明确接受第二个包时安装；正式 Release 未签名时必须停止，不得用 Debug 包替代。

## 2026-08-23 覆盖升级后的旧连接模式迁移

- 现象：原服务器地址和 Token 仍在设置页，但已持久化的 `TESLA_CLOUD` 模式让升级后的 App 进入云连接分支，无法直接恢复旧 Dashboard。
- Prevention rule：升级启动时必须重新检查“无云会话 + 旧自托管配置”并优先恢复 `SELF_HOSTED`；迁移逻辑必须有回归测试，且签名升级后的实时连接结果要和配置保留结果分开记录。

## 2026-08-23 Release 自托管 HTTP 与 AMap JNI

- 现象：Release 平台资源禁止所有明文 HTTP，导致旧可信局域网 TeslaMate 配置升级后无法连接；放行后 AMap R8 又暴露 JNI 反射类被裁剪/改名，产生 native SIGABRT。
- Prevention rule：可信局域网 HTTP 只在 Release 自托管资源层放行，公网地址仍由 `UrlSecurity` 拒绝；第三方 native/反射 SDK 必须保留原类名并用实体冷启动验证，不能只依赖 JVM/Compose 测试。

## 2026-08-23 Release 底栏路由不能依赖 qualifiedName

- 现象：Release 实体页面只有首页，`MateLinkBottomBar` 因混淆后的类型安全 route 与 `qualifiedName` 不匹配而返回空。
- Prevention rule：导航顶层匹配必须兼容 JVM `$` 路由和 R8 改写前缀，使用稳定类型名末段回退；签名 Release 必须逐项点击验证一级入口。
