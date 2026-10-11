package main

import (
	"net/http/httptest"
	"strings"
	"testing"
)

func TestApplinkFallbackPageRejectsNonGet(t *testing.T) {
	a := &app{}
	rec := httptest.NewRecorder()
	a.applinkFallbackPage(rec, httptest.NewRequest("POST", "/oauth/callback", nil))
	if rec.Code != 405 {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
}

func TestApplinkFallbackPageSuccessContainsIntent(t *testing.T) {
	a := &app{}
	rec := httptest.NewRecorder()
	a.applinkFallbackPage(rec, httptest.NewRequest("GET", "/oauth/callback?ticket=abc123", nil))
	if rec.Code != 200 {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "intent://") {
		t.Fatal("body missing intent:// URL")
	}
	if !strings.Contains(body, "package=com.matelink") {
		t.Fatal("body missing applink package")
	}
	if !strings.Contains(body, "ticket=abc123") {
		t.Fatal("body must preserve ticket parameter")
	}
	if strings.Contains(body, "browser_fallback_url=https%253A") {
		t.Fatal("browser_fallback_url is double-encoded")
	}
	if !strings.Contains(body, "browser_fallback_url=https%3A%2F%2F") {
		t.Fatal("browser_fallback_url must be single-encoded")
	}
	if !strings.Contains(body, "授权成功") {
		t.Fatal("body missing success copy")
	}
}

func TestApplinkFallbackPageEscapesTicket(t *testing.T) {
	a := &app{}
	rec := httptest.NewRecorder()
	a.applinkFallbackPage(rec, httptest.NewRequest("GET", "/oauth/callback?ticket=%3Cscript%3Ealert(1)%3C%2Fscript%3E", nil))
	body := rec.Body.String()
	if strings.Contains(body, "<script>alert(1)") {
		t.Fatal("raw script tag leaked into body")
	}
	if !strings.Contains(body, "&lt;script&gt;") && !strings.Contains(body, "%3Cscript%3E") {
		t.Fatal("ticket neither escaped nor percent-encoded")
	}
}

func TestApplinkFallbackPageErrorCopy(t *testing.T) {
	a := &app{}
	rec := httptest.NewRecorder()
	a.applinkFallbackPage(rec, httptest.NewRequest("GET", "/oauth/callback?error=authorization_failed", nil))
	body := rec.Body.String()
	if !strings.Contains(body, "授权失败") {
		t.Fatal("body missing error copy")
	}
	if !strings.Contains(body, "authorization_failed") {
		t.Fatal("body must include the error code for support")
	}
}

func TestApplinkFallbackPageEmptyParamsCopy(t *testing.T) {
	a := &app{}
	rec := httptest.NewRecorder()
	a.applinkFallbackPage(rec, httptest.NewRequest("GET", "/oauth/callback", nil))
	body := rec.Body.String()
	if !strings.Contains(body, "链接无效") {
		t.Fatal("body missing invalid-link copy")
	}
}

func TestApplinkFallbackServedBeforeSessionAuth(t *testing.T) {
	// The full ServeHTTP chain must answer /oauth/callback with the landing
	// page (200), not the session gate (401 session_required).
	a := &app{}
	rec := httptest.NewRecorder()
	a.ServeHTTP(rec, httptest.NewRequest("GET", "/oauth/callback?ticket=probe", nil))
	if rec.Code == 401 {
		t.Fatal("/oauth/callback fell through to the session gate")
	}
	if rec.Code != 200 {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
}
