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
	Nonce   string
	Consent oauthConsent
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
	_, err := s.pool.Exec(ctx, `
INSERT INTO jourvolt_auth_transactions(
    state_hash, transaction_hash, nonce, terms_version, privacy_version, expires_at
)
VALUES ($1, $2, $3, $4, $5, $6)`,
		hashToken(state), hashToken(transactionID), nonce,
		consent.TermsVersion, consent.PrivacyVersion, expiresAt)
	return err
}

func (s *store) consumeAuthState(ctx context.Context, state string) (authTransaction, error) {
	var transaction authTransaction
	err := s.pool.QueryRow(ctx, `
UPDATE jourvolt_auth_transactions
SET consumed_at=now()
WHERE state_hash=$1 AND consumed_at IS NULL AND expires_at > now()
RETURNING nonce, terms_version, privacy_version`, hashToken(state)).Scan(
		&transaction.Nonce,
		&transaction.Consent.TermsVersion,
		&transaction.Consent.PrivacyVersion,
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

func (s *store) saveTeslaGrantAndLoginTicket(
	ctx context.Context,
	providerSub, accessCiphertext, refreshCiphertext string,
	consent oauthConsent,
	accessExpiresAt time.Time,
) (string, string, error) {
	userIDSeed, err := randomToken()
	if err != nil {
		return "", "", err
	}
	ticket, err := randomToken()
	if err != nil {
		return "", "", err
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", "", err
	}
	defer tx.Rollback(ctx)

	var userID string
	providerSubHash := hashToken("tesla:" + providerSub)
	err = tx.QueryRow(ctx, `
INSERT INTO jourvolt_users(id, provider_sub) VALUES ($1, $2)
ON CONFLICT (provider_sub) DO UPDATE SET provider_sub=EXCLUDED.provider_sub
RETURNING id`, "usr_"+userIDSeed, providerSubHash).Scan(&userID)
	if err != nil {
		return "", "", err
	}
	if _, err := currentOAuthConsent(consent.TermsVersion, consent.PrivacyVersion); err != nil {
		return "", "", err
	}
	_, err = tx.Exec(ctx, `
INSERT INTO jourvolt_tesla_tokens(user_id, access_ciphertext, refresh_ciphertext, access_expires_at, updated_at)
VALUES ($1, $2, $3, $4, now())
ON CONFLICT (user_id) DO UPDATE SET
access_ciphertext=EXCLUDED.access_ciphertext,
refresh_ciphertext=EXCLUDED.refresh_ciphertext,
access_expires_at=EXCLUDED.access_expires_at,
updated_at=now()`, userID, accessCiphertext, refreshCiphertext, accessExpiresAt)
	if err != nil {
		return "", "", err
	}
	_, err = tx.Exec(ctx, `
INSERT INTO jourvolt_user_consents(user_id, terms_version, privacy_version, accepted_at)
VALUES ($1, $2, $3, now())
ON CONFLICT (user_id) DO UPDATE SET
terms_version=EXCLUDED.terms_version,
privacy_version=EXCLUDED.privacy_version,
accepted_at=EXCLUDED.accepted_at`, userID, consent.TermsVersion, consent.PrivacyVersion)
	if err != nil {
		return "", "", err
	}
	_, err = tx.Exec(ctx, `
INSERT INTO jourvolt_login_tickets(ticket_hash, user_id, expires_at)
VALUES ($1, $2, $3)`, hashToken(ticket), userID, time.Now().UTC().Add(loginTicketLifetime))
	if err != nil {
		return "", "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", "", err
	}
	return userID, ticket, nil
}

func (s *store) exchangeLoginTicket(ctx context.Context, ticket string) (session, error) {
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
	if err := tx.Commit(ctx); err != nil {
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
model=NULLIF($5, ''),
trim_badging=NULLIF($6, ''),
exterior_color=NULLIF($7, ''),
wheel_type=NULLIF($8, ''),
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
