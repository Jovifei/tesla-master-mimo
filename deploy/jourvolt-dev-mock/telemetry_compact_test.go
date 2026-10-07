package main

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"math/rand"
	"reflect"
	"testing"
	"time"
)

// The oracle keeps the original reducer unchanged. This adapter applies the
// existing PostgreSQL admission rule and its post-transition fresh-ID scoping.
// Its unbounded maps belong to the test oracle, never the compact state.
type compactReplayOracle struct {
	machine *telemetrySessionMachine
	scope   telemetryKey
	seen    map[string]bool
	latest  map[string]time.Time
}

func compactReferenceHeader(s *telemetrySession) *compactTelemetrySession {
	if s == nil {
		return nil
	}
	return &compactTelemetrySession{ID: s.ID, PublicID: s.PublicID, Kind: s.Kind, StartAt: s.StartAt, EndAt: s.EndAt, OdometerStart: s.OdometerStart, OdometerEnd: s.OdometerEnd, EnergyAdded: s.EnergyAdded, CompletionKey: s.CompletionKey, Source: s.Source, QualityState: s.QualityState, QualityReason: s.QualityReason, RouteCount: int64(len(s.Route)), ChargeCount: int64(len(s.ChargePoints))}
}

func requireCompactReferenceState(t *testing.T, s compactTelemetryState, o *compactReplayOracle) {
	t.Helper()
	if !reflect.DeepEqual(s.Drive, compactReferenceHeader(o.machine.drive)) || !reflect.DeepEqual(s.Charge, compactReferenceHeader(o.machine.charge)) {
		t.Fatalf("compact headers differ\n%+v %+v\n%+v %+v", s.Drive, s.Charge, compactReferenceHeader(o.machine.drive), compactReferenceHeader(o.machine.charge))
	}
	if !reflect.DeepEqual(s.StopCandidate, o.machine.stopCandidate) || !reflect.DeepEqual(s.ChargeEnergyStart, o.machine.chargeEnergyStart) || s.ChargeEnergyField != o.machine.chargeEnergyField || !reflect.DeepEqual(s.LastSpeed, o.machine.lastSpeed) || !s.LastSpeedAt.Equal(o.machine.lastSpeedAt) || !reflect.DeepEqual(s.LastHeading, o.machine.lastHeading) || !s.LastHeadingAt.Equal(o.machine.lastHeadingAt) || s.CurrentGear != o.machine.currentGear || !s.CurrentGearAt.Equal(o.machine.currentGearAt) {
		t.Fatal("compact scalar observations/baseline differ")
	}
	for i, field := range compactTelemetryFields {
		if !s.FieldTimes[i].Equal(o.latest[field]) {
			t.Fatalf("watermark %s differs", field)
		}
	}
	for _, pair := range []struct {
		c    *compactTelemetrySession
		full *telemetrySession
	}{{s.Drive, o.machine.drive}, {s.Charge, o.machine.charge}} {
		if pair.c != nil {
			a, b := compactTelemetryQuality(*pair.c)
			x, y := classifyTelemetrySession(*pair.full)
			if a != x || b != y {
				t.Fatalf("quality=(%s,%s), reference=(%s,%s)", a, b, x, y)
			}
		}
	}
}

func replayCompactReference(t *testing.T, scope telemetryKey, events []telemetrySessionEvent) {
	t.Helper()
	s, err := newCompactTelemetryState(scope, 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	o := newCompactReplayOracle(scope, 10*time.Second)
	for _, e := range events {
		if !o.admit(e) {
			continue
		}
		counts := map[string][2]int{}
		for _, p := range []*telemetrySession{o.machine.drive, o.machine.charge} {
			if p != nil {
				counts[p.ID] = [2]int{len(p.Route), len(p.ChargePoints)}
			}
		}
		doneBefore := len(o.machine.completed)
		o.apply(e)
		next, delta, err := transitionCompactTelemetry(s, scope, e)
		if err != nil || !delta.Applied {
			t.Fatalf("event=%+v applied=%v err=%v", e, delta.Applied, err)
		}
		if delta.CompletedCount != len(o.machine.completed)-doneBefore {
			t.Fatal("completion count differs")
		}
		for i := 0; i < delta.CompletedCount; i++ {
			if !reflect.DeepEqual(delta.Completed[i], *compactReferenceHeader(&o.machine.completed[doneBefore+i])) {
				t.Fatalf("completion differs: %+v", delta.Completed[i])
			}
		}
		var route *telemetryRoutePoint
		var charge *telemetryChargePoint
		var routeID, chargeID string
		candidates := []*telemetrySession{o.machine.drive, o.machine.charge}
		for i := doneBefore; i < len(o.machine.completed); i++ {
			candidates = append(candidates, &o.machine.completed[i])
		}
		for _, p := range candidates {
			if p == nil {
				continue
			}
			old := counts[p.ID]
			if len(p.Route) > old[0] {
				if len(p.Route) != old[0]+1 {
					t.Fatal("fixture emitted multiple route samples")
				}
				route = &p.Route[len(p.Route)-1]
				routeID = p.ID
			}
			if len(p.ChargePoints) > old[1] {
				if len(p.ChargePoints) != old[1]+1 {
					t.Fatal("fixture emitted multiple charge samples")
				}
				charge = &p.ChargePoints[len(p.ChargePoints)-1]
				chargeID = p.ID
			}
		}
		if !reflect.DeepEqual(delta.Route, route) || !reflect.DeepEqual(delta.Charge, charge) || delta.RouteSessionID != routeID || delta.ChargeSessionID != chargeID {
			t.Fatalf("sample delta differs for %+v: %+v", e, delta)
		}
		s = next
		requireCompactReferenceState(t, s, o)
	}
	for _, now := range []time.Time{time.Date(2026, 1, 2, 0, 0, 0, 0, time.UTC), time.Date(2026, 1, 3, 0, 0, 0, 0, time.UTC)} {
		before := len(o.machine.completed)
		want := o.machine.finalizeDue(now)
		next, d, err := finalizeCompactTelemetry(s, scope, now)
		if err != nil || d.Applied != want || d.CompletedCount != len(o.machine.completed)-before {
			t.Fatalf("timer=%+v %v want=%v", d, err, want)
		}
		if want && !reflect.DeepEqual(d.Completed[0], *compactReferenceHeader(&o.machine.completed[before])) {
			t.Fatal("timer completion identity differs")
		}
		s = next
		requireCompactReferenceState(t, s, o)
	}
}

func TestCompactTelemetryDifferentialReplay(t *testing.T) {
	at := time.Date(2026, 1, 1, 0, 0, 0, 123456789, time.UTC)
	scope := telemetryKey{"compact-differential", 5}
	base := []telemetrySessionEvent{
		compactTestEvent(at, 0, "Gear", "D"), compactTestEvent(at, 1, "VehicleSpeed", 0.0), compactTestEvent(at, 2, "GpsHeading", 0.0),
		compactTestEvent(at, 3, "Location", map[string]any{"latitude": 1.0, "longitude": 0.0}), compactTestEvent(at, 4, "Odometer", 0.0), compactTestEvent(at, 5, "Odometer", 1.0),
		compactTestEvent(at, 6, "Gear", "P"), compactTestEvent(at, 7, "DetailedChargeState", "Starting"), compactTestEvent(at, 8, "ACChargingEnergyIn", 10.0),
		compactTestEvent(at, 9, "ACChargingEnergyIn", 10.0), compactTestEvent(at, 10, "DCChargingEnergyIn", 100.0), compactTestEvent(at, 11, "DCChargingEnergyIn", 99.0),
		compactTestEvent(at, 12, "DCChargingEnergyIn", 102.0), compactTestEvent(at, 13, "Soc", 0.0), compactTestEvent(at, 14, "ACChargingPower", 0.0),
		compactTestEvent(at, 17, "DetailedChargeState", "Complete"), // charge then due drive completion in one transition
		compactTestEvent(at, 20, "Gear", "R"), compactTestEvent(at, 21, "Location", map[string]any{"latitude": 0.0, "longitude": 2.0}),
		compactTestEvent(at, 22, "Gear", "N"), compactTestEvent(at, 23, "VehicleSpeed", 3.0), compactTestEvent(at, 24, "Gear", "P"),
	}
	replayCompactReference(t, scope, append(append([]telemetrySessionEvent{}, base...), base...))
	for seed := int64(0); seed < 8; seed++ {
		t.Run(fmt.Sprint(seed), func(t *testing.T) {
			rng := rand.New(rand.NewSource(seed))
			events := make([]telemetrySessionEvent, 0, 600)
			fields := []string{"Gear", "VehicleSpeed", "Location", "GpsHeading", "Odometer", "DetailedChargeState", "ACChargingEnergyIn", "DCChargingEnergyIn", "ACChargingPower", "DCChargingPower", "Soc", "Locked"}
			for i := 0; i < 600; i++ {
				field := fields[rng.Intn(len(fields))]
				var value any = float64(rng.Intn(8))
				switch field {
				case "Gear":
					value = []string{"D", "R", "P", "N"}[rng.Intn(4)]
				case "DetailedChargeState":
					value = []string{"Charging", "Starting", "Complete", "Disconnected", "Unknown"}[rng.Intn(5)]
				case "Location":
					value = map[string]any{"latitude": float64(rng.Intn(3)), "longitude": float64(rng.Intn(3))}
				case "Locked":
					value = rng.Intn(2) == 0
				}
				e := compactTestEvent(at, i, field, value)
				e.ObservedAt = e.ObservedAt.Add(time.Duration(rng.Intn(30)-15) * time.Second)
				if i > 0 && i%19 == 0 {
					e.EventID = events[i-1].EventID
				}
				events = append(events, e)
			}
			replayCompactReference(t, scope, events)
		})
	}
}

func TestCompactTelemetryScopeVersionBoundsAndUnsupported(t *testing.T) {
	scope := telemetryKey{"compact-scope", 4}
	s, err := newCompactTelemetryState(scope, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	at := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	e := compactTestEvent(at, 0, "Gear", "D")
	for _, wrong := range []telemetryKey{{"another", 4}, {scope.UserID, 5}} {
		if _, _, err = transitionCompactTelemetry(s, wrong, e); err == nil {
			t.Fatal("cross-scope transition accepted")
		}
	}
	for _, field := range []string{"UnknownField", "VehicleSpeed ", ""} {
		bad := e
		bad.FieldName = field
		if _, _, err = transitionCompactTelemetry(s, scope, bad); err == nil {
			t.Fatal("unsupported field accepted", field)
		}
	}
	for field := range telemetryFieldSpecs {
		found := false
		for _, supported := range compactTelemetryFields {
			found = found || supported == field
		}
		if !found {
			t.Fatal("field contract changed without a compact version update", field)
		}
	}
	bad := s
	bad.Version++
	if _, _, err = transitionCompactTelemetry(bad, scope, e); err == nil {
		t.Fatal("future contract version accepted")
	}
	s, _, err = transitionCompactTelemetry(s, scope, e)
	if err != nil {
		t.Fatal(err)
	}
	s.Drive.ID = "existing-legacy-id"
	s.Drive.PublicID = 73
	s.Drive.RouteCount = math.MaxInt64
	before, _ := json.Marshal(s)
	_, _, err = transitionCompactTelemetry(s, scope, compactTestEvent(at, 1, "Location", map[string]any{"latitude": 1.0, "longitude": 1.0}))
	after, _ := json.Marshal(s)
	if err == nil || string(before) != string(after) {
		t.Fatal("count overflow mutated state")
	}
	for _, count := range []int64{0, 1, 2, 100000} {
		s.Drive.RouteCount = count
		s.Drive.OdometerStart = nil
		s.Drive.OdometerEnd = nil
		q, _ := compactTelemetryQuality(*s.Drive)
		if (q == "observed") != (count >= 2) {
			t.Fatal("quality lost total route count", count)
		}
	}
	s.Drive.RouteCount = 100000
	s, _, err = transitionCompactTelemetry(s, scope, compactTestEvent(at, 2, "Gear", "P"))
	if err != nil {
		t.Fatal(err)
	}
	_, done, err := finalizeCompactTelemetry(s, scope, at.Add(3*time.Second))
	if err != nil || done.CompletedCount != 1 || done.Completed[0].ID != "existing-legacy-id" || done.Completed[0].PublicID != 73 || done.Completed[0].RouteCount != 100000 {
		t.Fatalf("legacy identity/count not preserved: %+v %v", done, err)
	}
}

func newCompactReplayOracle(scope telemetryKey, debounce time.Duration) *compactReplayOracle {
	return &compactReplayOracle{newTelemetrySessionMachine(debounce), scope, map[string]bool{}, map[string]time.Time{}}
}

func (o *compactReplayOracle) admit(event telemetrySessionEvent) bool {
	if event.EventID == "" || event.ObservedAt.IsZero() || !event.ObservedAt.After(o.latest[event.FieldName]) || o.seen[event.EventID] {
		return false
	}
	o.seen[event.EventID] = true
	o.latest[event.FieldName] = event.ObservedAt
	return true
}

func (o *compactReplayOracle) apply(event telemetrySessionEvent) {
	oldDrive, oldCharge := o.machine.drive, o.machine.charge
	o.machine.apply(event)
	for i, session := range []*telemetrySession{o.machine.drive, o.machine.charge} {
		if session != nil && ((i == 0 && oldDrive == nil) || (i == 1 && oldCharge == nil)) {
			session.ID = scopedSessionID("telemetry_mqtt", o.scope.UserID, o.scope.VehicleID, session.Kind, session.StartAt.UTC().Format(time.RFC3339Nano))
		}
	}
}

func compactTestEvent(at time.Time, n int, field string, value any) telemetrySessionEvent {
	return telemetrySessionEvent{EventID: fmt.Sprintf("event-%d-%s", n, field), ObservedAt: at.Add(time.Duration(n) * time.Second), FieldName: field, Value: value}
}

func TestCompactTelemetryReferenceContracts(t *testing.T) {
	scope := telemetryKey{UserID: "compact-reference", VehicleID: 7}
	at := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	t.Run("drive_stop_and_timer", func(t *testing.T) {
		o := newCompactReplayOracle(scope, 10*time.Second)
		for _, event := range []telemetrySessionEvent{
			compactTestEvent(at, 0, "Gear", "D"), compactTestEvent(at, 1, "Location", map[string]any{"latitude": 1.0, "longitude": 0.0}),
			compactTestEvent(at, 2, "Gear", "P"), compactTestEvent(at, 3, "VehicleSpeed", 0.0),
			compactTestEvent(at, 7, "Gear", "D"), compactTestEvent(at, 8, "Location", map[string]any{"latitude": 0.0, "longitude": 1.0}),
			compactTestEvent(at, 9, "Gear", "P"),
		} {
			if !o.admit(event) {
				t.Fatal("fixture admission")
			}
			o.apply(event)
		}
		if o.machine.finalizeDue(at.Add(18 * time.Second)) {
			t.Fatal("early completion")
		}
		if !o.machine.finalizeDue(at.Add(19*time.Second)) || o.machine.finalizeDue(at.Add(time.Minute)) {
			t.Fatal("completion uniqueness")
		}
		got := o.machine.completed[0]
		if len(got.Route) != 2 || got.EndAt == nil || !got.EndAt.Equal(at.Add(19*time.Second)) || got.QualityState != "observed" || got.CompletionKey != sessionCompletionKey(got) {
			t.Fatalf("reference=%+v", got)
		}
	})
	t.Run("charge_baseline_switch_null_zero", func(t *testing.T) {
		o := newCompactReplayOracle(scope, 10*time.Second)
		for _, event := range []telemetrySessionEvent{
			compactTestEvent(at, 0, "DetailedChargeState", "Charging"), compactTestEvent(at, 1, "ACChargingEnergyIn", 10.0),
			compactTestEvent(at, 2, "ACChargingEnergyIn", 10.0), compactTestEvent(at, 3, "DCChargingEnergyIn", 50.0),
			compactTestEvent(at, 4, "DCChargingEnergyIn", 49.0), compactTestEvent(at, 5, "DCChargingEnergyIn", 52.0),
			compactTestEvent(at, 6, "Soc", 0.0), compactTestEvent(at, 7, "DetailedChargeState", "Complete"),
		} {
			if !o.admit(event) {
				t.Fatal("fixture admission")
			}
			o.apply(event)
		}
		got := o.machine.completed[0]
		if len(got.ChargePoints) != 3 || got.EnergyAdded == nil || *got.EnergyAdded != 2 || got.ChargePoints[0].EnergyAdded == nil || *got.ChargePoints[0].EnergyAdded != 0 || got.ChargePoints[0].ChargerPower != nil || got.ChargePoints[2].BatteryLevel == nil || *got.ChargePoints[2].BatteryLevel != 0 {
			t.Fatalf("reference=%+v", got)
		}
	})
	t.Run("equivalent_admission", func(t *testing.T) {
		o := newCompactReplayOracle(scope, 10*time.Second)
		e := compactTestEvent(at, 2, "Gear", "D")
		if !o.admit(e) {
			t.Fatal("first event")
		}
		o.apply(e)
		if o.admit(e) {
			t.Fatal("duplicate")
		}
		e.EventID = "equal-new-id"
		if o.admit(e) {
			t.Fatal("equal field time")
		}
		e.ObservedAt = at.Add(time.Second)
		if o.admit(e) {
			t.Fatal("older field time")
		}
		e = compactTestEvent(at, 3, "Location", map[string]any{"latitude": 1.0, "longitude": 1.0})
		e.EventID = "event-2-Gear"
		if o.admit(e) {
			t.Fatal("duplicate identity across fields")
		}
		e.EventID = "unique-location"
		if !o.admit(e) {
			t.Fatal("retry with unique identity")
		}
		o.apply(e)
		if len(o.machine.drive.Route) != 1 {
			t.Fatal("rejected admissions changed reducer")
		}
	})
}

func TestCompactTelemetryRetainedStateAndOwnership(t *testing.T) {
	var inspect func(reflect.Type)
	inspect = func(kind reflect.Type) {
		if kind == reflect.TypeOf(time.Time{}) {
			return
		}
		switch kind.Kind() {
		case reflect.Map, reflect.Slice, reflect.Interface:
			t.Fatalf("unbounded retained shape: %s", kind)
		case reflect.Pointer, reflect.Array:
			inspect(kind.Elem())
		case reflect.Struct:
			for i := 0; i < kind.NumField(); i++ {
				inspect(kind.Field(i).Type)
			}
		}
	}
	inspect(reflect.TypeOf(compactTelemetryState{}))
	inspect(reflect.TypeOf(compactTelemetryDelta{}))
	scope := telemetryKey{"compact-bounds", 6}
	s, err := newCompactTelemetryState(scope, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	at := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	s, _, err = transitionCompactTelemetry(s, scope, compactTestEvent(at, 0, "Gear", "D"))
	if err != nil {
		t.Fatal(err)
	}
	s, _, err = transitionCompactTelemetry(s, scope, compactTestEvent(at, 1, "VehicleSpeed", 0.0))
	if err != nil {
		t.Fatal(err)
	}
	before := s
	value := map[string]any{"latitude": 1.0, "longitude": 0.0}
	s, delta, err := transitionCompactTelemetry(s, scope, compactTestEvent(at, 2, "Location", value))
	if err != nil {
		t.Fatal(err)
	}
	if delta.Route == nil || delta.Route.Speed == nil || *delta.Route.Speed != 0 || delta.Route.Power != nil {
		t.Fatal("zero/null defaults changed")
	}
	*delta.Route.Speed = 99
	value["latitude"] = 99.0
	if *s.LastSpeed != 0 || *before.LastSpeed != 0 || delta.Route.Latitude != 1 || before.Drive.RouteCount != 0 {
		t.Fatal("returned sample/input/state alias")
	}
	for i := 3; i < 20003; i++ {
		s, delta, err = transitionCompactTelemetry(s, scope, compactTestEvent(at, i, "Location", map[string]any{"latitude": 1.0, "longitude": 1.0}))
		if err != nil || delta.Route == nil || delta.CompletedCount != 0 {
			t.Fatalf("long stream at %d: %v", i, err)
		}
	}
	if s.Drive.RouteCount != 20001 {
		t.Fatal("sample totals truncated", s.Drive.RouteCount)
	}
	encoded, err := json.Marshal(s)
	if err != nil || len(encoded) > 12000 {
		t.Fatalf("retained state bytes=%d err=%v", len(encoded), err)
	}
	// Core work does not depend on the number of previously retained samples.
	low, high := cloneCompactTelemetryState(s), cloneCompactTelemetryState(s)
	low.Drive.RouteCount = 1
	high.Drive.RouteCount = 1_000_000
	e := compactTestEvent(at, 20004, "Location", map[string]any{"latitude": 1.0, "longitude": 1.0})
	measure := func(state compactTelemetryState) float64 {
		return testing.AllocsPerRun(50, func() {
			if _, _, err := transitionCompactTelemetry(state, scope, e); err != nil {
				panic(err)
			}
		})
	}
	if a, b := measure(low), measure(high); a != b {
		t.Fatalf("allocation count depends on history: %v vs %v", a, b)
	}
}

func TestCompactTelemetryTimestampAndChargeOverflow(t *testing.T) {
	scope := telemetryKey{"compact-time", 8}
	s, _ := newCompactTelemetryState(scope, time.Nanosecond)
	at := time.Date(2026, 1, 1, 0, 0, 0, 123456789, time.UTC)
	first := compactTestEvent(at, 0, "Gear", "D")
	s, _, _ = transitionCompactTelemetry(s, scope, first)
	park := first
	park.EventID = "park"
	park.Value = "P"
	park.ObservedAt = at.Add(time.Nanosecond)
	s, d, err := transitionCompactTelemetry(s, scope, park)
	if err != nil || !d.Applied {
		t.Fatal("nanosecond field order lost", err)
	}
	if _, d, err = transitionCompactTelemetry(s, scope, park); err != nil || d.Applied {
		t.Fatal("equal time re-applied")
	}
	old := park
	old.EventID = "old"
	old.ObservedAt = at
	if _, d, err = transitionCompactTelemetry(s, scope, old); err != nil || d.Applied {
		t.Fatal("older field time re-applied")
	}
	if _, d, err = finalizeCompactTelemetry(s, scope, at.Add(time.Nanosecond)); err != nil || d.Applied {
		t.Fatal("timer fired early")
	}
	_, d, err = finalizeCompactTelemetry(s, scope, at.Add(2*time.Nanosecond))
	if err != nil || !d.Applied || !d.Completed[0].EndAt.Equal(at.Add(2*time.Nanosecond)) {
		t.Fatal("timer precision changed")
	}
	// time.Time subtraction saturates across this range; event-driven completion
	// must still retain the event timestamp, as the legacy reducer does.
	o := newCompactReplayOracle(scope, time.Second)
	base := compactTestEvent(at, 0, "Gear", "D")
	o.admit(base)
	o.apply(base)
	stop := compactTestEvent(at, 1, "Gear", "P")
	o.admit(stop)
	o.apply(stop)
	s, _ = newCompactTelemetryState(scope, time.Second)
	s, _, _ = transitionCompactTelemetry(s, scope, base)
	s, _, _ = transitionCompactTelemetry(s, scope, stop)
	far := compactTestEvent(at, 2, "Locked", true)
	far.ObservedAt = time.Date(9999, 1, 1, 0, 0, 0, 0, time.UTC)
	o.admit(far)
	o.apply(far)
	_, d, err = transitionCompactTelemetry(s, scope, far)
	if err != nil || !reflect.DeepEqual(d.Completed[0], *compactReferenceHeader(&o.machine.completed[0])) {
		t.Fatal("large timestamp duration changed completion", err)
	}
	s, _ = newCompactTelemetryState(scope, time.Second)
	s, _, _ = transitionCompactTelemetry(s, scope, compactTestEvent(at, 0, "DetailedChargeState", "Charging"))
	s.Charge.ChargeCount = math.MaxInt64
	before, _ := json.Marshal(s)
	_, d, err = transitionCompactTelemetry(s, scope, compactTestEvent(at, 1, "Soc", 0.0))
	after, _ := json.Marshal(s)
	if err == nil || d.Applied || string(before) != string(after) {
		t.Fatal("charge count overflow changed state")
	}
}

func TestTelemetryPostgresCompactAdmissionReference(t *testing.T) {
	s, ref, _ := nativeShadowFixture(t)
	// Current timestamps keep the actual event-ID retention gate present. This
	// tests existing admission only; compact transitions are not wired into it.
	at := time.Now().UTC().Truncate(time.Microsecond)
	first := nativeShadowEvent(ref, at, 0, "Gear", "D")
	requireNativeShadowIngest(t, s, first, 1)
	point := nativeShadowEvent(ref, at, 1, "Location", map[string]any{"latitude": 1.0, "longitude": 0.0})
	requireNativeShadowIngest(t, s, point, 1)
	before := nativeShadowFingerprint(t, s, ref)
	restarted := &telemetryService{store: s.store, config: s.config}
	for _, record := range []telemetryRecord{first, point} {
		requireNativeShadowIngest(t, restarted, record, 0)
	}
	reused := nativeShadowEvent(ref, at, 2, "Soc", 10.0)
	reused.EventID = point.EventID
	requireNativeShadowIngest(t, restarted, reused, 0)
	if nativeShadowFingerprint(t, s, ref) != before {
		t.Fatal("duplicate/reconstructed-service admission changed durable data")
	}
	older := point
	older.EventID = "older-" + mustRandomToken(t)
	older.ObservedAt = at
	requireNativeShadowIngest(t, s, older, 0)
	// Different event identities and values at the same persisted field time are
	// rejected too. PostgreSQL stores microseconds, unlike the pure core's time.Time.
	equal := point
	equal.EventID = "equal-" + mustRandomToken(t)
	equal.Value = map[string]any{"latitude": 2.0, "longitude": 0.0}
	requireNativeShadowIngest(t, s, equal, 0)
	newer := point
	newer.EventID = "newer-" + mustRandomToken(t)
	newer.ObservedAt = point.ObservedAt.Add(time.Microsecond)
	requireNativeShadowIngest(t, s, newer, 1)
	var seen int
	if err := s.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_telemetry_event_buffer WHERE event_id=$1 AND user_id=$2 AND vehicle_id=$3`, older.EventID, ref.UserID, ref.VehicleID).Scan(&seen); err != nil || seen != 0 {
		t.Fatalf("serial stale precheck consumed identity: %d %v", seen, err)
	}
	older.ObservedAt = at.Add(2 * time.Second)
	requireNativeShadowIngest(t, s, older, 1) // the earlier stale rejection did not consume this ID
	precision := older
	precision.EventID = "precision-" + mustRandomToken(t)
	precision.ObservedAt = older.ObservedAt.Add(time.Nanosecond)
	requireNativeShadowIngest(t, s, precision, 0)
	if err := s.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_telemetry_event_buffer WHERE event_id=$1 AND user_id=$2 AND vehicle_id=$3`, precision.EventID, ref.UserID, ref.VehicleID).Scan(&seen); err != nil || seen != 1 {
		t.Fatalf("guarded UPSERT rejection did not retain inserted ID: %d %v", seen, err)
	}
	precision.ObservedAt = older.ObservedAt.Add(time.Microsecond)
	requireNativeShadowIngest(t, s, precision, 0) // ID was consumed before this later guarded rejection
	fresh := precision
	fresh.EventID = "after-precision-" + mustRandomToken(t)
	requireNativeShadowIngest(t, s, fresh, 1)
	var count int
	if err := s.store.pool.QueryRow(context.Background(), `SELECT route_point_count FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND vehicle_id=$2`, ref.UserID, ref.VehicleID).Scan(&count); err != nil || count != 4 {
		t.Fatalf("admitted sample count=%d err=%v", count, err)
	}
	t.Log("actual PostgreSQL admission: stale-first ID remains reusable; post-insert precision rejection consumes ID; unchanged replay and cross-field duplicate reject; fresh unique microsecond event admits")
	other, otherRef, _ := nativeShadowFixture(t)
	sameIdentity := first
	sameIdentity.VINHash = otherRef.VINHash
	requireNativeShadowIngest(t, other, sameIdentity, 1)
	if readNativeShadowEvidence(t, other, otherRef).ID == readNativeShadowEvidence(t, s, ref).ID {
		t.Fatal("cross-scope native identity collision")
	}
}
