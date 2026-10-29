package main

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

var (
	errWeChatNotBound    = errors.New("wechat_not_bound")
	errWeChatConflict    = errors.New("wechat_binding_conflict")
	errWeChatBindExpired = errors.New("wechat_bind_expired")
	errSessionRequired   = errors.New("session_required")
)

func ensureWeChatSchema(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx, `
CREATE TABLE IF NOT EXISTS jourvolt_wechat_bindings (
  id BIGSERIAL PRIMARY KEY,
  user_id TEXT UNIQUE NOT NULL REFERENCES jourvolt_users(id) ON DELETE CASCADE,
  provider TEXT NOT NULL,
  openid_hash TEXT NOT NULL,
  unionid_hash TEXT,
  restore_not_before TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(provider, openid_hash),
  UNIQUE(provider, unionid_hash)
);
CREATE TABLE IF NOT EXISTS jourvolt_wechat_codes (
  provider TEXT NOT NULL,
  code_hash TEXT NOT NULL,
  started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  expires_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + interval '1 day'),
  PRIMARY KEY(provider, code_hash)
);
CREATE INDEX IF NOT EXISTS jourvolt_wechat_codes_expiry_idx ON jourvolt_wechat_codes(expires_at);
ALTER TABLE jourvolt_sessions ADD COLUMN IF NOT EXISTS wechat_binding_id BIGINT REFERENCES jourvolt_wechat_bindings(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS jourvolt_sessions_wechat_binding_idx ON jourvolt_sessions(wechat_binding_id);
CREATE TABLE IF NOT EXISTS jourvolt_wechat_binding_versions (
  user_id TEXT PRIMARY KEY REFERENCES jourvolt_users(id) ON DELETE CASCADE,
  generation BIGINT NOT NULL CHECK (generation >= 0)
);
CREATE TABLE IF NOT EXISTS jourvolt_logout_aliases (
  access_hash TEXT PRIMARY KEY,
  session_id BIGINT NOT NULL REFERENCES jourvolt_sessions(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS jourvolt_logout_aliases_expiry_idx ON jourvolt_logout_aliases(expires_at);
CREATE INDEX IF NOT EXISTS jourvolt_logout_aliases_session_idx ON jourvolt_logout_aliases(session_id);`)
	return err
}

// Claim before contacting WeChat so retries and concurrent requests cannot
// consume the same code twice, including across processes and restarts.
func (s *store) claimWeChatCode(ctx context.Context, provider, code string) (time.Time, error) {
	if _, err := s.pool.Exec(ctx, `DELETE FROM jourvolt_wechat_codes WHERE expires_at < clock_timestamp()`); err != nil {
		return time.Time{}, err
	}
	var started time.Time
	err := s.pool.QueryRow(ctx, `INSERT INTO jourvolt_wechat_codes(provider,code_hash)
VALUES($1,$2) ON CONFLICT DO NOTHING RETURNING started_at`, provider, hashToken("wechat:code:"+provider+":"+code)).Scan(&started)
	if errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, errWeChatCodeInvalid
	}
	return started, err
}

func wechatHashes(provider string, identity wechatIdentity) (string, string) {
	open := hashToken("wechat:openid:" + provider + ":" + identity.OpenID)
	union := ""
	if identity.UnionID != "" {
		// UnionID is scoped to this configured mini-program provider. We never
		// infer a shared developer/Open Platform identity across applications.
		union = hashToken("wechat:unionid:" + provider + ":" + identity.UnionID)
	}
	return open, union
}

func lockWeChatUser(ctx context.Context, tx pgx.Tx, userID string) error {
	var id string
	err := tx.QueryRow(ctx, `SELECT id FROM jourvolt_users WHERE id=$1 FOR UPDATE`, userID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return errSessionRequired
	}
	return err
}

func lockWeChatSession(ctx context.Context, tx pgx.Tx, token, userID string) (time.Time, error) {
	var expiry time.Time
	err := tx.QueryRow(ctx, `SELECT access_expires_at FROM jourvolt_sessions
WHERE access_hash=$1 AND user_id=$2 AND revoked_at IS NULL AND access_expires_at > clock_timestamp() FOR UPDATE`, hashToken(token), userID).Scan(&expiry)
	if errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, errSessionRequired
	}
	return expiry, err
}

// Locks, uniqueness checks and cascading deletes may wait after the first
// validation. Recheck against the database's actual clock before committing.
// The saved expiry also works when unbind deletes the caller's session.
func ensureWeChatSessionFresh(ctx context.Context, tx pgx.Tx, expiry time.Time) error {
	var valid bool
	if err := tx.QueryRow(ctx, `SELECT $1::timestamptz > clock_timestamp()`, expiry).Scan(&valid); err != nil {
		return err
	}
	if !valid {
		return errSessionRequired
	}
	return nil
}

func hasTeslaGrant(ctx context.Context, tx pgx.Tx, userID string) error {
	var present bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM jourvolt_tesla_tokens WHERE user_id=$1 AND access_ciphertext<>'' AND refresh_ciphertext<>'')`, userID).Scan(&present); err != nil {
		return err
	}
	if !present {
		return errTeslaReauthorization
	}
	return nil
}

func uniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}

func (s *store) bindWeChat(ctx context.Context, token, userID, provider string, identity wechatIdentity, expectedVersion int64) error {
	openHash, unionHash := wechatHashes(provider, identity)
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err = lockWeChatUser(ctx, tx, userID); err != nil {
		return err
	}
	var id int64
	var currentProvider, currentOpen, currentUnion string
	err = tx.QueryRow(ctx, `SELECT id,provider,openid_hash,COALESCE(unionid_hash,'') FROM jourvolt_wechat_bindings WHERE user_id=$1 FOR UPDATE`, userID).Scan(&id, &currentProvider, &currentOpen, &currentUnion)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	hasBinding := err == nil
	expiry, err := lockWeChatSession(ctx, tx, token, userID)
	if err != nil {
		return err
	}
	var version int64
	if err = tx.QueryRow(ctx, `SELECT COALESCE((SELECT generation FROM jourvolt_wechat_binding_versions WHERE user_id=$1),0)`, userID).Scan(&version); err != nil {
		return err
	}
	if version != expectedVersion {
		return errWeChatBindExpired
	}
	if err = hasTeslaGrant(ctx, tx, userID); err != nil {
		return err
	}
	if hasBinding {
		if currentProvider != provider || currentOpen != openHash || (currentUnion != "" && unionHash != "" && currentUnion != unionHash) {
			return errWeChatConflict
		}
		if currentUnion == "" && unionHash != "" {
			_, err = tx.Exec(ctx, `UPDATE jourvolt_wechat_bindings SET unionid_hash=$2 WHERE id=$1`, id, unionHash)
			if uniqueViolation(err) {
				return errWeChatConflict
			}
			if err != nil {
				return err
			}
		}
	} else {
		err = tx.QueryRow(ctx, `INSERT INTO jourvolt_wechat_bindings(user_id,provider,openid_hash,unionid_hash)
VALUES($1,$2,$3,NULLIF($4,'')) ON CONFLICT DO NOTHING RETURNING id`, userID, provider, openHash, unionHash).Scan(&id)
		if errors.Is(err, pgx.ErrNoRows) {
			return errWeChatConflict
		}
		if err != nil {
			return err
		}
	}
	if err = ensureWeChatSessionFresh(ctx, tx, expiry); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

type wechatBinding struct {
	ID        int64
	UserID    string
	Provider  string
	OpenHash  string
	UnionHash string
}

func (s *store) findWeChatBinding(ctx context.Context, provider string, identity wechatIdentity) (wechatBinding, error) {
	openHash, unionHash := wechatHashes(provider, identity)
	binding := wechatBinding{Provider: provider, OpenHash: openHash, UnionHash: unionHash}
	var savedUnion string
	err := s.pool.QueryRow(ctx, `SELECT id,user_id,COALESCE(unionid_hash,'') FROM jourvolt_wechat_bindings WHERE provider=$1 AND openid_hash=$2`, provider, openHash).Scan(&binding.ID, &binding.UserID, &savedUnion)
	if errors.Is(err, pgx.ErrNoRows) || err == nil && savedUnion != "" && unionHash != "" && savedUnion != unionHash {
		return wechatBinding{}, errWeChatNotBound
	}
	return binding, err
}

func (s *store) restoreWeChatSession(ctx context.Context, binding wechatBinding, started time.Time) (session, error) {
	access, err := randomToken()
	if err != nil {
		return session{}, err
	}
	refresh, err := randomToken()
	if err != nil {
		return session{}, err
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return session{}, err
	}
	defer tx.Rollback(ctx)
	if err = lockWeChatUser(ctx, tx, binding.UserID); err != nil {
		if errors.Is(err, errSessionRequired) {
			return session{}, errWeChatNotBound
		}
		return session{}, err
	}
	var notBefore time.Time
	var savedUnion string
	err = tx.QueryRow(ctx, `SELECT restore_not_before,COALESCE(unionid_hash,'') FROM jourvolt_wechat_bindings WHERE id=$1 AND user_id=$2 AND provider=$3 AND openid_hash=$4 FOR UPDATE`, binding.ID, binding.UserID, binding.Provider, binding.OpenHash).Scan(&notBefore, &savedUnion)
	if errors.Is(err, pgx.ErrNoRows) {
		return session{}, errWeChatNotBound
	}
	if err != nil {
		return session{}, err
	}
	if !started.After(notBefore) {
		return session{}, errSessionRequired
	}
	if savedUnion != "" && binding.UnionHash != "" && savedUnion != binding.UnionHash {
		return session{}, errWeChatNotBound
	}
	if savedUnion == "" && binding.UnionHash != "" {
		_, err = tx.Exec(ctx, `UPDATE jourvolt_wechat_bindings SET unionid_hash=$2 WHERE id=$1`, binding.ID, binding.UnionHash)
		if uniqueViolation(err) {
			return session{}, errWeChatConflict
		}
		if err != nil {
			return session{}, err
		}
	}
	if err = hasTeslaGrant(ctx, tx, binding.UserID); err != nil {
		return session{}, err
	}
	now := time.Now().UTC()
	_, err = tx.Exec(ctx, `INSERT INTO jourvolt_sessions(user_id,access_hash,refresh_hash,access_expires_at,refresh_expires_at,wechat_binding_id)
VALUES($1,$2,$3,$4,$5,$6)`, binding.UserID, hashToken(access), hashToken(refresh), now.Add(accessLifetime), now.Add(refreshLifetime), binding.ID)
	if err != nil {
		return session{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return session{}, err
	}
	return session{AccessToken: access, RefreshToken: refresh, ExpiresIn: int64(accessLifetime.Seconds()), UserID: binding.UserID}, nil
}

func (s *store) wechatBindingStatus(ctx context.Context, userID string) (bool, int64, error) {
	var bound bool
	var version int64
	err := s.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM jourvolt_wechat_bindings WHERE user_id=$1),
COALESCE((SELECT generation FROM jourvolt_wechat_binding_versions WHERE user_id=$1),0)`, userID).Scan(&bound, &version)
	return bound, version, err
}

func (s *store) unbindWeChat(ctx context.Context, token, userID string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err = lockWeChatUser(ctx, tx, userID); err != nil {
		return err
	}
	var id int64
	err = tx.QueryRow(ctx, `SELECT id FROM jourvolt_wechat_bindings WHERE user_id=$1 FOR UPDATE`, userID).Scan(&id)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	bound := err == nil
	expiry, err := lockWeChatSession(ctx, tx, token, userID)
	if err != nil {
		return err
	}
	// Record every explicit unbind even when there is no binding. A bind that
	// obtained an older generation may not recreate it after this commits.
	_, err = tx.Exec(ctx, `INSERT INTO jourvolt_wechat_binding_versions(user_id,generation) VALUES($1,1)
ON CONFLICT (user_id) DO UPDATE SET generation=jourvolt_wechat_binding_versions.generation+1`, userID)
	if err != nil {
		return err
	}
	if bound {
		// The foreign key deletes every WeChat-derived session, including its
		// rotated refresh token, without affecting Tesla-origin sessions.
		if _, err = tx.Exec(ctx, `DELETE FROM jourvolt_wechat_bindings WHERE id=$1`, id); err != nil {
			return err
		}
	}
	if err = ensureWeChatSessionFresh(ctx, tx, expiry); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// Preserve logout's single-session semantics while fencing restores that
// started before logout. Already established sessions on other devices remain.
func (s *store) revokeSessionWithWeChatFence(ctx context.Context, token string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var userID string
	var sessionID int64
	// A single snapshot covers a rotation that commits before or during this
	// lookup. An alias is accepted only here, never for authentication/refresh.
	err = tx.QueryRow(ctx, `SELECT id,user_id FROM jourvolt_sessions WHERE access_hash=$1
UNION ALL
SELECT s.id,s.user_id FROM jourvolt_logout_aliases a JOIN jourvolt_sessions s ON s.id=a.session_id
WHERE a.access_hash=$1 AND a.expires_at > clock_timestamp() LIMIT 1`, hashToken(token)).Scan(&sessionID, &userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	if err = lockWeChatUser(ctx, tx, userID); err != nil {
		if errors.Is(err, errSessionRequired) {
			return nil
		}
		return err
	}
	var bindingID int64
	err = tx.QueryRow(ctx, `SELECT id FROM jourvolt_wechat_bindings WHERE user_id=$1 FOR UPDATE`, userID).Scan(&bindingID)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	bound := err == nil
	// Rotation keeps the session ID but replaces the hash. Revoke the observed
	// session even if refresh changed its token while we waited for user locks.
	err = tx.QueryRow(ctx, `UPDATE jourvolt_sessions SET revoked_at=now() WHERE id=$1 AND revoked_at IS NULL RETURNING id`, sessionID).Scan(&sessionID)
	if errors.Is(err, pgx.ErrNoRows) {
		return tx.Commit(ctx)
	}
	if err != nil {
		return err
	}
	if bound {
		if _, err = tx.Exec(ctx, `UPDATE jourvolt_wechat_bindings SET restore_not_before=clock_timestamp() WHERE id=$1`, bindingID); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}
