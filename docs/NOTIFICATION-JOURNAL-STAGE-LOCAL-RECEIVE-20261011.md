# Original PR17 full-stage continuation — scoped completion journal and final local receive

This supplements (not replaces) `ENERGY-TRANSPORT-COMPATIBILITY-FINAL-STAGE-20261009.md`, `TLS-ENERGY-FIXED-STAGE-LOCAL-RECEIVE-20261009.md` and the unchanged original mandate restored at `REMOTE-FULL-DATA-STAGE-MANDATE-20261007.md` (original Git blob d567d1b358826c1392a53d7541175a0e1600cc5d, from f61b484fdc574e8320dea59664aef623500caefe).

Repository ONLY Jovifei/tesla-master-mimo; original branch codex/reliable-data-stage-20261007 / original Draft PR17 / original ChatGPT and local Codex threads. No new task, main merge, installation, production change or network experiment is authorized by this document. Use the final completed PR17 comment's **full SHA/tree/parent and same-SHA actual Actions jobs**, never this document's parent or a moving branch. The remote implementer owns repairs and CI; local Codex independently qualifies, builds/signs/installs within the existing gate and returns evidence, not business-code edits.

## Repair review and implemented source

Prior 48f284e32711ec4a7b39097196c1d12658311d45 was source/CI qualified for energy/detail receipt persistence. Follow-up dd1a3468d7fdda201041aaca851d637da3a0d0ec and 7e1d5f00e65925b62e19aab49d7984582f20f1c1 added notification-source guards. They are not final delivery. This continuation repairs actual active paths:

1. The prior seen-set compaction could remove acknowledged IDs after a stale pre-cutoff response, then replay them on correction. Seen acknowledgements are now append-only. Initial pre-cutoff archive IDs need not be added; an existing acknowledgement is never erased on the basis of later mutable history. The old compaction assertion was replaced with a strictly stronger stale-response -> correction -> no-replay test, plus actual JSON restart coverage.
2. Invalid, conflicting, future and sub-millisecond-future endpoints neither notify nor poison an ID. Corrected/late lower IDs can subsequently notify once. Duplicate IDs with different source/start/end receipts are rejected for publication, not guessed or merged into a natural event.
3. New `completion_v2_*` keys bind account, connection source, effective API origin, server, local and remote car namespace using a length-prefixed SHA-256 key. Existing unscoped preferences are **retained unchanged**, not reinterpreted as verified events or automatically migrated. First complete sync in each new namespace is silent, including an empty baseline. There is no Room schema migration, no history deletion/backfill and no global preference reset.
4. Actual DataStore transactions use `CompletionJournalCodec` and generated Moshi adapters, shared with compiled round-trip regressions. Stored event identity includes kind/source/start/end/scope, not just numeric ID. Unsupported/corrupt journal fails closed without resetting its baseline or overwriting the old value. Old keys stay available for explicit future review; a old unscoped pending notification is not silently promoted to new-origin Fleet proof.
5. `SyncRepository` AND the foreground `CompletedChargePromptViewModel` use the same receipt/scope/Room-read qualification before display/open or acknowledgement. Mode/account/origin changes cancel or suppress obsolete results. Android system and app acknowledgements remain independent. Permission denial does not acknowledge; a later successful scoped sync can resolve a pending event from that car's persistent Room row even when no new API rows arrive.
6. A verified completed charge with the backend's explicit incomplete-energy reason may notify while kWh remains unknown. An import, unverified source, unfinished session or guessed energy does not become a natural Fleet completion. Drive energy, SOC, zero/negative energy and immutable raw bytes are not changed by this notification patch.
7. Repeated/overlapping pages, invalid IDs and a 1000-page budget stop are **incomplete**, never successful notification baselines. Successfully persisted pages/history are retained. The shared page guard is used by both charge and drive. There is no eviction, provider wake or fake empty response. More than 49,950 rows plus an end page may require a separately reviewed cursor/budget solution; the current fail-closed cap is explicit, not a full-history success claim.
8. New system trip taps open the app's scoped inbox, like charge notifications; they do not carry a numeric detail ID across account/origin switches. The foreground qualified prompt offers the detail. System text is generic/private and contains no route, energy, address, identity or token. Old legacy deep-link helpers remain for existing compatibility tests, not new runtime delivery.

Exactly-once external notification display cannot be made atomic with Android NotificationManager and DataStore. Stable IDs plus onlyAlertOnce reduce the crash-between-post-and-ack window; persistent acknowledgement prevents replay after a successful saved acknowledgement. Do not claim a crash-proof exactly-once delivery guarantee or actual human-visible delivery from an accepted `notify()` call.

## Full mandate completion comparison and remaining independent gates

| Mandate | Cumulative wired source / isolated test candidate | Separate real gate |
|---|---|---|
| Charging, parking, detail/list/cache/load/cancellation | Existing source-qualified energy/SOC/parking plus this receipt-gated completion and failed-page semantics | Authenticated fresh API -> Room -> displayed list/detail; no cached health substitute |
| TPMS observed time/unit/source, 30-day trends, change alerts | Existing provenance, dedup, UI and worker candidates retained; legacy unverified records excluded, not deleted | Genuine 30-day/natural increment and meaningful alert; 7113 historical writes NOT authorized |
| Natural drive/charge notifications | Scoped durable journal, first import/empty baseline/late ID, two consumers, permission retry, source/window/identity and generic system tap wired | Actual authorized natural event, denied/restored notification permission and background/device behavior |
| Truthful energy/SOC/speed/temp/cost | Original immutable raw receipt and current qualified presentation remain separate; original byte-identical d119 Java replay remains mandatory | Real source/provider and phone measurements; unknown is not inferred energy |
| Fleet multi-user, resources/recovery/release | Existing Go/PG16/race/TLS/Web/source audits and bounded paging, journal restart and two-scope tests | Two real user consents/vehicles; provider first events; long-running resource and bridge acceptance; no TeslaMate archive substitution |

This is source and isolated-test completion only. No installation, live API, Nginx, DB, bridge, historical write, trust store, VPN/proxy/DNS/TTL or vehicle action occurs remotely. Production API remains last reported bb09fac04d11796ce676555dad094776cd1ef0ce; installed phone remains last independently reported 8625d28ecfaec920d39d8b6a56afa50e9854ae7a / Build46. These are inherited device/production receipts, not a new runtime observation. Main is not merged; PR17 stays Draft.

## Original local Codex receiving prompt — one consolidated packet

Read final PR17 completed comment; check out **exact full HEAD** in a fresh clean independent worktree of this repository. Do not reset, stash, clean or edit Owner dirty main. Record `git rev-parse HEAD 'HEAD^{tree}' HEAD^` and a clean diff; verify branch and real CI head match. Compare API source with deployed bb09 and phone with installed8625; new notification edits do not replace either baseline with the other's tree.

Independently inspect actual Android job (not just android-summary), both variant TEST XML, original `DisplayProjectionReplayTest` and `IndependentD119DisplayReplayTest`, `CompletionStateTest`, `CompletionPublicationPolicyTest`, `CompletionJournalFlowTest`, `CompletionRuntimeWiringTest`, existing raw/presentation/charge/parking/source-zero/negative/unknown regressions. Build Debug + Release units, lint both, assemble and R8 on JDK17/SDK35 with existing local signing configuration. Compile/run the unchanged original d119 Java fixture independently against final classes. Run Go tests only with disposable PostgreSQL16, race/resource/vet/build and Web/TLS tests following the prior receive matrix. No connected instrumentation on Owner's phone; it can uninstall/reset data. Inspect fake-only journals, never export actual account/event data.

Required additional behavior matrix: fresh populated baseline silent; fresh empty baseline then natural completion; repeated/failed page not complete; lower late ID; duplicate conflicts; malformed/future/sub-millisecond end corrected; JSON serialize/reload; app acknowledgement while system denied; scope switch during suspended Room read; permission recovery after restart with no new API rows; wrong account/origin/car/source/window refused; stale-before-cutoff then corrected receipt never replays. Verify both system and foreground guard call sites. Old unscoped preference bytes must remain; no automatic trusted upgrade. Resource regression: 20k pre-cutoff fake history yields a small baseline JSON, while existing acknowledgements are retained. Natural post-baseline seen IDs intentionally remain durable rather than pruned with an unsafe TTL.

After fixed-SHA full CI, independent build/safety/signer checks PASS, use the existing authorized same-signer **in-place adb install -r** path only. Preserve prior private signed APK, backups, firstInstallTime, login/session, settings, complete history and cache. Confirm nondebuggable Release, exact source/package/signer, retained data and bounded launch FATAL/ANR. No uninstall, clear, connected test, production test DB or network/trust change. Report failed checks to remote for implementation; do not edit business code locally.

Then record only legitimate already-authorized normal history reads / naturally occurring Fleet drive and charge / TPMS samples, with UTC and China timestamps and source tier. Fresh authenticated history, actual background notification appearance and real in-app scope changes are independent gates. No manufactured trip/charge/wake. Archive evidence is labeled archive, never Fleet first event. Report typed safe TLS categories without raw subjects/SAN/URLs/tokens/VIN/routes. The unanswered phone Wi-Fi -> cellular -> restore experiment remains unanswered, not implied by this source stage.

## Deployment and rollback matrix

| Component/action | Current permission/status | Backup / rollback requirement |
|---|---|---|
| Android source candidate | CI first; local independent same-signer install within existing gate | Private old APK/backups; no data clearing. New v2 notification keys retained by an older binary but ignored; downgrade may resume old unsafe pending state and cannot be called semantically safe without review. Prefer qualified forward repair. New energy envelope also has older-reader limits. |
| API Nano / energy serializer candidate | Not deployed by this stage | Separate exact approval; retain deployed bb09 image and four effective configurations, private DB/config backups, health/resource baseline, idempotent rollout and old-image rollback |
| Nginx/TLS candidate tools | Offline tests only, no live configuration applied | Separate operator/read/change approval; four-file private backup, config test, strict TLS/identity checks, atomic restore; no trust bypass |
| Production DB / old6SOC / 7113TPMS / bridge | Not authorized here | Exact scoped consent, reviewable backup, idempotence and explicit rollback before any runtime write |
| Main merge / release | NOT performed; Draft remains | CI/source alone does not open genuine device/provider/human acceptance gates |

Return one GitHub packet containing full SHA/tree/parent, actual job/artifact evidence and counts, local compile/test/sign/install/data-preservation results, independent original Java replay exit code, real authenticated history/notification/TPMS outcomes, unresolved human/environment gates and rollback review. No repeated per-file tasks and no new chat/task. Remote final comment must supply actual completed same-SHA results rather than placeholders or inherited green badges.

## 2026-10-11 full-stage TPMS source/receipt closure — qualification contract

Root cause: old Go `/status` returned pressures but not each wheel's
observation time. Android treated every new phone poll as a genuinely new
`provider_observation`, even when the pressure was a repeated cached
status. This could create synthetic-looking 30-day points or spurious
meaningful-pressure-change alerts. No production/history backfill or
deletion has been performed.

The revised source contract adds `pressure_unit=bar` and
`source=telemetry_mqtt` / `time_basis=collector_received_at` only when
the backend has actual field-scoped MQTT collector receipt instants,
plus each pressure wheel's separate `tpms_pressure_XX_observed_at`.
A later unrelated SOC event cannot change a wheel's evidence time.
This timestamp is **not** Tesla's tire sensor measurement time. Official
Tesla Fleet telemetry documentation explicitly notes timezone issues
with the last-seen TPMS sensor timestamps. We do not invent a
conversion, Tesla sampling instant or vehicle wake.

The live Android Worker now checks explicit source/unit/time, rejects
unqualified old API and future/stale timestamps, keeps actual zero,
writes one scoped partial wheel row per distinct timestamp, merges
simultaneous partial rows without erasing old values, and compares
each wheel with its own prior verified reading within the alert window.
An unverified legacy row never upgrades to a Fleet point. Unknown
ambient-temperature observation alignment stays unavailable. Missing
fresh pressure evidence never generates a fresh custom pressure alert.
Tesla warning flags remain gated behind a complete verified pressure
set in this candidate. No Room schema/DDL, legacy TPMS write, app
data clearing, log reset or production API change.

### Same-SHA local acceptance additions

Independently qualify the final SHA's Go
`TestTPMSFieldScopedCollectorReceiptsAndNoGlobalTimestampPromotion` and
`TestTPMSUnknownFleetTimestampImportAndStaleSnapshotFailClosed`;
Android `TpmsObservationEvidenceTest`, worker/state/30-day trends,
`TpmsProvenanceSourceContractTest`, the unchanged d119 standalone
Java replay, full Debug/Release unit XML, both lint variants, R8,
and all other existing fixed-stage resource/recovery/TLS/PG16/Web
checks. Verify synthetic different-field-timestamp, bad time, source
switch, 0 bar, legacy-vs-qualified same-ID, future record, and same-wheel
change cases. Keep local work in a separate clean checkout; preserve
original keystore and only use same-signer `adb install -r` after
independent gates. Do not run tests with production database credentials.

The deployed API `bb09fac` cannot provide this new TPMS timestamp
contract. Until an independently approved reversible candidate API
rollout, naturally sampled TPMS source→API→Phone trends remain
**NOT ACCEPTED** even if current CI is all green. Back up unchanged
deployed image/config/schema state, use reviewed no-DDL candidate
publication, verify field names and non-wake status paths, and roll
back to the prior image/config if necessary; this is a PLAN, not
authorization to deploy. Human natural Fleet first events, two users,
permissions/notifications and full phone TLS delivery remain
independent gates. No old 6-SOC or 7113-point TPMS repair performed.

### Legacy provenance quarantine correction (same implementation stage)

Older Android binaries wrote local polling time as `provider_observation`.
Those previously persisted rows are **retained** but excluded from this
new certified collector-time trend and notification path. Only new
`collector_received_verified` receipts with exact wheel-specific
Go MQTT collector timestamps qualify for new 7/30-day trends,
pressure deltas, retryable notification evidence and scoped pruning.
A pre-existing queued pressure alert must match a current exact V2
receipt/time/wheel/after-pressure or it cannot be delivered. Legacy
DataStore pending keys and Room rows are not deleted or rewritten.
This avoids using an incorrect legacy timestamp as the "before" side
of a new natural pressure-change claim.

Same-SHA Android tests must prove strict legacy-poll versus V2 split,
source/window/car/zero/late/wrong-wheel behavior, no historical rewrites,
and the Room `latestPerWheel` bounded query / same-instant partial merge
via independent instrumentation on a disposable database only.
