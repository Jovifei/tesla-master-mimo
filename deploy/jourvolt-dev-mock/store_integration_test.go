package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"sync"
	"testing"
	"time"
)

func TestStoreConsumesOAuthStateAndLoginTicketOnce(t *testing.T) {
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set")
	}
	ctx := context.Background()
	store, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(store.close)

	state, _ := randomToken()
	transactionID, _ := randomToken()
	nonce, _ := randomToken()
	consent, err := currentOAuthConsent(jourVoltTermsVersion, jourVoltPrivacyVersion)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.createAuthTransaction(ctx, state, transactionID, nonce, consent, time.Now().UTC().Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = store.pool.Exec(context.Background(), `DELETE FROM jourvolt_auth_transactions WHERE state_hash=$1`, hashToken(state))
	})
	if transaction, err := store.consumeAuthState(ctx, state); err != nil || transaction.Nonce != nonce || transaction.Consent != consent {
		t.Fatalf("first state consume = %#v, %v", transaction, err)
	}
	if _, err := store.consumeAuthState(ctx, state); err == nil {
		t.Fatal("OAuth state replay must fail")
	}
	expiredState, _ := randomToken()
	if err := store.createAuthTransaction(ctx, expiredState, transactionID+"expired", nonce, consent, time.Now().UTC().Add(-time.Second)); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = store.pool.Exec(context.Background(), `DELETE FROM jourvolt_auth_transactions WHERE state_hash=$1`, hashToken(expiredState))
	})
	if _, err := store.consumeAuthState(ctx, expiredState); err == nil {
		t.Fatal("expired OAuth state must fail")
	}

	providerSub, _ := randomToken()
	userID, ticket, err := store.saveTeslaGrantAndLoginTicket(
		ctx, providerSub, "encrypted-access", "encrypted-refresh", consent, time.Now().UTC().Add(time.Hour),
	)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.deleteUser(context.Background(), userID) })
	if _, err := store.exchangeLoginTicket(ctx, ticket); err != nil {
		t.Fatalf("first login ticket exchange: %v", err)
	}
	if _, err := store.exchangeLoginTicket(ctx, ticket); err == nil {
		t.Fatal("login ticket replay must fail")
	}
}

func TestStoreAllowsOneConcurrentJourVoltSessionRotation(t *testing.T) {
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set")
	}
	ctx := context.Background()
	store, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(store.close)
	userSeed, _ := randomToken()
	userID := "test_" + userSeed
	if err := store.ensureUser(ctx, userID); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.deleteUser(context.Background(), userID) })
	session, err := store.createSession(ctx, userID)
	if err != nil {
		t.Fatal(err)
	}

	results := make(chan error, 2)
	var workers sync.WaitGroup
	for range 2 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			_, err := store.rotateSession(ctx, session.RefreshToken)
			results <- err
		}()
	}
	workers.Wait()
	close(results)
	successes := 0
	failures := 0
	for err := range results {
		if err == nil {
			successes++
		} else {
			failures++
		}
	}
	if successes != 1 || failures != 1 {
		t.Fatalf("concurrent session rotation successes=%d failures=%d", successes, failures)
	}
}

func TestStoreLogoutRevokesRefreshSession(t *testing.T) {
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set")
	}
	ctx := context.Background()
	store, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(store.close)

	userID := "logout_test_" + mustRandomToken(t)
	if err := store.ensureUser(ctx, userID); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.deleteUser(context.Background(), userID) })

	session, err := store.createSession(ctx, userID)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.revokeSession(ctx, session.AccessToken); err != nil {
		t.Fatal(err)
	}
	if _, err := store.rotateSession(ctx, session.RefreshToken); err == nil {
		t.Fatal("refresh token must not rotate after logout")
	}
}

func TestStoreAccountDeletionCascadesCloudCredentialsAndSessions(t *testing.T) {
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set")
	}
	ctx := context.Background()
	store, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(store.close)

	userID := "delete_test_" + mustRandomToken(t)
	if err := store.ensureUser(ctx, userID); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.deleteUser(context.Background(), userID) })
	if _, err := store.pool.Exec(ctx, `
INSERT INTO jourvolt_tesla_tokens(user_id, access_ciphertext, refresh_ciphertext, access_expires_at)
VALUES ($1, 'access', 'refresh', now() + interval '1 hour')`, userID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.pool.Exec(ctx, `
INSERT INTO jourvolt_user_consents(user_id, terms_version, privacy_version, accepted_at)
VALUES ($1, $2, $3, now())`, userID, jourVoltTermsVersion, jourVoltPrivacyVersion); err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession(ctx, userID)
	if err != nil {
		t.Fatal(err)
	}

	api := &app{
		store: store,
		oauth: &teslaOAuth{config: &teslaConfig{
			ClientID:      "client-id",
			Authorization: "https://auth.tesla.cn/oauth2/v3/authorize",
			AppLinkURI:    "https://auth.jourvolt.com/oauth/callback",
		}},
	}
	req := httptest.NewRequest(http.MethodDelete, "/v1/account", nil)
	req.Header.Set("Authorization", "Bearer "+session.AccessToken)
	response := httptest.NewRecorder()
	api.ServeHTTP(response, req)
	if response.Code != http.StatusOK {
		t.Fatalf("delete account status = %d, want %d", response.Code, http.StatusOK)
	}
	var deletionResponse map[string]string
	if err := json.NewDecoder(response.Body).Decode(&deletionResponse); err != nil {
		t.Fatal(err)
	}
	if deletionResponse["status"] != "deleted" {
		t.Fatalf("delete account response = %#v", deletionResponse)
	}
	revokeURL, err := url.Parse(deletionResponse["tesla_consent_revoke_url"])
	if err != nil || revokeURL.Scheme != "https" || revokeURL.Host != "auth.tesla.cn" {
		t.Fatalf("delete account revoke URL = %q", deletionResponse["tesla_consent_revoke_url"])
	}
	backURL, err := url.Parse(revokeURL.Query().Get("back_url"))
	if err != nil || backURL.Scheme != "https" || backURL.Host != "auth.jourvolt.com" || backURL.Path != "/privacy/" {
		t.Fatalf("delete account back URL = %q", revokeURL.Query().Get("back_url"))
	}
	checks := map[string]string{
		"jourvolt_users":         "SELECT count(*) FROM jourvolt_users WHERE id=$1",
		"jourvolt_sessions":      "SELECT count(*) FROM jourvolt_sessions WHERE user_id=$1",
		"jourvolt_tesla_tokens":  "SELECT count(*) FROM jourvolt_tesla_tokens WHERE user_id=$1",
		"jourvolt_user_consents": "SELECT count(*) FROM jourvolt_user_consents WHERE user_id=$1",
	}
	for table, query := range checks {
		var count int
		if err := store.pool.QueryRow(ctx, query, userID).Scan(&count); err != nil {
			t.Fatal(err)
		}
		if count != 0 {
			t.Fatalf("%s still contains %d row(s) after account deletion", table, count)
		}
	}
	if _, err := store.userForAccess(ctx, session.AccessToken); err == nil {
		t.Fatal("deleted account access token must not authorize a request")
	}
}

func TestAccountDeletionWorksWhenTeslaOAuthIsNotConfigured(t *testing.T) {
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("JOURVOLT_TEST_DATABASE_URL is not set")
	}
	ctx := context.Background()
	store, err := openStore(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(store.close)

	userID := "delete_no_oauth_" + mustRandomToken(t)
	if err := store.ensureUser(ctx, userID); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.deleteUser(context.Background(), userID) })
	session, err := store.createSession(ctx, userID)
	if err != nil {
		t.Fatal(err)
	}

	api := &app{store: store}
	req := httptest.NewRequest(http.MethodDelete, "/v1/account", nil)
	req.Header.Set("Authorization", "Bearer "+session.AccessToken)
	response := httptest.NewRecorder()
	api.ServeHTTP(response, req)
	if response.Code != http.StatusOK {
		t.Fatalf("delete account without OAuth status = %d, want %d", response.Code, http.StatusOK)
	}
	var deletionResponse map[string]string
	if err := json.NewDecoder(response.Body).Decode(&deletionResponse); err != nil {
		t.Fatal(err)
	}
	if deletionResponse["status"] != "deleted" {
		t.Fatalf("delete account without OAuth response = %#v", deletionResponse)
	}
	if _, exists := deletionResponse["tesla_consent_revoke_url"]; exists {
		t.Fatalf("OAuth revoke URL must be omitted when OAuth is not configured: %#v", deletionResponse)
	}
}

func mustRandomToken(t *testing.T) string {
	t.Helper()
	token, err := randomToken()
	if err != nil {
		t.Fatal(err)
	}
	return token
}
