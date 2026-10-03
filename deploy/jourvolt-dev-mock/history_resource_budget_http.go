package main

import (
	"context"
	"errors"
	"net/http"
	"strconv"
)

var globalHistoryResourceBudget = newHistoryResourceAdmission(8, 2, 1)

func acquireHistoryHeavyBudget(ctx context.Context, userID string, vehicleID int) (func(), error) {
	return globalHistoryResourceBudget.acquire(ctx, userID, strconv.Itoa(vehicleID))
}

func (a *app) historyAdmissionError(w http.ResponseWriter, err error) {
	status, message := http.StatusServiceUnavailable, "history_resource_unavailable"
	switch {
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		status, message = http.StatusRequestTimeout, err.Error()
	case errors.Is(err, errHistoryResourceOverloaded):
		w.Header().Set("Retry-After", "1")
		status, message = http.StatusTooManyRequests, err.Error()
	}
	a.json(w, status, map[string]string{"error": message})
}
