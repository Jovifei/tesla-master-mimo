package main

import (
	"context"
	"strconv"
)

var globalHistoryResourceBudget = newHistoryResourceAdmission(8)

func acquireHistoryHeavyBudget(ctx context.Context, userID string, vehicleID int) (func(), error) {
	return globalHistoryResourceBudget.acquire(ctx, userID, strconv.Itoa(vehicleID))
}
