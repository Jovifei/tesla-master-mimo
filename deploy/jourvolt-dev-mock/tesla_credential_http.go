package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"time"
)

var errTeslaConfiguration = errors.New("tesla_configuration_error")

// Clone the client so even injected transports retain their timeout and TLS
// policy without mutating a caller or forwarding credentials to redirects.
func credentialHTTPClient(source *http.Client) *http.Client {
	client := http.Client{Timeout: 20 * time.Second}
	if source != nil {
		client = *source
	}
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return &client
}

func teslaTokenFailure(status int, body []byte) error {
	if status == http.StatusTooManyRequests {
		return errTeslaRateLimited
	}
	if status >= 500 || status >= 300 && status < 400 {
		return errTeslaUnavailable
	}
	var payload struct {
		Error string `json:"error"`
	}
	_ = json.Unmarshal(body, &payload)
	if status == 400 || status == 401 || status == 403 {
		switch payload.Error {
		case "invalid_client", "unauthorized_client":
			return errTeslaConfiguration
		case "invalid_grant":
			return errTeslaReauthorization
		}
		if status == http.StatusUnauthorized {
			return errTeslaReauthorization
		}
	}
	return errTeslaUnavailable
}
