package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestInviteCodeValidation(t *testing.T) {
	app := &app{}

	// 1. Invalid format
	req := httptest.NewRequest(http.MethodPost, "/v1/auth/invite/validate", bytes.NewBufferString(`{"code":"???"}`))
	rec := httptest.NewRecorder()
	app.validateInvite(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected status 400 for invalid format, got %d", rec.Code)
	}

	// 2. Pre-seeded default code: MATELINK2026
	req = httptest.NewRequest(http.MethodPost, "/v1/auth/invite/validate", bytes.NewBufferString(`{"code":"matelink2026"}`))
	rec = httptest.NewRecorder()
	app.validateInvite(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200 for valid code, got %d: %s", rec.Code, rec.Body.String())
	}
	var res map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &res); err != nil {
		t.Fatal(err)
	}
	if res["valid"] != true || res["code"] != "MATELINK2026" {
		t.Fatalf("unexpected response: %+v", res)
	}

	// 3. GET with query param
	req = httptest.NewRequest(http.MethodGet, "/v1/auth/invite/validate?code=MATELINK2026", nil)
	rec = httptest.NewRecorder()
	app.validateInvite(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200 for GET, got %d", rec.Code)
	}

	// 4. Unknown code
	req = httptest.NewRequest(http.MethodPost, "/v1/auth/invite/validate", bytes.NewBufferString(`{"code":"NONEXISTENT99"}`))
	rec = httptest.NewRecorder()
	app.validateInvite(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected status 400 for non-existent code, got %d", rec.Code)
	}

	ctx := context.Background()
	// 5. Expired code test
	err := app.store.createInviteCode(ctx, "EXPIREDCODE1", 10, time.Now().UTC().Add(-time.Hour), "Expired test")
	if err != nil {
		t.Fatal(err)
	}
	req = httptest.NewRequest(http.MethodPost, "/v1/auth/invite/validate", bytes.NewBufferString(`{"code":"EXPIREDCODE1"}`))
	rec = httptest.NewRecorder()
	app.validateInvite(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected status 400 for expired code, got %d", rec.Code)
	}

	// 6. Exhausted code test
	err = app.store.createInviteCode(ctx, "ONEUSEONLY99", 1, time.Now().UTC().Add(time.Hour), "Single use")
	if err != nil {
		t.Fatal(err)
	}
	// Claim it
	if err := app.store.claimInviteCode(ctx, "ONEUSEONLY99", "user-1", "android"); err != nil {
		t.Fatal(err)
	}
	// Next validate must fail as exhausted
	req = httptest.NewRequest(http.MethodPost, "/v1/auth/invite/validate", bytes.NewBufferString(`{"code":"ONEUSEONLY99"}`))
	rec = httptest.NewRecorder()
	app.validateInvite(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected status 400 for exhausted code, got %d", rec.Code)
	}
}
