package main

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

type wechatRoundTrip func(*http.Request) (*http.Response, error)

func (f wechatRoundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestWeChatProviderExchangeAndFailureMapping(t *testing.T) {
	for _, tc := range []struct {
		name string
		body string
		want error
	}{
		{"success", `{"openid":"openid-secret","unionid":"unionid-secret","session_key":"session-secret"}`, nil},
		{"expired", `{"errcode":40029,"errmsg":"code expired"}`, errWeChatCodeInvalid},
		{"replayed", `{"errcode":40163,"errmsg":"used"}`, errWeChatCodeInvalid},
		{"bad credential", `{"errcode":40125,"errmsg":"secret"}`, errWeChatUnavailable},
		{"missing session", `{"openid":"openid-secret"}`, errWeChatUnavailable},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := &http.Client{Transport: wechatRoundTrip(func(r *http.Request) (*http.Response, error) {
				if r.Method != http.MethodGet || r.URL.Scheme != "https" || r.URL.Host != "api.weixin.qq.com" || r.URL.Path != "/sns/jscode2session" || r.URL.Query().Get("appid") != "app-id" || r.URL.Query().Get("secret") != "app-secret" || r.URL.Query().Get("js_code") != "wx-code" || r.URL.Query().Get("grant_type") != "authorization_code" {
					t.Error("incorrect WeChat exchange request")
				}
				return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(tc.body)), Header: make(http.Header)}, nil
			})}
			provider := newWeChatProvider(&wechatConfig{AppID: "app-id", Secret: "app-secret"}, client)
			identity, err := provider.Exchange(context.Background(), "wx-code")
			if !errors.Is(err, tc.want) {
				t.Fatalf("exchange error = %v; want %v", err, tc.want)
			}
			if tc.want == nil && (identity.OpenID != "openid-secret" || identity.UnionID != "unionid-secret") {
				t.Fatal("missing verified WeChat identity")
			}
		})
	}
}

func TestWeChatConfigurationIsOptionalAndComplete(t *testing.T) {
	for _, tc := range []struct {
		id, secret          string
		configured, invalid bool
	}{
		{"", "", false, false}, {"id", "", false, true}, {"", "secret", false, true}, {"id", "secret", true, false},
	} {
		cfg, err := loadWeChatConfig(func(name string) string {
			if name == "WECHAT_MINIPROGRAM_APP_ID" {
				return tc.id
			}
			return tc.secret
		})
		if (cfg != nil) != tc.configured || (err != nil) != tc.invalid {
			t.Fatalf("config configured=%t invalid=%t, want %t %t", cfg != nil, err != nil, tc.configured, tc.invalid)
		}
	}
}
