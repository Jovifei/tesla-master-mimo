package main

import (
	"errors"
	"fmt"
	"regexp"
	"strings"

	"golang.org/x/oauth2"
)

var teslaErrorCodePattern = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)

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
		body := strings.TrimSpace(string(retrieve.Body))
		if len(body) > 300 {
			body = body[:300]
		}
		return fmt.Sprintf("status=%d tesla_error=%s body=%s", status, retrieve.ErrorCode, body)
	}
	return err.Error()
}

func sanitizeTeslaErrorCode(code string) string {
	code = strings.TrimSpace(code)
	if teslaErrorCodePattern.MatchString(code) {
		return code
	}
	return ""
}
