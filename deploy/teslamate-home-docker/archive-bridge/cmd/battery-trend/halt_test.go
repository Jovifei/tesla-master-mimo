package main

import (
	"bytes"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"testing"
)

func TestHaltMarkerPreservesStateAndIsIdempotent(t *testing.T) {
	c := syntheticConfig(t, "https://synthetic.example")
	c.DatabaseURL = "synthetic-database-secret"
	c.ArchiveToken = "synthetic-archive-secret"
	pending := []byte("unchanged pending and cursor")
	if err := os.WriteFile(c.StateFile, pending, 0o600); err != nil {
		t.Fatal(err)
	}
	halted, err := checkHalt(c)
	if err != nil || halted {
		t.Fatal("missing marker should not halt")
	}
	if err := markHalt(c, http.StatusForbidden); err != nil {
		t.Fatal("could not persist halt")
	}
	halted, err = checkHalt(c)
	if err != nil || !halted {
		t.Fatal("persisted marker did not halt")
	}
	markerPath := c.StateFile + ".halt"
	marker, err := os.ReadFile(markerPath)
	if err != nil || string(marker) != "http_status=403\n" {
		t.Fatal("marker did not contain only the fixed status")
	}
	if runtime.GOOS != "windows" {
		info, err := os.Stat(markerPath)
		if err != nil || info.Mode().Perm()&0o077 != 0 {
			t.Fatal("marker permissions are not private")
		}
	}
	if err := markHalt(c, http.StatusConflict); err != nil {
		t.Fatal("repeated halt should be idempotent")
	}
	again, _ := os.ReadFile(markerPath)
	state, _ := os.ReadFile(c.StateFile)
	original, _ := os.ReadFile(c.OriginalStateFile)
	if !bytes.Equal(marker, again) || !bytes.Equal(state, pending) || string(original) != "original sentinel" {
		t.Fatal("existing marker or source state changed")
	}
	for _, secret := range []string{c.DatabaseURL, c.ArchiveToken} {
		if bytes.Contains(marker, []byte(secret)) || bytes.Contains(state, []byte(secret)) {
			t.Fatal("credentials appeared in state or halt marker")
		}
	}
}

func TestHaltMarkerAllowsOnlyPermanentClientErrors(t *testing.T) {
	for _, status := range []int{400, 401, 403, 404, 409, 422, 499, 0, 200, 399, 408, 429, 500, 599} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			c := syntheticConfig(t, "https://synthetic.example")
			valid := status >= 400 && status <= 499 && status != 408 && status != 429
			err := markHalt(c, status)
			halted, checkErr := checkHalt(c)
			if (err == nil) != valid || checkErr != nil || halted != valid {
				t.Fatal("status changed halt behavior incorrectly")
			}
			original, _ := os.ReadFile(c.OriginalStateFile)
			if string(original) != "original sentinel" {
				t.Fatal("status handling changed original state")
			}
		})
	}
}

func TestHaltMarkerExistingNodesNeverOverwritten(t *testing.T) {
	for _, kind := range []string{"empty", "broken", "directory", "symlink", "symlink-original", "hardlink-original", "hardlink-other"} {
		t.Run(kind, func(t *testing.T) {
			c := syntheticConfig(t, "https://synthetic.example")
			path := c.StateFile + ".halt"
			var err error
			switch kind {
			case "empty":
				err = os.WriteFile(path, nil, 0o600)
			case "broken":
				err = os.WriteFile(path, []byte("partial marker"), 0o600)
			case "directory":
				err = os.Mkdir(path, 0o700)
			case "symlink":
				err = os.Symlink(filepath.Join(filepath.Dir(path), "missing"), path)
			case "symlink-original":
				err = os.Symlink(c.OriginalStateFile, path)
			case "hardlink-original":
				err = os.Link(c.OriginalStateFile, path)
			case "hardlink-other":
				other := filepath.Join(filepath.Dir(path), "other")
				if err = os.WriteFile(other, []byte("other sentinel"), 0o600); err == nil {
					err = os.Link(other, path)
				}
			}
			if err != nil && (kind == "symlink" || kind == "symlink-original" || kind == "hardlink-original" || kind == "hardlink-other") {
				t.Skip("filesystem does not permit link creation")
			}
			if err != nil {
				t.Fatal(err)
			}
			before, err := os.Lstat(path)
			if err != nil {
				t.Fatal(err)
			}
			contents, _ := os.ReadFile(path)
			halted, _ := checkHalt(c)
			if !halted {
				t.Fatal("existing node did not fail closed")
			}
			_ = markHalt(c, http.StatusForbidden)
			after, err := os.Lstat(path)
			actual, _ := os.ReadFile(path)
			original, _ := os.ReadFile(c.OriginalStateFile)
			if err != nil || !os.SameFile(before, after) || !bytes.Equal(contents, actual) || string(original) != "original sentinel" {
				t.Fatal("existing node or original state changed")
			}
		})
	}
}

func TestHaltMarkerCannotAliasOriginal(t *testing.T) {
	c := syntheticConfig(t, "https://synthetic.example")
	c.StateFile = filepath.Join(filepath.Dir(c.OriginalStateFile), "same")
	c.OriginalStateFile = c.StateFile + ".halt"
	if err := os.WriteFile(c.OriginalStateFile, []byte("original sentinel"), 0o600); err != nil {
		t.Fatal(err)
	}
	halted, checkErr := checkHalt(c)
	markErr := markHalt(c, http.StatusConflict)
	if !halted || checkErr == nil || markErr == nil {
		t.Fatal("original state alias did not fail closed")
	}
	original, _ := os.ReadFile(c.OriginalStateFile)
	if string(original) != "original sentinel" {
		t.Fatal("original state alias was overwritten")
	}
}

func TestHaltMarkerFailsClosedOnPathErrors(t *testing.T) {
	c := syntheticConfig(t, "https://synthetic.example")
	c.ArchiveToken = "synthetic-secret"
	c.StateFile = filepath.Join(filepath.Dir(c.StateFile), "missing-directory", "state")
	halted, err := checkHalt(c)
	markErr := markHalt(c, http.StatusConflict)
	if !halted || err == nil || markErr == nil {
		t.Fatal("unavailable marker directory did not fail closed")
	}
	if bytes.Contains([]byte(err.Error()), []byte(c.ArchiveToken)) || bytes.Contains([]byte(markErr.Error()), []byte(c.ArchiveToken)) {
		t.Fatal("halt error exposed credentials")
	}
	original, _ := os.ReadFile(c.OriginalStateFile)
	if string(original) != "original sentinel" {
		t.Fatal("path failure changed original state")
	}
}

func TestHaltMarkerConcurrentCreateNeverOverwrites(t *testing.T) {
	c := syntheticConfig(t, "https://synthetic.example")
	var group sync.WaitGroup
	errs := make(chan error, 16)
	for i := 0; i < cap(errs); i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			errs <- markHalt(c, http.StatusConflict)
		}()
	}
	group.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal("concurrent creation should be idempotent")
		}
	}
	marker, err := os.ReadFile(c.StateFile + ".halt")
	if err != nil || string(marker) != "http_status=409\n" {
		t.Fatal("concurrent create corrupted marker")
	}
}
