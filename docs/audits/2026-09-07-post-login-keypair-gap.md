# Post-login virtual-key acceptance gap — 2026-09-07

## Finding

The current branch correctly sets Tesla OAuth parameters `prompt_missing_scopes=true`, `require_requested_scopes=true`, and `show_keypair_step=true`. However, `show_keypair_step=true` is only a Tesla authorization-UX hint. It is not evidence that MateLink actually performs the second, Tesla-owned virtual-key pairing step immediately after a successful OAuth callback.

## Product requirement

For the target consumer experience, a user must never be sent to an infrastructure/settings page to discover Fleet Telemetry setup. After successful Tesla OAuth/session exchange:

1. MateLink should discover the selected/first vehicle.
2. MateLink should query pairing/config readiness.
3. If Tesla reports `pairing_required`, MateLink should present the official Tesla virtual-key confirmation as the next onboarding step.
4. After returning from Tesla, MateLink should re-check pairing/configuration once and continue automatically.
5. The flow must be gated so app resume/recreation cannot repeatedly launch the external Tesla flow.

If the vehicle does not require a virtual key, this step is skipped.

## Merge gate

PR #10 remains draft. Do not claim the desired one-flow onboarding is complete until the real Android path proves OAuth -> MateLink session -> conditional Tesla key confirmation -> telemetry config -> first real event without asking the user to configure MQTT/broker/certificates/topics.
