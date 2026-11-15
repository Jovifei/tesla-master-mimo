#!/usr/bin/env python3
"""Audit an already-authorized history export. No network, mutations, secrets or energy inference.
Input is kept local. Output contains only dates/counts and boolean scope evidence.
This cannot prove provider completeness or authenticate the supplied scope assertions.
"""
from __future__ import annotations
import argparse
import datetime as dt
import json
import math
from pathlib import Path
from zoneinfo import ZoneInfo

MAX_BYTES = 10 * 1024 * 1024

def audit(document: dict, start: str = '2026-10-27', end: str = '2026-11-12') -> dict:
    if not isinstance(document, dict) or document.get('schema_version') != 1:
        raise ValueError('Expected schema_version 1')
    first, last = dt.date.fromisoformat(start), dt.date.fromisoformat(end)
    if first > last or (last-first).days > 366:
        raise ValueError('Invalid or excessive audit window')
    zone = ZoneInfo(document['display_timezone'])
    scope = document.get('scope_verified', {})
    if not all(scope.get(key) is True for key in ('account', 'vehicle', 'source')):
        raise ValueError('Same account, vehicle and source must be independently verified first')
    evidence = document.get('evidence_class')
    if evidence not in ('synthetic', 'production-readonly'):
        raise ValueError('Label evidence class explicitly')
    pages = document.get('page_responses')
    if not isinstance(pages, list) or not 1 <= len(pages) <= 100:
        raise ValueError('Expected 1..100 bounded page responses')
    by_day = {}; seen = set(); received_pages = set(); expected_pages = set()
    counts = dict(returned_rows=0, duplicate_rows=0, undated_rows=0, unidentified_rows=0,
                  quarantined_rows=0, failed_or_malformed_pages=0)
    latest = None
    for page in pages:
        if not isinstance(page, dict) or page.get('status') != 200:
            counts['failed_or_malformed_pages'] += 1; continue
        raw = page.get('payload')
        data = raw.get('data', raw) if isinstance(raw, dict) else None
        if not isinstance(data, dict) or not isinstance(data.get('charges'), list):
            counts['failed_or_malformed_pages'] += 1; continue
        requested = page.get('requested_page')
        if isinstance(requested, int) and not isinstance(requested, bool) and requested > 0:
            received_pages.add(requested)
        meta = data.get('meta', {})
        total = meta.get('total_pages') if isinstance(meta, dict) else None
        if isinstance(total, int) and not isinstance(total, bool) and 0 <= total <= 100:
            expected_pages.add(total)
        if len(data['charges']) > 1000:
            raise ValueError('Too many rows in one page')
        for row in data['charges']:
            if not isinstance(row, dict):
                counts['failed_or_malformed_pages'] += 1; continue
            identity = row.get('charge_id', row.get('id'))
            source = row.get('source_instance', row.get('source', ''))
            if isinstance(identity, (str, int)) and not isinstance(identity, bool) and str(identity):
                key = (str(source), str(identity))
                if key in seen:
                    counts['duplicate_rows'] += 1; continue
                seen.add(key)
            else:
                counts['unidentified_rows'] += 1
            counts['returned_rows'] += 1
            quarantined = row.get('quality_state') == 'quarantined'
            if quarantined: counts['quarantined_rows'] += 1
            raw_date = row.get('start_date')
            try:
                stamp = dt.datetime.fromisoformat(raw_date.replace('Z', '+00:00'))
                if stamp.tzinfo is None: raise ValueError('Undated timezone')
                day = stamp.astimezone(zone).date()
            except (ValueError, AttributeError, TypeError):
                counts['undated_rows'] += 1; continue
            latest = max(latest, day) if latest else day
            if not first <= day <= last: continue
            result = by_day.setdefault(day.isoformat(), {'returned': 0, 'quarantined': 0, 'reported_energy_fields': 0, 'reported_zero_fields': 0})
            result['returned'] += 1
            result['quarantined'] += int(quarantined)
            energy = row.get('charge_energy_added')
            if isinstance(energy, (int, float)) and not isinstance(energy, bool) and math.isfinite(energy):
                result['reported_energy_fields'] += 1
                result['reported_zero_fields'] += int(energy == 0)
    total = next(iter(expected_pages)) if len(expected_pages) == 1 else None
    all_pages = total is not None and not counts['failed_or_malformed_pages'] and (
        received_pages == set(range(1, total+1)) or total == 0 and received_pages == {1})
    ui = document.get('ui', {})
    filters = {key: ui.get(key) for key in ('start_date', 'end_date') if isinstance(ui.get(key), str) and len(ui[key]) == 10}
    for key,value in filters.items():
        dt.date.fromisoformat(value)
    return {
        'schema_version': 1, 'evidence_class': evidence, 'window': {'start': start, 'end': end, 'timezone': str(zone)},
        'scope_assertions_supplied': True, 'scope_independently_authenticated_by_tool': False,
        'page_count': len(pages), 'api_pagination_complete': all_pages,
        'provider_history_complete': None, 'latest_returned_date': latest.isoformat() if latest else None,
        'counts': counts, 'daily_counts': dict(sorted(by_day.items())), 'ui_date_filter': filters,
        'interpretation': 'Returned counts are not independent charging sessions or qualified energy. Missing days do not prove no charging. Compare with UI, then stored API/provider evidence; never backfill from this report.'
    }

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--start', default='2026-10-27')
    parser.add_argument('--end', default='2026-11-12')
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve(): raise SystemExit('Refusing to overwrite input evidence')
    if args.output.exists(): raise SystemExit('Refusing to replace existing output evidence')
    if not 0 < args.input.stat().st_size <= MAX_BYTES: raise SystemExit('Input exceeds bounded export size')
    try:
        result = audit(json.loads(args.input.read_text(encoding='utf-8')), args.start, args.end)
    except (ValueError, KeyError, TypeError) as exc:
        # Do not print raw data or exception contents that may contain identifiers.
        raise SystemExit('Invalid or unverified export; no audit result written') from None
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Wrote date/count-only report. No production recovery or natural acceptance is asserted.')

if __name__ == '__main__': main()
