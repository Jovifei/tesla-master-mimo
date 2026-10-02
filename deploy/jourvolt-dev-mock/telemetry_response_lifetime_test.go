package main

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestTelemetryProxyResponseBodySurvivesHeaders(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		w.(http.Flusher).Flush()
		time.Sleep(30 * time.Millisecond)
		_, _ = io.WriteString(w, `{"error":"public key has not been paired with the vehicle"}`)
	}))
	defer server.Close()
	service := newTelemetryServiceForTest("partner.example.com")
	response, err := service.commandProxyRequestWithToken(context.Background(), http.MethodGet, server.URL, nil, "fixture-token")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("body failed after successful headers: %v", err)
	}
	if got := telemetryCommandErrorClass(response.StatusCode, body); got != "pairing_required" {
		t.Fatalf("authoritative pairing error lost: %s", got)
	}
}
