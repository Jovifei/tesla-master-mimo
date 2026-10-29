package main

import (
	"context"
	"net/http/httptest"
	"testing"
	"time"
)

func expireTestSessionSoon(t *testing.T, f *wechatFixture, token string) time.Time {
	t.Helper()
	var expiry time.Time
	err := f.store.pool.QueryRow(context.Background(), `UPDATE jourvolt_sessions
SET access_expires_at=clock_timestamp()+interval '2 seconds'
WHERE access_hash=$1 RETURNING access_expires_at`, hashToken(token)).Scan(&expiry)
	if err != nil {
		t.Fatal(err)
	}
	return expiry
}

func waitForDatabaseLock(t *testing.T, f *wechatFixture, queryPattern string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for {
		var waiting bool
		err := f.store.pool.QueryRow(context.Background(), `SELECT EXISTS(
SELECT 1 FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND state='active'
AND wait_event_type='Lock' AND query LIKE $1)`, queryPattern).Scan(&waiting)
		if err != nil {
			t.Fatal(err)
		}
		if waiting {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("request did not block on %s", queryPattern)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func waitForDatabaseExpiry(t *testing.T, f *wechatFixture, expiry time.Time) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for {
		var expired bool
		if err := f.store.pool.QueryRow(context.Background(), `SELECT clock_timestamp() >= $1::timestamptz`, expiry).Scan(&expired); err != nil {
			t.Fatal(err)
		}
		if expired {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("database clock did not reach expiry")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestWeChatRealPostgresSessionExpiryWhileWaitingForUserLock(t *testing.T) {
	for _, action := range []string{"bind", "unbind"} {
		t.Run(action, func(t *testing.T) {
			f := newWeChatFixture(t)
			identity := wechatIdentity{OpenID: "expire-user-lock-" + action}
			if action == "unbind" {
				requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "existing-lock-bind", identity), 200, "bound")
			}
			ctx := context.Background()
			blocker, err := f.store.pool.Begin(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer blocker.Rollback(ctx)
			var user string
			if err = blocker.QueryRow(ctx, `SELECT id FROM jourvolt_users WHERE id=$1 FOR UPDATE`, f.users[0].UserID).Scan(&user); err != nil {
				t.Fatal(err)
			}
			expiry := expireTestSessionSoon(t, f, f.users[0].AccessToken)
			f.provider.add("pending-lock-bind", identity)
			result := make(chan *httptest.ResponseRecorder, 1)
			go func() {
				body := ""
				if action == "bind" {
					body = `{"code":"pending-lock-bind","binding_version":"0"}`
				}
				result <- f.request("POST", "/v1/auth/wechat/"+action, f.users[0].AccessToken, body)
			}()
			waitForDatabaseLock(t, f, "%SELECT id FROM jourvolt_users WHERE id=$1 FOR UPDATE%")
			waitForDatabaseExpiry(t, f, expiry)
			if err := blocker.Commit(ctx); err != nil {
				t.Fatal(err)
			}
			requireWeChatStatus(t, awaitWeChatResponse(t, result), 401, "session_required")
			fresh, err := f.store.createSession(ctx, f.users[0].UserID)
			if err != nil {
				t.Fatal(err)
			}
			bound, version := wechatVersion(t, f, fresh.AccessToken)
			if bound != (action == "unbind") || version != "0" {
				t.Fatalf("%s after expired token: bound=%t version=%s", action, bound, version)
			}
		})
	}
}

func TestWeChatRealPostgresBindSessionExpiresWhileWaitingOnUniqueIdentity(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "expiry-unique"}
	openHash, unionHash := wechatHashes(f.provider.id, id)
	ctx := context.Background()
	blocker, err := f.store.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Rollback(ctx)
	_, err = blocker.Exec(ctx, `INSERT INTO jourvolt_wechat_bindings(user_id,provider,openid_hash,unionid_hash) VALUES($1,$2,$3,NULLIF($4,''))`, f.users[1].UserID, f.provider.id, openHash, unionHash)
	if err != nil {
		t.Fatal(err)
	}
	expiry := expireTestSessionSoon(t, f, f.users[0].AccessToken)
	f.provider.add("pending-unique", id)
	result := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		result <- f.request("POST", "/v1/auth/wechat/bind", f.users[0].AccessToken, `{"code":"pending-unique","binding_version":"0"}`)
	}()
	waitForDatabaseLock(t, f, "%INSERT INTO jourvolt_wechat_bindings(user_id,provider,openid_hash,unionid_hash)%")
	waitForDatabaseExpiry(t, f, expiry)
	if err := blocker.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	requireWeChatStatus(t, awaitWeChatResponse(t, result), 401, "session_required")
	fresh, err := f.store.createSession(ctx, f.users[0].UserID)
	if err != nil {
		t.Fatal(err)
	}
	if bound, version := wechatVersion(t, f, fresh.AccessToken); bound || version != "0" {
		t.Fatalf("binding committed after unique wait: bound=%t version=%s", bound, version)
	}
}

func TestWeChatRealPostgresUnbindSessionExpiresWhileDeletingSessions(t *testing.T) {
	f := newWeChatFixture(t)
	id := wechatIdentity{OpenID: "expiry-unbind-cascade"}
	requireWeChatStatus(t, f.code(t, "bind", f.users[0].AccessToken, "cascade-expiry-bind", id), 200, "bound")
	caller := parseWeChatSession(t, f.code(t, "restore", "", "cascade-expiry-caller", id), f.users[0].UserID)
	other := parseWeChatSession(t, f.code(t, "restore", "", "cascade-expiry-other", id), f.users[0].UserID)
	ctx := context.Background()
	blocker, err := f.store.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Rollback(ctx)
	var sessionID int64
	if err = blocker.QueryRow(ctx, `SELECT id FROM jourvolt_sessions WHERE access_hash=$1 FOR UPDATE`, hashToken(other.AccessToken)).Scan(&sessionID); err != nil {
		t.Fatal(err)
	}
	expiry := expireTestSessionSoon(t, f, caller.AccessToken)
	result := make(chan *httptest.ResponseRecorder, 1)
	go func() { result <- f.request("POST", "/v1/auth/wechat/unbind", caller.AccessToken, "") }()
	waitForDatabaseLock(t, f, "%DELETE FROM jourvolt_wechat_bindings WHERE id=$1%")
	waitForDatabaseExpiry(t, f, expiry)
	if err := blocker.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	requireWeChatStatus(t, awaitWeChatResponse(t, result), 401, "session_required")
	if bound, version := wechatVersion(t, f, f.users[0].AccessToken); !bound || version != "0" {
		t.Fatalf("expired unbind changed binding: bound=%t version=%s", bound, version)
	}
	if w := f.request("GET", "/v1/vehicles", other.AccessToken, ""); w.Code != 200 {
		t.Fatalf("expired unbind revoked another session: %d", w.Code)
	}
}
