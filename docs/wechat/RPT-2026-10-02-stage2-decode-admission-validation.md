# Stage2 decode admission local acceptance — 2026-10-02

Accepted source prerequisite: 93a1e3a28df6c7f55e468f0f23cdfe5afc556703. Remote request adc2955cbd131746f1ac0850269204305ac0aada was reviewed but rejected: its replacement removed the existing importHistory entry without replacing callers or acquiring an outer decode permit; its primitive-only test leaked a permit. The executable source fix in this commit was authored locally as a residual bug repair.

Ordinary import acquires its scoped shared permit before JSON decoding. Archive import resolves and validates binding user/vehicle before acquiring its permit and decoding. HTTP callers use an internal admitted import path and retain the permit through conversion, persistence and response serialization. Non-HTTP callers keep the original importHistory wrapper, which acquires once. Error paths release via defer; 429/Retry-After and cancellation 408 remain distinct.

Red/green evidence: overload and canceled requests previously read the body twice; after correction both ordinary/archive handlers read it zero times. Invalid JSON releases the permit; subsequent valid requests succeed. Valid requests do not acquire twice. Four top-level tests, eight subcases pass.

Final validation:
- Full Go suite with isolated PostgreSQL16: 306 PASS including subtests, zero failures/skips.
- go vet/build: PASS.
- Linux targeted history/admission/import/archive/decode race: PASS, exit0.
- gofmt/git diff --check: PASS.
- Independent focused read-only review and eight decode subcases: PASS.

Evidence: E:/Claude_allow/Download/matelink-oom-local-20261002/stage2-decode-go-final.json and stage2-decode-linux-race.log.

The decode-before-admission gap recorded at 93a1e3a is closed by this source. Independent persisted summaries, bounded point/chunk persistence, restart/QoS1 and 2/4/8/16 resource measurements remain incomplete. Remote summary helpers were not materialized. No main merge, production deployment, history deletion, TTL change or Stage2 phone acceptance is claimed.
