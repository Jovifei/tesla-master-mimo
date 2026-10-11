#!/usr/bin/env python3
"""Execute the exact production Room DAO efficiency queries in isolated in-memory SQLite."""
from pathlib import Path
import re
import sqlite3

path = Path("android/app/src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt")
source = path.read_text(encoding="utf-8")

def sql_for(name):
    pattern = re.compile(r'@Query\("""((?:(?!@Query\().)*?)"""\)\s*suspend fun '+name+r'\(', re.S)
    matches = pattern.findall(source)
    assert len(matches) == 1, (name, len(matches))
    return matches[0]

connection = sqlite3.connect(":memory:")
connection.execute("""
CREATE TABLE drives_summary (
  carId INTEGER NOT NULL, qualityState TEXT NOT NULL,
  startDate TEXT NOT NULL, distance REAL NOT NULL, energyConsumed REAL
)""")

def seed(rows):
    connection.execute("DELETE FROM drives_summary")
    connection.executemany("INSERT INTO drives_summary VALUES(?,?,?,?,?)",rows)

def avg(car=1):
    return connection.execute(sql_for("avgEfficiency"), {"carId":car}).fetchone()[0]

def avg_range(car=1):
    return connection.execute(sql_for("avgEfficiencyInRange"), {
        "carId":car,"startDate":"2026-01-01T00:00:00Z",
        "endDate":"2027-01-01T00:00:00Z",
    }).fetchone()[0]

seed([
    (1,"observed","2026-08-01T00:00:00Z",10.0,2.0),
    (1,"observed","2026-08-02T00:00:00Z",90.0,None),
    (1,"quarantined","2026-08-03T00:00:00Z",1000.0,100.0),
    (2,"observed","2026-08-02T00:00:00Z",100.0,100.0),
])
assert abs(avg()-200.0)<1e-8, avg()
assert abs(avg_range()-200.0)<1e-8, avg_range()
assert abs(avg(car=2)-1000.0)<1e-8
print("PASS: unknown-energy distance excluded from both numerator and denominator, scope isolated")

seed([(1,"observed","2026-08-01T00:00:00Z",100.0,None)])
assert avg() is None and avg_range() is None
print("PASS: all unknown energy yields SQL NULL, not fabricated zero")

seed([(1,"observed","2026-08-01T00:00:00Z",5.0,0.0),
      (1,"observed","2026-08-03T00:00:00Z",5.0,-0.5),
      (1,"observed","2025-01-01T00:00:00Z",100.0,100.0)])
assert abs(avg_range()+50.0)<1e-8, avg_range()
print("PASS: valid zero and negative regeneration included, date window bounded")
