#!/usr/bin/env python3
"""Offline deterministic policy and SNI tests; no external network."""
import hashlib
import importlib.util
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest

FILE = Path(__file__).with_name("qualify-public-tls.py")
spec = importlib.util.spec_from_file_location("qualification", FILE)
qual = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qual)


class TlsQualificationTests(unittest.TestCase):
    def test_context_is_strict(self):
        ctx = qual.secure_context()
        self.assertTrue(ctx.check_hostname)
        self.assertEqual(ctx.verify_mode, ssl.CERT_REQUIRED)
        self.assertGreaterEqual(ctx.minimum_version, ssl.TLSVersion.TLSv1_2)

    def test_mismatched_peer_never_emits_health_request(self):
        factory_calls = []
        fake_cert = b"CI test-only public fixture"
        class FakeSocket:
            def getpeercert(self, binary_form=False):
                self.assert_binary = binary_form
                return fake_cert
        class FakeConnection:
            def __init__(self, host, timeout, context):
                factory_calls.append(("init", host, timeout, context.check_hostname, context.verify_mode))
                self.sock = FakeSocket()
            def connect(self): factory_calls.append(("connect",))
            def request(self, method, path): factory_calls.append(("request", method, path))
            def close(self): factory_calls.append(("close",))
        with self.assertRaisesRegex(qual.QualificationFailure, "UNEXPECTED_PEER"):
            qual.qualify("0" * 64, 1, connection_factory=FakeConnection)
        self.assertEqual(factory_calls[0][1:3], (qual.HOST, qual.TIMEOUT_SECONDS))
        self.assertFalse(any(row[0] == "request" for row in factory_calls))

    def test_matching_peer_gets_only_unauthenticated_health(self):
        seen = []
        payload = b"fixture-leaf"
        class FakeResponse:
            status = 200
            def close(self): pass
        class FakeConnection:
            def __init__(self, host, timeout, context): seen.append(("target", host)); self.sock = self
            def connect(self): pass
            def getpeercert(self, binary_form=False): return payload
            def request(self, method, path, *args, **kwargs): seen.append((method, path, args, kwargs))
            def getresponse(self): return FakeResponse()
            def close(self): pass
        digest = hashlib.sha256(payload).hexdigest()
        self.assertEqual(qual.qualify(digest, 2, connection_factory=FakeConnection), 2)
        self.assertEqual([row for row in seen if row[0] == "GET"],
                         [("GET", "/healthz", (), {})] * 2)
        self.assertEqual(len([row for row in seen if row[0] == "target"]), 2)

    def test_no_unbounded_or_unapproved_peer(self):
        for count in (0, 3, 100):
            with self.assertRaises(qual.QualificationFailure):
                qual.qualify(sample_count=count)
        for value in ("x", "A" * 64, "0" * 65):
            with self.assertRaises(qual.QualificationFailure):
                qual.qualify(expected_peer=value)
        self.assertEqual(qual.sanitized_error(ValueError("token=PRIVATE")), "UNEXPECTED_FAILURE")
        self.assertEqual(qual.main([]), 2)

    def test_health_non200_does_not_count_as_transport_pass(self):
        class Response:
            status = 503
            def close(self): pass
        class Fake:
            def __init__(self, host, timeout, context): self.sock = self
            def connect(self): pass
            def getpeercert(self, binary_form=False): return b"synthetic-leaf"
            def request(self, method, path): self.method, self.path = method, path
            def getresponse(self): return Response()
            def close(self): pass
        with self.assertRaisesRegex(qual.QualificationFailure, "HEALTH_STATUS"):
            qual.qualify(sample_count=1, connection_factory=Fake)

    def test_peer_switch_stops_second_http_request(self):
        values = iter((b"first-peer", b"different-peer"))
        requests = []
        class Response:
            status = 200
            def close(self): pass
        class Fake:
            def __init__(self, host, timeout, context): self.sock = self
            def connect(self): pass
            def getpeercert(self, binary_form=False): return next(values)
            def request(self, method, path): requests.append((method, path))
            def getresponse(self): return Response()
            def close(self): pass
        with self.assertRaisesRegex(qual.QualificationFailure, "PEER_CHANGED"):
            qual.qualify(sample_count=2, connection_factory=Fake)
        self.assertEqual(requests, [("GET", "/healthz")])

    def test_other_approved_hostname_has_sni_validation_but_no_http(self):
        selected = []
        class Fake:
            def __init__(self, host, timeout, context):
                selected.append((host, context.check_hostname))
                self.sock = self
            def connect(self): pass
            def getpeercert(self, binary_form=False): return b"fixture-leaf"
            def request(self, method, path): raise AssertionError("unexpected HTTP request")
            def close(self): pass
        hostname = "auth.teslalink.joviluma.com"
        self.assertEqual(qual.qualify(sample_count=2, host=hostname,
                                      tls_only=True, connection_factory=Fake), 2)
        self.assertEqual(selected, [(hostname, True), (hostname, True)])
        with self.assertRaises(qual.QualificationFailure):
            qual.qualify(host="unapproved.example.com", tls_only=True)
        with self.assertRaises(qual.QualificationFailure):
            qual.qualify(host=hostname, tls_only=False)

    def test_actual_selfissued_local_peer_rejected_by_tls_context(self):
        with tempfile.TemporaryDirectory() as td:
            crt, key = str(Path(td) / "temporary.crt"), str(Path(td) / "temporary.key")
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048",
                            "-nodes", "-days", "1", "-subj", "/CN=localhost",
                            "-keyout", key, "-out", crt], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=8)
            srv = socket.socket()
            srv.bind(("127.0.0.1", 0))
            srv.listen(1)
            srv.settimeout(3)
            port = srv.getsockname()[1]
            completed = []
            sni_seen = []
            def server():
                try:
                    sock, _ = srv.accept()
                    with sock:
                        server_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                        server_ctx.load_cert_chain(crt, key)
                        server_ctx.set_servername_callback(lambda tls_socket, name, tls_context: sni_seen.append(name))
                        try:
                            with server_ctx.wrap_socket(sock, server_side=True):
                                pass
                        except ssl.SSLError:
                            pass
                except OSError:
                    pass
                finally:
                    completed.append(True)
                    srv.close()
            t = threading.Thread(target=server, daemon=True)
            t.start()
            with socket.create_connection(("127.0.0.1", port), timeout=3) as sock:
                with self.assertRaises(ssl.SSLCertVerificationError):
                    with qual.secure_context().wrap_socket(sock, server_hostname=qual.HOST):
                        pass
            t.join(timeout=4)
            self.assertTrue(completed)
            self.assertEqual(sni_seen, [qual.HOST])


if __name__ == "__main__":
    unittest.main()
