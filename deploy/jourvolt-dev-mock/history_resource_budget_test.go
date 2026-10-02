package main

import (
	"context"
	"errors"
	"testing"
)

func TestHistoryReadBudgetRejectsCanceledContextEvenWhenFree(t *testing.T) {
	budget := newHistoryReadBudget(1)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := budget.acquire(ctx, "user-a", "vehicle-a"); !errors.Is(err, context.Canceled) {
		t.Fatalf("expected canceled context, got %v", err)
	}
}

func TestHistoryReadBudgetReleaseIsIdempotent(t *testing.T) {
	budget := newHistoryReadBudget(1)
	release, err := budget.acquire(context.Background(), "user-a", "vehicle-a")
	if err != nil {
		t.Fatal(err)
	}
	release()
	release()
	if _, err := budget.acquire(context.Background(), "user-b", "vehicle-b"); err != nil {
		t.Fatalf("idempotent cleanup leaked capacity: %v", err)
	}
}

func TestHistoryReadBudgetVehicleScopeIncludesUser(t *testing.T) {
	budget := newHistoryReadBudget(2)
	one, err := budget.acquire(context.Background(), "user-a", "vehicle-1")
	if err != nil {
		t.Fatal(err)
	}
	defer one()
	if two, err := budget.acquire(context.Background(), "user-b", "vehicle-1"); err != nil {
		t.Fatalf("same vehicle id across accounts interfered: %v", err)
	} else {
		two()
	}
}

func TestHistoryReadBudgetEvictsCompletedScopes(t *testing.T) {
	budget := newHistoryReadBudget(1)
	release, err := budget.acquire(context.Background(), "user-a", "vehicle-a")
	if err != nil {
		t.Fatal(err)
	}
	release()
	if len(budget.scopes) != 0 {
		t.Fatalf("completed scope retained: %v", budget.scopes)
	}
}

func TestHistoryReadBudgetFailedAcquireDoesNotConsumeCapacity(t *testing.T) {
	budget := newHistoryReadBudget(1)
	release, err := budget.acquire(context.Background(), "user-a", "vehicle-a")
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	if _, err := budget.acquire(context.Background(), "user-b", "vehicle-b"); !errors.Is(err, errHistoryResourceBudgetExceeded) {
		t.Fatalf("expected exhaustion, got %v", err)
	}
}
