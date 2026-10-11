package main

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const historyContextVersion = 1
const historyContextUIDMaxBytes = 256

// historyContext proves the current application's persisted user/car binding
// without fetching live Tesla data, decrypting a VIN, or updating any row.
// vehicle_uid deliberately matches /cars; readiness uses a different UID form.
func (a *app) historyContext(w http.ResponseWriter, r *http.Request, userID string, carID int, path string, parts []string) {
	canonicalPath := "/api/matelink/v1/cars/" + strconv.Itoa(carID) + "/history-context"
	if r.Method != http.MethodGet || carID <= 0 || carID > 1<<31-1 || len(parts) != 2 || path != canonicalPath || r.URL.EscapedPath() != canonicalPath {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	unavailable := func() {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "history_identity_unavailable"})
	}
	if a.store == nil || a.store.pool == nil {
		unavailable()
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	var identity *string
	err := a.store.pool.QueryRow(ctx, `SELECT CASE
		WHEN octet_length(provider_vehicle_id) BETWEEN 1 AND $3 THEN provider_vehicle_id
		ELSE NULL END
		FROM jourvolt_vehicles WHERE user_id=$1 AND id=$2`, userID, carID, historyContextUIDMaxBytes).Scan(&identity)
	if errors.Is(err, pgx.ErrNoRows) {
		a.json(w, http.StatusNotFound, map[string]string{"error": "vehicle_not_found"})
		return
	}
	if err != nil || identity == nil || strings.TrimSpace(*identity) == "" || strings.TrimSpace(*identity) != *identity {
		unavailable()
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
		"capability_version": historyContextVersion,
		"car_id":             carID,
		"vehicle_uid":        *identity,
	}})
}
