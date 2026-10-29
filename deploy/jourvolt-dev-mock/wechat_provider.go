package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
)

var (
	errWeChatCodeInvalid = errors.New("wechat_code_invalid")
	errWeChatUnavailable = errors.New("wechat_provider_unavailable")
)

type wechatIdentity struct {
	OpenID  string
	UnionID string
}

type wechatCodeProvider interface {
	ProviderID() string
	Exchange(context.Context, string) (wechatIdentity, error)
}

type wechatConfig struct {
	AppID  string
	Secret string
}

func loadWeChatConfig(getenv func(string) string) (*wechatConfig, error) {
	id := strings.TrimSpace(getenv("WECHAT_MINIPROGRAM_APP_ID"))
	secret := strings.TrimSpace(getenv("WECHAT_MINIPROGRAM_APP_SECRET"))
	if id == "" && secret == "" {
		return nil, nil
	}
	if id == "" || secret == "" {
		return nil, errors.New("incomplete WeChat configuration")
	}
	return &wechatConfig{AppID: id, Secret: secret}, nil
}

type wechatHTTPProvider struct {
	config *wechatConfig
	client *http.Client
}

func newWeChatProvider(config *wechatConfig, client *http.Client) *wechatHTTPProvider {
	return &wechatHTTPProvider{config: config, client: credentialHTTPClient(client)}
}

func (p *wechatHTTPProvider) ProviderID() string {
	return "wechat:miniprogram:" + p.config.AppID
}

// WeChat consumes js_code itself, verifies its expiry, and supplies openid.
// session_key is checked but never persisted, logged, or returned to clients.
func (p *wechatHTTPProvider) Exchange(ctx context.Context, code string) (wechatIdentity, error) {
	query := url.Values{
		"appid":      {p.config.AppID},
		"secret":     {p.config.Secret},
		"js_code":    {code},
		"grant_type": {"authorization_code"},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://api.weixin.qq.com/sns/jscode2session?"+query.Encode(), nil)
	if err != nil {
		return wechatIdentity{}, errWeChatUnavailable
	}
	response, err := p.client.Do(req)
	if err != nil {
		return wechatIdentity{}, errWeChatUnavailable
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return wechatIdentity{}, errWeChatUnavailable
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 8193))
	if err != nil || len(body) > 8192 {
		return wechatIdentity{}, errWeChatUnavailable
	}
	var payload struct {
		OpenID     string `json:"openid"`
		UnionID    string `json:"unionid"`
		SessionKey string `json:"session_key"`
		ErrorCode  int    `json:"errcode"`
	}
	if json.Unmarshal(body, &payload) != nil {
		return wechatIdentity{}, errWeChatUnavailable
	}
	if payload.ErrorCode == 40029 || payload.ErrorCode == 40163 {
		return wechatIdentity{}, errWeChatCodeInvalid
	}
	if payload.ErrorCode != 0 || payload.OpenID == "" || payload.SessionKey == "" {
		return wechatIdentity{}, errWeChatUnavailable
	}
	return wechatIdentity{OpenID: payload.OpenID, UnionID: payload.UnionID}, nil
}
