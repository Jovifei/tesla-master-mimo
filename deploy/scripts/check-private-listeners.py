#!/usr/bin/env python3
"""Classify local TCP listeners for protected ports; never print addresses.
Read 'ss -lntH' from stdin. A missing/malformed protected listener fails
closed, including public-only IPv4, IPv6 and wildcard binds. No network call.
"""
import ipaddress
import sys

PORTS = {4000, 8080, 5432, 1883, 18080, 18090}


def check(lines):
    for line in lines:
        columns = line.split()
        if len(columns) < 5:
            # An empty input is fine; an unparseable listener row is not.
            if line.strip():
                return False
            continue
        local = columns[3]
        if ":" not in local:
            return False
        host, port_str = local.rsplit(":", 1)
        try:
            port = int(port_str)
        except ValueError:
            # ss also displays nonnumeric service names in some setups.
            return False
        if port not in PORTS:
            continue
        host = host.strip("[]")
        if host in ("*", "0.0.0.0", "::"):
            return False
        try:
            address = ipaddress.ip_address(host.split("%", 1)[0])
        except ValueError:
            return False
        mapped = getattr(address, "ipv4_mapped", None)
        if not (address.is_loopback or (mapped is not None and mapped.is_loopback)):
            return False
    return True


if __name__ == "__main__":
    ok = check(sys.stdin)
    print("PRIVATE_LISTENERS=" + ("PASS" if ok else "FAIL"))
    sys.exit(0 if ok else 1)
