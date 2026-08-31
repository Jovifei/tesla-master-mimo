package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
)

const teslaPartnerTokenScope = "openid vehicle_device_data vehicle_cmds vehicle_charging_cmds"

type teslaPartnerRegistrar struct {
	config *teslaConfig
	client *http.Client
	mu     sync.Mutex
	ok     bool
}

func newTeslaPartnerRegistrar(config *teslaConfig, client *http.Client) *teslaPartnerRegistrar {
	return &teslaPartnerRegistrar{config: config, client: client}
}

func (r *teslaPartnerRegistrar) ensure(ctx context.Context) error {
	if r == nil {
		return nil
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.ok {
		return nil
	}
	if err := r.register(ctx); err != nil {
		return err
	}
	r.ok = true
	return nil
}

func (r *teslaPartnerRegistrar) register(ctx context.Context) error {
	token, err := r.partnerAccessToken(ctx)
	if err != nil {
		return err
	}
	payload, err := json.Marshal(map[string]string{"domain": r.config.PartnerDomain})
	if err != nil {
		return err
	}
	path := "/api/1/partner_accounts"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.config.FleetAPIBase+path, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/json")
	response, err := r.client.Do(req)
	if err != nil {
		return fmt.Errorf("tesla partner register: %w", err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		logTeslaAPI("POST", path, response.StatusCode, body)
		return fmt.Errorf("tesla partner register: status=%d", response.StatusCode)
	}
	logTeslaAPI("POST", path, response.StatusCode, nil)
	return nil
}

func (r *teslaPartnerRegistrar) partnerAccessToken(ctx context.Context) (string, error) {
	form := url.Values{
		"grant_type":    {"client_credentials"},
		"client_id":     {r.config.ClientID},
		"client_secret": {r.config.ClientSecret},
		"audience":      {r.config.FleetAPIBase},
		"scope":         {teslaPartnerTokenScope},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.config.TokenEndpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	response, err := r.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("tesla partner token: %w", err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		logTeslaAPI("POST", r.config.TokenEndpoint, response.StatusCode, body)
		return "", fmt.Errorf("tesla partner token: status=%d", response.StatusCode)
	}
	var payload struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.Unmarshal(body, &payload); err != nil || payload.AccessToken == "" {
		return "", fmt.Errorf("tesla partner token: missing access_token")
	}
	return payload.AccessToken, nil
}
