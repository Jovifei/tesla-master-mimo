package main

import (
	"context"
	"errors"
	"sync"
)

var errHistoryResourceOverloaded = errors.New("history_resource_overloaded")

type historyResourceAdmission struct {
	mu sync.Mutex
	limit int
	active int
	users map[string]int
	vehicles map[string]int
}

func newHistoryResourceAdmission(limit int) *historyResourceAdmission {
	return &historyResourceAdmission{limit: limit, users: make(map[string]int), vehicles: make(map[string]int)}
}

func historyResourceVehicleKey(userID, vehicleID string) string {
	return userID + "\x00" + vehicleID
}

func (a *historyResourceAdmission) acquire(ctx context.Context, userID, vehicleID string) (func(), error) {
	if a == nil {
		return func() {}, nil
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.active >= a.limit {
		return nil, errHistoryResourceOverloaded
	}
	key := historyResourceVehicleKey(userID, vehicleID)
	a.active++
	a.users[userID]++
	a.vehicles[key]++
	var once sync.Once
	return func() {
		once.Do(func() {
			a.mu.Lock()
			defer a.mu.Unlock()
			a.active--
			a.users[userID]--
			a.vehicles[key]--
			if a.users[userID] == 0 { delete(a.users, userID) }
			if a.vehicles[key] == 0 { delete(a.vehicles, key) }
		})
	}, nil
}
