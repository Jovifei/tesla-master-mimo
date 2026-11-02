package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync/atomic"
	"testing"
)

func TestUnownedStateStopsActualLegacyEntrypoints(t *testing.T) {
	if mode := os.Getenv("MATELINK_SYNTHETIC_ENTRY_MODE"); mode != "" {
		os.Args = []string{"battery-trend"}
		if mode == "stdin" {
			os.Args = append(os.Args, "--credentials-stdin")
		}
		main()
		return
	}
	var calls atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	for _, mode := range []string{"env", "stdin"} {
		for _, scenario := range []string{"absent", "empty", "cursor", "pending", "corrupt"} {
			t.Run(mode+"/"+scenario, func(t *testing.T) {
				c := syntheticConfig(t, server.URL)
				observation, err := observationFromDrive(syntheticDrive(1), c.SourceCarID)
				if err != nil {
					t.Fatal(err)
				}
				body, _, _, err := makeTrendRequest(c.SourceInstanceID, c.SourceVehicleID, []trendObservation{observation})
				if err != nil {
					t.Fatal(err)
				}
				switch scenario {
				case "empty":
					err = saveState(c, trendState{Version: 1, ScopeHash: c.ScopeHash})
				case "cursor":
					err = saveState(c, trendState{Version: 1, ScopeHash: c.ScopeHash, LastDriveID: 1})
				case "pending":
					err = saveState(c, trendState{Version: 1, ScopeHash: c.ScopeHash, HighWaterID: 1, Pending: &pendingBatch{Body: body, LastDriveID: 1}})
				case "corrupt":
					err = os.WriteFile(c.StateFile, []byte("synthetic-invalid-state"), 0600)
				}
				if err != nil {
					t.Fatal(err)
				}
				before, beforeErr := os.ReadFile(c.StateFile)
				original, _ := os.ReadFile(c.OriginalStateFile)
				vars := testNonSecretEnv(c)
				vars["MATELINK_SYNTHETIC_ENTRY_MODE"] = mode
				if mode == "env" {
					vars["DATABASE_URL"], vars["ARCHIVE_TOKEN"] = c.DatabaseURL, "synthetic-owner-b-token"
				}
				var env []string
				for _, item := range os.Environ() {
					name, _, _ := strings.Cut(item, "=")
					if name == "DATABASE_URL" || name == "ARCHIVE_TOKEN" {
						continue
					}
					if _, replaced := vars[name]; !replaced {
						env = append(env, item)
					}
				}
				for name, value := range vars {
					env = append(env, name+"="+value)
				}
				cmd := exec.Command(os.Args[0], "-test.run=^TestUnownedStateStopsActualLegacyEntrypoints$")
				cmd.Env = env
				if mode == "stdin" {
					input, _ := json.Marshal(map[string]string{"database_url": c.DatabaseURL, "archive_token": "synthetic-owner-b-token"})
					cmd.Stdin = bytes.NewReader(input)
				} else {
					cmd.Stdin = strings.NewReader("")
				}
				output, err := cmd.CombinedOutput()
				if err == nil || (!bytes.Contains(output, []byte("battery trend requires verified owner")) &&
					!bytes.Contains(output, []byte("battery trend owner identity unavailable"))) {
					t.Fatal("actual entrypoint did not stop before network")
				}
				if bytes.Contains(output, []byte("synthetic-owner-b-token")) || bytes.Contains(output, []byte(c.DatabaseURL)) {
					t.Fatal("credential appeared in logs")
				}
				after, afterErr := os.ReadFile(c.StateFile)
				originalAfter, _ := os.ReadFile(c.OriginalStateFile)
				if os.IsNotExist(beforeErr) != os.IsNotExist(afterErr) || !bytes.Equal(before, after) || !bytes.Equal(original, originalAfter) || calls.Load() != 0 {
					t.Fatal("entrypoint changed state or sent data")
				}
			})
		}
	}
}
