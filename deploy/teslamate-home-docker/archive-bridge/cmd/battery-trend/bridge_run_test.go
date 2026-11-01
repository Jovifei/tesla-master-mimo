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

func TestRunStopsOnPermanentClientErrorAndRestartsFrozenBatch(t *testing.T) {
	for _, status := range []int{http.StatusBadRequest, http.StatusUnauthorized, http.StatusForbidden, http.StatusNotFound, http.StatusConflict, http.StatusUnprocessableEntity} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			var calls atomic.Int32
			var current atomic.Int32
			current.Store(int32(status))
			var mu sync.Mutex
			var bodies [][]byte
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				body, err := io.ReadAll(r.Body)
				if err != nil {
					t.Error(err)
					return
				}
				calls.Add(1)
				mu.Lock()
				bodies = append(bodies, append([]byte(nil), body...))
				mu.Unlock()
				if code := int(current.Load()); code != http.StatusOK {
					w.WriteHeader(code)
					return
				}
				var request trendRequest
				if json.Unmarshal(body, &request) != nil {
					t.Error("invalid synthetic request")
					return
				}
				syntheticReceipt(w, request, 0, len(request.Observations), 0)
			}))
			defer server.Close()
			c := syntheticConfig(t, server.URL)
			c.PollInterval = 20 * time.Millisecond
			source := &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}
			worker := bridge{config: c, source: source, client: server.Client()}
			ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
			defer cancel()
			err := worker.run(ctx)
			var stopped trendHTTPError
			if !errors.As(err, &stopped) || stopped.status != status || calls.Load() != 1 {
				t.Fatal("permanent response did not stop after one request")
			}
			state, err := loadState(c)
			if err != nil || state.LastDriveID != 0 || state.Pending == nil {
				t.Fatal("permanent response changed pending progress")
			}
			data, err := os.ReadFile(c.StateFile)
			if err != nil || bytes.Contains(data, []byte(c.ArchiveToken)) || bytes.Contains(data, []byte(c.DatabaseURL)) {
				t.Fatal("credentials appeared in independent state")
			}
			original, err := os.ReadFile(c.OriginalStateFile)
			if err != nil || string(original) != "original sentinel" {
				t.Fatal("original bridge state changed")
			}

			current.Store(http.StatusOK)
			restartedSource := &fakeSource{}
			restarted := bridge{config: c, source: restartedSource, client: server.Client()}
			if err := restarted.pollOnce(context.Background()); err != nil {
				t.Fatal("frozen batch could not be retried after restart")
			}
			state, err = loadState(c)
			mu.Lock()
			sameBody := len(bodies) == 2 && bytes.Equal(bodies[0], bodies[1])
			mu.Unlock()
			if err != nil || state.LastDriveID != 1 || state.Pending != nil || restartedSource.highCalls != 0 || restartedSource.fetchCalls != 0 || !sameBody {
				t.Fatal("restart did not safely replay the frozen batch")
			}
		})
	}
}

func TestRunRetriesTransientErrorWithoutAdvancingFirst(t *testing.T) {
	for _, status := range []int{http.StatusRequestTimeout, http.StatusTooManyRequests, http.StatusServiceUnavailable} {
		t.Run(http.StatusText(status), func(t *testing.T) { testRunRetriesTransientError(t, status) })
	}
}

func testRunRetriesTransientError(t *testing.T, status int) {
	t.Helper()
	var calls atomic.Int32
	var mu sync.Mutex
	var bodies [][]byte
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
			w.WriteHeader(status)
			return
		}
		var request trendRequest
		if json.Unmarshal(body, &request) != nil {
			t.Error("invalid synthetic request")
			return
		}
		syntheticReceipt(w, request, 1, 0, 0)
	}))
	defer server.Close()
	c := syntheticConfig(t, server.URL)
	c.PollInterval = 10 * time.Millisecond
	source := &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}
	worker := bridge{config: c, source: source, client: server.Client()}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- worker.run(ctx) }()
	defer cancel()
	deadline := time.After(5 * time.Second)
	for {
		state, err := loadState(c)
		if err == nil && state.LastDriveID == 1 && state.Pending == nil {
			break
		}
		select {
		case err := <-done:
			t.Fatalf("transient error stopped bridge: %v", err)
		case <-deadline:
			t.Fatal("transient retry did not finish")
		case <-time.After(10 * time.Millisecond):
		}
	}
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatal("bridge did not stop on cancellation")
	}
	mu.Lock()
	sameBody := len(bodies) == 2 && bytes.Equal(bodies[0], bodies[1])
	mu.Unlock()
	if calls.Load() != 2 || source.fetchCalls != 1 || !sameBody {
		t.Fatal("transient retry did not replay one frozen batch")
	}
}

func TestTrendRetryAfterParsing(t *testing.T) {
	now := time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)
	for _, test := range []struct {
		raw  string
		want time.Duration
	}{
		{"1", time.Second}, {" 2 ", 2 * time.Second}, {"0", 0}, {"-1", 0}, {"invalid", 0},
		{now.Add(10 * time.Second).Format(http.TimeFormat), 10 * time.Second},
		{now.Add(-time.Second).Format(http.TimeFormat), 0},
		{"9999999999999999999999999", time.Duration(1<<63 - 1)},
	} {
		if got := trendRetryAfter(test.raw, now); got != test.want {
			t.Fatalf("Retry-After %q: got %v, want %v", test.raw, got, test.want)
		}
	}
}

func TestRateLimitHonorsRetryAfterAndCanBeCancelled(t *testing.T) {
	for _, cancelDuringWait := range []bool{false, true} {
		name := "retry-after-delay"
		if cancelDuringWait {
			name = "cancel-during-delay"
		}
		t.Run(name, func(t *testing.T) {
			var calls atomic.Int32
			var first, second time.Time
			var mu sync.Mutex
			entered := make(chan struct{})
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				body, err := io.ReadAll(r.Body)
				if err != nil {
					t.Error(err)
					return
				}
				count := calls.Add(1)
				mu.Lock()
				if count == 1 {
					first = time.Now()
				} else {
					second = time.Now()
				}
				mu.Unlock()
				if count == 1 {
					if cancelDuringWait {
						w.Header().Set("Retry-After", "3600")
					} else {
						w.Header().Set("Retry-After", "1")
					}
					w.WriteHeader(http.StatusTooManyRequests)
					close(entered)
					return
				}
				var request trendRequest
				if json.Unmarshal(body, &request) != nil {
					t.Error("invalid synthetic request")
					return
				}
				syntheticReceipt(w, request, 1, 0, 0)
			}))
			defer server.Close()
			c := syntheticConfig(t, server.URL)
			c.PollInterval = 10 * time.Millisecond
			worker := bridge{config: c, source: &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}, client: server.Client()}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() { done <- worker.run(ctx) }()
			select {
			case <-entered:
			case <-time.After(5 * time.Second):
				t.Fatal("first synthetic request missing")
			}
			if cancelDuringWait {
				select {
				case <-time.After(60 * time.Millisecond):
				case err := <-done:
					t.Fatalf("bridge stopped early: %v", err)
				}
				cancel()
				select {
				case err := <-done:
					if !errors.Is(err, context.Canceled) {
						t.Fatal("cancel did not stop cooldown")
					}
				case <-time.After(time.Second):
					t.Fatal("cancel blocked during cooldown")
				}
				state, err := loadState(c)
				if err != nil || state.LastDriveID != 0 || state.Pending == nil || calls.Load() != 1 {
					t.Fatal("cooldown cancellation changed pending batch")
				}
				return
			}
			deadline := time.After(5 * time.Second)
			for {
				state, err := loadState(c)
				if err == nil && state.LastDriveID == 1 && state.Pending == nil {
					break
				}
				select {
				case err := <-done:
					t.Fatalf("bridge stopped early: %v", err)
				case <-deadline:
					t.Fatal("rate-limit retry did not complete")
				case <-time.After(10 * time.Millisecond):
				}
			}
			cancel()
			if err := <-done; !errors.Is(err, context.Canceled) {
				t.Fatal("bridge did not stop on cancellation")
			}
			mu.Lock()
			elapsed := second.Sub(first)
			mu.Unlock()
			if calls.Load() != 2 || elapsed < 900*time.Millisecond {
				t.Fatalf("rate-limit retry happened too early: %v", elapsed)
			}
		})
	}
}
