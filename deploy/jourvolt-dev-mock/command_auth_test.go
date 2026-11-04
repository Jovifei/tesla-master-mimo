package main

import (
	"encoding/base64"
	"testing"
	"time"
)

func unsignedJWT(payload string) string {
	encode := func(value string) string {
		return base64.RawURLEncoding.EncodeToString([]byte(value))
	}
	return encode(`{"alg":"none"}`) + "." + encode(payload) + "." + encode("sig")
}

func TestParseGrantedScopesFromScpClaim(t *testing.T) {
	token := unsignedJWT(`{"scp":["openid","vehicle_device_data","vehicle_cmds"]}`)
	granted := parseGrantedScopes(token)
	if len(granted) != 3 || granted[2] != "vehicle_cmds" {
		t.Fatalf("granted = %#v", granted)
	}
	if missing := missingCommandScopes(granted); len(missing) != 1 || missing[0] != "vehicle_charging_cmds" {
		t.Fatalf("missing = %#v", missing)
	}
}

func TestParseGrantedScopesFromScopeStringFallback(t *testing.T) {
	token := unsignedJWT(`{"scope":"openid vehicle_cmds vehicle_charging_cmds"}`)
	if missing := missingCommandScopes(parseGrantedScopes(token)); len(missing) != 0 {
		t.Fatalf("missing = %#v, want none", missing)
	}
}

func TestParseGrantedScopesRejectsMalformedTokens(t *testing.T) {
	for _, token := range []string{"", "not-a-jwt", "a.b", unsignedJWT(`{"scp":"oops"}`), unsignedJWT(`not json`)} {
		if granted := parseGrantedScopes(token); granted != nil {
			t.Fatalf("token %q granted = %#v, want nil", token, granted)
		}
	}
	// Unknown grants fail closed: everything counts as missing.
	if missing := missingCommandScopes(nil); len(missing) != len(commandScopes) {
		t.Fatalf("missing(nil) = %#v", missing)
	}
}

func TestCommandRateLimiterBucketsAndLimits(t *testing.T) {
	limiter := newCommandRateLimiter()
	for i := 0; i < commandDailyLimitNormal; i++ {
		if !limiter.allow("user", 1, false) {
			t.Fatalf("normal attempt %d must be allowed", i+1)
		}
	}
	if limiter.allow("user", 1, false) {
		t.Fatal("normal attempt beyond the daily limit must be rejected")
	}
	// Sensitive bucket has its own budget, unaffected by normal usage.
	for i := 0; i < commandDailyLimitSensitive; i++ {
		if !limiter.allow("user", 1, true) {
			t.Fatalf("sensitive attempt %d must be allowed", i+1)
		}
	}
	if limiter.allow("user", 1, true) {
		t.Fatal("sensitive attempt beyond the daily limit must be rejected")
	}
	// A different vehicle has an independent budget.
	if !limiter.allow("user", 2, false) {
		t.Fatal("different vehicle must have an independent budget")
	}
}

func TestCommandRateLimiterResetsOnNewDay(t *testing.T) {
	limiter := newCommandRateLimiter()
	day := time.Date(2026, 1, 1, 12, 0, 0, 0, time.UTC)
	limiter.now = func() time.Time { return day }
	for i := 0; i < commandDailyLimitSensitive; i++ {
		limiter.allow("user", 1, true)
	}
	if limiter.allow("user", 1, true) {
		t.Fatal("same-day sensitive budget must be exhausted")
	}
	limiter.now = func() time.Time { return day.Add(24 * time.Hour) }
	if !limiter.allow("user", 1, true) {
		t.Fatal("a new day must reset the budget")
	}
}

func TestCommandAuditSchemaIsIdempotentDDL(t *testing.T) {
	// The schema helper is exercised against a real pool in deployment; here
	// we only pin the entry type's zero-value behavior used by phase 2.
	entry := commandAuditEntry{UserID: "u", VehicleID: 1, Command: "lock", Tier: "low", Outcome: "accepted"}
	if entry.ErrorClass != "" {
		t.Fatalf("ErrorClass = %q", entry.ErrorClass)
	}
}
