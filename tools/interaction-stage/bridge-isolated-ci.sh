#!/usr/bin/env bash
# Existing bridge tests in a disposable mount namespace, never a live bridge.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
mkdir -p evidence
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/tests" "$TMP/original"
printf '{"synthetic":true}\n' > "$TMP/original/state.json"
BEFORE=$(sha256sum "$TMP/original/state.json" | cut -d' ' -f1)
BRIDGE="$ROOT/deploy/teslamate-home-docker/archive-bridge"
(cd "$BRIDGE" && go mod download)
mapfile -t PACKAGES < <(cd "$BRIDGE" && go list ./...)
for index in "${!PACKAGES[@]}"; do
  package=${PACKAGES[$index]}
  (cd "$BRIDGE" && CGO_ENABLED=0 go test -c -o "$TMP/tests/$index.test" "$package")
  if [[ "$package" == */cmd/battery-trend ]]; then BATTERY_TEST="$index.test"; fi
done
test -n "${BATTERY_TEST:-}"
IMAGE=debian:bookworm-slim
docker pull "$IMAGE"
docker image inspect "$IMAGE" --format '{{json .RepoDigests}}' > evidence/bridge-container-image.json
for index in "${!PACKAGES[@]}"; do
  docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
    --tmpfs /tmp:rw,nosuid,nodev --tmpfs /trend:rw,nosuid,nodev --tmpfs /socket:rw,nosuid,nodev \
    --mount "type=bind,src=$TMP/tests,dst=/tests,readonly" \
    --mount "type=bind,src=$TMP/original,dst=/original,readonly" \
    -e MATELINK_SYNTHETIC_ORIGINAL=/original/state.json \
    "$IMAGE" "/tests/$index.test" -test.v 2>&1 | tee -a evidence/bridge-container.log
done
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
if grep -E -- '--- (FAIL|SKIP):' evidence/bridge-container.log evidence/bridge-writable-alias.log; then exit 1; fi
(cd "$BRIDGE" && go test -race -v -count=1 -run '^TestExistingBridge.*Pending503' ./cmd/battery-trend) \
  2>&1 | tee evidence/bridge-race.log
