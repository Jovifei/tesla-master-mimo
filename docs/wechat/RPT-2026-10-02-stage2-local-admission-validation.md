# Stage 2 local acceptance — 2026-10-02

Remote base: e9ef770645a3a51689fed651cf3ab8bfc7e250dd.

Detail HTTP admission is integrated. Local correction supplies the three constructor limits (global 8, user 2, vehicle 1). Five real HTTP handler tests verify overload/Retry-After, account isolation, canceled requests, not-found release, and lightweight list availability under global saturation. CI race selection now includes admission and telemetry HTTP tests.

Validation on the final local tree:
- Full Go suite with isolated PostgreSQL 16: PASS, 287 tests, zero failures/skips.
- Targeted Linux Go race suite with isolated PostgreSQL 16: PASS, exit 0.
- Earlier full Go vet/build: PASS. Final changes after those checks affect tests and CI selection only.
- gofmt and git diff --check: PASS.

Evidence is retained locally under E:/Claude_allow/Download/matelink-oom-local-20261002 (stage2-go-final.json, stage2-linux-race.log).

Scope remains incomplete: import/archive admission, independent persisted summaries, bounded point/chunk persistence, restart/QoS1 qualification, and 2/4/8/16 resource measurements require remote implementation and a further local handoff. Stage 2 is not accepted as complete. No main merge, production deployment, historical-data deletion, or retention change occurred. Stage 2 phone validation: NOT_RUN (backend-only change); earlier Stage 1 Android installation does not prove this stage's production behavior.
