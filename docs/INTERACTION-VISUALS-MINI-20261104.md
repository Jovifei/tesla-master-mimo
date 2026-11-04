# Mini interaction visuals — isolated source stage (2026-11-04)

Repository: `Jovifei/tesla-master-mimo` only. New branch:
`codex/miniprogram-interaction-visuals-20261104`. Base:
`55d49b921f541c5c1d793c88e64dfaac51ee32b2` (0.6.5); original branch untouched.
App is implemented independently on `codex/app-interaction-visuals-20261104`,
base `984196d8c1a6deaff124eaa8dd6c45f55c40e700`. Never overwrite one backend with the other.

## Actual consumers

- Binding and Tesla/optional WeChat account pages use received pending/action/confirmed/error states.
  Official WebView, consent, refresh/restore, buttons and authority checks remain unchanged.
  A MateLink session is not labeled as a newly confirmed Tesla grant. No timer sets success.
- Qualified per-wheel TPMS data drives the shared position diagram. Selecting a wheel opens
  and filters the existing real observation chart; another tap restores all wheels. Zero is
  preserved; unknown/old/source-less timestamps never become green normal pressure.
- Existing A/B endpoint area gains local focus interaction; real maps and route samples remain
  untouched. Schematic geometry is explicitly not a recorded route or vehicle movement.
- Parked overlays consume the same verified API response's bounded recorded-charge intervals.
  Separate sessions/zero/unfinished ends remain separate. Invalid source/window/duplicate
  identities are excluded. No new polling, fake stopped intervals or fabricated energy.
- Opening details expand from the current warning area. Aggregated doors/windows never claim
  which individual opening is open; null remains unknown.
- Empty list ornaments and the existing brand loading logo are retained locally. New pulses
  are bounded, background app listeners pause motion and are removed on unmount. CSS honors
  reduced motion when the renderer exposes it. Native Mini reduced-motion behavior remains
  a device gate, not a claim made from a web harness.

The same 240×460 source geometry is shared with Android. The neutral top-view is a position
schematic, not an exact model photograph. Existing real packaged vehicle catalogue artwork
remains authoritative. Text and values are native components, never baked into pixels.

## Preservation / tests

No session/storage keys, database schema, auth scope, API origins, production config, Owner
files or existing history are migrated/deleted. No added write, login or vehicle command.
Existing source branch's 0.6.5 lockfile updates are preserved. Test the actual components,
TPMS chart, drive detail and parked overlay positive/negative consumers, plus the full
locked typecheck/Vitest/native-resource tests/WeChat build and all four timezone suites.
No dependency upgrade, reduced test selection or historical green SHA substitutes for
terminal source gates. Artifacts and exact SHA/tree/parent are supplied by the final receipt.

## Local receive and human gates

Receive App first, Mini second in separate clean worktrees at the two immutable final SHAs.
Do not reset the original E-drive or touch dirty files. Independently verify/build; report
failure as one sanitized GitHub evidence bundle, not local business implementation. No
phone install, signing credential export, Mini upload/publish, production deployment or
history backfill is authorized by this source stage. Native WeChat visual/interaction,
real login/permission and natural Fleet acceptance remain separate from synthetic tests.
