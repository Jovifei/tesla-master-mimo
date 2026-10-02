package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestTelemetryHistoryDetailAdmissionOverloadAndRelease(t *testing.T) {
	budget := newHistoryResourceAdmission(1)
	release, err := budget.acquire(context.Background(), "user-a", "vehicle-a")
	if err != nil { t.Fatal(err) }
	defer release()
	if _, err := budget.acquire(context.Background(), "user-a", "vehicle-a"); err == nil { t.Fatal("expected overload") }
	release()
	if _, err := budget.acquire(context.Background(), "user-b", "vehicle-b"); err != nil { t.Fatal(err) }
	_ = httptest.NewRecorder()
	_ = http.MethodGet
}
