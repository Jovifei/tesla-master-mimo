package main

import (
	"context"
	"errors"
	"sync"
)

var errHistoryResourceOverloaded = errors.New("history_resource_overloaded")

type historyResourceAdmission struct {
	mu sync.Mutex
	globalLimit int
	userLimit int
	vehicleLimit int
	active int
	users map[string]int
	vehicles map[string]int
}

func newHistoryResourceAdmission(global, user, vehicle int) *historyResourceAdmission {
	return &historyResourceAdmission{
		globalLimit: global,
		userLimit: user,
		vehicleLimit: vehicle,
		users: make(map[string]int),
		vehicles: make(map[string]int),
	}
}

func historyResourceVehicleKey(userID, vehicleID string) string {
	return userID + "\x00" + vehicleID
}

func (a *historyResourceAdmission) acquire(ctx context.Context, userID, vehicleID string) (func(), error) {
	if a == nil {
		return func() {}, nil
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	vehicleKey := historyResourceVehicleKey(userID, vehicleID)
	if a.globalLimit > 0 && a.active >= a.globalLimit {
		return nil, errHistoryResourceOverloaded
	}
	if a.userLimit > 0 && a.users[userID] >= a.userLimit {
		return nil, errHistoryResourceOverloaded
	}
	if a.vehicleLimit > 0 && a.vehicles[vehicleKey] >= a.vehicleLimit {
		return nil, errHistoryResourceOverloaded
	}
	a.active++
	a.users[userID]++
	a.vehicles[vehicleKey]++
	var once sync.Once
	return func() {
		once.Do(func() {
			a.mu.Lock()
			defer a.mu.Unlock()
			a.active--
			a.users[userID]--
			a.vehicles[vehicleKey]--
			if a.users[userID] <= 0 {
				delete(a.users, userID)
			}
			if a.vehicles[vehicleKey] <= 0 {
				delete(a.vehicles, vehicleKey)
			}
		})
	}, nil
}
