#!/usr/bin/env python3
"""Execute actual deployment and verification scripts inside a fake, isolated
filesystem/command namespace. No host Nginx, credentials or public traffic.
"""
import os
import pathlib
import subprocess
import tempfile
import textwrap
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
TX = REPO / "deploy/scripts/tls-nginx-transaction.sh"
VERIFY = REPO / "deploy/scripts/verify-public.sh"
ROOT_SETUP = REPO / "deploy/scripts/setup-root.sh"


def write_executable(path, text):
    path.write_text(textwrap.dedent(text).lstrip(), encoding="utf-8")
    path.chmod(0o755)


class ActualTlsScriptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.conf = self.root / "conf.d"
        self.conf.mkdir()
        self.src = self.root / "candidate"
        self.src.mkdir()
        self.bin = self.root / "mockbin"
        self.bin.mkdir()
        self.log = self.root / "reloads.txt"
        self.log.write_text("")
        for name in ("jourvolt.conf", "jourvolt-ssl.inc",
                     "jourvolt-ssl.selfsigned.inc", "jourvolt-ssl.le.inc"):
            (self.conf / name).write_text("ORIGINAL_" + name)
            (self.src / name).write_text("NEW_" + name)
        (self.conf / "other-project.conf").write_text("OWNER_UNTOUCHED")
        write_executable(self.bin / "sudo", """
            #!/usr/bin/env bash
            # Fail ONLY the restore direction, never the preflight backup.
            if [[ "${TLS_TEST_FAIL_RESTORE:-}" == "copy" && "$1" == cp &&
                  "$2" == -a && "$4" == *".jourvolt-tls-rollback."* &&
                  "$5" == "$TLS_TEST_CONF/"* ]]; then exit 9; fi
            if [[ "${TLS_TEST_FAIL_RESTORE:-}" == "remove" && "$1" == rm &&
                  "$*" == *"$TLS_TEST_CONF/jourvolt-ssl.inc"* ]]; then exit 9; fi
            exec "$@"
        """)
        write_executable(self.bin / "nginx", """
            #!/usr/bin/env bash
            if [[ "$1" == "-v" ]]; then exit 0; fi
            [[ "$1" == "-t" ]] || exit 2
            if grep -q BROKEN "$TLS_TEST_CONF/jourvolt.conf"; then exit 1; fi
            if [[ "${TLS_TEST_NGINX_FAIL:-}" == 1 ]]; then exit 1; fi
            if grep -q MORE_WARNINGS "$TLS_TEST_CONF/jourvolt.conf"; then
                echo 'nginx: [warn] new overlap' >&2
            fi
            exit 0
        """)
        write_executable(self.bin / "systemctl", """
            #!/usr/bin/env bash
            [[ "$1" == "reload" && "$2" == "nginx" ]] || exit 2
            echo reload >> "$TLS_TEST_RELOADS"
            if grep -q RELOAD_FAIL "$TLS_TEST_CONF/jourvolt.conf" ||
                 [[ "${TLS_TEST_RELOAD_FAIL:-}" == 1 ]]; then exit 1; fi
            exit 0
        """)
        self.env = dict(os.environ)
        self.env.update(PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        TLS_TEST_CONF=str(self.conf), TLS_TEST_RELOADS=str(self.log),
                        PUBLIC_IP="203.0.113.7", MATE_LINK_API_TOKEN="")
        self.files = [self.src / name for name in (
            "jourvolt.conf", "jourvolt-ssl.inc",
            "jourvolt-ssl.selfsigned.inc", "jourvolt-ssl.le.inc")]

    def run_tx(self, main=None, flags=None):
        if main is not None:
            self.files[0].write_text(main)
        env = dict(self.env, **(flags or {}))
        return subprocess.run(["bash", str(TX), str(self.conf)] +
                              [str(x) for x in self.files],
                              env=env, text=True, capture_output=True, timeout=12)

    def assert_restored(self):
        for name in ("jourvolt.conf", "jourvolt-ssl.inc",
                     "jourvolt-ssl.selfsigned.inc", "jourvolt-ssl.le.inc"):
            self.assertEqual((self.conf / name).read_text(), "ORIGINAL_" + name)
        self.assertEqual((self.conf / "other-project.conf").read_text(), "OWNER_UNTOUCHED")

    def test_actual_transaction_succeeds_and_second_apply_is_noop(self):
        result = self.run_tx()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("TLS_TRANSACTION=APPLIED", result.stdout)
        self.assertEqual(self.log.read_text().count("reload"), 1)
        second = self.run_tx()
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertIn("TLS_TRANSACTION=NOOP", second.stdout)
        self.assertEqual(self.log.read_text().count("reload"), 1)
        self.assertEqual((self.conf / "other-project.conf").read_text(), "OWNER_UNTOUCHED")
        self.assertTrue(list(self.conf.glob(".jourvolt-tls-rollback.*")))

    def test_actual_transaction_nginx_config_failure_restores_all_files(self):
        result = self.run_tx("BROKEN")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TLS_TRANSACTION=ROLLED_BACK", result.stdout)
        self.assert_restored()
        self.assertEqual(self.log.read_text().count("reload"), 1)
        self.assertTrue(list(self.conf.glob(".jourvolt-tls-rollback.*")))

    def test_new_nginx_warning_triggers_full_rollback(self):
        result = self.run_tx("MORE_WARNINGS")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("NEW_NGINX_WARNINGS", result.stdout)
        self.assertIn("TLS_TRANSACTION=ROLLED_BACK", result.stdout)
        self.assert_restored()
        self.assertEqual(self.log.read_text().count("reload"), 1)

    def test_actual_transaction_reload_failure_restores_disk_and_service(self):
        result = self.run_tx("RELOAD_FAIL")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TLS_TRANSACTION=ROLLED_BACK", result.stdout)
        self.assert_restored()
        self.assertEqual(self.log.read_text().count("reload"), 2)

    def test_restore_copy_failure_must_report_operator_not_false_rolled_back(self):
        result = self.run_tx("BROKEN", {"TLS_TEST_FAIL_RESTORE": "copy"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TLS_TRANSACTION=ROLLBACK_REQUIRES_OPERATOR", result.stdout)
        self.assertNotIn("TLS_TRANSACTION=ROLLED_BACK", result.stdout)
        self.assertEqual(self.log.read_text(), "")
        self.assertEqual((self.conf / "other-project.conf").read_text(), "OWNER_UNTOUCHED")

    def test_restore_remove_failure_must_report_operator_not_false_rolled_back(self):
        (self.conf / "jourvolt-ssl.inc").unlink()
        result = self.run_tx("BROKEN", {"TLS_TEST_FAIL_RESTORE": "remove"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TLS_TRANSACTION=ROLLBACK_REQUIRES_OPERATOR", result.stdout)
        self.assertNotIn("TLS_TRANSACTION=ROLLED_BACK", result.stdout)
        self.assertEqual(self.log.read_text(), "")
        self.assertEqual((self.conf / "other-project.conf").read_text(), "OWNER_UNTOUCHED")

    def test_existing_le_postissuance_failure_cannot_claim_activation(self):
        helper = REPO / "deploy/scripts/reload-qualified-le.sh"
        self.assertIn("reload-qualified-le.sh", ROOT_SETUP.read_text())
        for failflag, reason in (
            ({"TLS_TEST_NGINX_FAIL": "1"}, "NGINX_TEST_FAILED"),
            ({"TLS_TEST_RELOAD_FAIL": "1"}, "RELOAD_FAILED")
        ):
            self.log.write_text("")
            result = subprocess.run(["bash", str(helper)],
                capture_output=True, text=True, timeout=5,
                env=dict(self.env, **failflag))
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(reason, result.stdout)
            self.assertNotIn("VALIDATED_RELOAD", result.stdout)
        self.log.write_text("")
        valid = subprocess.run(["bash", str(helper)],
            capture_output=True, text=True, timeout=5, env=self.env)
        self.assertEqual(valid.returncode, 0)
        self.assertIn("TLS_LE_ACTIVATION=VALIDATED_RELOAD", valid.stdout)
        self.assertEqual(self.log.read_text().count("reload"), 1)

    def test_actual_transaction_rejects_symlink_without_tampering(self):
        owned = self.conf / "jourvolt-ssl.inc"
        owned.unlink()
        (self.root / "OWNER_OUTSIDE").write_text("DO_NOT_TOUCH")
        owned.symlink_to(self.root / "OWNER_OUTSIDE")
        result = self.run_tx()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SYMLINK_REJECTED", result.stdout)
        self.assertEqual((self.root / "OWNER_OUTSIDE").read_text(), "DO_NOT_TOUCH")
        self.assertEqual(self.log.read_text(), "")

    def install_verify_fakes(self):
        write_executable(self.bin / "nslookup", """
            #!/usr/bin/env bash
            printf 'Server: test\\nAddress: 223.5.5.5\\nName: test\\nAddress: 203.0.113.7\\n'
        """)
        write_executable(self.bin / "ss", """
            #!/usr/bin/env bash
            case "${TLS_TEST_SS_MODE:-empty}" in
              loopback)
                printf 'LISTEN 0 128 127.0.0.1:18080 0.0.0.0:*\\n'
                printf 'LISTEN 0 128 [::1]:18090 [::]:*\\n' ;;
              specific)
                printf 'LISTEN 0 128 203.0.113.7:18080 0.0.0.0:*\\n' ;;
              wildcard)
                printf 'LISTEN 0 128 0.0.0.0:18090 0.0.0.0:*\\n' ;;
              ipv6)
                printf 'LISTEN 0 128 [2001:db8::9]:18090 [::]:*\\n' ;;
              mapped)
                printf 'LISTEN 0 128 [::ffff:127.0.0.1]:18080 [::]:*\\n' ;;
              *) : ;;
            esac
            exit 0
        """)
        write_executable(self.bin / "python3", """
            #!/usr/bin/env bash
            case "$1" in
              -) exec /usr/bin/python3 "$@" ;;
              */qualify-public-tls.py)
                  echo 'TLS_QUALIFICATION=PASS validated_health_samples=2'
                  exit 0 ;;
              */check-socket-port.py)
                  if [[ "${@: -1}" == "443" ]]; then exit 0; fi
                  if [[ "$2" == "203.0.113.7" &&
                        "${TLS_TEST_PUBLIC_ONLY_PORT:-}" == "${@: -1}" ]]; then exit 0; fi
                  exit 1 ;;
              *) exec /usr/bin/python3 "$@" ;;
            esac
        """)
        write_executable(self.bin / "curl", """
            #!/usr/bin/env bash
            output=''
            url=''
            while [[ "$#" -gt 0 ]]; do
              case "$1" in
                -o) output="$2"; shift 2 ;;
                -w|-H|--max-time|--max-filesize) shift 2 ;;
                http*) url="$1"; shift ;;
                *) shift ;;
              esac
            done
            if [[ "$url" == http://* ]]; then
               code=301
            elif [[ "$url" == *"/api/matelink/v1/capabilities" ]]; then
               code="${TLS_TEST_CAPABILITIES:-401}"
            else
               code=200
            fi
            if [[ -n "$output" ]]; then
               printf '<html>MateLink com.matelink</html>' > "$output"
            fi
            printf '%s' "$code"
        """)

    def run_public(self, status="401", public_ip="203.0.113.7",
                   public_only_port=None, ss_mode="empty"):
        self.install_verify_fakes()
        env = dict(self.env, TLS_TEST_CAPABILITIES=status, PUBLIC_IP=public_ip,
                   TLS_TEST_PUBLIC_ONLY_PORT=str(public_only_port or ""),
                   TLS_TEST_SS_MODE=ss_mode, TMPDIR=str(self.root))
        return subprocess.run(["bash", str(VERIFY)], text=True,
                              capture_output=True, env=env, timeout=15)

    def test_actual_verify_public_401_is_success_and_non401_is_hard_failure(self):
        baseline = self.run_public()
        self.assertEqual(baseline.returncode, 0, baseline.stdout + baseline.stderr)
        self.assertIn("VERIFY_PUBLIC:", baseline.stdout)
        for status in ("200", "302", "403", "500"):
            result = self.run_public(status)
            self.assertEqual(result.returncode, 1, (status, result.stdout, result.stderr))
            self.assertIn("Unauthenticated capabilities", result.stdout)
        offline = self.run_public("000")
        self.assertEqual(offline.returncode, 1)
        self.assertIn("[FAIL]", offline.stdout)
        self.assertIn("VERIFY_PUBLIC:", offline.stdout)

    def test_public_only_private_port_is_detected_without_loopback_listener(self):
        result = self.run_public(public_only_port=4000)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("Private port unexpectedly accessible externally", result.stdout)
        self.assertIn("VERIFY_PUBLIC:", result.stdout)

    def test_local_listener_inventory_rejects_specific_ipv4_ipv6_and_wildcards(self):
        for mode in ("specific", "wildcard", "ipv6"):
            result = self.run_public(ss_mode=mode)
            self.assertEqual(result.returncode, 1, (mode, result.stdout, result.stderr))
            self.assertIn("Nonloopback or unverified protected listener present",
                          result.stdout)
            self.assertIn("VERIFY_PUBLIC:", result.stdout)
        for mode in ("empty", "loopback", "mapped"):
            result = self.run_public(ss_mode=mode)
            self.assertEqual(result.returncode, 0, (mode, result.stdout, result.stderr))
            self.assertIn("PRIVATE_LISTENERS=PASS", result.stdout)

    def test_private_listener_classifier_reads_only_existing_local_ss_snapshot(self):
        helper = REPO / "deploy/scripts/check-private-listeners.py"
        fixtures = [
            ("LISTEN 0 128 127.0.0.1:18080 0.0.0.0:*\\n", True),
            ("LISTEN 0 128 [::1]:18090 [::]:*\\n", True),
            ("LISTEN 0 128 [::ffff:127.0.0.1]:18090 [::]:*\\n", True),
            ("LISTEN 0 128 203.0.113.7:18080 0.0.0.0:*\\n", False),
            ("LISTEN 0 128 [2001:db8::9]:18090 [::]:*\\n", False),
            ("LISTEN 0 128 0.0.0.0:5432 0.0.0.0:*\\n", False),
            ("LISTEN 0 128 [::]:4000 [::]:*\\n", False)
        ]
        for listing, accepted in fixtures:
            result = subprocess.run(["/usr/bin/python3", str(helper)], input=listing,
                                    text=True, capture_output=True, timeout=4)
            self.assertEqual(result.returncode, 0 if accepted else 1)
            self.assertEqual(result.stdout.strip(),
                             "PRIVATE_LISTENERS=" + ("PASS" if accepted else "FAIL"))
            self.assertNotIn("203.0.113", result.stdout)

    def test_invalid_public_ip_rejected_before_any_network_execution(self):
        flag = self.root / "SENTINEL"
        bad = "203.0.113.7;touch " + str(flag)
        result = self.run_public(public_ip=bad)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(flag.exists())
        self.assertIn("INVALID_PUBLIC_IP", result.stderr)
        self.assertNotIn("PUBLIC_IP=203", result.stdout + result.stderr)

    def test_installed_commentless_tls_directives_match_but_extras_fail(self):
        canonical = REPO / "deploy/nginx/jourvolt-ssl.le.inc"
        active = self.conf / "jourvolt-ssl.inc"
        active.write_text(
            "ssl_certificate /etc/letsencrypt/live/jourvolt/fullchain.pem;\n"
            "ssl_certificate_key /etc/letsencrypt/live/jourvolt/privkey.pem;\n"
        )
        command = ["bash", str(REPO / "deploy/scripts/nginx-include-match.sh"),
                   str(active), str(canonical)]
        good = subprocess.run(command, env=self.env, capture_output=True, timeout=4)
        self.assertEqual(good.returncode, 0)
        active.write_text(active.read_text() + "ssl_verify_client off;\n")
        rejected = subprocess.run(command, env=self.env, capture_output=True, timeout=4)
        self.assertNotEqual(rejected.returncode, 0)

    def test_setup_integration_invokes_transaction_not_direct_active_copy(self):
        source = ROOT_SETUP.read_text()
        self.assertIn('tls-nginx-transaction.sh', source)
        self.assertIn('qualify-nginx-le.sh', source)
        self.assertIn('nginx-include-match.sh', source)
        self.assertNotIn('sudo tee "${NGINX_CONF_DIR}/jourvolt.conf"', source)
        self.assertNotIn('sudo cp -a "${NGINX_CONF_DIR}/jourvolt-ssl.le.inc" "${NGINX_CONF_DIR}/jourvolt-ssl.inc"', source)


if __name__ == "__main__":
    unittest.main()
