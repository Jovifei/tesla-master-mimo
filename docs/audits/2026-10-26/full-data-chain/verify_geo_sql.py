"""Run the actual DAO SELECT strings on synthetic in-memory SQLite, not a Room/device test."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import hashlib
import json
import re
import sqlite3

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
DAO = ROOT / "android/app/src/main/java/com/matelink/data/local/dao/AggregateDao.kt"
queries = dict((name, sql) for sql, name in re.findall(
    r'@Query\(\s*"""(.*?)"""\s*\)\s*suspend fun (\w+)',
    DAO.read_text(encoding="utf-8"), re.S))


def database():
    db = sqlite3.connect(":memory:")
    db.row_factory = sqlite3.Row
    db.executescript("""
        CREATE TABLE drives_summary(carId INTEGER, driveId INTEGER, startDate TEXT,
          qualityState TEXT, distance REAL, startAddress TEXT, apiEvidence TEXT);
        CREATE TABLE drive_detail_aggregates(carId INTEGER, driveId INTEGER,
          startCountryCode TEXT, startCountryName TEXT, startRegionName TEXT,
          startLatitude REAL, startLongitude REAL);
        CREATE TABLE charges_summary(carId INTEGER, chargeId INTEGER, startDate TEXT,
          qualityState TEXT, energyAdded REAL, latitude REAL, longitude REAL,
          address TEXT, apiEvidence TEXT);
        CREATE TABLE charge_detail_aggregates(carId INTEGER, chargeId INTEGER,
          countryCode TEXT, countryName TEXT, regionName TEXT, isFastCharger INTEGER);
    """)
    # Second qualified drive anchors a reachable country/region even when row1 is quarantined.
    for record in (1, 2):
        db.execute("INSERT INTO drives_summary VALUES(-7,?,?,'observed',10,'Synthetic',?)",
                   (record, "2026-06-01T10:00:00Z", '{"odometer_details":{"distance":10}}'))
        db.execute("INSERT INTO drive_detail_aggregates VALUES(-7,?,'AA','Synthetic','Region',10,20)", (record,))
    db.execute("INSERT INTO charges_summary VALUES(-7,9,'2026-06-01T10:00:00Z','observed',5,10,20,'Synthetic',NULL)")
    db.execute("INSERT INTO charge_detail_aggregates VALUES(-7,9,'AA','Synthetic','Region',0)")
    return db


def read(db, method, **extra):
    sql = queries[method]
    assert sql.lstrip().upper().startswith("SELECT")
    return [dict(row) for row in db.execute(sql, {"carId": -7, "countryCode": "AA", **extra})]


cases = []


def result(name, expected, actual):
    cases.append({"name": name, "expected": expected, "actual": actual,
                  "result": "PASS" if expected == actual else "FAIL"})


with database() as db:
    result("control_qualified_map_records", [1, 2], [
        len(read(db, "getChargeLocationsForCountry")), len(read(db, "getDriveLocationsForCountry"))])
with database() as db:
    db.execute("UPDATE charges_summary SET qualityState='quarantined'")
    result("quarantined_charge_marker_must_not_join_qualified_country", 0,
           len(read(db, "getChargeLocationsForCountry")))
with database() as db:
    db.execute("UPDATE drives_summary SET qualityState='quarantined' WHERE driveId=1")
    result("quarantined_drive_marker_must_not_join_qualified_country", 1,
           len(read(db, "getDriveLocationsForCountry")))
with database() as db:
    db.execute("UPDATE drives_summary SET qualityState='quarantined'")
    result("control_country_summary_rejects_quarantined_drives", [], read(db, "getCountriesVisited"))
with database() as db:
    timestamp = "2025-12-31T20:00:00Z"
    assert datetime.fromisoformat(timestamp).astimezone(timezone(timedelta(hours=8))).year == 2026
    db.execute("UPDATE drives_summary SET startDate=?", (timestamp,))
    result("local_2026_country_must_include_utc_previous_day", 1, len(read(db,
        "getCountriesVisitedInRange", startDate="2026-01-01T00:00:00", endDate="2027-01-01T00:00:00")))
with database() as db:
    evidence = json.dumps({"source": "teslamate_archive", "energy_consumed_net": 2.0,
                           "odometer_details": {"distance": None}})
    db.execute("UPDATE drives_summary SET distance=0, apiEvidence=?", (evidence,))
    result("missing_distance_evidence_must_not_be_reported_as_observed_zero", None,
           read(db, "getCountriesVisited")[0]["totalDistanceKm"])
with database() as db:
    db.execute("UPDATE drives_summary SET distance=0, apiEvidence=?",
               ('{"odometer_details":{"distance":0}}',))
    result("control_explicit_zero_distance_stays_zero", 0.0,
           read(db, "getCountriesVisited")[0]["totalDistanceKm"])

report = {"layer": "actual DAO SQL / synthetic minimal SQLite; not Android Room/provider",
          "source_sha256": hashlib.sha256(DAO.read_bytes()).hexdigest(),
          "tests": len(cases), "failures": sum(c["result"] == "FAIL" for c in cases), "cases": cases}
(HERE / "geo-sql-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
for case in cases:
    print(case)
print("tests", report["tests"], "failures", report["failures"])
raise SystemExit(1 if report["failures"] else 0)
