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
            exec "$@"
        """)
        write_executable(self.bin / "nginx", """
            #!/usr/bin/env bash
            if [[ "$1" == "-v" ]]; then exit 0; fi
            [[ "$1" == "-t" ]] || exit 2
            if grep -q BROKEN "$TLS_TEST_CONF/jourvolt.conf"; then exit 1; fi
            exit 0
        """)
        write_executable(self.bin / "systemctl", """
            #!/usr/bin/env bash
            [[ "$1" == "reload" && "$2" == "nginx" ]] || exit 2
            echo reload >> "$TLS_TEST_RELOADS"
            if grep -q RELOAD_FAIL "$TLS_TEST_CONF/jourvolt.conf"; then exit 1; fi
            exit 0
        """)
        self.env = dict(os.environ)
        self.env.update(PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        TLS_TEST_CONF=str(self.conf), TLS_TEST_RELOADS=str(self.log),
                        PUBLIC_IP="203.0.113.7", MATE_LINK_API_TOKEN="")
        self.files = [self.src / name for name in (
            "jourvolt.conf", "jourvolt-ssl.inc",
            "jourvolt-ssl.selfsigned.inc", "jourvolt-ssl.le.inc")]

    def run_tx(self, main=None):
        if main is not None:
            self.files[0].write_text(main)
        return subprocess.run(["bash", str(TX), str(self.conf)] +
                              [str(x) for x in self.files],
                              env=self.env, text=True, capture_output=True, timeout=12)

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

    def test_actual_transaction_reload_failure_restores_disk_and_service(self):
        result = self.run_tx("RELOAD_FAIL")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TLS_TRANSACTION=ROLLED_BACK", result.stdout)
        self.assert_restored()
        self.assertEqual(self.log.read_text().count("reload"), 2)

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

    def run_public(self, status="401", public_ip="203.0.113.7"):
        self.install_verify_fakes()
        env = dict(self.env, TLS_TEST_CAPABILITIES=status, PUBLIC_IP=public_ip,
                   TMPDIR=str(self.root))
        return subprocess.run(["bash", str(VERIFY)], text=True,
                              capture_output=True, env=env, timeout=15)

    def test_actual_verify_public_401_is_success_and_non401_is_hard_failure(self):
        baseline = self.run_public()
        self.assertEqual(baseline.returncode, 0, baseline.stdout + baseline.stderr)
        self.assertIn("VERIFY_PUBLIC:", baseline.stdout)
        for status in ("200", "302", "403", "500", "000"):
            result = self.run_public(status)
            self.assertEqual(result.returncode, 1, (status, result.stdout, result.stderr))
            self.assertIn("Unauthenticated capabilities", result.stdout)

    def test_invalid_public_ip_rejected_before_any_network_execution(self):
        flag = self.root / "SENTINEL"
        bad = "203.0.113.7;touch " + str(flag)
        result = self.run_public(public_ip=bad)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(flag.exists())
        self.assertIn("INVALID_PUBLIC_IP", result.stderr)
        self.assertNotIn("PUBLIC_IP=203", result.stdout + result.stderr)

    def test_setup_integration_invokes_transaction_not_direct_active_copy(self):
        source = ROOT_SETUP.read_text()
        self.assertIn('tls-nginx-transaction.sh', source)
        self.assertIn('qualify-nginx-le.sh', source)
        self.assertNotIn('sudo tee "${NGINX_CONF_DIR}/jourvolt.conf"', source)
        self.assertNotIn('sudo cp -a "${NGINX_CONF_DIR}/jourvolt-ssl.le.inc" "${NGINX_CONF_DIR}/jourvolt-ssl.inc"', source)


if __name__ == "__main__":
    unittest.main()
