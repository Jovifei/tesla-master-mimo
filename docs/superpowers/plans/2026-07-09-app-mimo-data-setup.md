# app_mimo Data Setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make MateLink guide users through real TeslaMate data setup and clearly distinguish real, mock, and degraded display data.

**Architecture:** Prioritize Android and Web P0 work, because Android is the baseline native app and Web currently has the largest real-data gap. Keep TeslaMate root URL as the shared input contract and let each client append `/api/...` paths. Preserve existing mock/real boundaries and document any proof that requires native toolchains.

**Tech Stack:** Android Kotlin/Compose/Hilt/Retrofit/DataStore, Web React/Vite/Zustand, iOS SwiftUI/AppState, Markdown docs.

---

## Tasks

- [ ] Android: strengthen connection testing to ping, optional readyz, and cars.
- [ ] Android: make first-run setup safer and clearer than ordinary Settings.
- [ ] Android: add instance editor test/override behavior.
- [ ] Web: persist configuration, normalize `/api/v1` paths, and stop silent mock fallback in real mode.
- [ ] Cross-platform/docs: normalize root-URL guidance and record remaining completion boundaries.
- [ ] iOS: implement or explicitly defer multi-instance parity based on current source complexity.
- [ ] Verification: run available tests/checks and record proof limits.
