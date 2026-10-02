package main

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

func TestHistoryReadBudgetRollbackAfterContextCancellation(t *testing.T) {
	budget := newHistoryReadBudget(1)
	release, err := budget.acquire(context.Background(), "user-a", "vehicle-a")
	if err != nil {
		t.Fatal(err)
	}
	defer release()

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := budget.acquire(ctx, "user-b", "vehicle-b"); !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context cancellation, got %v", err)
	}

	// Failed acquisition must not consume global capacity.
	if _, err := budget.acquire(context.Background(), "user-c", "vehicle-c"); !errors.Is(err, errHistoryResourceBudgetExceeded) {
		t.Fatalf("expected budget exhaustion while first holder remains, got %v", err)
	}
}

func TestHistoryReadBudgetReleaseIsIdempotentAtCallSite(t *testing.T) {
	budget := newHistoryReadBudget(1)
	release, err := budget.acquire(context.Background(), "user-a", "vehicle-a")
	if err != nil {
		t.Fatal(err)
	}

	// The primitive returns a release closure. A caller should guard against
	// accidental repeated cleanup; this test documents that double cleanup is
	// rejected by the primitive rather than silently corrupting semaphore state.
	release()
	defer func() {
		if recover() == nil {
			t.Fatal("expected repeated release to be detected")
		}
	}()
	release()
}

func TestHistoryReadBudgetIsolatesUsersAndVehicles(t *testing.T) {
	budget := newHistoryReadBudget(4)
	first, err := budget.acquire(context.Background(), "user-a", "vehicle-a")
	if err != nil {
		t.Fatal(err)
	}
	defer first()

	if second, err := budget.acquire(context.Background(), "user-b", "vehicle-b"); err != nil {
		t.Fatalf("different tenant should proceed: %v", err)
	} else {
		second()
	}

	// Same vehicle is isolated even if another user has room globally.
	limited := newHistoryReadBudget(2)
	one, err := limited.acquire(context.Background(), "user-a", "vehicle-a")
	if err != nil {
		t.Fatal(err)
	}
	defer one()

	if _, err := limited.acquire(context.Background(), "user-a", "vehicle-a"); !errors.Is(err, errHistoryResourceBudgetExceeded) {
		t.Fatalf("same vehicle should be bounded, got %v", err)
	}
}

func TestHistoryReadBudgetConcurrentCancellationDoesNotLeak(t *testing.T) {
	budget := newHistoryReadBudget(1)
	for i := 0; i < 20; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), time.Millisecond)
		var wg sync.WaitGroup
		wg.Add(1)
		go func() {
			defer wg.Done()
			if release, err := budget.acquire(ctx, "user", "vehicle"); err == nil {
				release()
			}
		}()
		wg.Wait()
		cancel()
	}
	if release, err := budget.acquire(context.Background(), "final-user", "final-vehicle"); err != nil {
		t.Fatalf("capacity leaked after cancellation: %v", err)
	} else {
		release()
	}
}
