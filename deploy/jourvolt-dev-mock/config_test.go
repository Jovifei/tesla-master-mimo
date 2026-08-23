package main

import (
	"bytes"
	"encoding/base64"
	"testing"
)

func TestTeslaConfigIsAllOrNothing(t *testing.T) {
	empty := func(string) string { return "" }
	config, err := loadTeslaConfig(empty)
	if err != nil || config != nil {
		t.Fatalf("empty configuration = %#v, %v; want nil, nil", config, err)
	}

	partial := map[string]string{"TESLA_CLIENT_ID": "client-id"}
	if _, err := loadTeslaConfig(func(name string) string { return partial[name] }); err == nil {
		t.Fatal("partial Tesla configuration must fail closed")
	}
}

func TestTeslaConfigAcceptsCompleteHTTPSSettings(t *testing.T) {
	values := map[string]string{
		"TESLA_CLIENT_ID":           "client-id",
		"TESLA_CLIENT_SECRET":       "client-secret",
		"TESLA_REDIRECT_URI":        "https://api.example.com/v1/auth/tesla/callback",
		"JOURVOLT_APP_LINK_URI":     "https://auth.example.com/oauth/callback",
		"JOURVOLT_TOKEN_KEY_BASE64": base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32)),
	}
	config, err := loadTeslaConfig(func(name string) string { return values[name] })
	if err != nil {
		t.Fatal(err)
	}
	if config == nil || config.FleetAPIBase != defaultTeslaFleetURL {
		t.Fatalf("unexpected Tesla configuration: %#v", config)
	}
}

func TestTeslaConfigRejectsUnexpectedCallbackPaths(t *testing.T) {
	base := map[string]string{
		"TESLA_CLIENT_ID":           "client-id",
		"TESLA_CLIENT_SECRET":       "client-secret",
		"TESLA_REDIRECT_URI":        "https://api.example.com/v1/auth/tesla/callback",
		"JOURVOLT_APP_LINK_URI":     "https://auth.example.com/oauth/callback",
		"JOURVOLT_TOKEN_KEY_BASE64": base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32)),
	}
	cases := map[string]string{
		"TESLA_REDIRECT_URI":    "https://api.example.com/wrong",
		"JOURVOLT_APP_LINK_URI": "https://auth.example.com/wrong",
	}
	for name, invalidValue := range cases {
		values := make(map[string]string, len(base))
		for key, value := range base {
			values[key] = value
		}
		values[name] = invalidValue
		if _, err := loadTeslaConfig(func(key string) string { return values[key] }); err == nil {
			t.Fatalf("%s with unexpected path must fail closed", name)
		}
	}
}

func TestTeslaChinaDefaultsMatchOfficialOIDCMetadata(t *testing.T) {
	if defaultTeslaAuthURL != "https://auth.tesla.cn/oauth2/v3/authorize" {
		t.Fatalf("authorization endpoint = %q", defaultTeslaAuthURL)
	}
	if defaultTeslaTokenURL != "https://auth.tesla.cn/oauth2/v3/token" {
		t.Fatalf("token endpoint = %q", defaultTeslaTokenURL)
	}
	if defaultTeslaIssuer != "https://auth.tesla.cn/oauth2/v3/nts" {
		t.Fatalf("issuer = %q", defaultTeslaIssuer)
	}
	if defaultTeslaJWKSURL != "https://auth.tesla.cn/oauth2/v3/discovery/thirdparty/keys" {
		t.Fatalf("JWKS endpoint = %q", defaultTeslaJWKSURL)
	}
	if defaultTeslaFleetURL != "https://fleet-api.prd.cn.vn.cloud.tesla.cn" {
		t.Fatalf("Fleet API base = %q", defaultTeslaFleetURL)
	}
}

func TestTokenCipherRoundTripAndRandomNonce(t *testing.T) {
	cipher, err := newTokenCipher(bytes.Repeat([]byte{3}, 32))
	if err != nil {
		t.Fatal(err)
	}
	first, err := cipher.encrypt("sensitive-token")
	if err != nil {
		t.Fatal(err)
	}
	second, err := cipher.encrypt("sensitive-token")
	if err != nil {
		t.Fatal(err)
	}
	if first == second {
		t.Fatal("AES-GCM encryption must use a fresh nonce")
	}
	plain, err := cipher.decrypt(first)
	if err != nil || plain != "sensitive-token" {
		t.Fatalf("decrypt = %q, %v", plain, err)
	}
}
