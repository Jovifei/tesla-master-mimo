package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

type wechatTestCode struct {
	identity wechatIdentity
	expired  bool
	used     bool
	entered  chan struct{}
	release  chan struct{}
}

type wechatTestProvider struct {
	id    string
	mu    sync.Mutex
	codes map[string]*wechatTestCode
	calls int
}

func (p *wechatTestProvider) ProviderID() string { return p.id }
func (p *wechatTestProvider) add(code string, identity wechatIdentity) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.codes[code] = &wechatTestCode{identity: identity}
}
func (p *wechatTestProvider) Exchange(ctx context.Context, code string) (wechatIdentity, error) {
	p.mu.Lock()
	p.calls++
	entry := p.codes[code]
	if entry == nil || entry.used || entry.expired {
		p.mu.Unlock()
		return wechatIdentity{}, errWeChatCodeInvalid
	}
	entry.used = true
	p.mu.Unlock()
	if entry.entered != nil {
		close(entry.entered)
		select {
		case <-entry.release:
		case <-ctx.Done():
			return wechatIdentity{}, errWeChatUnavailable
		}
	}
	return entry.identity, nil
}

type wechatTestVehicles struct{ store *store }

func (p wechatTestVehicles) Vehicles(ctx context.Context, userID string) ([]vehicle, error) {
	rows, err := p.store.pool.Query(ctx, `SELECT id,display_name FROM jourvolt_vehicles WHERE user_id=$1 ORDER BY id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var vehicles []vehicle
	for rows.Next() {
		var v vehicle
		if err := rows.Scan(&v.ID, &v.DisplayName); err != nil {
			return nil, err
		}
		vehicles = append(vehicles, v)
	}
	return vehicles, rows.Err()
}
func (p wechatTestVehicles) Status(ctx context.Context, userID string, id int) (vehicleStatus, error) {
	_, err := p.store.fleetVehicle(ctx, userID, id)
	return vehicleStatus{}, err
}

type wechatFixture struct {
	app      *app
	store    *store
	provider *wechatTestProvider
	users    []session
}

func newWeChatFixture(t *testing.T) *wechatFixture {
	t.Helper()
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set: real PostgreSQL integration not run")
	}
	ctx := context.Background()
	s, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	seed, err := randomToken()
	if err != nil {
		t.Fatal(err)
	}
	cipher, err := newTokenCipher([]byte("0123456789abcdef0123456789abcdef"))
	if err != nil {
		t.Fatal(err)
	}
	access, _ := cipher.encrypt("synthetic-tesla-access")
	refresh, _ := cipher.encrypt("synthetic-tesla-refresh")
	p := &wechatTestProvider{id: "wechat:miniprogram:test-" + seed, codes: map[string]*wechatTestCode{}}
	client := &http.Client{Transport: wechatRoundTrip(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 400, Body: io.NopCloser(strings.NewReader(`{"error":"invalid_grant"}`)), Header: make(http.Header)}, nil
	})}
	grants := &teslaTokenManager{store: s, cipher: cipher, config: &teslaConfig{TokenEndpoint: "https://test.invalid/token"}, client: client}
	f := &wechatFixture{store: s, provider: p, app: &app{store: s, wechat: &wechatService{provider: p, grants: grants}, provider: wechatTestVehicles{store: s}}}
	for i := 0; i < 2; i++ {
		id := fmt.Sprintf("wechat-test-%s-%d", seed, i)
		if err := s.ensureUser(ctx, id); err != nil {
			t.Fatal(err)
		}
		_, err := s.pool.Exec(ctx, `INSERT INTO jourvolt_tesla_tokens(user_id,access_ciphertext,refresh_ciphertext,access_expires_at) VALUES ($1,$2,$3,now()+interval '1 hour')`, id, access, refresh)
		if err != nil {
			t.Fatal(err)
		}
		login, err := s.createSession(ctx, id)
		if err != nil {
			t.Fatal(err)
		}
		f.users = append(f.users, login)
		if _, err := s.upsertFleetVehicle(ctx, id, "vehicle", "synthetic-vin-cipher", fmt.Sprintf("car-%d", i), "online"); err != nil {
			t.Fatal(err)
		}
	}
	t.Cleanup(func() {
		for _, u := range f.users {
			_, _ = f.store.pool.Exec(ctx, `DELETE FROM jourvolt_users WHERE id=$1`, u.UserID)
		}
		_, _ = f.store.pool.Exec(ctx, `DELETE FROM jourvolt_wechat_codes WHERE provider=$1`, f.provider.id)
		f.store.close()
	})
	return f
}

func (f *wechatFixture) request(method, path, token, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	f.app.ServeHTTP(w, r)
	return w
}
func (f *wechatFixture) code(t *testing.T, action, token, code string, identity wechatIdentity) *httptest.ResponseRecorder {
	t.Helper()
	f.provider.add(code, identity)
	body := `{"code":"` + code + `"}`
	if action == "bind" {
		var status struct {
			Version string `json:"binding_version"`
		}
		w := f.request(http.MethodGet, "/v1/auth/wechat/binding", token, "")
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &status) != nil {
			t.Fatalf("binding status: %d %s", w.Code, w.Body.String())
		}
		if status.Version == "" {
			status.Version = "0"
		}
		body = `{"code":"` + code + `","binding_version":"` + status.Version + `"}`
	}
	return f.request(http.MethodPost, "/v1/auth/wechat/"+action, token, body)
}
func requireWeChatStatus(t *testing.T, w *httptest.ResponseRecorder, status int, content string) {
	t.Helper()
	if w.Code != status || !strings.Contains(w.Body.String(), content) {
		t.Fatalf("response = %d %s, want %d %s", w.Code, w.Body.String(), status, content)
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("missing no-store")
	}
}
func parseWeChatSession(t *testing.T, w *httptest.ResponseRecorder, userID string) session {
	t.Helper()
	var payload struct {
		Access  string `json:"access_token"`
		Refresh string `json:"refresh_token"`
		User    struct {
			ID string `json:"id"`
		} `json:"user"`
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &payload) != nil || payload.Access == "" || payload.Refresh == "" || payload.User.ID != userID {
		t.Fatalf("invalid session response = %d %s", w.Code, w.Body.String())
	}
	return session{AccessToken: payload.Access, RefreshToken: payload.Refresh, UserID: payload.User.ID}
}

func TestWeChatRealPostgresBindRestoreReplayAndExpiry(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "synthetic-openid", UnionID: "synthetic-unionid"}
	requireWeChatStatus(t, f.code(t, "restore", "", "initial-unbound", id), 404, "wechat_not_bound")
	requireWeChatStatus(t, f.request("POST", "/v1/auth/wechat/restore", "", `{"code":"initial-unbound"}`), 401, "wechat_code_invalid")
	requireWeChatStatus(t, f.request("POST", "/v1/auth/wechat/bind", "", `{"code":"unauth"}`), 401, "session_required")
	requireWeChatStatus(t, f.request("GET", "/v1/auth/wechat/binding", f.users[0].AccessToken, ""), 200, `"bound":false`)
	w := f.code(t, "bind", f.users[0].AccessToken, "bind-1", id)
	requireWeChatStatus(t, w, 200, `"status":"bound"`)
	requireWeChatStatus(t, f.request("GET", "/v1/auth/wechat/binding", f.users[0].AccessToken, ""), 200, `"bound":true`)
	restored := f.code(t, "restore", "", "restore-1", id)
	grant := parseWeChatSession(t, restored, f.users[0].UserID)
	for _, secret := range []string{id.OpenID, id.UnionID, "session_key", "synthetic-tesla-access", "synthetic-tesla-refresh"} {
		if strings.Contains(restored.Body.String(), secret) {
			t.Fatalf("response leaks %s", secret)
		}
	}
	if user, err := f.store.userForAccess(context.Background(), grant.AccessToken); err != nil || user != f.users[0].UserID {
		t.Fatal("restored session is not scoped to Tesla user")
	}
	var openHash, unionHash, codeHash string
	if err := f.store.pool.QueryRow(context.Background(), `SELECT openid_hash,unionid_hash FROM jourvolt_wechat_bindings WHERE user_id=$1`, f.users[0].UserID).Scan(&openHash, &unionHash); err != nil {
		t.Fatal(err)
	}
	if err := f.store.pool.QueryRow(context.Background(), `SELECT code_hash FROM jourvolt_wechat_codes WHERE provider=$1 AND code_hash=$2`, f.provider.id, hashToken("wechat:code:"+f.provider.id+":restore-1")).Scan(&codeHash); err != nil {
		t.Fatal(err)
	}
	if openHash == id.OpenID || unionHash == id.UnionID || codeHash == "restore-1" {
		t.Fatal("raw identity/code was persisted")
	}
	requireWeChatStatus(t, f.request("POST", "/v1/auth/wechat/restore", "", `{"code":"restore-1"}`), 401, "wechat_code_invalid")
	f.provider.add("expired", id)
	f.provider.codes["expired"].expired = true
	requireWeChatStatus(t, f.request("POST", "/v1/auth/wechat/restore", "", `{"code":"expired"}`), 401, "wechat_code_invalid")
}

func TestWeChatRealPostgresConflictsUnbindAndAccountSwitch(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "person-one", UnionID: "union-one"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "bind-a", id), 200, "bound")
	old := parseWeChatSession(t, f.code(t, "restore", "", "old", id), f.users[0].UserID)
	rotated := f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+old.RefreshToken+`"}`)
	var rotation struct {
		Access  string `json:"access_token"`
		Refresh string `json:"refresh_token"`
	}
	if rotated.Code != 200 || json.Unmarshal(rotated.Body.Bytes(), &rotation) != nil || rotation.Access == "" || rotation.Refresh == "" {
		t.Fatalf("rotation = %d %s", rotated.Code, rotated.Body.String())
	}
	requireWeChatStatus(t, f.code(t, "bind", f.users[1].AccessToken, "bind-b-same", id), 409, "wechat_binding_conflict")
	requireWeChatStatus(t, f.code(t, "bind", f.users[1].AccessToken, "bind-b-union", wechatIdentity{OpenID: "another-open", UnionID: id.UnionID}), 409, "wechat_binding_conflict")
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "bind-a-new", wechatIdentity{OpenID: "another-person"}), 409, "wechat_binding_conflict")
	requireWeChatStatus(t, f.request("GET", "/v1/auth/wechat/binding", f.users[1].AccessToken, ""), 200, `"bound":false`)
	requireWeChatStatus(t, f.request("POST", "/v1/auth/wechat/unbind", f.users[0].AccessToken, ""), 200, "unbound")
	vehicle := f.request("GET", "/v1/vehicles", rotation.Access, "")
	if vehicle.Code != 401 {
		t.Fatalf("unbound rotated session retained vehicle access: %d", vehicle.Code)
	}
	if f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+rotation.Refresh+`"}`).Code != 401 {
		t.Fatal("unbound refresh still works")
	}
	if f.request("GET", "/v1/vehicles", f.users[0].AccessToken, "").Code != 200 {
		t.Fatal("Tesla-origin session was revoked")
	}
	requireWeChatStatus(t, f.code(t, "bind", f.users[1].AccessToken, "bind-b-after-unbind", id), 200, "bound")
	newSession := parseWeChatSession(t, f.code(t, "restore", "", "new", id), f.users[1].UserID)
	vehicles := f.request("GET", "/v1/vehicles", newSession.AccessToken, "")
	if vehicles.Code != 200 || !strings.Contains(vehicles.Body.String(), "car-1") || strings.Contains(vehicles.Body.String(), "car-0") {
		t.Fatalf("cross-account vehicles: %d %s", vehicles.Code, vehicles.Body.String())
	}
}

func TestWeChatRealPostgresGrantFailureRequiresTeslaLogin(t *testing.T) {
	for _, scenario := range []string{"missing", "revoked"} {
		t.Run(scenario, func(t *testing.T) {
			f := newWeChatFixture(t)
			id := wechatIdentity{OpenID: "grant-" + scenario}
			requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "grant-bind", id), 200, "bound")
			if scenario == "missing" {
				_, _ = f.store.pool.Exec(context.Background(), `DELETE FROM jourvolt_tesla_tokens WHERE user_id=$1`, f.users[0].UserID)
			} else {
				_, _ = f.store.pool.Exec(context.Background(), `UPDATE jourvolt_tesla_tokens SET access_expires_at=now()-interval '1 minute' WHERE user_id=$1`, f.users[0].UserID)
			}
			w := f.code(t, "restore", "", "grant-restore", id)
			requireWeChatStatus(t, w, 401, "tesla_reauthorization_required")
			if strings.Contains(w.Body.String(), "access_token") || strings.Contains(w.Body.String(), "synthetic") {
				t.Fatal("failed grant returned credentials")
			}
		})
	}
}

func TestWeChatRealPostgresConcurrentReplay(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "parallel"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "parallel-bind", id), 200, "bound")
	f.provider.add("parallel-code", id)
	start := make(chan struct{})
	results := make(chan *httptest.ResponseRecorder, 2)
	for i := 0; i < 2; i++ {
		go func() {
			<-start
			results <- f.request("POST", "/v1/auth/wechat/restore", "", `{"code":"parallel-code"}`)
		}()
	}
	close(start)
	first, second := <-results, <-results
	if !((first.Code == 200 && second.Code == 401) || (first.Code == 401 && second.Code == 200)) {
		t.Fatalf("concurrent restore = %d, %d", first.Code, second.Code)
	}
	f.provider.mu.Lock()
	calls := f.provider.calls
	f.provider.mu.Unlock()
	if calls != 2 {
		t.Fatalf("provider called %d times, expected once for bind and once for restore", calls)
	}
}

func TestWeChatRealPostgresLogoutAndUnbindFenceInflightRestore(t *testing.T) {
	for _, action := range []string{"logout", "unbind-rebind"} {
		t.Run(action, func(t *testing.T) {
			f := newWeChatFixture(t)
			id := wechatIdentity{OpenID: "race-" + action}
			requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "race-bind", id), 200, "bound")
			other := parseWeChatSession(t, f.code(t, "restore", "", "other-device", id), f.users[0].UserID)
			f.provider.add("race-restore", id)
			entry := f.provider.codes["race-restore"]
			entry.entered, entry.release = make(chan struct{}), make(chan struct{})
			result := make(chan *httptest.ResponseRecorder, 1)
			go func() { result <- f.request("POST", "/v1/auth/wechat/restore", "", `{"code":"race-restore"}`) }()
			select {
			case <-entry.entered:
			case <-time.After(10 * time.Second):
				t.Fatal("provider did not enter")
			}
			if action == "logout" {
				w := f.request("POST", "/v1/session/logout", f.users[0].AccessToken, "")
				if w.Code != 200 {
					t.Fatalf("logout = %d", w.Code)
				}
				if f.request("GET", "/v1/vehicles", other.AccessToken, "").Code != 200 {
					t.Fatal("logout revoked another device")
				}
			} else {
				requireWeChatStatus(t, f.request("POST", "/v1/auth/wechat/unbind", f.users[0].AccessToken, ""), 200, "unbound")
				requireWeChatStatus(t, f.code(t, "bind", f.users[1].AccessToken, "rebind", id), 200, "bound")
				if f.request("GET", "/v1/vehicles", other.AccessToken, "").Code != 401 {
					t.Fatal("unbind kept old device session")
				}
			}
			close(entry.release)
			select {
			case w := <-result:
				if w.Code != 401 || !strings.Contains(w.Body.String(), "session_required") {
					t.Fatalf("in-flight restore survived %s: %d %s", action, w.Code, w.Body.String())
				}
			case <-time.After(10 * time.Second):
				t.Fatal("restore did not finish")
			}
			user := f.users[0].UserID
			if action == "unbind-rebind" {
				user = f.users[1].UserID
			}
			parseWeChatSession(t, f.code(t, "restore", "", "fresh-after-race", id), user)
		})
	}
}

func TestWeChatRealPostgresReplaySurvivesStoreReopen(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "reopen"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "reopen-bind", id), 200, "bound")
	parseWeChatSession(t, f.code(t, "restore", "", "reopen-code", id), f.users[0].UserID)
	secondStore, err := openStore(context.Background(), os.Getenv("JOURVOLT_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer secondStore.close()
	secondApp := *f.app
	secondApp.store = secondStore
	w := httptest.NewRecorder()
	secondApp.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/v1/auth/wechat/restore", strings.NewReader(`{"code":"reopen-code"}`)))
	requireWeChatStatus(t, w, 401, "wechat_code_invalid")
	f.provider.mu.Lock()
	calls := f.provider.calls
	f.provider.mu.Unlock()
	if calls != 2 {
		t.Fatalf("replayed code reached provider after reopen: calls=%d", calls)
	}
}

func TestWeChatRealPostgresConcurrentBindingHasOneOwner(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "contended-owner", UnionID: "contended-union"}
	f.provider.add("contend-a", id)
	f.provider.add("contend-b", id)
	start := make(chan struct{})
	results := make(chan *httptest.ResponseRecorder, 2)
	for i, code := range []string{"contend-a", "contend-b"} {
		go func(i int, code string) {
			<-start
			results <- f.request("POST", "/v1/auth/wechat/bind", f.users[i].AccessToken, `{"code":"`+code+`","binding_version":"0"}`)
		}(i, code)
	}
	close(start)
	var first, second *httptest.ResponseRecorder
	select {
	case first = <-results:
	case <-time.After(10 * time.Second):
		t.Fatal("first bind stalled")
	}
	select {
	case second = <-results:
	case <-time.After(10 * time.Second):
		t.Fatal("second bind stalled")
	}
	if !((first.Code == 200 && second.Code == 409) || (first.Code == 409 && second.Code == 200)) {
		t.Fatalf("concurrent binding = %d, %d", first.Code, second.Code)
	}
}

func TestWeChatRealPostgresConcurrentRefreshUnbindCannotRetainSession(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "rotation-race"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "rotation-bind", id), 200, "bound")
	old := parseWeChatSession(t, f.code(t, "restore", "", "rotation-restore", id), f.users[0].UserID)
	start := make(chan struct{})
	refreshResult, unbindResult := make(chan *httptest.ResponseRecorder, 1), make(chan *httptest.ResponseRecorder, 1)
	go func() {
		<-start
		refreshResult <- f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+old.RefreshToken+`"}`)
	}()
	go func() {
		<-start
		unbindResult <- f.request("POST", "/v1/auth/wechat/unbind", f.users[0].AccessToken, "")
	}()
	close(start)
	var refreshed, unbound *httptest.ResponseRecorder
	select {
	case refreshed = <-refreshResult:
	case <-time.After(10 * time.Second):
		t.Fatal("refresh stalled")
	}
	select {
	case unbound = <-unbindResult:
	case <-time.After(10 * time.Second):
		t.Fatal("unbind stalled")
	}
	requireWeChatStatus(t, unbound, 200, "unbound")
	if refreshed.Code == 200 {
		var payload struct {
			Access  string `json:"access_token"`
			Refresh string `json:"refresh_token"`
		}
		if json.Unmarshal(refreshed.Body.Bytes(), &payload) != nil || payload.Access == "" || payload.Refresh == "" {
			t.Fatal("invalid refresh response")
		}
		if f.request("GET", "/v1/vehicles", payload.Access, "").Code != 401 {
			t.Fatal("racing refreshed access survived unbind")
		}
		if f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+payload.Refresh+`"}`).Code != 401 {
			t.Fatal("racing refreshed refresh token survived unbind")
		}
	} else if refreshed.Code != 401 {
		t.Fatalf("unexpected racing refresh: %d %s", refreshed.Code, refreshed.Body.String())
	}
}

func TestWeChatRealPostgresAccountDeletionRevokesRestoreAndSessions(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "deleted-account"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "delete-bind", id), 200, "bound")
	old := parseWeChatSession(t, f.code(t, "restore", "", "delete-restore", id), f.users[0].UserID)
	if w := f.request("DELETE", "/v1/account", f.users[0].AccessToken, ""); w.Code != 200 {
		t.Fatalf("delete account: %d %s", w.Code, w.Body.String())
	}
	if f.request("GET", "/v1/vehicles", old.AccessToken, "").Code != 401 {
		t.Fatal("deleted account session can read vehicles")
	}
	if f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+old.RefreshToken+`"}`).Code != 401 {
		t.Fatal("deleted account session can refresh")
	}
	requireWeChatStatus(t, f.code(t, "restore", "", "delete-next", id), 404, "wechat_not_bound")
}

func TestWeChatRealPostgresUnionIDDiscoveredDuringRestoreIsProtected(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "union-later"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "union-initial-bind", id), 200, "bound")
	id.UnionID = "newly-available-union"
	parseWeChatSession(t, f.code(t, "restore", "", "union-later-restore", id), f.users[0].UserID)
	w := f.code(t, "bind", f.users[1].AccessToken, "union-conflicting-bind", wechatIdentity{OpenID: "other-openid", UnionID: id.UnionID})
	requireWeChatStatus(t, w, 409, "wechat_binding_conflict")
}

func TestWeChatRealPostgresLogoutRevokesSessionAfterConcurrentRefresh(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "logout-refresh-race"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "logout-refresh-bind", id), 200, "bound")
	old := parseWeChatSession(t, f.code(t, "restore", "", "logout-refresh-start", id), f.users[0].UserID)
	tx, err := f.store.pool.Begin(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(context.Background())
	var locked string
	if err := tx.QueryRow(context.Background(), `SELECT id FROM jourvolt_users WHERE id=$1 FOR UPDATE`, old.UserID).Scan(&locked); err != nil {
		t.Fatal(err)
	}
	logout := make(chan *httptest.ResponseRecorder, 1)
	go func() { logout <- f.request("POST", "/v1/session/logout", old.AccessToken, "") }()
	deadline := time.Now().Add(5 * time.Second)
	for {
		var waiting bool
		err := f.store.pool.QueryRow(context.Background(), `SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%SELECT id FROM jourvolt_users WHERE id=$1 FOR UPDATE%')`).Scan(&waiting)
		if err != nil {
			t.Fatal(err)
		}
		if waiting {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("logout did not wait for user lock")
		}
		time.Sleep(10 * time.Millisecond)
	}
	rotated := f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+old.RefreshToken+`"}`)
	var payload struct {
		Access  string `json:"access_token"`
		Refresh string `json:"refresh_token"`
	}
	if rotated.Code != 200 || json.Unmarshal(rotated.Body.Bytes(), &payload) != nil || payload.Access == "" || payload.Refresh == "" {
		t.Fatalf("rotation = %d %s", rotated.Code, rotated.Body.String())
	}
	if err := tx.Commit(context.Background()); err != nil {
		t.Fatal(err)
	}
	select {
	case w := <-logout:
		if w.Code != 200 {
			t.Fatalf("logout = %d %s", w.Code, w.Body.String())
		}
	case <-time.After(5 * time.Second):
		t.Fatal("logout stalled")
	}
	if f.request("GET", "/v1/vehicles", payload.Access, "").Code != 401 {
		t.Fatal("rotated access survived logout")
	}
	if f.request("POST", "/v1/session/refresh", "", `{"refresh_token":"`+payload.Refresh+`"}`).Code != 401 {
		t.Fatal("rotated refresh survived logout")
	}
}
