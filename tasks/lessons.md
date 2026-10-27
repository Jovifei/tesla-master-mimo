# Lessons

## 2026-10-24 历史加载体验与本地优先持久化
- 严禁加载时的整屏空白阻断（图二缺陷）：进入页面或下拉/切 Tab 时，不可用全屏纯色 Loading 替换整体内容。必须始终保持既有或本地缓存的列表/图表内容可见，仅在前景覆盖轻量半透明遮罩（alpha = 0.20f）与居中加载标志（150.dp）。
- 本地优先（Room SQLite）0ms 呈现：打开或切换页面时，先同步从本地数据库读取全量缓存并即时完成状态映射渲染，避免每次都依赖网络下发；网络返回后平滑增量刷新，彻底消除用户感知的加载等待。
- 业务与使用频次匹配的时间范围：充电通常每隔数天一次，默认筛选必须为近 30 天；行程反映日常出行，默认近 7 天。二者不能简单使用相同的时间窗口。
- 触底平滑分页加载：大数据量下不能一次性请求全量或截断展示；通过列表滚动状态检测触底，按步长自动向前扩展时间范围并拉取更早历史，配合底部轻量指示器实现无缝追加。
- 界面信噪比提升：移除与用户核心操作无关的数据来源、云端验证等声明性冗余横幅，确保视线聚焦在核心出行与补能数据上。

## 2026-10-15 统计与设备验收
- 已知能量总和与能耗效率有不同资格：总能量保留真实0及有限能量，效率只用有效能量/里程对；不能因为距离缺失把已知能量抹为未知，也不能把未知能量当0。
- 用户拔出手机并指定电脑测试后，只做隔离模拟环境和包交付；不要求重连，不把模拟器或UI组件回归标为真机通过。
- UI数据格式化必须确认内部单位，typed Wh/km展示kWh/100km除10；不能受英里显示偏好错误改变已规范化能耗单位。

## 2026-10-07 远端执行不能靠状态声明证明
- Project旧持久说明同时有远端90%与本地执行旧循环，必须统一持久配置并实际点保存、重新打开比对，不能仅关闭弹窗当保存。
- 网页回复结束且无真实Actions/新源码就是未执行交接，不把ACTIVE/“下一批继续”当后台运行；核对GitHub头和job证据。重复完整指令仍只读取返回时诊断上下文/执行环境，不无限空转或自动改派本地业务实现。
- 用户要求原会话时，新远端执行上下文的范围不确定要明确询问；原会话/代码/data/账号均保留。
## 2026-10-02 远端执行与本地交接

- Pattern：Jovi 的协作流程允许网页 GPT 在远端执行并提交独立分支，再把 Prompt/交接包交给本地 Codex，不能固定解释为远端永远只规划。
- Prevention：以用户本轮分工为准；本地先核验远端 SHA、PR、交接包和代码，再独立运行测试，禁止重复 apply。发现问题回传同一 Project 分析；合并、部署及设备操作按各自授权执行。
- 默认分工进一步明确：网页GPT是大型任务的规划者、审核者和主要执行者，约90%阶段工作在远端完成后提交GitHub；本地负责拉取、编译/手机验收、必要bug修正，再提交GitHub让远端复审推进下一大阶段。不要将后续循环退回远端逐项指挥本地实现。
- 分页上限变动必须检查所有单次大页调用方，不能仅验证常规历史/同步页；时间轴和按时间查询也需要完整翻页。
- 身份失效或账号切换后的读取失败不能保留旧账号界面数据；只有当前身份已确认的 Success/cache 结果才可继续展示。
- 参考 docs/LESSONS-2026-10-02-history-resource-bounds.md：元数据只读必要列，完整归档保留，TotalAlloc 与生产 RSS/实车证据分开记录。

## 2026-10-02 主分支同步与 ADB 端口冲突

- 已交付功能合并到 main 后，同步 origin/main 和主目录 checkout，并核验两端 SHA、ahead/behind 与工作区状态。
- ADB 默认端口 5037 被其他进程占用时，先读进程归属；本次 FlClash 占用，使用进程级 ADB_SERVER_SOCKET=tcp:localhost:15037 即可连接手机，不需改代理、VPN 或停止其进程。

## 2026-10-02 Android 交付不可停在已编译

- Pattern：界面改动即使代码已推送、Release 已构建，也不算完成；漏跑单元测试、未把 APK 覆盖安装到 Jovi 的手机会让其无法验收。
- Prevention：每次 Android 交付离开前完成 Debug/Release 单测、Lint、签名 Release、包身份/签名校验及指定真机 `adb install -r`，记录首次安装时间与启动/FATAL/ANR。遇到 Tesla 车主确认只停止在该确认，不替用户点击。

## 2026-10-01 自动接入不能把服务错误转成用户授权任务

- Pattern：502 来自代理隔离网络，不能据此声称缺钥匙或要求重新登录；用户不应承担服务器参数配置。
- Prevention：先比较受控代理/官方读取并保留错误阶段；只有官方明确缺权限/钥匙才提示本人确认。后台自动恢复，空配置和归档历史均不能冒充 Fleet 成功。
- Build evidence：构建脚本 JSON 曾误报 PASS，原始日志实际缺 SDK。必须核验日志、APK 版本/时间和签名，不安装旧产物。

## 2026-09-06 覆盖更新必须保留会话并以真实数据验收

- Pattern：`adb install -r` 的覆盖更新不等于重新安装。即使包名、签名和首次安装时间保持不变，也必须验证既有登录会话仍可用；不得把构建、健康端点或 HTTP 200 当成车辆位置、行程、曲线、充电和电池数据已经恢复的证据。
- Prevention rule：发布前后记录 package/version/firstInstallTime，并验证会话恢复而非只验证启动；对 Telemetry 必须分别核对 pairing、MQTT 消费者、latest、route points、drive、charge 和应用呈现。缺失真实事件时明确报告 collecting，不宣称修复完成。

## 2026-09-06 计划与执行授权边界

- Pattern：Jovi 在排障阶段要求先给出修复方案，代码改动须等待明确的执行授权。
- Prevention rule：诊断和计划阶段只读；只有收到明确执行指令后，才在隔离工作树实施最小改动。部署、真实设备安装、数据清理、提交和推送始终另行征询。

## 2026-08-30 首次登录必须解释数据可用性

- Pattern：只显示空白占位会让新用户误以为登录或车辆接口失败，也会让旧用户的本机历史与云端新身份混在一起。
- Prevention rule：首次绑定后按稳定车辆身份展示字段级就绪状态；缺失保持 unavailable 并解释收集条件，旧历史必须验证身份并经用户确认后迁移。

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

## 2026-08-23 真机导航验证不能在 L1 Dashboard 直接使用返回键

- 现象：在 Dashboard 上发送 `KEYCODE_BACK` 会退出 MateLink，后续坐标测试可能落到其他系统应用，产生无效结果。
- Prevention rule：真机 L1 回归使用显式 `am start` 恢复 MateLink 或直接点击底部导航；仅在已确认的 L2/L3 页面使用返回键。每次坐标输入后先验证当前焦点仍为 `com.matelink/.MainActivity`。

## 2026-08-31 Fleet vehicle UID omission caused history-page crash

- Pattern: `fleetProvider.Vehicles` calculated the stable `providerID` but omitted `VehicleUID` when constructing the API vehicle. Because the JSON field uses `omitempty`, cloud `/api/v1/cars` responses omitted `vehicle_uid`; the Android `VehicleContext` identity guard then failed while Drives/Charges loaded history. The exception escaped `viewModelScope`, so the process crashed after the page click. Efficiency and Range shared the same history repository and had the same latent path.
- Evidence: OnePlus 7 Pro `6e4fa92f` logcat at 21:55:02, 21:55:07, 21:55:51 and 21:57:25 reported `Process: com.matelink` and `IllegalArgumentException: cloud vehicle uid is required`; the release mapping resolved the stack to `VehicleContextRepository.resolve` -> `VehicleContextStore.resolveCar` -> `cloudVehicleStableIdentity`.
- Resolution: Fleet vehicle mapping now returns `VehicleUID: providerID`; missing cloud identity raises `HistoryIdentityUnavailableException`; `UnifiedHistoryRepository` converts it to a recoverable configuration result; Drives, Charges, Efficiency, Range, Cost and Stats render localized waiting copy. Stats progress observers and range detail reads also ignore this expected unavailable state. No numeric car-ID fallback was added, so history isolation remains fail-closed.
- Prevention rule: Test every stable identity field across provider model -> JSON -> Android model, and make history repositories convert expected identity-unavailable states before UI coroutines. After any history/Room change, exercise each affected navigation entry on the target device and inspect post-interaction `AndroidRuntime` logs; build success alone is insufficient.
- Boundary: local source, tests, signed APK and the live ECS health boundary are verified; real Tesla vehicle UID response and history-page clicks still require the real account flow before end-to-end Pilot can be claimed.

## 2026-08-31 ECS deployment must sync the build closure

- Pattern: The ECS working directory was an older partial Go tree. Uploading only the changed `fleet_provider.go` produced compile errors for missing Telemetry types; the failed build happened before container replacement, but a partial source tree would have been unsafe to leave.
- Prevention rule: Before rebuilding a remote Go service, compare the remote source manifest with the local build closure; upload the matching production `.go`, module files and Docker build inputs to a temporary directory, verify hashes, keep a rollback copy, and use `docker compose up --no-deps` for the API only. In PowerShell, use a single-quoted here-string for remote Bash so `$(...)` and `$1` are not evaluated locally. Validate the actual HTTP method and path before treating a 404 as a route failure.

## 2026-09-01 首次登录数据等待不是订阅失败

- Pattern: Tesla OAuth successfully creates a session and returns a current vehicle snapshot, but position, drive/charge history, standby analysis and continuous TPMS/events may still be waiting for the vehicle or Fleet Telemetry. Showing a generic error makes a normal first-login state look like an account or subscription failure.
- Prevention rule: On first connection, render per-capability `available`, `waiting_vehicle`, `collecting`, `unsupported` and `unknown` states with source/time. Explain that local history starts accumulating after login; never replace missing values with zero and never call a paid subscription requirement without a server-provided billing state.

## 2026-09-01 可选待机端点必须有本机降级

- Pattern: The cloud compatibility service did not expose the self-hosted-only standby endpoint, so Android showed `Standby data unavailable` after an expected 404.
- Resolution: The cloud route returns a typed collecting empty result; Android falls back through `UnifiedHistoryRepository` and derives only adjacent-drive parking candidates. Charge-overlap windows are excluded, and kWh/power stay nullable without measured capacity or telemetry coverage.
- Prevention rule: Treat optional history endpoints as capability states, not transport errors. Test 404, empty response, local history, charging overlap and no-history cases separately; verify the device sees a localized collecting state before claiming the page is fixed.

## 2026-09-01 AMap 和高级网络设置需要分层引导

- Pattern: A single long AMap instruction block and an ungrouped advanced-network form obscured the primary action and made cloud login look dependent on self-hosted TeslaMate.
- Resolution: Use a short three-step AMap wizard with Material icons, copy actions, privacy and verification kept in the final step. Group network inputs into server, authentication and security cards, and make copy mode-aware for cloud versus self-hosted connections.
- Prevention rule: For setup UX changes, update Compose tests and AndroidTest selectors together; preserve the existing security/verification callbacks and test 360dp-width navigation, content descriptions and large text before delivery.

## 2026-09-01 版本号和修复说明必须同批交付

- Pattern: A code update without an in-app explanation left device testers unable to tell which behavior had changed, especially when the build SHA still pointed at the pre-commit HEAD.
- Prevention rule: Every user-visible update increments `versionCode`/`versionName`, adds localized release notes, and distinguishes the version from the build Git SHA. Do not publish or push until the notes match the tested candidate and the exact APK identity is recorded.

## 2026-09-02 Release 公共协议地址必须防漏配

- Pattern: Release 构建未传 `MATELINK_PUBLIC_INFO_BASE_URL` 时，`PublicInfoLinks` 返回空值，登录页把协议按钮和 Tesla 登录按钮全部禁用，并误导用户以为云端授权不可用。
- Resolution: 默认值指向已发布的官方协议域名；Release guard 同时要求显式传入 `https://auth.teslalink.joviluma.com`，避免静默使用错误地址。每个发布包都要检查生成的 BuildConfig 和真机协议按钮状态。
- Prevention rule: 公开协议地址与 API/Auth host 一样属于 Release 必填配置；构建脚本、构建命令和设备入口必须使用同一套 fail-fast 校验，不能只凭源码默认值判断登录可用。

## 2026-09-02 重新授权导航不能让自动重定向抢先清栈

- Pattern: 设置页重新授权先清会话，Dashboard 自动登录重定向可能在异步回调导航前执行并清掉 Settings，导致登录页返回桌面或没有返回入口。
- Resolution: 登录页始终提供安全返回；有上一级时返回上一级，没有上一级时进入 Settings，并在返回窗口抑制自动重定向；成功授权后仍由统一导航清栈回 Dashboard。
- Prevention rule: 对“清会话 + 异步导航”流程测试返回、取消、超时和成功四条路径；不要只测试成功回调，也不要依赖 `previousBackStackEntry` 作为唯一来源判断。

## 2026-09-02 设备 ANR 需要区分应用阻塞与系统冻结

- Pattern: OnePlus 设备曾报告 `Input dispatching timed out`，但 ANR 采样中 MateLink 主线程和工作线程均处于 `__refrigerator`，没有 Java/Kotlin 堆栈，不能据此改业务线程。
- Prevention rule: ANR 必须同时读取 DropBox 主线程栈、进程 CPU/冻结状态和重启后复现结果；只有拿到应用栈或稳定复现后才修改代码。设备级冻结保留为 DEVICE REVIEW，不升级为应用 Bug 修复通过。
# 2026-09-08 OAuth grant renewal must reset the data-plane retry boundary

- Pattern: A valid new Tesla OAuth grant can coexist with a persisted `telemetry_error`; checking only whether a pairing row has ever been updated prevents Fleet Telemetry configure from running again.
- Prevention rule: After `/v1/auth/exchange`, retry only unsynced, non-billing-blocked vehicles with per-vehicle in-flight deduplication. Keep ordinary vehicle discovery conservative so a missing virtual key is not hammered on every poll.
- Pattern: Drive history summaries contain real route endpoints but no addresses; enriching only on the detail screen leaves the list misleadingly at “unknown → unknown”.
- Prevention rule: The list may reverse-geocode only finite, non-zero observed endpoint coordinates, preserve existing provider addresses, and keep missing coordinates null.
# 2026-09-08 Docker named-tmpfs volumes are not a shared renderer boundary

- Pattern: A Compose named volume backed by the local `tmpfs` driver can be mounted as separate tmpfs instances per container; a renderer's config file may disappear from the Fleet Telemetry container even though the renderer exited successfully.
- Prevention rule: Use one explicitly prepared host tmpfs directory (default `/dev/shm/jourvolt-fleet-telemetry-config`) bind-mounted into both containers, owned by the deploy uid/gid with mode `0700`; keep the rendered file at `0600`.
# 2026-09-28 复用本机已有 Docker 镜像

- Pattern：临时 PostgreSQL 验证第一次使用了未缓存的通用标签，触发了不必要的镜像拉取，随后立即中止。
- Prevention：测试容器启动前先用 `docker images` 核对本机已有精确标签，优先复用已安装镜像；项目下载边界不因 Docker 测试而放宽。

## 2026-10-02 云登录 502 与无界历史读取

- 状态/计数/来源查询禁止为少量元数据加载全部路线；摘要、详情和原始归档应分层，保留完整数据但按需读取。
- 502 应先对齐 Nginx、API 重启与内核 OOM 时间线，不能无证据引导用户重复登录或钥匙授权。
- 当前 OOMKilled=false 和 healthz=200 不证明此前没有 OOM，也不证明内存问题修复。
- 本地 main、origin/main、手机源码 SHA、生产 build_sha 必须分别记录；远端历史分支应分类说明，不能宣称所有分支都已合并。

## 2026-10-07 投诉日期修正
- 用户确认10月7日北京时间没有行程，实际需核对10月6日。自然数据查询需先明确北京时间日期和起止时间，不能用误记日期推定采集故障，也不能否定用户实际行驶。

## 2026-10-07 午后自然行程新观测
- 旧12:04零行程仅适用于当时；13:57已有自然新行程，源云372、一条今日记录、bridge积压0。不能复用旧快照否定用户新行程。
- 代码、CI、本地构建、签名安装、实际服务部署及自然数据验收必须逐项明确；不可让“已修复”指代未安装/未部署。
- 冻结生产源细化：historySummariesPostgres已只取路线端点，historyDetailPostgres已单条读取，不可仅凭history方法名字推定全路线。确证完整历史热点是vehicleItems/readiness调用history；列表风险为全摘要物化后Go分页，分别测试，不能混为OOM证据。

## 2026-10-07 实际交付、合法日志与跨engine镜像经验
- 普通角色PermissionError不等于没有已有合法委派。只检查当前精确命令授权；有既有权限才在服务器内按窗口脱敏读取，不能改权限/通过host挂载绕过；没有授权仍停止。
- Docker image ID可能跨engine指向manifest而非config。先比对saved manifest的config SHA、runtime身份/labels/diffIDs，再选本机loaded ID；不能照搬ID或直接继续失败tag之后的构建。
- Windows源码契约测试读Text要规范化EOL；仅修测试的行尾读取与版本快照，不通过改产品算法或跳过失败来过关。
- 手机签名读取门禁失败必须停安装。本次缺少本机临时子目录，创建允许E盘目录后重新完整验签再install-r，不能跳过既有包证书比较。
- 当前API容器重建会重置restart计数，0不证明历史问题永久消失。文档必须保留真实code/CI/build/deploy/install/natural验证层级与OPEN项目。

## 2026-10-07 驻车状态与数据真实性
- 用户确认英文history提示在驻车详情，实际safeUI为history_not_collected；不能把可选驻车功能缺数据解释为整体历史断连。逐页面核对错误来源再解释。
- 源/云速度SOC存在不代表列表字段已输出；先逐字段定位摘要映射缺口。胎压样本不足不能制造历史；旧无来源记录保留但不作为真实趋势或提醒。
- 复用测试容器时以docker inspect当前端口为准，本轮实际8159，旧记录7117不能当当前配置。

## 2026-10-07 构建必须冻结同一源码树
- 主源码编译后再新增函数和测试，测试阶段可能引用旧main产物；并行agent编辑完成后冻结源码再统一构建，测试/签名/安装绑定同一SHA。资源不足暂停额外编译，仅使用本任务范围的低并行设置，不停止其他项目进程。

## 2026-10-07 远端必须承担完整阶段执行
- Jovi再次明确：远端ChatGPT审核、规划、执行大阶段并GitHub提交；本地仅接收验证编译安装测试及回传。不把大阶段拆成反复小提示，不因远端未提交就自动改为本地主实施。
- 给原会话一个完整可执行阶段，要求持续实际源码提交与交接；按用户授权每5分钟检查结果，未完成保持进行中，不把安装或源码审查当项目完成。
# 2026-10-08 远端执行与收尾纠正
- 远端24秒最终回复仅核验入口，不构成长任务；完整prompt不等于完整执行。没有源码/实际job证据不能声称后台ACTIVE。
- 能读取和写GitHub却缺workflow dispatch，不足以证明不能执行：恢复工作时先检查现有push触发工作流与实际可用工具，不连续重复只读派工。
- Jovi要求当日结束时，停止发送与定时续跑，提交自身脱敏成果并保留Owner dirty；未验收问题明确保持OPEN。


## 2026-10-09 TLS实机验收
- CI全绿、同签安装和health200不关闭认证历史失败；按实际手机安全诊断与来源时间取证。
- SSLException不能统称证书链错误；独立HTTPS对照若同样失败，不能宣称服务独有根因，不采用信任全证书、HTTP降级或网络绕过。


## 2026-10-09 已完成交付与网页超时分开
- 完整GitHub固定源码/CI/手机回执已存在时，网页超时不能把交付重新标成未执行或重复安装。
- Jovi明确允许因长历史切换时，复用同Project新网页执行上下文，先在同一完整阶段Prompt中核验workspace再继续；不新建Codex任务，不把绑定核验单独派成短任务。


- When remote says GitHub write tools are missing, require explicit tool discovery before treating it as a human permission gate. UI unable-to-load reply and actual tool capability are independent; recover same-tab reply once and verify. Repeated unchanged polling is waiting, never implementation progress.

- Jovi explicitly authorizes same-Project replacement web chats when long/failed replies cannot recover. First retry/refresh ended failed replies; preserve generating turns. Reuse a replacement chat Jovi opens and wait for its current response; do not create another or duplicate dispatch.

## 2026-10-11 Jovi原目录与整阶段纠正
- 本项目本地工作和接收固定E:\project\tesla_master\app_mimo；不自行放入C盘worktree。接收前保留dirty并征询具体源码范围授权，不覆盖已有改动。
- 远端一次承担审核、修复、下一整阶段规划与实现、测试复审和GitHub固定交付；简短核验回复不是执行完成，不用微派工消耗次数。
- 同Project旧聊天内容过长无法加载时，按Jovi明确授权在项目集合新开聊天，保留旧聊天和指定模型强度，用一个完整阶段合同接续；不反复入口审计或微派工。跨项目目录笔误先确认，不能把MateLink源码写进jovi-automation。

## 2026-10-11 完整阶段不能以短回复替代
- Jovi再次纠正：长阶段必须按真实工作量估算和固定交付验收；远端最终答复即停止，不能声称仍后台实施。CI运行时等待完整实际结果，集中失败与实质覆盖缺口后一次完整回传，不边读边发微任务。
- 派发前明确跨层交付矩阵、完整源码路径/工具执行路线、已有覆盖及验收；两项hash测试不算跨层阶段。没有实际源码实施/同SHA全量CI/复审固定交接，不转入本地接收。
- Jovi选择保留GPT-6极高后，不再次提议或自行换模型。只有已验证的执行机制变更才具体说明并请求必要的新任务授权；只读runner/bootstrapPASS必须与A-E业务实施/最终固定源码分开。

## 2026-10-11 分工变更（最新优先）
- Jovi明确改回聊天模式：远端给完整执行方案和集中代码审核，本地Codex在原E盘执行、测试、按审核修正。不再使用/创建/跟进Work，不再强求远端Chat实际代码实施。
- 最新明确分工覆盖旧remote-only合同；普通本地A-E源码实施已由本次请求授权，不重复询问同一范围，生产/真实数据/迁移运行/网络/车辆门保持。

## 2026-10-04 单次新数据成功不能关闭间歇故障

- 一次安装/刷新出现新日期后，仍需列表→详情→返回→前台重复读取；后续复发必须重新打开事件，不用旧成功覆盖新证据。
- 错误诊断端口保留typed category，HTTP为空不能把DNS/TLS/JSON/EOF一律合并。固定枚举与安全阶段足够，禁止异常正文/响应体/URL/身份进入日志。
- Room的非空数值占位不是API观测；离线详情复用原证据mapper，未知null和真实0单独验，不能靠Int字段是否为0判断已观测。
- Portable测试不能替代生成JSON adapter、Room、Hilt、Compose或APK验证；版本号、源码、签名、安装与真实复验各自记录。

## 2026-10-13 Android收口与小程序接续
- Jovi明确最新顺序：完成能耗→验证安装→合并main→独立小程序分支；旧main不合并约束已由明确授权更新，但不能未闭合就合并。
- 客户端能耗交付不强制捆绑服务器schema实验；独立候选完整保存，只撤回本会话明确拥有且hash未变的改动。
- App阶段结束后只处理必要收尾，不扩展无关App功能；小程序接续现有代码、AppID与M3.1，不重建项目或覆盖较新主线业务。

## 2026-10-14 Jovi来源/通道/交互纠正
- 小程序迁移不得只追求页面可打开；每项数据保留来源、计算版本与作用域，逐层验证API→归一化→合并→缓存→列表/详情→重开。
- 以前版本通过的能力必须保留可执行回归；新版本全链验证不能用局部单元通过替代。
- App与小程序核心操作流程保持一致，微信差异明确记录；通信/认证边界审查不得通过降低安全性解决。
- Raw receipt资格判断必须看到实际收到的候选；预先用缓存补齐null会抹掉缺证据事实。弱摘要与真实纠正分开，原字节保持和新SOC重算两组旧/新回归同时通过。

- 版本回执以固定commit源码、生成BuildConfig和实际APK三方核验为准；接续摘要中的48是旧标签，当前冻结/提交347d实际49/2.1.30，0源码drift，不误判新writer。

- 详情请求失败/停止重试属于本地执行状态，不能写回summary qualityState/qualityReason。保留行数不等于保留统计资格：真实Room回归必须比较count/distance/energy/cost并重开，0/null和quarantined一起检查。

### 2026-10-14 全阶段统一验收
- 用户明确授权完整继续后，App SOURCE_APPROVED只是阶段节点；继续完成保数据安装、主分支和小程序本地交付，不在派发或中间检查点停止。
- 不能让Taro可选旧Vite peer与现代Vitest硬共用根依赖；用独立测试工具锁，不force/legacy-peer。两份锁分别ci/audit，保留真实剩余告警。
- 缓存车单仅授权离线阅读；当前numeric ID必须从真实getCars的origin/account/generation/UID关系验证。
- UI不展示任意服务端错误/状态/quality理由；报告时间不是逐字段时间，config_synced不是用户车辆钥匙确认。

### 2026-10-14 小程序整包复审补充
- encodeURIComponent不转义点号，origin/account/UID拼接必须同时严格转义分隔符。旧歧义键保留，不猜迁移或删除。
- 缓存过滤不仅轨迹数组，还需normalized/snake GPS标量及旧cache读取投影；保留原值与非位置业务字段。
- 可信消费必须排除隔离记录，并保持分页入口；旧click和详情反馈不能绕过quarantine。
- legacy充电输入不等于有AC物理资格；旧缓存也需再验合同，不按标量字段名称授予AC。
- WebView外部错误、未知status及迟到pending结果需有限文案与同事务/lifecycle守护；先真实API去重回归，不能凭猜测放宽generation规则。

### 2026-10-14 最后增量
- API先清session再请求logout，UI无论成功/失败都应同步当前真实storage session；旧请求不能无条件setSession(null)覆盖新账号。
- 同source/ID/exactwindow弱null numeric不是清除SOC/距离；保留既有值，真实非null0/更正仍生效，unknown合同依然权威。
- 相同整数SOC不证明0kWh；测试要区分真实0字段与有合格API合同的真实0能量，不能为了测试转绿伪造估算零。

### 2026-10-14 grade仲裁
- 通用历史已选择整条old/next时，后续专用energy/charge/enrichment不能再次消费被拒绝输入或从落选记录嫁接证明；质量选择要在真实消费者链验证。
- observed→derived测试必须同source/window且含真实能耗receipt；只测普通distance字段或异source会漏掉专用分支。
# 2026-10-14 行程页面反馈

- 行程卡按Jovi确认的四指标组织：时长、距离、最高速度、平均能耗；总kWh留详情，不擅自增加卡片指标。
- 数字、单位、SOC和图表颜色须作为一个可审阅页面设计确认；屏幕验收不能被源码/CI替代，长单位必须完整显示。
- 发版版本变化要同步严格版本契约；测试失败不可只改生产版本后就安装。


### 2026-10-15 单位与汇总复核
- API preferred单位下原值不应二次转换，但必须用同一响应unit_of_length证明计算分母；不能只依据云km状态撤回selfhost英里缺陷。原receipt保留、分析单位规范化分别测试。
- 未知距离/时长不当0累计成完整总量；已知样本平均分母和覆盖分别展示，真实0与缺失明确区分。


### 2026-10-15 跨页面能耗消费者
- 修改数据单位后必须追遍主卡、Stats、Trip、Mileage、Timeline和Curves实际页面；主formatter通过不能替旁路raw消费者通过。
- 里程页同样只消费qualified netEnergy，负净能量/真实0保留；平均按合格energy/distance对，不用全里程稀释；估算与报告计数说明分开。
- 曲线原API已preferred，避免再乘mile因子；rawreceipt单位属于同响应，不能借后来status修改。显式null metadata与缺键兼容分别测。


## 2026-10-15 网页审核回复与源码版本核对
- Jovi纠正：网页已有审核回复时，先刷新同一会话并检查已保存回复，不能仅凭末尾错误提示判断没有回复。
- 逐项绑定Iteration、被审SHA和最终SHA；已有旧版本CHANGES_REQUIRED与最终版本未获复审分别说明。重试前确认没有已完成回复，避免重复消耗远端次数。

## 2026-10-15 安全与兼容阶段复核
- 凭据client策略必须追到遥测配置等旁路；正常refresh构造器安全不能证明所有Bearer请求安全。
- 凭据正文需检查响应头后的取消/读取失败，不能解析ReadAll返回的部分数据后发布成功token。
- 隔离迁移CI先按实际镜像的完整必填环境复现；缺MQTT_HOST不能通过放宽迁移测试处理。
- OCI归档按index、manifest、config位置分别验证，正确哈希本身不证明归档是可运行镜像。

## 2026-10-15 生产Compose归档容器识别
- 定向升级前按com.docker.compose.project/service标签枚举所有状态容器，不只看当前规范名称；历史归档容器可能仍有相同标签并被Compose误选。
- 归档桥保留原活跃容器与游标，生产桥使用独立matelink-archive-production项目；后续部署使用private/archive-production-compose.json，不再由原home项目批量重建桥。
## 2026-10-25 新源码与实机页面复核

- 收到“项目代码已更新”的纠正后，先核对原工作区 HEAD、远端分支、手机实际 versionCode 与截图，再决定是否沿用上一轮结论；旧 SHA 的审核结论不能直接判定新页面。
- 离线 Room 回退不能凭全库 `driveId` 唯一就认定当前车辆：必须通过当前账号、来源、API origin 与车辆映射得到本地 history car key，再读取同一键下的两端行程。
- 行程历史里用户指定的 SOC 位置和技术徽标要以实机渲染核验，源码单测与签名安装本身不能证明布局符合要求。
- 一段驻车可含充电、停充、SOC下降、复充；单个 `linked_charge` 和整段 `SOC差/时长` 不能充当逐次充电或非充电耗电。逐会话时间/能量证据要分开，数据不足时不把混合净变化标成停车耗电。

## 2026-10-19 行程卡视觉与统计位置
- Jovi纠正：行程卡SOC应在右侧；列表不显示“功率积分100%”覆盖率徽标，能量证据留在详情。
- 月度柱状图属于顶部统计区，应在摘要后、历史列表前，不能插在两段历史记录之间。
## 2026-10-20 页面空态与布局复查

- Jovi纠正：不能用一次构建或几张列表截图声称“每页数据已打通”。发布审查必须按已安装版本逐页核对同一记录的来源、资格、计算、持久化和实际空态；源码推导与手机观测分开报告。
- 首页温度趋势存在示意点时，不得拿它证明真实驻车温度历史；任何模拟数据须与真实数据明显隔离，不能参与真实极值。
- 行程列表以密度为先；详情页的曲线、指标卡和来源说明应按内容层次成组，避免两列大卡与曲线穿插造成视觉跳跃。修改费用入口先用实际截图和用户确认具体位置。
- Jovi纠正远端审核交接：启动聊天、发路径或确认文件存在都不等于远端看过真实界面。必须验证远端已读逐页审查文档，并确认图片像素实际可见；若连接器只给元信息，使用用户授权的隐私筛选后聊天附件补足，再要求逐张审核。
- 同一手机记录在列表、详情及再次进入后的变化必须按稳定 ID/同车/同筛选复核。合计变化或列表回落不等于真实历史删除；先保留前后截图，追 API list/detail、资格合同、Room receipt 与刷新时点。
- Jovi已授权本阶段按推荐方案完成后集中审核；已授权范围内不要因普通实现取舍或可复现缺陷反复征询。所有独立真实数据、生产与凭证门仍以具体证据陈列，不冒称已验收。

## 2026-10-21 四列真实文案与最终画面

- 四列组件的中文已知数值测试通过，不代表英文未知值也能在正常屏宽同时可见。回归必须用实际页面的最长本地化标签、未知占位、真零和单位检查视口边界；紧凑占位可用“—”，读屏仍须说完整未知语义。
- 行程与充电不能共用 `trips` 计数单位；同一合成充电的 12.5 kWh 卡片与汇总不可因过早取整看成不同数据。最终截图必须取最后一次源码修改后的包，不能拿前一个构建画面背书。

## 2026-10-24 最新底座与完整远端阶段
- Jovi纠正：不要把一个大阶段切成几十秒结束的纯审计小任务。给同一 Project 聊天一份覆盖审核、源码修复、下一阶段实际实施、全量测试复审和固定 GitHub 提交的完整合同；已有生成中的回复先等完成，不重复发送。
- GitHub main、最新 Draft App 候选、原 E 盘 Owner 脏工作区和手机安装包各有自己的 SHA／版本。版本号较大不证明源码包含最新 PR；同 SHA CI 也不证明手机或生产已更新。
- 四轮胎压和温度的“当前有值／历史为空”要追观测时间、来源、作用域及 Room 写入；`暂无数据`不能伪成零，更不能用错车缓存填充。
- 账号隔离修复要复审首次加载、轮询、远端失败、挂起的地址解析和 UID 重绑。纯策略函数测试通过不能替真实 ViewModel 消费者通过。
## 2026-10-25 原阶段继续与可见像素门

- Jovi已明确要求在现有授权内持续执行；代码能核实的分支自己查，普通源码、隔离测试和 PR 修复不因重复确认而停下。真正的登录、本机权限或生产数据门才提出精确问题。
- API35 JUnit 与 PNG 文件头、尺寸、大小均不能证明画面可见。合成截屏需检查真实像素内容、完整/缺数据画面的差异及对应固定 SHA；全黑图必须保留为 FAIL，不能宣传为视觉验收。
- Room-first 页面验证要区分缓存已发布与网络阶段已完成；测试币种必须与费用 receipt 同币种，跨币种不可为了测试通过混加。

## 2026-10-25 build71 实机功能回退纠正

- 视觉“精简”不能等于删掉用户已批准的字段。改驻车、行程或充电详情前，先列出前后参数/图标/颜色逐项对照；默认保留原能力，确需移除必须先征询 Jovi。
- 列表有估算费用而摘要写“暂无数据”、筛选 7 天而摘要显示全历史，是跨消费者口径不一致；必须用同一车、同一窗口、同一币种与同一费用资格的实机/Room/UI回归核对，不能只看单个卡片。
- 当前车辆 SOC 不能充当过去某段驻车的起止 SOC。界面应分别说明“当前观测时间”和“历史区间证据”，优先追同窗相邻行程/充电/温度实际持久数据，确无证据才显示清晰空态。
- 时间按钮必须控制摘要、图表、列表同一数据范围；用户需能查询更早指定日期，不得因默认 7/30/90 天截断历史。保持有界分页与手机本地缓存。

## 2026-10-25 API35 Compose UI 原生测试与横向筛选行

- 在同一页面挂载多组横向滚动筛选行（如日期筛选和距离筛选）时，必须为每组提供明确的 `testTag`，避免测试定位滚动容器时发生歧义。
- Compose `LazyColumn` 仅将视口内的可见项组合进语义树；在异步数据从网络返回前执行静态次数的滑动重试会导致测试提前用尽次数而超时。长列表测试必须在 `waitUntil` 等待块内结合滚动与检查，直到异步项真实进入视口。
## 2026-10-25 Jovi再次明确原目录与变更授权

- 本项目后续默认在 `E:\project\tesla_master\app_mimo` 工作，不因隔离便利自行改用 C盘或其他 worktree。接续先查最近会话是否正在同目录回迁，避免并发切分支或覆盖。
- Jovi当前要求代码修改前征询授权：先完成只读定位、列清实际文件与最小改法，再申请具体范围；不要用历史宽泛授权覆盖当前明确限制。
- Obsidian/台账中的旧版WIP须与当前 Git、同SHA CI及设备版本核对，不能把已交付修复重新列为待修。
## 2026-10-26 Jovi明确只续修逻辑、不改布局

- 审核结束不等于实施完成；得到继续修改授权后直接完成代码与验证，不停在方案。
- 本轮禁止改页面布局和参数排列：冻结现有Screen/资源哈希，只修数据和并发逻辑；他人已有页面dirty不回退、不认领为本轮修改。
## 2026-10-26 并发写入时按最新授权隔离审查

- Jovi在其他任务仍写原目录时，明确允许新分支/worktree；该授权覆盖此前本轮不得自行创建worktree的默认约束。使用E盘独立树，原目录不切分支、不覆盖。
- 复制候选先后与目标哈希须一致；源码漂移要更新受影响页面结论，不拿旧审查覆盖新内容。共享Git core.worktree被其他任务改动时，本进程显式绑定实际根，不修改对方共享配置。
## 2026-10-27 App分支与版本记录

- Jovi明确要求授权修复在App分支实施并提交；不得把审查分支或小程序分支当App交付。其他App分支已占用时使用同固定基点的独立codex/app-*修复分支，保留原任务。
- 每项问题保留发现日期、当时版本、证据、每次改法/测试与实际commit；源码版本、候选包、已安装版和上线验收分开，不能只写“已修复”。

## 2026-10-27 Jovi要求完成实际显示验收

- 签名候选和本地用例完成后，仍须继续推进已授权逐页验收；不能以“真机待验”替代执行。先探测设备，核对安装版与代码提交，再检查用户反馈的具体字段。
- “账户数据”等有歧义的反馈须确认原意，不能直接定性成串账号或缓存错误；等待澄清时并行检查真实消费者的正例及漏值路径。
- 接口/Room有值、资格拒绝、计算丢值、显示丢值分别留证；缺少真实采样的字段不能补造数值来满足表面全有数据。
- Gradle会先构建多个变体的依赖，再执行测试；看到Release预编译不能推断Debug测试已经运行。读取XML先核对本轮test任务完成和产物时间，不把上轮RED输出当本轮GREEN。
