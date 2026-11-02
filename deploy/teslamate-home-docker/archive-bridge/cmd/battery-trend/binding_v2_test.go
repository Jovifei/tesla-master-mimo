package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"testing"
)

func TestFreshOwnerStateAndV2SendUseCurrentBindingIdentity(t *testing.T) {
	identity := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	var mu sync.Mutex
	var requests []string
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		requests = append(requests, r.Method+" "+r.URL.Path)
		mu.Unlock()
		switch r.URL.Path {
		case "/api/v1/cars/7/history/archive/binding-identity":
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"data":{"schema_version":1,"binding_identity":"`+identity+`"}}`)
		case "/api/v1/cars/7/history/archive/battery-trend/import-v2":
			if r.Header.Get(bindingIdentityHeader) != identity {
				t.Error("binding identity header mismatch")
			}
			body, _ := io.ReadAll(r.Body)
			var request trendRequest
			if json.Unmarshal(body, &request) != nil {
				t.Error("invalid synthetic request")
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = fmt.Fprintf(w, `{"data":{"schema_version":1,"batch_id":%q,"payload_sha256":%q,"inserted":%d,"unchanged":0,"conflicted":0,"binding_identity":%q}}`,
				request.BatchID, request.PayloadSHA256, len(request.Observations), identity)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	c := syntheticConfig(t, server.URL)
	gotIdentity, err := fetchBindingIdentity(context.Background(), c, server.Client())
	if err != nil || gotIdentity != identity {
		t.Fatal("current binding identity was not fetched")
	}
	c.BindingIdentity = gotIdentity
	state, err := verifyOwnerState(c, gotIdentity)
	if err != nil || state.Version != 2 || state.BindingIdentity != identity {
		t.Fatal("fresh owner state was not initialized")
	}

	source := &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}
	worker := bridge{config: c, source: source, client: server.Client()}
	if err = worker.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	state, err = loadState(c)
	if err != nil || state.Version != 2 || state.BindingIdentity != identity ||
		state.LastDriveID != 1 || state.Pending != nil {
		t.Fatal("v2 acknowledgement did not advance the bound state")
	}
	mu.Lock()
	defer mu.Unlock()
	expected := []string{
		"GET /api/v1/cars/7/history/archive/binding-identity",
		"POST /api/v1/cars/7/history/archive/battery-trend/import-v2",
	}
	if len(requests) != 2 || requests[0] != expected[0] || requests[1] != expected[1] {
		t.Fatalf("requests=%v", requests)
	}
}

func TestOwnerChangeStopsAndLegacyMigrationPreservesFrozenBatch(t *testing.T) {
	c := syntheticConfig(t, "https://synthetic.example")
	a := "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	b := "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	observation, err := observationFromDrive(syntheticDrive(1), c.SourceCarID)
	if err != nil {
		t.Fatal(err)
	}
	body, _, _, err := makeTrendRequest(c.SourceInstanceID, c.SourceVehicleID, []trendObservation{observation})
	if err != nil {
		t.Fatal(err)
	}
	legacy := trendState{Version: 1, ScopeHash: c.ScopeHash, HighWaterID: 1,
		Pending: &pendingBatch{Body: body, LastDriveID: 1}}
	if err = saveState(c, legacy); err != nil {
		t.Fatal(err)
	}
	if _, err = verifyOwnerState(c, a); err == nil {
		t.Fatal("legacy state was claimed without explicit migration")
	}
	before, _ := os.ReadFile(c.StateFile)
	if err = migrateLegacyState(c, a); err != nil {
		t.Fatal(err)
	}
	migrated, err := loadState(c)
	after, _ := os.ReadFile(c.StateFile)
	if err != nil || migrated.Version != 2 || migrated.BindingIdentity != a ||
		!bytes.Equal(migrated.Pending.Body, body) || migrated.LastDriveID != 0 ||
		migrated.HighWaterID != 1 || bytes.Equal(before, after) {
		t.Fatal("migration did not preserve pending bytes and cursor")
	}
	before = after
	if _, err = verifyOwnerState(c, b); err == nil {
		t.Fatal("rebound account claimed existing state")
	}
	after, _ = os.ReadFile(c.StateFile)
	if !bytes.Equal(before, after) {
		t.Fatal("owner mismatch modified state")
	}
}
