package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"testing"
	"time"
)

func TestStoreSerializesConcurrentTeslaRefresh(t *testing.T) {
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
	cipher, err := newTokenCipher(bytes.Repeat([]byte{9}, 32))
	if err != nil {
		t.Fatal(err)
	}
	accessCiphertext, _ := cipher.encrypt("expired-access")
	refreshCiphertext, _ := cipher.encrypt("old-refresh")
	providerSub, _ := randomToken()
	userID, _, err := store.saveTeslaGrantAndLoginTicket(
		ctx, providerSub, accessCiphertext, refreshCiphertext,
		oauthConsent{TermsVersion: jourVoltTermsVersion, PrivacyVersion: jourVoltPrivacyVersion},
		time.Now().UTC().Add(-time.Minute),
	)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.deleteUser(context.Background(), userID) })

	var lock sync.Mutex
	refreshCalls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		lock.Lock()
		refreshCalls++
		lock.Unlock()
		_ = json.NewEncoder(w).Encode(map[string]any{
			"access_token": "new-access", "refresh_token": "new-refresh", "expires_in": 3600,
		})
	}))
	t.Cleanup(server.Close)
	manager := &teslaTokenManager{
		store: store, cipher: cipher,
		config: &teslaConfig{ClientID: "client-id", TokenEndpoint: server.URL},
		client: server.Client(),
	}

	results := make(chan string, 2)
	errors := make(chan error, 2)
	var workers sync.WaitGroup
	for range 2 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			token, err := manager.accessToken(ctx, userID, "")
			results <- token
			errors <- err
		}()
	}
	workers.Wait()
	close(results)
	close(errors)
	for err := range errors {
		if err != nil {
			t.Fatal(err)
		}
	}
	for token := range results {
		if token != "new-access" {
			t.Fatalf("access token = %q", token)
		}
	}
	lock.Lock()
	defer lock.Unlock()
	if refreshCalls != 1 {
		t.Fatalf("Tesla refresh calls = %d, want 1", refreshCalls)
	}
}
