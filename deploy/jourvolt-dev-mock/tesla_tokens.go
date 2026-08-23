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

var (
	errTeslaReauthorization = errors.New("tesla_reauthorization_required")
	errTeslaRateLimited     = errors.New("tesla_rate_limited")
	errTeslaUnavailable     = errors.New("tesla_unavailable")
	errVehicleNotFound      = errors.New("vehicle_not_found")
)

type fleetAccessTokens interface {
	accessToken(context.Context, string, string) (string, error)
}

type teslaTokenManager struct {
	store  *store
	cipher *tokenCipher
	config *teslaConfig
	client *http.Client
}

func (m *teslaTokenManager) accessToken(ctx context.Context, userID, rejectedAccessToken string) (string, error) {
	tx, err := m.store.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx)

	var accessCiphertext, refreshCiphertext string
	var accessExpiresAt time.Time
	err = tx.QueryRow(ctx, `
SELECT access_ciphertext, refresh_ciphertext, access_expires_at
FROM jourvolt_tesla_tokens
WHERE user_id=$1
FOR UPDATE`, userID).Scan(&accessCiphertext, &refreshCiphertext, &accessExpiresAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", errTeslaReauthorization
	}
	if err != nil {
		return "", err
	}
	accessToken, err := m.cipher.decrypt(accessCiphertext)
	if err != nil {
		return "", err
	}
	stillValid := accessExpiresAt.After(time.Now().UTC().Add(time.Minute))
	alreadyRotated := rejectedAccessToken != "" && accessToken != rejectedAccessToken
	if (rejectedAccessToken == "" && stillValid) || alreadyRotated {
		if err := tx.Commit(ctx); err != nil {
			return "", err
		}
		return accessToken, nil
	}

	refreshToken, err := m.cipher.decrypt(refreshCiphertext)
	if err != nil {
		return "", err
	}
	refreshed, err := m.refresh(ctx, refreshToken)
	if err != nil {
		return "", err
	}
	newAccessCiphertext, err := m.cipher.encrypt(refreshed.AccessToken)
	if err != nil {
		return "", err
	}
	newRefreshCiphertext, err := m.cipher.encrypt(refreshed.RefreshToken)
	if err != nil {
		return "", err
	}
	newExpiresAt := time.Now().UTC().Add(time.Duration(refreshed.ExpiresIn) * time.Second)
	_, err = tx.Exec(ctx, `
UPDATE jourvolt_tesla_tokens SET
access_ciphertext=$2,
refresh_ciphertext=$3,
access_expires_at=$4,
updated_at=now()
WHERE user_id=$1`, userID, newAccessCiphertext, newRefreshCiphertext, newExpiresAt)
	if err != nil {
		return "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", err
	}
	return refreshed.AccessToken, nil
}

type teslaRefreshResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int64  `json:"expires_in"`
}

func (m *teslaTokenManager) refresh(ctx context.Context, refreshToken string) (teslaRefreshResponse, error) {
	form := url.Values{
		"grant_type":    {"refresh_token"},
		"client_id":     {m.config.ClientID},
		"refresh_token": {refreshToken},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, m.config.TokenEndpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return teslaRefreshResponse{}, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	response, err := m.client.Do(req)
	if err != nil {
		return teslaRefreshResponse{}, errTeslaUnavailable
	}
	defer response.Body.Close()
	switch response.StatusCode {
	case http.StatusUnauthorized:
		return teslaRefreshResponse{}, errTeslaReauthorization
	case http.StatusTooManyRequests:
		return teslaRefreshResponse{}, errTeslaRateLimited
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		return teslaRefreshResponse{}, errTeslaUnavailable
	}
	var payload teslaRefreshResponse
	if err := json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&payload); err != nil {
		return teslaRefreshResponse{}, fmt.Errorf("decode Tesla refresh response: %w", err)
	}
	if payload.AccessToken == "" || payload.RefreshToken == "" || payload.ExpiresIn <= 0 {
		return teslaRefreshResponse{}, errTeslaUnavailable
	}
	return payload, nil
}
