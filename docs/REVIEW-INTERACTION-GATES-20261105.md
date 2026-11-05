# Interaction stage: full-gate corrective review (2026-11-05)

The new owned App branch remains based on build 89 / 984196d. API35 at 4eac02c passed, but full Debug and Release each had 23 failures; that is not approval.

The original independent-family change was not reflected in older Oct28 consumer tests. The reviewed tests from 41c8477 are adopted with all scope/address/energy/parking assertions preserved: slow charging now runs as a separate real repository consumer, and refresh timeout blocks the drive endpoint actually requested. The same reviewed stable-local-identity normalization and reentrant LatestHistoryLoad fix, with their original real Room/loader tests, are included without merging either backend or changing the Owner branch.

The remaining energy failures are real: unqualified Fleet scalars must not become reported energy. The corrective source restores the existing raw-versus-qualified boundary, preserves raw receipts and independent explicitly labelled SOC estimates, rejects source-record conflicts in list/detail fallback, and refuses to erase a scope/window rejection during enrichment. New tests invoke the actual list/detail consumers with positive zero, independent estimate, missing/unknown and conflicting records.

No source test is disabled. The full gate is still required on the terminal fixed SHA. The bridge mount-isolation suite must run in its intended isolated Linux container, including explicit read-only and writable-alias fixtures; the mount guard must not be weakened to make a hosted host namespace pass. Synthetic execution never changes production, live bridges, device data or original development branches.
