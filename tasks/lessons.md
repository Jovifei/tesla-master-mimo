# Lessons

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
