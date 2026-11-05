# Execute optional upstream-image tests rather than accepting skips

Run 42044934961 at 9583d0c8dd080c3e664959a92f701432a80366f1 actually passed all bridge tests, both PG18 consumers and the writable-alias negative test. The strict gate correctly remained red because the adapter's two existing optional runtime-image tests skipped without API instances.

This follow-up supplies those missing fixtures, not an allowlist. A job-owned internal Docker network has no external connectivity or published host ports. It contains only the pinned synthetic PostgreSQL fixture, the repository's previously selected immutable API 1.25.0 image, and an image containing the actual adapter binary compiled from this checkout. Their aliases are the exact api/adapter names required by the existing guards. The database role/password and API token are explicit synthetic test values. No real Tesla/provider/account credentials are used.

Both API/proxy and running-adapter tests are now required to PASS along with the existing no-fail/no-skip checks, read-only original fixture, alias rejection and race checks. The harness records image digests, binary hash and actual logs. Health probes are bounded and cleanup removes only job-owned containers/network/image. No production business code or mount guard is modified.

The source stage remains App/Mini interaction visuals, not a production rollout. Native WeChat, physical-device installation and genuine natural Fleet/TPMS/notification acceptance remain separate. Complete terminal-SHA CI and independent artifact inspection are required before the final receipt.
