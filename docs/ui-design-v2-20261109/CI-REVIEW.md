# V2 review history

The first run 42625401432, job 129187730464, failed before building or testing. The floating ubuntu-latest runner resolved to Ubuntu 26.04, unsupported by the already pinned Playwright 1.57.0. This is not a product or design pass. The workflow now pins ubuntu-24.04; dependency versions and assertions are not relaxed. The original failure is retained. Only exact final-SHA checks and downloaded screenshots count as completed offline validation. No Figma or native acceptance is inferred.

Pixel review of artifact 13677593639 (128 checks passed) found two design issues not covered by the original checks: vehicle-login CTA below the fold, and reused non-specific analytics scales. Fixed login viewport, metric-specific units/ranges, original charging average-price meaning, and detail Tab association. New assertions cover both login variants at 393/320px and each chart unit. No tests or semantic gates were removed.
