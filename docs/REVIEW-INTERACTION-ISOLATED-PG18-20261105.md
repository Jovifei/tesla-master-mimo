# Complete isolated bridge/adapter gate

The visual-stage full run 42043436305 exposed missing package-relative source files in the container and an unconfigured optional PG18 compatibility fixture. These are test-runner setup defects, not permission to change production mount guards or suppress tests.

The runner now mounts this exact checkout read-only at /source and uses each compiled package's original working directory. It starts a disposable pinned PostgreSQL 18 image with no external network or published port, applies the existing pinned TeslaMate 4.3.0 migrations and repository synthetic fixtures, and creates the explicitly guarded battery-trend test database on loopback port 55432. Test containers share only that synthetic loopback namespace, not the host network. Source, credentials and schemas used are synthetic; no provider or production service is contacted.

All bridge test packages and the adapter execute, including the PG18 and TEMP-table boundary consumers. The full gate requires no skipped/failed cases, both PG18 consumers, the independent readonly mount case and writable-alias rejection, and preservation of the original fixture hash. The existing race checks remain. Cleanup targets only this job's named container and mktemp directory. Source/production mount validation is unchanged.

The terminal exact-SHA workflow and artifacts, not this note or prior focused success, determine delivery status. Original branches, Owner worktrees, phone data and production remain outside this operation.
