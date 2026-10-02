package main

import (
	"context"
	"errors"
	"sync"
)

var errHistoryResourceOverloaded = errors.New("history_resource_overloaded")

// historyResourceAdmission is a small shared admission guard for heavy history
// operations. Handlers should acquire immediately before materializing detail,
// import, archive, or route payloads and defer the returned release closure.
// Cleanup is idempotent because cancellation/error/defer paths may overlap.
type historyResourceAdmission struct {
	mu sync.Mutex
	limit int
	active int
}

func newHistoryResourceAdmission(limit int) *historyResourceAdmission {
	return &historyResourceAdmission{limit: limit}
}

func (a *historyResourceAdmission) acquire(ctx context.Context) (func(), error) {
	if a == nil {
		return func(){}, nil
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	a.mu.Lock()
	if a.active >= a.limit {
		a.mu.Unlock()
		return nil, errHistoryResourceOverloaded
	}
	a.active++
	a.mu.Unlock()
	var once sync.Once
	return func() {
		once.Do(func() {
			a.mu.Lock()
			a.active--
			a.mu.Unlock()
		})
	}, nil
}
