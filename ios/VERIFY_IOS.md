# iOS Verification

## Windows Checks

On Windows, verify only the source and project-generation inputs:

1. Confirm `project.yml` defines the `MateLink` application target and the `MateLink/App`, `MateLink/Core`, and `MateLink/Features` source roots.
2. Confirm `Podfile` exists and is intended to run after XcodeGen generates `MateLink.xcodeproj`.
3. Confirm `MateLink/Info.plist` contains:
   - `NSAppTransportSecurity` with `NSAllowsLocalNetworking`
   - `NSLocalNetworkUsageDescription`
4. Confirm localization resources exist under `MateLink/Resources/*.lproj/Localizable.strings`.
5. Confirm widget status is still deferred:
   - widget source exists
   - no widget target is declared in `project.yml`
   - no `.entitlements` file exists under `app_mimo/ios`

This Windows pass is preparation only. It does not prove a build.

## Mac Simulator Verification

```bash
cd app_mimo/ios
brew install xcodegen cocoapods
xcodegen generate
pod install
xcodebuild -workspace MateLink.xcworkspace -scheme MateLink -destination 'platform=iOS Simulator,name=iPhone 15' build
```

Acceptance for the first native proof:

- build exits successfully, and
- the app reaches `OnboardingView` or the main tab shell

## Connected iPhone Verification

1. Connect the iPhone by USB.
2. Trust the computer on the iPhone.
3. Run `xcodegen generate` and `pod install` if they have not been run yet.
4. Open `MateLink.xcworkspace`.
5. Set Team and Bundle Identifier under Signing and Capabilities.
6. Select the connected iPhone.
7. Run the `MateLink` scheme.

## Widget Verification Status

Widget verification is out of scope for this round.

Status: `deferred / source exists but target not wired`

Do not attempt widget runtime verification until a later change adds:

- a widget extension target in `project.yml`
- matching entitlements
- proven App Group wiring for shared defaults
