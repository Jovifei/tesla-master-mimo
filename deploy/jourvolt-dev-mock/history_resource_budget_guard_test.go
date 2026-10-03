package main

import (
	"context"
	"errors"
	"testing"
)

func TestHistoryResourceAdmissionEnforcesGlobalUserAndVehicleScopes(t *testing.T) {
	guard := newHistoryResourceAdmission(4, 2, 1)
	first, err := guard.acquire(context.Background(), "u1", "v1")
	if err != nil {
		t.Fatal(err)
	}
	defer first()
	if _, err := guard.acquire(context.Background(), "u1", "v1"); !errors.Is(err, errHistoryResourceOverloaded) {
		t.Fatalf("expected vehicle isolation, got %v", err)
	}
	second, err := guard.acquire(context.Background(), "u1", "v2")
	if err != nil {
		t.Fatal(err)
	}
	defer second()
	third, err := guard.acquire(context.Background(), "u2", "v1")
	if err != nil {
		t.Fatal(err)
	}
	third()
}

func TestHistoryResourceAdmissionCanceledAfterMutexEntryDoesNotAdmit(t *testing.T) {
	guard := newHistoryResourceAdmission(1, 1, 1)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := guard.acquire(ctx, "u1", "v1"); !errors.Is(err, context.Canceled) {
		t.Fatalf("expected canceled context, got %v", err)
	}
}

func TestHistoryResourceAdmissionReleaseIsIdempotent(t *testing.T) {
	guard := newHistoryResourceAdmission(1, 1, 1)
	release, err := guard.acquire(context.Background(), "u1", "v1")
	if err != nil {
		t.Fatal(err)
	}
	release()
	release()
	if _, err := guard.acquire(context.Background(), "u2", "v2"); err != nil {
		t.Fatal(err)
	}
}
