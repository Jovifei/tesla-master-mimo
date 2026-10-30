# Engine26 isolated diagnostics candidate

This is an unaccepted diagnostic candidate. The second CI at reference
`e99c27f8` stopped at case11 with `CAPTURE / CONTRACT_REJECTED`. Its specific
cause remains unknown. The network-name/empty-NetworkID explanation is a mock
hypothesis, not a confirmed observation from that runtime.

This change only adds fixed static diagnostic codes, synchronizes the verifier's
code whitelist and updates the harness hash lock. Recovery core and receipt-store
bytes are unchanged. No API route, recovery decision, resource/cleanup gate or
execution permission is relaxed. This candidate grants no push, deployment,
server access or further CI execution authority.

Any separately authorized test that executes this harness against Docker is
restricted to an isolated disposable GitHub-hosted VM. It must not use a local,
self-hosted or production daemon. The workflow describes one explicitly selected
competitor-first13-case round; another order needs separate review and invocation.
Local static/mock checks are preparation evidence only.

Actual outcomes must be established from that run's source/input hashes, exit
status, journal, result, unknown/reconciliation and cleanup evidence. Neither a
candidate label nor static/mock success is acceptance. No production configuration,
credentials or real vehicle data are included.
