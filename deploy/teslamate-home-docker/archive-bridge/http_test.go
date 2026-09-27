package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestImportRequestURLAuthAndErrorRedaction(t *testing.T) {
	const token = "unit-test-token"
	const responseBody = `token=unit-test-token vin=VIN-REDACTED coordinates=31.2304,121.4737`
	var received *http.Request
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received = r
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte(responseBody))
	}))
	defer server.Close()

	config := testConfig(server.URL+"/archive-root/", filepath.Join(t.TempDir(), "cursor.json"))
	config.ArchiveToken = token
	config.ArchiveVehicleID = "vehicle-42"
	bridge := NewBridge(config, fixtureSource{})
	err := bridge.importBatch(t.Context(), ArchiveBatch{
		SourceInstanceID: config.SourceInstanceID,
		SourceVehicleID:  config.SourceVehicleID,
		SourceType:       "teslamate",
		ChunkID:          "chunk-test",
	})
	if err == nil {
		t.Fatal("import succeeded on 502")
	}
	if strings.Contains(err.Error(), token) || strings.Contains(err.Error(), "VIN-REDACTED") || strings.Contains(err.Error(), "31.2304") || strings.Contains(err.Error(), responseBody) {
		t.Fatalf("sensitive response data leaked through error: %v", err)
	}
	if received == nil {
		t.Fatal("server did not receive a request")
	}
	wantPath := "/archive-root/api/v1/cars/vehicle-42/history/archive/import"
	if received.Method != http.MethodPost || received.URL.Path != wantPath {
		t.Fatalf("request = %s %s, want POST %s", received.Method, received.URL.Path, wantPath)
	}
	if got := received.Header.Get("Authorization"); got != "Bearer "+token {
		t.Fatalf("authorization = %q", got)
	}
	if got := received.Header.Get("Content-Type"); got != "application/json" {
		t.Fatalf("content type = %q", got)
	}
	if received.URL.RawQuery != "" {
		t.Fatalf("unexpected query string: %q", received.URL.RawQuery)
	}
}

func TestImportBatchContainsSourceEnvelope(t *testing.T) {
	batch := ArchiveBatch{
		SourceInstanceID: "home-instance",
		SourceVehicleID:  "8",
		SourceType:       "teslamate",
		ChunkID:          "chunk-1",
		Drives:           []ArchiveDrive{{SourceRecordID: "teslamate:drive:42"}},
	}
	encoded, err := json.Marshal(batch)
	if err != nil {
		t.Fatal(err)
	}
	body := string(encoded)
	for _, fragment := range []string{`"source_instance_id":"home-instance"`, `"source_vehicle_id":"8"`, `"source_type":"teslamate"`, `"chunk_id":"chunk-1"`, `"source_record_id":"teslamate:drive:42"`} {
		if !strings.Contains(body, fragment) {
			t.Fatalf("request body missing %s: %s", fragment, body)
		}
	}
}
