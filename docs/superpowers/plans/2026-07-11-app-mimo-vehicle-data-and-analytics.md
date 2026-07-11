# Vehicle Data and Analytics Implementation Plan

> **For agentic workers:** Use subagent-driven development task-by-task. Do not stage or commit without Jovi's explicit approval.

**Goal:** Deliver a truthful Android dashboard and analytics flow backed by TeslaMateApi plus a self-hosted MateLink Adapter.

**Architecture:** Fix local summary/detail sync first, then place a Go adapter in front of TeslaMateApi on port 8080. Android detects adapter capabilities and falls back cleanly when it is absent.

**Tech Stack:** Kotlin, Compose, Room, WorkManager, Retrofit, Go 1.22, PostgreSQL, MQTT, Docker Compose.

## Ordered Tasks

1. Add test dependencies and failing unit tests for pagination guards, aggregate persistence, weighted energy, timeline classification, cost precedence, short-history windows, and partial battery health.
2. Fix Android sync and calculation primitives; write detail aggregates, prevent repeated-last-page loops, and make summary progress truthful.
3. Add the Go adapter, its database migrations, MQTT snapshot persistence, legacy API proxy, timeline, override, geocode, and inferred sentry endpoints.
4. Integrate adapter capability detection into Android repositories and replace dashboard partial-data UI with the compact status-first layout.
5. Replace drive history with adapter timeline; use fixed 24-hour time, parked metrics, Chinese address fallback, and short-relocation merging.
6. Add charge override UI and shared effective-cost resolver; use it in charge history, details, statistics, and cost analysis.
7. Repair Statistics, Battery, Efficiency, Range, and Vampire screens to use correct source-aware values and adaptive 7/30-day rules.
8. Run unit, integration, Docker, Gradle, and device smoke verification; capture final screenshots and report candidate Git files only.
