# MateLink iOS

This directory contains the native SwiftUI implementation for `app_mimo`.

## Verified Project Entry

The checked-in iOS source tree is driven by XcodeGen plus CocoaPods:

```bash
cd app_mimo/ios
brew install xcodegen cocoapods
xcodegen generate
pod install
open MateLink.xcworkspace
```

`project.yml` is the source of truth for target generation. After `pod install`, open `MateLink.xcworkspace`.

## Windows-Prep Scope

Windows can verify source presence and project metadata, but it cannot prove a native iOS build. The current Windows-prep evidence includes:

- `project.yml` app target wiring
- `Podfile` dependency wiring
- `Info.plist` ATS local-networking allowance
- `NSLocalNetworkUsageDescription` for LAN access

Mac plus Xcode are still required for simulator build proof, device signing, and CocoaPods workspace validation.

## Minimum Launch Goal

The first acceptable Mac-side proof is a successful app launch into either:

- `OnboardingView`, or
- the main 4-tab shell (`Dashboard`, `Drives`, `Charges`, `More`)

Mock mode is acceptable for the first launch proof.

## Widget Status

Status: `deferred / source exists but target not wired`

Why it is still deferred:

- widget source exists at `MateLink/Widget/MateLinkWidget.swift`
- `project.yml` has no widget extension target
- no widget entitlements file exists in this directory
- no App Group wiring proof is present for the `group.com.matelink` suite used in source

Do not describe Widget support as active until those project-level pieces are added and verified.
