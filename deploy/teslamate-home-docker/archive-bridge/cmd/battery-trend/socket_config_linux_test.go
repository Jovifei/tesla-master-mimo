//go:build linux

package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/matelink/teslamate-archive-bridge/internal/credentialsocket"
)

func TestSocketConfigRequiresMatchingScopeAndNoInheritedSecrets(t *testing.T) {
	c := syntheticConfig(t, "https://synthetic.example")
	env := testNonSecretEnv(c)
	dir := t.TempDir()
	if err := os.Chmod(dir, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "credentials.sock")
	env["BATTERY_TREND_CREDENTIAL_SOCKET"] = path
	lookup := func(name string) (string, bool) { value, ok := env[name]; return value, ok }
	if _, err := loadSocketConfig(context.Background(), lookup); !errors.Is(err, credentialsocket.ErrUnavailable) {
		t.Fatal("missing socket should retry")
	}
	broker, err := credentialsocket.Listen(path, credentialsocket.Credentials{
		DatabaseURL: c.DatabaseURL, ArchiveToken: c.ArchiveToken, ArchiveURL: c.ArchiveURL,
		VehicleID: c.ArchiveVehicleID, SourceInstanceID: c.SourceInstanceID, SourceVehicleID: c.SourceVehicleID,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer broker.Close()
	got, err := loadSocketConfig(context.Background(), lookup)
	if err != nil || got.ScopeHash != c.ScopeHash || got.ArchiveToken != c.ArchiveToken {
		t.Fatal("authorized socket config rejected")
	}
	if err := os.Rename(c.OriginalStateFile, c.OriginalStateFile+".waiting"); err != nil {
		t.Fatal(err)
	}
	if _, err := loadSocketConfig(context.Background(), lookup); !errors.Is(err, credentialsocket.ErrUnavailable) {
		t.Fatal("missing original state did not wait")
	}
	if err := os.Rename(c.OriginalStateFile+".waiting", c.OriginalStateFile); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"ARCHIVE_URL", "ARCHIVE_VEHICLE_ID", "ARCHIVE_SOURCE_INSTANCE_ID", "ARCHIVE_SOURCE_VEHICLE_ID"} {
		old := env[name]
		env[name] = "synthetic-other-scope"
		if _, err := loadSocketConfig(context.Background(), lookup); !errors.Is(err, credentialsocket.ErrRejected) {
			t.Fatal("mismatched scope accepted")
		}
		env[name] = old
	}
	for _, name := range []string{"DATABASE_URL", "ARCHIVE_TOKEN"} {
		env[name] = "synthetic-secret"
		if _, err := loadSocketConfig(context.Background(), lookup); !errors.Is(err, credentialsocket.ErrRejected) {
			t.Fatal("inherited credential environment accepted")
		}
		delete(env, name)
	}
	if err := broker.Close(); err != nil {
		t.Fatal(err)
	}
	rotated, err := credentialsocket.Listen(path, credentialsocket.Credentials{
		DatabaseURL: c.DatabaseURL, ArchiveToken: "synthetic-rotated-token", ArchiveURL: c.ArchiveURL,
		VehicleID: c.ArchiveVehicleID, SourceInstanceID: c.SourceInstanceID, SourceVehicleID: c.SourceVehicleID,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer rotated.Close()
	got, err = loadSocketConfig(context.Background(), lookup)
	if err != nil || got.ScopeHash != c.ScopeHash || got.ArchiveToken != "synthetic-rotated-token" {
		t.Fatal("same-scope credential rotation failed")
	}
}
