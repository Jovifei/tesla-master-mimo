package main

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"unicode"
)

type wechatService struct {
	provider wechatCodeProvider
	grants   fleetAccessTokens
}

func (a *app) wechatAuthRoute(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	action := strings.TrimPrefix(r.URL.Path, "/v1/auth/wechat/")
	if (action == "binding" && r.Method != http.MethodGet) || (action != "binding" && r.Method != http.MethodPost) ||
		(action != "binding" && action != "bind" && action != "unbind" && action != "restore") {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	var userID, accessToken string
	if action != "restore" {
		accessToken = bearerToken(r)
		if accessToken == "" {
			writeWeChatError(a, w, errSessionRequired)
			return
		}
		var err error
		userID, err = a.store.userForAccess(r.Context(), accessToken)
		if err != nil {
			if err.Error() == "session_required" {
				writeWeChatError(a, w, errSessionRequired)
			} else {
				writeWeChatError(a, w, err)
			}
			return
		}
	}
	if action == "binding" {
		bound, version, err := a.store.wechatBindingStatus(r.Context(), userID)
		if err != nil {
			writeWeChatError(a, w, err)
			return
		}
		a.json(w, http.StatusOK, map[string]any{"bound": bound, "binding_version": strconv.FormatInt(version, 10)})
		return
	}
	if action == "unbind" {
		if err := a.store.unbindWeChat(r.Context(), accessToken, userID); err != nil {
			writeWeChatError(a, w, err)
			return
		}
		a.json(w, http.StatusOK, map[string]string{"status": "unbound"})
		return
	}
	if a.wechat == nil || a.wechat.provider == nil || a.wechat.grants == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "wechat_not_configured"})
		return
	}
	var request struct {
		Code           string `json:"code"`
		BindingVersion string `json:"binding_version"`
	}
	decoder := json.NewDecoder(io.LimitReader(r.Body, 8192))
	if err := decoder.Decode(&request); err != nil || !validWeChatCode(request.Code) || decoder.Decode(&struct{}{}) != io.EOF {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_wechat_request"})
		return
	}
	var expectedVersion int64
	if action == "bind" {
		var valid bool
		expectedVersion, valid = parseWeChatBindingVersion(request.BindingVersion)
		if !valid {
			a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_wechat_request"})
			return
		}
	}
	provider := a.wechat.provider.ProviderID()
	started, err := a.store.claimWeChatCode(r.Context(), provider, request.Code)
	if err != nil {
		writeWeChatError(a, w, err)
		return
	}
	identity, err := a.wechat.provider.Exchange(r.Context(), request.Code)
	if err != nil {
		writeWeChatError(a, w, err)
		return
	}
	if identity.OpenID == "" {
		writeWeChatError(a, w, errWeChatUnavailable)
		return
	}
	if action == "restore" {
		binding, err := a.store.findWeChatBinding(r.Context(), provider, identity)
		if err != nil {
			writeWeChatError(a, w, err)
			return
		}
		if _, err := a.wechat.grants.accessToken(r.Context(), binding.UserID, ""); err != nil {
			writeWeChatError(a, w, err)
			return
		}
		granted, err := a.store.restoreWeChatSession(r.Context(), binding, started)
		if err != nil {
			writeWeChatError(a, w, err)
			return
		}
		a.json(w, http.StatusOK, map[string]any{"access_token": granted.AccessToken, "refresh_token": granted.RefreshToken, "expires_in": granted.ExpiresIn, "user": map[string]string{"id": granted.UserID}})
		return
	}
	if _, err := a.wechat.grants.accessToken(r.Context(), userID, ""); err != nil {
		writeWeChatError(a, w, err)
		return
	}
	if err := a.store.bindWeChat(r.Context(), accessToken, userID, provider, identity, expectedVersion); err != nil {
		writeWeChatError(a, w, err)
		return
	}
	a.json(w, http.StatusOK, map[string]string{"status": "bound"})
}

func parseWeChatBindingVersion(value string) (int64, bool) {
	if len(value) == 0 || len(value) > 19 {
		return 0, false
	}
	for _, c := range value {
		if c < '0' || c > '9' {
			return 0, false
		}
	}
	version, err := strconv.ParseInt(value, 10, 64)
	return version, err == nil
}

func validWeChatCode(code string) bool {
	if len(code) == 0 || len(code) > 512 {
		return false
	}
	return !strings.ContainsFunc(code, func(r rune) bool { return unicode.IsSpace(r) || unicode.IsControl(r) })
}

func writeWeChatError(a *app, w http.ResponseWriter, err error) {
	status, code := http.StatusInternalServerError, "wechat_service_failed"
	switch {
	case errors.Is(err, errWeChatCodeInvalid):
		status, code = http.StatusUnauthorized, "wechat_code_invalid"
	case errors.Is(err, errWeChatNotBound):
		status, code = http.StatusNotFound, "wechat_not_bound"
	case errors.Is(err, errWeChatConflict):
		status, code = http.StatusConflict, "wechat_binding_conflict"
	case errors.Is(err, errWeChatBindExpired):
		status, code = http.StatusConflict, "wechat_bind_expired"
	case errors.Is(err, errSessionRequired):
		status, code = http.StatusUnauthorized, "session_required"
	case errors.Is(err, errTeslaReauthorization):
		status, code = http.StatusUnauthorized, "tesla_reauthorization_required"
	case errors.Is(err, errWeChatUnavailable):
		status, code = http.StatusServiceUnavailable, "wechat_provider_unavailable"
	case errors.Is(err, errTeslaUnavailable), errors.Is(err, errTeslaRateLimited), errors.Is(err, errTeslaConfiguration):
		status, code = http.StatusServiceUnavailable, "tesla_unavailable"
	}
	a.json(w, status, map[string]string{"error": code})
}
