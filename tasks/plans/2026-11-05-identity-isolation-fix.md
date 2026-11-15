# App identity isolation fix - 2026-11-05

## Scope

- Remove the Mileage selection path that allowed an old projection to remain usable while the identity proof was invalid or being reactivated.
- Clear Mileage projections immediately when a scope change invalidates the active identity or proof.
- Do not reuse an old Dashboard `CarData` object after a failed vehicle discovery request.
- Add regression coverage for account changes, vehicle binding changes, proof revocation, transient network failure, and scoped Dashboard receipts.

## Verification

- Focused Debug tests: `MileageRoomFirstViewModelTest`, `MileageRoomFirstRegressionTest`, and `DashboardOfflineReceiptTest` pass.
- Full Debug and Release unit tests pass.
- `lintDebug` and `lintRelease` pass.
- Release guard was exercised with explicit production API/AuthHost/PublicInfo properties.

## Remaining gates

- Commit and push the isolated fix branch.
- Build and sign the Release APK, install with `adb install -r`, and verify the existing login/data timestamp.
- Merge the verified commit into PR #27's App branch and wait for GitHub CI.
