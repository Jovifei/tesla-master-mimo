package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

const (
	httpStatusServerError = http.StatusBadGateway
	httpStatusNoContent   = http.StatusOK
)

type fixtureSource struct {
	drives  []DriveRecord
	charges []ChargeRecord
}

func (f fixtureSource) FetchDrives(_ context.Context, _, _ int64) ([]DriveRecord, error) {
	return append([]DriveRecord(nil), f.drives...), nil
}

func (f fixtureSource) FetchCharges(_ context.Context, _, _ int64) ([]ChargeRecord, error) {
	return append([]ChargeRecord(nil), f.charges...), nil
}

type archiveTestRequest struct {
	DriveSourceRecordID string
}

func newArchiveTestServer(t *testing.T, status func(archiveTestRequest) int) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Error(err)
		}
		var payload struct {
			Drives []struct {
				SourceRecordID string `json:"source_record_id"`
			} `json:"drives"`
		}
		if err := json.Unmarshal(body, &payload); err != nil {
			t.Error(err)
		}
		request := archiveTestRequest{}
		if len(payload.Drives) != 0 {
			request.DriveSourceRecordID = payload.Drives[0].SourceRecordID
		}
		responseStatus := status(request)
		w.WriteHeader(responseStatus)
		if responseStatus >= http.StatusOK && responseStatus < http.StatusMultipleChoices {
			_, _ = w.Write([]byte(`{"data":{"imported_drives":1,"imported_charges":0}}`))
		}
	}))
}

func testConfig(archiveURL, stateFile string) Config {
	return Config{
		DatabaseURL:      "postgres://unit.test/teslamate",
		ArchiveURL:       archiveURL,
		ArchiveToken:     "test-token",
		ArchiveVehicleID: "vehicle-1",
		SourceInstanceID: "instance-1",
		SourceVehicleID:  "8",
		SourceCarID:      8,
		StateFile:        stateFile,
		BatchSize:        100,
		PollInterval:     time.Second,
	}
}
