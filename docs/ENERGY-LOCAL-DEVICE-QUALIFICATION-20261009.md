# Energy stage local qualification — 2026-10-09

## Fixed product
- Source: `80c891825da6cd6592df075e86788cdb81b449af`; tree `0971e7ef0580cf2219c2e8e0625a0d51530b09ac`.
- CI: https://github.com/Jovifei/tesla-master-mimo/actions/runs/37820112901 — all fixed-SHA jobs successful.
- Actual local Debug714: 0fail/0skip; Release714: 0fail/8skip; lintDebug0error/259warning/9info; lintRelease0error/242warning/8info. Signed R8 build exit0, BUILD SUCCESSFUL.
- Go/PG16 295/295 and Linux full race/vet/modverify/build passed on84abb526. Go subtree diff84→80 is empty; reused identical-source evidence, not a new80 Go execution claim.
- APK2.1.27/build46, nondebuggable, same original signing certificate; SHA256 `A3E3DE81F38C3CA7E2FFFD7734F64F426B0821E8213D5D1A27F8DF280885E7D5`.

## Actual physical-device install
- Independently pulled live original APK immediately before installation and verified same certificate. Generic safe install helper could not read device cert and stopped without installing; independently verified fallback used only `adb install -r`, exit0/Success.
- Actual installed46/2.1.27; original firstInstallTime and data directory unchanged. No uninstall, clear data, auth bypass, vehicle wake or fabricated trip.
- Declared MainActivity launched. Bounded10-second sample: alive, FATAL0/ANR0. Existing logged-in app and cached history visible.

## CHANGES_REQUIRED: authenticated device history qualification
Source observation time (not latest live indefinitely): 2026-10-08T18:20:17–18:23:20Z / 2026-10-09 02:20–02:23 China.
- Actual Release phone `HistorySync`: `stage=history_context http=none category=tls`, repeated four requests. No successful authenticated history response evidenced.
- Drive page explicitly says cloud history sync failed and shows preserved local records. Declared latest-drive detail has unavailable energy/SOC fields; no false real-data PASS.
- Phone Android11, clock matches UTC, validated network; no VPN transport or captive portal observed. Network configuration left unchanged.
- Server-side read-only public health/ready200; API running, OOMfalse/restart0. These do not close authenticated history failure.
- Authorized ordinary Nginx access-log read denied (exit1); no privilege bypass. No client identity attribution or raw request logs emitted.
- Public certificate validity Aug28→Nov26 2026. Server chain leaf→YR1→cross-signed RootYR→RootX1. Desktop verified TLS1.3/hostname. Existing release network-security-config unchanged from14ca59d. These observations do not yet prove the phone TLS root cause.

## Requested remote final-stage repair/review
Review complete fixed source, client safe TLS classification/transport, real APK configuration and production compatibility. Diagnose without trusting all certificates, user CA injection, HTTP fallback, identity cache bypass, DNS/proxy/VPN changes or guessed OOM. If current diagnostics are insufficient, implement minimal privacy-safe cause discrimination and focused tests, then full fixed-SHA CI and one complete handoff. Keep product/source and environment failures separate. Main merge blocked until real authenticated history works or a concrete independent human/environment blocker is confirmed.

## Cleanup and independent gates
13 obsolete build-cache/failed-log items totaling1,784,862,693bytes moved to a recoverable local archive. Not physical disk deletion; source, effective regression tests, schemas/migrations, APK reports and rollback preserved. Owner dirty remains untouched.
Natural Fleet/notification events, second real user and unavailable historical parking energy remain separate pending gates. No production schema/data/bridge deployment in this stage.
