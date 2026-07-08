# app_mimo iOS Source Inventory

## Generated App Target Roots

`project.yml` currently generates one application target, `MateLink`, from these roots:

- `MateLink/App`
- `MateLink/Core`
- `MateLink/Features`
- `MateLink/Resources`

## App Entry and Shell

- `MateLink/App/MateLinkApp.swift`
- `MateLink/App/ContentView.swift`
- `MateLink/App/AppState.swift`

## Core Support Used By The Current iOS Shell

- `MateLink/Core/API/ApiClient.swift`
- `MateLink/Core/Map/AmapView.swift`
- `MateLink/Core/Map/MapUtils.swift`
- `MateLink/Core/Models/Car.swift`
- `MateLink/Core/Models/CarStatus.swift`
- `MateLink/Core/Theme/AppTheme.swift`
- `MateLink/Core/Utils/GCJ02Converter.swift`
- `MateLink/Core/Utils/Localization.swift`
- `MateLink/Core/Utils/RouteSimplifier.swift`

## Feature Files On The Current Verification Path

- `MateLink/Features/About/AboutView.swift`
- `MateLink/Features/Battery/BatteryHealthView.swift`
- `MateLink/Features/Charges/ChargeDetailView.swift`
- `MateLink/Features/Charges/ChargeListView.swift`
- `MateLink/Features/Charges/CurrentChargeView.swift`
- `MateLink/Features/Dashboard/DashboardView.swift`
- `MateLink/Features/Drives/DriveListView.swift`
- `MateLink/Features/Mileage/MileageView.swift`
- `MateLink/Features/More/MoreView.swift`
- `MateLink/Features/Onboarding/OnboardingView.swift`
- `MateLink/Features/Sentry/SentryHistoryView.swift`
- `MateLink/Features/Settings/SettingsView.swift`
- `MateLink/Features/Statistics/StatisticsView.swift`
- `MateLink/Features/Timeline/TimelineView.swift`
- `MateLink/Features/Updates/UpdatesView.swift`

These are not the only feature files in the tree; they are the ones most directly tied to the current shell, onboarding, and review-remediation path.

## Project-Generation Inputs

- `project.yml` defines the app target and bundle metadata.
- `Podfile` expects the generated `MateLink.xcodeproj` and installs the AMap pods.
- `MateLink/Info.plist` already carries the Windows-prep ATS and local-networking settings.

## Localization Inputs

- `MateLink/Resources/en.lproj/Localizable.strings`
- `MateLink/Resources/zh-Hans.lproj/Localizable.strings`
- `MateLink/Resources/ja.lproj/Localizable.strings`
- `MateLink/Resources/de.lproj/Localizable.strings`
- `MateLink/Resources/fr.lproj/Localizable.strings`

The current package 3 pass wires the tab labels plus the most obvious `Settings` and `More` entry copy to these existing resources. It does not claim full iOS string coverage.

## Widget Sources

- `MateLink/Widget/MateLinkWidget.swift` exists in the source tree.
- `MateLink/Features/Dashboard/DashboardView.swift` writes widget-shaped values to `UserDefaults(suiteName: "group.com.matelink")`.

Status: `deferred / source exists but target not wired`

Current repository evidence is still missing:

- a widget target in `project.yml`
- any `.entitlements` file under `app_mimo/ios`
- project-level App Group wiring proof

Because of those gaps, widget code should be treated as source inventory only, not as a verified supported target.
