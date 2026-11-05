#!/usr/bin/env bash
# CI only: synthetic databases and mount namespaces; no live bridge or Owner volumes.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
mkdir -p evidence
TMP=$(mktemp -d)
PG_NAME="matelink-visual-pg-${GITHUB_RUN_ID:-$$}-${GITHUB_RUN_ATTEMPT:-1}"
cleanup() { docker rm -f "$PG_NAME" >/dev/null 2>&1 || true; rm -rf "$TMP"; }
trap cleanup EXIT
mkdir -p "$TMP/tests" "$TMP/original"
printf '{"synthetic":true}\n' > "$TMP/original/state.json"
BEFORE=$(sha256sum "$TMP/original/state.json" | cut -d' ' -f1)
BRIDGE="$ROOT/deploy/teslamate-home-docker/archive-bridge"
ADAPTER="$ROOT/deploy/teslamate-home-docker/adapter"
(cd "$BRIDGE" && go mod download)
mapfile -t PACKAGES < <(cd "$BRIDGE" && go list ./...)
DIRS=()
for index in "${!PACKAGES[@]}"; do
  package=${PACKAGES[$index]}
  DIRS+=("$(cd "$BRIDGE" && go list -f '{{.Dir}}' "$package")")
  (cd "$BRIDGE" && CGO_ENABLED=0 go test -c -o "$TMP/tests/$index.test" "$package")
  if [[ "$package" == */cmd/battery-trend ]]; then BATTERY_TEST="$index.test"; fi
done
test -n "${BATTERY_TEST:-}"
(cd "$ADAPTER" && CGO_ENABLED=0 go test -c -o "$TMP/tests/adapter.test" ./cmd/adapter)
IMAGE=debian:bookworm-slim
PG_IMAGE=postgres@sha256:22c89fe0d0f507606260237fd55e51f6137f58b2d5bcf6152242b96d9fe8f9a4
MIGRATION_IMAGE=teslamate/teslamate:4.3.0@sha256:516fc9f0a14369f541b1a70ff2f65dd9b57c2e01c1f533b6d43b375445404955
docker pull "$IMAGE"
docker pull "$PG_IMAGE"
docker pull "$MIGRATION_IMAGE"
docker image inspect "$IMAGE" "$PG_IMAGE" "$MIGRATION_IMAGE" --format '{{json .RepoDigests}}' > evidence/bridge-container-images.json
# No published ports or external network. Test containers share only this synthetic loopback.
docker run -d --rm --name "$PG_NAME" --network none --tmpfs /var/lib/postgresql:rw \
  -e POSTGRES_USER=compat_only -e POSTGRES_PASSWORD=isolated-only -e POSTGRES_DB=compat430 \
  "$PG_IMAGE" -p 55432 -c fsync=off > evidence/bridge-postgres-container.txt
READY=0
for attempt in $(seq 1 60); do
  if docker exec "$PG_NAME" pg_isready -p 55432 -U compat_only -d compat430; then READY=1; break; fi
  sleep 1
done
test "$READY" = 1
docker run --rm --network "container:$PG_NAME" --entrypoint bin/teslamate \
  -e DATABASE_USER=compat_only -e DATABASE_PASS=isolated-only -e DATABASE_NAME=compat430 \
  -e DATABASE_HOST=127.0.0.1 -e DATABASE_PORT=55432 \
  -e ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= -e TZ=UTC -e MQTT_HOST=synthetic.invalid \
  "$MIGRATION_IMAGE" eval 'TeslaMate.Release.migrate()' 2>&1 | tee evidence/bridge-upstream-migrations.log
docker exec -i "$PG_NAME" psql -v ON_ERROR_STOP=1 -p 55432 -U compat_only -d compat430 < deploy/teslamate-home-docker/qualification/fixture-v430.sql
docker exec -i "$PG_NAME" psql -v ON_ERROR_STOP=1 -p 55432 -U compat_only -d compat430 < deploy/teslamate-home-docker/qualification/fixture-unsupported.sql
docker exec -i "$PG_NAME" psql -v ON_ERROR_STOP=1 -p 55432 -U compat_only -d compat430 <<'SQL'
CREATE ROLE bt_test LOGIN PASSWORD 'isolated-only';
CREATE DATABASE matelink_battery_trend_test OWNER bt_test;
SQL
run_test() {
  local binary=$1 directory=$2
  docker run --rm --network "container:$PG_NAME" --read-only --cap-drop ALL --security-opt no-new-privileges \
    --tmpfs /tmp:rw,nosuid,nodev --tmpfs /trend:rw,nosuid,nodev --tmpfs /socket:rw,nosuid,nodev \
    --mount "type=bind,src=$TMP/tests,dst=/tests,readonly" \
    --mount "type=bind,src=$TMP/original,dst=/original,readonly" \
    --mount "type=bind,src=$ROOT,dst=/source,readonly" --workdir "/source/${directory#"$ROOT/"}" \
    -e MATELINK_SYNTHETIC_ORIGINAL=/original/state.json \
    -e 'MATELINK_COMPAT_DATABASE_URL=postgres://compat_only:isolated-only@127.0.0.1:55432/compat430?sslmode=disable' \
    -e 'MATELINK_COMPAT_UNSUPPORTED_DATABASE_URL=postgres://compat_only:isolated-only@127.0.0.1:55432/compat430?sslmode=disable&search_path=compat_unsupported' \
    -e 'MATELINK_BATTERY_TREND_TEST_DSN=postgres://bt_test:isolated-only@127.0.0.1:55432/matelink_battery_trend_test?sslmode=disable' \
    "$IMAGE" "/tests/$binary" -test.v 2>&1 | tee -a evidence/bridge-container.log
}
# Preserve package-relative source/fixtures without making the checkout writable.
for index in "${!PACKAGES[@]}"; do run_test "$index.test" "${DIRS[$index]}"; done
run_test adapter.test "$ADAPTER/cmd/adapter"
# The same physical backing as a writable trend alias MUST be rejected.
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
  --tmpfs /tmp:rw,nosuid,nodev --tmpfs /socket:rw,nosuid,nodev \
  --mount "type=bind,src=$TMP/tests,dst=/tests,readonly" \
  --mount "type=bind,src=$TMP/original,dst=/original,readonly" \
  --mount "type=bind,src=$TMP/original,dst=/trend" \
  -e MATELINK_SYNTHETIC_ORIGINAL=/original/state.json -e MATELINK_SYNTHETIC_EXPECT_ALIAS=1 \
  "$IMAGE" "/tests/$BATTERY_TEST" -test.v -test.run '^TestSyntheticContainerMountIsolation$' \
  2>&1 | tee evidence/bridge-writable-alias.log
test "$BEFORE" = "$(sha256sum "$TMP/original/state.json" | cut -d' ' -f1)"
grep -q -- '--- PASS: TestSyntheticContainerMountIsolation' evidence/bridge-container.log
grep -q -- '--- PASS: TestSyntheticContainerMountIsolation' evidence/bridge-writable-alias.log
grep -q -- '--- PASS: TestPostgresReadsScopedBoundaryEvidenceAndBlocksOpenLowerID' evidence/bridge-container.log
test "$(grep -c -- '--- PASS: TestCompatibilityRealPG18MigratedV430 (' evidence/bridge-container.log)" -eq 2
if grep -E -- '--- (FAIL|SKIP):' evidence/bridge-container.log evidence/bridge-writable-alias.log; then exit 1; fi
(cd "$BRIDGE" && go test -race -v -count=1 -run '^TestExistingBridge.*Pending503' ./cmd/battery-trend) \
  2>&1 | tee evidence/bridge-race.log
