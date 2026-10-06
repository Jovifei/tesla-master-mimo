"""Exercise a SQLite dialect model, not the final PostgreSQL statement.

SQLite requires LIMIT before OFFSET. LIMIT -1 remains unlimited and does not
filter a blocker. Its derived tables also cannot reference the outer newer
row, so the model uses the same uniquely selected header as a scalar lookup.
Actual PostgreSQL syntax/plans are checked separately on the unchanged SQL.
"""
from pathlib import Path
import re
import sqlite3
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
source = (ROOT / "deploy/jourvolt-dev-mock/parked_interval.go").read_text()
query = re.search(r"const parkedIntervalSQL = `([^`]+)`", source, re.S).group(1)
sqlite_query = query.replace(" OFFSET 0", " LIMIT -1 OFFSET 0")
sqlite_query = sqlite_query.replace(
    "AND started_at <= newer.started_at",
    "AND started_at <= (SELECT started_at FROM jourvolt_telemetry_sessions "
    "WHERE public_id=$4 AND user_id=$1 AND vehicle_id=$2 AND kind='drive')",
)
db = sqlite3.connect(":memory:")
db.execute("""CREATE TABLE jourvolt_telemetry_sessions(
    id TEXT, public_id INTEGER, user_id TEXT, vehicle_id INTEGER, kind TEXT,
    started_at TEXT, ended_at TEXT, source TEXT NOT NULL, quality_state TEXT,
    source_instance_id TEXT, source_vehicle_id TEXT,
    start_address TEXT, end_address TEXT, route_json TEXT, source_record_id TEXT)""")
older = ("old", 11, "owner", 7, "drive", "2026-01-01T08:00:00Z",
         "2026-01-01T08:30:00Z", "teslamate_archive", "observed", "source-a",
         "car-a", None, "old endpoint", "NOT_DECODED", "old")
newer = ("new", 12, "owner", 7, "drive", "2026-01-01T10:00:00Z",
         "2026-01-01T10:30:00Z", "teslamate_archive", "observed", "source-a",
         "car-a", "new endpoint", None, "NOT_DECODED", "new")
insert = "INSERT INTO jourvolt_telemetry_sessions VALUES(" + ",".join(["?"] * 15) + ")"
db.executemany(insert, [older, newer])
params = {"1": "owner", "2": 7, "3": 11, "4": 12}
result = db.execute(sqlite_query, params).fetchone()
assert result is not None and result[-1] == 0
for key, value in [("1", "other-owner"), ("2", 8), ("3", 99), ("4", 99)]:
    assert db.execute(sqlite_query, params | {key: value}).fetchone() is None

cases = [
    ("hidden_short", {}, 1),
    ("quarantined", {8: "quarantined"}, 1),
    ("cross_source", {7: "telemetry_mqtt"}, 1),
    ("unfinished", {6: None}, 1),
    ("overlap_gap", {5: "2026-01-01T07:00:00Z"}, 1),
    ("newer_tie", {5: "2026-01-01T10:00:00Z", 6: "2026-01-01T10:10:00Z"}, 1),
    ("nested_older", {5: "2026-01-01T08:10:00Z", 6: "2026-01-01T08:20:00Z"}, 1),
    ("overlap_older_only", {5: "2026-01-01T07:00:00Z", 6: "2026-01-01T08:10:00Z"}, 1),
    ("older_tie", {5: "2026-01-01T08:00:00Z", 6: "2026-01-01T08:30:00Z"}, 1),
    ("different_owner", {2: "other-owner"}, 0),
    ("different_car", {3: 8}, 0),
    ("charge", {4: "charge"}, 0),
    ("before_gap", {5: "2026-01-01T07:00:00Z", 6: "2026-01-01T08:00:00Z"}, 0),
    ("after_gap", {5: "2026-01-01T11:00:00Z", 6: "2026-01-01T11:30:00Z"}, 0),
]
for name, changes, expected in cases:
    row = list(older)
    row[0], row[1] = "block", 13
    row[-1] = "block"
    row[5], row[6] = "2026-01-01T09:00:00Z", "2026-01-01T09:01:00Z"
    for key, value in changes.items():
        row[key] = value
    db.execute(insert, row)
    assert db.execute(sqlite_query, params).fetchone()[-1] == expected, name
    db.execute("DELETE FROM jourvolt_telemetry_sessions WHERE public_id=13")
print(f"SQLite dialect model (unlimited LIMIT and scalar header lookup): {5 + len(cases)} scenarios PASS")

for locale in ["values", "values-zh"]:
    tree = ET.parse(ROOT / f"android/app/src/main/res/{locale}/strings.xml")
    names = [item.get("name") for item in tree.getroot() if item.tag == "string"]
    assert len(names) == len(set(names)), locale
    for suffix in ["title", "partial_explanation", "start_endpoint", "end_endpoint", "source",
                   "not_confirmed", "read_unavailable"]:
        assert "drive_interval_" + suffix in names, (locale, suffix)
print("Android XML: both locales parse; 7 matching resources; no duplicate names PASS")
assert not any(value in query for value in ["route_json", "charge_points_json", "history_summary_"])
assert re.search(r"(?:blocker\.)?source\s*<>\s*'teslamate_archive'", query)
assert "ORDER BY started_at OFFSET 0" in query
assert re.search(r"(?:blocker\.)?source\s*=\s*'teslamate_archive'", query)
assert "ORDER BY source_instance_id, source_vehicle_id, source_record_id OFFSET 0" in query
print("No detail JSON/summary columns; both partial-index predicates present PASS")
print("This SQLite helper does not run Go, PostgreSQL, or Android checks; see their separate validation receipts")
