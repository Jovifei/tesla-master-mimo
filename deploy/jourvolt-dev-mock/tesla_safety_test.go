package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"golang.org/x/oauth2"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestCredentialRequestsNeverFollowRedirects(t *testing.T) {
	for _, status := range []int{301, 302, 303, 307, 308} {
		for _, sameHost := range []bool{false, true} {
			for _, operation := range []string{"refresh", "exchange", "partner", "fleet", "telemetry"} {
				t.Run(fmt.Sprintf("%s/%d/sameHost=%t", operation, status, sameHost), func(t *testing.T) {
					var targetCalls atomic.Int32
					target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
						targetCalls.Add(1)
						w.Header().Set("Content-Type", "application/json")
						fmt.Fprint(w, `{"access_token":"not-accepted","refresh_token":"not-accepted","expires_in":3600,"token_type":"Bearer"}`)
					}))
					defer target.Close()
					server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
						if r.URL.Path == "/target" {
							targetCalls.Add(1)
							fmt.Fprint(w, `{"access_token":"not-accepted","refresh_token":"not-accepted","expires_in":3600}`)
							return
						}
						destination := target.URL
						if sameHost {
							destination = "/target"
						}
						http.Redirect(w, r, destination, status)
					}))
					defer server.Close()
					cfg := &teslaConfig{ClientID: "synthetic-client", ClientSecret: "synthetic-secret", TokenEndpoint: server.URL, FleetAPIBase: server.URL}
					var err error
					switch operation {
					case "refresh":
						manager := &teslaTokenManager{config: cfg, client: server.Client()}
						_, err = manager.refresh(context.Background(), "synthetic-refresh")
					case "exchange":
						o := newTeslaOAuth(cfg, nil, nil, server.Client())
						ctx := context.WithValue(context.Background(), oauth2.HTTPClient, o.client)
						_, err = o.oauth2.Exchange(ctx, "synthetic-code")
					case "partner":
						_, err = newTeslaPartnerRegistrar(cfg, server.Client()).partnerAccessToken(context.Background())
					case "fleet", "telemetry":
						p := &fleetProvider{client: server.Client(), baseURL: server.URL}
						var resp *http.Response
						if operation == "telemetry" {
							s := &telemetryService{httpClient: server.Client()}
							resp, err = s.commandProxyRequestWithToken(context.Background(), http.MethodPost, server.URL, []byte(`{"synthetic":true}`), "synthetic-bearer")
						} else {
							resp, err = p.request(context.Background(), "/api/1/vehicles", "synthetic-bearer")
						}
						if resp != nil {
							resp.Body.Close()
							if resp.StatusCode >= 300 {
								err = errTeslaUnavailable
							}
						}
					}
					if targetCalls.Load() != 0 {
						t.Fatalf("redirect target received %d credential requests", targetCalls.Load())
					}
					if err == nil {
						t.Fatal("redirect must not be accepted as credentials")
					}
				})
			}
		}
	}
}

func TestRefreshFailureClassification(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
		want   error
	}{
		{"grant", 400, `{"error":"invalid_grant"}`, errTeslaReauthorization},
		{"client", 400, `{"error":"invalid_client"}`, errTeslaConfiguration},
		{"rate", 429, `{"error":"invalid_grant"}`, errTeslaRateLimited},
		{"server", 503, `{"error":"invalid_grant"}`, errTeslaUnavailable},
		{"malformed", 200, `not-json synthetic-secret`, errTeslaUnavailable},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(tc.status); fmt.Fprint(w, tc.body) }))
			defer server.Close()
			m := &teslaTokenManager{config: &teslaConfig{TokenEndpoint: server.URL}, client: server.Client()}
			_, err := m.refresh(context.Background(), "synthetic-refresh")
			if !errors.Is(err, tc.want) {
				t.Fatalf("class=%v want=%v", err, tc.want)
			}
		})
	}
}

func TestRefreshFailurePreservesPersistedGrant(t *testing.T) {
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("isolated PostgreSQL required")
	}
	ctx := context.Background()
	s, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer s.close()
	cipher := mustTestCipher(t)
	access, _ := cipher.encrypt("synthetic-old-access")
	refresh, _ := cipher.encrypt("synthetic-old-refresh")
	sub, _ := randomToken()
	user, _, err := s.saveTeslaGrantAndLoginTicket(ctx, sub, access, refresh, oauthConsent{TermsVersion: jourVoltTermsVersion, PrivacyVersion: jourVoltPrivacyVersion}, time.Now().Add(-time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	defer s.deleteUser(ctx, user)
	for _, status := range []int{400, 401, 429, 503, 307, 200} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Location", "/must-not-follow")
				w.WriteHeader(status)
				fmt.Fprint(w, `{"error":"invalid_client","access_token":"incomplete"}`)
			}))
			defer server.Close()
			m := &teslaTokenManager{store: s, cipher: cipher, config: &teslaConfig{TokenEndpoint: server.URL}, client: server.Client()}
			if _, err = m.accessToken(ctx, user, ""); err == nil {
				t.Fatal("failure accepted")
			}
			var afterAccess, afterRefresh string
			if err = s.pool.QueryRow(ctx, "SELECT access_ciphertext,refresh_ciphertext FROM jourvolt_tesla_tokens WHERE user_id=$1", user).Scan(&afterAccess, &afterRefresh); err != nil {
				t.Fatal(err)
			}
			if access != afterAccess || refresh != afterRefresh {
				t.Fatal("failed refresh changed persisted credentials")
			}
		})
	}
}

func TestCredentialErrorsAndLogsNeverEchoUnknownCodes(t *testing.T) {
	sentinel := "synthetic_secret_ABC123"
	for _, body := range []string{`{"error":"` + sentinel + `"}`, `{"error":"invalid_grant","access_token":"` + sentinel + `"}`, `{"error":"https://host/?code=` + sentinel + `"}`} {
		if strings.Contains(teslaAPILogBody([]byte(body)), sentinel) {
			t.Fatal("untrusted error leaked")
		}
	}
	var sink bytes.Buffer
	// Callback formatting is the only value passed to logging; reject arbitrary codes.
	got := teslaCallbackLogError(&oauth2.RetrieveError{ErrorCode: sentinel, Body: []byte(sentinel)})
	sink.WriteString(got)
	if strings.Contains(sink.String(), sentinel) {
		t.Fatal("callback error leaked")
	}
}

func TestCredentialClientRetainsTimeout(t *testing.T) {
	source := &http.Client{Timeout: 13 * time.Second}
	o := newTeslaOAuth(&teslaConfig{}, nil, nil, source)
	if o.client.Timeout != source.Timeout {
		t.Fatal("timeout changed")
	}
	if source.CheckRedirect != nil {
		t.Fatal("caller client mutated")
	}
}

func TestClientConfigurationDoesNotRequestUserReauthorization(t *testing.T) {
	for _, code := range []int{400, 401, 403} {
		t.Run(fmt.Sprint(code), func(t *testing.T) {
			body := []byte(`{"error":"invalid_client"}`)
			if telemetryCommandErrorClass(code, body) != "configuration_invalid" {
				t.Fatal("client configuration became permission error")
			}
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(code); w.Write(body) }))
			defer server.Close()
			tokens := &testAccessTokens{}
			p := &fleetProvider{client: server.Client(), baseURL: server.URL, tokens: tokens}
			err := p.get(context.Background(), "synthetic-user", "/api/1/vehicles", &map[string]any{})
			if code == 401 || code == 403 {
				if !errors.Is(err, errTeslaConfiguration) {
					t.Fatalf("configuration class lost: %v", err)
				}
			}
			if len(tokens.calls) != 1 {
				t.Fatal("configuration failure rotated user credentials")
			}
		})
	}
}

func TestCredentialRequestsPreserveCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	m := &teslaTokenManager{config: &teslaConfig{TokenEndpoint: "http://127.0.0.1:1"}}
	if _, err := m.refresh(ctx, "synthetic"); !errors.Is(err, context.Canceled) {
		t.Fatal("refresh cancellation lost")
	}
	p := &fleetProvider{baseURL: "http://127.0.0.1:1"}
	if _, err := p.request(ctx, "/api/1/vehicles", "synthetic"); !errors.Is(err, context.Canceled) {
		t.Fatal("Fleet cancellation lost")
	}
	r := newTeslaPartnerRegistrar(&teslaConfig{TokenEndpoint: "http://127.0.0.1:1"}, nil)
	if _, err := r.partnerAccessToken(ctx); !errors.Is(err, context.Canceled) {
		t.Fatal("partner cancellation lost")
	}
}

func TestCredentialCancellationAfterResponseHeaders(t *testing.T) {
	for _, op := range []string{"refresh", "partner"} {
		t.Run(op, func(t *testing.T) {
			headers := make(chan struct{})
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				fmt.Fprint(w, `{"access_token":"synthetic","refresh_token":"synthetic","expires_in":3600}`)
				w.(http.Flusher).Flush()
				close(headers)
				<-r.Context().Done()
			}))
			defer server.Close()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() {
				cfg := &teslaConfig{TokenEndpoint: server.URL}
				var err error
				if op == "refresh" {
					_, err = (&teslaTokenManager{config: cfg, client: server.Client()}).refresh(ctx, "synthetic")
				} else {
					_, err = newTeslaPartnerRegistrar(cfg, server.Client()).partnerAccessToken(ctx)
				}
				done <- err
			}()
			<-headers
			cancel()
			select {
			case err := <-done:
				if !errors.Is(err, context.Canceled) {
					t.Fatalf("post-header cancellation accepted/class lost: %v", err)
				}
			case <-time.After(time.Second):
				t.Fatal("cancellation did not terminate")
			}
		})
	}
}
