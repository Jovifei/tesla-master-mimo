package main

import (
	"encoding/base64"
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

const (
	defaultTeslaAuthURL  = "https://auth.tesla.cn/oauth2/v3/authorize"
	defaultTeslaTokenURL = "https://auth.tesla.cn/oauth2/v3/token"
	// Tesla's authorization response includes RFC 9207 `iss=https://auth.tesla.cn/oauth2/v3`
	// (observed on live callback 2026-08-30). That document's JWKS is discovery/keys,
	// not the partner NTS thirdparty/keys used for client_credentials.
	defaultTeslaIssuer   = "https://auth.tesla.cn/oauth2/v3"
	defaultTeslaJWKSURL  = "https://auth.tesla.cn/oauth2/v3/discovery/keys"
	defaultTeslaFleetURL = "https://fleet-api.prd.cn.vn.cloud.tesla.cn"
	teslaNTSIssuer       = "https://auth.tesla.cn/oauth2/v3/nts"
	teslaNTSJWKSURL      = "https://auth.tesla.cn/oauth2/v3/discovery/thirdparty/keys"
)

type teslaConfig struct {
	ClientID      string
	ClientSecret  string
	RedirectURI   string
	AppLinkURI    string
	TokenKey      []byte
	Authorization string
	TokenEndpoint string
	Issuer        string
	JWKSURL       string
	FleetAPIBase  string
	PartnerDomain string
}

func loadTeslaConfig(getenv func(string) string) (*teslaConfig, error) {
	required := []string{
		"TESLA_CLIENT_ID",
		"TESLA_CLIENT_SECRET",
		"TESLA_REDIRECT_URI",
		"JOURVOLT_APP_LINK_URI",
		"JOURVOLT_TOKEN_KEY_BASE64",
	}
	values := make(map[string]string, len(required))
	configured := 0
	for _, name := range required {
		values[name] = strings.TrimSpace(getenv(name))
		if values[name] != "" {
			configured++
		}
	}
	if configured == 0 {
		return nil, nil
	}
	if configured != len(required) {
		missing := make([]string, 0, len(required)-configured)
		for _, name := range required {
			if values[name] == "" {
				missing = append(missing, name)
			}
		}
		return nil, fmt.Errorf("incomplete Tesla configuration; missing %s", strings.Join(missing, ", "))
	}

	key, err := base64.StdEncoding.DecodeString(values["JOURVOLT_TOKEN_KEY_BASE64"])
	if err != nil || len(key) != 32 {
		return nil, fmt.Errorf("JOURVOLT_TOKEN_KEY_BASE64 must encode exactly 32 bytes")
	}
	if err := requireHTTPSPathURL(
		"TESLA_REDIRECT_URI",
		values["TESLA_REDIRECT_URI"],
		"/v1/auth/tesla/callback",
	); err != nil {
		return nil, err
	}
	if err := requireHTTPSPathURL(
		"JOURVOLT_APP_LINK_URI",
		values["JOURVOLT_APP_LINK_URI"],
		"/oauth/callback",
	); err != nil {
		return nil, err
	}

	appLink, err := url.Parse(values["JOURVOLT_APP_LINK_URI"])
	if err != nil || appLink.Hostname() == "" {
		return nil, fmt.Errorf("JOURVOLT_APP_LINK_URI must include a hostname")
	}
	partnerDomain, err := normalizePartnerDomain(envOrDefault(getenv, "TESLA_PARTNER_DOMAIN", appLink.Hostname()))
	if err != nil {
		return nil, fmt.Errorf("TESLA_PARTNER_DOMAIN: %w", err)
	}

	config := &teslaConfig{
		ClientID:      values["TESLA_CLIENT_ID"],
		ClientSecret:  values["TESLA_CLIENT_SECRET"],
		RedirectURI:   values["TESLA_REDIRECT_URI"],
		AppLinkURI:    values["JOURVOLT_APP_LINK_URI"],
		TokenKey:      key,
		Authorization: envOrDefault(getenv, "TESLA_AUTH_URL", defaultTeslaAuthURL),
		TokenEndpoint: envOrDefault(getenv, "TESLA_TOKEN_URL", defaultTeslaTokenURL),
		Issuer:        envOrDefault(getenv, "TESLA_ISSUER", defaultTeslaIssuer),
		JWKSURL:       envOrDefault(getenv, "TESLA_JWKS_URL", defaultTeslaJWKSURL),
		FleetAPIBase:  strings.TrimRight(envOrDefault(getenv, "TESLA_FLEET_API_BASE", defaultTeslaFleetURL), "/"),
		PartnerDomain: partnerDomain,
	}
	for name, value := range map[string]string{
		"TESLA_AUTH_URL":       config.Authorization,
		"TESLA_TOKEN_URL":      config.TokenEndpoint,
		"TESLA_ISSUER":         config.Issuer,
		"TESLA_JWKS_URL":       config.JWKSURL,
		"TESLA_FLEET_API_BASE": config.FleetAPIBase,
	} {
		if err := requireHTTPSURL(name, value); err != nil {
			return nil, err
		}
	}
	return config, nil
}

func loadBool(getenv func(string) string, name string, fallback bool) (bool, error) {
	raw := strings.TrimSpace(getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.ParseBool(raw)
	if err != nil {
		return false, fmt.Errorf("%s must be true or false", name)
	}
	return value, nil
}

func envOrDefault(getenv func(string) string, name, fallback string) string {
	if value := strings.TrimSpace(getenv(name)); value != "" {
		return value
	}
	return fallback
}

func requireHTTPSURL(name, value string) error {
	parsed, err := url.Parse(value)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" ||
		parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" ||
		(parsed.Port() != "" && parsed.Port() != "443") {
		return fmt.Errorf("%s must be an absolute HTTPS URL", name)
	}
	return nil
}

func normalizePartnerDomain(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", fmt.Errorf("must be a hostname")
	}
	if strings.Contains(raw, "://") {
		parsed, err := url.Parse(raw)
		if err != nil || parsed.Hostname() == "" || parsed.User != nil {
			return "", fmt.Errorf("must be a hostname")
		}
		return parsed.Hostname(), nil
	}
	if strings.ContainsAny(raw, "/?#:@") {
		return "", fmt.Errorf("must be a hostname")
	}
	return raw, nil
}

func requireHTTPSPathURL(name, value, expectedPath string) error {
	if err := requireHTTPSURL(name, value); err != nil {
		return err
	}
	parsed, err := url.Parse(value)
	if err != nil || parsed.Path != expectedPath {
		return fmt.Errorf("%s must use path %s", name, expectedPath)
	}
	return nil
}
