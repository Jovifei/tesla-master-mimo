# Tesla MateLink MIMO

`app_mimo/` contains the current MateLink implementation family for Android, iOS, and web.

## Repository Layout

```text
app_mimo/
|- android/        Android app
|- ios/            Native SwiftUI app plus XcodeGen/CocoaPods entry
|- shared/         Shared API/model definitions
`- web_matelink/   Web client
```

## Current Platform Snapshot

| Platform | Current state |
| --- | --- |
| Android | Broadest implementation surface. Native app shell, features, and Android widget sources exist. |
| iOS | Native SwiftUI sources exist and the project is prepared for Mac-side generation through `project.yml` + CocoaPods, but native build proof is still Mac-only. |
| Web | Separate web client exists under `web_matelink/`. |

## iOS Build Entry

The current iOS build path is:

```bash
cd app_mimo/ios
brew install xcodegen cocoapods
xcodegen generate
pod install
open MateLink.xcworkspace
```

Use the generated `.xcworkspace`, not a bare `.xcodeproj`, after CocoaPods integration.

## iOS Windows-Prep Status

The repo already contains the Windows-prep inputs needed before Mac verification:

- `app_mimo/ios/project.yml` defines the app target source roots.
- `app_mimo/ios/Podfile` defines the CocoaPods dependencies expected after project generation.
- `app_mimo/ios/MateLink/Info.plist` already includes App Transport Security local-networking allowance plus `NSLocalNetworkUsageDescription`.

This is enough for source review and project-generation prep on Windows, but not enough to claim a successful iOS build. Mac plus Xcode are still required for `xcodegen`, `pod install`, simulator build, and signing checks.

## Widget Status

iOS Widget support is not considered wired in this round.

Status: `deferred / source exists but target not wired`

Current evidence:

- `app_mimo/ios/MateLink/Widget/MateLinkWidget.swift` exists.
- `app_mimo/ios/project.yml` currently defines only the `MateLink` application target.
- No widget `.entitlements` file is present under `app_mimo/ios/`.
- No App Group wiring proof is present in project metadata, even though source files reference `group.com.matelink`.

Treat the widget code as source inventory only until a later pass adds a real target, entitlements, and App Group proof.
