package main

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/go-jose/go-jose/v4"
)

func TestTeslaOAuthCreatesOneTimeJourVoltSession(t *testing.T) {
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set")
	}

	ctx := context.Background()
	store, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(store.close)

	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := jose.NewSigner(jose.SigningKey{
		Algorithm: jose.RS256,
		Key:       privateKey,
	}, (&jose.SignerOptions{}).WithType("JWT").WithHeader("kid", "test-key"))
	if err != nil {
		t.Fatal(err)
	}

	var expectedNonce string
	var provider *httptest.Server
	provider = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/keys":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(jose.JSONWebKeySet{Keys: []jose.JSONWebKey{{
				Key:       &privateKey.PublicKey,
				KeyID:     "test-key",
				Algorithm: string(jose.RS256),
				Use:       "sig",
			}}})
		case "/token":
			if err := r.ParseForm(); err != nil || r.Form.Get("code") != "auth-code" {
				http.Error(w, "unexpected token request", http.StatusBadRequest)
				return
			}
			idToken := signedTestIDToken(t, signer, provider.URL, expectedNonce)
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"access_token":  "tesla-access",
				"token_type":    "Bearer",
				"refresh_token": "tesla-refresh",
				"expires_in":    3600,
				"id_token":      idToken,
			})
		default:
			http.NotFound(w, r)
		}
	}))
	defer provider.Close()

	oauth := newTeslaOAuth(&teslaConfig{
		ClientID:      "test-client",
		ClientSecret:  "test-secret",
		RedirectURI:   provider.URL + "/v1/auth/tesla/callback",
		AppLinkURI:    "https://auth.example.com/oauth/callback",
		Authorization: provider.URL + "/authorize",
		TokenEndpoint: provider.URL + "/token",
		Issuer:        provider.URL,
		JWKSURL:       provider.URL + "/keys",
		FleetAPIBase:  "https://fleet.example.com",
	}, store, mustTestCipher(t), provider.Client())

	consent, err := currentOAuthConsent(jourVoltTermsVersion, jourVoltPrivacyVersion)
	if err != nil {
		t.Fatal(err)
	}
	started, err := oauth.start(ctx, consent)
	if err != nil {
		t.Fatal(err)
	}
	startURL, err := url.Parse(started.AuthorizationURL)
	if err != nil {
		t.Fatal(err)
	}
	state := startURL.Query().Get("state")
	expectedNonce = startURL.Query().Get("nonce")
	if state == "" || expectedNonce == "" {
		t.Fatalf("authorization URL missing state/nonce: %s", started.AuthorizationURL)
	}

	ticket, err := oauth.callback(ctx, url.Values{"state": {state}, "code": {"auth-code"}})
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(ticket) == "" {
		t.Fatal("OAuth callback returned an empty login ticket")
	}
	link, err := url.Parse(oauth.appLink(ticket, ""))
	if err != nil || link.Query().Get("ticket") != ticket {
		t.Fatalf("App Link does not carry the returned ticket: %v", link)
	}

	session, err := store.exchangeLoginTicket(ctx, ticket)
	if err != nil {
		t.Fatal(err)
	}
	if session.UserID == "" || session.AccessToken == "" || session.RefreshToken == "" {
		t.Fatalf("incomplete JourVolt session: %#v", session)
	}
	var recordedTerms, recordedPrivacy string
	if err := store.pool.QueryRow(ctx, `
SELECT terms_version, privacy_version FROM jourvolt_user_consents WHERE user_id=$1`, session.UserID,
	).Scan(&recordedTerms, &recordedPrivacy); err != nil {
		t.Fatal(err)
	}
	if recordedTerms != consent.TermsVersion || recordedPrivacy != consent.PrivacyVersion {
		t.Fatalf("recorded consent = %q/%q, want %q/%q", recordedTerms, recordedPrivacy, consent.TermsVersion, consent.PrivacyVersion)
	}
	if _, err := store.exchangeLoginTicket(ctx, ticket); err == nil {
		t.Fatal("login ticket replay must fail")
	}

	t.Cleanup(func() {
		_, _ = store.pool.Exec(context.Background(),
			`DELETE FROM jourvolt_users WHERE provider_sub=$1`, hashToken("tesla:test-sub"))
	})
}

func TestOAuthConsentRequiresBothCurrentDocumentVersions(t *testing.T) {
	if _, err := currentOAuthConsent(jourVoltTermsVersion, jourVoltPrivacyVersion); err != nil {
		t.Fatalf("current versions should be accepted: %v", err)
	}
	for _, pair := range [][2]string{
		{"", jourVoltPrivacyVersion},
		{jourVoltTermsVersion, ""},
		{"old", jourVoltPrivacyVersion},
		{jourVoltTermsVersion, "old"},
	} {
		if _, err := currentOAuthConsent(pair[0], pair[1]); err == nil {
			t.Fatalf("consent %q/%q should be rejected", pair[0], pair[1])
		}
	}
}

func TestConsentRevokeURLUsesConfiguredTeslaRegion(t *testing.T) {
	oauth := &teslaOAuth{config: &teslaConfig{
		ClientID:      "client-id",
		Authorization: "https://auth.tesla.cn/oauth2/v3/authorize",
		AppLinkURI:    "https://auth.jourvolt.com/oauth/callback",
	}}
	revokeURL, err := url.Parse(oauth.consentRevokeURL())
	if err != nil {
		t.Fatal(err)
	}
	if revokeURL.Scheme != "https" || revokeURL.Host != "auth.tesla.cn" {
		t.Fatalf("revoke URL origin = %s, want auth.tesla.cn", revokeURL)
	}
	if revokeURL.Path != "/user/revoke/consent" {
		t.Fatalf("revoke URL path = %q", revokeURL.Path)
	}
	if got := revokeURL.Query().Get("revoke_client_id"); got != "client-id" {
		t.Fatalf("revoke_client_id = %q", got)
	}
	backURL, err := url.Parse(revokeURL.Query().Get("back_url"))
	if err != nil || backURL.Scheme != "https" || backURL.Host != "auth.jourvolt.com" || backURL.Path != "/privacy/" {
		t.Fatalf("back_url = %q", revokeURL.Query().Get("back_url"))
	}
}

func signedTestIDToken(t *testing.T, signer jose.Signer, issuer, nonce string) string {
	t.Helper()
	claims := map[string]any{
		"iss":   issuer,
		"sub":   "test-sub",
		"aud":   "test-client",
		"nonce": nonce,
		"iat":   time.Now().Unix(),
		"exp":   time.Now().Add(time.Hour).Unix(),
	}
	payload, err := json.Marshal(claims)
	if err != nil {
		t.Fatal(err)
	}
	signedJWS, err := signer.Sign(payload)
	if err != nil {
		t.Fatal(err)
	}
	signed, err := signedJWS.CompactSerialize()
	if err != nil {
		t.Fatal(err)
	}
	return signed
}

func mustTestCipher(t *testing.T) *tokenCipher {
	t.Helper()
	cipher, err := newTokenCipher([]byte("01234567890123456789012345678901"))
	if err != nil {
		t.Fatal(err)
	}
	return cipher
}
