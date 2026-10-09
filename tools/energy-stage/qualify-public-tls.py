#!/usr/bin/env python3
"""Two bounded, unauthenticated, certificate-validated health checks from THIS host.

No insecure fallback, system trust edits, token, redirects, response bodies,
certificate names, exception text, addresses or certificates in output.
The GitHub-hosted vantage is NOT evidence of Android/device TLS or Fleet data.
"""
import argparse
import hashlib
import http.client
import re
import socket
import ssl
import sys

HOST = "api.teslalink.joviluma.com"
HEALTH_PATH = "/healthz"
TIMEOUT_SECONDS = 5
ALLOWED_COUNTS = (1, 2)


class QualificationFailure(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def secure_context():
    context = ssl.create_default_context(purpose=ssl.Purpose.SERVER_AUTH)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    # Both checks are mandatory, irrespective of any ambient TLS environment.
    if context.verify_mode != ssl.CERT_REQUIRED or not context.check_hostname:
        raise QualificationFailure("POLICY")
    return context


def check_expected_peer(actual, expected):
    if expected is not None and actual != expected:
        raise QualificationFailure("UNEXPECTED_PEER")


def qualify(expected_peer=None, sample_count=2, connection_factory=None):
    if sample_count not in ALLOWED_COUNTS:
        raise QualificationFailure("POLICY")
    if expected_peer is not None and not re.fullmatch(r"[0-9a-f]{64}", expected_peer):
        raise QualificationFailure("POLICY")
    factory = connection_factory or http.client.HTTPSConnection
    first_peer = None
    for _ in range(sample_count):
        connection = factory(HOST, timeout=TIMEOUT_SECONDS, context=secure_context())
        try:
            # HTTPSConnection uses HOST as both the TCP target and TLS SNI.
            # secure_context verifies trusted chain, validity and HOST identity.
            connection.connect()
            certificate = connection.sock.getpeercert(binary_form=True)
            if not certificate:
                raise QualificationFailure("NO_VERIFIED_PEER")
            peer = hashlib.sha256(certificate).hexdigest()
            check_expected_peer(peer, expected_peer)
            if first_peer is not None and peer != first_peer:
                raise QualificationFailure("PEER_CHANGED")
            first_peer = peer
            # Only the public, non-authenticated health endpoint is allowed.
            connection.request("GET", HEALTH_PATH)
            response = connection.getresponse()
            status = response.status
            response.close()
            if status != 200:
                raise QualificationFailure("HEALTH_STATUS")
        finally:
            connection.close()
    return sample_count


def sanitized_error(exc):
    if isinstance(exc, QualificationFailure):
        return exc.code
    if isinstance(exc, (ssl.SSLCertVerificationError, ssl.CertificateError)):
        return "CERTIFICATE_VALIDATION"
    if isinstance(exc, ssl.SSLError):
        return "TLS_FAILURE"
    if isinstance(exc, (TimeoutError, socket.timeout)):
        return "TIMEOUT"
    if isinstance(exc, (OSError, http.client.HTTPException)):
        return "TRANSPORT"
    return "UNEXPECTED_FAILURE"


def main(argv=None):
    parser = argparse.ArgumentParser(description="Validated public TLS qualification (no credentials)")
    parser.add_argument("--live", action="store_true", help="Explicitly perform bounded public health reads")
    parser.add_argument("--expected-peer-sha256", help="Optional independently approved public DER leaf digest")
    parser.add_argument("--samples", type=int, default=2, choices=ALLOWED_COUNTS)
    args = parser.parse_args(argv)
    if not args.live:
        print("TLS_QUALIFICATION=NOT_RUN reason=LIVE_REQUIRED")
        return 2
    try:
        count = qualify(args.expected_peer_sha256, args.samples)
    except Exception as exc:
        print("TLS_QUALIFICATION=FAIL reason=" + sanitized_error(exc))
        return 1
    print("TLS_QUALIFICATION=PASS validated_health_samples=" + str(count) +
          " vantage=current_runner_only")
    return 0


if __name__ == "__main__":
    sys.exit(main())
