package main

import (
	"context"
	"errors"
	"sync"
)

var errHistoryResourceBudgetExceeded = errors.New("history_resource_budget_exceeded")

// historyReadBudget bounds expensive history operations before they allocate
// large route/detail payloads. Counters are kept in memory only while admitted
// calls exist; completed calls release their scope entries.
type historyReadBudget struct {
	mu sync.Mutex
	limit int
	global int
	scopes map[string]int
}

func newHistoryReadBudget(global int) *historyReadBudget {
	return &historyReadBudget{limit: global, scopes: make(map[string]int)}
}

func historyBudgetScopeKey(userID, vehicleID string) string {
	return userID + "\x00" + vehicleID
}

func (b *historyReadBudget) acquire(ctx context.Context, userID, vehicleID string) (func(), error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if b == nil {
		return func() {}, nil
	}
	key := historyBudgetScopeKey(userID, vehicleID)
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.global >= b.limit || b.scopes[key] >= 1 {
		return nil, errHistoryResourceBudgetExceeded
	}
	b.global++
	b.scopes[key]++
	var once sync.Once
	return func() {
		once.Do(func() {
			b.mu.Lock()
			defer b.mu.Unlock()
			b.global--
			b.scopes[key]--
			if b.scopes[key] == 0 {
				delete(b.scopes, key)
			}
		})
	}, nil
}
