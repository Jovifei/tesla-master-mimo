#!/usr/bin/env python3
"""Validate shared Drive Report V1 fixtures with the Python standard library."""

from __future__ import annotations

import json
import math
from datetime import datetime
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "shared" / "contracts"
FIXTURES = CONTRACT / "fixtures"
SOURCES = {"direct", "derived", "estimated", "cached", "unavailable"}


def check(ok: bool, message: str) -> None:
    if not ok:
        raise AssertionError(message)


def finite(value: Any) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
    )


def metric(name: str, item: dict[str, Any], nullable: bool = True) -> None:
    check(set(item) == {"value", "source"}, f"{name}: changed metric shape")
    check(item["source"] in SOURCES, f"{name}: invalid source")
    value = item["value"]
    if value is None:
        check(nullable, f"{name}: null is forbidden")
        check(item["source"] == "unavailable", f"{name}: null must be unavailable")
    else:
        check(finite(value), f"{name}: value is not finite")
        check(item["source"] != "unavailable", f"{name}: available value is unavailable")


def validate(report: dict[str, Any], name: str) -> None:
    required = {
        "schema_version", "car_id", "drive_id", "start_date", "end_date",
        "duration_seconds", "privacy", "distance", "battery", "energy",
        "cost", "speed", "elevation", "temperature", "route", "series",
    }
    check(required <= report.keys(), f"{name}: missing required fields")
    check(report["schema_version"] == 1, f"{name}: unsupported schema")
    check(type(report["car_id"]) is int and report["car_id"] > 0, f"{name}: bad car_id")
    check(type(report["drive_id"]) is int and report["drive_id"] > 0, f"{name}: bad drive_id")
    start, end = datetime.fromisoformat(report["start_date"]), datetime.fromisoformat(report["end_date"])
    check(end > start, f"{name}: invalid time range")
    check(type(report["duration_seconds"]) is int and report["duration_seconds"] > 0,
          f"{name}: invalid duration")
    check(report["privacy"] == {
        "addresses_masked_by_default": True,
        "notification_contains_addresses": False,
    }, f"{name}: privacy contract changed")

    metric(f"{name}.distance", report["distance"], nullable=False)
    check(report["distance"]["value"] > 0, f"{name}: completed drive needs positive distance")
    for field in ("odometer_start_km", "odometer_end_km",
                  "rated_range_start_km", "rated_range_end_km"):
        if field in report:
            metric(f"{name}.{field}", report[field])

    battery = report["battery"]
    check(set(battery) == {"start_percent", "end_percent", "delta_percent"},
          f"{name}: battery shape changed")
    for field, item in battery.items():
        metric(f"{name}.battery.{field}", item)
        if field != "delta_percent" and item["value"] is not None:
            check(0 <= item["value"] <= 100, f"{name}: battery out of range")

    energy = report["energy"]
    check(energy["source"] in {"api", "power_samples", "unavailable"},
          f"{name}: invalid energy source")
    metric(f"{name}.energy.kwh", energy["kwh"])
    metric(f"{name}.energy.wh_per_km", energy["wh_per_km"])
    if energy["source"] == "unavailable":
        check(energy["kwh"]["value"] is None, f"{name}: unavailable energy has value")
    if energy["coverage_seconds"] is not None:
        check(type(energy["coverage_seconds"]) is int and energy["coverage_seconds"] >= 0,
              f"{name}: invalid coverage seconds")
    if energy["coverage_ratio"] is not None:
        check(finite(energy["coverage_ratio"]) and 0 <= energy["coverage_ratio"] <= 1,
              f"{name}: invalid coverage ratio")

    cost = report["cost"]
    check(cost["source"] in {"flat_tariff_estimate", "unavailable"},
          f"{name}: invalid cost source")
    if cost["source"] == "unavailable":
        check(cost["amount"] is None and cost["price_per_kwh"] is None
              and cost["estimated"] is False, f"{name}: unavailable cost looks authoritative")
    else:
        check(finite(cost["amount"]) and cost["amount"] >= 0, f"{name}: invalid cost")
        check(finite(cost["price_per_kwh"]) and cost["price_per_kwh"] >= 0,
              f"{name}: invalid tariff")
        check(cost["estimated"] is True, f"{name}: tariff cost is not marked estimated")

    for field, item in report["speed"].items():
        metric(f"{name}.speed.{field}", item)
        if item["value"] is not None:
            check(item["value"] >= 0, f"{name}: negative speed")
    metric(f"{name}.elevation.average_m", report["elevation"]["average_m"])
    ratio = report["elevation"]["sample_coverage_ratio"]
    if ratio is not None:
        check(finite(ratio) and 0 <= ratio <= 1, f"{name}: invalid elevation coverage")
    for field, item in report["temperature"].items():
        metric(f"{name}.temperature.{field}", item)

    check(type(report["route"]) is list and len(report["route"]) <= 360,
          f"{name}: route is unbounded")
    for point in report["route"]:
        lat, lon = point["latitude"], point["longitude"]
        check(finite(lat) and finite(lon), f"{name}: non-finite coordinate")
        check(-90 <= lat <= 90 and -180 <= lon <= 180, f"{name}: coordinate out of range")
        check((lat, lon) != (0, 0), f"{name}: zero-island coordinate")

    check(type(report["series"]) is list and len(report["series"]) <= 360,
          f"{name}: series is unbounded")
    for sample in report["series"]:
        datetime.fromisoformat(sample["timestamp"])
        for field, value in sample.items():
            if field != "timestamp" and value is not None:
                check(finite(value), f"{name}: {field} is not finite")
        lat, lon = sample["latitude"], sample["longitude"]
        check((lat is None) == (lon is None), f"{name}: partial coordinate pair")
        if lat is not None:
            check(-90 <= lat <= 90 and -180 <= lon <= 180, f"{name}: sample out of range")
            check((lat, lon) != (0, 0), f"{name}: zero-island sample")


def main() -> None:
    schema = json.loads((CONTRACT / "drive-report-v1.schema.json").read_text("utf-8"))
    check(schema["$schema"] == "https://json-schema.org/draft/2020-12/schema",
          "schema draft changed")
    check(schema["properties"]["schema_version"]["const"] == 1, "schema version changed")
    fixtures = sorted(FIXTURES.glob("drive-report-v1-*.json"))
    check(len(fixtures) >= 2, "complete and partial fixtures are required")
    for path in fixtures:
        validate(json.loads(path.read_text("utf-8")), path.name)
        print(f"PASS {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
