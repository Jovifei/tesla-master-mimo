package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Vehicle-command prerequisites (design 2026-11-05 §6, preconditions P2/P3
// and §6.4): the OAuth scope upgrade, granted-scope detection for existing
// users, the audit trail, and the per-user rate limiter skeleton. No command
// endpoint lives here yet; phase 2 builds on these pieces.

// commandScopes are the user-token scopes a vehicle command requires. The
// portal application already grants them (owner-confirmed 2026-11-04); the
// OAuth configuration requests them and Tesla's prompt_missing_scopes flow
// makes existing users consent to just the missing ones.
var commandScopes = []string{"vehicle_cmds", "vehicle_charging_cmds"}

// parseGrantedScopes reads the granted scopes out of a Tesla access token.
// Tesla issues JWT access tokens whose payload carries the scopes in the
// "scp" claim (a JSON array); a space-separated "scope" claim is accepted
// as a fallback. Any malformed input yields nil (unknown), never a guess.
func parseGrantedScopes(accessToken string) []string {
	parts := strings.Split(accessToken, ".")
	if len(parts) != 3 {
		return nil
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		padded := parts[1] + strings.Repeat("=", (4-len(parts[1])%4)%4)
		payload, err = base64.URLEncoding.DecodeString(padded)
		if err != nil {
			return nil
		}
	}
	var claims struct {
		Scp   []string `json:"scp"`
		Scope string   `json:"scope"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil {
		return nil
	}
	if len(claims.Scp) > 0 {
		return claims.Scp
	}
	if strings.TrimSpace(claims.Scope) != "" {
		return strings.Fields(claims.Scope)
	}
	return nil
}

// parseUserIDSet parses a comma-separated user ID list (the commands gray
// list) into a lookup set. An empty input yields an empty set, which the
// caller treats as "no restriction".
func parseUserIDSet(raw string) map[string]bool {
	set := map[string]bool{}
	for _, part := range strings.Split(raw, ",") {
		if id := strings.TrimSpace(part); id != "" {
			set[id] = true
		}
	}
	return set
}

// missingCommandScopes returns the command scopes absent from a granted
// list, in canonical order. A nil granted list means "unknown" and is
// reported as missing everything so callers fail closed.
func missingCommandScopes(granted []string) []string {
	held := make(map[string]bool, len(granted))
	for _, scope := range granted {
		held[scope] = true
	}
	missing := make([]string, 0, len(commandScopes))
	for _, scope := range commandScopes {
		if !held[scope] {
			missing = append(missing, scope)
		}
	}
	return missing
}

// --- rate limiting (design §6.5 values; owner may retune) ---

const (
	commandDailyLimitNormal    = 30
	commandDailyLimitSensitive = 5
)

// commandRateLimiter counts command attempts per user+vehicle per UTC day
// in two buckets: normal and sensitive. It is an in-memory skeleton — the
// counts reset on restart, which fails safe (a restart can only hand back
// budget within the same day, never carry debt away). A durable counter can
// replace it behind the same surface in phase 2 if the owner wants.
type commandRateLimiter struct {
	mu     sync.Mutex
	now    func() time.Time
	counts map[string]*commandDayCounts
}

type commandDayCounts struct {
	day       string
	normal    int
	sensitive int
}

func newCommandRateLimiter() *commandRateLimiter {
	return &commandRateLimiter{now: time.Now, counts: make(map[string]*commandDayCounts)}
}

func commandRateKey(userID string, vehicleID int) string {
	return userID + "/" + strconv.Itoa(vehicleID)
}

// allow records one attempted command and reports whether it is within the
// daily budget for its bucket. Attempts are counted whether or not the
// command later succeeds: the budget protects the Fleet quota and the car,
// not just successful actuations.
func (l *commandRateLimiter) allow(userID string, vehicleID int, sensitive bool) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	day := l.now().UTC().Format("2006-01-02")
	key := commandRateKey(userID, vehicleID)
	entry := l.counts[key]
	if entry == nil || entry.day != day {
		entry = &commandDayCounts{day: day}
		l.counts[key] = entry
	}
	if sensitive {
		if entry.sensitive >= commandDailyLimitSensitive {
			return false
		}
		entry.sensitive++
		return true
	}
	if entry.normal >= commandDailyLimitNormal {
		return false
	}
	entry.normal++
	return true
}

// --- audit trail (design §6.4) ---

type commandAuditEntry struct {
	UserID     string
	VehicleID  int
	Command    string
	Tier       string // "low" | "mid" | "high"
	Outcome    string // "accepted" | "rejected" | "tesla_error" | "unverified"
	ErrorClass string // taxonomy class when not accepted, else ""
}

func ensureCommandSchema(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx, `
CREATE TABLE IF NOT EXISTS jourvolt_vehicle_command_audit (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL,
    vehicle_id INTEGER NOT NULL,
    command TEXT NOT NULL,
    tier TEXT NOT NULL DEFAULT '',
    outcome TEXT NOT NULL,
    error_class TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS jourvolt_vehicle_command_audit_lookup
    ON jourvolt_vehicle_command_audit (user_id, vehicle_id, created_at);`)
	return err
}

// insertCommandAudit appends one audit row. Auditing is best-effort from the
// caller's perspective in phase 2's flow (the command result is already
// decided when the outcome row is written), but the insert itself reports
// its error so tests and callers can choose to surface it.
func (s *store) insertCommandAudit(ctx context.Context, entry commandAuditEntry) error {
	_, err := s.pool.Exec(ctx, `
INSERT INTO jourvolt_vehicle_command_audit(user_id, vehicle_id, command, tier, outcome, error_class)
VALUES ($1, $2, $3, $4, $5, $6)`,
		entry.UserID, entry.VehicleID, entry.Command, entry.Tier, entry.Outcome, entry.ErrorClass)
	return err
}
