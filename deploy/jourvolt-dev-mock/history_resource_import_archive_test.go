package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func importBudgetRequest() historyImportRequest {
	return historyImportRequest{Source: "local_import", Drives: []historyImportSession{{
		SessionID: "same-client-record", StartedAt: "2026-10-01T10:00:00Z", EndedAt: "2026-10-01T10:01:00Z",
	}}}
}

func requireImportBudgetCount(t *testing.T, s *telemetryService, user string, count int) {
	t.Helper()
	metadata, err := s.historyMetadata(context.Background(), user, 1, "drive")
	if err != nil || metadata.Total != count {
		t.Fatalf("user=%s count=%d want=%d err=%v", user, metadata.Total, count, err)
	}
}

func TestHistoryImportSharedBudgetOverloadAndRelease(t *testing.T) {
	s := newTelemetryServiceForTest("example.test")
	user := t.Name()
	release, err := acquireHistoryHeavyBudget(context.Background(), user, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	if _, err := s.importHistory(context.Background(), user, 1, importBudgetRequest()); !errors.Is(err, errHistoryResourceOverloaded) {
		t.Fatalf("import did not use shared budget: %v", err)
	}
	requireImportBudgetCount(t, s, user, 0)
	release()
	result, err := s.importHistory(context.Background(), user, 1, importBudgetRequest())
	if err != nil || result.ImportedDrives != 1 {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	requireImportBudgetCount(t, s, user, 1)
	// A successful import must return its permit to the detail/import budget.
	releaseAgain, err := acquireHistoryHeavyBudget(context.Background(), user, 1)
	if err != nil {
		t.Fatalf("successful import leaked permit: %v", err)
	}
	releaseAgain()
}

func TestHistoryImportCanceledDoesNotWriteOrLeak(t *testing.T) {
	s := newTelemetryServiceForTest("example.test")
	user := t.Name()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := s.importHistory(ctx, user, 1, importBudgetRequest()); !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled import error=%v", err)
	}
	requireImportBudgetCount(t, s, user, 0)
	if result, err := s.importHistory(context.Background(), user, 1, importBudgetRequest()); err != nil || result.ImportedDrives != 1 {
		t.Fatalf("canceled import leaked permit: result=%+v err=%v", result, err)
	}
}

func TestHistoryImportBudgetSeparatesAccounts(t *testing.T) {
	s := newTelemetryServiceForTest("example.test")
	first, second := t.Name()+"-a", t.Name()+"-b"
	release, err := acquireHistoryHeavyBudget(context.Background(), first, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	if _, err := s.importHistory(context.Background(), second, 1, importBudgetRequest()); err != nil {
		t.Fatal(err)
	}
	requireImportBudgetCount(t, s, first, 0)
	requireImportBudgetCount(t, s, second, 1)
	release()
	if _, err := s.importHistory(context.Background(), first, 1, importBudgetRequest()); err != nil {
		t.Fatal(err)
	}
	one, _, err := s.historyPage(context.Background(), first, 1, "drive", historyPageOptions{})
	if err != nil {
		t.Fatal(err)
	}
	two, _, err := s.historyPage(context.Background(), second, 1, "drive", historyPageOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(one) != 1 || len(two) != 1 || one[0]["session_id"] == two[0]["session_id"] {
		t.Fatalf("identical client records crossed account scope: %v %v", one, two)
	}
}

func archiveBudgetApp(t *testing.T, user string) (*app, string) {
	t.Helper()
	s := newTelemetryServiceForTest("example.test")
	token, _, err := s.memory.createArchiveBinding(context.Background(), user, 1, archiveBindingRequest{
		SourceType: "teslamate", SourceInstanceID: "budget-home", SourceVehicleID: "source-car-1",
	})
	if err != nil {
		t.Fatal(err)
	}
	return &app{telemetry: s}, token
}

func archiveBudgetHTTP(a *app, token, user string, ctx context.Context) *httptest.ResponseRecorder {
	r := archiveImportRequestForTest(token, "budget-home", "source-car-1").WithContext(ctx)
	w := httptest.NewRecorder()
	a.archiveBindingResource(w, r, user)
	return w
}

func requireArchiveBudgetHTTP(t *testing.T, w *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	if w.Code != status {
		t.Fatalf("status=%d want=%d body=%s", w.Code, status, w.Body.String())
	}
	if code != "" {
		var body struct {
			Error string `json:"error"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil || body.Error != code {
			t.Fatalf("error response=%s want=%s err=%v", w.Body.String(), code, err)
		}
	}
}

func TestArchiveImportSharedBudgetHTTPOverloadAndRelease(t *testing.T) {
	user := t.Name()
	a, token := archiveBudgetApp(t, user)
	release, err := acquireHistoryHeavyBudget(context.Background(), user, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	w := archiveBudgetHTTP(a, token, user, context.Background())
	requireArchiveBudgetHTTP(t, w, http.StatusTooManyRequests, "history_resource_overloaded")
	if w.Header().Get("Retry-After") != "1" {
		t.Fatalf("Retry-After=%q", w.Header().Get("Retry-After"))
	}
	requireImportBudgetCount(t, a.telemetry, user, 0)
	release()
	requireArchiveBudgetHTTP(t, archiveBudgetHTTP(a, token, user, context.Background()), http.StatusOK, "")
	requireImportBudgetCount(t, a.telemetry, user, 1)
	releaseAgain, err := acquireHistoryHeavyBudget(context.Background(), user, 1)
	if err != nil {
		t.Fatalf("archive success leaked permit: %v", err)
	}
	releaseAgain()
}

func TestArchiveImportCanceledHTTPDoesNotWriteOrLeak(t *testing.T) {
	user := t.Name()
	a, token := archiveBudgetApp(t, user)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	w := archiveBudgetHTTP(a, token, user, ctx)
	requireArchiveBudgetHTTP(t, w, http.StatusRequestTimeout, context.Canceled.Error())
	if w.Header().Get("Retry-After") != "" {
		t.Fatal("cancellation was mapped to overload")
	}
	requireImportBudgetCount(t, a.telemetry, user, 0)
	requireArchiveBudgetHTTP(t, archiveBudgetHTTP(a, token, user, context.Background()), http.StatusOK, "")
	requireImportBudgetCount(t, a.telemetry, user, 1)
}

func ordinaryImportBudgetHTTP(t *testing.T, a *app, user string, ctx context.Context) *httptest.ResponseRecorder {
	t.Helper()
	body, err := json.Marshal(importBudgetRequest())
	if err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/import", bytes.NewReader(body)).WithContext(ctx)
	w := httptest.NewRecorder()
	a.historyImport(w, r, user, 1)
	return w
}

func TestHistoryImportSharedBudgetHTTPOverloadAndRelease(t *testing.T) {
	user := t.Name()
	a := &app{telemetry: newTelemetryServiceForTest("example.test")}
	release, err := acquireHistoryHeavyBudget(context.Background(), user, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	w := ordinaryImportBudgetHTTP(t, a, user, context.Background())
	requireArchiveBudgetHTTP(t, w, http.StatusTooManyRequests, "history_resource_overloaded")
	if w.Header().Get("Retry-After") != "1" {
		t.Fatalf("Retry-After=%q", w.Header().Get("Retry-After"))
	}
	requireImportBudgetCount(t, a.telemetry, user, 0)
	release()
	requireArchiveBudgetHTTP(t, ordinaryImportBudgetHTTP(t, a, user, context.Background()), http.StatusOK, "")
	requireImportBudgetCount(t, a.telemetry, user, 1)
}

func TestHistoryImportCanceledHTTPDoesNotWriteOrLeak(t *testing.T) {
	user := t.Name()
	a := &app{telemetry: newTelemetryServiceForTest("example.test")}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	w := ordinaryImportBudgetHTTP(t, a, user, ctx)
	requireArchiveBudgetHTTP(t, w, http.StatusRequestTimeout, context.Canceled.Error())
	if w.Header().Get("Retry-After") != "" {
		t.Fatal("cancellation was mapped to overload")
	}
	requireImportBudgetCount(t, a.telemetry, user, 0)
	requireArchiveBudgetHTTP(t, ordinaryImportBudgetHTTP(t, a, user, context.Background()), http.StatusOK, "")
	requireImportBudgetCount(t, a.telemetry, user, 1)
}
