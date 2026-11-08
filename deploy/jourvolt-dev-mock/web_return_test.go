package main

import (
	"strings"
	"testing"
)

func TestValidateWebReturnEntry(t *testing.T) {
	valid := []string{
		"https://web.teslalink.joviluma.com/auth/complete",
		"https://example.com/callback",
		"http://localhost:5173/auth/complete",
		"http://127.0.0.1:4173/auth/complete",
	}
	for _, entry := range valid {
		if err := validateWebReturnEntry(entry); err != nil {
			t.Errorf("entry %q should be valid: %v", entry, err)
		}
	}
	invalid := []string{
		"http://web.teslalink.joviluma.com/auth/complete", // http only for loopback
		"https://user@evil.example.com/callback",          // userinfo
		"https://example.com/callback#fragment",           // fragment
		"web.teslalink.joviluma.com/auth/complete",        // not absolute
		"ftp://example.com/callback",
		"https:///no-host",
	}
	for _, entry := range invalid {
		if err := validateWebReturnEntry(entry); err == nil {
			t.Errorf("entry %q should be rejected", entry)
		}
	}
}

func TestValidateReturnToExactMatchOnly(t *testing.T) {
	oauth := &teslaOAuth{config: &teslaConfig{WebReturnAllowlist: []string{
		"https://web.teslalink.joviluma.com/auth/complete",
		"http://localhost:5173/auth/complete",
	}}}
	if got, err := oauth.validateReturnTo(""); err != nil || got != "" {
		t.Fatalf("empty return_to must pass through empty, got %q err %v", got, err)
	}
	if got, err := oauth.validateReturnTo("https://web.teslalink.joviluma.com/auth/complete"); err != nil || got == "" {
		t.Fatalf("allowlisted return_to must be accepted, got %q err %v", got, err)
	}
	rejected := []string{
		// Host-suffix spoof, path variation, query smuggling, case and
		// trailing-slash tricks: exact match rejects them all.
		"https://web.teslalink.joviluma.com.evil.com/auth/complete",
		"https://web.teslalink.joviluma.com/auth/complete/extra",
		"https://web.teslalink.joviluma.com/auth/complete?ticket=forged",
		"https://web.teslalink.joviluma.com/auth/complete/",
		"HTTPS://web.teslalink.joviluma.com/auth/complete",
		"https://web.teslalink.joviluma.com/auth/complete%2f..",
		"http://localhost:5173/auth/complete.evil.com",
		"https://evil.com/?next=https://web.teslalink.joviluma.com/auth/complete",
	}
	for _, raw := range rejected {
		if _, err := oauth.validateReturnTo(raw); err == nil {
			t.Errorf("return_to %q must be rejected (exact-match allowlist)", raw)
		}
	}
}

func TestReturnLinkSetsTicketOrError(t *testing.T) {
	got := returnLink("https://web.teslalink.joviluma.com/auth/complete", "ticket-123", "")
	if !strings.HasPrefix(got, "https://web.teslalink.joviluma.com/auth/complete?") || !strings.Contains(got, "ticket=ticket-123") {
		t.Errorf("ticket link wrong: %s", got)
	}
	got = returnLink("http://localhost:5173/auth/complete", "", "access_denied")
	if !strings.Contains(got, "error=access_denied") || strings.Contains(got, "ticket=") {
		t.Errorf("error link wrong: %s", got)
	}
	// A pre-existing ticket/error in the base URL is replaced, not duplicated.
	got = returnLink("https://example.com/cb?ticket=old&keep=1", "new", "")
	if strings.Count(got, "ticket=") != 1 || !strings.Contains(got, "ticket=new") || !strings.Contains(got, "keep=1") {
		t.Errorf("replacement wrong: %s", got)
	}
}

func TestLoadTeslaConfigWebReturnAllowlist(t *testing.T) {
	values := map[string]string{
		"TESLA_CLIENT_ID":               "id",
		"TESLA_CLIENT_SECRET":           "secret",
		"TESLA_REDIRECT_URI":            "https://api.example.com/v1/auth/tesla/callback",
		"JOURVOLT_APP_LINK_URI":         "https://auth.example.com/oauth/callback",
		"JOURVOLT_TOKEN_KEY_BASE64":     "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		"JOURVOLT_WEB_RETURN_ALLOWLIST": "https://web.example.com/auth/complete, http://localhost:5173/auth/complete",
	}
	config, err := loadTeslaConfig(func(name string) string { return values[name] })
	if err != nil {
		t.Fatalf("loadTeslaConfig: %v", err)
	}
	if len(config.WebReturnAllowlist) != 2 {
		t.Fatalf("allowlist parsed wrong: %v", config.WebReturnAllowlist)
	}
	values["JOURVOLT_WEB_RETURN_ALLOWLIST"] = "http://not-loopback.example.com/auth/complete"
	if _, err := loadTeslaConfig(func(name string) string { return values[name] }); err == nil {
		t.Fatal("non-loopback http entry must fail config load")
	}
}
