#!/usr/bin/env python3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse
from collections import Counter
import json
from threading import Lock
import time
from urllib.parse import parse_qs, urlparse


VALID_TOKEN = "synthetic-qualification-key"
SCENARIOS = {
    "normal",
    "auth_401",
    "timeout",
    "no_drives",
    "no_charges",
    "parked_partial",
    # P0.7 compatibility aliases.
    "empty",
    "missing",
}
TIMEOUT_SECONDS = 35
CONNECTION_TEST_PATHS = {"/api/ping"}


def body_for(path: str, scenario: str):
    if path == "/api/ping" or path == "/api/readyz":
        return {"response": "ok"}
    if path == "/api/matelink/v1/capabilities":
        return {
            "data": {"adapter_version": "p0.7-fixture", "features": ["snapshot", "parked_detail"]}
        }
    if path == "/api/v1/cars":
        return {
            "data": {
                "cars": [
                    {
                        "car_id": 101,
                        "name": "Synthetic Qualification Vehicle",
                        "car_details": {"model": "Y", "trim_badging": "P", "efficiency": 0.16},
                        "car_exterior": {
                            "exterior_color": "Pearl White",
                            "wheel_type": "Test Wheel",
                        },
                        "teslamate_stats": {"total_charges": 1, "total_drives": 2},
                    }
                ]
            }
        }
    if path == "/api/v1/cars/101/status" or path == "/api/v1/cars/1/status":
        return {
            "data": {
                "status": {
                    "display_name": "Synthetic Qualification Vehicle",
                    "state": "online",
                    "odometer": 12345.6,
                    "battery_details": {
                        "battery_level": 68,
                        "usable_battery_level": 66,
                        "rated_battery_range": 312.4,
                    },
                    "charging_details": {"plugged_in": False, "charging_state": "Disconnected"},
                    "climate_details": {"inside_temp": 22.0, "outside_temp": 18.5},
                },
                "units": {
                    "unit_of_length": "km",
                    "unit_of_temperature": "C",
                    "unit_of_pressure": "bar",
                },
            }
        }
    if path == "/api/matelink/v1/cars/101/snapshot" or path == "/api/matelink/v1/cars/1/snapshot":
        return {
            "data": {
                "status": body_for("/api/v1/cars/101/status", scenario)["data"]["status"],
                "units": {
                    "unit_of_length": "km",
                    "unit_of_temperature": "C",
                    "unit_of_pressure": "bar",
                },
                "observed_at": "2026-07-18T00:00:00Z",
                "source": "fixture",
                "field_sources": {"battery": "fixture"},
            }
        }
    if path == "/api/v1/cars/101/drives" or path == "/api/v1/cars/1/drives":
        if scenario in {"empty", "no_drives"}:
            return {"data": {"drives": []}}
        return {
            "data": {
                "drives": [
                    {
                        "drive_id": 702,
                        "start_date": "2026-07-18T10:00:00Z",
                        "end_date": "2026-07-18T10:25:00Z",
                        "start_address": "Fixture Office",
                        "end_address": "Fixture Lab",
                        "odometer_details": {
                            "odometer_start": 1018.0,
                            "odometer_end": 1030.0,
                            "odometer_distance": 12.0,
                        },
                        "duration_min": 25,
                        "battery_details": {"start_battery_level": 73, "end_battery_level": 69},
                        "energy_consumed_net": 2.2,
                    },
                    {
                        "drive_id": 701,
                        "start_date": "2026-07-18T08:00:00Z",
                        "end_date": "2026-07-18T08:30:00Z",
                        "start_address": "Fixture Garage",
                        "end_address": "Fixture Office",
                        "odometer_details": {
                            "odometer_start": 1000.0,
                            "odometer_end": 1018.0,
                            "odometer_distance": 18.0,
                        },
                        "duration_min": 30,
                        "battery_details": {"start_battery_level": 80, "end_battery_level": 74},
                        "energy_consumed_net": 3.1,
                    },
                ]
            }
        }
    if path == "/api/v1/cars/101/drives/701" or path == "/api/v1/cars/1/drives/701":
        return {
            "data": {
                "car": {"car_id": 101, "car_name": "Synthetic Qualification Vehicle"},
                "drive": {
                    "drive_id": 701,
                    "start_date": "2026-07-18T08:00:00Z",
                    "end_date": "2026-07-18T08:30:00Z",
                    "start_address": "Fixture Garage",
                    "end_address": "Fixture Office",
                    "odometer_details": {
                        "odometer_start": 1000.0,
                        "odometer_end": 1018.0,
                        "odometer_distance": 18.0,
                    },
                    "duration_min": 30,
                    "battery_details": {"start_battery_level": 80, "end_battery_level": 74},
                    "energy_consumed_net": 3.1,
                    "drive_details": [],
                },
            }
        }
    if (
        path == "/api/matelink/v1/cars/101/parked/701/702"
        or path == "/api/matelink/v1/cars/1/parked/701/702"
    ):
        if scenario in {"missing", "parked_partial"}:
            return {
                "data": {
                    "older_drive_id": 701,
                    "newer_drive_id": 702,
                    "start_date": "2026-07-18T08:30:00Z",
                    "end_date": "2026-07-18T10:00:00Z",
                    "sample_count": 0,
                    "coverage_seconds": 0,
                    "coverage_ratio": 0.0,
                    "source": "fixture-missing",
                }
            }
        return {
            "data": {
                "older_drive_id": 701,
                "newer_drive_id": 702,
                "start_date": "2026-07-18T08:30:00Z",
                "end_date": "2026-07-18T10:00:00Z",
                "address": "Fixture Office",
                "start_battery_level": 74,
                "end_battery_level": 73,
                "battery_delta": -1,
                "energy_kwh": 0.42,
                "average_power_kw": 0.28,
                "peak_power_kw": 0.9,
                "inside_temp_average": 21.8,
                "outside_temp_average": 18.2,
                "sample_count": 12,
                "coverage_seconds": 5400,
                "coverage_ratio": 1.0,
                "source": "fixture",
            }
        }
    if path == "/api/v1/cars/101/charges" or path == "/api/v1/cars/1/charges":
        if scenario in {"empty", "no_charges"}:
            return {"data": {"charges": []}}
        return {
            "data": {
                "charges": [
                    {
                        "charge_id": 801,
                        "start_date": "2026-07-18T12:00:00Z",
                        "end_date": "2026-07-18T12:45:00Z",
                        "address": "Fixture Charger",
                        "charge_energy_added": 12.5,
                        "charge_energy_used": 13.1,
                        "cost": None,
                        "duration_min": 45,
                        "battery_details": {"start_battery_level": 40, "end_battery_level": 70},
                    }
                ]
            }
        }
    if path in (
        "/api/v1/cars/101/charges/801",
        "/api/v1/cars/101/charges/current",
        "/api/v1/cars/1/charges/801",
        "/api/v1/cars/1/charges/current",
    ):
        return {
            "data": {
                "car": {"car_id": 101, "car_name": "Synthetic Qualification Vehicle"},
                "charge": {
                    "charge_id": 801,
                    "start_date": "2026-07-18T12:00:00Z",
                    "end_date": "2026-07-18T12:45:00Z",
                    "address": "Fixture Charger",
                    "charge_energy_added": 12.5,
                    "charge_energy_used": 13.1,
                    "cost": None,
                    "duration_min": 45,
                    "battery_details": {"start_battery_level": 40, "end_battery_level": 70},
                    "charge_details": [],
                },
            }
        }
    return None


class FixtureHandler(BaseHTTPRequestHandler):
    scenario = "normal"
    app_request_count = 0
    connection_test_request_count = 0
    request_counts = Counter()
    status_counts = Counter()
    state_lock = Lock()

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/_health":
            self._send(200, self._state_payload())
            return
        if parsed.path == "/_qualification/scenario":
            scenario = parse_qs(parsed.query).get("name", [""])[0]
            if scenario not in SCENARIOS:
                self._send(400, {"error": "unknown synthetic scenario"})
                return
            with type(self).state_lock:
                type(self).scenario = scenario
            self._send(200, self._state_payload())
            return
        if parsed.path == "/_qualification/reset":
            with type(self).state_lock:
                type(self).app_request_count = 0
                type(self).connection_test_request_count = 0
                type(self).request_counts.clear()
                type(self).status_counts.clear()
            self._send(200, self._state_payload())
            return
        if parsed.path == "/_qualification/state":
            self._send(200, self._state_payload())
            return

        with type(self).state_lock:
            scenario = type(self).scenario
            type(self).app_request_count += 1
            if parsed.path in CONNECTION_TEST_PATHS:
                type(self).connection_test_request_count += 1
            type(self).request_counts[parsed.path] += 1

        if scenario == "timeout":
            time.sleep(TIMEOUT_SECONDS)
        if self.headers.get("Authorization") != f"Bearer {VALID_TOKEN}":
            self._record_status(401)
            self._send(401, {"error": "unauthorized"})
            return
        if scenario == "auth_401":
            self._record_status(401)
            self._send(401, {"error": "unauthorized"})
            return
        data = body_for(parsed.path, scenario)
        if data is None:
            self._record_status(404)
            self._send(404, {"error": "not found"})
            return
        self._record_status(200)
        self._send(200, data)

    def log_message(self, fmt, *args):
        print("%s %s" % (self.command, urlparse(self.path).path), flush=True)

    def _record_status(self, status):
        with type(self).state_lock:
            type(self).status_counts[status] += 1

    def _state_payload(self):
        with type(self).state_lock:
            return {
                "ok": True,
                "scenario": type(self).scenario,
                "app_request_count": type(self).app_request_count,
                "connection_test_request_count": type(self).connection_test_request_count,
                "requests_by_path": dict(type(self).request_counts),
                "responses_by_status": {
                    str(status): count for status, count in type(self).status_counts.items()
                },
            }

    def _send(self, status, payload):
        raw = json.dumps(payload).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--scenario", choices=sorted(SCENARIOS), default="normal")
    args = parser.parse_args()
    FixtureHandler.scenario = args.scenario
    httpd = ThreadingHTTPServer((args.host, args.port), FixtureHandler)
    print(f"fixture listening on {args.host}:{args.port} scenario={args.scenario}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
