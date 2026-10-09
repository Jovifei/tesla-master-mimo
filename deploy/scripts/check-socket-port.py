#!/usr/bin/env python3
"""Bounded TCP reachability check for the original public-entry self-test.
No shell evaluation, no route/token/body output; no environment changes.
"""
import ipaddress
import socket
import sys

PORTS = {443, 4000, 8080, 5432, 1883}
def valid_ip(value):
    try:
        parsed = ipaddress.IPv4Address(value)
        return str(parsed) == value
    except ipaddress.AddressValueError:
        return False

def main(argv):
    if len(argv) != 2 or not valid_ip(argv[0]):
        return 2
    try:
        port = int(argv[1])
    except ValueError:
        return 2
    if port not in PORTS:
        return 2
    try:
        with socket.create_connection((argv[0], port), timeout=2.5):
            return 0
    except (OSError, TimeoutError):
        return 1

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
