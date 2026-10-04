package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

// Discovery may fail upstream while this application's session and persisted
// vehicle ownership remain valid. History must retain its own auth/owner gate.
type failingHistoryDiscoveryProvider struct {
	err   error
	calls int
}

func (p *failingHistoryDiscoveryProvider) Vehicles(context.Context, string) ([]vehicle, error) {
	p.calls++
	return nil, p.err
}

func (p *failingHistoryDiscoveryProvider) Status(context.Context, string, int) (vehicleStatus, error) {
	panic("history reads must not request live vehicle status")
}

func TestHistoryPostgresAuthenticatedReadsSurviveDiscoveryFailure(t *testing.T) {
	s, owner, car := openHistoryQueryTestDB(t)
	_, otherOwner, otherCar := openHistoryQueryTestDB(t)
	ctx := context.Background()
	for _, kind := range []string{"drive", "charge"} {
		seedHistoryQueryRows(t, s, owner, car, 1, 1, 0, kind)
	}
	if _, err := s.store.pool.Exec(ctx, `UPDATE jourvolt_telemetry_sessions
		SET started_at='2026-10-04T01:30:00Z',ended_at='2026-10-04T02:00:00Z'
		WHERE user_id=$1 AND vehicle_id=$2`, owner, car); err != nil {
		t.Fatal(err)
	}
	ownerSession, err := s.store.createSession(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	otherSession, err := s.store.createSession(ctx, otherOwner)
	if err != nil {
		t.Fatal(err)
	}
	for _, failure := range []struct {
		name string
		err  error
		code int
	}{
		{"upstream_unavailable", errTeslaUnavailable, http.StatusServiceUnavailable},
		{"tesla_reauthorization", errTeslaReauthorization, http.StatusUnauthorized},
		{"upstream_rate_limit", errTeslaRateLimited, http.StatusTooManyRequests},
	} {
		t.Run(failure.name, func(t *testing.T) {
			p := &failingHistoryDiscoveryProvider{err: failure.err}
			a := &app{store: s.store, telemetry: s, provider: p}
			get := func(path, token string) *httptest.ResponseRecorder {
				r := httptest.NewRequest(http.MethodGet, path, nil)
				if token != "" {
					r.Header.Set("Authorization", "Bearer "+token)
				}
				w := httptest.NewRecorder()
				a.ServeHTTP(w, r)
				return w
			}
			if w := get("/api/v1/cars", ownerSession.AccessToken); w.Code != failure.code {
				t.Fatalf("discovery status=%d, want %d", w.Code, failure.code)
			}
			if p.calls != 1 {
				t.Fatalf("discovery calls=%d, want one", p.calls)
			}
			for _, kind := range []string{"drive", "charge"} {
				path := fmt.Sprintf("/api/v1/cars/%d/%ss?page=1&show=50", car, kind)
				w := get(path, ownerSession.AccessToken)
				var body struct {
					Data map[string]json.RawMessage `json:"data"`
				}
				if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &body) != nil {
					t.Fatalf("owned %s failed: status=%d body=%s", kind, w.Code, w.Body.String())
				}
				var rows []struct {
					StartDate string `json:"start_date"`
					EndDate   string `json:"end_date"`
					Source    string `json:"source"`
				}
				if err := json.Unmarshal(body.Data[kind+"s"], &rows); err != nil || len(rows) != 1 ||
					rows[0].StartDate != "2026-10-04T01:30:00Z" || rows[0].EndDate != "2026-10-04T02:00:00Z" || rows[0].Source != "teslamate_archive" {
					t.Fatalf("latest owned %s missing: rows=%+v err=%v", kind, rows, err)
				}
				if p.calls != 1 {
					t.Fatalf("owned %s read depended on live discovery", kind)
				}
				for _, token := range []string{"", "invalid-synthetic-session"} {
					if w := get(path, token); w.Code != http.StatusUnauthorized {
						t.Fatalf("invalid session could read %s: status=%d", kind, w.Code)
					}
				}
				if p.calls != 1 {
					t.Fatal("unauthenticated history reached provider")
				}
			}
			// Another valid account has its own persisted car, but cannot use the
			// first account's numeric car ID to read its archive.
			for _, kind := range []string{"drive", "charge"} {
				ownEmpty := get(fmt.Sprintf("/api/v1/cars/%d/%ss", otherCar, kind), otherSession.AccessToken)
				if ownEmpty.Code != http.StatusOK {
					t.Fatalf("other owner's empty history status=%d", ownEmpty.Code)
				}
				var emptyBody struct {
					Data map[string]json.RawMessage `json:"data"`
				}
				var emptyRows []json.RawMessage
				if err := json.Unmarshal(ownEmpty.Body.Bytes(), &emptyBody); err != nil {
					t.Fatal(err)
				}
				if err := json.Unmarshal(emptyBody.Data[kind+"s"], &emptyRows); err != nil || len(emptyRows) != 0 {
					t.Fatalf("other owner's history leaked rows: count=%d err=%v", len(emptyRows), err)
				}
				cross := get(fmt.Sprintf("/api/v1/cars/%d/%ss", car, kind), otherSession.AccessToken)
				if cross.Code != failure.code {
					t.Fatalf("cross-owner history status=%d, want fail closed %d", cross.Code, failure.code)
				}
			}
		})
	}
}
