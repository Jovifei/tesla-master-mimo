# Independent TeslaMate battery-trend bridge

**Safety gate, 2026-11-02:** v2 state binds the independent cursor and frozen pending
bytes to the server-issued archive binding identity. Same-binding credential renewal
may resume; a different account or rebinding stops before source reads or uploads.
Legacy v1 state is never claimed automatically. A fresh state and an explicitly
confirmed one-time v1 migration are supported by the commands below.

This candidate is a separate executable. It reads completed TeslaMate drive summaries and their same-car, same-drive boundary positions through read-only PostgreSQL transactions. It sends only the independently scoped battery-trend import; it never replays drive or charge archives and does not write their cursor.

The process requires `ARCHIVE_URL` (HTTPS), `ARCHIVE_VEHICLE_ID`, `ARCHIVE_SOURCE_INSTANCE_ID`, `ARCHIVE_SOURCE_VEHICLE_ID`, `STATE_FILE` (the existing archive bridge state), `BATTERY_TREND_STATE_FILE` (the new independent state), and `BATTERY_TREND_SOURCE_TIMEZONE=UTC`. The UTC setting is an explicit operator assertion about the source's timezone-free timestamps; verify that convention for each source before enabling the bridge. A PostgreSQL session timezone alone cannot establish the meaning of existing naive timestamps. Both parent directories must already exist. The original state must be visible for alias checking and should be mounted read-only; the battery state must use a separate writable directory. Do not mount the original bridge state writable into this process. The existing `STATE_FILE` is inspected for path isolation only, never opened for writing.

For a new deployment, pass `--credentials-stdin` and supply one JSON object with `database_url` and `archive_token` via standard input, then close stdin. Input is limited to 16 KiB; both credential environment variables must be absent in this mode. Credentials stay in process memory and are not written to arguments, environment or state. Feed the pipe without echoing it or creating an environment file; the container runtime's privileged administrators can still inspect process memory. With no argument, the existing `DATABASE_URL`/`ARCHIVE_TOKEN` environment mode remains supported for compatibility.

The opt-in `docker-compose.battery-trend.yml` override also supports `--credentials-socket`. The original bridge serves its existing in-memory credentials over a private Unix socket; the trend process verifies the same Linux UID, socket ownership and permissions, and the archive URL, vehicle and source scope. Its socket mount and original-state mount are read-only, and its cursor stays in the explicitly named **existing** independent trend volume. The socket carries credentials in memory; it is not a credential file. Docker administrators can still inspect the original bridge's existing environment or process memory. This mode requires Linux and refuses a writable original-state mount. It does not enable or recreate any service merely by adding the override file.

On startup the bridge fetches `/history/archive/binding-identity` using the current
binding token, creates a new v2 state only when none exists, and otherwise requires
the stored identity to match. Uploads use `/history/archive/battery-trend/import-v2`
with `X-MateLink-Binding-Identity`; every receipt must echo the same identity.

After the signed-in owner confirms the current account, vehicle and source in the App,
an operator may migrate an existing v1 state once:

    BATTERY_TREND_CONFIRM_LEGACY_STATE_OWNER=I_CONFIRM_THIS_LOCAL_STATE_BELONGS_TO_CURRENT_BINDING \
      matelink-battery-trend --migrate-v1-binding

In managed socket mode, use `--credentials-socket --migrate-v1-binding`. Migration is
atomic, preserves the exact pending body and cursor, and never sends evidence.

In socket mode the process waits for the broker, source database and first original state file when they are unavailable. A permanent HTTP 4xx other than 408/429 writes a separate `.halt` marker next to the independent trend state and stops sending, including after a normal restart. An existing or damaged marker also stops sending. The marker contains only the HTTP status, not the token or pending batch; the pending state remains intact. An operator must inspect the failure, complete any same-scope credential renewal or resolve a real conflict, preserve the state, and explicitly archive the marker before restarting. Refreshing an expired/revoked token still requires the existing authorized enrollment and recreation of the original bridge with the renewed credential; merely editing an environment file does not change a running container. If the marker cannot be persisted, the current process remains stopped and logs the issue; an immediate crash before durable marker creation can permit one retry after restart.

Before activating the override, confirm the production API includes the `battery_trend_archive_pending` 503 change. Earlier servers can return 409 when a new archive is merely not present yet, which safely halts the pending batch but cannot resume automatically. Confirm that the existing trend volume belongs to the same source and vehicle, preserve its frozen pending batch, and stop the prior trend process before starting the replacement; concurrent access fails the state lock. The shared socket volume must be private to these two services. A Docker daemon restart can restart both containers when Docker itself runs; Docker Desktop configured to launch only at interactive login cannot guarantee recovery before login. Source records inserted or corrected behind the acknowledged drive ID are not automatically rescanned. No mechanism can recreate telemetry that was never collected while the source was offline.

Optional `BATTERY_TREND_BATCH_SIZE` is 1–50 (default 50); `BATTERY_TREND_POLL_INTERVAL` is at least one second (default one minute). The source database credentials must use a read-only role. The process also sets read-only transactions and UTC for its PostgreSQL connection. This directory's Dockerfile uses the parent `archive-bridge` directory as build context; it does not modify the existing image or compose configuration.

Each completed-drive ID watermark is frozen in the independent state. An unfinished lower ID blocks progression past later completed IDs. Same-car and same-drive position identity and the ±1-second boundary are required; mismatches stop the batch without advancing. Rated range comes only from the drive's start/end summaries, with unknown observation time; SOC and its observed time come from the referenced position. Missing values remain null.

Before sending, the exact request bytes and stable batch ID are atomically stored with owner-only permissions. The independent cursor advances only after the expected schema, hash, batch ID and `inserted + unchanged` receipt are verified. A lost response replays the same bytes after restart. Source records corrected after their ID was already acknowledged are not automatically rescanned; they require a separately reviewed reconciliation. HTTP 4xx responses stop the process with the pending batch intact, except 408 and 429, which are retried. Server and transient network failures continue to retry without advancing progress, no sooner than the configured interval after the previous attempt and any longer valid `Retry-After`. Investigate a permanent client error before restarting; server conflicts and bad source positions never change the original bridge.

Run local tests from `archive-bridge` with `go test ./cmd/battery-trend`. The PostgreSQL test only accepts an explicitly configured loopback synthetic database named `matelink_battery_trend_test` and uses temporary tables; without it, that integration test is reported as skipped. No production deployment or credentials are needed for the synthetic tests.
