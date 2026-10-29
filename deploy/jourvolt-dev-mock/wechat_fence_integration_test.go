package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func wechatVersion(t *testing.T, f *wechatFixture, token string) (bool, string) {
	t.Helper()
	w := f.request("GET", "/v1/auth/wechat/binding", token, "")
	var status struct {
		Bound   bool   `json:"bound"`
		Version string `json:"binding_version"`
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &status) != nil || status.Version == "" {
		t.Fatalf("missing binding version: %d %s", w.Code, w.Body.String())
	}
	return status.Bound, status.Version
}

func wechatRefresh(t *testing.T, f *wechatFixture, current session) session {
	t.Helper()
	w := f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+current.RefreshToken+`"}`)
	var data struct {
		Access  string `json:"access_token"`
		Refresh string `json:"refresh_token"`
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &data) != nil || data.Access == "" || data.Refresh == "" {
		t.Fatalf("refresh = %d %s", w.Code, w.Body.String())
	}
	return session{AccessToken: data.Access, RefreshToken: data.Refresh, UserID: current.UserID}
}

func pausedWeChatRequest(t *testing.T, f *wechatFixture, action, token, code, version string, identity wechatIdentity) (<-chan *httptest.ResponseRecorder, func()) {
	t.Helper()
	f.provider.add(code, identity)
	entry := f.provider.codes[code]
	entry.entered, entry.release = make(chan struct{}), make(chan struct{})
	var once sync.Once
	release := func() { once.Do(func() { close(entry.release) }) }
	t.Cleanup(release)
	body := `{"code":"` + code + `"}`
	if action == "bind" {
		body = `{"code":"` + code + `","binding_version":"` + version + `"}`
	}
	result := make(chan *httptest.ResponseRecorder, 1)
	go func() { result <- f.request("POST", "/v1/auth/wechat/"+action, token, body) }()
	select {
	case <-entry.entered:
	case <-time.After(10 * time.Second):
		t.Fatal("WeChat provider did not enter")
	}
	return result, release
}

func awaitWeChatResponse(t *testing.T, result <-chan *httptest.ResponseRecorder) *httptest.ResponseRecorder {
	t.Helper()
	select {
	case w := <-result:
		return w
	case <-time.After(10 * time.Second):
		t.Fatal("WeChat request did not finish")
		return nil
	}
}

func TestWeChatRealPostgresLogoutAfterCommittedRefreshRevokesSameSession(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "logout-after-refresh"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "logout-after-bind", id), 200, "bound")
	old := parseWeChatSession(t, f.code(t, "restore", "", "logout-after-old", id), f.users[0].UserID)
	other := parseWeChatSession(t, f.code(t, "restore", "", "logout-after-other", id), f.users[0].UserID)
	rotated := wechatRefresh(t, f, old)
	if f.request("GET", "/v1/vehicles", old.AccessToken, "").Code != 401 {
		t.Fatal("old access must not authenticate")
	}
	if f.request("GET", "/v1/auth/wechat/binding", old.AccessToken, "").Code != 401 {
		t.Fatal("logout alias must not authenticate binding status")
	}
	if f.request("POST", "/v1/auth/wechat/unbind", old.AccessToken, "").Code != 401 {
		t.Fatal("logout alias must not authorize unbind")
	}
	if f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+old.AccessToken+`"}`).Code != 401 {
		t.Fatal("old access must not refresh")
	}
	inFlight, release := pausedWeChatRequest(t, f, "restore", "", "logout-after-inflight", "", id)
	if w := f.request("POST", "/v1/session/logout", old.AccessToken, ""); w.Code != 200 {
		t.Fatalf("logout: %d", w.Code)
	}
	if f.request("GET", "/v1/vehicles", rotated.AccessToken, "").Code != 401 {
		t.Fatal("rotated access survived delayed logout")
	}
	if f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+rotated.RefreshToken+`"}`).Code != 401 {
		t.Fatal("rotated refresh survived delayed logout")
	}
	if f.request("GET", "/v1/vehicles", other.AccessToken, "").Code != 200 {
		t.Fatal("logout revoked another device")
	}
	release()
	requireWeChatStatus(t, awaitWeChatResponse(t, inFlight), 401, "session_required")
	newFlight, newRelease := pausedWeChatRequest(t, f, "restore", "", "logout-after-repeated", "", id)
	if w := f.request("POST", "/v1/session/logout", old.AccessToken, ""); w.Code != 200 {
		t.Fatalf("repeated alias logout: %d", w.Code)
	}
	newRelease()
	parseWeChatSession(t, awaitWeChatResponse(t, newFlight), f.users[0].UserID)
}

func TestWeChatRealPostgresExpiredLogoutFencesRestoreOnlyOnce(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "expired-logout"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "expired-bind", id), 200, "bound")
	expired := parseWeChatSession(t, f.code(t, "restore", "", "expired-session", id), f.users[0].UserID)
	other := parseWeChatSession(t, f.code(t, "restore", "", "expired-other", id), f.users[0].UserID)
	if _, err := f.store.pool.Exec(context.Background(), `UPDATE jourvolt_sessions SET access_expires_at=now()-interval '1 minute' WHERE access_hash=$1`, hashToken(expired.AccessToken)); err != nil {
		t.Fatal(err)
	}
	result, release := pausedWeChatRequest(t, f, "restore", "", "expired-inflight", "", id)
	if w := f.request("POST", "/v1/session/logout", expired.AccessToken, ""); w.Code != 200 {
		t.Fatalf("logout = %d", w.Code)
	}
	if f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+expired.RefreshToken+`"}`).Code != 401 {
		t.Fatal("expired session remained refreshable")
	}
	release()
	requireWeChatStatus(t, awaitWeChatResponse(t, result), 401, "session_required")
	if f.request("GET", "/v1/vehicles", other.AccessToken, "").Code != 200 {
		t.Fatal("expired logout revoked another device")
	}
	newResult, newRelease := pausedWeChatRequest(t, f, "restore", "", "after-first-logout", "", id)
	if w := f.request("POST", "/v1/session/logout", expired.AccessToken, ""); w.Code != 200 {
		t.Fatalf("duplicate logout = %d", w.Code)
	}
	newRelease()
	parseWeChatSession(t, awaitWeChatResponse(t, newResult), f.users[0].UserID)
}

func TestWeChatRealPostgresBindingVersionRejectsDelayedRequest(t *testing.T) {
	f := newWeChatFixture(t)
	bound, version := wechatVersion(t, f, f.users[0].AccessToken)
	if bound || version != "0" {
		t.Fatalf("initial binding = %t version=%s", bound, version)
	}
	for i := 0; i < 2; i++ {
		requireWeChatStatus(t, f.request("POST", "/v1/auth/wechat/unbind", f.users[0].AccessToken, ""), 200, "unbound")
	}
	if bound, current := wechatVersion(t, f, f.users[0].AccessToken); bound || current != "2" {
		t.Fatalf("after unbind bound=%t version=%s", bound, current)
	}
	if _, other := wechatVersion(t, f, f.users[1].AccessToken); other != "0" {
		t.Fatalf("other account version=%s", other)
	}
	if w := f.request("POST", "/v1/auth/wechat/unbind", "invalid-session", ""); w.Code != 401 {
		t.Fatalf("invalid unbind: %d", w.Code)
	}
	if _, current := wechatVersion(t, f, f.users[0].AccessToken); current != "2" {
		t.Fatalf("unauthorized unbind changed version to %s", current)
	}
	restartWeChatFixture(t, f)
	if bound, current := wechatVersion(t, f, f.users[0].AccessToken); bound || current != "2" {
		t.Fatalf("version not persisted after restart: bound=%t version=%s", bound, current)
	}
	id := wechatIdentity{OpenID: "delayed-bind"}
	f.provider.add("delayed-arrival", id)
	w := f.request("POST", "/v1/auth/wechat/bind", f.users[0].AccessToken, `{"code":"delayed-arrival","binding_version":"0"}`)
	requireWeChatStatus(t, w, 409, "wechat_bind_expired")
	if bound, _ := wechatVersion(t, f, f.users[0].AccessToken); bound {
		t.Fatal("stale request recreated binding")
	}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "fresh-version", id), 200, "bound")
}

func TestWeChatRealPostgresUnbindRejectsInFlightBind(t *testing.T) {
	for _, initiallyBound := range []bool{false, true} {
		t.Run(fmt.Sprintf("bound_%t", initiallyBound), func(t *testing.T) {
			f := newWeChatFixture(t)
			id := wechatIdentity{OpenID: "pending-bind"}
			if initiallyBound {
				requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "existing-bind", id), 200, "bound")
			}
			result, release := pausedWeChatRequest(t, f, "bind", f.users[0].AccessToken, "pending-bind-code", "0", id)
			requireWeChatStatus(t, f.request("POST", "/v1/auth/wechat/unbind", f.users[0].AccessToken, ""), 200, "unbound")
			release()
			requireWeChatStatus(t, awaitWeChatResponse(t, result), 409, "wechat_bind_expired")
			if bound, version := wechatVersion(t, f, f.users[0].AccessToken); bound || version != "1" {
				t.Fatalf("late bind succeeded, bound=%t version=%s", bound, version)
			}
			requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "new-bind", id), 200, "bound")
		})
	}
}

func TestWeChatRealPostgresBindingVersionValidation(t *testing.T) {
	for _, body := range []string{`{"code":"c"}`, `{"code":"c","binding_version":""}`, `{"code":"c","binding_version":0}`, `{"code":"c","binding_version":"-1"}`, `{"code":"c","binding_version":"x"}`, `{"code":"c","binding_version":"9223372036854775808"}`} {
		t.Run(body, func(t *testing.T) {
			f := newWeChatFixture(t)
			f.provider.add("c", wechatIdentity{OpenID: "invalid-version"})
			w := f.request("POST", "/v1/auth/wechat/bind", f.users[0].AccessToken, body)
			requireWeChatStatus(t, w, 400, "invalid_wechat_request")
			if f.provider.calls != 0 {
				t.Fatal("invalid version reached provider")
			}
		})
	}
}

func restartWeChatFixture(t *testing.T, f *wechatFixture) {
	t.Helper()
	previous := f.app.wechat.grants.(*teslaTokenManager)
	providerID := f.provider.id
	f.store.close()
	reopened, err := openStore(context.Background(), os.Getenv("JOURVOLT_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	f.store = reopened
	f.provider = &wechatTestProvider{id: providerID, codes: map[string]*wechatTestCode{}}
	grants := &teslaTokenManager{store: reopened, cipher: previous.cipher, config: previous.config, client: previous.client}
	f.app = &app{store: reopened, wechat: &wechatService{provider: f.provider, grants: grants}, provider: wechatTestVehicles{store: reopened}}
}

func TestWeChatRealPostgresClosedStoreReopensExistingAccount(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "restart-original"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "restart-bind", id), 200, "bound")
	parseWeChatSession(t, f.code(t, "restore", "", "restart-used", id), f.users[0].UserID)
	restartWeChatFixture(t, f)
	f.provider.add("restart-used", id)
	requireWeChatStatus(t, f.request("POST", "/v1/auth/wechat/restore", "", `{"code":"restart-used"}`), 401, "wechat_code_invalid")
	if f.provider.calls != 0 {
		t.Fatal("replayed code reached restarted provider")
	}
	s := parseWeChatSession(t, f.code(t, "restore", "", "restart-fresh", id), f.users[0].UserID)
	w := f.request("GET", "/v1/vehicles", s.AccessToken, "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), "car-0") || strings.Contains(w.Body.String(), "car-1") {
		t.Fatalf("restarted account vehicles = %d %s", w.Code, w.Body.String())
	}
}

func TestWeChatRealPostgresLogoutAliasExpiresAndCleanupIsBounded(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "alias-expiry"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "expiry-bind", id), 200, "bound")
	old := parseWeChatSession(t, f.code(t, "restore", "", "expiry-session", id), f.users[0].UserID)
	rotated := wechatRefresh(t, f, old)
	if _, err := f.store.pool.Exec(context.Background(), `UPDATE jourvolt_logout_aliases SET expires_at=clock_timestamp()-interval '1 second' WHERE access_hash=$1`, hashToken(old.AccessToken)); err != nil {
		t.Fatal(err)
	}
	inFlight, release := pausedWeChatRequest(t, f, "restore", "", "expiry-inflight", "", id)
	if w := f.request("POST", "/v1/session/logout", old.AccessToken, ""); w.Code != 200 {
		t.Fatalf("expired alias logout: %d", w.Code)
	}
	if f.request("GET", "/v1/vehicles", rotated.AccessToken, "").Code != 200 {
		t.Fatal("expired alias revoked session")
	}
	release()
	parseWeChatSession(t, awaitWeChatResponse(t, inFlight), f.users[0].UserID)
	_ = wechatRefresh(t, f, rotated)
	var remaining int
	if err := f.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_logout_aliases WHERE access_hash=$1`, hashToken(old.AccessToken)).Scan(&remaining); err != nil || remaining != 0 {
		t.Fatalf("expired alias cleanup: %d %v", remaining, err)
	}
}

func TestWeChatRealPostgresLogoutAliasCleanupLimitsBatch(t *testing.T) {
	f := newWeChatFixture(t)
	var sessionID int64
	if err := f.store.pool.QueryRow(context.Background(), `SELECT id FROM jourvolt_sessions WHERE access_hash=$1`, hashToken(f.users[0].AccessToken)).Scan(&sessionID); err != nil {
		t.Fatal(err)
	}
	seed, err := randomToken()
	if err != nil {
		t.Fatal(err)
	}
	prefix := "expired-batch-" + seed + "-"
	if _, err := f.store.pool.Exec(context.Background(), `INSERT INTO jourvolt_logout_aliases(access_hash,session_id,expires_at)
SELECT $1 || n::text,$2,clock_timestamp()-interval '1 minute' FROM generate_series(1,130) n`, prefix, sessionID); err != nil {
		t.Fatal(err)
	}
	rotated := wechatRefresh(t, f, f.users[0])
	var remaining int
	if err := f.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_logout_aliases WHERE access_hash LIKE $1`, prefix+"%").Scan(&remaining); err != nil || remaining != 2 {
		t.Fatalf("first cleanup remaining=%d error=%v", remaining, err)
	}
	_ = wechatRefresh(t, f, rotated)
	if err := f.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_logout_aliases WHERE access_hash LIKE $1`, prefix+"%").Scan(&remaining); err != nil || remaining != 0 {
		t.Fatalf("second cleanup remaining=%d error=%v", remaining, err)
	}
}

func TestWeChatRealPostgresLogoutAliasSurvivesMultipleRotationsAndRestart(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "alias-restart"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "alias-restart-bind", id), 200, "bound")
	old := parseWeChatSession(t, f.code(t, "restore", "", "alias-restart-old", id), f.users[0].UserID)
	other := parseWeChatSession(t, f.code(t, "restore", "", "alias-restart-other", id), f.users[0].UserID)
	first := wechatRefresh(t, f, old)
	latest := wechatRefresh(t, f, first)
	restartWeChatFixture(t, f)
	if f.request("GET", "/v1/vehicles", old.AccessToken, "").Code != 401 {
		t.Fatal("old alias authenticates after restart")
	}
	if w := f.request("POST", "/v1/session/logout", old.AccessToken, ""); w.Code != 200 {
		t.Fatalf("restart logout: %d", w.Code)
	}
	if f.request("GET", "/v1/vehicles", latest.AccessToken, "").Code != 401 {
		t.Fatal("twice-rotated session survived restart logout")
	}
	if f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+latest.RefreshToken+`"}`).Code != 401 {
		t.Fatal("twice-rotated refresh survived restart logout")
	}
	if f.request("GET", "/v1/vehicles", other.AccessToken, "").Code != 200 {
		t.Fatal("restart logout revoked other device")
	}
}

func TestWeChatRealPostgresLogoutAliasCascadeOnUnbindAndAccountDelete(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "alias-cascade"}
	tokens := wechatRefresh(t, f, f.users[0])
	requireWeChatStatus(t, f.code(t, "bind", tokens.AccessToken, "cascade-bind", id), 200, "bound")
	wx := parseWeChatSession(t, f.code(t, "restore", "", "cascade-restore", id), f.users[0].UserID)
	_ = wechatRefresh(t, f, wx)
	var wxCount, teslaCount int
	count := func() {
		if err := f.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_logout_aliases WHERE access_hash=$1`, hashToken(wx.AccessToken)).Scan(&wxCount); err != nil {
			t.Fatal(err)
		}
		if err := f.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_logout_aliases WHERE access_hash=$1`, hashToken(f.users[0].AccessToken)).Scan(&teslaCount); err != nil {
			t.Fatal(err)
		}
	}
	count()
	if wxCount != 1 || teslaCount != 1 {
		t.Fatalf("missing aliases: wx=%d tesla=%d", wxCount, teslaCount)
	}
	requireWeChatStatus(t, f.request("POST", "/v1/auth/wechat/unbind", tokens.AccessToken, ""), 200, "unbound")
	count()
	if wxCount != 0 || teslaCount != 1 {
		t.Fatalf("unbind cascade: wx=%d tesla=%d", wxCount, teslaCount)
	}
	if w := f.request("DELETE", "/v1/account", tokens.AccessToken, ""); w.Code != 200 {
		t.Fatalf("delete account = %d %s", w.Code, w.Body.String())
	}
	count()
	if teslaCount != 0 {
		t.Fatal("account deletion kept session aliases")
	}
	var versions int
	if err := f.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_wechat_binding_versions WHERE user_id=$1`, f.users[0].UserID).Scan(&versions); err != nil || versions != 0 {
		t.Fatalf("account deletion kept version: %d %v", versions, err)
	}
	if f.request("GET", "/v1/vehicles", f.users[1].AccessToken, "").Code != 200 {
		t.Fatal("account delete affected another user")
	}
}
