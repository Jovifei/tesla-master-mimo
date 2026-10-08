"""Execute the real DAO SQL in disposable SQLite against independent numeric cases."""
import json
import pathlib
import re
import sqlite3

root = pathlib.Path(__file__).parent
source = (root / 'android/app/src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt').read_text(encoding='utf-8-sig')
queries = {name: query for query, name in re.findall(r'@Query\("""(.*?)"""\)\s*suspend fun (\w+)\(', source, re.S)}
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE drives_summary(carId INTEGER, qualityState TEXT, energyConsumed REAL, distance REAL, startDate TEXT)')
def result(rows, expected):
    db.execute('DELETE FROM drives_summary')
    db.executemany('INSERT INTO drives_summary VALUES(1,"observed",?,?,"2026-10-08T00:00:00Z")', rows)
    actual = db.execute(queries['avgEfficiency'], {'carId': 1}).fetchone()[0]
    return {'actual': actual, 'expected': expected, 'pass': actual == expected}
cases = {
    'unknown_energy_distance_not_in_denominator': result([(20.0, 100.0), (None, 900.0)], 200.0),
    'all_unknown_stays_unknown': result([(None, 100.0)], None),
    'known_zero_is_valid': result([(1.0, 1.0), (0.0, 9.0)], 100.0),
    'net_recovery_is_valid': result([(-0.5, 10.0)], -50.0),
}
(root / 'energy-dao-synthetic-results.json').write_text(json.dumps(cases, indent=2), encoding='utf-8')
print(json.dumps({'tests': len(cases), 'passed': sum(c['pass'] for c in cases.values()), 'failed': sum(not c['pass'] for c in cases.values()), 'environment': 'disposable SQLite', 'product_sql_extracted': True}))
raise SystemExit(0 if all(c['pass'] for c in cases.values()) else 1)
