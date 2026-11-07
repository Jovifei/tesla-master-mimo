package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func ptr[T any](v T) *T { return &v }

func syntheticDrive(id int64) sourceDrive {
	start := time.Date(2026, 10, 30, 0, 0, 0, 0, time.UTC).Add(time.Duration(id) * time.Minute)
	end := start.Add(time.Minute)
	return sourceDrive{ID: id, StartedAt: start, EndedAt: end,
		Start:        &sourcePosition{ID: id * 2, CarID: 8, DriveID: id, ObservedAt: start, SocPercent: ptr(0)},
		End:          &sourcePosition{ID: id*2 + 1, CarID: 8, DriveID: id, ObservedAt: end, SocPercent: ptr(75)},
		StartRatedKm: ptr(0.0), EndRatedKm: ptr(320.5), OutsideTempAvg: ptr(-4.0)}
}

type fakeSource struct {
	rows                  []sourceDrive
	high                  int64
	highCalls, fetchCalls int
}

type failingSource struct{ failHigh, sawHighDeadline, sawFetchDeadline bool }

func (s *failingSource) HighWater(ctx context.Context, _ int64) (int64, error) {
	_, s.sawHighDeadline = ctx.Deadline()
	if !s.sawHighDeadline {
		return 0, errors.New("missing source deadline")
	}
	if s.failHigh {
		return 0, errors.New("synthetic source failure")
	}
	return 1, nil
}

func (s *failingSource) Fetch(ctx context.Context, _, _ int64, _ int) ([]sourceDrive, error) {
	_, s.sawFetchDeadline = ctx.Deadline()
	if !s.sawFetchDeadline {
		return nil, errors.New("missing source deadline")
	}
	return nil, errors.New("synthetic source failure")
}

func (s *fakeSource) HighWater(_ context.Context, after int64) (int64, error) {
	s.highCalls++
	if s.high <= after {
		return 0, nil
	}
	return s.high, nil
}
func (s *fakeSource) Fetch(_ context.Context, after, high int64, limit int) ([]sourceDrive, error) {
	s.fetchCalls++
	var out []sourceDrive
	for _, d := range s.rows {
		if d.ID > after && d.ID <= high && len(out) < limit {
			out = append(out, d)
		}
	}
	return out, nil
}

func syntheticConfig(t *testing.T, base string) config {
	t.Helper()
	dir := t.TempDir()
	original := filepath.Join(dir, "original-state.json")
	if err := os.WriteFile(original, []byte("original sentinel"), 0o600); err != nil {
		t.Fatal(err)
	}
	env := map[string]string{
		"DATABASE_URL": "postgres://fixture:synthetic@127.0.0.1:5433/synthetic",
		"ARCHIVE_URL":  base, "ARCHIVE_TOKEN": "synthetic-token", "ARCHIVE_VEHICLE_ID": "7",
		"ARCHIVE_SOURCE_INSTANCE_ID": "fixture-instance", "ARCHIVE_SOURCE_VEHICLE_ID": "8",
		"STATE_FILE": original, "BATTERY_TREND_STATE_FILE": filepath.Join(dir, "battery-trend-state.json"),
		"BATTERY_TREND_SOURCE_TIMEZONE": "UTC",
	}
	c, err := loadConfig(func(k string) (string, bool) { v, ok := env[k]; return v, ok })
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func syntheticReceipt(w http.ResponseWriter, request trendRequest, inserted, unchanged, conflicts int) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = fmt.Fprintf(w, `{"data":{"schema_version":1,"batch_id":%q,"payload_sha256":%q,"inserted":%d,"unchanged":%d,"conflicted":%d}}`,
		request.BatchID, request.PayloadSHA256, inserted, unchanged, conflicts)
}

func TestCanonicalPayloadKeepsUnitsZeroNullAndUTC(t *testing.T) {
	d := syntheticDrive(1)
	o, err := observationFromDrive(d, 8)
	if err != nil {
		t.Fatal(err)
	}
	body, batch, hash, err := makeTrendRequest("fixture-instance", "8", []trendObservation{o})
	if err != nil {
		t.Fatal(err)
	}
	var request trendRequest
	if err = json.Unmarshal(body, &request); err != nil {
		t.Fatal(err)
	}
	canonical, err := json.Marshal(request.trendPayload)
	if err != nil {
		t.Fatal(err)
	}
	want := `{"schema_version":1,"source":"teslamate","source_instance_id":"fixture-instance","source_vehicle_id":"8","units":{"rated_range":"km","temperature":"C","soc":"percent"},"observations":[{"source_record_id":"teslamate:drive:1","started_at":"2026-10-30T00:01:00Z","ended_at":"2026-10-30T00:02:00Z","start":{"soc_percent":0,"soc_position_observed_at":"2026-10-30T00:01:00Z","soc_position_id":2,"rated_range_km":0,"rated_range_source":"teslamate_drive_start_summary","rated_range_observed_at":null},"end":{"soc_percent":75,"soc_position_observed_at":"2026-10-30T00:02:00Z","soc_position_id":3,"rated_range_km":320.5,"rated_range_source":"teslamate_drive_end_summary","rated_range_observed_at":null},"outside_temp_avg_c":-4,"temperature_source":"teslamate_drive_average"}]}`
	if string(canonical) != want {
		t.Fatalf("canonical payload differs: %s", canonical)
	}
	digest := sha256.Sum256([]byte(want))
	if hash != hex.EncodeToString(digest[:]) || batch != "battery-v1-"+hash {
		t.Fatal("canonical hash or stable batch differs")
	}
	if request.BatchID != batch || request.PayloadSHA256 != hash {
		t.Fatal("wire payload omitted batch identity")
	}
	d.Start, d.End, d.StartRatedKm, d.EndRatedKm, d.OutsideTempAvg = nil, nil, nil, nil, nil
	missing, err := observationFromDrive(d, 8)
	if err != nil || missing.Start == nil || missing.Start.SocPercent != nil || missing.Start.RatedRangeSource != nil || missing.TemperatureSource != nil {
		t.Fatal("missing fields must stay null")
	}
}

func TestServerCanonicalGolden(t *testing.T) {
	start := "2026-10-28T01:00:00Z"
	end := "2026-10-28T01:30:00Z"
	startSource := "teslamate_drive_start_summary"
	endSource := "teslamate_drive_end_summary"
	tempSource := "teslamate_drive_average"
	o := trendObservation{
		SourceRecordID: "teslamate:drive:42", StartedAt: start, EndedAt: end,
		Start: &trendBoundary{SocPercent: ptr(80), SocPositionObservedAt: &start, SocPositionID: ptr(int64(101)),
			RatedRangeKm: ptr(320.5), RatedRangeSource: &startSource},
		End: &trendBoundary{SocPercent: ptr(72), SocPositionObservedAt: &end, SocPositionID: ptr(int64(102)),
			RatedRangeKm: ptr(290.25), RatedRangeSource: &endSource},
		OutsideTempAvgC: ptr(19.5), TemperatureSource: &tempSource,
	}
	body, _, hash, err := makeTrendRequest("source-a", "source-car", []trendObservation{o})
	if err != nil || hash != "7a5fe2298155881c59cbd5598b6043ad8783551ca89823aa1bf35b820ac310de" {
		t.Fatalf("server canonical golden mismatch: %s %v", hash, err)
	}
	fixture, err := os.ReadFile("testdata/server-golden.json")
	if err != nil || !bytes.Equal(bytes.TrimSpace(fixture), body) {
		t.Fatal("shared server fixture does not match the bridge wire bytes")
	}
}

func TestRejectInvalidSourceIdentityWindowAndEvidence(t *testing.T) {
	base := syntheticDrive(1)
	for name, mutate := range map[string]func(*sourceDrive){
		"foreign car":    func(d *sourceDrive) { d.Start.CarID = 9 },
		"foreign drive":  func(d *sourceDrive) { d.End.DriveID = 2 },
		"late position":  func(d *sourceDrive) { d.Start.ObservedAt = d.StartedAt.Add(time.Second + time.Nanosecond) },
		"invalid soc":    func(d *sourceDrive) { d.Start.SocPercent = ptr(101) },
		"invalid range":  func(d *sourceDrive) { d.StartRatedKm = ptr(-1.0) },
		"invalid window": func(d *sourceDrive) { d.EndedAt = d.StartedAt.Add(-time.Second) },
	} {
		d := base
		d.Start, d.End = ptr(*base.Start), ptr(*base.End)
		mutate(&d)
		if _, err := observationFromDrive(d, 8); err == nil {
			t.Fatalf("accepted %s", name)
		}
	}
}

func TestFailedOrIncompleteReceiptsNeverAdvanceCursor(t *testing.T) {
	for _, mode := range []string{"401", "403", "404", "409", "413", "422", "503", "202", "missing", "schema", "batch", "hash", "count", "negative", "conflict", "content-type"} {
		t.Run(mode, func(t *testing.T) {
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				body, _ := io.ReadAll(r.Body)
				var request trendRequest
				if json.Unmarshal(body, &request) != nil {
					t.Error("invalid request")
				}
				if r.URL.Path != "/api/v1/cars/7/history/archive/battery-trend/import" || r.Header.Get("X-MateLink-Archive-Binding") != "synthetic-token" {
					t.Error("request scope or auth")
				}
				switch mode {
				case "401":
					w.WriteHeader(401)
				case "403":
					w.WriteHeader(403)
				case "404":
					w.WriteHeader(404)
				case "409":
					w.WriteHeader(409)
				case "413":
					w.WriteHeader(413)
				case "422":
					w.WriteHeader(422)
				case "503":
					w.WriteHeader(503)
				case "202":
					w.WriteHeader(202)
				case "missing":
					w.Header().Set("Content-Type", "application/json")
					_, _ = w.Write([]byte(`{"data":{}}`))
				case "schema":
					w.Header().Set("Content-Type", "application/json")
					_, _ = fmt.Fprintf(w, `{"data":{"schema_version":2,"batch_id":%q,"payload_sha256":%q,"inserted":1,"unchanged":0,"conflicted":0}}`, request.BatchID, request.PayloadSHA256)
				case "batch":
					request.BatchID = "different-batch"
					syntheticReceipt(w, request, 1, 0, 0)
				case "hash":
					request.PayloadSHA256 = strings.Repeat("0", 64)
					syntheticReceipt(w, request, 1, 0, 0)
				case "count":
					syntheticReceipt(w, request, 0, 0, 0)
				case "negative":
					syntheticReceipt(w, request, -1, 2, 0)
				case "conflict":
					syntheticReceipt(w, request, 1, 0, 1)
				case "content-type":
					_, _ = w.Write([]byte(`{}`))
				}
			}))
			defer server.Close()
			c := syntheticConfig(t, server.URL)
			b := bridge{config: c, source: &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}, client: server.Client()}
			if err := b.pollOnce(context.Background()); err == nil {
				t.Fatal("invalid receipt accepted")
			}
			state, err := loadState(c)
			if err != nil || state.LastDriveID != 0 || state.Pending == nil {
				t.Fatalf("cursor advanced or pending lost: %v", err)
			}
			original, _ := os.ReadFile(c.OriginalStateFile)
			if string(original) != "original sentinel" {
				t.Fatal("original bridge state changed")
			}
			stored, _ := os.ReadFile(c.StateFile)
			if strings.Contains(string(stored), c.ArchiveToken) {
				t.Fatal("token stored in state")
			}
		})
	}
}

func TestLostReceiptReplaysFrozenBatchWithoutRereadingSource(t *testing.T) {
	var bodies [][]byte
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		bodies = append(bodies, append([]byte(nil), body...))
		var request trendRequest
		if json.Unmarshal(body, &request) != nil {
			t.Error("request malformed")
		}
		if len(bodies) == 1 {
			hijacker, ok := w.(http.Hijacker)
			if !ok {
				t.Error("no hijacker")
				return
			}
			conn, _, err := hijacker.Hijack()
			if err != nil {
				t.Error(err)
				return
			}
			_ = conn.Close()
			return
		}
		syntheticReceipt(w, request, 0, len(request.Observations), 0)
	}))
	defer server.Close()
	c := syntheticConfig(t, server.URL)
	source := &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}
	b := bridge{config: c, source: source, client: server.Client()}
	if err := b.pollOnce(context.Background()); err == nil {
		t.Fatal("dropped response accepted")
	}
	changed := syntheticDrive(1)
	changed.OutsideTempAvg = ptr(100.0)
	restartedSource := &fakeSource{rows: []sourceDrive{changed}, high: 1}
	restarted := bridge{config: c, source: restartedSource, client: server.Client()}
	if err := restarted.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if source.fetchCalls != 1 || restartedSource.fetchCalls != 0 || restartedSource.highCalls != 0 ||
		len(bodies) != 2 || string(bodies[0]) != string(bodies[1]) {
		t.Fatal("retry did not use frozen bytes")
	}
	state, err := loadState(c)
	if err != nil || state.LastDriveID != 1 || state.HighWaterID != 0 || state.Pending != nil {
		t.Fatal("verified receipt did not advance exactly once")
	}
}

func TestFrozenHighWaterAndFiftyItemBatches(t *testing.T) {
	var sizes []int
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request trendRequest
		if json.NewDecoder(r.Body).Decode(&request) != nil {
			t.Error("request malformed")
		}
		sizes = append(sizes, len(request.Observations))
		syntheticReceipt(w, request, len(request.Observations), 0, 0)
	}))
	defer server.Close()
	c := syntheticConfig(t, server.URL)
	s := &fakeSource{high: 101}
	for i := int64(1); i <= 101; i++ {
		s.rows = append(s.rows, syntheticDrive(i))
	}
	b := bridge{config: c, source: s, client: server.Client()}
	if err := b.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	s.high = 102
	s.rows = append(s.rows, syntheticDrive(102))
	for i := 0; i < 3; i++ {
		if err := b.pollOnce(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	if fmt.Sprint(sizes) != "[50 50 1 1]" || s.highCalls != 2 {
		t.Fatalf("unexpected snapshot batches: %v, %d", sizes, s.highCalls)
	}
	state, err := loadState(c)
	if err != nil || state.LastDriveID != 102 {
		t.Fatal("wrong final cursor")
	}
}

func TestStateScopeAndOriginalPathCannotBeReused(t *testing.T) {
	c := syntheticConfig(t, "https://synthetic.example")
	if err := distinctPaths(c.OriginalStateFile, c.OriginalStateFile); err == nil {
		t.Fatal("same state accepted")
	}
	hardlink := filepath.Join(filepath.Dir(c.StateFile), "hardlink.json")
	if err := os.Link(c.OriginalStateFile, hardlink); err == nil {
		if distinctPaths(c.OriginalStateFile, hardlink) == nil {
			t.Fatal("hardlinked state accepted")
		}
	}
	symlink := filepath.Join(filepath.Dir(c.StateFile), "symlink.json")
	if err := os.Symlink(c.OriginalStateFile, symlink); err == nil {
		if distinctPaths(c.OriginalStateFile, symlink) == nil {
			t.Fatal("symlinked state accepted")
		}
	}
	state := trendState{Version: 1, ScopeHash: c.ScopeHash, LastDriveID: 1}
	if err := saveState(c, state); err != nil {
		t.Fatal(err)
	}
	other := c
	other.ScopeHash = strings.Repeat("0", 64)
	if _, err := loadState(other); err == nil {
		t.Fatal("different source scope accepted")
	}
	first, err := acquireStateLock(c.StateFile + ".lock")
	if err != nil {
		t.Fatal(err)
	}
	if second, err := acquireStateLock(c.StateFile + ".lock"); err == nil {
		_ = second.Close()
		t.Fatal("concurrent writer accepted")
	}
	_ = first.Close()
	second, err := acquireStateLock(c.StateFile + ".lock")
	if err != nil {
		t.Fatal("lock not released on close")
	}
	_ = second.Close()
}

func TestSourceTimezoneRequiresExplicitUTC(t *testing.T) {
	c := syntheticConfig(t, "https://synthetic.example")
	env := map[string]string{
		"DATABASE_URL": c.DatabaseURL, "ARCHIVE_URL": c.ArchiveURL, "ARCHIVE_TOKEN": c.ArchiveToken,
		"ARCHIVE_VEHICLE_ID": c.ArchiveVehicleID, "ARCHIVE_SOURCE_INSTANCE_ID": c.SourceInstanceID,
		"ARCHIVE_SOURCE_VEHICLE_ID": c.SourceVehicleID, "STATE_FILE": c.OriginalStateFile,
		"BATTERY_TREND_STATE_FILE": c.StateFile,
	}
	lookup := func(k string) (string, bool) { v, ok := env[k]; return v, ok }
	if _, err := loadConfig(lookup); err == nil {
		t.Fatal("missing source timezone accepted")
	}
	env["BATTERY_TREND_SOURCE_TIMEZONE"] = "Asia/Shanghai"
	if _, err := loadConfig(lookup); err == nil {
		t.Fatal("unverified source timezone accepted")
	}
	env["BATTERY_TREND_SOURCE_TIMEZONE"] = "UTC"
	if _, err := loadConfig(lookup); err != nil {
		t.Fatal(err)
	}
}

func TestCorruptPendingStateNeverResetsOrPosts(t *testing.T) {
	c := syntheticConfig(t, "https://synthetic.example")
	source := &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}
	observation, err := observationFromDrive(source.rows[0], 8)
	if err != nil {
		t.Fatal(err)
	}
	body, _, _, err := makeTrendRequest(c.SourceInstanceID, c.SourceVehicleID, []trendObservation{observation})
	if err != nil {
		t.Fatal(err)
	}
	state := trendState{Version: 1, ScopeHash: c.ScopeHash, HighWaterID: 1, Pending: &pendingBatch{Body: body, LastDriveID: 1}}
	if err = saveState(c, state); err != nil {
		t.Fatal(err)
	}
	state.Pending.Body[len(state.Pending.Body)-2] ^= 1
	data, err := json.Marshal(state)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(c.StateFile, data, 0o600); err != nil {
		t.Fatal(err)
	}
	b := bridge{config: c, source: source, client: &http.Client{Timeout: time.Second}}
	if err = b.pollOnce(context.Background()); err == nil {
		t.Fatal("corrupt pending state accepted")
	}
	if source.fetchCalls != 0 || source.highCalls != 0 {
		t.Fatal("corrupt state triggered a source read")
	}
	stored, _ := os.ReadFile(c.StateFile)
	if string(stored) != string(data) {
		t.Fatal("corrupt state was silently reset")
	}
	original, _ := os.ReadFile(c.OriginalStateFile)
	if string(original) != "original sentinel" {
		t.Fatal("original bridge state changed")
	}
}

func TestSourceFailureNeverAdvancesAndUsesDeadline(t *testing.T) {
	for _, failHigh := range []bool{true, false} {
		c := syntheticConfig(t, "https://synthetic.example")
		source := &failingSource{failHigh: failHigh}
		b := bridge{config: c, source: source, client: &http.Client{}}
		if err := b.pollOnce(context.Background()); err == nil {
			t.Fatal("source failure accepted")
		}
		if !source.sawHighDeadline || (!failHigh && !source.sawFetchDeadline) {
			t.Fatal("source call lacked deadline")
		}
		state, err := loadState(c)
		if err != nil || state.LastDriveID != 0 || state.Pending != nil {
			t.Fatal("source failure advanced state")
		}
		original, _ := os.ReadFile(c.OriginalStateFile)
		if string(original) != "original sentinel" {
			t.Fatal("source failure changed original bridge state")
		}
	}
}

func TestRedirectDoesNotForwardBindingOrAdvanceCursor(t *testing.T) {
	var redirected atomic.Bool
	other := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { redirected.Store(true) }))
	defer other.Close()
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL, http.StatusTemporaryRedirect)
	}))
	defer server.Close()
	c := syntheticConfig(t, server.URL)
	b := bridge{config: c, source: &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}, client: server.Client()}
	if err := b.pollOnce(context.Background()); err == nil {
		t.Fatal("redirect accepted")
	}
	state, err := loadState(c)
	if redirected.Load() || err != nil || state.LastDriveID != 0 || state.Pending == nil {
		t.Fatal("redirect forwarded the binding or advanced state")
	}
}
