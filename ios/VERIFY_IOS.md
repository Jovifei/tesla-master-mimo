# iOS Verification — Apple Redesign (2026-08)

## Source Tree Structure

```
MateLink/
├── App/                    # App entry, ContentView, AppState
├── Core/
│   ├── API/                # TeslaMateAPI, MockAPI, APICache
│   ├── Map/                # AmapView (MapKit), DriveRouteMap, GCJ02Converter
│   ├── Models/             # Car, CarStatus, Charge, Drive, ChargePoint, DrivePosition, BatteryHealth
│   ├── Theme/              # MateTheme, CarColorPalette, MateAnimation, AppTheme (legacy compat)
│   ├── UI/                 # EmptyStateView, StatCard
│   └── Utils/              # LTTBDownsample, Localization, RouteSimplifier
├── Features/
│   ├── Navigation/         # AppRoute (Route enum), RouteDestinationView
│   ├── Dashboard/          # DashboardView (rewritten)
│   ├── Drives/             # DriveListView, DriveDetailView
│   ├── Charges/            # ChargeListView, ChargeDetailView, CurrentChargeView
│   ├── Battery/            # BatteryHealthView
│   ├── Settings/           # SettingsView, AddInstanceView, TariffConfigView
│   ├── Onboarding/         # OnboardingView
│   ├── More/               # MoreView
│   └── [legacy views]      # StatisticsView, EfficiencyView, CostView, etc.
├── Resources/              # en/zh-Hans/ja Localizable.strings, mock_data.json, fonts
└── Widget/                 # MateLinkWidget (deferred — not wired)
```

## Windows Checks

1. Confirm `project.yml` defines the `MateLink` application target with source roots: `MateLink/App`, `MateLink/Core`, `MateLink/Features`
2. Confirm `Podfile` exists with AMap pods (MapKit fallback is active)
3. Confirm `MateLink/Info.plist` contains:
   - `NSAppTransportSecurity` with `NSAllowsLocalNetworking`
   - `NSLocalNetworkUsageDescription`
4. Confirm localization resources exist under `MateLink/Resources/*.lproj/Localizable.strings` (en, zh-Hans, ja)
5. Confirm fonts exist: `MateLink/Resources/Inter-*.ttf` and `MateLink/Resources/JetBrainsMono-*.ttf`
6. Confirm widget status is still deferred

## Mac Simulator Verification

```bash
cd app_mimo/ios
brew install xcodegen cocoapods
xcodegen generate
pod install
xcodebuild -workspace MateLink.xcworkspace -scheme MateLink -destination 'platform=iOS Simulator,name=iOS 17' build
```

**Expected results:**
- Build exits successfully (no compile errors)
- App launches into `OnboardingView` (if no server configured) or `ContentView` (if mock data available)
- 4-tab navigation works: Dashboard → Drives → Charges → More
- Dashboard shows mock vehicle status (battery %, range, temperature, tire pressure)
- Dark mode toggle in Settings works
- CarColorPalette changes accent color when switching vehicles (if multiple cars in mock data)

## Key Design System Files

| File | Purpose |
|------|---------|
| `Core/Theme/MateTheme.swift` | Semantic colors, typography (Inter/JetBrainsMono), SF Symbols map |
| `Core/Theme/CarColorPalette.swift` | 9 Tesla paint colors → light/dark palettes with HSL harmonization |
| `Core/Theme/MateAnimation.swift` | Spring animation specs (default/momentum/snappy/gentle) |
| `Features/Navigation/AppRoute.swift` | Type-safe Route enum (mirrors Android Screen sealed interface) |
| `Features/Navigation/AppRouter.swift` | RouteDestinationView — centralized route resolver with async detail loaders |

## Mock Mode

The app works fully in mock mode without any server:
- `AppState.isMockMode = true` (default on first launch)
- `MockAPI` loads `mock_data.json` from the app bundle
- Dashboard, Drives, Charges, Battery Health all show sample data
- All navigation routes work

## Connected iPhone Verification

1. Connect the iPhone by USB.
2. Trust the computer on the iPhone.
3. Run `xcodegen generate` and `pod install` if they have not been run yet.
4. Open `MateLink.xcworkspace`.
5. Set Team and Bundle Identifier under Signing and Capabilities.
6. Select the connected iPhone.
7. Run the `MateLink` scheme.

## API Integration (Phase 2)

When the user provides API credentials:
1. Open Settings → Network → enter server URL and API token
2. Tap "Test Connection"
3. App switches from mock mode to live data
4. Dashboard shows real vehicle status (polls every 5 seconds)
5. Drives/Charges lists fetch from the paginated API

## Widget Verification Status

Status: `deferred / source exists but target not wired`

Do not attempt widget runtime verification until a later change adds:
- A widget extension target in `project.yml`
- Matching entitlements
- Proven App Group wiring for shared defaults
