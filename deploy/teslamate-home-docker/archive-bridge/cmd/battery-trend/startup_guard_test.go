package main

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

const unownedStateError = "battery trend state requires owner verification"

func TestUnownedStartupAlsoRejectsAbsentAndEmptyState(t *testing.T) {
	c := syntheticConfig(t, "https://synthetic.example")
	if err := rejectUnownedRestartState(c); err == nil {
		t.Fatal("absent state must not be treated as owner proof")
	}
	if _, err := os.Stat(c.StateFile); !os.IsNotExist(err) {
		t.Fatal("guard created a state file")
	}
	if err := saveState(c, trendState{Version: 1, ScopeHash: c.ScopeHash}); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(c.StateFile)
	if err != nil || rejectUnownedRestartState(c) == nil {
		t.Fatal("empty state must not be treated as owner proof")
	}
	after, _ := os.ReadFile(c.StateFile)
	original, _ := os.ReadFile(c.OriginalStateFile)
	if !bytes.Equal(before, after) || string(original) != "original sentinel" {
		t.Fatal("guard modified state")
	}
}

func TestUnownedStartupRejectsPendingCursorAndWatermarkWithoutSending(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { requests.Add(1) }))
	defer server.Close()
	for _, scenario := range []string{"pending", "cursor", "high-water"} {
		for _, token := range []string{"same", "different"} {
			t.Run(scenario+"/"+token, func(t *testing.T) {
				c := syntheticConfig(t, server.URL)
				c.ArchiveToken = "synthetic-owner-a-token"
				state := trendState{Version: 1, ScopeHash: c.ScopeHash}
				switch scenario {
				case "pending":
					observation, err := observationFromDrive(syntheticDrive(1), c.SourceCarID)
					if err != nil {
						t.Fatal(err)
					}
					body, _, _, err := makeTrendRequest(c.SourceInstanceID, c.SourceVehicleID, []trendObservation{observation})
					if err != nil {
						t.Fatal(err)
					}
					state.HighWaterID = 1
					state.Pending = &pendingBatch{Body: body, LastDriveID: 1}
				case "cursor":
					state.LastDriveID = 1
				case "high-water":
					state.HighWaterID = 1
				}
				if err := saveState(c, state); err != nil {
					t.Fatal(err)
				}
				before, _ := os.ReadFile(c.StateFile)
				original, _ := os.ReadFile(c.OriginalStateFile)
				if token == "different" {
					c.ArchiveToken = "synthetic-owner-b-token"
				}
				err := rejectUnownedRestartState(c)
				if err == nil || err.Error() != unownedStateError {
					t.Fatal("unowned state was not rejected with fixed error")
				}
				after, _ := os.ReadFile(c.StateFile)
				originalAfter, _ := os.ReadFile(c.OriginalStateFile)
				if !bytes.Equal(before, after) || !bytes.Equal(original, originalAfter) || requests.Load() != 0 {
					t.Fatal("guard changed data or sent a request")
				}
			})
		}
	}
}

func TestUnownedStartupRejectsInvalidOrCrossScopeState(t *testing.T) {
	for _, scenario := range []string{"empty-file", "invalid-json", "unknown-field", "oversized", "cross-scope", "missing-directory", "original-alias"} {
		t.Run(scenario, func(t *testing.T) {
			c := syntheticConfig(t, "https://synthetic.example")
			c.ArchiveToken = "synthetic-secret-token"
			c.DatabaseURL = "synthetic-secret-url"
			if err := saveState(c, trendState{Version: 1, ScopeHash: c.ScopeHash}); err != nil {
				t.Fatal(err)
			}
			switch scenario {
			case "empty-file":
				if err := os.WriteFile(c.StateFile, nil, 0o600); err != nil {
					t.Fatal(err)
				}
			case "invalid-json":
				if err := os.WriteFile(c.StateFile, []byte("{"), 0o600); err != nil {
					t.Fatal(err)
				}
			case "unknown-field":
				if err := os.WriteFile(c.StateFile, []byte(`{"version":1,"unexpected":true}`), 0o600); err != nil {
					t.Fatal(err)
				}
			case "oversized":
				if err := os.WriteFile(c.StateFile, bytes.Repeat([]byte("x"), (1<<20)+1), 0o600); err != nil {
					t.Fatal(err)
				}
			case "cross-scope":
				c.ScopeHash = strings.Repeat("b", 64)
			case "missing-directory":
				c.StateFile = filepath.Join(filepath.Dir(c.StateFile), "missing", "state")
			case "original-alias":
				c.StateFile = c.OriginalStateFile
			}
			before, _ := os.ReadFile(c.StateFile)
			original, _ := os.ReadFile(c.OriginalStateFile)
			err := rejectUnownedRestartState(c)
			if err == nil || err.Error() != unownedStateError || strings.Contains(err.Error(), c.StateFile) ||
				strings.Contains(err.Error(), c.ArchiveToken) || strings.Contains(err.Error(), c.DatabaseURL) {
				t.Fatal("invalid state was not rejected with a private fixed error")
			}
			after, _ := os.ReadFile(c.StateFile)
			originalAfter, _ := os.ReadFile(c.OriginalStateFile)
			if !bytes.Equal(before, after) || !bytes.Equal(original, originalAfter) {
				t.Fatal("invalid state or original was modified")
			}
		})
	}
}
