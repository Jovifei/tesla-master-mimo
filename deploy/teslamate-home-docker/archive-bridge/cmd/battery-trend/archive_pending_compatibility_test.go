package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestExistingBridgeRetriesArchivePending503WithoutAdvancing(t *testing.T) {
	var calls atomic.Int32
	var mu sync.Mutex
	var bodies [][]byte
	first := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Error(err)
			return
		}
		mu.Lock()
		bodies = append(bodies, append([]byte(nil), body...))
		mu.Unlock()
		if calls.Add(1) == 1 {
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Retry-After", "1")
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"error":"battery_trend_archive_pending"}`))
			close(first)
			return
		}
		var request trendRequest
		if json.Unmarshal(body, &request) != nil {
			t.Error("invalid synthetic request")
			return
		}
		syntheticReceipt(w, request, len(request.Observations), 0, 0)
	}))
	defer server.Close()
	c := syntheticConfig(t, server.URL)
	c.PollInterval = 20 * time.Millisecond
	source := &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}
	worker := bridge{config: c, source: source, client: server.Client()}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- worker.run(ctx) }()
	select {
	case <-first:
	case <-time.After(5 * time.Second):
		t.Fatal("first request did not arrive")
	}
	state, err := loadState(c)
	if err != nil || state.LastDriveID != 0 || state.Pending == nil {
		t.Fatal("503 lost the pending batch or advanced the cursor")
	}
	pending := append([]byte(nil), state.Pending.Body...)
	original, err := os.ReadFile(c.OriginalStateFile)
	if err != nil || string(original) != "original sentinel" {
		t.Fatal("original bridge state changed")
	}
	select {
	case <-time.After(150 * time.Millisecond):
	case err := <-done:
		t.Fatalf("503 stopped the bridge: %v", err)
	}
	if calls.Load() != 1 {
		t.Fatal("bridge ignored the pending response retry delay")
	}
	deadline := time.After(5 * time.Second)
	for {
		state, err = loadState(c)
		if err == nil && state.LastDriveID == 1 && state.Pending == nil {
			break
		}
		select {
		case err := <-done:
			t.Fatalf("bridge stopped before recovery: %v", err)
		case <-deadline:
			t.Fatal("pending batch did not recover")
		case <-time.After(10 * time.Millisecond):
		}
	}
	cancel()
	if err = <-done; !errors.Is(err, context.Canceled) {
		t.Fatal("bridge did not stop on cancellation")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(bodies) != 2 || !bytes.Equal(bodies[0], pending) || !bytes.Equal(bodies[0], bodies[1]) || source.fetchCalls != 1 {
		t.Fatal("recovery did not replay exactly the frozen pending batch")
	}
}

func TestExistingBridgeRestartsPending503FromFrozenState(t *testing.T) {
	var ready atomic.Bool
	var mu sync.Mutex
	var bodies [][]byte
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		mu.Lock()
		bodies = append(bodies, append([]byte(nil), body...))
		mu.Unlock()
		if !ready.Load() {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"error":"battery_trend_archive_pending"}`))
			return
		}
		var request trendRequest
		if json.Unmarshal(body, &request) != nil {
			t.Error("invalid synthetic request")
			return
		}
		syntheticReceipt(w, request, len(request.Observations), 0, 0)
	}))
	defer server.Close()
	c := syntheticConfig(t, server.URL)
	worker := bridge{config: c, source: &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}, client: server.Client()}
	var status trendHTTPError
	if err := worker.pollOnce(context.Background()); !errors.As(err, &status) || status.status != http.StatusServiceUnavailable {
		t.Fatal("pending response was not retained as existing transient status")
	}
	state, err := loadState(c)
	if err != nil || state.Pending == nil || state.LastDriveID != 0 {
		t.Fatal("pending state missing before restart")
	}
	pending := append([]byte(nil), state.Pending.Body...)
	ready.Store(true)
	source := &fakeSource{}
	restarted := bridge{config: c, source: source, client: server.Client()}
	if err = restarted.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	state, err = loadState(c)
	mu.Lock()
	defer mu.Unlock()
	if err != nil || state.LastDriveID != 1 || state.Pending != nil || source.fetchCalls != 0 || source.highCalls != 0 || len(bodies) != 2 || !bytes.Equal(bodies[0], pending) || !bytes.Equal(bodies[0], bodies[1]) {
		t.Fatal("restart did not recover the frozen pending batch")
	}
}
