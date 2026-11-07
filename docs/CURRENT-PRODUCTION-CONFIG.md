# MateLink current production configuration

> Current as of 2026-11-06. This file overrides older documents, build defaults, and historical reports.

## Active cloud production link

| Item | Value |
|---|---|
| Cloud API base URL | `https://api.teslalink.joviluma.com/` |
| Public info base URL | `https://auth.teslalink.joviluma.com` |
| App Link auth host | `auth.teslalink.joviluma.com` |
| Production server | `120.55.64.11` |
| Production server nginx | `nginx/1.24.0` |
| Cloud API upstream | `127.0.0.1:18090` on the production server |
| Health endpoints | `/healthz`, `/readyz` |
| Current API build observed | `d61d6c7bb326f11e20b4f63987ae72010f6d3a94` |

Verified on 2026-11-06 from the production server route:

```text
GET https://api.teslalink.joviluma.com/healthz -> 200
GET https://api.teslalink.joviluma.com/readyz  -> 200
```

## Self-hosted link

| Item | Value |
|---|---|
| Self-hosted public entry | `https://teslalink.joviluma.com` |
| Expected nginx upstream | `127.0.0.1:18080` on the production server |
| Current state | Not running; public entry returns 502 |
| Server project | `/home/jourvolt/matelink-selfhost` |
| Reason | The compose project has no running containers, and required Teslamate images are not present locally |

The self-hosted link is optional history infrastructure. It is not the current cloud production path for phone refresh. Do not restore it by touching unrelated services or by replacing production credentials.

## Forbidden or stale values

| Value | Status |
|---|---|
| `https://api.jourvolt.com/` | Legacy default; forbidden for current Release builds |
| `http://192.168.0.104:18080` | Historical local/LAN self-host setting; not the current cloud link |
| `198.18.x.x` DNS answers | Workstation TUN/fake-IP behavior; not real public DNS evidence |

## Current Android production artifact

| Item | Value |
|---|---|
| Package | `com.matelink` |
| Version name | `2.1.83` |
| Version code | `103` |
| Certificate SHA-256 | `9ab144e824abf26a5941819abb06831288c36a8bfe622657e3dc9d88281fc774` |
| Current APK SHA-256 | `A7B58BAC069B501A22384CD87B4595E5C5A4752E1A1C6215EF1056DA453D7E1A` |
| Build API URL | `https://api.teslalink.joviluma.com/` |
| Phone install method | Same-signature `adb install -r` only |
| Phone first install time preserved | `2026-08-31 22:36:47` |

## Verification sequence

1. Confirm the production API directly from the server or with `curl --resolve`.
2. Build with the exact current URLs.
3. Inspect generated Release `BuildConfig`.
4. Verify APK package, signature, and hash.
5. Install with `adb install -r`.
6. Confirm the phone first-install time is unchanged.
7. Capture read-only home and charging screenshots without touching vehicle controls.
