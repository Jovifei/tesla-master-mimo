package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestWeChatRestoreFailsClosedWithoutProvider(t *testing.T) {
	a := &app{}
	w := httptest.NewRecorder()
	a.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/v1/auth/wechat/restore", strings.NewReader(`{"code":"one-use-code"}`)))
	if w.Code != http.StatusServiceUnavailable || !strings.Contains(w.Body.String(), `"wechat_not_configured"`) {
		t.Fatalf("restore without provider = %d %s; want 503 wechat_not_configured", w.Code, w.Body.String())
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("WeChat auth response must not be cached")
	}
}
