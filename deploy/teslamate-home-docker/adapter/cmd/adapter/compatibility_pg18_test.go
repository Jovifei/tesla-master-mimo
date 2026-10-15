package main

import (
	"context"
	"database/sql"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"net/url"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

// Requires the explicitly disposable marker installed by fixture-v430.sql.
// This test only SELECTs; normal developer/CI runs skip it without an explicit URL.
func TestCompatibilityRealPG18MigratedV430(t *testing.T) {
	dsn := os.Getenv("MATELINK_COMPAT_DATABASE_URL")
	if dsn == "" {
		t.Skip("isolated PG18 fixture URL not supplied")
	}
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	var marker, version string
	if err := db.QueryRowContext(ctx, "SELECT marker,current_setting('server_version') FROM matelink_compat_fixture").Scan(&marker, &version); err != nil || marker != "SYNTHETIC-COMPAT430-20261015" || len(version) < 3 || version[:3] != "18." {
		t.Fatalf("not the disposable PG18 fixture: %v", err)
	}
	store := &postgresStore{db: db, loc: time.UTC}
	t.Run("snapshot", func(t *testing.T) {
		value, err := store.Snapshot(ctx, 7)
		if err != nil {
			t.Fatal(err)
		}
		driving := value.Status["driving_details"].(map[string]any)
		climate := value.Status["climate_details"].(map[string]any)
		if value.Units["unit_of_length"] != "km" || driving["power"] != int64(-2) || climate["inside_temp"] != nil {
			t.Fatalf("native units/negative/null changed: %#v", value.Status)
		}
	})
	t.Run("parked_linked_charge", func(t *testing.T) {
		value, err := store.Parked(ctx, 7, 100, 101)
		if err != nil {
			t.Fatal(err)
		}
		if value.LinkedCharge == nil || value.LinkedCharge.ChargeID != 501 || value.LinkedCharge.EnergyAddedKWh == nil || *value.LinkedCharge.EnergyAddedKWh != 0 {
			t.Fatal("linked charging_process missing or zero coerced")
		}
	})
	t.Run("standby", func(t *testing.T) {
		value, err := store.Standby(ctx, 7)
		if err != nil {
			t.Fatal(err)
		}
		if len(value) != 1 {
			t.Fatalf("standby windows=%d", len(value))
		}
	})
	t.Run("cross_car", func(t *testing.T) {
		if _, err := store.Parked(ctx, 8, 100, 101); err != sql.ErrNoRows {
			t.Fatalf("foreign car bounds not rejected: %v", err)
		}
	})
	t.Run("unsupported_schema_is_error", func(t *testing.T) {
		negativeDSN := os.Getenv("MATELINK_COMPAT_UNSUPPORTED_DATABASE_URL")
		if negativeDSN == "" {
			t.Skip("unsupported schema fixture not supplied")
		}
		unsupportedDB, err := sql.Open("pgx", negativeDSN)
		if err != nil {
			t.Fatal(err)
		}
		defer unsupportedDB.Close()
		unsupportedStore := &postgresStore{db: unsupportedDB, loc: time.UTC}
		_, err = unsupportedStore.Parked(ctx, 7, 100, 101)
		var pgError *pgconn.PgError
		if !errors.As(err, &pgError) || pgError.Code != "42703" {
			t.Fatalf("unsupported schema must fail explicitly: %v", err)
		}
	})
}

func TestCompatibilityRealAPI1250ProxyV430Schema(t *testing.T) {
	endpoint := os.Getenv("MATELINK_COMPAT_UPSTREAM_URL")
	if endpoint == "" {
		t.Skip("isolated API URL not supplied")
	}
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Hostname() != "api" {
		t.Fatal("only isolated API alias is permitted")
	}
	proxy := testServer(&recordingStore{})
	proxy.proxy = httputil.NewSingleHostReverseProxy(parsed)
	client := &http.Client{Timeout: 10 * time.Second}
	for _, path := range []string{"/api/v1/cars", "/api/v1/cars/7/drives?startDate=2026-10-01T00%3A00%3A00Z&endDate=2026-10-02T00%3A00%3A00Z&page=1&size=1", "/api/v1/cars/7/charges?startDate=2026-10-01T00%3A00%3A00Z&endDate=2026-10-02T00%3A00%3A00Z"} {
		response, err := client.Get(endpoint + path)
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
		response.Body.Close()
		if err != nil || response.StatusCode != 200 {
			t.Fatalf("isolated API contract failed path=%s status=%d body=%s error=%v", path, response.StatusCode, string(body), err)
		}
		request := httptest.NewRequest(http.MethodGet, path, nil)
		request.Header.Set("Authorization", "Bearer "+validTestSecret)
		recorder := httptest.NewRecorder()
		proxy.routes().ServeHTTP(recorder, request)
		if recorder.Code != 200 || recorder.Body.String() != string(body) {
			t.Fatalf("real API proxy modified response for %s", path)
		}
	}
}

func TestCompatibilityRunningImageEndpoints(t *testing.T) {
	endpoint := os.Getenv("MATELINK_COMPAT_RUNNING_ADAPTER_URL")
	if endpoint == "" {
		t.Skip("isolated adapter image not supplied")
	}
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Hostname() != "adapter" {
		t.Fatal("only isolated adapter alias permitted")
	}
	client := &http.Client{Timeout: 10 * time.Second}
	for _, path := range []string{"/api/matelink/v1/cars/7/snapshot", "/api/matelink/v1/cars/7/parked/100/101", "/api/matelink/v1/cars/7/standby", "/api/v1/cars/7/drives"} {
		req, _ := http.NewRequest(http.MethodGet, endpoint+path, nil)
		req.Header.Set("Authorization", "Bearer "+validTestSecret)
		response, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		if response.StatusCode != 200 {
			t.Fatalf("running image %s status=%d body=%s", path, response.StatusCode, body)
		}
	}
	response, err := client.Get(endpoint + "/api/matelink/v1/cars/7/snapshot")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 401 {
		t.Fatal("running image accepted unauthenticated request")
	}
	req, _ := http.NewRequest(http.MethodGet, endpoint+"/api/matelink/v1/cars/8/parked/100/101", nil)
	req.Header.Set("Authorization", "Bearer "+validTestSecret)
	response, err = client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode == 200 {
		t.Fatal("running image accepted foreign vehicle drive bounds")
	}
}
