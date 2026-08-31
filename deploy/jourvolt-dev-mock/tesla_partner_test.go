package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestPartnerRegisterUsesClientCredentialsAndDomain(t *testing.T) {
	var sawPartnerAuth, sawDomain bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/oauth2/v3/token":
			if err := r.ParseForm(); err != nil {
				t.Fatal(err)
			}
			if r.Form.Get("grant_type") != "client_credentials" {
				t.Fatalf("grant_type = %q", r.Form.Get("grant_type"))
			}
			if r.Form.Get("client_id") != "client" || r.Form.Get("client_secret") != "secret" {
				t.Fatal("client credentials were not posted")
			}
			if r.Form.Get("audience") == "" {
				t.Fatal("audience must be posted")
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "partner-token", "expires_in": 3600})
		case r.Method == http.MethodPost && r.URL.Path == "/api/1/partner_accounts":
			if r.Header.Get("Authorization") != "Bearer partner-token" {
				t.Fatalf("register auth = %q", r.Header.Get("Authorization"))
			}
			body, _ := io.ReadAll(r.Body)
			var payload map[string]string
			if err := json.Unmarshal(body, &payload); err != nil {
				t.Fatal(err)
			}
			if payload["domain"] != "auth.teslalink.joviluma.com" {
				t.Fatalf("domain = %#v", payload)
			}
			sawPartnerAuth = true
			sawDomain = true
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"response":{"domain":"auth.teslalink.joviluma.com"}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	registrar := newTeslaPartnerRegistrar(&teslaConfig{
		ClientID:      "client",
		ClientSecret:  "secret",
		TokenEndpoint: server.URL + "/oauth2/v3/token",
		FleetAPIBase:  server.URL,
		PartnerDomain: "auth.teslalink.joviluma.com",
	}, server.Client())
	if err := registrar.ensure(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := registrar.ensure(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !sawPartnerAuth || !sawDomain {
		t.Fatal("register was not called with the partner token and domain")
	}
}

func TestPartnerRegisterSurfacesTeslaStatus(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/oauth2/v3/token" {
			_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "partner-token", "expires_in": 3600})
			return
		}
		w.WriteHeader(http.StatusPreconditionFailed)
		_, _ = w.Write([]byte(`{"error":"Account must be registered"}`))
	}))
	defer server.Close()

	registrar := newTeslaPartnerRegistrar(&teslaConfig{
		ClientID:      "client",
		ClientSecret:  "secret",
		TokenEndpoint: server.URL + "/oauth2/v3/token",
		FleetAPIBase:  server.URL,
		PartnerDomain: "auth.example.com",
	}, server.Client())
	if err := registrar.ensure(context.Background()); err == nil {
		t.Fatal("expected register failure")
	}
}
