# History and Analytics Reliability Design

## Goal

Make driving, charging, efficiency, range, standby, and annual-report screens
honest, complete, and mutually consistent. The app must never present a
partial-history query as all history, nor present missing data as zero.

## Confirmed Product Decisions

- More > **Driving history** shows every eligible drive, defaulting to all
  history. Date and distance filters only change presentation.
- The existing detector-backed feature is renamed **Long-distance trips**. It
  remains separate from individual driving history.
- Remove **3D vehicle preview** and **Current charge** from More. No existing
  data is deleted.
- A charging session accepts a local, manual **total amount** in Chinese yuan
  (`¥`), not a unit price. A valid manual amount takes precedence over the
  TeslaMate cost and can be cleared to restore the source cost.
- Keep five independent pages: Efficiency, Cost, Range, Standby, and Annual
  report. They use the same complete-history source and compatible time ranges.
- Efficiency comparisons use same-model, season/temperature-comparable public
  aggregate samples. Consumption order is low to high: `0%` is the lowest
  observed consumption and `100%` is the highest. `P42` means 42 percent of
  comparable samples use no more energy than the user. Lower consumption is
  better.
- Standby causes are named only when historical evidence records the cause.
  Otherwise the result is **detected standby consumption; cause unknown**.
- Annual reports always offer the current calendar year and the preceding year;
  each begins at January 1. Empty years show no-data state, never zero metrics.

## Data Model and Flow

`AnalysisHistoryRepository` owns a normalized history snapshot per car:

1. Read complete, paginated drive and charge history from the configured
   self-hosted service; de-duplicate by source ID.
2. Reconcile locally persisted summaries without discarding either source.
3. Expose records, source freshness, coverage, and no-data reasons to all six
   consumers.
4. Cache only data already owned by the app. Network failure serves explicitly
   labeled cached data when available.

The repository is the only location where time windows, eligibility, energy
source, and duplicate rules are defined. UI view models request named slices
from it rather than fetch and filter independently.

Manual charge totals are a local record keyed by car and charge ID, with a
source state (`manual`, `TeslaMate`, `free`, `unavailable`). Amounts must be
finite and non-negative. Currency is fixed to `¥` for this feature.

Public benchmark data is anonymous aggregate data only. It contains source
version, citation, vehicle family/model, environmental bucket, sample count,
and percentile boundaries. User history is never uploaded for comparison.

## Screen Contracts

### Driving history and long-distance trips

Driving history is an all-history individual-drive list. The More entry opens
this list. Long-distance trips remains a separate, clearly named aggregation
with its existing manual-edit behavior.

### Charge detail and cost analysis

Charge detail exposes **Modify total amount** and a `¥` amount field. It shows
the selected source. Cost analysis contains total cost, total energy, average
per session, average per kWh, monthly AC/DC series, and locations. It labels
manual totals instead of treating them as upstream facts.

### Efficiency

The page contains: all-history average; three-month, summer, winter, and
custom windows; trend; speed buckets; per-drive expansion; personal-history
percentile; and public comparable percentile. Each percentile shows boundaries
and sample count. Missing public samples keep personal analysis available and
show the reason.

### Range

Range is distinct from mileage. It compares rated-range loss with actual drive
distance, shows prediction error and accuracy, and summarizes effects by
season and speed when coverage permits.

### Standby

The page calculates every eligible parked interval from available history and
shows energy loss, average power, duration, location where available,
confidence, and only evidence-backed cause labels. It includes total kWh,
average W, interval count, and daily trend. No interval means a no-data state.

### Annual report

The year selector contains the current year and prior year even when local
summaries are incomplete. Report metrics derive from the normalized history:
drives, distance, energy, efficiency, charges, costs, standby, and monthly
comparisons. A selected empty year explicitly says no records were found.

## Error Handling and Privacy

- Zero is a valid metric only when records support it; otherwise display a
  specific no-data or insufficient-coverage state.
- Remote errors preserve cached data with source/freshness labels.
- Parse and pagination errors never silently truncate history.
- Do not read, log, transmit, or display stored Docker credentials, map keys,
  vehicle IDs, VINs, precise routes, or personal addresses for this work.

## Verification

- JVM contracts cover pagination/deduplication, interval boundaries, manual
  total precedence, percentile direction, seasonal windows, standby attribution,
  and current/prior annual-year availability.
- Run all JVM tests, Debug APK build, and Android-test APK build.
- Install with `adb install -r` only; do not uninstall or clear application
  data. Confirm version, first-install continuity, main navigation, all six
  screens, and fatal/ANR/database/format logs on the physical device.

## Scope Boundaries

This specification does not add user tracking, cloud upload, a new account,
or device instrumentation. It does not modify self-hosted Docker deployment
unless a later, separate investigation proves pagination support is absent.
