package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func TestWeChatConfigIsAllOrNothing(t *testing.T) {
	if config, err := loadWeChatConfig(func(string) string { return "" }); err != nil || config != nil {
		t.Fatalf("empty WeChat config = %#v, %v; want nil, nil", config, err)
	}
	values := map[string]string{"WECHAT_APP_ID": "wx-app"}
	if _, err := loadWeChatConfig(func(name string) string { return values[name] }); err == nil {
		t.Fatal("partial WeChat config must fail closed")
	}
	values["WECHAT_APP_SECRET"] = "secret-value"
	config, err := loadWeChatConfig(func(name string) string { return values[name] })
	if err != nil || config == nil || config.SessionURL != defaultWeChatSessionURL {
		t.Fatalf("complete WeChat config = %#v, %v", config, err)
	}
}

func TestWeChatSessionFailsClosedWhenBackendIsNotConfigured(t *testing.T) {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/v1/auth/wechat/session", strings.NewReader(`{"code":"code"}`))
	(&app{}).wechatSession(recorder, request)
	if recorder.Code != http.StatusServiceUnavailable || !strings.Contains(recorder.Body.String(), `"wechat_auth_not_configured"`) {
		t.Fatalf("response = %d %s", recorder.Code, recorder.Body.String())
	}
}

func TestWeChatCodeExchangeUsesServerEndpointAndRejectsProviderError(t *testing.T) {
	var seen url.Values
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = r.URL.Query()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"openid":"openid-1","session_key":"never-persist"}`))
	}))
	defer server.Close()
	auth := newWeChatAuth(&wechatConfig{AppID: "wx-app", AppSecret: "server-secret", SessionURL: server.URL}, server.Client())
	result, err := auth.exchangeCode(context.Background(), "code-1")
	if err != nil || result.OpenID != "openid-1" {
		t.Fatalf("exchange result = %#v, %v", result, err)
	}
	if seen.Get("appid") != "wx-app" || seen.Get("secret") != "server-secret" || seen.Get("js_code") != "code-1" || seen.Get("grant_type") != "authorization_code" {
		t.Fatalf("provider query = %#v", seen)
	}

	errorServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"errcode":40029,"errmsg":"invalid code"}`))
	}))
	defer errorServer.Close()
	auth.config.SessionURL = errorServer.URL
	if _, err := auth.exchangeCode(context.Background(), "expired"); err != errWeChatCodeRejected {
		t.Fatalf("provider error = %v, want %v", err, errWeChatCodeRejected)
	}
}

func TestWeChatCallbackChannelAndJSONStringAreEscaped(t *testing.T) {
	got := addAppLinkChannel("https://auth.example.com/oauth/callback?ticket=one", "wechat")
	if !strings.Contains(got, "channel=wechat") {
		t.Fatalf("channel URL = %q", got)
	}
	if strings.Contains(jsonString("</script>"), "</script>") {
		t.Fatal("JSON string contains an unescaped script terminator")
	}
}

func TestWeChatAuthorizationUsesOwnedBridgeURL(t *testing.T) {
	oauth := &teslaOAuth{config: &teslaConfig{AppLinkURI: "https://auth.example.com/oauth/callback"}}
	got := oauth.wechatAuthorizationURL("state-value")
	parsed, err := url.Parse(got)
	if err != nil || parsed.Host != "auth.example.com" || parsed.Path != "/oauth/wechat/authorize" || parsed.Query().Get("state") != "state-value" {
		t.Fatalf("bridge URL = %q, %v", got, err)
	}
}
