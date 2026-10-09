# Independent local TLS source review — fixed3dd44e31

Status CHANGES_REQUIRED, read-only review. Installed product8625/phone installation and production remain unchanged; no real key read, no production or phone network change. This is one consolidated return within the same full stage, not per-file implementation delegation.

1. setup-root.sh157–175/216–219 modifies active include/config before validation/reload without ERR/EXIT rollback. Add transactional restoration on bad template/certificate, nginx-t and reload failures; prove original active files/other project configuration and running-state invariants with the actual script in an isolated test environment.
2. LE_VALID130–136 checks expiry, names and nonemptykey only, not notBefore, chain trust or key correspondence. Do not present these checks alone as trusted-ready. Actual nginx/key validation and certificate-window/chain proof must precede activation; no real key data in receipts. Existing boolean-selector/grep tests alone do not qualify setup behavior.
3. verify-public.sh122/162 can WARN on unauthenticated capabilities200 and still exit0. Check actual endpoint authentication design; under its stated401 contract, unexpected success/redirect/serverfailure must not pass. Add actual script-level positive/negative regressions and explicitly mark optional token test NOT_RUN without credentials.
4. verify-public.sh58/151–152 interpolates unvalidated PUBLIC_IP into bash-c. Reject non-IP/shell-meta values before commands, or remove shell interpolation. Exercise malicious input safely in isolation and prove no extra command executes. Include safe temp-file/symlink and IPv4/IPv6/listener cases.

Positive scope: curl-k removed; known-LE selection does not choose placeholder; qualifier fixes API/SNI, uses standard required chain+hostname validation, performs health-only no-auth reads and redacts errors. These improvements do not resolve the older phone peer cause.

Meaningful remaining matrix: actual setup no-downgrade/idempotence and rollback under every failure; trusted testCA wrong-SAN/expired/not-yet-valid/missing-chain and trust-negative cases; peer-comparison/non200; auth contract failures; invalid parameters and safe temporary files. Fixtures/tests synthetic only, no fake phone/natural/prod PASS. Finish full candidate/CI and gated runtime plan, rather than weakening assertions or returning only a helper.

Local verifier independent current public-file vs old-peer comparison and parent-contract context: docs/TLS-STAGE-SUPPLEMENTAL-READONLY-EVIDENCE-20261009.md at eab3911. Keep snapshots/time and causal limits separate.

