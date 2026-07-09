# MateLink app_mimo Phase Handoff - 2026-07-09

## 1. Source Documents Reviewed

| Source | Path | Role in this handoff |
|---|---|---|
| Architecture | `E:/project/tesla_master/docs/01-ARC-系统架构.md` | Defines TeslaMate as the self-hosted data source, mock/real split, native app + web architecture, and Network-First/cache/mock direction. |
| Main PRD | `E:/project/tesla_master/docs/PRD/MateLink_PRD.md` | Defines first-run data setup, multi-instance management, connection testing, empty/error states, tariff configuration, Widget/watch scope, and acceptance expectations. |
| Stitch Swiss PRD | `E:/project/tesla_master/docs/PRD/MateLink_Stitch_Swiss_PRD_2026-07-05.md` | Current product baseline for `app_mimo`: 4-tab mobile shell, More/Settings system area, mock honesty, and page-level completion scope. |
| UI PRD | `E:/project/tesla_master/docs/PRD/MateLink_UI_PRD.md` | Defines what each page should display, how settings should expose Server URL/API Token/Test/Save/Mock, and the visual/data honesty rules. |
| GLM implementation plans | `E:/project/tesla_master/docs/PLAN/glm/*.md` | Historical implementation plans and phase goals; useful as intent, not proof of current completion. |
| MIMO interaction plan | `E:/project/tesla_master/docs/PLAN/mimo/mimo_交互规划.md` | Defines Web/onboarding/settings interactions and mock mode banner expectations. |
| Word implementation plan | `E:/project/tesla_master/docs/Tesla_MateLink_Implement_Plan.docx` | Historical three-platform delivery plan; marks build phases as complete, but current handoff verifies against actual `app_mimo` source. |
| Reference repos | `E:/project/tesla_master/docs/git_ref/` | Read-only reference library. Do not edit; use only for comparison and implementation borrowing. |
| Current live mapping | `E:/project/tesla_master/app_mimo/docs/STITCH_PAGE_MAPPING.md` | Existing current-status ledger for page reachability, deferred Widget status, and proof boundaries. |
| Data catalog | `E:/project/tesla_master/app_mimo/docs/DATA-CATALOG.md` | Field inventory and API endpoint map for display data. |

## 2. Executive Status

`app_mimo` has a strong UI shell and enough real-data plumbing to continue integration, especially on Android and iOS. The UI is no longer the main blocker. The current blocker is data-configuration clarity: users need an explicit path to connect their own TeslaMate instance, know whether they are seeing mock or real data, and understand what to do when data is missing.

Current phase status:

| Area | Status | Notes |
|---|---|---|
| Android app shell | Mostly complete | 4-tab shell, More hub, Settings, detail navigation, many analysis/report routes are wired. |
| Android real data | Partially complete | `TeslamateRepository` supports cars/status/drives/charges/current charge/battery/updates/global settings with mock mode and secondary URL fallback. Runtime build and real server proof are still gated on Java/Gradle availability. |
| Android data configuration | Mostly complete | Settings can collect Server URL, secondary URL, API Token, Basic Auth, invalid cert toggle, language, currency, tariff, mock mode, and multi-instance data. First-run currently lands on Settings, not a dedicated guided onboarding. |
| iOS app shell | Mostly complete | SwiftUI 4-tab shell and More/Settings/onboarding flow exist. Widget remains source-only/deferred. |
| iOS real data | Partially complete | Many pages use `state.real` in real mode and `state.mock` in mock mode. Native compile and simulator proof require Mac/Xcode. |
| iOS data configuration | Good first-run flow | `OnboardingView` tests `/api/ping`, `/api/readyz`, and `/api/v1/cars`, then saves URL to UserDefaults and token to Keychain. Settings can reconnect. Multi-instance UX is closer to "Add Instance" than full multi-instance management. |
| Web app | Prototype / demo | Pages and mock-backed charts exist. Store is in-memory Zustand, `onboardingDone` defaults true, Settings test is simulated, and API paths/base URL are inconsistent with native contracts. Treat Web as visual/data experiment, not completed real-data app. |
| Widget | Deferred | Android source exists and update worker is wired; iOS Widget source exists but target/entitlements/App Group wiring are not complete. Do not accept Widget as product-complete. |
| Tests/proof | Limited | Static inspection completed. Android native build requires Java/Gradle. iOS native build requires Mac/Xcode/XcodeGen/CocoaPods. |

## 3. What Is Complete

### Product/UI

- The main mobile information architecture is present: Dashboard, Drives, Charges, More, Settings, About, detail screens, and many analysis/report entries.
- The UI direction is acceptable for the current stage. Remaining UI work is polish and consistency, not a blocker for data connection.
- More is now the right system/analysis hub on native apps.
- Mock mode is visible enough in product concepts and implemented in native data paths.

### Android

- Start destination checks configuration: if a server is configured or mock mode is enabled, start at Dashboard; otherwise start at Settings.
- Settings supports primary Server URL, secondary Server URL, API Token, HTTP Basic Auth, self-signed cert toggle, currency, language, short-drive/charge display, mock mode, tariff entry, force resync, and debug tools.
- API token and Basic Auth password are stored through `EncryptedSharedPreferences`.
- Multi-instance data is stored separately through `InstanceDataStore`, with per-instance token storage and active-instance switching.
- Switching an active instance cancels sync, clears cached tables, updates legacy settings, and triggers a fresh sync/widget update.
- `TeslamateRepository` has typed real/mock paths for major endpoints and avoids silent mock fallback in real mode.

### iOS

- First-run onboarding is explicit and user friendly: URL + optional token, staged progress, connection test, and mock skip.
- Connection proof is stronger than Android Settings: it checks server reachability, readiness, and actual car access before saving.
- Token storage uses Keychain with local-device/unlocked accessibility.
- Real/mock mode is centralized in `AppState`.
- Many feature pages branch explicitly between `state.mock` and `state.real`.

## 4. What Is Not Complete

### Data Configuration Gaps

- Android needs a first-class onboarding screen. Starting at Settings works technically, but it does not guide a non-developer user through "what is TeslaMate, where do I find the URL/token, test, then save".
- Android `testConnection()` only pings the server. It should also verify readiness and fetch `/api/v1/cars`, otherwise a server can look connected while data pages still fail.
- Android save can proceed after entering a URL without a successful connection test. For first-run, Save should require a successful test or show a deliberate "save anyway" path.
- Android instance editor can save a new instance without testing it first.
- iOS does not yet expose Android-level full multi-instance management. It connects the current instance but does not show a complete active-instance list/switch/delete model equivalent to Android.
- Web does not truly configure real data: Settings and Onboarding keep local component state, do not call `setServer`, and do not persist.
- Web connection test is simulated with `setTimeout`; it does not call the configured server.
- Web API paths use `/api/cars` style paths while native code and docs use `/api/v1/cars`. This must be normalized before Web can be considered real-data-ready.
- Web silently falls back to mock data on fetch failure. That is acceptable for a demo, but product real mode must show explicit error/unavailable state.

### Feature / Platform Gaps

- Web is missing or structurally different from native for More, About, Timeline, Cost, Range, Vampire, and some entry points.
- Widget remains deferred for product acceptance, especially iOS Widget target/entitlements/App Group wiring.
- Native build proof is not available on this Windows machine.
- Real TeslaMate server end-to-end proof was not run in this pass.

## 5. How Users Configure Display Data

The user is configuring a self-hosted TeslaMate API, not a Tesla account.

Minimum configuration fields:

| Field | Required | Notes |
|---|---:|---|
| TeslaMate server URL | Yes | Use the API root host, for example `http://192.168.1.100:4000` or `https://teslamate.example.com`. Do not append `/api/v1` for native apps because code appends API paths itself. |
| API Token | Optional / deployment-dependent | Required only if the user's TeslaMate API is protected by bearer token. Store securely. |
| HTTP Basic Auth | Optional | Android supports username/password for reverse-proxy auth. iOS/Web do not yet match this fully. |
| Secondary server URL | Optional | Android supports fallback for network-level primary failures. Useful for public domain + LAN address pairing. |
| Accept invalid certificates | Optional / advanced | Android exposes this for self-signed local HTTPS. Keep collapsed and warn clearly. |
| Mock mode | Optional | For demo/offline development. Must never be confused with real data. |
| Tariff configuration | Optional but recommended | Needed for meaningful cost display. |

Native data flow:

1. User enters TeslaMate root URL and optional credentials.
2. App tests connection.
3. App fetches cars and selects `currentCarId`.
4. Dashboard requests `/api/v1/cars`, `/api/v1/cars/{id}/status`.
5. Other pages request drives, charges, battery health, updates, and derived analytics.
6. If mock mode is enabled, all major display data uses bundled mock data and should label this honestly.

## 6. Recommended User Guidance Flow

Recommended copy and flow for first-run:

1. Welcome: "Connect your TeslaMate server"
2. Explain: "MateLink connects directly to your own TeslaMate API. It does not use Tesla official login and does not send data to a third-party server."
3. Ask for URL:
   - Label: "TeslaMate server URL"
   - Placeholder: `https://teslamate.example.com` or `http://192.168.1.100:4000`
   - Helper: "Use the server root. Do not add `/api/v1`."
4. Ask for credentials:
   - API Token optional
   - Expand advanced options for Basic Auth, secondary URL, invalid certificate.
5. Test:
   - Step 1: reach `/api/ping`
   - Step 2: reach `/api/readyz` when supported
   - Step 3: fetch `/api/v1/cars`
6. Success:
   - Show detected car count and selected car name.
   - Primary action: "Enter Dashboard"
   - Secondary: "Add another instance" only after first success.
7. Failure:
   - Show reason and next action:
     - URL invalid: "Use http:// or https://"
     - Timeout: "Check network/VPN/LAN access"
     - 401: "Check API token"
     - JSON/HTML response: "Your URL may point to the web UI, not the API root"
     - Public HTTP: "Use HTTPS or a local LAN address"
8. Mock path:
   - "Use demo data" remains available, but show a persistent mock banner/chip until real mode is configured.

## 7. Next Phase Tasks

| Priority | Task | Owner area | Acceptance |
|---|---|---|---|
| P0 | Add Android first-run onboarding or convert first Settings entry into a guided setup mode | Android | Fresh install shows a clear connect flow; save requires successful test or explicit override. |
| P0 | Strengthen Android connection test to ping + readyz + cars | Android data | Test fails with actionable messages for invalid URL/token/API mismatch. |
| P0 | Normalize URL guidance across Android/iOS/Web | Cross-platform | All docs and placeholders say "root URL, no `/api/v1`" unless a platform is changed to accept both. |
| P0 | Fix Web real-data configuration | Web | Settings/Onboarding call `setServer`, persist state, use `/api/v1` endpoints, and stop silent mock fallback in real mode. |
| P1 | Align iOS multi-instance UX with Android | iOS | Show configured instances, active marker, switch/edit/delete, and per-instance token behavior. |
| P1 | Add data-source labels where charts are estimated or summary-derived | Cross-platform | User can tell real sampled data from mock/summary-derived estimates. |
| P1 | Run native build and smoke tests on proper toolchains | Android/iOS | Android Gradle build passes with JDK; iOS simulator launch passes on Mac/Xcode. |
| P2 | Widget productization | Android/iOS | Android widget verified on device; iOS target, entitlements, App Group, and timeline provider verified. |

## 8. Verification Performed In This Pass

- Read and extracted key content from the Word implementation plan.
- Read/sampled the listed PRD, PLAN, architecture, current `app_mimo/docs`, and source files.
- Inspected Android Settings, instance storage, secure storage, repository, nav start destination, and API interfaces.
- Inspected iOS AppState, Onboarding, AddInstance, Settings, and API client.
- Inspected Web store, API client, Onboarding, Settings, and API usage.
- Did not edit `E:/project/tesla_master/docs/git_ref/`.

Proof boundary:

- No native Android Gradle build was run because this Windows environment previously lacked Java/Gradle proof capability.
- No iOS build was run because iOS verification requires Mac/Xcode/XcodeGen/CocoaPods.
- No live TeslaMate server was provided, so real network behavior remains source-level verified only.

## 9. Implementation Update - 2026-07-09 Data Setup Pass

Completed in this implementation pass:

| Area | Status | Evidence |
|---|---|---|
| Android connection test | Implemented, native build gated | `TeslamateRepository.testConnection()` now validates root URL input and probes `/api/ping`, `/api/readyz` as warning-only, and `/api/v1/cars` as the decisive data check. |
| Android first-run guidance | Implemented, native build gated | Settings shows a first-run "Connect TeslaMate" guide when unconfigured and non-mock, with root URL helper text. |
| Android save/test honesty | Implemented, native build gated | Settings and instance editor now require a successful connection test or an explicit second save attempt that warns about unverified config. |
| Web persistence | Verified by build | Zustand store now uses `persist`, defaults to onboarding until a server or mock mode is chosen, and stores real-data configuration in `localStorage`. |
| Web real-data setup | Verified by build | Onboarding and Settings call the real connection tester and `setServer(url, token)` after success. |
| Web API honesty | Verified by build | API client uses `/api/v1` paths, unwraps TeslaMate `data` payloads, and throws real-mode errors instead of falling back to mock data. |
| iOS multi-instance | Source implemented, Xcode gated | `AppState` now stores instance metadata in UserDefaults, per-instance tokens in Keychain, and supports add/update/switch/delete. |

Still incomplete or gated:

| Area | Remaining work |
|---|---|
| Android proof | `JAVA_HOME`/`java` is unavailable on this Windows machine, so Gradle unit/build verification could not run here. |
| Android full onboarding polish | The first-run guide is inside Settings rather than a separate dedicated onboarding route. |
| Web full IA parity | Web remains sidebar-based and still lacks full native/Stitch IA parity. |
| iOS proof | Requires Mac/Xcode to compile and simulator-test multi-instance switching and Keychain behavior. |
| Widget | Android device-level widget validation and iOS target/entitlements/App Group/timeline verification remain P2. |
