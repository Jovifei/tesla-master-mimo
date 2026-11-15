# MateLink AI execution rules

> This file is the current execution contract for any AI or automation working in this repository. Read it before any build, deploy, server inspection, package installation, or code/config change. Historical reports do not override this file.

## Non-negotiable identity

- Address the owner as **Jovi**.
- The Android production package identity is `com.matelink`, not `com.matelink.test.mock`.
- Never uninstall, clear data, reset, or overwrite Jovi's phone data. The only allowed phone mutation is same-signature `adb install -r` after signature/package identity checks.

## Production link rules

1. The cloud API is always:

   ```text
   https://api.teslalink.joviluma.com/
   ```

2. The App Link/auth host is always:

   ```text
   auth.teslalink.joviluma.com
   ```

3. Never use the legacy default `https://api.jourvolt.com/` for a Release package.
4. Never treat the historical phone self-host address `http://192.168.0.104:18080` as the current cloud production link.
5. Before building a Release, verify the exact arguments and then verify `JOURVOLT_API_BASE_URL` in the generated Release `BuildConfig`.

## Server inspection order

1. Inspect the production server first. MateLink services are deployed on the server, not on Jovi's local Docker.
2. Check the current endpoint, then server nginx, then the server-local upstream, then the relevant container.
3. Do not touch unrelated containers or services. `star-photo`, ETF, medicine-box, and other projects are out of scope.
4. Do not diagnose production from local Docker state or from a workstation proxy's fake-IP DNS result.
5. If workstation DNS returns `198.18.x.x`, treat it as local TUN/proxy behavior and verify the real endpoint with `curl --resolve` or by SSH to the production server.

## Release build gate

Use the current production values:

```powershell
cd E:\project\tesla_master\app_mimo\android
.\build-pilot-apk.ps1 `
  -ApiBaseUrl "https://api.teslalink.joviluma.com/" `
  -AuthHost "auth.teslalink.joviluma.com" `
  -PublicInfoBaseUrl "https://auth.teslalink.joviluma.com" `
  -SigningPropertiesPath "E:\Claude_allow\matelink-release.properties" `
  -SkipLint
```

For a release candidate, do not use `-SkipLint` unless `lintDebug` has already passed for that exact source tree.

Required checks after build:

- APK package is `com.matelink`.
- APK is non-debuggable.
- APK certificate SHA-256 matches the installed package certificate.
- Generated Release `BuildConfig.JOURVOLT_API_BASE_URL` is exactly `https://api.teslalink.joviluma.com/`.
- Record the APK SHA-256 before installation.

## Evidence rules

- A successful build or 200 health check is not production acceptance.
- Phone visual evidence must be captured without clicking vehicle-control buttons.
- Separate source tests, build, signed artifact, installation, provider health, and human/phone acceptance.
- Keep historical documents as evidence only; they are not current configuration.
