package main

import (
	"database/sql"
	"errors"
	"testing"
	"time"
)

type syntheticChargeRow struct {
	id     int
	start  time.Time
	end    sql.NullTime
	energy sql.NullFloat64
}

type syntheticChargeRows struct {
	values  []syntheticChargeRow
	index   int
	scanErr error
	rowsErr error
}

func (r *syntheticChargeRows) Next() bool {
	if r.index >= len(r.values) {
		return false
	}
	r.index++
	return true
}

func (r *syntheticChargeRows) Scan(dest ...any) error {
	if r.scanErr != nil {
		return r.scanErr
	}
	v := r.values[r.index-1]
	*dest[0].(*int) = v.id
	*dest[1].(*time.Time) = v.start
	*dest[2].(*sql.NullTime) = v.end
	*dest[3].(*sql.NullFloat64) = v.energy
	return nil
}

func (r *syntheticChargeRows) Err() error { return r.rowsErr }

func TestCollectParkedChargesRetainsTwoSessionsAndPartialIntervals(t *testing.T) {
	start := time.Date(2026, 10, 1, 0, 10, 0, 0, time.UTC)
	rows := &syntheticChargeRows{values: []syntheticChargeRow{
		{41, start, sql.NullTime{Time: start.Add(20 * time.Minute), Valid: true}, sql.NullFloat64{Float64: 0, Valid: true}},
		{42, start.Add(35 * time.Minute), sql.NullTime{}, sql.NullFloat64{}},
	}}
	charges, complete, err := collectParkedCharges(rows, time.UTC)
	if err != nil || !complete || len(charges) != 2 {
		t.Fatalf("two observed sessions must remain distinct: charges=%+v complete=%v err=%v", charges, complete, err)
	}
	if charges[0].ChargeID != 41 || charges[1].ChargeID != 42 ||
		charges[0].EnergyAddedKWh == nil || *charges[0].EnergyAddedKWh != 0 ||
		charges[1].EnergyAddedKWh != nil || charges[1].EndDate != nil {
		t.Fatalf("nullable time or real zero changed: %+v", charges)
	}
	if charges[0].StartDate != formatTime(start, time.UTC) ||
		charges[0].EndDate == nil || *charges[0].EndDate != formatTime(start.Add(20*time.Minute), time.UTC) {
		t.Fatalf("partial interval was clipped or retimed: %+v", charges[0])
	}
}

func TestCollectParkedChargesReportsTruncationAtFixedBound(t *testing.T) {
	start := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	rows := &syntheticChargeRows{}
	for i := 0; i < maxParkedChargeSessions+1; i++ {
		rows.values = append(rows.values, syntheticChargeRow{id: i + 1, start: start.Add(time.Duration(i) * time.Minute)})
	}
	charges, complete, err := collectParkedCharges(rows, time.UTC)
	if err != nil || complete || len(charges) != maxParkedChargeSessions ||
		charges[0].ChargeID != 1 || charges[len(charges)-1].ChargeID != maxParkedChargeSessions {
		t.Fatalf("bounded list must expose partial coverage: count=%d complete=%v err=%v", len(charges), complete, err)
	}
}

func TestCollectParkedChargesPropagatesRowsFailures(t *testing.T) {
	start := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	for _, rows := range []*syntheticChargeRows{
		{values: []syntheticChargeRow{{id: 1, start: start}}, scanErr: errors.New("synthetic scan failed")},
		{rowsErr: errors.New("synthetic rows failed")},
	} {
		if _, _, err := collectParkedCharges(rows, time.UTC); err == nil {
			t.Fatal("database read failure must not become a complete empty list")
		}
	}
}

func TestNearestChargeSOCRequiresUniqueActualSampleWithinFiveMinutes(t *testing.T) {
	boundary := time.Date(2026, 10, 1, 0, 20, 0, 0, time.UTC)
	level, at := nearestChargeSOC([]chargeSOCSample{
		{boundary.Add(-4 * time.Minute), 70},
		{boundary.Add(1 * time.Minute), 71},
	}, boundary)
	if level == nil || *level != 71 || at == nil || *at != boundary.Add(time.Minute) {
		t.Fatalf("nearest exact process sample lost: level=%v at=%v", level, at)
	}
	for _, samples := range [][]chargeSOCSample{
		nil,
		{{boundary.Add(-6 * time.Minute), 70}},
		{{boundary.Add(-time.Minute), 70}, {boundary.Add(time.Minute), 71}},
		{{boundary, 70}, {boundary, 71}},
		{{boundary, -1}, {boundary.Add(6 * time.Minute), 72}},
	} {
		if soc, observed := nearestChargeSOC(samples, boundary); soc != nil || observed != nil {
			t.Fatalf("missing, out-of-window or ambiguous SOC cannot be inferred: %+v", samples)
		}
	}
}
