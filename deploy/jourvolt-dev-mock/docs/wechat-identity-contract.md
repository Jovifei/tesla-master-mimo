# Optional WeChat Mini Program identity binding

Tesla OAuth remains the primary account login. WeChat can restore a MateLink session only after a user explicitly binds the WeChat identity to an already authenticated Tesla-backed MateLink account. Binding does not infer an account from a VIN, email address or display name. All endpoints below use HTTPS, JSON and `Cache-Control: no-store`. Error responses have the shape `{"error":"code"}`.

## Endpoints

| Request | Authentication and body | Success |
| --- | --- | --- |
| `POST /v1/auth/wechat/bind` | `Authorization: Bearer <MateLink access token>` and `{"code":"<fresh wx.login code>","binding_version":"0"}` using the version captured for this explicit action. | `200 {"status":"bound"}` |
| `GET /v1/auth/wechat/binding` | `Authorization: Bearer <MateLink access token>` | `200 {"bound":true,"binding_version":"0"}` or `{"bound":false,"binding_version":"0"}`; the version is a decimal string and contains no WeChat identity. |
| `POST /v1/auth/wechat/unbind` | `Authorization: Bearer <MateLink access token>`; no body required. | `200 {"status":"unbound"}`; also succeeds if already unbound. |
| `POST /v1/auth/wechat/restore` | No MateLink session required. Body `{"code":"<fresh wx.login code>"}`. | `200 {"access_token":"...","refresh_token":"...","expires_in":900,"user":{"id":"..."}}` for the linked Tesla-backed account. These are MateLink session credentials, not Tesla or WeChat credentials. |

The session response does not include a `provider` or `source` field. The client may label a session as WeChat-origin locally based on the operation that returned it; the server independently records the binding on the session and retains that association through refresh.

All operations consuming a code require a new `wx.login` call. A code is reserved before the WeChat provider is contacted, and remains consumed even when the operation fails or times out. Never retry the same code. The provider validates code expiry and single use; the backend also records a hash for one day so concurrent requests and process restarts cannot replay it. After that retention period the WeChat provider still rejects expired or used codes.

| HTTP | Error | Client behavior |
| --- | --- | --- |
| 400 | `invalid_wechat_request` | Obtain a fresh code and send a valid JSON body. |
| 401 | `session_required` | Authenticate with Tesla again or discard a stale in-flight response. |
| 401 | `wechat_code_invalid` | Obtain a fresh `wx.login` code. Includes provider-reported expired or used codes. |
| 401 | `tesla_reauthorization_required` | Complete the existing official Tesla OAuth flow; do not treat WeChat as a Tesla authorization. |
| 404 | `wechat_not_bound` | Show the Tesla login entry; binding is an explicit optional step after successful Tesla login. |
| 409 | `wechat_binding_conflict` | The identity belongs to another MateLink user, or this user has another WeChat binding. Unbind from the account that owns it before binding another; there is no automatic account merge. |
| 409 | `wechat_bind_expired` | This action predates an unbind. Show that it was cancelled; do not automatically retry it. |
| 503 | `wechat_not_configured` | WeChat binding/restoration is unavailable; Tesla login remains available. |
| 503 | `wechat_provider_unavailable` | Provider/network/configuration failure; use a new code on retry. |
| 503 | `tesla_unavailable` | Tesla grant verification or refresh is temporarily unavailable; retry later. |
| 500 | `wechat_service_failed` | Server/storage failure; use a fresh code on retry. |

An unrecognized path or method returns `404 not_found`. Binding and unbinding require a currently valid bearer token at the time the database mutation commits. Binding additionally checks the Tesla grant. Restore verifies the linked account's locally valid Tesla access token or refreshes it using the existing server-side token manager before creating a session. A grant revoked by Tesla before its locally recorded access expiry can still be rejected by a later Fleet API call; clients must also handle the existing `tesla_reauthorization_required` response there.

## Minimal client sequence

1. Offer Tesla login first. On a later launch, if the user has enabled WeChat recovery, call `wx.login` and submit the code to `restore`. If no binding exists or the Tesla grant needs reauthorization, show the official Tesla login flow.
2. For an explicit bind action, obtain `binding_version` from `GET /binding` before the confirmation, preserve that exact version through confirmation and any session refresh, call `wx.login`, then submit its fresh code and the captured version to `bind`. An unbind increments the version even if already unbound, so any older bind is rejected, including one that arrives late. Do not replace the captured version after a 401 or automatically retry `wechat_bind_expired`; a new attempt requires a new explicit action, version and code. Show account conflicts instead of switching or merging accounts automatically.
3. Use `GET /binding` for the current account's binding setting. `POST /unbind` removes the binding and all sessions issued through it, including already rotated refresh tokens. A Tesla-origin session is preserved; if the caller itself is WeChat-origin, it is invalid after unbinding.
4. `POST /v1/session/logout` continues to end the current session only. It also fences restores already in flight for that account, but does not revoke other established devices. During the 15 minutes after session refresh, the immediately previous and other recently rotated access tokens can only be used to log out that same session; they cannot authenticate or refresh it. After this window an older token cannot revoke the rotated session. A later fresh code can restore again while the binding exists. The client must use its own session epoch or cancellation guard to discard late responses after logout, account switch, or unbind. Account deletion deletes the binding and its sessions.

## Provider and deployment

Set both `WECHAT_MINIPROGRAM_APP_ID` and `WECHAT_MINIPROGRAM_APP_SECRET` in the private server environment and retain the existing Tesla OAuth and token-encryption configuration. All supplied Compose variants pass the optional values through. Empty or incomplete WeChat configuration disables bind/restore with `503 wechat_not_configured` without disabling Tesla login. `binding` and `unbind` remain available to an authenticated account when WeChat configuration is absent. Invalid credentials or unreachable WeChat produce `503 wechat_provider_unavailable`; no synthetic identity is created.

The provider uses WeChat's fixed HTTPS `sns/jscode2session` endpoint and does not follow redirects. The AppSecret, code, WeChat `session_key`, openid, unionid and Tesla credentials must not be logged by proxies, HTTP tracing or application monitoring. The backend discards `session_key`, stores only provider-scoped hashes of openid/optional unionid and code, and keeps Tesla access and refresh credentials encrypted in the existing server store. WeChat unionid uniqueness is enforced only within the configured mini-program provider; it is never used to merge identities across apps or developer accounts. One MateLink user has at most one WeChat binding; changing AppID requires unbinding the old identity before rebinding. Put rate limits on the public code endpoints at the gateway.

Startup performs additive, idempotent PostgreSQL schema creation: binding and code-reservation tables, a per-user binding-version table, a logout-only session-alias table and a nullable binding foreign key/index on sessions. Session aliases contain only hashed former access tokens; they expire after 15 minutes and are removed in bounded batches on later refreshes. Deleting a session, unbinding a WeChat-origin session or deleting its user cascades to the relevant aliases; deleting the user also removes its version. The binding foreign key deletes WeChat-origin sessions when their binding is deleted. Back up the database before rollout and deploy all instances together: older binaries do not implement binding versions or logout aliases, so mixed versions cannot provide these concurrency guarantees. Before rolling back to an older binary, revoke the WeChat-origin sessions and disable WeChat recovery; do not drop new tables or session columns while newer instances are running. Production AppID/AppSecret, WeChat API connectivity, platform permissions and a real user flow require separate provider/device verification.
