#!/usr/bin/env python3
"""Synthetic CA/leaf issuance exercises the ACTUAL deploy cert validator.
No production certificates, user CA installs, raw subject/SAN or secrets in logs.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "deploy/scripts/qualify-nginx-le.sh"
HOSTS = ("teslalink.joviluma.com", "api.teslalink.joviluma.com",
         "auth.teslalink.joviluma.com")


class NginxCertValidation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.tmp.name)
        def cmd(*args):
            subprocess.run(list(args), cwd=cls.root, check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        cls.cmd = staticmethod(cmd)
        cmd("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-days", "3650", "-subj", "/CN=Isolated CI Root",
            "-keyout", str(cls.root / "root.key"),
            "-out", str(cls.root / "root.crt"))
        cmd("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes",
            "-subj", "/CN=api.teslalink.joviluma.com",
            "-keyout", str(cls.root / "leaf.key"),
            "-out", str(cls.root / "leaf.csr"))
        (cls.root / "index.txt").write_text("")
        (cls.root / "serial").write_text("1000\n")
        (cls.root / "newcerts").mkdir()
        (cls.root / "ca.cnf").write_text("""
[ca]
default_ca=issuer
[issuer]
database={root}/index.txt
serial={root}/serial
new_certs_dir={root}/newcerts
certificate={root}/root.crt
private_key={root}/root.key
default_md=sha256
policy=policy_any
x509_extensions=server_ext
unique_subject=no
[policy_any]
commonName=supplied
[server_ext]
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:teslalink.joviluma.com,DNS:api.teslalink.joviluma.com,DNS:auth.teslalink.joviluma.com
        """.format(root=cls.root))
        def sign(name, before=None, after=None):
            args=["openssl", "ca", "-batch", "-notext",
                  "-config", str(cls.root / "ca.cnf"),
                  "-in", str(cls.root / "leaf.csr"),
                  "-out", str(cls.root / (name + ".crt"))]
            if before is not None:
                args += ["-startdate", before, "-enddate", after]
            else:
                args += ["-days", "2"]
            cmd(*args)
            (cls.root / (name + ".pem")).write_bytes(
                (cls.root / (name + ".crt")).read_bytes() +
                (cls.root / "root.crt").read_bytes())
        sign("valid")
        sign("expired", "20000101000000Z", "20000102000000Z")
        sign("future", "20500101000000Z", "20500102000000Z")
        cmd("openssl", "genpkey", "-algorithm", "RSA",
            "-pkeyopt", "rsa_keygen_bits:2048",
            "-out", str(cls.root / "wrong.key"))
        (cls.root / "leaf-only.pem").write_bytes((cls.root / "valid.crt").read_bytes())
        cmd("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-days", "2", "-subj", "/CN=Untrusted CI Root",
            "-keyout", str(cls.root / "other.key"),
            "-out", str(cls.root / "other.crt"))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def qualify(self, chain, key="leaf.key", hosts=HOSTS, ca="root.crt"):
        env = dict(os.environ, SSL_CERT_FILE=str(self.root / ca),
                   SSL_CERT_DIR=str(self.root / "empty_trust"))
        return subprocess.run(["bash", str(SCRIPT),
                               str(self.root / chain), str(self.root / key), *hosts],
                              env=env, capture_output=True, text=True, timeout=6)

    def test_correct_chain_names_current_window_and_matching_private_key(self):
        result = self.qualify("valid.pem")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.strip(), "TLS_CERTIFICATE=VALID")

    def test_certbot_live_symlinks_preserve_valid_trust_and_key(self):
        # certbot live/ holds symlinks into the private archive directory.
        (self.root / "live-chain.pem").symlink_to(self.root / "valid.pem")
        (self.root / "live-key.pem").symlink_to(self.root / "leaf.key")
        result = self.qualify("live-chain.pem", key="live-key.pem")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_wrong_hostname_is_rejected(self):
        self.assertNotEqual(self.qualify("valid.pem", hosts=(HOSTS[0], "other.example.com", HOSTS[2])).returncode, 0)

    def test_untrusted_root_and_incomplete_chain_are_rejected(self):
        self.assertNotEqual(self.qualify("valid.pem", ca="other.crt").returncode, 0)
        self.assertNotEqual(self.qualify("leaf-only.pem").returncode, 0)

    def test_expired_and_not_yet_valid_are_rejected(self):
        self.assertNotEqual(self.qualify("expired.pem").returncode, 0)
        self.assertNotEqual(self.qualify("future.pem").returncode, 0)

    def test_key_mismatch_and_missing_file_are_rejected(self):
        self.assertNotEqual(self.qualify("valid.pem", key="wrong.key").returncode, 0)
        self.assertNotEqual(self.qualify("not-there.pem").returncode, 0)


if __name__ == "__main__":
    unittest.main()
