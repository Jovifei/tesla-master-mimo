# TLS stage supplemental read-only evidence — 2026-10-09

Observation UTC2026-10-09T00:37:42Z / China08:37:42; no service/config/data writes.

The current public `/etc/nginx/jourvolt-selfsigned.crt` was readable through the existing authorized operations account. Its certificate fingerprint **does not match** the old failed phone handshake peer observed2026-10-08T19:06–19:09Z. No fingerprint, certificate subject/SAN or raw trace is published. The old source8625 `setup-root.sh` generates RSA2048, whereas the old failed wire peer was EC with433-byte DER. The script also preserves an already-existing self-signed file, so generation shape alone is not causal attribution. Current file inequality cannot prove what was present/served during the older failure. Keep the real script downgrade and insecure-verification defects separate from an unproven historical phone root cause.

Parent contract context: local verifier read `E:\project\tesla_master\AGENTS.md`; it requires code-review-graph tools first for structural/source exploration (detect_changes, get_review_context, get_affected_flows, query_graph/semantic_search_nodes), with rg/read fallback when the graph does not cover the need. No graph tool is exposed in the current local tool inventory. This does not expand the remote connector's PATH_OUTSIDE_WORKSPACE boundary or authorize production changes. Do not reconfigure the connector merely to read the parent.

Use the same full-stage GitHub handoff and existing pending human/network gates. Repair actual source/script defects with meaningful no-downgrade, strict TLS and idempotence regressions. Do not run setup-root on production, infer a proxy actor, weaken trust or treat old snapshots as current live state.
