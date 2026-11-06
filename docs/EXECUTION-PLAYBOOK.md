# MateLink execution playbook

> Mandatory playbook. Read it before every commit, Release build, production-link check, or compile-error fix. Pair it with `AI-RULES.md` and `CURRENT-PRODUCTION-CONFIG.md`.

## Mandatory pre-commit checklist

Run in the exact source tree that will be committed:

```powershell
git status --short --branch
git diff --check
git diff --stat
```

Then confirm:

1. The staged path list contains only intended changes; unrelated dirty files stay unstaged.
2. For documentation changes, run the Markdown link check and require `BROKEN_COUNT=0`.
3. For Android source changes, run focused tests, then `:app:testDebugUnitTest`, then `:app:lintDebug`.
4. For Web source changes, run `npm run lint` and `npm run build` from `web_matelink`.
5. For Release/build changes, inspect generated `BuildConfig`, package, signature, and artifact hash.
6. Update `tasks/todo.md` with actual results and unresolved risks.
7. Never claim a test that was not run in this source tree.

Stage explicit paths and verify:

```powershell
git add <explicit-paths>
git diff --cached --check
git diff --cached --stat
git diff --cached --name-status
```

Commit only after all listed gates pass, or after recording the limitation in `tasks/todo.md`.

## Mandatory Android Release sequence

1. Read `CURRENT-PRODUCTION-CONFIG.md`.
2. Run focused tests for changed Android files.
3. Run from `android/`:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest --console=plain
   .\gradlew.bat :app:lintDebug --console=plain
   ```

4. Build with current production values and approved signing properties:

   ```powershell
   .\build-pilot-apk.ps1 `
     -ApiBaseUrl "https://api.teslalink.joviluma.com/" `
     -AuthHost "auth.teslalink.joviluma.com" `
     -PublicInfoBaseUrl "https://auth.teslalink.joviluma.com" `
     -SigningPropertiesPath "E:\Claude_allow\matelink-release.properties" `
     -SkipLint
   ```

5. Verify generated Release `BuildConfig.JOURVOLT_API_BASE_URL`.
6. Verify APK package, certificate, and SHA-256.
7. Verify the installed phone certificate before installation.
8. Use only `adb install -r APK`.
9. Record `firstInstallTime` before and after; it must not change.
10. Capture read-only home/charging screenshots without touching vehicle controls.

## Production-link diagnosis sequence

1. Read `CURRENT-PRODUCTION-CONFIG.md`.
2. Verify the endpoint with the production route:

   ```powershell
   curl.exe --ssl-no-revoke --resolve api.teslalink.joviluma.com:443:120.55.64.11 `
     https://api.teslalink.joviluma.com/healthz
   ```

3. Check `/readyz`.
4. If authorized, SSH and inspect only the relevant nginx block and container.
5. Do not use local Docker, local DNS, or proxy fake-IP answers as production evidence.
6. Restore only the affected service; never restart or modify unrelated containers.
7. Record endpoint, server build SHA, and phone visual result.

## Compile/build error log

Append every non-trivial compile, lint, test, build, or deployment error. Never rewrite historical rows.

| Date | Stage | Error | Root cause | Resolution | Prevention |
|---|---|---|---|---|---|
| 2026-11-06 | `:app:lintDebug` | `SuspiciousIndentation` in `DriveDetailScreen.kt` | Composable call was indented as if it continued the preceding `val detail` statement | Reindent the call; rerun `lintDebug` to PASS | Keep call-site indentation tied to the surrounding `if` block |
| 2026-11-06 | Release | Phone stayed on `同步中` / `无法连接车辆服务` | Release used legacy `https://api.jourvolt.com/` instead of the current production API | Rebuild with `https://api.teslalink.joviluma.com/`, verify generated `BuildConfig`, reinstall same-signed APK | Never infer the API URL from defaults or memory; read `CURRENT-PRODUCTION-CONFIG.md` |
| 2026-11-06 | Production diagnosis | Workstation DNS returned `198.18.x.x`; TLS failed | FlClash TUN fake-IP DNS/routing intercepted the endpoint | Use `curl --resolve` and SSH; API returned 200 | Treat `198.18.x.x` as workstation proxy evidence, not production state |
| 2026-11-06 | Server compose | `matelink-selfhost` images missing; Docker Hub timeout | Self-hosted compose had no running containers and required images were absent | Cloud production remained healthy; self-hosted restoration deferred | Check server image availability before promising recovery; do not use local Docker as production |
| 2026-11-07 | Documentation link repair | Automated rewrite produced nested Markdown in `APP-ISSUE-VERSIONS.md` | String was mutated while saved match indexes remained valid | Restore file from HEAD and rewrite one match at a time with an advancing offset | Never mutate a string while iterating saved match indexes |

## Documentation organization rule

- Root `docs/` contains only authority and active index files.
- Historical evidence lives in function folders described in `README.md`.
- Historical documents never override current authority files.
- Moving documents requires updating relative links and running the link check.
- Do not delete historical documents without Jovi's explicit approval.
