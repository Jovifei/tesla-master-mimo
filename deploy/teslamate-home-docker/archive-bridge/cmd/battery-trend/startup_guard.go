package main

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

const bindingIdentityHeader = "X-MateLink-Binding-Identity"

var errUnownedRestartState = errors.New("battery trend state requires owner verification")

// Kept for direct guard regression coverage; production startup fetches and
// verifies the current server binding identity before calling verifyOwnerState.
func rejectUnownedRestartState(config) error {
	return errUnownedRestartState
}

func requireLocallySafeRestartState(c config) error {
	if _, err := os.Stat(c.StateFile); errors.Is(err, os.ErrNotExist) {
		return nil
	} else if err != nil {
		return errUnownedRestartState
	}
	state, err := loadState(c)
	if err != nil || state.Version != 2 || !validBindingIdentity(state.BindingIdentity) {
		return errUnownedRestartState
	}
	return nil
}

func validBindingIdentity(value string) bool {
	if len(value) != 64 || strings.ToLower(value) != value {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

func fetchBindingIdentity(ctx context.Context, c config, client *http.Client) (string, error) {
	endpoint, err := url.Parse(c.ArchiveURL)
	if err != nil || endpoint.Scheme != "https" || endpoint.Host == "" || endpoint.User != nil {
		return "", errors.New("invalid archive URL")
	}
	endpoint.Path = strings.TrimRight(endpoint.Path, "/") + "/api/v1/cars/" + c.ArchiveVehicleID + "/history/archive/binding-identity"
	endpoint.RawPath, endpoint.RawQuery, endpoint.Fragment = "", "", ""
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint.String(), nil)
	if err != nil {
		return "", errors.New("identity request creation failed")
	}
	req.Header.Set("X-MateLink-Archive-Binding", c.ArchiveToken)
	req.Header.Set("Accept", "application/json")
	response, err := client.Do(req)
	if err != nil {
		return "", errors.New("identity request failed")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", trendHTTPError{status: response.StatusCode, retryAfter: trendRetryAfter(response.Header.Get("Retry-After"), time.Now())}
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 4097))
	if err != nil || len(body) > 4096 {
		return "", errors.New("invalid identity response")
	}
	var receipt struct {
		Data struct {
			SchemaVersion   *int    `json:"schema_version"`
			BindingIdentity *string `json:"binding_identity"`
		} `json:"data"`
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&receipt); err != nil || decoder.Decode(&struct{}{}) != io.EOF ||
		receipt.Data.SchemaVersion == nil || *receipt.Data.SchemaVersion != 1 ||
		receipt.Data.BindingIdentity == nil || !validBindingIdentity(*receipt.Data.BindingIdentity) {
		return "", errors.New("identity response mismatch")
	}
	return *receipt.Data.BindingIdentity, nil
}

func verifyOwnerState(c config, identity string) (trendState, error) {
	if !validBindingIdentity(identity) {
		return trendState{}, errUnownedRestartState
	}
	if _, err := os.Stat(c.StateFile); errors.Is(err, os.ErrNotExist) {
		state := trendState{Version: 2, ScopeHash: c.ScopeHash, BindingIdentity: identity}
		if err = saveState(c, state); err != nil {
			return trendState{}, err
		}
		return state, nil
	} else if err != nil {
		return trendState{}, errUnownedRestartState
	}
	state, err := loadState(c)
	if err != nil || state.Version != 2 || state.BindingIdentity != identity {
		return trendState{}, errUnownedRestartState
	}
	return state, nil
}

func migrateLegacyState(c config, identity string) error {
	if !validBindingIdentity(identity) {
		return errUnownedRestartState
	}
	state, err := loadState(c)
	if err != nil || state.Version != 1 || state.BindingIdentity != "" {
		return errUnownedRestartState
	}
	state.Version = 2
	state.BindingIdentity = identity
	return saveState(c, state)
}
