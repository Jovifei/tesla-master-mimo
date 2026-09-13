package main

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const (
	authTransactionLifetime = 10 * time.Minute
	loginTicketLifetime     = 2 * time.Minute
	jourVoltTermsVersion    = "2026-08-21"
	jourVoltPrivacyVersion  = "2026-08-21"
)

type oauthConsent struct {
	TermsVersion   string
	PrivacyVersion string
}

type authTransaction struct {
	StateHash              string
	TransactionHash        string
	Nonce                  string
	Consent                oauthConsent
	Channel                string
	ClientProofHash        string
	ExpectedUserID         string
	WeChatStatus           string
	WeChatCallbackRefHash  string
	WeChatTicketCiphertext string
	WeChatCompletedUserID  string
	WeChatLinkTokenHash    string
	WeChatAppID            string
	WeChatOpenIDHash       string
}

func currentOAuthConsent(termsVersion, privacyVersion string) (oauthConsent, error) {
	consent := oauthConsent{
		TermsVersion:   strings.TrimSpace(termsVersion),
		PrivacyVersion: strings.TrimSpace(privacyVersion),
	}
	if consent.TermsVersion != jourVoltTermsVersion || consent.PrivacyVersion != jourVoltPrivacyVersion {
		return oauthConsent{}, errors.New("consent_required")
	}
	return consent, nil
}

func (s *store) createAuthTransaction(
	ctx context.Context,
	state, transactionID, nonce string,
	consent oauthConsent,
	expiresAt time.Time,
) error {
	return s.createAuthTransactionWithClient(ctx, state, transactionID, nonce, consent, expiresAt, wechatLinkInfo{}, "", "", "")
}

func (s *store) createAuthTransactionWithWeChat(
	ctx context.Context,
	state, transactionID, nonce string,
	consent oauthConsent,
	expiresAt time.Time,
	wechat wechatLinkInfo,
) error {
	return s.createAuthTransactionWithClient(ctx, state, transactionID, nonce, consent, expiresAt, wechat, "", "", "")
}

func (s *store) createAuthTransactionWithClient(
	ctx context.Context,
	state, transactionID, nonce string,
	consent oauthConsent,
	expiresAt time.Time,
	wechat wechatLinkInfo,
	channel, clientProofHash, expectedUserID string,
) error {
	if s == nil || s.pool == nil {
		return errors.New("store_unavailable")
	}
	_, err := s.pool.Exec(ctx, `
INSERT INTO jourvolt_auth_transactions(
    state_hash, transaction_hash, nonce, terms_version, privacy_version,
    wechat_link_hash, wechat_app_id, wechat_openid_hash,
    channel, client_proof_hash, expected_user_id, wechat_status, expires_at
)
VALUES ($1, $2, $3, $4, $5, NULLIF($6, ''), NULLIF($7, ''), NULLIF($8, ''),
        COALESCE(NULLIF($9, ''), 'native'), NULLIF($10, ''), NULLIF($11, ''), 'pending', $12)`,
		hashToken(state), hashToken(transactionID), nonce,
		consent.TermsVersion, consent.PrivacyVersion,
		wechat.TokenHash, wechat.AppID, wechat.OpenIDHash,
		channel, clientProofHash, expectedUserID, expiresAt)
	return err
}

func (s *store) consumeAuthState(ctx context.Context, state string) (authTransaction, error) {
	var transaction authTransaction
	transaction.StateHash = hashToken(state)
	err := s.pool.QueryRow(ctx, `
UPDATE jourvolt_auth_transactions
SET consumed_at=now()
WHERE state_hash=$1 AND consumed_at IS NULL AND expires_at > now()
	RETURNING transaction_hash, nonce, COALESCE(terms_version, ''), COALESCE(privacy_version, ''),
COALESCE(channel, 'native'), COALESCE(client_proof_hash, ''), COALESCE(expected_user_id, ''),
COALESCE(wechat_status, 'pending'), COALESCE(wechat_callback_ref_hash, ''),
COALESCE(wechat_ticket_ciphertext, ''), COALESCE(wechat_completed_user_id, ''),
COALESCE(wechat_link_hash, ''), COALESCE(wechat_app_id, ''), COALESCE(wechat_openid_hash, '')`, hashToken(state)).Scan(
		&transaction.TransactionHash,
		&transaction.Nonce,
		&transaction.Consent.TermsVersion,
		&transaction.Consent.PrivacyVersion,
		&transaction.Channel,
		&transaction.ClientProofHash,
		&transaction.ExpectedUserID,
		&transaction.WeChatStatus,
		&transaction.WeChatCallbackRefHash,
		&transaction.WeChatTicketCiphertext,
		&transaction.WeChatCompletedUserID,
		&transaction.WeChatLinkTokenHash,
		&transaction.WeChatAppID,
		&transaction.WeChatOpenIDHash,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return authTransaction{}, errors.New("invalid_oauth_state")
	}
	if err != nil {
		return authTransaction{}, err
	}
	if _, err := currentOAuthConsent(transaction.Consent.TermsVersion, transaction.Consent.PrivacyVersion); err != nil {
		return authTransaction{}, errors.New("invalid_oauth_state")
	}
	return transaction, nil
}

func (s *store) authStateIsWeChat(ctx context.Context, state string) bool {
	transaction, err := s.authTransactionForState(ctx, state)
	return err == nil && transaction.Channel == "wechat"
}

func (s *store) authTransactionForState(ctx context.Context, state string) (authTransaction, error) {
	if s == nil || s.pool == nil || strings.TrimSpace(state) == "" {
		return authTransaction{}, errors.New("invalid_oauth_state")
	}
	var transaction authTransaction
	transaction.StateHash = hashToken(state)
	err := s.pool.QueryRow(ctx, `
SELECT transaction_hash, nonce, COALESCE(terms_version, ''), COALESCE(privacy_version, ''),
COALESCE(channel, 'native'), COALESCE(client_proof_hash, ''), COALESCE(expected_user_id, ''),
COALESCE(wechat_status, 'pending'), COALESCE(wechat_callback_ref_hash, ''),
COALESCE(wechat_ticket_ciphertext, ''), COALESCE(wechat_completed_user_id, ''),
COALESCE(wechat_link_hash, ''), COALESCE(wechat_app_id, ''), COALESCE(wechat_openid_hash, '')
FROM jourvolt_auth_transactions
WHERE state_hash=$1 AND consumed_at IS NULL AND expires_at > now()`, hashToken(state)).Scan(
		&transaction.TransactionHash,
		&transaction.Nonce,
		&transaction.Consent.TermsVersion,
		&transaction.Consent.PrivacyVersion,
		&transaction.Channel,
		&transaction.ClientProofHash,
		&transaction.ExpectedUserID,
		&transaction.WeChatStatus,
		&transaction.WeChatCallbackRefHash,
		&transaction.WeChatTicketCiphertext,
		&transaction.WeChatCompletedUserID,
		&transaction.WeChatLinkTokenHash,
		&transaction.WeChatAppID,
		&transaction.WeChatOpenIDHash,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return authTransaction{}, errors.New("invalid_oauth_state")
	}
	if err != nil {
		return authTransaction{}, err
	}
	return transaction, nil
}

func (s *store) saveTeslaGrantAndLoginTicket(
	ctx context.Context,
	providerSub, accessCiphertext, refreshCiphertext string,
	consent oauthConsent,
	accessExpiresAt time.Time,
) (string, string, error) {
	ticket, err := randomToken()
	if err != nil {
		return "", "", err
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", "", err
	}
	defer tx.Rollback(ctx)

	userID, err := ensureTeslaUserTx(ctx, tx, providerSub)
	if err != nil {
		return "", "", err
	}
	if err := persistTeslaGrantTx(ctx, tx, userID, accessCiphertext, refreshCiphertext, consent, accessExpiresAt, ticket); err != nil {
		return "", "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", "", err
	}
	return userID, ticket, nil
}

func ensureTeslaUserTx(ctx context.Context, tx pgx.Tx, providerSub string) (string, error) {
	userIDSeed, err := randomToken()
	if err != nil {
		return "", err
	}
	providerSubHash := hashToken("tesla:" + providerSub)
	var userID string
	err = tx.QueryRow(ctx, `
INSERT INTO jourvolt_users(id, provider_sub) VALUES ($1, $2)
ON CONFLICT (provider_sub) DO UPDATE SET provider_sub=EXCLUDED.provider_sub
RETURNING id`, "usr_"+userIDSeed, providerSubHash).Scan(&userID)
	if err != nil {
		return "", err
	}
	return userID, nil
}

func persistTeslaGrantTx(
	ctx context.Context,
	tx pgx.Tx,
	userID, accessCiphertext, refreshCiphertext string,
	consent oauthConsent,
	accessExpiresAt time.Time,
	ticket string,
) error {
	if _, err := currentOAuthConsent(consent.TermsVersion, consent.PrivacyVersion); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `
INSERT INTO jourvolt_tesla_tokens(user_id, access_ciphertext, refresh_ciphertext, access_expires_at, updated_at)
VALUES ($1, $2, $3, $4, now())
ON CONFLICT (user_id) DO UPDATE SET
access_ciphertext=EXCLUDED.access_ciphertext,
	refresh_ciphertext=EXCLUDED.refresh_ciphertext,
access_expires_at=EXCLUDED.access_expires_at,
updated_at=now()`, userID, accessCiphertext, refreshCiphertext, accessExpiresAt)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `
INSERT INTO jourvolt_user_consents(user_id, terms_version, privacy_version, accepted_at)
VALUES ($1, $2, $3, now())
ON CONFLICT (user_id) DO UPDATE SET
terms_version=EXCLUDED.terms_version,
privacy_version=EXCLUDED.privacy_version,
	accepted_at=EXCLUDED.accepted_at`, userID, consent.TermsVersion, consent.PrivacyVersion)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `
INSERT INTO jourvolt_login_tickets(ticket_hash, user_id, expires_at)
VALUES ($1, $2, $3)`, hashToken(ticket), userID, time.Now().UTC().Add(loginTicketLifetime))
	if err != nil {
		return err
	}
	return nil
}

func (s *store) saveTeslaGrantAndWeChatArtifact(
	ctx context.Context,
	transaction authTransaction,
	providerSub, accessCiphertext, refreshCiphertext string,
	consent oauthConsent,
	accessExpiresAt time.Time,
	ticket, ticketCiphertext, callbackRef string,
) (string, error) {
	if s == nil || s.pool == nil {
		return "", errors.New("store_unavailable")
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx)
	userID, err := ensureTeslaUserTx(ctx, tx, providerSub)
	if err != nil {
		return "", err
	}
	if transaction.ExpectedUserID != "" && transaction.ExpectedUserID != userID {
		return "", errors.New("wechat_identity_conflict")
	}
	if transaction.WeChatLinkTokenHash != "" {
		if err := bindWeChatIdentityTx(ctx, tx, transaction.WeChatLinkTokenHash, userID); err != nil {
			return "", err
		}
	}
	if err := persistTeslaGrantTx(ctx, tx, userID, accessCiphertext, refreshCiphertext, consent, accessExpiresAt, ticket); err != nil {
		return "", err
	}
	commandTag, err := tx.Exec(ctx, `
UPDATE jourvolt_auth_transactions
SET wechat_status='ready', wechat_callback_ref_hash=$2,
    wechat_ticket_ciphertext=$3, wechat_completed_user_id=$4
WHERE transaction_hash=$1 AND channel='wechat' AND consumed_at IS NOT NULL
  AND client_proof_hash IS NOT NULL AND wechat_status='pending'`,
		transaction.TransactionHash, hashToken(callbackRef), ticketCiphertext, userID)
	if err != nil {
		return "", err
	}
	if commandTag.RowsAffected() != 1 {
		return "", errors.New("wechat_transaction_invalid")
	}
	if err := tx.Commit(ctx); err != nil {
		return "", err
	}
	return callbackRef, nil
}

func (s *store) exchangeLoginTicket(ctx context.Context, ticket string) (session, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return session{}, err
	}
	defer tx.Rollback(ctx)

	result, err := s.exchangeLoginTicketTx(ctx, tx, ticket)
	if err != nil {
		return session{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return session{}, err
	}
	return result, nil
}

func (s *store) exchangeLoginTicketTx(ctx context.Context, tx pgx.Tx, ticket string) (session, error) {
	access, err := randomToken()
	if err != nil {
		return session{}, err
	}
	refresh, err := randomToken()
	if err != nil {
		return session{}, err
	}
	var userID string
	err = tx.QueryRow(ctx, `
UPDATE jourvolt_login_tickets
SET consumed_at=now()
WHERE ticket_hash=$1 AND consumed_at IS NULL AND expires_at > now()
RETURNING user_id`, hashToken(ticket)).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return session{}, errors.New("invalid_login_ticket")
	}
	if err != nil {
		return session{}, err
	}
	now := time.Now().UTC()
	_, err = tx.Exec(ctx, `
INSERT INTO jourvolt_sessions(user_id, access_hash, refresh_hash, access_expires_at, refresh_expires_at)
VALUES ($1, $2, $3, $4, $5)`, userID, hashToken(access), hashToken(refresh),
		now.Add(accessLifetime), now.Add(refreshLifetime))
	if err != nil {
		return session{}, err
	}
	return session{
		AccessToken: access, RefreshToken: refresh,
		ExpiresIn: int64(accessLifetime.Seconds()), UserID: userID,
	}, nil
}

type storedVehicle struct {
	ID            int
	VINCiphertext string
	DisplayName   string
	State         string
	Model         string
	TrimBadging   string
	ExteriorColor string
	WheelType     string
}

func (s *store) upsertFleetVehicle(
	ctx context.Context,
	userID, providerVehicleID, vinCiphertext, displayName, state string,
) (storedVehicle, error) {
	var record storedVehicle
	err := s.pool.QueryRow(ctx, `
INSERT INTO jourvolt_vehicles(user_id, provider_vehicle_id, vin_ciphertext, display_name, state, updated_at)
VALUES ($1, $2, $3, $4, $5, now())
ON CONFLICT (user_id, provider_vehicle_id) DO UPDATE SET
vin_ciphertext=EXCLUDED.vin_ciphertext,
display_name=EXCLUDED.display_name,
state=EXCLUDED.state,
updated_at=now()
RETURNING id, vin_ciphertext, display_name, state,
COALESCE(model, ''), COALESCE(trim_badging, ''), COALESCE(exterior_color, ''), COALESCE(wheel_type, '')`,
		userID, providerVehicleID, vinCiphertext, displayName, state,
	).Scan(
		&record.ID, &record.VINCiphertext, &record.DisplayName, &record.State,
		&record.Model, &record.TrimBadging, &record.ExteriorColor, &record.WheelType,
	)
	return record, err
}

func (s *store) fleetVehicle(ctx context.Context, userID string, vehicleID int) (storedVehicle, error) {
	var record storedVehicle
	err := s.pool.QueryRow(ctx, `
SELECT id, vin_ciphertext, display_name, state
FROM jourvolt_vehicles
WHERE id=$1 AND user_id=$2`, vehicleID, userID).Scan(
		&record.ID, &record.VINCiphertext, &record.DisplayName, &record.State,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return storedVehicle{}, errors.New("vehicle_not_found")
	}
	return record, err
}

func (s *store) updateFleetVehicleMetadata(
	ctx context.Context,
	userID string,
	vehicleID int,
	displayName, state, model, trimBadging, exteriorColor, wheelType string,
) error {
	commandTag, err := s.pool.Exec(ctx, `
UPDATE jourvolt_vehicles SET
display_name=$3,
state=$4,
model=COALESCE(NULLIF($5, ''), model),
trim_badging=COALESCE(NULLIF($6, ''), trim_badging),
exterior_color=COALESCE(NULLIF($7, ''), exterior_color),
wheel_type=COALESCE(NULLIF($8, ''), wheel_type),
updated_at=now()
WHERE id=$1 AND user_id=$2`, vehicleID, userID, displayName, state, model, trimBadging, exteriorColor, wheelType)
	if err != nil {
		return err
	}
	if commandTag.RowsAffected() != 1 {
		return fmt.Errorf("vehicle_not_found")
	}
	return nil
}
