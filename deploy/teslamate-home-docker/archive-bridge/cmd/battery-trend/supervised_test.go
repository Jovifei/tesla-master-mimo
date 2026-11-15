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
	"sync/atomic"
	"testing"
	"time"
)

type syntheticRoundTrip func(*http.Request) (*http.Response, error)

func (f syntheticRoundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestManagedNetworkFailureReplaysFrozenBatch(t *testing.T) {
	var bodies [][]byte
	var attempts atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var request trendRequest
		if json.Unmarshal(body, &request) != nil {
			t.Error("invalid synthetic request")
			return
		}
		syntheticReceipt(w, request, 1, 0, 0)
	}))
	defer server.Close()
	client := server.Client()
	base := client.Transport
	firstAttempt := make(chan struct{})
	allowRetry := make(chan struct{})
	client.Transport = syntheticRoundTrip(func(r *http.Request) (*http.Response, error) {
		body, _ := io.ReadAll(r.Body)
		bodies = append(bodies, append([]byte(nil), body...))
		r.Body = io.NopCloser(bytes.NewReader(body))
		if attempts.Add(1) == 1 {
			close(firstAttempt)
			return nil, errors.New("synthetic network outage")
		}
		select {
		case <-allowRetry:
		case <-r.Context().Done():
			return nil, r.Context().Err()
		}
		return base.RoundTrip(r)
	})
	c := syntheticConfig(t, server.URL)
	c.PollInterval = 10 * time.Millisecond
	b := bridge{config: c, source: &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}, client: client}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- runManaged(ctx, c, b.run) }()
	select {
	case <-firstAttempt:
	case <-time.After(2 * time.Second):
		t.Fatal("network attempt missing")
	}
	state, err := loadState(c)
	if err != nil || state.LastDriveID != 0 || state.Pending == nil {
		t.Fatal("network failure changed frozen state")
	}
	close(allowRetry)
	deadline := time.After(2 * time.Second)
	for {
		state, err = loadState(c)
		if err == nil && state.LastDriveID == 1 && state.Pending == nil {
			break
		}
		select {
		case <-deadline:
			t.Fatal("network retry did not complete")
		case <-time.After(10 * time.Millisecond):
		}
	}
	cancel()
	if err = <-done; !errors.Is(err, context.Canceled) {
		t.Fatal("managed bridge did not cancel")
	}
	if attempts.Load() != 2 || len(bodies) != 2 || !bytes.Equal(bodies[0], bodies[1]) {
		t.Fatal("network retry changed frozen request")
	}
	if halted, err := checkHalt(c); err != nil || halted {
		t.Fatal("network error created permanent halt")
	}
}

func TestManagedPermanentResponsePersistsStopAcrossRestart(t *testing.T) {
	for _, code := range []int{400, 401, 403, 404, 409, 422} {
		t.Run(http.StatusText(code), func(t *testing.T) {
			var status atomic.Int32
			var calls atomic.Int32
			var first []byte
			status.Store(int32(code))
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				body, _ := io.ReadAll(r.Body)
				if calls.Add(1) == 1 {
					first = append([]byte(nil), body...)
				} else if !bytes.Equal(first, body) {
					t.Error("restart changed frozen request")
				}
				if value := int(status.Load()); value != http.StatusOK {
					w.WriteHeader(value)
					return
				}
				var request trendRequest
				if json.Unmarshal(body, &request) != nil {
					t.Error("invalid request")
					return
				}
				syntheticReceipt(w, request, 1, 0, 0)
			}))
			defer server.Close()
			c := syntheticConfig(t, server.URL)
			c.PollInterval = 10 * time.Millisecond
			b := bridge{config: c, source: &fakeSource{rows: []sourceDrive{syntheticDrive(1)}, high: 1}, client: server.Client()}
			if err := runManaged(context.Background(), c, b.run); !errors.Is(err, errManagedHalt) || calls.Load() != 1 {
				t.Fatal("permanent response did not halt")
			}
			before, err := os.ReadFile(c.StateFile)
			if err != nil {
				t.Fatal(err)
			}
			status.Store(http.StatusOK)
			if err := runManaged(context.Background(), c, b.run); !errors.Is(err, errManagedHalt) || calls.Load() != 1 {
				t.Fatal("restart retried without review")
			}
			after, err := os.ReadFile(c.StateFile)
			if err != nil || !bytes.Equal(before, after) {
				t.Fatal("restart altered pending state")
			}
			if err := os.Rename(c.StateFile+".halt", c.StateFile+".halt.reviewed"); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
			defer cancel()
			if err := runManaged(ctx, c, b.run); !errors.Is(err, context.DeadlineExceeded) {
				t.Fatal("explicit recovery did not resume")
			}
			state, err := loadState(c)
			if err != nil || state.LastDriveID != 1 || state.Pending != nil || calls.Load() != 2 {
				t.Fatal("frozen batch not acknowledged")
			}
			original, err := os.ReadFile(c.OriginalStateFile)
			if err != nil || string(original) != "original sentinel" {
				t.Fatal("original state changed")
			}
		})
	}
}
