package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
	"golang.org/x/oauth2"
)

var errOAuthCallbackRejected = errors.New("oauth_callback_rejected")

type teslaOAuth struct {
	config    *teslaConfig
	store     *store
	cipher    *tokenCipher
	oauth2    oauth2.Config
	verifiers map[string]*oidc.IDTokenVerifier
	client    *http.Client
}

type authStart struct {
	AuthorizationURL    string    `json:"authorization_url"`
	WebAuthorizationURL string    `json:"web_authorization_url,omitempty"`
	TransactionID       string    `json:"transaction_id"`
	ClientProof         string    `json:"client_proof,omitempty"`
	Channel             string    `json:"channel,omitempty"`
	ExpiresAt           time.Time `json:"expires_at"`
}

func newTeslaOAuth(config *teslaConfig, store *store, cipher *tokenCipher, client *http.Client) *teslaOAuth {
	makeVerifier := func(issuer, jwks string) *oidc.IDTokenVerifier {
		keySet := oidc.NewRemoteKeySet(context.Background(), jwks)
		return oidc.NewVerifier(issuer, keySet, &oidc.Config{ClientID: config.ClientID})
	}
	verifiers := map[string]*oidc.IDTokenVerifier{
		config.Issuer:      makeVerifier(config.Issuer, config.JWKSURL),
		defaultTeslaIssuer: makeVerifier(defaultTeslaIssuer, defaultTeslaJWKSURL),
		teslaNTSIssuer:     makeVerifier(teslaNTSIssuer, teslaNTSJWKSURL),
	}
	return &teslaOAuth{
		config: config,
		store:  store,
		cipher: cipher,
		oauth2: oauth2.Config{
			ClientID:     config.ClientID,
			ClientSecret: config.ClientSecret,
			RedirectURL:  config.RedirectURI,
			Scopes:       []string{"openid", "offline_access", "vehicle_device_data", "vehicle_location"},
			Endpoint: oauth2.Endpoint{
				AuthURL:   config.Authorization,
				TokenURL:  config.TokenEndpoint,
				AuthStyle: oauth2.AuthStyleInParams,
			},
		},
		verifiers: verifiers,
		client:    client,
	}
}

func normalizeTeslaIssuer(raw string) string {
	return strings.TrimRight(strings.TrimSpace(raw), "/")
}

func peekJWTIssuer(rawIDToken string) string {
	parts := strings.Split(rawIDToken, ".")
	if len(parts) < 2 {
		return ""
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return ""
	}
	var claims struct {
		Issuer string `json:"iss"`
	}
	if json.Unmarshal(payload, &claims) != nil {
		return ""
	}
	return normalizeTeslaIssuer(claims.Issuer)
}

func (o *teslaOAuth) allowedVerifier(raw string) *oidc.IDTokenVerifier {
	if o == nil {
		return nil
	}
	return o.verifiers[normalizeTeslaIssuer(raw)]
}

func (o *teslaOAuth) verifierForIssuer(raw string) *oidc.IDTokenVerifier {
	if v := o.allowedVerifier(raw); v != nil {
		return v
	}
	if o == nil || o.config == nil {
		return nil
	}
	if v := o.allowedVerifier(o.config.Issuer); v != nil {
		return v
	}
	for _, v := range o.verifiers {
		return v
	}
	return nil
}

func (o *teslaOAuth) idTokenIssuerCandidates(rawIDToken, callbackIssuer string) []string {
	seen := make(map[string]struct{}, 2)
	out := make([]string, 0, 2)
	add := func(raw string) {
		issuer := normalizeTeslaIssuer(raw)
		if issuer == "" {
			return
		}
		if _, exists := seen[issuer]; exists {
			return
		}
		seen[issuer] = struct{}{}
		out = append(out, issuer)
	}
	add(peekJWTIssuer(rawIDToken))
	add(callbackIssuer)
	return out
}

func (o *teslaOAuth) verifyIDToken(ctx context.Context, rawIDToken, callbackIssuer string) (*oidc.IDToken, error) {
	var lastErr error
	tried := 0
	for _, issuer := range o.idTokenIssuerCandidates(rawIDToken, callbackIssuer) {
		verifier := o.allowedVerifier(issuer)
		if verifier == nil {
			continue
		}
		tried++
		idToken, err := verifier.Verify(ctx, rawIDToken)
		if err == nil {
			return idToken, nil
		}
		lastErr = err
	}
	if lastErr != nil {
		return nil, lastErr
	}
	if tried == 0 {
		return nil, errors.New("no allowed tesla issuer")
	}
	return nil, errors.New("id token verify failed")
}

func (o *teslaOAuth) start(ctx context.Context, consent oauthConsent) (authStart, error) {
	return o.startForClient(ctx, consent, "", "native", "")
}

func (o *teslaOAuth) startForWeChat(ctx context.Context, consent oauthConsent, linkToken string) (authStart, error) {
	return o.startForClient(ctx, consent, linkToken, "wechat", "")
}

func (o *teslaOAuth) startForClient(ctx context.Context, consent oauthConsent, linkToken, channel, expectedUserID string) (authStart, error) {
	if o == nil || o.store == nil || o.store.pool == nil {
		return authStart{}, errors.New("store_unavailable")
	}
	if _, err := currentOAuthConsent(consent.TermsVersion, consent.PrivacyVersion); err != nil {
		return authStart{}, err
	}
	channel = strings.TrimSpace(strings.ToLower(channel))
	if channel != "native" && channel != "wechat" {
		return authStart{}, errors.New("invalid_auth_channel")
	}
	wechat := wechatLinkInfo{}
	if strings.TrimSpace(linkToken) != "" {
		if o.store == nil {
			return authStart{}, errors.New("wechat_link_store_unavailable")
		}
		var err error
		wechat, err = o.store.wechatLinkForToken(ctx, linkToken)
		if err != nil {
			return authStart{}, err
		}
	}
	state, err := randomToken()
	if err != nil {
		return authStart{}, err
	}
	transactionID, err := randomToken()
	if err != nil {
		return authStart{}, err
	}
	nonce, err := randomToken()
	if err != nil {
		return authStart{}, err
	}
	expiresAt := time.Now().UTC().Add(authTransactionLifetime)
	clientProof := ""
	clientProofHash := ""
	if channel == "wechat" {
		clientProof, err = randomToken()
		if err != nil {
			return authStart{}, err
		}
		clientProofHash = hashToken(clientProof)
	}
	if err := o.store.createAuthTransactionWithClient(ctx, state, transactionID, nonce, consent, expiresAt, wechat, channel, clientProofHash, expectedUserID); err != nil {
		return authStart{}, err
	}
	authorizationURL := o.authorizationURL(state, nonce)
	webAuthorizationURL := ""
	if channel == "wechat" {
		webAuthorizationURL = o.wechatAuthorizationURL(state)
	}
	return authStart{
		AuthorizationURL: authorizationURL, WebAuthorizationURL: webAuthorizationURL,
		TransactionID: transactionID, ClientProof: clientProof, Channel: channel, ExpiresAt: expiresAt,
	}, nil
}

func (o *teslaOAuth) authorizationURL(state, nonce string) string {
	return o.oauth2.AuthCodeURL(
		state,
		oauth2.AccessTypeOffline,
		oauth2.SetAuthURLParam("nonce", nonce),
		// Tesla owns the credential and consent UI. Existing SSO sessions may be
		// reused; only missing scopes should force another consent step.
		oauth2.SetAuthURLParam("prompt_missing_scopes", "true"),
		oauth2.SetAuthURLParam("require_requested_scopes", "true"),
		// Vehicles that require a virtual key can complete that official Tesla
		// confirmation as part of onboarding instead of making users discover a
		// separate infrastructure/setup screen later.
		oauth2.SetAuthURLParam("show_keypair_step", "true"),
	)
}

func (o *teslaOAuth) wechatAuthorizationURL(state string) string {
	parsed, err := url.Parse(o.appLink("", ""))
	if err != nil || parsed.Host == "" {
		return ""
	}
	parsed.Path = "/oauth/wechat/authorize"
	parsed.RawQuery = url.Values{"state": {state}}.Encode()
	return parsed.String()
}

type oauthCallbackResult struct {
	Ticket      string
	CallbackRef string
	WeChat      bool
}

func (o *teslaOAuth) callback(ctx context.Context, values url.Values) (string, error) {
	result, err := o.callbackResult(ctx, values)
	return result.Ticket, err
}

func (o *teslaOAuth) callbackResult(ctx context.Context, values url.Values) (oauthCallbackResult, error) {
	if o == nil || o.store == nil || o.store.pool == nil || o.cipher == nil {
		return oauthCallbackResult{}, errOAuthCallbackRejected
	}
	state := strings.TrimSpace(values.Get("state"))
	if state == "" {
		return oauthCallbackResult{}, errOAuthCallbackRejected
	}
	transaction, err := o.store.consumeAuthState(ctx, state)
	if err != nil {
		return oauthCallbackResult{}, errOAuthCallbackRejected
	}
	if values.Get("error") != "" {
		return oauthCallbackResult{}, errOAuthCallbackRejected
	}
	code := strings.TrimSpace(values.Get("code"))
	if code == "" {
		return oauthCallbackResult{}, errOAuthCallbackRejected
	}

	exchangeContext := context.WithValue(ctx, oauth2.HTTPClient, o.client)
	token, err := o.oauth2.Exchange(
		exchangeContext,
		code,
		oauth2.SetAuthURLParam("audience", o.config.FleetAPIBase),
	)
	if err != nil {
		log.Printf("tesla oauth token exchange failed: %s", teslaCallbackLogError(err))
		return oauthCallbackResult{}, err
	}
	if token.AccessToken == "" || token.RefreshToken == "" || token.Expiry.IsZero() {
		log.Printf("tesla oauth token incomplete: has_refresh=%t expiry_zero=%t", token.RefreshToken != "", token.Expiry.IsZero())
		return oauthCallbackResult{}, errOAuthCallbackRejected
	}
	rawIDToken, ok := token.Extra("id_token").(string)
	if !ok || rawIDToken == "" {
		log.Printf("tesla oauth token missing id_token")
		return oauthCallbackResult{}, errOAuthCallbackRejected
	}
	callbackIssuer := strings.TrimSpace(values.Get("issuer"))
	idToken, err := o.verifyIDToken(exchangeContext, rawIDToken, callbackIssuer)
	if err != nil {
		logTeslaIDTokenVerificationFailure(callbackIssuer, rawIDToken, err)
		return oauthCallbackResult{}, fmt.Errorf("tesla id_token: %w", err)
	}
	var claims struct {
		Subject string `json:"sub"`
		Nonce   string `json:"nonce"`
	}
	if err := idToken.Claims(&claims); err != nil || claims.Subject == "" || claims.Nonce != transaction.Nonce {
		return oauthCallbackResult{}, errOAuthCallbackRejected
	}
	accessCiphertext, err := o.cipher.encrypt(token.AccessToken)
	if err != nil {
		return oauthCallbackResult{}, err
	}
	refreshCiphertext, err := o.cipher.encrypt(token.RefreshToken)
	if err != nil {
		return oauthCallbackResult{}, err
	}
	if transaction.Channel == "wechat" {
		ticket, err := randomToken()
		if err != nil {
			return oauthCallbackResult{}, err
		}
		ticketCiphertext, err := o.cipher.encrypt(ticket)
		if err != nil {
			return oauthCallbackResult{}, err
		}
		callbackRef, err := randomToken()
		if err != nil {
			return oauthCallbackResult{}, err
		}
		storedRef, err := o.store.saveTeslaGrantAndWeChatArtifact(
			ctx, transaction, claims.Subject, accessCiphertext, refreshCiphertext,
			transaction.Consent, token.Expiry.UTC(), ticket, ticketCiphertext, callbackRef,
		)
		if err != nil {
			return oauthCallbackResult{}, err
		}
		return oauthCallbackResult{CallbackRef: storedRef, WeChat: true}, nil
	}
	_, ticket, err := o.store.saveTeslaGrantAndLoginTicket(
		ctx,
		claims.Subject,
		accessCiphertext,
		refreshCiphertext,
		transaction.Consent,
		token.Expiry.UTC(),
	)
	return oauthCallbackResult{Ticket: ticket}, err
}

func logTeslaIDTokenVerificationFailure(callbackIssuer, rawIDToken string, verifierErr error) {
	_ = rawIDToken
	_ = verifierErr
	issuerCategory := "unrecognized"
	if normalizeTeslaIssuer(callbackIssuer) == defaultTeslaIssuer || normalizeTeslaIssuer(callbackIssuer) == teslaNTSIssuer {
		issuerCategory = "known"
	}
	log.Printf("tesla oauth id_token verify failed class=id_token_invalid issuer=%s", issuerCategory)
}

func (o *teslaOAuth) appLink(ticket, errorCode string) string {
	parsed, err := url.Parse(o.config.AppLinkURI)
	if err != nil {
		return o.config.AppLinkURI
	}
	query := parsed.Query()
	query.Del("ticket")
	query.Del("error")
	if ticket != "" {
		query.Set("ticket", ticket)
	}
	if errorCode != "" {
		query.Set("error", errorCode)
	}
	parsed.RawQuery = query.Encode()
	return parsed.String()
}

func (o *teslaOAuth) wechatAppLink(callbackRef, errorCode string) string {
	parsed, err := url.Parse(o.appLink("", errorCode))
	if err != nil {
		return o.appLink("", errorCode)
	}
	query := parsed.Query()
	query.Del("ticket")
	if callbackRef != "" {
		query.Set("callback_ref", callbackRef)
	}
	parsed.RawQuery = query.Encode()
	return parsed.String()
}

func addAppLinkChannel(raw, channel string) string {
	parsed, err := url.Parse(raw)
	if err != nil || strings.TrimSpace(channel) == "" {
		return raw
	}
	query := parsed.Query()
	query.Set("channel", channel)
	parsed.RawQuery = query.Encode()
	return parsed.String()
}

// consentRevokeURL returns Tesla's user-facing consent management page. Tesla
// documents revocation through this page rather than a server-side revoke API.
// The URL is generated from the configured regional authorization origin so a
// China Pilot does not get redirected to a different Tesla region.
func (o *teslaOAuth) consentRevokeURL() string {
	if o == nil || o.config == nil || strings.TrimSpace(o.config.ClientID) == "" {
		return ""
	}
	authority, err := url.Parse(o.config.Authorization)
	if err != nil || authority.Scheme != "https" || authority.Host == "" {
		return ""
	}
	backURL, err := url.Parse(o.config.AppLinkURI)
	if err != nil || backURL.Scheme != "https" || backURL.Host == "" {
		return ""
	}
	backURL.Path = "/privacy/"
	backURL.RawQuery = ""
	backURL.Fragment = ""

	authority.Path = "/user/revoke/consent"
	authority.RawQuery = url.Values{
		"revoke_client_id": {o.config.ClientID},
		"back_url":         {backURL.String()},
	}.Encode()
	authority.Fragment = ""
	return authority.String()
}

func (a *app) authRoute(w http.ResponseWriter, r *http.Request) bool {
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/v1/auth/tesla/start":
		if a.oauth == nil {
			a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "oauth_not_configured"})
			return true
		}
		consent, err := currentOAuthConsent(
			r.Header.Get("X-JourVolt-Terms-Version"),
			r.Header.Get("X-JourVolt-Privacy-Version"),
		)
		if err != nil {
			a.json(w, http.StatusBadRequest, map[string]string{"error": "consent_required"})
			return true
		}
		linkToken := strings.TrimSpace(r.Header.Get("X-WeChat-Link-Token"))
		channel := strings.TrimSpace(strings.ToLower(r.Header.Get("X-WeChat-Channel")))
		expectedUserID := ""
		if channel == "wechat" && linkToken == "" {
			var ok bool
			expectedUserID, ok = a.auth(w, r)
			if !ok {
				return true
			}
		}
		if channel == "" {
			channel = "native"
		}
		started, err := a.oauth.startForClient(r.Context(), consent, linkToken, channel, expectedUserID)
		if err != nil {
			if strings.Contains(err.Error(), "wechat_link_") {
				a.json(w, http.StatusUnauthorized, map[string]string{"error": "wechat_link_expired"})
				return true
			}
			if strings.Contains(err.Error(), "wechat_identity_") {
				a.json(w, http.StatusConflict, map[string]string{"error": "wechat_authorization_conflict"})
				return true
			}
			a.json(w, http.StatusInternalServerError, map[string]string{"error": "oauth_start_failed"})
			return true
		}
		a.json(w, http.StatusOK, started)
		return true
	case r.Method == http.MethodGet && r.URL.Path == "/v1/auth/tesla/callback":
		if a.oauth == nil {
			a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "oauth_not_configured"})
			return true
		}
		wechatFlow := a.store != nil && a.store.authStateIsWeChat(r.Context(), r.URL.Query().Get("state"))
		result, err := a.oauth.callbackResult(r.Context(), r.URL.Query())
		if err != nil {
			redirect := a.oauth.appLink("", teslaAppLinkError(err))
			if wechatFlow {
				redirect = addAppLinkChannel(a.oauth.wechatAppLink("", teslaAppLinkError(err)), "wechat")
			}
			http.Redirect(w, r, redirect, http.StatusSeeOther)
			return true
		}
		redirect := a.oauth.appLink(result.Ticket, "")
		if result.WeChat || wechatFlow {
			redirect = addAppLinkChannel(a.oauth.wechatAppLink(result.CallbackRef, ""), "wechat")
		}
		http.Redirect(w, r, redirect, http.StatusSeeOther)
		return true
	case r.Method == http.MethodPost && r.URL.Path == "/v1/auth/exchange":
		if a.oauth == nil {
			a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "oauth_not_configured"})
			return true
		}
		if a.store == nil || a.store.pool == nil {
			a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "store_unavailable"})
			return true
		}
		var request struct {
			Ticket string `json:"ticket"`
		}
		decoder := json.NewDecoder(io.LimitReader(r.Body, 8192))
		if err := decoder.Decode(&request); err != nil || strings.TrimSpace(request.Ticket) == "" {
			a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_login_ticket"})
			return true
		}
		session, err := a.store.exchangeLoginTicket(r.Context(), request.Ticket)
		if err != nil {
			if err.Error() == "invalid_login_ticket" {
				a.json(w, http.StatusUnauthorized, map[string]string{"error": "invalid_login_ticket"})
			} else {
				a.json(w, http.StatusInternalServerError, map[string]string{"error": "session_create_failed"})
			}
			return true
		}
		a.json(w, http.StatusOK, map[string]any{
			"access_token":  session.AccessToken,
			"refresh_token": session.RefreshToken,
			"expires_in":    session.ExpiresIn,
			"user":          map[string]string{"id": session.UserID},
		})
		if a.telemetry != nil {
			a.telemetry.retryAfterAuthorization(session.UserID)
		}
		return true
	case strings.HasPrefix(r.URL.Path, "/v1/auth/"):
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return true
	default:
		return false
	}
}

func (a *app) wechatAuthorize(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || a.oauth == nil || a.store == nil {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	state := strings.TrimSpace(r.URL.Query().Get("state"))
	transaction, err := a.store.authTransactionForState(r.Context(), state)
	if err != nil || transaction.Channel != "wechat" {
		a.json(w, http.StatusUnauthorized, map[string]string{"error": "invalid_oauth_state"})
		return
	}
	http.Redirect(w, r, a.oauth.authorizationURL(state, transaction.Nonce), http.StatusSeeOther)
}
