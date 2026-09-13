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
	var info wechatLinkInfo
	err = tx.QueryRow(ctx, `
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
	if err != nil {
		return err
	}
	return tx.Commit(ctx)
}
