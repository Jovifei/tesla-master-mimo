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
