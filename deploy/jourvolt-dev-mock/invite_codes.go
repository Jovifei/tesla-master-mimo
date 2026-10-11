package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var inviteCodeFormatRegex = regexp.MustCompile(`^[A-Z0-9]{4,16}$`)

type inviteCodeRecord struct {
	Code      string
	MaxUses   int
	UsedCount int
	ExpiresAt time.Time
	Note      string
	CreatedAt time.Time
}

type inMemoryInviteStore struct {
	mu    sync.RWMutex
	codes map[string]*inviteCodeRecord
	uses  []inviteCodeUseRecord
}

type inviteCodeUseRecord struct {
	Code       string
	UserID     string
	UsedAt     time.Time
	ClientType string
}

func newInMemoryInviteStore() *inMemoryInviteStore {
	store := &inMemoryInviteStore{
		codes: make(map[string]*inviteCodeRecord),
	}
	// Pre-seed a default test code
	store.codes["MATELINK2026"] = &inviteCodeRecord{
		Code:      "MATELINK2026",
		MaxUses:   100,
		UsedCount: 0,
		ExpiresAt: time.Now().UTC().Add(365 * 24 * time.Hour),
		Note:      "Pre-seeded test invite code",
		CreatedAt: time.Now().UTC(),
	}
	return store
}

var fallbackInviteStore = newInMemoryInviteStore()

func ensureInviteSchema(ctx context.Context, pool *pgxpool.Pool) error {
	if pool == nil {
		return nil
	}
	_, err := pool.Exec(ctx, `
CREATE TABLE IF NOT EXISTS jourvolt_invite_codes (
    code TEXT PRIMARY KEY,
    max_uses INTEGER NOT NULL DEFAULT 1,
    used_count INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT jourvolt_invite_used_count CHECK (used_count <= max_uses)
);

CREATE TABLE IF NOT EXISTS jourvolt_invite_code_uses (
    id BIGSERIAL PRIMARY KEY,
    code TEXT NOT NULL REFERENCES jourvolt_invite_codes(code) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES jourvolt_users(id) ON DELETE CASCADE,
    used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    client_type TEXT NOT NULL DEFAULT 'unknown'
);

CREATE INDEX IF NOT EXISTS jourvolt_invite_code_uses_code_idx ON jourvolt_invite_code_uses(code);
CREATE INDEX IF NOT EXISTS jourvolt_invite_code_uses_user_idx ON jourvolt_invite_code_uses(user_id);
`)
	return err
}

func normalizeInviteCode(raw string) string {
	return strings.ToUpper(strings.TrimSpace(raw))
}

func (s *store) validateInviteCode(ctx context.Context, rawCode string) (bool, int, string, error) {
	code := normalizeInviteCode(rawCode)
	if !inviteCodeFormatRegex.MatchString(code) {
		return false, 0, "invalid_format", nil
	}

	if s == nil || s.pool == nil {
		fallbackInviteStore.mu.RLock()
		defer fallbackInviteStore.mu.RUnlock()
		record, exists := fallbackInviteStore.codes[code]
		if !exists {
			return false, 0, "not_found", nil
		}
		if time.Now().UTC().After(record.ExpiresAt) {
			return false, 0, "expired", nil
		}
		if record.UsedCount >= record.MaxUses {
			return false, 0, "exhausted", nil
		}
		return true, record.MaxUses - record.UsedCount, "ok", nil
	}

	var maxUses, usedCount int
	var expiresAt time.Time
	err := s.pool.QueryRow(ctx, `
		SELECT max_uses, used_count, expires_at
		FROM jourvolt_invite_codes
		WHERE code = $1
	`, code).Scan(&maxUses, &usedCount, &expiresAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return false, 0, "not_found", nil
		}
		return false, 0, "", err
	}

	if time.Now().UTC().After(expiresAt) {
		return false, 0, "expired", nil
	}
	if usedCount >= maxUses {
		return false, 0, "exhausted", nil
	}
	return true, maxUses - usedCount, "ok", nil
}

func (s *store) claimInviteCode(ctx context.Context, rawCode, userID, clientType string) error {
	code := normalizeInviteCode(rawCode)
	if !inviteCodeFormatRegex.MatchString(code) {
		return errors.New("invalid_format")
	}

	if s == nil || s.pool == nil {
		fallbackInviteStore.mu.Lock()
		defer fallbackInviteStore.mu.Unlock()
		record, exists := fallbackInviteStore.codes[code]
		if !exists {
			return errors.New("not_found")
		}
		if time.Now().UTC().After(record.ExpiresAt) {
			return errors.New("expired")
		}
		if record.UsedCount >= record.MaxUses {
			return errors.New("exhausted")
		}
		record.UsedCount++
		fallbackInviteStore.uses = append(fallbackInviteStore.uses, inviteCodeUseRecord{
			Code:       code,
			UserID:     userID,
			UsedAt:     time.Now().UTC(),
			ClientType: clientType,
		})
		return nil
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var maxUses, usedCount int
	var expiresAt time.Time
	err = tx.QueryRow(ctx, `
		SELECT max_uses, used_count, expires_at
		FROM jourvolt_invite_codes
		WHERE code = $1
		FOR UPDATE
	`, code).Scan(&maxUses, &usedCount, &expiresAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return errors.New("not_found")
		}
		return err
	}

	if time.Now().UTC().After(expiresAt) {
		return errors.New("expired")
	}
	if usedCount >= maxUses {
		return errors.New("exhausted")
	}

	_, err = tx.Exec(ctx, `
		UPDATE jourvolt_invite_codes
		SET used_count = used_count + 1
		WHERE code = $1
	`, code)
	if err != nil {
		return err
	}

	_, err = tx.Exec(ctx, `
		INSERT INTO jourvolt_invite_code_uses (code, user_id, used_at, client_type)
		VALUES ($1, $2, now(), $3)
	`, code, userID, clientType)
	if err != nil {
		return err
	}

	return tx.Commit(ctx)
}

func (s *store) createInviteCode(ctx context.Context, code string, maxUses int, expiresAt time.Time, note string) error {
	norm := normalizeInviteCode(code)
	if !inviteCodeFormatRegex.MatchString(norm) {
		return fmt.Errorf("invalid invite code format: %q", code)
	}
	if s == nil || s.pool == nil {
		fallbackInviteStore.mu.Lock()
		defer fallbackInviteStore.mu.Unlock()
		fallbackInviteStore.codes[norm] = &inviteCodeRecord{
			Code:      norm,
			MaxUses:   maxUses,
			UsedCount: 0,
			ExpiresAt: expiresAt,
			Note:      note,
			CreatedAt: time.Now().UTC(),
		}
		return nil
	}

	_, err := s.pool.Exec(ctx, `
		INSERT INTO jourvolt_invite_codes (code, max_uses, used_count, expires_at, note, created_at)
		VALUES ($1, $2, 0, $3, $4, now())
		ON CONFLICT (code) DO UPDATE
		SET max_uses = EXCLUDED.max_uses,
		    expires_at = EXCLUDED.expires_at,
		    note = EXCLUDED.note
	`, norm, maxUses, expiresAt, note)
	return err
}

func (a *app) validateInvite(w http.ResponseWriter, r *http.Request) {
	var code string
	if r.Method == http.MethodGet {
		code = r.URL.Query().Get("code")
	} else if r.Method == http.MethodPost {
		var req struct {
			Code string `json:"code"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			a.json(w, http.StatusBadRequest, map[string]any{
				"valid": false,
				"error": "invalid_request",
			})
			return
		}
		code = req.Code
	} else {
		a.json(w, http.StatusMethodNotAllowed, map[string]string{"error": "method_not_allowed"})
		return
	}

	valid, remaining, reason, err := a.store.validateInviteCode(r.Context(), code)
	if err != nil {
		a.json(w, http.StatusInternalServerError, map[string]any{
			"valid": false,
			"error": "store_error",
		})
		return
	}

	if !valid {
		a.json(w, http.StatusBadRequest, map[string]any{
			"valid":  false,
			"reason": reason,
		})
		return
	}

	a.json(w, http.StatusOK, map[string]any{
		"valid":     true,
		"code":      normalizeInviteCode(code),
		"remaining": remaining,
	})
}

func (a *app) claimInvite(w http.ResponseWriter, r *http.Request) {
	userID, ok := a.auth(w, r)
	if !ok {
		return
	}

	var req struct {
		Code       string `json:"code"`
		ClientType string `json:"client_type"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_request"})
		return
	}

	clientType := strings.TrimSpace(req.ClientType)
	if clientType == "" {
		clientType = "unknown"
	}

	if err := a.store.claimInviteCode(r.Context(), req.Code, userID, clientType); err != nil {
		a.json(w, http.StatusBadRequest, map[string]any{
			"success": false,
			"error":   err.Error(),
		})
		return
	}

	a.json(w, http.StatusOK, map[string]any{
		"success": true,
		"code":    normalizeInviteCode(req.Code),
	})
}
