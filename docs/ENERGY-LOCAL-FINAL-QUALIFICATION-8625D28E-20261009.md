# Energy stage fixed-source local receipt — 2026-10-09

## Product, builds and actual phone
- Fixed product SHA `8625d28ecfaec920d39d8b6a56afa50e9854ae7a`; tree `9931e57c56a12d22d20c2482dd5eb9af5bb0d0fe`; parent `80c891825da6cd6592df075e86788cdb81b449af`.
- Exact CI https://github.com/Jovifei/tesla-master-mimo/actions/runs/37825196099 all jobs SUCCESS, including Android job113476130165. Go/isolatedPG295, race/resource/Web and10 source audits succeeded.
- Actual independent local signed build exit0, BUILD SUCCESSFUL in7m50s: Debug719/0fail/0skip; Release719/0fail/8skip; lintDebug0error/262warning/9info; lintRelease0error/242warning/8info; R8/signed Release passed.
- Same original signer, nondebuggable `com.matelink`2.1.27/build46. APK SHA256 `DD1EC28BC7E61D6F729DFD0F707F4C7A8086744E942AE97C3EB87D9E16E6B0FE`; actual installed APK hash independently matches.
- Only install-r; firstInstallTime unchanged. Session/cache retained, no data clear or uninstall. MainActivity10-second bounded sample alive/FATAL0/ANR0. No vehicle wake, fabricated trip or production deployment.
- Current Go subtree identical to previously locally qualified84abb526: local Go295/race/vet/modverify/build evidence reused for identical Go source, not claimed re-executed at8625. Current fixed-SHA CI Go independently succeeds.

## Real device failure, not closed by a transient200
Source observation times are explicit and are not timeless live data:
- 2026-10-08T18:54Z / Oct9 02:54China diagnostic install/launch.
- Around18:59Z phone ordinary HTTPS health returned200/verify0, but around19:03:58Z actual app history_context again failed before HTTP: `category=tls tls_cause=certificate_path_validation`.
- Around19:06–19:09Z ordinary validated phone curl again failed60/verify18. A bounded unauthenticated health handshake trace, with certificate validation enabled and no insecure flag, was parsed privately. ClientHello sent the correct expected SNI. The failed peer returned a single433-byte self-issued certificate, different from the expected production leaf, not issued by expected Let's Encrypt and not covering the API hostname. No certificate subject/SAN, raw trace or private identifiers are published.
- Desktop/server public TLS1.2 and1.3 had the expected production chain; existing valid RootX1 is present on the phone. This proves inconsistent peer/certificate-path observations, not the exact router/proxy/server component. Do not install a new CA or bypass TLS to hide it.
- User was asked about HTTPS inspection and permission for a temporary Wi-Fi→cellular→restore diagnostic. No answer or network change was assumed.
- Real natural Oct7 record:3100 source points, full power-window coverage, no duplicate conflicts/large gaps,3100 SOC observations. Latest natural source record1596 points also fully covered. These were fresh, scoped read-only cloud observations19:02Z; no source/DB writes. During TLS failure the phone used cache: SOC/sample max speed remained visible, energy unavailable, cloud-sync warning present. This is not accepted as successful fresh energy display.
- Ordinary current Nginx access-log content read was permission-denied. No escalation or container/host-log bypass. Health/ready200 and APIrunning/OOMfalse/restart0 do not close authenticated reads.

## Repository/knowledge and remaining gates
- Thirteen obsolete cache/failed-log items totaling1,784,862,693bytes recoverably moved out of worktrees. No physical disk deletion claimed. Effective tests/fixtures, schemas, migrations, APK/report evidence and rollback packages retained; tracked artifact scan found no obsolete binaries to delete.
- Owner main remains `f0dcd447dfcfdaf3a479f78eebd4077cd2e52a45`; original dirty source and notes preserved privately. Three-way integration previews preserve the Owner address-format change and both note histories; no destructive reset/clean/stash or blind main sync.
- Canonical Docs index links this stage's official energy-method specification, source handoff and TLS troubleshooting. Raw logs/traces, signatures/private settings and identities stay local and out of Git.
- Main merge remains pending actual authenticated-history qualification or an explicitly accepted, independently established environment gate. CI/install are PASS; full product acceptance is not.
- Fleet first events, passive natural notifications, second real user and unavailable old parking energy remain separately pending. No old sixSOC/7113TPMS backfill, DB/schema write, API/bridge upgrade, proxy/VPN/DNS/trust change.
- Hub service remains connection-refused; original pending event retained, no false successful Hub report.

## Next action
After Jovi's network diagnostic decision, perform the bounded permitted test and restore its setting, then retry normal authenticated list/detail/charging reads and private scalar/oracle checks. Return consolidated evidence to the same remote full-stage review; repair only demonstrated defects remotely. Merge verified PR17 and preserve Owner overlays only after the actual gates close.
