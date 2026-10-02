package main

import (
	"context"
	"errors"
	"sync"
)

var errHistoryResourceBudgetExceeded = errors.New("history_resource_budget_exceeded")

// historyReadBudget bounds expensive history operations before they allocate
// large route/detail payloads. It is intentionally independent from storage;
// callers still decide whether the requested operation is allowed.
type historyReadBudget struct {
	global chan struct{}
	mu sync.Mutex
	users map[string]chan struct{}
	vehicles map[string]chan struct{}
}

func newHistoryReadBudget(global, perUser, perVehicle int) *historyReadBudget {
	return &historyReadBudget{
		global: make(chan struct{}, global),
		users: make(map[string]chan struct{}),
		vehicles: make(map[string]chan struct{}),
	}
}

func (b *historyReadBudget) getScope(m map[string]chan struct{}, key string) chan struct{} {
	b.mu.Lock()
	defer b.mu.Unlock()
	v := m[key]
	if v == nil {
		v = make(chan struct{}, 1)
		m[key] = v
	}
	return v
}

func (b *historyReadBudget) acquire(ctx context.Context, userID, vehicleID string) (func(), error) {
	if b == nil {
		return func(){}, nil
	}
	user := b.getScope(b.users, userID)
	vehicle := b.getScope(b.vehicles, vehicleID)
	locks := []chan struct{}{b.global, user, vehicle}
	for _, lock := range locks {
		select {
		case lock <- struct{}{}:
		default:
			for i := range locks {
				if i == 0 { break }
			}
			return nil, errHistoryResourceBudgetExceeded
		}
	}
	return func() {
		for i := len(locks)-1; i >= 0; i-- {
			<-locks[i]
		}
	}, nil
}
