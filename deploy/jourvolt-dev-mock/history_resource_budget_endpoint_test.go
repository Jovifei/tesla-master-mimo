package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func historyBudgetTestApp(users ...string) *app {
	s := newTelemetryServiceForTest("example.test")
	end := time.Date(2026, 10, 2, 0, 0, 0, 0, time.UTC)
	for _, user := range users {
		s.memory.completed[telemetryKey{UserID: user, VehicleID: 1}] = []telemetrySession{{
			ID: user + "-drive", PublicID: 1, Kind: "drive", StartAt: end.Add(-time.Hour), EndAt: &end,
			Source: "telemetry_mqtt", QualityState: "observed",
			Route: []telemetryRoutePoint{{ObservedAt: end, Latitude: 1, Longitude: 2}},
		}}
	}
	return &app{telemetry: s}
}

func requestHistoryForBudgetTest(a *app, ctx context.Context, user, id string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/drives/"+id, nil).WithContext(ctx)
	w := httptest.NewRecorder()
	var parts []string
	if id != "" {
		parts = []string{id}
	}
	a.telemetryHistory(w, r, user, 1, "drive", parts)
	return w
}

func requireHistoryBudgetResponse(t *testing.T, w *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	if w.Code != status {
		t.Fatalf("status=%d want=%d body=%s", w.Code, status, w.Body.String())
	}
	var body map[string]json.RawMessage
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if code != "" {
		var actual string
		if err := json.Unmarshal(body["error"], &actual); err != nil || actual != code {
			t.Fatalf("error=%s want=%s", body["error"], code)
		}
	} else if len(body["data"]) == 0 {
		t.Fatalf("missing data: %s", w.Body.String())
	}
}

func TestTelemetryHistoryDetailAdmissionOverloadAndRelease(t *testing.T) {
	user := t.Name()
	a := historyBudgetTestApp(user)
	release, err := acquireHistoryHeavyBudget(context.Background(), user, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	w := requestHistoryForBudgetTest(a, context.Background(), user, "1")
	requireHistoryBudgetResponse(t, w, http.StatusTooManyRequests, "history_resource_overloaded")
	if w.Header().Get("Retry-After") != "1" {
		t.Fatalf("Retry-After=%q", w.Header().Get("Retry-After"))
	}
	release()
	requireHistoryBudgetResponse(t, requestHistoryForBudgetTest(a, context.Background(), user, "1"), http.StatusOK, "")
}

func TestTelemetryHistoryDetailAdmissionSeparatesAccounts(t *testing.T) {
	first, second := t.Name()+"-a", t.Name()+"-b"
	a := historyBudgetTestApp(first, second)
	release, err := acquireHistoryHeavyBudget(context.Background(), first, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	w := requestHistoryForBudgetTest(a, context.Background(), second, "1")
	requireHistoryBudgetResponse(t, w, http.StatusOK, "")
	var body struct {
		Data struct {
			Drive struct {
				SessionID string `json:"session_id"`
			} `json:"drive"`
		} `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil || body.Data.Drive.SessionID != second+"-drive" {
		t.Fatalf("wrong account detail: %s err=%v", w.Body.String(), err)
	}
}

func TestTelemetryHistoryDetailCanceledRequestDoesNotLeakBudget(t *testing.T) {
	user := t.Name()
	a := historyBudgetTestApp(user)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	w := requestHistoryForBudgetTest(a, ctx, user, "1")
	requireHistoryBudgetResponse(t, w, http.StatusRequestTimeout, context.Canceled.Error())
	if w.Header().Get("Retry-After") != "" {
		t.Fatal("cancellation was classified as overload")
	}
	requireHistoryBudgetResponse(t, requestHistoryForBudgetTest(a, context.Background(), user, "1"), http.StatusOK, "")
}

func TestTelemetryHistoryDetailNotFoundReleasesBudget(t *testing.T) {
	user := t.Name()
	a := historyBudgetTestApp(user)
	requireHistoryBudgetResponse(t, requestHistoryForBudgetTest(a, context.Background(), user, "999"), http.StatusNotFound, "drive_not_found")
	requireHistoryBudgetResponse(t, requestHistoryForBudgetTest(a, context.Background(), user, "1"), http.StatusOK, "")
}

func TestTelemetryHistoryListBypassesHeavyBudget(t *testing.T) {
	user := t.Name()
	a := historyBudgetTestApp(user)
	for i := 0; i < 8; i++ {
		release, err := acquireHistoryHeavyBudget(context.Background(), fmt.Sprintf("%s-holder-%d", user, i), 1)
		if err != nil {
			t.Fatal(err)
		}
		defer release()
	}
	requireHistoryBudgetResponse(t, requestHistoryForBudgetTest(a, context.Background(), user, "1"), http.StatusTooManyRequests, "history_resource_overloaded")
	w := requestHistoryForBudgetTest(a, context.Background(), user, "")
	requireHistoryBudgetResponse(t, w, http.StatusOK, "")
	var body struct {
		Data struct {
			Drives []map[string]json.RawMessage `json:"drives"`
		} `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Data.Drives) != 1 || string(body.Data.Drives[0]["drive_details"]) != "[]" {
		t.Fatalf("list must preserve summaries without route payload: %s", w.Body.String())
	}
}
