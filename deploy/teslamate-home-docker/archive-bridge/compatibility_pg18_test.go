package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func TestCompatibilityRealPG18MigratedV430(t *testing.T) {
	dsn := os.Getenv("MATELINK_COMPAT_DATABASE_URL")
	if dsn == "" {
		t.Skip("isolated PG18 fixture URL not supplied")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	var marker, version string
	if err := pool.QueryRow(ctx, "SELECT marker,current_setting('server_version') FROM matelink_compat_fixture").Scan(&marker, &version); err != nil || marker != "SYNTHETIC-COMPAT430-20261015" || len(version) < 3 || version[:3] != "18." {
		t.Fatalf("not disposable PG18: %v", err)
	}
	source := NewPostgresSource(pool, 7)
	drives, err := source.FetchDrives(ctx, 0, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(drives) != 1 || drives[0].ID != 100 || len(drives[0].Route) != 2 || drives[0].Route[0].Power == nil || *drives[0].Route[0].Power != -3 || drives[0].Route[1].Speed != nil {
		t.Fatalf("drive mapping/paging/negative/null changed: %#v", drives)
	}
	next, err := source.FetchDrives(ctx, 100, 1)
	if err != nil || len(next) != 1 || next[0].ID != 101 {
		t.Fatalf("keyset paging changed: %#v %v", next, err)
	}
	charges, err := source.FetchCharges(ctx, 0, 500)
	if err != nil {
		t.Fatal(err)
	}
	if len(charges) != 2 || charges[0].ID != 501 || charges[0].Cost == nil || *charges[0].Cost != -1 || charges[0].EnergyAdded == nil || *charges[0].EnergyAdded != 0 || len(charges[0].ChargePoints) != 3 || charges[0].ChargePoints[0].BatteryLevel == nil || *charges[0].ChargePoints[0].BatteryLevel != 69 || charges[0].ChargePoints[1].BatteryLevel != nil || charges[1].ID != 502 || charges[1].EnergyAdded == nil || *charges[1].EnergyAdded != 2.5 || len(charges[1].ChargePoints) != 4 || charges[1].ChargePoints[1].BatteryLevel != nil {
		t.Fatalf("charge scope/null/zero/negative changed: %#v", charges)
	}
	foreign := NewPostgresSource(pool, 8)
	other, err := foreign.FetchDrives(ctx, 0, 500)
	if err != nil || len(other) != 1 || other[0].ID != 200 {
		t.Fatalf("foreign vehicle scope not isolated: %#v %v", other, err)
	}
	t.Run("real_poll_tls_receipt_and_cursor", func(t *testing.T) {
		requests := 0
		sink := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var batch ArchiveBatch
			if err := json.NewDecoder(r.Body).Decode(&batch); err != nil {
				t.Error(err)
				w.WriteHeader(400)
				return
			}
			if batch.SourceVehicleID != "7" || batch.SourceInstanceID != "instance-1" {
				t.Error("cross-scope batch")
			}
			requests++
			_ = json.NewEncoder(w).Encode(map[string]any{"data": map[string]int{"imported_drives": len(batch.Drives), "imported_charges": len(batch.Charges)}})
		}))
		defer sink.Close()
		config := testConfig(sink.URL, filepath.Join(t.TempDir(), "cursor.json"))
		config.SourceVehicleID = "7"
		config.SourceCarID = 7
		config.BatchSize = 500
		bridge := NewBridge(config, source)
		bridge.httpClient = sink.Client()
		if err := bridge.PollOnce(ctx); err != nil {
			t.Fatal(err)
		}
		cursor, err := loadCursor(config.StateFile)
		if err != nil || cursor.LastDriveID != 102 || cursor.LastChargeID != 502 || requests != 4 {
			t.Fatalf("poll cursor/receipts=%#v requests=%d err=%v", cursor, requests, err)
		}
		if err := bridge.PollOnce(ctx); err != nil || requests != 4 {
			t.Fatalf("duplicate poll imported unchanged IDs: %v", err)
		}
	})
}
