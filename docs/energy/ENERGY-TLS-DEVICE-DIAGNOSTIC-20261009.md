# Authenticated history TLS qualification — energy stage / PR17 / 2026-10-09

## Source of truth and honest qualification
Read-only local/device observation is retained verbatim from
`docs/ENERGY-LOCAL-DEVICE-QUALIFICATION-20261009.md` (Jovi's independent evidence
`codex/energy-device-verification-20261009@e64c39a`).

The Android build46/2.1.27 based on `80c891825da6cd6592df075e86788cdb81b449af`
passed same-SHA Actions `37820112901`. Local Debug 714/714 and Release
706/714 (8 skipped, 0 failed), lint 0 errors, R8, same-signer signed release
and `adb install -r` passed. `firstInstallTime`, data directory, cached
history and the signed-in app state were retained. Synthetic Go/PG16 tests
passed. These are CI/device/install facts, **not** first natural Fleet event,
passive notification or authenticated history acceptance.

The Android 11 phone then emitted four safe lines of
`stage=history_context http=none category=tls` during
2026-10-08 18:20:17–18:23:20 UTC. The cloud history page displayed a cached
failure warning, with missing detail measurements. This is an unresolved
**real authenticated-history qualification FAIL**, not a blank-trip or account
failure. HTTP status is absent because a completed HTTP response was not
observed. Public `health`/`ready` status 200 and server restart count 0
do **not** prove that this phone negotiated TLS, reached the authorized
history handler or obtained user-scoped records.

The read-only observed public certificate chain and desktop TLS1.3/hostname
success cannot establish the Android 11 handshake cause. No usable
per-request origin access log was available via normal permissions; permission
denial is not license to escalate. No certificate, root-store, routing, DNS,
proxy, VPN, network policy or trusted identity is changed in this code stage.

## Minimal additive diagnostic, no security bypass
`SafeApiFailure.TLS` remains the existing stable high-level category `tls`;
`ApiResult.Error.safeTlsCause` records an optional **type-based code** only.
It does not include the exception message, URL, hostname, token, VIN,
path, peer certificates, stack trace, request/response bodies, user IDs or
endpoint query. `HistorySync` uses a strict allowlist for stage names and
prints only `stage`, `requested_at`, `http`, `category`, and an optional
`tls_cause` (from a closed enum). Legacy callers still see `category=tls`.

| `tls_cause` | What the Java exception type establishes (not a guessed root cause) |
|---|---|
| `certificate_expired` | Java certificate-expired exception present in bounded causal chain |
| `certificate_not_yet_valid` | Java not-yet-valid certificate exception present |
| `certificate_path_validation` | Java `CertPathBuilderException` or `CertPathValidatorException` present; does not itself prove missing root anchor |
| `certificate_validation_other` | Other Java certificate-validation exception present |
| `peer_unverified` | `SSLPeerUnverifiedException` present; does not on its own prove hostname mismatch |
| `protocol` | `SSLProtocolException` present; does not by itself identify proxy/cipher/TLS version |
| `handshake_unspecified` | `SSLHandshakeException` present with no more specific **type** observed |
| `tls_unspecified` | Another `SSLException` present |

The app's prior message "Server certificate cannot be verified" was wrong
for a generic SSL exception; it is now "Secure connection could not be
established." The connection-test readiness warning also no longer renders
`Throwable.message` to the UI. These changes do not adjust OkHttp trust
management, `HostnameVerifier`, security XML, timeouts, auth headers,
vehicle scope, token refresh, fallback or the selected cloud endpoint.
A regression checks release system trust and the existing HTTPS requirement,
and tests prove fixed output with secret-looking input and nested typed causes.

## Exact requalification procedure (local verifier only; remote owns code)
After **final fixed-SHA** CI is completed successfully, use a separate clean
worktree, preserve dirty files, and independently verify HEAD/tree/parent and
the real unsigned Actions output. Re-run Debug/Release unit tests (including
`SafeApiFailureTest`, `HistoryDiscoveryPolicyTest`,
`CloudTlsPolicyRegressionTest`), lint, R8 and all original energy/PG/race
tests. Record actual pass/fail/skip; no prior-run reuse. Sign with the same
private key, verify `com.matelink`, build46 version2.1.27 and cert equality;
`adb install -r` only, preserving session, cache, history and original
first-install time. No uninstall, app-data clearing, destructive instrumented
test on Owner's phone or private signing key transfer.

With the owner's normal, **already-authorized** account and vehicle, use the
app's existing history read action; do not wake the vehicle. In a short
bounded observation window, collect only redacted `HistorySync` allowlisted
diagnostic lines (including `tls_cause`) and functional outcome:
authenticated history succeeds with correct account/vehicle/source,
or still fails. Do not expose raw logcat, requests, TLS exceptions, URLs,
coordinates, VIN, vehicle IDs, token, account ID or server logs. In the
failure case, report the stable code **as observed**, including unknown
or `handshake_unspecified`. Never transform certificate-related *types*
into a claim that RootX1, phone system CA or the API certificate is the cause.
If source information cannot identify the cause, the gate remains blocked.
Read-only access-log denial stays an explicit permission gate; no privilege bypass.

Separate decisions: CI PASS does not prove device PASS; signed install PASS
does not prove authenticated Fleet/history PASS; archive observations, synthetic
PG and health 200 cannot prove first natural Fleet events, passive drive/charge
notifications, TPMS trends or second real-user isolation.
`main` merge remains BLOCKED until Jovi receives a genuine successful
authenticated-history requalification or a specific independently supported
human/environment blocker determination under the actual approval rule.
Even a source merge would not authorize production DB writes, API/bridge
deployment, historical six SOC corrections, TPMS 7113 backfill, TLS bypass,
or network change. Preserve rollback package build45, deployed API bb09,
full history, signing identity and backups.
