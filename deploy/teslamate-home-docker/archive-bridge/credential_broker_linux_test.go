//go:build linux

package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/matelink/teslamate-archive-bridge/internal/credentialsocket"
)

func TestCredentialBrokerRecoversWithoutBlockingArchive(t *testing.T) {
	parent := t.TempDir()
	dir := filepath.Join(parent, "private")
	path := filepath.Join(dir, "credentials.sock")
	creds := credentialsocket.Credentials{DatabaseURL: "postgres://synthetic", ArchiveToken: "synthetic-token"}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); runCredentialBroker(ctx, path, creds, 10*time.Millisecond) }()
	defer cancel()
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for {
		got, err := credentialsocket.Fetch(context.Background(), path)
		if err == nil && got == creds {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("broker did not recover")
		}
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("broker did not stop")
	}
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		t.Fatal("socket was not cleaned up")
	}
}
