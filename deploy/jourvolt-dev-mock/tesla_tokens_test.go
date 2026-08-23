package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

func TestTeslaRefreshUsesOfficialRotationFields(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "invalid body", http.StatusBadRequest)
			return
		}
		form, err := url.ParseQuery(string(body))
		if err != nil {
			http.Error(w, "invalid form", http.StatusBadRequest)
			return
		}
		if form.Get("grant_type") != "refresh_token" || form.Get("client_id") != "client-id" || form.Get("refresh_token") != "old-refresh" {
			http.Error(w, "unexpected refresh form", http.StatusBadRequest)
			return
		}
		if form.Get("client_secret") != "" {
			http.Error(w, "client secret must not be sent", http.StatusBadRequest)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"access_token": "new-access", "refresh_token": "new-refresh", "expires_in": 3600,
		})
	}))
	defer server.Close()
	manager := &teslaTokenManager{
		config: &teslaConfig{ClientID: "client-id", TokenEndpoint: server.URL},
		client: server.Client(),
	}
	refreshed, err := manager.refresh(context.Background(), "old-refresh")
	if err != nil {
		t.Fatal(err)
	}
	if refreshed.AccessToken != "new-access" || refreshed.RefreshToken != "new-refresh" {
		t.Fatalf("unexpected rotated token response: %#v", refreshed)
	}
}
