package main

import (
	"context"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func openWeChatIntegrationStore(t *testing.T) (*store, context.Context) {
	t.Helper()
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
	return store, ctx
}

func TestWeChatAuthorizationClaimIsAtomicAndOneTime(t *testing.T) {
	store, ctx := openWeChatIntegrationStore(t)
	consent, err := currentOAuthConsent(jourVoltTermsVersion, jourVoltPrivacyVersion)
	if err != nil {
		t.Fatal(err)
	}
	appID := "wx_test_" + mustRandomToken(t)[:12]
	openidHash := hashToken("openid:" + mustRandomToken(t))
	linkToken, _, err := store.createWeChatLinkChallenge(ctx, appID, openidHash)
	if err != nil {
		t.Fatal(err)
	}
	state, transactionID, nonce := mustRandomToken(t), mustRandomToken(t), mustRandomToken(t)
	proof := mustRandomToken(t)
	transactionExpires := time.Now().UTC().Add(time.Minute)
	if err := store.createAuthTransactionWithClient(ctx, state, transactionID, nonce, consent, transactionExpires, wechatLinkInfo{
		TokenHash: hashToken(linkToken), AppID: appID, OpenIDHash: openidHash,
	}, "wechat", hashToken(proof), ""); err != nil {
		t.Fatal(err)
	}
	transaction, err := store.consumeAuthState(ctx, state)
	if err != nil {
		t.Fatal(err)
	}
	cipher := mustTestCipher(t)
	ticket := mustRandomToken(t)
	ticketCiphertext, err := cipher.encrypt(ticket)
	if err != nil {
		t.Fatal(err)
	}
	callbackRef := mustRandomToken(t)
	providerSub := "wechat-claim-" + mustRandomToken(t)
	callback, err := store.saveTeslaGrantAndWeChatArtifact(ctx, transaction, providerSub, "access-ciphertext", "refresh-ciphertext", consent, time.Now().UTC().Add(time.Hour), ticket, ticketCiphertext, callbackRef)
	if err != nil || callback != callbackRef {
		t.Fatalf("save WeChat artifact = %q, %v", callback, err)
	}
	userID, found, err := store.wechatIdentityUser(ctx, appID, openidHash)
	if err != nil || !found || strings.TrimSpace(userID) == "" {
		t.Fatalf("bound WeChat identity = %q, %t, %v", userID, found, err)
	}
	t.Cleanup(func() {
		_ = store.deleteUser(context.Background(), userID)
		_, _ = store.pool.Exec(context.Background(), `DELETE FROM jourvolt_auth_transactions WHERE transaction_hash=$1`, hashToken(transactionID))
		_, _ = store.pool.Exec(context.Background(), `DELETE FROM jourvolt_wechat_link_challenges WHERE token_hash=$1`, hashToken(linkToken))
	})
	record, err := store.wechatAuthorizationRecord(ctx, transactionID, proof, false)
	if err != nil || record.Status != "ready" {
		t.Fatalf("ready transaction = %#v, %v", record, err)
	}
	var workers sync.WaitGroup
	results := make(chan error, 2)
	for range 2 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			_, claimErr := store.claimWeChatAuthorization(ctx, transactionID, proof, callbackRef, cipher)
			results <- claimErr
		}()
	}
	workers.Wait()
	close(results)
	successes, failures := 0, 0
	for claimErr := range results {
		if claimErr == nil {
			successes++
		} else {
			failures++
		}
	}
	if successes != 1 || failures != 1 {
		t.Fatalf("concurrent WeChat claims successes=%d failures=%d", successes, failures)
	}
}

func TestWeChatAuthorizationConflictRollsBackGrantAndBinding(t *testing.T) {
	store, ctx := openWeChatIntegrationStore(t)
	consent, err := currentOAuthConsent(jourVoltTermsVersion, jourVoltPrivacyVersion)
	if err != nil {
		t.Fatal(err)
	}
	existingUser := "wechat_existing_" + mustRandomToken(t)
	if err := store.ensureUser(ctx, existingUser); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.deleteUser(context.Background(), existingUser) })
	appID := "wx_conflict_" + mustRandomToken(t)[:12]
	openidHash := hashToken("openid:" + mustRandomToken(t))
	if _, err := store.pool.Exec(ctx, `
INSERT INTO jourvolt_wechat_identities(app_id, openid_hash, user_id)
VALUES ($1, $2, $3)`, appID, openidHash, existingUser); err != nil {
		t.Fatal(err)
	}
	linkToken, _, err := store.createWeChatLinkChallenge(ctx, appID, openidHash)
	if err != nil {
		t.Fatal(err)
	}
	state, transactionID, nonce := mustRandomToken(t), mustRandomToken(t), mustRandomToken(t)
	proof := mustRandomToken(t)
	if err := store.createAuthTransactionWithClient(ctx, state, transactionID, nonce, consent, time.Now().UTC().Add(time.Minute), wechatLinkInfo{
		TokenHash: hashToken(linkToken), AppID: appID, OpenIDHash: openidHash,
	}, "wechat", hashToken(proof), ""); err != nil {
		t.Fatal(err)
	}
	transaction, err := store.consumeAuthState(ctx, state)
	if err != nil {
		t.Fatal(err)
	}
	cipher := mustTestCipher(t)
	ticket := mustRandomToken(t)
	ticketCiphertext, err := cipher.encrypt(ticket)
	if err != nil {
		t.Fatal(err)
	}
	providerSub := "wechat_conflict_" + mustRandomToken(t)
	if _, err := store.saveTeslaGrantAndWeChatArtifact(ctx, transaction, providerSub, "access", "refresh", consent, time.Now().UTC().Add(time.Hour), ticket, ticketCiphertext, mustRandomToken(t)); err == nil || err.Error() != "wechat_identity_conflict" {
		t.Fatalf("conflict error = %v, want wechat_identity_conflict", err)
	}
	var count int
	if err := store.pool.QueryRow(ctx, `SELECT count(*) FROM jourvolt_users WHERE provider_sub=$1`, hashToken("tesla:"+providerSub)).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatalf("conflicting grant left %d user row(s)", count)
	}
	_, _ = store.pool.Exec(ctx, `DELETE FROM jourvolt_auth_transactions WHERE transaction_hash=$1`, hashToken(transactionID))
	_, _ = store.pool.Exec(ctx, `DELETE FROM jourvolt_wechat_link_challenges WHERE token_hash=$1`, hashToken(linkToken))
}
