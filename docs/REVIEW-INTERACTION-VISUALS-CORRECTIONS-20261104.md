# Interaction visuals — corrective review and test boundaries

This remains the separate `codex/miniprogram-interaction-visuals-20261104` source stage based on owner `55d49b921f541c5c1d793c88e64dfaac51ee32b2` / Mini 0.6.5. Existing layouts, actions, sessions, storage keys and dependency locks remain.

## Actual failures corrected, assertions retained

- Locked Taro `ButtonProps` rejects `ariaRole`; the real native Button keeps its label/click and inherent semantics, without that unsupported property.
- Three older TPMS tests mocked only Text/View/Button and searched for the sole Button. The new real diagram adds Image and selectable wheels. Their host mocks now include Image; the original evidence action is selected by its existing `tpms-evidence-toggle` class. Zero, stale, missing, pagination and pipeline assertions stay unchanged.
- The original hero-geometry test now isolates Taro lifecycle hooks like the other real component tests; 170/160 logical-pixel assertions remain.
- A pre-existing Android compatibility fixture used the identical source/time window for two intended separate charging sessions. That legitimately deduplicates on completed repository publication. Session 65 now has its own 12:00–13:00 synthetic window; the exact two IDs, count, CNY/USD separation and 2.28 amount/graph assertions remain. No history or cost implementation was changed.
- The four-wheel diagram uses separate responsive columns, not absolute overlays that can collide with the car at narrow widths/large fonts. Original metric cards and trend data are untouched.

## Actual consumer rendering

`tools/interaction-stage/browser/` bundles the real React visual components using the existing locked test tooling. Its test-only DOM adapter is not imported by the WeChat production bundle. It prohibits storage/provider access and renders only public artwork plus synthetic fixture values. The isolated Chromium runner tests real clicks, zero/missing/stale, scope reset, openings, recorded intervals, foreground/reduced motion, and 320/412 widths in both themes, including 1.8x wheel text. It records 46 PNG hashes plus exact HEAD/staged tree. This is browser component evidence, **not native WeChat or phone acceptance**.

Both the complete native-resource/Vitest suites and strict types/production WeChat build must pass before source publication. Final read-only workflows must verify the terminal fixed SHA, not substitute a prior passing candidate. Native WeChat DevTools, real Tesla consent/optional WeChat binding, natural Fleet data and any signed installation remain separate gates. No Owner workspace, phone, production or original development branch is altered.
