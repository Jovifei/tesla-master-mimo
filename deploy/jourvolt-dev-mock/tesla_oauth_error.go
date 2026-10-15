package main

import (
	"errors"
	"fmt"
	"strings"

	"golang.org/x/oauth2"
)

func teslaAppLinkError(err error) string {
	if err == nil {
		return "authorization_failed"
	}
	var retrieve *oauth2.RetrieveError
	if errors.As(err, &retrieve) {
		if code := sanitizeTeslaErrorCode(retrieve.ErrorCode); code != "" {
			return code
		}
	}
	if strings.Contains(err.Error(), "tesla id_token") {
		return "id_token_invalid"
	}
	return "authorization_failed"
}

func teslaCallbackLogError(err error) string {
	if err == nil {
		return ""
	}
	var retrieve *oauth2.RetrieveError
	if errors.As(err, &retrieve) {
		status := 0
		if retrieve.Response != nil {
			status = retrieve.Response.StatusCode
		}
		code := sanitizeTeslaErrorCode(retrieve.ErrorCode)
		if code == "" {
			code = "authorization_failed"
		}
		return fmt.Sprintf("status=%d tesla_error=%s", status, code)
	}
	return "status=0 tesla_error=authorization_failed"
}

func sanitizeTeslaErrorCode(code string) string {
	code = strings.TrimSpace(code)
	switch code {
	case "invalid_request", "invalid_client", "invalid_grant", "unauthorized_client", "unsupported_grant_type", "invalid_scope", "access_denied", "temporarily_unavailable", "server_error", "not_found", "vehicle_not_found", "rate_limited", "billing_blocked":
		return code
	}
	return ""
}
