//go:build linux

package credentialsocket

import (
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCredentialSocketRestrictedAndRestartable(t *testing.T) {
	dir := t.TempDir()
	if err := os.Chmod(dir, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "credentials.sock")
	want := Credentials{DatabaseURL: "postgres://synthetic:private@localhost/synthetic", ArchiveToken: "synthetic-private-token", ArchiveURL: "https://synthetic.example", VehicleID: "4", SourceInstanceID: "synthetic", SourceVehicleID: "2"}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	s, err := Listen(path, want)
	if err != nil {
		t.Fatal(err)
	}
	got, err := Fetch(ctx, path)
	if err != nil || got != want {
		t.Fatal("authorized peer could not receive matching credentials")
	}
	if _, err := Listen(path, want); err == nil {
		t.Fatal("second broker acquired an active socket")
	}
	info, err := os.Lstat(path)
	if err != nil || info.Mode()&os.ModeSocket == 0 || info.Mode().Perm() != 0600 {
		t.Fatal("socket permissions not private")
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	// A crash can leave a stale socket. The lock must permit safe recovery.
	stale, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	stale.(*net.UnixListener).SetUnlinkOnClose(false)
	if err := stale.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = Listen(path, want)
	if err != nil {
		t.Fatal("stale owned socket did not recover")
	}
	defer s.Close()
	if got, err = Fetch(ctx, path); err != nil || got != want {
		t.Fatal("restarted broker did not deliver credentials")
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if entry.Type().IsRegular() {
			data, err := os.ReadFile(filepath.Join(dir, entry.Name()))
			if err != nil || strings.Contains(string(data), want.ArchiveToken) || strings.Contains(string(data), want.DatabaseURL) {
				t.Fatal("credentials persisted to disk")
			}
		}
	}
}

func TestCredentialSocketRejectsUnsafePathsAndUnavailableBroker(t *testing.T) {
	dir := t.TempDir()
	if err := os.Chmod(dir, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "credentials.sock")
	if _, err := Fetch(context.Background(), path); !errors.Is(err, ErrUnavailable) {
		t.Fatal("missing broker should be retryable")
	}
	if err := os.WriteFile(path, []byte("do not replace"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := Listen(path, Credentials{}); err == nil {
		t.Fatal("regular file was replaced")
	}
	data, err := os.ReadFile(path)
	if err != nil || string(data) != "do not replace" {
		t.Fatal("existing file changed")
	}
	if err := os.Chmod(dir, 0777); err != nil {
		t.Fatal(err)
	}
	if _, err := Fetch(context.Background(), path); !errors.Is(err, ErrRejected) {
		t.Fatal("unsafe directory accepted")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := Fetch(ctx, "relative.sock"); !errors.Is(err, ErrRejected) {
		t.Fatal("relative socket accepted")
	}
}

func TestCredentialSocketDoesNotReplaceLiveForeignSocket(t *testing.T) {
	dir := t.TempDir()
	if err := os.Chmod(dir, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "credentials.sock")
	foreign, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer foreign.Close()
	_, err = Listen(path, Credentials{DatabaseURL: "postgres://synthetic", ArchiveToken: "synthetic"})
	if err == nil {
		t.Fatal("active socket was replaced")
	}
	conn, err := net.DialTimeout("unix", path, time.Second)
	if err != nil {
		t.Fatal("active socket was disrupted")
	}
	conn.Close()
}
