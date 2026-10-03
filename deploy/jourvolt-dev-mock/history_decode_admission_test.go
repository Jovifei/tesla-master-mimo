package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type historyCountingReadCloser struct {
	io.Reader
	Reads int
}

func (body *historyCountingReadCloser) Read(p []byte) (int, error) {
	body.Reads++
	return body.Reader.Read(p)
}

func (body *historyCountingReadCloser) Close() error { return nil }

func historyDecodeTestRequest(t *testing.T, user string, archive bool, ctx context.Context, invalid bool) (*app, *http.Request, *historyCountingReadCloser) {
	t.Helper()
	var a *app
	var r *http.Request
	if archive {
		var token string
		a, token = archiveBudgetApp(t, user)
		r = archiveImportRequestForTest(token, "budget-home", "source-car-1")
	} else {
		a = &app{telemetry: newTelemetryServiceForTest("example.test")}
		payload, err := json.Marshal(importBudgetRequest())
		if err != nil {
			t.Fatal(err)
		}
		r = httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/history/import", bytes.NewReader(payload))
	}
	reader := io.Reader(r.Body)
	if invalid {
		reader = strings.NewReader("{")
	}
	body := &historyCountingReadCloser{Reader: reader}
	r.Body = body
	return a, r.WithContext(ctx), body
}

func runHistoryDecodeTestRequest(a *app, r *http.Request, user string, archive bool) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	if archive {
		a.archiveBindingResource(w, r, user)
	} else {
		a.historyImport(w, r, user, 1)
	}
	return w
}

func TestHistoryDecodeAdmissionRejectsOverloadBeforeReading(t *testing.T) {
	for _, archive := range []bool{false, true} {
		name := "ordinary"
		if archive {
			name = "archive"
		}
		t.Run(name, func(t *testing.T) {
			user := t.Name()
			a, r, body := historyDecodeTestRequest(t, user, archive, context.Background(), false)
			release, err := acquireHistoryHeavyBudget(context.Background(), user, 1)
			if err != nil {
				t.Fatal(err)
			}
			defer release()
			w := runHistoryDecodeTestRequest(a, r, user, archive)
			requireArchiveBudgetHTTP(t, w, http.StatusTooManyRequests, "history_resource_overloaded")
			if w.Header().Get("Retry-After") != "1" {
				t.Fatalf("Retry-After=%q", w.Header().Get("Retry-After"))
			}
			if body.Reads != 0 {
				t.Fatalf("overloaded request decoded body: reads=%d", body.Reads)
			}
			requireImportBudgetCount(t, a.telemetry, user, 0)
		})
	}
}

func TestHistoryDecodeAdmissionRejectsCancelBeforeReading(t *testing.T) {
	for _, archive := range []bool{false, true} {
		name := "ordinary"
		if archive {
			name = "archive"
		}
		t.Run(name, func(t *testing.T) {
			user := t.Name()
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			a, r, body := historyDecodeTestRequest(t, user, archive, ctx, false)
			w := runHistoryDecodeTestRequest(a, r, user, archive)
			requireArchiveBudgetHTTP(t, w, http.StatusRequestTimeout, context.Canceled.Error())
			if body.Reads != 0 {
				t.Fatalf("canceled request decoded body: reads=%d", body.Reads)
			}
			requireImportBudgetCount(t, a.telemetry, user, 0)
			release, err := acquireHistoryHeavyBudget(context.Background(), user, 1)
			if err != nil {
				t.Fatalf("canceled request leaked permit: %v", err)
			}
			release()
		})
	}
}

func TestHistoryDecodeAdmissionInvalidJSONReleasesPermit(t *testing.T) {
	for _, archive := range []bool{false, true} {
		name := "ordinary"
		if archive {
			name = "archive"
		}
		t.Run(name, func(t *testing.T) {
			user := t.Name()
			a, r, body := historyDecodeTestRequest(t, user, archive, context.Background(), true)
			w := runHistoryDecodeTestRequest(a, r, user, archive)
			if w.Code != http.StatusBadRequest || body.Reads == 0 {
				t.Fatalf("status=%d reads=%d body=%s", w.Code, body.Reads, w.Body.String())
			}
			requireImportBudgetCount(t, a.telemetry, user, 0)
			if archive {
				w = archiveBudgetHTTP(a, r.Header.Get(archiveBindingHeader), user, context.Background())
			} else {
				w = ordinaryImportBudgetHTTP(t, a, user, context.Background())
			}
			requireArchiveBudgetHTTP(t, w, http.StatusOK, "")
			requireImportBudgetCount(t, a.telemetry, user, 1)
		})
	}
}

func TestHistoryDecodeAdmissionSuccessDoesNotDoubleAcquire(t *testing.T) {
	for _, archive := range []bool{false, true} {
		name := "ordinary"
		if archive {
			name = "archive"
		}
		t.Run(name, func(t *testing.T) {
			user := t.Name()
			a, r, body := historyDecodeTestRequest(t, user, archive, context.Background(), false)
			requireArchiveBudgetHTTP(t, runHistoryDecodeTestRequest(a, r, user, archive), http.StatusOK, "")
			if body.Reads == 0 {
				t.Fatal("successful request skipped body")
			}
			requireImportBudgetCount(t, a.telemetry, user, 1)
			release, err := acquireHistoryHeavyBudget(context.Background(), user, 1)
			if err != nil {
				t.Fatalf("successful handler leaked permit: %v", err)
			}
			release()
		})
	}
}
