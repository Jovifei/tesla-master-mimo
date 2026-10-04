package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestHistoryContextPostgresReturnsPersistedIdentityWithoutDiscovery(t *testing.T) {
	s, owner, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	session, err := s.store.createSession(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	var wantUID, before string
	if err := s.store.pool.QueryRow(ctx, `SELECT provider_vehicle_id,md5(row_to_json(v)::text)
		FROM jourvolt_vehicles v WHERE user_id=$1 AND id=$2`, owner, car).Scan(&wantUID, &before); err != nil {
		t.Fatal(err)
	}
	p := &failingHistoryDiscoveryProvider{err: errTeslaUnavailable}
	a := &app{store: s.store, telemetry: s, provider: p}
	path := fmt.Sprintf("/api/matelink/v1/cars/%d/history-context", car)
	w := historyContextRequest(a, http.MethodGet, path, session.AccessToken)
	var response struct {
		Data struct {
			Version int    `json:"capability_version"`
			CarID   int    `json:"car_id"`
			UID     string `json:"vehicle_uid"`
		} `json:"data"`
	}
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &response) != nil ||
		response.Data.Version != 1 || response.Data.CarID != car || response.Data.UID != wantUID {
		t.Fatalf("history context status=%d body=%s", w.Code, w.Body.String())
	}
	if p.calls != 0 {
		t.Fatalf("pure history context called Vehicles %d times", p.calls)
	}
	var after string
	if err := s.store.pool.QueryRow(ctx, `SELECT md5(row_to_json(v)::text)
		FROM jourvolt_vehicles v WHERE user_id=$1 AND id=$2`, owner, car).Scan(&after); err != nil || before != after {
		t.Fatalf("read changed persisted vehicle: equal=%t err=%v", before == after, err)
	}
	for _, forbidden := range []string{"vin", "token", "display_name", "provider_vehicle_id", "updated_at"} {
		if strings.Contains(w.Body.String(), forbidden) {
			t.Fatalf("context exposed unnecessary field %q", forbidden)
		}
	}
}

func TestHistoryContextPostgresFailsClosedWithoutProviderFallback(t *testing.T) {
	s, owner, car := openHistoryQueryTestDB(t)
	_, otherOwner, _ := openHistoryQueryTestDB(t)
	ctx := context.Background()
	ownerSession, err := s.store.createSession(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	otherSession, err := s.store.createSession(ctx, otherOwner)
	if err != nil {
		t.Fatal(err)
	}
	p := &failingHistoryDiscoveryProvider{err: errTeslaUnavailable}
	a := &app{store: s.store, telemetry: s, provider: p}
	path := fmt.Sprintf("/api/matelink/v1/cars/%d/history-context", car)
	for _, test := range []struct {
		name, method, path, token string
		code                      int
	}{
		{"no_session", "GET", path, "", 401},
		{"invalid_session", "GET", path, "invalid-synthetic-token", 401},
		{"foreign_owner", "GET", path, otherSession.AccessToken, 404},
		{"missing_car", "GET", "/api/matelink/v1/cars/2147483647/history-context", ownerSession.AccessToken, 404},
		{"negative_car", "GET", "/api/matelink/v1/cars/-1/history-context", ownerSession.AccessToken, 404},
		{"zero_car", "GET", "/api/matelink/v1/cars/0/history-context", ownerSession.AccessToken, 404},
		{"oversize_car", "GET", "/api/matelink/v1/cars/2147483648/history-context", ownerSession.AccessToken, 404},
		{"post", "POST", path, ownerSession.AccessToken, 404},
		{"suffix", "GET", path + "/extra", ownerSession.AccessToken, 404},
		{"encoded_separator", "GET", strings.Replace(path, "/history-context", "%2Fhistory-context", 1), ownerSession.AccessToken, 404},
	} {
		t.Run(test.name, func(t *testing.T) {
			w := historyContextRequest(a, test.method, test.path, test.token)
			if w.Code != test.code {
				t.Fatalf("status=%d want=%d", w.Code, test.code)
			}
			if p.calls != 0 {
				t.Fatalf("rejected context called Vehicles %d times", p.calls)
			}
		})
	}
	if _, err := s.store.pool.Exec(ctx, `UPDATE jourvolt_vehicles SET provider_vehicle_id=$1 WHERE user_id=$2 AND id=$3`, strings.Repeat("x", 256), owner, car); err != nil {
		t.Fatal(err)
	}
	if w := historyContextRequest(a, http.MethodGet, path, ownerSession.AccessToken); w.Code != http.StatusOK || p.calls != 0 {
		t.Fatalf("256 byte identity status=%d providerCalls=%d", w.Code, p.calls)
	}
	for _, value := range []string{"", " ", " padded ", strings.Repeat("x", 257), strings.Repeat("界", 86)} {
		if _, err := s.store.pool.Exec(ctx, `UPDATE jourvolt_vehicles SET provider_vehicle_id=$1 WHERE user_id=$2 AND id=$3`, value, owner, car); err != nil {
			t.Fatal(err)
		}
		w := historyContextRequest(a, http.MethodGet, path, ownerSession.AccessToken)
		if w.Code != http.StatusServiceUnavailable || strings.TrimSpace(w.Body.String()) != "{\"error\":\"history_identity_unavailable\"}" || p.calls != 0 {
			t.Fatalf("invalid identity status=%d body=%s providerCalls=%d", w.Code, w.Body.String(), p.calls)
		}
	}
}

func TestHistoryContextPostgresCancellationReturnsSafeError(t *testing.T) {
	s, owner, car := openHistoryQueryTestDB(t)
	ctx := context.Background()
	session, err := s.store.createSession(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	tx, err := s.store.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `LOCK TABLE jourvolt_vehicles IN ACCESS EXCLUSIVE MODE`); err != nil {
		t.Fatal(err)
	}
	p := &failingHistoryDiscoveryProvider{err: errTeslaUnavailable}
	a := &app{store: s.store, telemetry: s, provider: p}
	r := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/api/matelink/v1/cars/%d/history-context", car), nil)
	r.Header.Set("Authorization", "Bearer "+session.AccessToken)
	requestCtx, cancel := context.WithTimeout(ctx, 100*time.Millisecond)
	defer cancel()
	w := httptest.NewRecorder()
	a.ServeHTTP(w, r.WithContext(requestCtx))
	if w.Code != http.StatusServiceUnavailable || strings.TrimSpace(w.Body.String()) != "{\"error\":\"history_identity_unavailable\"}" || p.calls != 0 {
		t.Fatalf("cancelled lookup status=%d body=%s providerCalls=%d", w.Code, w.Body.String(), p.calls)
	}
}

func historyContextRequest(a *app, method, path, token string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, nil)
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	a.ServeHTTP(w, r)
	return w
}
