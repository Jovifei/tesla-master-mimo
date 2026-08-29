package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const (
	validTestSecret   = "synthetic-valid-secret"
	invalidTestSecret = "synthetic-invalid-secret"
)

type recordingStore struct {
	snapshotValue snapshot
	snapshotErr   error
	parkedValue   parkedDetail
	parkedErr     error
	standbyValue  []standbyWindow
	standbyErr    error
	snapshotCalls int
	parkedCalls   int
	standbyCalls  int
}

func (s *recordingStore) Snapshot(context.Context, int) (snapshot, error) {
	s.snapshotCalls++
	if s.snapshotErr != nil {
		return snapshot{}, s.snapshotErr
	}
	return s.snapshotValue, nil
}

func (s *recordingStore) Parked(context.Context, int, int, int) (parkedDetail, error) {
	s.parkedCalls++
	if s.parkedErr != nil {
		return parkedDetail{}, s.parkedErr
	}
	return s.parkedValue, nil
}

func (s *recordingStore) Standby(context.Context, int) ([]standbyWindow, error) {
	s.standbyCalls++
	if s.standbyErr != nil {
		return nil, s.standbyErr
	}
	return s.standbyValue, nil
}

func testSnapshot() snapshot {
	return snapshot{
		Status:       map[string]any{"odometer": 123.4},
		Units:        map[string]string{"unit_of_length": "km"},
		ObservedAt:   "2026-07-11T12:00:00+08:00",
		Source:       "database_latest",
		FieldSources: map[string]string{"odometer": "database_latest"},
	}
}

func testParkedDetail() parkedDetail {
	return parkedDetail{OlderDriveID: 1, NewerDriveID: 2, SampleCount: 3, Source: "database_latest"}
}

func testServer(store *recordingStore) *server {
	return &server{store: store, proxy: http.NotFoundHandler(), apiToken: validTestSecret}
}

func serve(t *testing.T, s *server, path, token string) (*httptest.ResponseRecorder, *bytes.Buffer) {
	t.Helper()
	var logs bytes.Buffer
	previous := log.Writer()
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(previous) })

	req := httptest.NewRequest(http.MethodGet, path, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res := httptest.NewRecorder()
	s.routes().ServeHTTP(res, req)
	return res, &logs
}

func assertNoSyntheticCredentials(t *testing.T, values ...string) {
	t.Helper()
	for _, value := range values {
		if strings.Contains(value, validTestSecret) || strings.Contains(value, invalidTestSecret) {
			t.Fatal("synthetic credential leaked")
		}
	}
}

func assertJSON(t *testing.T, res *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	if !strings.HasPrefix(res.Header().Get("Content-Type"), "application/json") {
		t.Fatal("response is not JSON")
	}
	var body map[string]any
	if err := json.Unmarshal(res.Body.Bytes(), &body); err != nil {
		t.Fatal("response is not valid JSON")
	}
	return body
}

func TestAuthenticationRejectsMissingAndWrongCredentialsWithoutLeaks(t *testing.T) {
	for _, tc := range []struct {
		name  string
		token string
	}{
		{name: "missing"},
		{name: "wrong", token: invalidTestSecret},
	} {
		t.Run(tc.name, func(t *testing.T) {
			store := &recordingStore{snapshotValue: testSnapshot()}
			res, logs := serve(t, testServer(store), "/api/matelink/v1/cars/7/snapshot", tc.token)
			if res.Code != http.StatusUnauthorized {
				t.Fatalf("got status %d", res.Code)
			}
			if store.snapshotCalls != 0 || store.parkedCalls != 0 {
				t.Fatal("authentication failure reached data store")
			}
			assertJSON(t, res)
			assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
		})
	}
}

func TestCapabilitiesRequiresConfiguredToken(t *testing.T) {
	store := &recordingStore{}
	res, logs := serve(t, testServer(store), "/api/matelink/v1/capabilities", "")
	if res.Code != http.StatusUnauthorized {
		t.Fatalf("got status %d", res.Code)
	}
	assertJSON(t, res)
	assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
}

func TestCapabilitiesAllowsValidAuthentication(t *testing.T) {
	store := &recordingStore{}
	res, logs := serve(t, testServer(store), "/api/matelink/v1/capabilities", validTestSecret)
	if res.Code != http.StatusOK {
		t.Fatalf("got status %d", res.Code)
	}
	body := assertJSON(t, res)
	data, ok := body["data"].(map[string]any)
	if !ok || data["adapter_version"] != "1" {
		t.Fatal("capabilities contract changed")
	}
	assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
}

func TestSnapshotReturnsSourceAwareData(t *testing.T) {
	store := &recordingStore{snapshotValue: testSnapshot()}
	res, logs := serve(t, testServer(store), "/api/matelink/v1/cars/7/snapshot", validTestSecret)
	if res.Code != http.StatusOK {
		t.Fatalf("got status %d", res.Code)
	}
	if store.snapshotCalls != 1 {
		t.Fatal("snapshot handler did not call data store exactly once")
	}
	body := assertJSON(t, res)
	if _, ok := body["data"].(map[string]any); !ok {
		t.Fatal("snapshot response is missing data")
	}
	if !containsAll(res.Body.String(), "database_latest", "observed_at", "123.4") {
		t.Fatal("snapshot response mapping changed")
	}
	assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
}

func TestSnapshotRejectsInvalidCarIDWithoutStoreAccess(t *testing.T) {
	for _, path := range []string{
		"/api/matelink/v1/cars/0/snapshot",
		"/api/matelink/v1/cars/not-a-number/snapshot",
	} {
		t.Run(path, func(t *testing.T) {
			store := &recordingStore{snapshotValue: testSnapshot()}
			res, logs := serve(t, testServer(store), path, validTestSecret)
			if res.Code != http.StatusBadRequest {
				t.Fatalf("got status %d", res.Code)
			}
			if store.snapshotCalls != 0 {
				t.Fatal("invalid snapshot request reached data store")
			}
			assertJSON(t, res)
			assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
		})
	}
}

func TestSnapshotNotFoundUsesControlledError(t *testing.T) {
	store := &recordingStore{snapshotErr: sql.ErrNoRows}
	res, logs := serve(t, testServer(store), "/api/matelink/v1/cars/7/snapshot", validTestSecret)
	if res.Code != http.StatusNotFound || !containsAll(res.Body.String(), "no vehicle snapshot") {
		t.Fatal("snapshot not-found contract changed")
	}
	assertJSON(t, res)
	assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
}

func TestSnapshotStoreErrorDoesNotLeakCredentials(t *testing.T) {
	store := &recordingStore{snapshotErr: errors.New("upstream rejected synthetic-valid-secret and synthetic-invalid-secret")}
	res, logs := serve(t, testServer(store), "/api/matelink/v1/cars/7/snapshot", validTestSecret)
	if res.Code != http.StatusInternalServerError || !containsAll(res.Body.String(), "snapshot unavailable") {
		t.Fatal("snapshot error contract changed")
	}
	assertJSON(t, res)
	assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
}

func TestParkedUsesStableAdjacentDriveIDs(t *testing.T) {
	store := &recordingStore{parkedValue: testParkedDetail()}
	res, logs := serve(t, testServer(store), "/api/matelink/v1/cars/7/parked/11/12", validTestSecret)
	if res.Code != http.StatusOK {
		t.Fatalf("got status %d", res.Code)
	}
	if store.parkedCalls != 1 {
		t.Fatal("parked handler did not call data store exactly once")
	}
	if !containsAll(res.Body.String(), "sample_count", "database_latest") {
		t.Fatal("parked response mapping changed")
	}
	assertJSON(t, res)
	assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
}

func TestParkedResponsePreservesLinkedChargeWhenIntervalsOverlap(t *testing.T) {
	value := testParkedDetail()
	value.LinkedCharge = &linkedCharge{ChargeID: 41}
	store := &recordingStore{parkedValue: value}
	res, logs := serve(t, testServer(store), "/api/matelink/v1/cars/7/parked/11/12", validTestSecret)
	if res.Code != http.StatusOK {
		t.Fatalf("got status %d", res.Code)
	}
	if !containsAll(res.Body.String(), "linked_charge", "charge_id", "41") {
		t.Fatal("parked response lost its linked charge")
	}
	assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
}

func TestStandbyResponseReturnsOnlyAdapterObservedWindows(t *testing.T) {
	delta := -1
	store := &recordingStore{standbyValue: []standbyWindow{{
		StartDate:       "2026-08-20T00:00:00+08:00",
		EndDate:         "2026-08-20T04:00:00+08:00",
		DurationSeconds: 14400,
		BatteryDelta:    &delta,
		CoverageRatio:   0.9,
	}}}
	res, logs := serve(t, testServer(store), "/api/matelink/v1/cars/7/standby", validTestSecret)
	if res.Code != http.StatusOK {
		t.Fatalf("got status %d", res.Code)
	}
	if !containsAll(res.Body.String(), "windows", "battery_delta", "coverage_ratio") {
		t.Fatal("standby response is missing observed window evidence")
	}
	assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
}

func TestParkedRejectsInvalidIDsWithoutStoreAccess(t *testing.T) {
	for _, path := range []string{
		"/api/matelink/v1/cars/0/parked/11/12",
		"/api/matelink/v1/cars/7/parked/0/12",
		"/api/matelink/v1/cars/7/parked/11/not-a-number",
	} {
		t.Run(path, func(t *testing.T) {
			store := &recordingStore{parkedValue: testParkedDetail()}
			res, logs := serve(t, testServer(store), path, validTestSecret)
			if res.Code != http.StatusBadRequest {
				t.Fatalf("got status %d", res.Code)
			}
			if store.parkedCalls != 0 {
				t.Fatal("invalid parked request reached data store")
			}
			assertJSON(t, res)
			assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
		})
	}
}

func TestParkedNotFoundUsesControlledError(t *testing.T) {
	store := &recordingStore{parkedErr: sql.ErrNoRows}
	res, logs := serve(t, testServer(store), "/api/matelink/v1/cars/7/parked/11/12", validTestSecret)
	if res.Code != http.StatusNotFound || !containsAll(res.Body.String(), "parked interval not found") {
		t.Fatal("parked not-found contract changed")
	}
	assertJSON(t, res)
	assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
}

func TestParkedStoreErrorDoesNotLeakCredentials(t *testing.T) {
	store := &recordingStore{parkedErr: errors.New("upstream rejected synthetic-valid-secret and synthetic-invalid-secret")}
	res, logs := serve(t, testServer(store), "/api/matelink/v1/cars/7/parked/11/12", validTestSecret)
	if res.Code != http.StatusInternalServerError || !containsAll(res.Body.String(), "parked data unavailable") {
		t.Fatal("parked error contract changed")
	}
	assertJSON(t, res)
	assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
}

func containsAll(value string, needles ...string) bool {
	for _, needle := range needles {
		if !strings.Contains(value, needle) {
			return false
		}
	}
	return true
}
