# MateLink P0 - UI and analytics foundation

## Status

`PARTIAL - awaiting visual direction`. P0 implements a Debug-only review surface and the first truthful empty-data corrections. It does not implement the four-tab production navigation or replace the vehicle analytics algorithms.

## Debug design review

The review Activity exists only under `android/app/src/debug` and is installed only in the isolated `com.matelink.test.mock` Debug package. It is not part of the formal `com.matelink` Release artifact.

It provides the same six information layouts in two directions:

- `PRECISION_TELEMETRY`: cyan accent, compact telemetry hierarchy, and clearly separated numeric cards.
- `PURE_MINIMAL`: neutral black/white/gray palette, larger primary number, and reduced visual density.

Both directions include light and dark variants plus collecting and unavailable examples. The production direction remains undecided until Jovi selects one of them.

## Implemented foundation

- `MetricState` carries evidence, source, time, sample count, coverage, confidence, collecting, unavailable, and failure states.
- `MetricStatusPanel` provides consistent loading, collecting, empty, unavailable, and error presentation.
- An empty successful history result now exits `EfficiencyViewModel` loading instead of keeping the efficiency page spinning indefinitely.
- Efficiency, range, vampire drain, cost, and mileage screens avoid showing missing history as zero-valued analysis. Affected cards read theme colors instead of direct white backgrounds.

## Explicitly deferred

- The production four-tab navigation, deep-link remapping, and complete screen migration are P1.
- Weighted efficiency, capacity evidence, CNY default migration, charge-cost resolver consolidation, personalized range, standby windows, and recommendations are P3.
- Mock and design-review data are local development evidence only; they are not Tesla OAuth or real-vehicle evidence.

## Verification

- `:app:assembleDebug --rerun-tasks`, `:app:testDebugUnitTest` (162 tests, 0 failures, 0 errors), and `:app:assembleRelease`: PASS.
- `:app:lintRelease`: PASS with 0 errors and 251 existing warnings; no `MissingTranslation` finding.
- Release merged manifest and APK contain no `DesignReviewActivity` or Debug review class.
- `emulator-5554`: Debug review Activity launched; both themes, dark mode, collecting, and unavailable states were checked interactively.
- No instrumentation test, physical-device operation, staging, commit, or push was performed.
