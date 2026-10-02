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
)

const (
	defaultWeChatSessionURL = "https://api.weixin.qq.com/sns/jscode2session"
	wechatLinkLifetime      = 10 * time.Minute
)

var (
	errWeChatNotConfigured = errors.New("wechat_auth_not_configured")
	errWeChatCodeRejected  = errors.New("wechat_code_rejected")
)

type wechatConfig struct {
	AppID      string
	AppSecret  string
	SessionURL string
}

func loadWeChatConfig(getenv func(string) string) (*wechatConfig, error) {
	appID := strings.TrimSpace(getenv("WECHAT_APP_ID"))
	appSecret := strings.TrimSpace(getenv("WECHAT_APP_SECRET"))
	if appID == "" && appSecret == "" {
		return nil, nil
	}
	if appID == "" || appSecret == "" {
		return nil, fmt.Errorf("incomplete WeChat configuration; WECHAT_APP_ID and WECHAT_APP_SECRET are both required")
	}
	sessionURL := envOrDefault(getenv, "WECHAT_SESSION_URL", defaultWeChatSessionURL)
	if err := requireHTTPSURL("WECHAT_SESSION_URL", sessionURL); err != nil {
		return nil, err
	}
	return &wechatConfig{AppID: appID, AppSecret: appSecret, SessionURL: sessionURL}, nil
}

type wechatAuth struct {
	config *wechatConfig
	client *http.Client
}

func newWeChatAuth(config *wechatConfig, client *http.Client) *wechatAuth {
	if config == nil {
		return nil
	}
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second}
	}
	return &wechatAuth{config: config, client: client}
}

type wechatCodeSession struct {
	OpenID     string `json:"openid"`
	SessionKey string `json:"session_key"`
	UnionID    string `json:"unionid"`
	ErrCode    int    `json:"errcode"`
	ErrMsg     string `json:"errmsg"`
}

func (w *wechatAuth) exchangeCode(ctx context.Context, code string) (wechatCodeSession, error) {
	if w == nil || w.config == nil {
		return wechatCodeSession{}, errWeChatNotConfigured
	}
	code = strings.TrimSpace(code)
	if code == "" || len(code) > 512 {
		return wechatCodeSession{}, errWeChatCodeRejected
	}
	endpoint, err := url.Parse(w.config.SessionURL)
	if err != nil {
		return wechatCodeSession{}, errWeChatNotConfigured
	}
	query := endpoint.Query()
	query.Set("appid", w.config.AppID)
	query.Set("secret", w.config.AppSecret)
	query.Set("js_code", code)
	query.Set("grant_type", "authorization_code")
	endpoint.RawQuery = query.Encode()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint.String(), nil)
	if err != nil {
		return wechatCodeSession{}, err
	}
	response, err := w.client.Do(request)
	if err != nil {
		return wechatCodeSession{}, fmt.Errorf("wechat session exchange: %w", err)
	}
	defer response.Body.Close()
	var result wechatCodeSession
	if err := json.NewDecoder(io.LimitReader(response.Body, 32*1024)).Decode(&result); err != nil {
		return wechatCodeSession{}, fmt.Errorf("wechat session response: %w", err)
	}
	// The session_key is intentionally only held in this local response and is
	// never returned, logged, or persisted by the application.
	if response.StatusCode < 200 || response.StatusCode >= 300 || result.ErrCode != 0 || strings.TrimSpace(result.OpenID) == "" {
		return wechatCodeSession{}, errWeChatCodeRejected
	}
	return result, nil
}

type wechatLinkInfo struct {
	TokenHash  string
	AppID      string
	OpenIDHash string
}

type wechatAuthorizationRecord struct {
	TransactionHash  string
	Channel          string
	ClientProofHash  string
	ExpectedUserID   string
	Status           string
	ExpiresAt        time.Time
	CallbackRefHash  string
	TicketCiphertext string
	TicketExpiresAt  time.Time
	CompletedUserID  string
	FailureCode      string
}

type wechatAuthorizationRequest struct {
	TransactionID string `json:"transaction_id"`
	ClientProof   string `json:"client_proof"`
	CallbackRef   string `json:"callback_ref"`
}

type wechatAuthorizationStatusResponse struct {
	Status    string    `json:"status"`
	ExpiresAt time.Time `json:"expires_at,omitempty"`
}

type wechatSessionRequest struct {
	Code           string `json:"code"`
	TermsVersion   string `json:"terms_version"`
	PrivacyVersion string `json:"privacy_version"`
}

type wechatSessionResponse struct {
	Status       string `json:"status"`
	AccessToken  string `json:"access_token,omitempty"`
	RefreshToken string `json:"refresh_token,omitempty"`
	ExpiresIn    int64  `json:"expires_in,omitempty"`
	User         *struct {
		ID string `json:"id"`
	} `json:"user,omitempty"`
	LinkToken string    `json:"link_token,omitempty"`
	ExpiresAt time.Time `json:"expires_at,omitempty"`
}

func (a *app) wechatSession(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	if a.wechat == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "wechat_auth_not_configured"})
		return
	}
	if a.store == nil || a.store.pool == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "store_unavailable"})
		return
	}
	var request wechatSessionRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 8*1024)).Decode(&request); err != nil || strings.TrimSpace(request.Code) == "" {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_wechat_code"})
		return
	}
	if _, err := currentOAuthConsent(request.TermsVersion, request.PrivacyVersion); err != nil {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "consent_required"})
		return
	}
	identity, err := a.wechat.exchangeCode(r.Context(), request.Code)
	if err != nil {
		if errors.Is(err, errWeChatCodeRejected) {
			a.json(w, http.StatusUnauthorized, map[string]string{"error": "wechat_code_rejected"})
			return
		}
		a.json(w, http.StatusBadGateway, map[string]string{"error": "wechat_unavailable"})
		return
	}
	openidHash := hashToken("wechat:" + a.wechat.config.AppID + ":" + identity.OpenID)
	userID, found, err := a.store.wechatIdentityUser(r.Context(), a.wechat.config.AppID, openidHash)
	if err != nil {
		a.json(w, http.StatusInternalServerError, map[string]string{"error": "wechat_identity_lookup_failed"})
		return
	}
	if found && userID != "" {
		session, err := a.store.createSession(r.Context(), userID)
		if err != nil {
			a.json(w, http.StatusInternalServerError, map[string]string{"error": "session_create_failed"})
			return
		}
		a.json(w, http.StatusOK, wechatSessionResponse{
			Status: "authenticated", AccessToken: session.AccessToken, RefreshToken: session.RefreshToken,
			ExpiresIn: session.ExpiresIn, User: &struct {
				ID string `json:"id"`
			}{ID: userID},
		})
		return
	}
	linkToken, expiresAt, err := a.store.createWeChatLinkChallenge(r.Context(), a.wechat.config.AppID, openidHash)
	if err != nil {
		a.json(w, http.StatusInternalServerError, map[string]string{"error": "wechat_link_challenge_failed"})
		return
	}
	a.json(w, http.StatusOK, wechatSessionResponse{Status: "link_required", LinkToken: linkToken, ExpiresAt: expiresAt})
}

func (a *app) wechatAuthorizationStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || a.store == nil || a.store.pool == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "store_unavailable"})
		return
	}
	request, err := decodeWechatAuthorizationRequest(r)
	if err != nil {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_wechat_authorization_request"})
		return
	}
	record, err := a.store.wechatAuthorizationRecord(r.Context(), request.TransactionID, request.ClientProof, false)
	if err != nil {
		writeWechatAuthorizationError(w, err)
		return
	}
	if !a.requireWechatAuthorizationOwner(w, r, record.ExpectedUserID) {
		return
	}
	expiresAt := record.ExpiresAt
	if record.Status == "ready" && !record.TicketExpiresAt.IsZero() && record.TicketExpiresAt.Before(expiresAt) {
		expiresAt = record.TicketExpiresAt
	}
	a.json(w, http.StatusOK, wechatAuthorizationStatusResponse{Status: record.Status, ExpiresAt: expiresAt})
}

func (a *app) wechatAuthorizationClaim(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || a.store == nil || a.store.pool == nil || a.oauth == nil || a.oauth.cipher == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "store_unavailable"})
		return
	}
	request, err := decodeWechatAuthorizationRequest(r)
	if err != nil {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_wechat_authorization_request"})
		return
	}
	record, err := a.store.wechatAuthorizationRecord(r.Context(), request.TransactionID, request.ClientProof, false)
	if err != nil {
		writeWechatAuthorizationError(w, err)
		return
	}
	if !a.requireWechatAuthorizationOwner(w, r, record.ExpectedUserID) {
		return
	}
	claimed, err := a.store.claimWeChatAuthorization(r.Context(), request.TransactionID, request.ClientProof, request.CallbackRef, a.oauth.cipher)
	if err != nil {
		writeWechatAuthorizationError(w, err)
		return
	}
	a.json(w, http.StatusOK, map[string]any{
		"access_token": claimed.AccessToken, "refresh_token": claimed.RefreshToken,
		"expires_in": claimed.ExpiresIn, "user": map[string]string{"id": claimed.UserID},
	})
	if a.telemetry != nil {
		a.telemetry.retryAfterAuthorization(claimed.UserID)
	}
}

func (a *app) wechatAuthorizationCancel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || a.store == nil || a.store.pool == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "store_unavailable"})
		return
	}
	request, err := decodeWechatAuthorizationRequest(r)
	if err != nil {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_wechat_authorization_request"})
		return
	}
	record, err := a.store.wechatAuthorizationRecord(r.Context(), request.TransactionID, request.ClientProof, false)
	if err != nil {
		writeWechatAuthorizationError(w, err)
		return
	}
	if !a.requireWechatAuthorizationOwner(w, r, record.ExpectedUserID) {
		return
	}
	if err := a.store.cancelWeChatAuthorization(r.Context(), request.TransactionID, request.ClientProof); err != nil {
		writeWechatAuthorizationError(w, err)
		return
	}
	a.json(w, http.StatusOK, map[string]string{"status": "cancelled"})
}

func decodeWechatAuthorizationRequest(r *http.Request) (wechatAuthorizationRequest, error) {
	var request wechatAuthorizationRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 16*1024)).Decode(&request); err != nil {
		return request, err
	}
	request.TransactionID = strings.TrimSpace(request.TransactionID)
	request.ClientProof = strings.TrimSpace(request.ClientProof)
	request.CallbackRef = strings.TrimSpace(request.CallbackRef)
	if request.TransactionID == "" || request.ClientProof == "" || len(request.TransactionID) > 256 || len(request.ClientProof) > 256 || len(request.CallbackRef) > 256 {
		return request, errors.New("invalid_wechat_authorization_request")
	}
	return request, nil
}

func (a *app) requireWechatAuthorizationOwner(w http.ResponseWriter, r *http.Request, expectedUserID string) bool {
	if expectedUserID == "" {
		return true
	}
	userID, ok := a.auth(w, r)
	if !ok {
		return false
	}
	if userID != expectedUserID {
		a.json(w, http.StatusForbidden, map[string]string{"error": "wechat_authorization_conflict"})
		return false
	}
	return true
}

func writeWechatAuthorizationError(w http.ResponseWriter, err error) {
	code := "wechat_authorization_unavailable"
	status := http.StatusServiceUnavailable
	switch err.Error() {
	case "wechat_authorization_invalid":
		code = "wechat_authorization_invalid"
		status = http.StatusUnauthorized
	case "wechat_authorization_pending":
		code = "wechat_authorization_pending"
		status = http.StatusConflict
	case "wechat_authorization_failed", "wechat_authorization_cancelled", "wechat_authorization_claimed":
		code = err.Error()
		status = http.StatusConflict
	case "wechat_authorization_expired":
		code = "wechat_authorization_expired"
		status = http.StatusGone
	case "wechat_identity_conflict", "wechat_authorization_conflict":
		code = "wechat_authorization_conflict"
		status = http.StatusConflict
	case "store_unavailable":
		code = "store_unavailable"
		status = http.StatusServiceUnavailable
	case "wechat_transaction_corrupt", "invalid_login_ticket":
		code = "wechat_transaction_corrupt"
		status = http.StatusInternalServerError
	}
	(&app{}).json(w, status, map[string]string{"error": code})
}

func (s *store) wechatAuthorizationRecord(ctx context.Context, transactionID, clientProof string, forUpdate bool) (wechatAuthorizationRecord, error) {
	if s == nil || s.pool == nil {
		return wechatAuthorizationRecord{}, errors.New("store_unavailable")
	}
	return queryWechatAuthorizationRecord(ctx, s.pool, transactionID, clientProof, forUpdate)
}

type queryer interface {
	QueryRow(context.Context, string, ...any) pgx.Row
}

func queryWechatAuthorizationRecord(ctx context.Context, q queryer, transactionID, clientProof string, forUpdate bool) (wechatAuthorizationRecord, error) {
	transactionID = strings.TrimSpace(transactionID)
	clientProof = strings.TrimSpace(clientProof)
	if transactionID == "" || clientProof == "" || len(transactionID) > 256 || len(clientProof) > 256 {
		return wechatAuthorizationRecord{}, errors.New("wechat_authorization_invalid")
	}
	query := `
SELECT transaction_hash, COALESCE(channel, 'native'), COALESCE(client_proof_hash, ''),
COALESCE(expected_user_id, ''), COALESCE(wechat_status, 'pending'), expires_at,
COALESCE(wechat_callback_ref_hash, ''), COALESCE(wechat_ticket_ciphertext, ''),
COALESCE(wechat_ticket_expires_at, 'epoch')::timestamptz,
COALESCE(wechat_completed_user_id, ''), COALESCE(wechat_failure_code, '')
FROM jourvolt_auth_transactions
WHERE transaction_hash=$1`
	if forUpdate {
		query += " FOR UPDATE"
	}
	var record wechatAuthorizationRecord
	err := q.QueryRow(ctx, query, hashToken(transactionID)).Scan(
		&record.TransactionHash, &record.Channel, &record.ClientProofHash,
		&record.ExpectedUserID, &record.Status, &record.ExpiresAt,
		&record.CallbackRefHash, &record.TicketCiphertext, &record.TicketExpiresAt,
		&record.CompletedUserID, &record.FailureCode,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return wechatAuthorizationRecord{}, errors.New("wechat_authorization_invalid")
		}
		return wechatAuthorizationRecord{}, err
	}
	if record.Channel != "wechat" || record.ClientProofHash != hashToken(clientProof) {
		return wechatAuthorizationRecord{}, errors.New("wechat_authorization_invalid")
	}
	if record.Status != "claimed" && time.Now().UTC().After(record.ExpiresAt) {
		return record, errors.New("wechat_authorization_expired")
	}
	if record.Status == "ready" && !record.TicketExpiresAt.IsZero() && time.Now().UTC().After(record.TicketExpiresAt) {
		return record, errors.New("wechat_authorization_expired")
	}
	return record, nil
}

func (s *store) claimWeChatAuthorization(ctx context.Context, transactionID, clientProof, callbackRef string, cipher *tokenCipher) (session, error) {
	if s == nil || s.pool == nil || cipher == nil {
		return session{}, errors.New("store_unavailable")
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return session{}, err
	}
	defer tx.Rollback(ctx)
	record, err := queryWechatAuthorizationRecord(ctx, tx, transactionID, clientProof, true)
	if err != nil {
		return session{}, err
	}
	if record.Status == "pending" {
		return session{}, errors.New("wechat_authorization_pending")
	}
	if record.Status == "failed" {
		return session{}, errors.New("wechat_authorization_failed")
	}
	if record.Status == "cancelled" {
		return session{}, errors.New("wechat_authorization_cancelled")
	}
	if record.Status == "claimed" {
		return session{}, errors.New("wechat_authorization_claimed")
	}
	if record.Status != "ready" {
		return session{}, errors.New("wechat_authorization_expired")
	}
	if callbackRef != "" && hashToken(strings.TrimSpace(callbackRef)) != record.CallbackRefHash {
		return session{}, errors.New("wechat_authorization_invalid")
	}
	ticket, err := cipher.decrypt(record.TicketCiphertext)
	if err != nil || ticket == "" {
		return session{}, errors.New("wechat_transaction_corrupt")
	}
	claimed, err := s.exchangeLoginTicketTx(ctx, tx, ticket)
	if err != nil {
		return session{}, err
	}
	commandTag, err := tx.Exec(ctx, `
UPDATE jourvolt_auth_transactions SET wechat_status='claimed', wechat_claimed_at=now()
WHERE transaction_hash=$1 AND wechat_status='ready'`, record.TransactionHash)
	if err != nil {
		return session{}, err
	}
	if commandTag.RowsAffected() != 1 {
		return session{}, errors.New("wechat_authorization_invalid")
	}
	if err := tx.Commit(ctx); err != nil {
		return session{}, err
	}
	return claimed, nil
}

func (s *store) cancelWeChatAuthorization(ctx context.Context, transactionID, clientProof string) error {
	if s == nil || s.pool == nil {
		return errors.New("store_unavailable")
	}
	commandTag, err := s.pool.Exec(ctx, `
UPDATE jourvolt_auth_transactions
SET wechat_status='cancelled'
WHERE transaction_hash=$1 AND channel='wechat' AND client_proof_hash=$2
  AND wechat_status IN ('pending', 'ready') AND expires_at > now()`, hashToken(strings.TrimSpace(transactionID)), hashToken(strings.TrimSpace(clientProof)))
	if err != nil {
		return err
	}
	if commandTag.RowsAffected() != 1 {
		return errors.New("wechat_authorization_invalid")
	}
	return nil
}

func (s *store) markWeChatAuthorizationFailed(ctx context.Context, state, failureCode string) error {
	if s == nil || s.pool == nil {
		return errors.New("store_unavailable")
	}
	_, err := s.pool.Exec(ctx, `
UPDATE jourvolt_auth_transactions
SET wechat_status='failed', wechat_failure_code=$2
WHERE state_hash=$1 AND channel='wechat' AND wechat_status='pending'`, hashToken(strings.TrimSpace(state)), sanitizeTeslaErrorCode(failureCode))
	return err
}

func (s *store) wechatIdentityUser(ctx context.Context, appID, openidHash string) (string, bool, error) {
	if s == nil || s.pool == nil {
		return "", false, errors.New("store_unavailable")
	}
	var userID string
	err := s.pool.QueryRow(ctx, `
SELECT user_id FROM jourvolt_wechat_identities
WHERE app_id=$1 AND openid_hash=$2`, appID, openidHash).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return strings.TrimSpace(userID), true, nil
}

func (s *store) createWeChatLinkChallenge(ctx context.Context, appID, openidHash string) (string, time.Time, error) {
	if s == nil || s.pool == nil {
		return "", time.Time{}, errors.New("store_unavailable")
	}
	token, err := randomToken()
	if err != nil {
		return "", time.Time{}, err
	}
	expiresAt := time.Now().UTC().Add(wechatLinkLifetime)
	_, err = s.pool.Exec(ctx, `
INSERT INTO jourvolt_wechat_link_challenges(token_hash, app_id, openid_hash, expires_at)
VALUES ($1, $2, $3, $4)`, hashToken(token), appID, openidHash, expiresAt)
	if err != nil {
		return "", time.Time{}, err
	}
	return token, expiresAt, nil
}

func (s *store) wechatLinkForToken(ctx context.Context, token string) (wechatLinkInfo, error) {
	if s == nil || s.pool == nil {
		return wechatLinkInfo{}, errors.New("store_unavailable")
	}
	token = strings.TrimSpace(token)
	if token == "" || len(token) > 256 {
		return wechatLinkInfo{}, errors.New("wechat_link_invalid")
	}
	var info wechatLinkInfo
	info.TokenHash = hashToken(token)
	err := s.pool.QueryRow(ctx, `
SELECT app_id, openid_hash FROM jourvolt_wechat_link_challenges
WHERE token_hash=$1 AND consumed_at IS NULL AND expires_at > now()`, info.TokenHash).Scan(&info.AppID, &info.OpenIDHash)
	if errors.Is(err, pgx.ErrNoRows) {
		return wechatLinkInfo{}, errors.New("wechat_link_expired")
	}
	if err != nil {
		return wechatLinkInfo{}, err
	}
	return info, nil
}

func (s *store) bindWeChatIdentityByHash(ctx context.Context, tokenHash, userID string) error {
	if s == nil || s.pool == nil {
		return errors.New("store_unavailable")
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := bindWeChatIdentityTx(ctx, tx, tokenHash, userID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func bindWeChatIdentityTx(ctx context.Context, tx pgx.Tx, tokenHash, userID string) error {
	var info wechatLinkInfo
	err := tx.QueryRow(ctx, `
UPDATE jourvolt_wechat_link_challenges
SET consumed_at=now()
WHERE token_hash=$1 AND consumed_at IS NULL AND expires_at > now()
RETURNING app_id, openid_hash`, tokenHash).Scan(&info.AppID, &info.OpenIDHash)
	if errors.Is(err, pgx.ErrNoRows) {
		return errors.New("wechat_link_expired")
	}
	if err != nil {
		return err
	}
	var existingUser string
	err = tx.QueryRow(ctx, `
SELECT COALESCE(user_id, '') FROM jourvolt_wechat_identities
WHERE app_id=$1 AND openid_hash=$2`, info.AppID, info.OpenIDHash).Scan(&existingUser)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		_, err = tx.Exec(ctx, `
INSERT INTO jourvolt_wechat_identities(app_id, openid_hash, user_id, updated_at)
VALUES ($1, $2, $3, now())`, info.AppID, info.OpenIDHash, userID)
	case err != nil:
		return err
	case strings.TrimSpace(existingUser) != "" && existingUser != userID:
		return errors.New("wechat_identity_conflict")
	default:
		_, err = tx.Exec(ctx, `
UPDATE jourvolt_wechat_identities SET user_id=$3, updated_at=now()
WHERE app_id=$1 AND openid_hash=$2`, info.AppID, info.OpenIDHash, userID)
	}
	return err
}
