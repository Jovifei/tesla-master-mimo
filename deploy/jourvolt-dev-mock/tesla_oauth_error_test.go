package main

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"testing"

	"golang.org/x/oauth2"
)

func TestTeslaAppLinkErrorMapsTeslaTokenCodes(t *testing.T) {
	retrieve := &oauth2.RetrieveError{
		ErrorCode: "unauthorized_client",
		Response:  &http.Response{StatusCode: 401},
		Body:      []byte(`{"error":"unauthorized_client"}`),
	}
	if got := teslaAppLinkError(fmt.Errorf("tesla token exchange: %w", retrieve)); got != "unauthorized_client" {
		t.Fatalf("got %q", got)
	}
}

func TestTeslaOAuthFailsClosedWithoutStoreOrCipher(t *testing.T) {
	oauth := &teslaOAuth{}
	consent, err := currentOAuthConsent(jourVoltTermsVersion, jourVoltPrivacyVersion)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := oauth.start(context.Background(), consent); err == nil {
		t.Fatal("OAuth start must fail closed when the store is unavailable")
	}
	if _, err := oauth.callback(context.Background(), nil); !errors.Is(err, errOAuthCallbackRejected) {
		t.Fatalf("OAuth callback error = %v, want %v", err, errOAuthCallbackRejected)
	}
}

func TestTeslaAppLinkErrorMapsIDTokenFailure(t *testing.T) {
	err := fmt.Errorf("tesla id_token: %w", errors.New("oidc: id token issued by a different provider"))
	if got := teslaAppLinkError(err); got != "id_token_invalid" {
		t.Fatalf("got %q", got)
	}
}

func TestTeslaAppLinkErrorFallsBackForGenericRejection(t *testing.T) {
	if got := teslaAppLinkError(errOAuthCallbackRejected); got != "authorization_failed" {
		t.Fatalf("got %q", got)
	}
}

func TestSanitizeTeslaErrorCodeRejectsUnsafeValues(t *testing.T) {
	if got := sanitizeTeslaErrorCode("not a code"); got != "" {
		t.Fatalf("got %q", got)
	}
	if got := sanitizeTeslaErrorCode("invalid_grant"); got != "invalid_grant" {
		t.Fatalf("got %q", got)
	}
}

func TestVerifierForIssuerPrefersCallbackRFC9207Iss(t *testing.T) {
	oauth := newTeslaOAuthForIssuerTests()
	if oauth.verifierForIssuer("https://auth.tesla.cn/oauth2/v3") == oauth.verifierForIssuer(teslaNTSIssuer) {
		t.Fatal("user-token issuer must not reuse the NTS verifier")
	}
	if oauth.verifierForIssuer("https://evil.example") == nil {
		t.Fatal("unknown issuer should fall back to configured verifier")
	}
}

func TestPeekJWTIssuerReadsIssClaim(t *testing.T) {
	got := peekJWTIssuer(unsignedTestJWT(teslaNTSIssuer))
	if got != teslaNTSIssuer {
		t.Fatalf("peekJWTIssuer = %q", got)
	}
}

func TestPeekJWTIssuerRejectsGarbage(t *testing.T) {
	if got := peekJWTIssuer("not-a-jwt"); got != "" {
		t.Fatalf("got %q", got)
	}
}

func TestAllowedVerifierRejectsUnknownIssuer(t *testing.T) {
	oauth := newTeslaOAuthForIssuerTests()
	if oauth.allowedVerifier("https://evil.example") != nil {
		t.Fatal("unknown issuer must not be allowlisted")
	}
	if oauth.allowedVerifier(teslaNTSIssuer) == nil {
		t.Fatal("NTS issuer must be allowlisted")
	}
	if oauth.allowedVerifier(defaultTeslaIssuer+"/") == nil {
		t.Fatal("trailing slash must still match the allowlisted issuer")
	}
}

func TestIDTokenIssuerCandidatesPreferTokenIss(t *testing.T) {
	oauth := newTeslaOAuthForIssuerTests()
	got := oauth.idTokenIssuerCandidates(unsignedTestJWT(teslaNTSIssuer), defaultTeslaIssuer)
	if len(got) != 2 || got[0] != teslaNTSIssuer || got[1] != defaultTeslaIssuer {
		t.Fatalf("candidates = %#v", got)
	}
}

func newTeslaOAuthForIssuerTests() *teslaOAuth {
	return newTeslaOAuth(&teslaConfig{
		ClientID:      "client",
		ClientSecret:  "secret",
		RedirectURI:   "https://api.example.com/v1/auth/tesla/callback",
		AppLinkURI:    "https://auth.example.com/oauth/callback",
		Authorization: defaultTeslaAuthURL,
		TokenEndpoint: defaultTeslaTokenURL,
		Issuer:        teslaNTSIssuer,
		JWKSURL:       teslaNTSJWKSURL,
		FleetAPIBase:  defaultTeslaFleetURL,
	}, nil, nil, http.DefaultClient)
}

func unsignedTestJWT(issuer string) string {
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"none"}`))
	payload := base64.RawURLEncoding.EncodeToString([]byte(`{"iss":"` + issuer + `"}`))
	return header + "." + payload + ".x"
}
