package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestCursorSaveReplacesStateAtomically(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cursor.json")
	initial := Cursor{LastDriveID: 7, LastChargeID: 11}
	if err := saveCursor(path, initial); err != nil {
		t.Fatal(err)
	}
	original, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}

	wantErr := errors.New("rename failed")
	err = saveCursorWithRename(path, Cursor{LastDriveID: 99, LastChargeID: 101}, func(_, _ string) error {
		return wantErr
	})
	if !errors.Is(err, wantErr) {
		t.Fatalf("save error = %v, want %v", err, wantErr)
	}
	current, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(current) != string(original) {
		t.Fatalf("state changed after failed atomic replace: %q != %q", current, original)
	}
	if leftovers, _ := filepath.Glob(filepath.Join(dir, ".cursor.json.tmp-*")); len(leftovers) != 0 {
		t.Fatalf("temporary cursor files remain: %v", leftovers)
	}
}

func TestBridgeDoesNotAdvanceCursorUntilImportSucceeds(t *testing.T) {
	// The first response is deliberately unsuccessful. The next poll must
	// retry the same source records and only then persist their IDs.
	responses := []int{httpStatusServerError, httpStatusNoContent}
	seen := make([]string, 0, len(responses))
	server := newArchiveTestServer(t, func(request archiveTestRequest) int {
		seen = append(seen, request.DriveSourceRecordID)
		return responses[len(seen)-1]
	})
	defer server.Close()

	dir := t.TempDir()
	config := testConfig(server.URL, filepath.Join(dir, "cursor.json"))
	source := fixtureSource{
		drives: []DriveRecord{{ID: 42, StartedAt: timePointer(testTime(1)), EndedAt: timePointer(testTime(2))}},
	}
	bridge := NewBridge(config, source)

	if err := bridge.PollOnce(t.Context()); err == nil {
		t.Fatal("first poll succeeded on a server error")
	}
	state, err := loadCursor(config.StateFile)
	if err != nil {
		t.Fatal(err)
	}
	if state != (Cursor{}) {
		t.Fatalf("cursor advanced after failed import: %#v", state)
	}
	if err := bridge.PollOnce(t.Context()); err != nil {
		t.Fatal(err)
	}
	state, err = loadCursor(config.StateFile)
	if err != nil {
		t.Fatal(err)
	}
	if state.LastDriveID != 42 {
		t.Fatalf("last drive ID = %d, want 42", state.LastDriveID)
	}
	if len(seen) != 2 || seen[0] != seen[1] {
		t.Fatalf("retry source IDs = %#v, want the same ID twice", seen)
	}
}

func TestCursorCannotBeReusedForAnotherCloudOrSourceVehicle(t *testing.T) {
	config := testConfig("https://api.example.com", filepath.Join(t.TempDir(), "cursor.json"))
	_, err := scopeCursor(config, Cursor{
		LastDriveID: 42, ArchiveVehicleID: "other-cloud-car",
		SourceInstanceID: config.SourceInstanceID, SourceVehicleID: config.SourceVehicleID,
	})
	if err == nil {
		t.Fatal("cursor from another cloud vehicle was accepted")
	}
}

func TestFreshCursorBindsToTheConfiguredScope(t *testing.T) {
	config := testConfig("https://api.example.com", filepath.Join(t.TempDir(), "cursor.json"))
	cursor, err := scopeCursor(config, Cursor{})
	if err != nil {
		t.Fatal(err)
	}
	if cursor.ArchiveVehicleID != config.ArchiveVehicleID || cursor.SourceInstanceID != config.SourceInstanceID || cursor.SourceVehicleID != config.SourceVehicleID {
		t.Fatalf("cursor scope = %#v", cursor)
	}
}

func TestOnePollPublishesEachFetchedSessionAtomicallyWithoutIntervalDelays(t *testing.T) {
	requestSizes := []int{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var payload ArchiveBatch
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		requestSizes = append(requestSizes, len(payload.Drives)+len(payload.Charges))
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprintf(w, `{"data":{"imported_drives":%d,"imported_charges":%d}}`, len(payload.Drives), len(payload.Charges))
	}))
	defer server.Close()
	config := testConfig(server.URL, filepath.Join(t.TempDir(), "cursor.json"))
	bridge := NewBridge(config, fixtureSource{drives: []DriveRecord{
		{ID: 1, StartedAt: timePointer(testTime(1)), EndedAt: timePointer(testTime(2))},
		{ID: 2, StartedAt: timePointer(testTime(2)), EndedAt: timePointer(testTime(3))},
		{ID: 3, StartedAt: timePointer(testTime(3)), EndedAt: timePointer(testTime(4))},
	}})

	if err := bridge.PollOnce(t.Context()); err != nil {
		t.Fatal(err)
	}
	cursor, err := loadCursor(config.StateFile)
	if err != nil {
		t.Fatal(err)
	}
	if cursor.LastDriveID != 3 {
		t.Fatalf("last drive ID = %d, want 3", cursor.LastDriveID)
	}
	if !reflect.DeepEqual(requestSizes, []int{1, 1, 1}) {
		t.Fatalf("atomic request sizes = %#v", requestSizes)
	}
}
