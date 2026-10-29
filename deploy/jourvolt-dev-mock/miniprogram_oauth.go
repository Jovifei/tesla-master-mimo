package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/oauth2"
)

func ensureMiniProgramAuthSchema(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx, `CREATE TABLE IF NOT EXISTS jourvolt_miniprogram_auth (
state_hash TEXT PRIMARY KEY REFERENCES jourvolt_auth_transactions(state_hash) ON DELETE CASCADE,
transaction_hash TEXT UNIQUE NOT NULL,
proof_hash TEXT NOT NULL,
pkce_verifier TEXT NOT NULL,
status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','ready','cancelled','consumed','failed')),
user_id TEXT REFERENCES jourvolt_users(id) ON DELETE CASCADE,
expires_at TIMESTAMPTZ NOT NULL
)`)
	return err
}

func (o *teslaOAuth) startMiniProgram(ctx context.Context, consent oauthConsent) (authStart, error) {
	// The proof authorizes only polling/claiming this transaction. It is distinct
	// from the OAuth state, OIDC nonce and server-held PKCE verifier.
	proof, err := randomToken()
	if err != nil {
		return authStart{}, err
	}
	verifier := oauth2.GenerateVerifier()
	started, err := o.start(ctx, consent)
	if err != nil {
		return authStart{}, err
	}
	u, err := url.Parse(started.AuthorizationURL)
	if err != nil {
		return authStart{}, err
	}
	q := u.Query()
	_, err = o.store.pool.Exec(ctx, `INSERT INTO jourvolt_miniprogram_auth
(state_hash,transaction_hash,proof_hash,pkce_verifier,expires_at) VALUES ($1,$2,$3,$4,$5)`,
		hashToken(q.Get("state")), hashToken(started.TransactionID), hashToken(proof), verifier, started.ExpiresAt)
	if err != nil {
		return authStart{}, err
	}
	q.Set("code_challenge", oauth2.S256ChallengeFromVerifier(verifier))
	q.Set("code_challenge_method", "S256")
	u.RawQuery = q.Encode()
	started.AuthorizationURL = u.String()
	started.ClientProof = proof
	return started, nil
}

type miniProgramAuthRecord struct {
	Status    string    `json:"status"`
	ExpiresAt time.Time `json:"expires_at"`
	UserID    string    `json:"-"`
}

var errInvalidMiniProgramTransaction = errors.New("invalid_authorization_transaction")

func readMiniProgramRecord(ctx context.Context, tx pgx.Tx, transactionID, proof string) (miniProgramAuthRecord, error) {
	var record miniProgramAuthRecord
	err := tx.QueryRow(ctx, `SELECT status,expires_at,COALESCE(user_id,'') FROM jourvolt_miniprogram_auth
WHERE transaction_hash=$1 AND proof_hash=$2 FOR UPDATE`, hashToken(transactionID), hashToken(proof)).Scan(&record.Status, &record.ExpiresAt, &record.UserID)
	if errors.Is(err, pgx.ErrNoRows) {
		return record, errInvalidMiniProgramTransaction
	}
	if err != nil {
		return record, err
	}
	if (record.Status == "pending" || record.Status == "ready") && !time.Now().UTC().Before(record.ExpiresAt) {
		record.Status = "expired"
	}
	return record, nil
}

// This lock also serializes callback completion with cancellation. A cancelled
// transaction cannot update Tesla grants, become ready, or create a session.
func (s *store) completeMiniProgramAuthorization(ctx context.Context, transaction authTransaction, subject, access, refresh string, expiry time.Time) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var status string
	var expires time.Time
	err = tx.QueryRow(ctx, `SELECT status,expires_at FROM jourvolt_miniprogram_auth WHERE state_hash=$1 FOR UPDATE`, transaction.StateHash).Scan(&status, &expires)
	if err != nil {
		return err
	}
	if status != "pending" || !time.Now().UTC().Before(expires) {
		return errors.New("authorization_not_pending")
	}
	userID, err := saveTeslaGrant(ctx, tx, subject, access, refresh, transaction.Consent, expiry)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `UPDATE jourvolt_miniprogram_auth SET status='ready',user_id=$2,pkce_verifier='' WHERE state_hash=$1`, transaction.StateHash, userID)
	if err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *store) miniProgramAuthorization(ctx context.Context, transactionID, proof, action string) (miniProgramAuthRecord, session, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return miniProgramAuthRecord{}, session{}, err
	}
	defer tx.Rollback(ctx)
	record, err := readMiniProgramRecord(ctx, tx, transactionID, proof)
	if err != nil {
		return record, session{}, err
	}
	if action == "status" {
		return record, session{}, nil
	}
	if action == "cancel" {
		if record.Status == "consumed" {
			return record, session{}, errors.New("authorization_consumed")
		}
		if record.Status == "expired" {
			return record, session{}, errors.New("authorization_expired")
		}
		_, err = tx.Exec(ctx, `UPDATE jourvolt_miniprogram_auth SET status='cancelled',pkce_verifier='' WHERE transaction_hash=$1`, hashToken(transactionID))
		if err != nil {
			return record, session{}, err
		}
		record.Status = "cancelled"
		return record, session{}, tx.Commit(ctx)
	}
	if record.Status != "ready" {
		return record, session{}, fmt.Errorf("authorization_%s", record.Status)
	}
	access, err := randomToken()
	if err != nil {
		return record, session{}, err
	}
	refresh, err := randomToken()
	if err != nil {
		return record, session{}, err
	}
	now := time.Now().UTC()
	_, err = tx.Exec(ctx, `INSERT INTO jourvolt_sessions(user_id,access_hash,refresh_hash,access_expires_at,refresh_expires_at) VALUES ($1,$2,$3,$4,$5)`, record.UserID, hashToken(access), hashToken(refresh), now.Add(accessLifetime), now.Add(refreshLifetime))
	if err != nil {
		return record, session{}, err
	}
	_, err = tx.Exec(ctx, `UPDATE jourvolt_miniprogram_auth SET status='consumed',pkce_verifier='' WHERE transaction_hash=$1`, hashToken(transactionID))
	if err != nil {
		return record, session{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return record, session{}, err
	}
	record.Status = "consumed"
	return record, session{AccessToken: access, RefreshToken: refresh, ExpiresIn: int64(accessLifetime.Seconds()), UserID: record.UserID}, nil
}

func (a *app) miniProgramAuthRoute(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	action := strings.TrimPrefix(r.URL.Path, "/v1/auth/miniprogram/")
	if r.Method != http.MethodPost || (action != "status" && action != "claim" && action != "cancel") {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	if a.oauth == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "oauth_not_configured"})
		return
	}
	var request struct {
		TransactionID string `json:"transaction_id"`
		ClientProof   string `json:"client_proof"`
	}
	decoder := json.NewDecoder(io.LimitReader(r.Body, 8192))
	if err := decoder.Decode(&request); err != nil || strings.TrimSpace(request.TransactionID) == "" || strings.TrimSpace(request.ClientProof) == "" {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_authorization_request"})
		return
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_authorization_request"})
		return
	}
	record, granted, err := a.store.miniProgramAuthorization(r.Context(), request.TransactionID, request.ClientProof, action)
	if err != nil {
		code := err.Error()
		status := http.StatusConflict
		switch code {
		case "invalid_authorization_transaction":
			status = http.StatusUnauthorized
		case "authorization_expired":
			status = http.StatusGone
		case "authorization_pending", "authorization_cancelled", "authorization_consumed", "authorization_failed":
		default:
			status = http.StatusInternalServerError
			code = "authorization_service_failed"
		}
		a.json(w, status, map[string]string{"error": code})
		return
	}
	if action != "claim" {
		a.json(w, http.StatusOK, record)
		return
	}
	a.json(w, http.StatusOK, map[string]any{"access_token": granted.AccessToken, "refresh_token": granted.RefreshToken, "expires_in": granted.ExpiresIn, "user": map[string]string{"id": granted.UserID}})
	if a.telemetry != nil {
		a.telemetry.retryAfterAuthorization(granted.UserID)
	}
}

func miniProgramCallbackPage(w http.ResponseWriter, success bool) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	message := "Tesla 授权未完成。请返回 MateLink 小程序检查状态或重新开始。"
	if success {
		message = "Tesla 授权已完成。请返回 MateLink 小程序检查授权状态。"
	}
	_, _ = io.WriteString(w, "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>MateLink 授权</title><body><h1>MateLink</h1><p>"+message+"</p></body></html>")
}
