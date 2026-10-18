package main

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/go-jose/go-jose/v4"
	"golang.org/x/oauth2"
)

type miniOAuthFixture struct {
	app           *app
	oauth         *teslaOAuth
	store         *store
	nonce         string
	challenge     string
	wrongNonce    bool
	wrongPKCE     bool
	providerCalls atomic.Int32
	subject       string
	tokenEntered  chan struct{}
	tokenRelease  chan struct{}
	tokenGate     sync.Once
}

func newMiniOAuthFixture(t *testing.T) *miniOAuthFixture {
	t.Helper()
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set: real PostgreSQL integration not run")
	}
	s, err := openStore(context.Background(), dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.close)
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := jose.NewSigner(jose.SigningKey{Algorithm: jose.RS256, Key: key}, (&jose.SignerOptions{}).WithHeader("kid", "mini-key"))
	if err != nil {
		t.Fatal(err)
	}
	seed, _ := randomToken()
	f := &miniOAuthFixture{store: s, subject: "mini-test-" + seed}
	var provider *httptest.Server
	provider = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/keys":
			_ = json.NewEncoder(w).Encode(jose.JSONWebKeySet{Keys: []jose.JSONWebKey{{Key: &key.PublicKey, KeyID: "mini-key", Algorithm: "RS256", Use: "sig"}}})
		case "/token":
			f.providerCalls.Add(1)
			if f.tokenEntered != nil {
				f.tokenGate.Do(func() { close(f.tokenEntered) })
				<-f.tokenRelease
			}
			if err := r.ParseForm(); err != nil {
				http.Error(w, "form", 400)
				return
			}
			verifier := r.Form.Get("code_verifier")
			if f.challenge != "" && (f.wrongPKCE || verifier == "" || oauth2.S256ChallengeFromVerifier(verifier) != f.challenge) {
				http.Error(w, `{"error":"invalid_grant"}`, 400)
				return
			}
			nonce := f.nonce
			if f.wrongNonce {
				nonce = "wrong-nonce"
			}
			claims, _ := json.Marshal(map[string]any{"iss": provider.URL, "sub": f.subject, "aud": "test-client", "nonce": nonce, "iat": time.Now().Unix(), "exp": time.Now().Add(time.Hour).Unix()})
			jws, err := signer.Sign(claims)
			if err != nil {
				http.Error(w, "sign", 500)
				return
			}
			signed, err := jws.CompactSerialize()
			if err != nil {
				http.Error(w, "serialize", 500)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "tesla-mini-access", "refresh_token": "tesla-mini-refresh", "expires_in": 3600, "token_type": "Bearer", "id_token": signed})
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(provider.Close)
	f.oauth = newTeslaOAuth(&teslaConfig{ClientID: "test-client", ClientSecret: "secret", RedirectURI: provider.URL + "/v1/auth/tesla/callback", AppLinkURI: "https://auth.example.com/oauth/callback", Authorization: provider.URL + "/authorize", TokenEndpoint: provider.URL + "/token", Issuer: provider.URL, JWKSURL: provider.URL + "/keys", FleetAPIBase: "https://fleet.example.com"}, s, mustTestCipher(t), provider.Client())
	f.app = &app{store: s, oauth: f.oauth}
	t.Cleanup(func() {
		_, _ = s.pool.Exec(context.Background(), `DELETE FROM jourvolt_auth_transactions WHERE state_hash IN (SELECT state_hash FROM jourvolt_miniprogram_auth WHERE user_id=(SELECT id FROM jourvolt_users WHERE provider_sub=$1))`, hashToken("tesla:"+f.subject))
		_, _ = s.pool.Exec(context.Background(), `DELETE FROM jourvolt_users WHERE provider_sub=$1`, hashToken("tesla:"+f.subject))
	})
	return f
}

func (f *miniOAuthFixture) start(t *testing.T, mini bool) authStart {
	t.Helper()
	r := httptest.NewRequest(http.MethodGet, "/v1/auth/tesla/start", nil)
	r.Header.Set("X-JourVolt-Terms-Version", jourVoltTermsVersion)
	r.Header.Set("X-JourVolt-Privacy-Version", jourVoltPrivacyVersion)
	if mini {
		r.Header.Set("X-MiniProgram-Channel", "wechat")
	}
	w := httptest.NewRecorder()
	f.app.ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("start: %d %s", w.Code, w.Body.String())
	}
	var started authStart
	if err := json.Unmarshal(w.Body.Bytes(), &started); err != nil {
		t.Fatal(err)
	}
	u, err := url.Parse(started.AuthorizationURL)
	if err != nil {
		t.Fatal(err)
	}
	f.nonce = u.Query().Get("nonce")
	f.challenge = u.Query().Get("code_challenge")
	if mini && (started.ClientProof == "" || f.challenge == "" || u.Query().Get("code_challenge_method") != "S256") {
		t.Fatal("Mini proof/PKCE missing")
	}
	if !mini && (started.ClientProof != "" || f.challenge != "" || strings.Contains(w.Body.String(), "client_proof")) {
		t.Fatal("App start contract changed")
	}
	if mini && w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("proof response must not be cached")
	}
	stateHash := hashToken(u.Query().Get("state"))
	t.Cleanup(func() {
		_, _ = f.store.pool.Exec(context.Background(), `DELETE FROM jourvolt_auth_transactions WHERE state_hash=$1`, stateHash)
	})
	return started
}

func (f *miniOAuthFixture) callback(t *testing.T, started authStart, extra url.Values) *httptest.ResponseRecorder {
	t.Helper()
	u, _ := url.Parse(started.AuthorizationURL)
	q := url.Values{"state": {u.Query().Get("state")}, "code": {"auth-code"}}
	for k, v := range extra {
		q[k] = v
	}
	w := httptest.NewRecorder()
	f.app.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/v1/auth/tesla/callback?"+q.Encode(), nil))
	return w
}

func (f *miniOAuthFixture) action(action string, started authStart, proof string) *httptest.ResponseRecorder {
	data, _ := json.Marshal(map[string]string{"transaction_id": started.TransactionID, "client_proof": proof})
	w := httptest.NewRecorder()
	f.app.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/v1/auth/miniprogram/"+action, strings.NewReader(string(data))))
	return w
}

func assertMiniStatus(t *testing.T, w *httptest.ResponseRecorder, want string) {
	t.Helper()
	var record miniProgramAuthRecord
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &record) != nil || record.Status != want {
		t.Fatalf("status want %s: %d %s", want, w.Code, w.Body.String())
	}
	for _, s := range []string{"client_proof", "access_token", "refresh_token", "pkce_verifier", "user_id"} {
		if strings.Contains(w.Body.String(), s) {
			t.Fatalf("status leaks %s", s)
		}
	}
}

func TestMiniProgramOAuthRealPostgresTeslaFirstAndAppAccountReuse(t *testing.T) {
	f := newMiniOAuthFixture(t)
	appStart := f.start(t, false)
	appCallback := f.callback(t, appStart, nil)
	if appCallback.Code != 303 {
		t.Fatalf("App callback changed: %d", appCallback.Code)
	}
	link, _ := url.Parse(appCallback.Header().Get("Location"))
	appSession, err := f.store.exchangeLoginTicket(context.Background(), link.Query().Get("ticket"))
	if err != nil {
		t.Fatal(err)
	}
	started := f.start(t, true)
	u, _ := url.Parse(started.AuthorizationURL)
	for _, secret := range []string{started.ClientProof, started.TransactionID} {
		if strings.Contains(started.AuthorizationURL, secret) {
			t.Fatal("authorization URL contains client secret")
		}
	}
	var proofHash, verifier string
	if err := f.store.pool.QueryRow(context.Background(), `SELECT proof_hash,pkce_verifier FROM jourvolt_miniprogram_auth WHERE transaction_hash=$1`, hashToken(started.TransactionID)).Scan(&proofHash, &verifier); err != nil {
		t.Fatal(err)
	}
	if proofHash == started.ClientProof || proofHash != hashToken(started.ClientProof) || verifier == started.ClientProof || verifier == f.nonce || verifier == u.Query().Get("state") {
		t.Fatal("proof/state/nonce/verifier not separated")
	}
	assertMiniStatus(t, f.action("status", started, started.ClientProof), "pending")
	if w := f.action("claim", started, started.ClientProof); w.Code != 409 || !strings.Contains(w.Body.String(), "authorization_pending") {
		t.Fatalf("premature claim: %d %s", w.Code, w.Body.String())
	}
	for _, action := range []string{"status", "claim", "cancel"} {
		if w := f.action(action, started, "wrong-proof"); w.Code != 401 {
			t.Fatalf("%s wrong proof: %d", action, w.Code)
		}
	}
	w := f.callback(t, started, nil)
	if w.Code != 200 || w.Header().Get("Location") != "" || !strings.Contains(w.Body.String(), "授权已完成") {
		t.Fatalf("Mini callback: %d %s", w.Code, w.Body.String())
	}
	for _, secret := range []string{started.ClientProof, started.TransactionID, "ticket=", "tesla-mini-access", "tesla-mini-refresh"} {
		if strings.Contains(w.Body.String(), secret) {
			t.Fatal("callback leaks credentials")
		}
	}
	assertMiniStatus(t, f.action("status", started, started.ClientProof), "ready")
	claim := f.action("claim", started, started.ClientProof)
	var payload struct {
		Access  string `json:"access_token"`
		Refresh string `json:"refresh_token"`
		User    struct {
			ID string `json:"id"`
		} `json:"user"`
	}
	if claim.Code != 200 || json.Unmarshal(claim.Body.Bytes(), &payload) != nil || payload.Access == "" || payload.Refresh == "" || payload.User.ID != appSession.UserID {
		t.Fatalf("claim does not preserve App user: %d %s", claim.Code, claim.Body.String())
	}
	assertMiniStatus(t, f.action("status", started, started.ClientProof), "consumed")
	if w := f.action("claim", started, started.ClientProof); w.Code != 409 || !strings.Contains(w.Body.String(), "authorization_consumed") {
		t.Fatal("claim replay accepted")
	}
	if w := f.action("cancel", started, started.ClientProof); w.Code != 409 {
		t.Fatal("consumed cancellation accepted")
	}
	if w := f.callback(t, started, nil); strings.Contains(w.Body.String(), "授权已完成") {
		t.Fatal("OAuth state replay accepted")
	}
	var tickets int
	if err := f.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_login_tickets WHERE user_id=$1`, payload.User.ID).Scan(&tickets); err != nil || tickets != 1 {
		t.Fatalf("Mini must not create App login ticket: %d %v", tickets, err)
	}
}

func TestMiniProgramOAuthRealPostgresNegativePaths(t *testing.T) {
	for _, scenario := range []string{"cancel", "expired", "nonce", "pkce", "denied", "wrong_state"} {
		t.Run(scenario, func(t *testing.T) {
			f := newMiniOAuthFixture(t)
			started := f.start(t, true)
			extra := url.Values{}
			want := "failed"
			switch scenario {
			case "cancel":
				assertMiniStatus(t, f.action("cancel", started, started.ClientProof), "cancelled")
				assertMiniStatus(t, f.action("cancel", started, started.ClientProof), "cancelled")
				want = "cancelled"
			case "expired":
				_, err := f.store.pool.Exec(context.Background(), `UPDATE jourvolt_miniprogram_auth SET expires_at=now()-interval '1 second' WHERE transaction_hash=$1`, hashToken(started.TransactionID))
				if err != nil {
					t.Fatal(err)
				}
				want = "expired"
			case "nonce":
				f.wrongNonce = true
			case "pkce":
				f.wrongPKCE = true
			case "denied":
				extra.Set("error", "access_denied")
			case "wrong_state":
				extra.Set("state", "unknown-state")
				want = "pending"
			}
			w := f.callback(t, started, extra)
			if strings.Contains(w.Body.String(), "授权已完成") {
				t.Fatal("invalid callback succeeded")
			}
			assertMiniStatus(t, f.action("status", started, started.ClientProof), want)
			if (scenario == "cancel" || scenario == "expired" || scenario == "wrong_state") && f.providerCalls.Load() != 0 {
				t.Fatal("invalid transaction reached Tesla token endpoint")
			}
			if w := f.action("claim", started, started.ClientProof); w.Code == 200 {
				t.Fatal("invalid transaction issued session")
			}
			var users int
			if err := f.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_users WHERE provider_sub=$1`, hashToken("tesla:"+f.subject)).Scan(&users); err != nil || users != 0 {
				t.Fatalf("invalid callback saved grant/user: %d %v", users, err)
			}
		})
	}
}

func TestMiniProgramOAuthRealPostgresCancellationWinsInflightCallback(t *testing.T) {
	f := newMiniOAuthFixture(t)
	// An already working App account/grant must remain intact if cancellation
	// wins while the Mini provider exchange is in flight.
	appStart := f.start(t, false)
	appCallback := f.callback(t, appStart, nil)
	link, _ := url.Parse(appCallback.Header().Get("Location"))
	appSession, err := f.store.exchangeLoginTicket(context.Background(), link.Query().Get("ticket"))
	if err != nil {
		t.Fatal(err)
	}
	var beforeAccess, beforeRefresh string
	var beforeUpdated time.Time
	if err := f.store.pool.QueryRow(context.Background(), `SELECT access_ciphertext,refresh_ciphertext,updated_at FROM jourvolt_tesla_tokens WHERE user_id=$1`, appSession.UserID).Scan(&beforeAccess, &beforeRefresh, &beforeUpdated); err != nil {
		t.Fatal(err)
	}
	started := f.start(t, true)
	f.tokenEntered = make(chan struct{})
	f.tokenRelease = make(chan struct{})
	defer func() {
		select {
		case <-f.tokenRelease:
		default:
			close(f.tokenRelease)
		}
	}()
	u, _ := url.Parse(started.AuthorizationURL)
	callbackDone := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		w := httptest.NewRecorder()
		q := url.Values{"state": {u.Query().Get("state")}, "code": {"inflight-code"}}
		f.app.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/v1/auth/tesla/callback?"+q.Encode(), nil))
		callbackDone <- w
	}()
	select {
	case <-f.tokenEntered:
	case <-time.After(5 * time.Second):
		t.Fatal("callback did not reach provider")
	}
	assertMiniStatus(t, f.action("cancel", started, started.ClientProof), "cancelled")
	close(f.tokenRelease)
	select {
	case callback := <-callbackDone:
		if strings.Contains(callback.Body.String(), "授权已完成") {
			t.Fatal("cancelled callback reported success")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("callback did not finish")
	}
	assertMiniStatus(t, f.action("status", started, started.ClientProof), "cancelled")
	var afterAccess, afterRefresh string
	var afterUpdated time.Time
	if err := f.store.pool.QueryRow(context.Background(), `SELECT access_ciphertext,refresh_ciphertext,updated_at FROM jourvolt_tesla_tokens WHERE user_id=$1`, appSession.UserID).Scan(&afterAccess, &afterRefresh, &afterUpdated); err != nil {
		t.Fatal(err)
	}
	if afterAccess != beforeAccess || afterRefresh != beforeRefresh || !afterUpdated.Equal(beforeUpdated) {
		t.Fatal("cancelled callback changed existing App Tesla grant")
	}
	var sessions int
	if err := f.store.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_sessions WHERE user_id=$1`, appSession.UserID).Scan(&sessions); err != nil || sessions != 1 {
		t.Fatalf("cancelled callback created session: %d %v", sessions, err)
	}
}

func TestMiniProgramOAuthRealPostgresProofIsolationAndMalformedRequests(t *testing.T) {
	f := newMiniOAuthFixture(t)
	first := f.start(t, true)
	second := f.start(t, true)
	for _, action := range []string{"status", "claim", "cancel"} {
		if w := f.action(action, first, second.ClientProof); w.Code != http.StatusUnauthorized {
			t.Fatalf("cross-transaction %s proof accepted: %d", action, w.Code)
		}
		for _, body := range []string{`{}`, `{"transaction_id":"x","client_proof":"y"} {}`, `{"transaction_id":1,"client_proof":"y"}`, strings.Repeat("x", 8193)} {
			w := httptest.NewRecorder()
			f.app.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/v1/auth/miniprogram/"+action, strings.NewReader(body)))
			if w.Code != http.StatusBadRequest {
				t.Fatalf("malformed %s request: %d", action, w.Code)
			}
		}
	}
	assertMiniStatus(t, f.action("status", first, first.ClientProof), "pending")
	assertMiniStatus(t, f.action("status", second, second.ClientProof), "pending")
}

func TestMiniProgramOAuthRealPostgresCancelBeforeDeletePreventsAccountResurrection(t *testing.T) {
	for _, inFlight := range []bool{false, true} {
		name := "callback_after_delete"
		if inFlight {
			name = "provider_inflight_before_cancel_and_delete"
		}
		t.Run(name, func(t *testing.T) {
			f := newMiniOAuthFixture(t)
			appStart := f.start(t, false)
			appCallback := f.callback(t, appStart, nil)
			link, err := url.Parse(appCallback.Header().Get("Location"))
			if err != nil {
				t.Fatal(err)
			}
			appSession, err := f.store.exchangeLoginTicket(context.Background(), link.Query().Get("ticket"))
			if err != nil {
				t.Fatal(err)
			}
			started := f.start(t, true)
			var callbackDone chan *httptest.ResponseRecorder
			if inFlight {
				f.tokenEntered = make(chan struct{})
				f.tokenRelease = make(chan struct{})
				defer func() {
					select {
					case <-f.tokenRelease:
					default:
						close(f.tokenRelease)
					}
				}()
				callbackDone = make(chan *httptest.ResponseRecorder, 1)
				u, _ := url.Parse(started.AuthorizationURL)
				go func() {
					w := httptest.NewRecorder()
					q := url.Values{"state": {u.Query().Get("state")}, "code": {"before-deletion-code"}}
					f.app.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/v1/auth/tesla/callback?"+q.Encode(), nil))
					callbackDone <- w
				}()
				select {
				case <-f.tokenEntered:
				case <-time.After(5 * time.Second):
					t.Fatal("callback did not enter provider before cancellation")
				}
			}
			assertMiniStatus(t, f.action("cancel", started, started.ClientProof), "cancelled")
			deletion := httptest.NewRecorder()
			deleteRequest := httptest.NewRequest(http.MethodDelete, "/v1/account", nil)
			deleteRequest.Header.Set("Authorization", "Bearer "+appSession.AccessToken)
			f.app.ServeHTTP(deletion, deleteRequest)
			if deletion.Code != http.StatusOK || !strings.Contains(deletion.Body.String(), `"status":"deleted"`) {
				t.Fatalf("delete account failed: %d %s", deletion.Code, deletion.Body.String())
			}
			var callback *httptest.ResponseRecorder
			if inFlight {
				close(f.tokenRelease)
				select {
				case callback = <-callbackDone:
				case <-time.After(5 * time.Second):
					t.Fatal("callback did not finish after deletion")
				}
			} else {
				callback = f.callback(t, started, nil)
			}
			if strings.Contains(callback.Body.String(), "授权已完成") {
				t.Fatal("old callback reported successful authorization after deletion")
			}
			assertMiniStatus(t, f.action("status", started, started.ClientProof), "cancelled")
			if claim := f.action("claim", started, started.ClientProof); claim.Code != http.StatusConflict || !strings.Contains(claim.Body.String(), "authorization_cancelled") {
				t.Fatalf("old transaction claim not rejected: %d %s", claim.Code, claim.Body.String())
			}
			// Check both the deleted ID and verified subject: recreation under a
			// new user ID must fail this regression just as recreation under the
			// old user ID would.
			checks := map[string]string{
				"user":    `SELECT count(*) FROM jourvolt_users WHERE id=$1 OR provider_sub=$2`,
				"grant":   `SELECT count(*) FROM jourvolt_tesla_tokens WHERE user_id=$1 OR user_id IN (SELECT id FROM jourvolt_users WHERE provider_sub=$2)`,
				"session": `SELECT count(*) FROM jourvolt_sessions WHERE user_id=$1 OR user_id IN (SELECT id FROM jourvolt_users WHERE provider_sub=$2)`,
				"consent": `SELECT count(*) FROM jourvolt_user_consents WHERE user_id=$1 OR user_id IN (SELECT id FROM jourvolt_users WHERE provider_sub=$2)`,
				"ticket":  `SELECT count(*) FROM jourvolt_login_tickets WHERE user_id=$1 OR user_id IN (SELECT id FROM jourvolt_users WHERE provider_sub=$2)`,
			}
			for label, query := range checks {
				var count int
				if err := f.store.pool.QueryRow(context.Background(), query, appSession.UserID, hashToken("tesla:"+f.subject)).Scan(&count); err != nil || count != 0 {
					t.Fatalf("deleted %s reconstructed: count=%d error=%v", label, count, err)
				}
			}
		})
	}
}

func TestMiniProgramOAuthRealPostgresClaimCancelRace(t *testing.T) {
	f := newMiniOAuthFixture(t)
	for i := 0; i < 12; i++ {
		started := f.start(t, true)
		f.callback(t, started, nil)
		gate := make(chan struct{})
		var claim, cancel *httptest.ResponseRecorder
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); <-gate; claim = f.action("claim", started, started.ClientProof) }()
		go func() { defer wg.Done(); <-gate; cancel = f.action("cancel", started, started.ClientProof) }()
		close(gate)
		wg.Wait()
		if (claim.Code == 200) == (cancel.Code == 200) {
			t.Fatalf("exactly one race winner required: claim=%d cancel=%d", claim.Code, cancel.Code)
		}
		want := "cancelled"
		if claim.Code == 200 {
			want = "consumed"
		}
		assertMiniStatus(t, f.action("status", started, started.ClientProof), want)
	}
}
