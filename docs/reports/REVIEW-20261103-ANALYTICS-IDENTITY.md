# Analytics cold/online identity publication follow-up

Scope: same authorized App branch, following the App/Mini review stage. The source review found that cold cloud contexts use an opaque persisted origin/account/car mapping, whereas authenticated discovery returns a UID-bearing identity string. Efficiency and range correctly compare page contexts, but this representation change could reject fresh same-car data after a cold Room publication.

AnalysisHistoryRepository now validates the original read proof and current UID-to-local mapping, then publishes the canonical persisted context used by page capture. It checks the local vehicle key before and after publication; it does not compare only a numeric remote car ID or authorize a cached context for remote reads. A changed vehicle UID still allocates another local key and invalidates the old page.

AnalysisCloudPublicationRoomTest exercises actual persisted identity, database close/reopen, UnifiedHistoryRepository, AnalysisHistoryRepository and EfficiencyViewModel. The positive case requires a second remote trip and changed weighted efficiency to replace the cached projection after same-UID discovery; the negative case changes the UID behind the same remote ID, requires old data to be removed, retains the old archive, and reloads only the replacement vehicle. The test is included in the fast gate as well as both full JVM suites.

No storage schema, credential, history repair, device operation or production service changed. Final source approval remains conditional on all same-SHA CI gates and the separate device/provider/production matrix in LOCAL-RECEIVE-APP-MINI-20261103.md.
