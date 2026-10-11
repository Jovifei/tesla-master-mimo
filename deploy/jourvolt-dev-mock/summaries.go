package main

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type tripSummaryItem struct {
	Source       string    `json:"source"`
	SourceID     string    `json:"source_id"`
	StartedAt    time.Time `json:"started_at"`
	EndedAt      time.Time `json:"ended_at"`
	DistanceKm   *float64  `json:"distance_km,omitempty"`
	DurationMin  *int      `json:"duration_min,omitempty"`
	EnergyKwh    *float64  `json:"energy_kwh,omitempty"`
	CostYuan     *float64  `json:"cost_yuan,omitempty"`
	StartAddress string    `json:"start_address,omitempty"`
	EndAddress   string    `json:"end_address,omitempty"`
	LatStart     *float64  `json:"lat_start,omitempty"`
	LonStart     *float64  `json:"lon_start,omitempty"`
	LatEnd       *float64  `json:"lat_end,omitempty"`
	LonEnd       *float64  `json:"lon_end,omitempty"`
}

type chargeSummaryItem struct {
	Source      string    `json:"source"`
	SourceID    string    `json:"source_id"`
	StartedAt   time.Time `json:"started_at"`
	EndedAt     time.Time `json:"ended_at"`
	EnergyKwh   *float64  `json:"energy_kwh,omitempty"`
	CostYuan    *float64  `json:"cost_yuan,omitempty"`
	StationName string    `json:"station_name,omitempty"`
	ChargerType string    `json:"charger_type,omitempty"`
	IsFast      *bool     `json:"is_fast,omitempty"`
}

type inMemorySummaryStore struct {
	mu      sync.RWMutex
	trips   map[string][]tripSummaryItem
	charges map[string][]chargeSummaryItem
}

func newInMemorySummaryStore() *inMemorySummaryStore {
	return &inMemorySummaryStore{
		trips:   make(map[string][]tripSummaryItem),
		charges: make(map[string][]chargeSummaryItem),
	}
}

var fallbackSummaryStore = newInMemorySummaryStore()

func summaryScopeKey(userID string, vehicleID int) string {
	return userID + ":" + strconv.Itoa(vehicleID)
}

func ensureSummarySchema(ctx context.Context, pool *pgxpool.Pool) error {
	if pool == nil {
		return nil
	}
	_, err := pool.Exec(ctx, `
CREATE TABLE IF NOT EXISTS jourvolt_trip_summaries (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES jourvolt_users(id) ON DELETE CASCADE,
    vehicle_id INTEGER NOT NULL REFERENCES jourvolt_vehicles(id) ON DELETE CASCADE,
    source TEXT NOT NULL,
    source_id TEXT NOT NULL DEFAULT '',
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ NOT NULL,
    distance_km DOUBLE PRECISION,
    duration_min INTEGER,
    energy_kwh DOUBLE PRECISION,
    cost_yuan DOUBLE PRECISION,
    start_address TEXT NOT NULL DEFAULT '',
    end_address TEXT NOT NULL DEFAULT '',
    lat_start DOUBLE PRECISION,
    lon_start DOUBLE PRECISION,
    lat_end DOUBLE PRECISION,
    lon_end DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, vehicle_id, source, source_id, started_at)
);

CREATE INDEX IF NOT EXISTS jourvolt_trip_summaries_user_vehicle_idx 
    ON jourvolt_trip_summaries(user_id, vehicle_id, started_at DESC);

CREATE TABLE IF NOT EXISTS jourvolt_charge_summaries (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES jourvolt_users(id) ON DELETE CASCADE,
    vehicle_id INTEGER NOT NULL REFERENCES jourvolt_vehicles(id) ON DELETE CASCADE,
    source TEXT NOT NULL,
    source_id TEXT NOT NULL DEFAULT '',
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ NOT NULL,
    energy_kwh DOUBLE PRECISION,
    cost_yuan DOUBLE PRECISION,
    station_name TEXT NOT NULL DEFAULT '',
    charger_type TEXT NOT NULL DEFAULT '',
    is_fast BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, vehicle_id, source, source_id, started_at)
);

CREATE INDEX IF NOT EXISTS jourvolt_charge_summaries_user_vehicle_idx 
    ON jourvolt_charge_summaries(user_id, vehicle_id, started_at DESC);
`)
	return err
}

func (s *store) upsertTripSummaries(ctx context.Context, userID string, vehicleID int, items []tripSummaryItem) (int, int, error) {
	if s == nil || s.pool == nil {
		fallbackSummaryStore.mu.Lock()
		defer fallbackSummaryStore.mu.Unlock()
		key := summaryScopeKey(userID, vehicleID)
		existing := fallbackSummaryStore.trips[key]
		inserted := 0
		updated := 0
		for _, item := range items {
			foundIndex := -1
			for i, ex := range existing {
				if ex.Source == item.Source && ex.SourceID == item.SourceID && ex.StartedAt.Equal(item.StartedAt) {
					foundIndex = i
					break
				}
			}
			if foundIndex >= 0 {
				existing[foundIndex] = item
				updated++
			} else {
				existing = append(existing, item)
				inserted++
			}
		}
		fallbackSummaryStore.trips[key] = existing
		return inserted, updated, nil
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback(ctx)

	inserted := 0
	updated := 0
	for _, item := range items {
		tag, err := tx.Exec(ctx, `
			INSERT INTO jourvolt_trip_summaries (
				user_id, vehicle_id, source, source_id, started_at, ended_at,
				distance_km, duration_min, energy_kwh, cost_yuan,
				start_address, end_address, lat_start, lon_start, lat_end, lon_end, updated_at
			) VALUES (
				$1, $2, $3, $4, $5, $6,
				$7, $8, $9, $10,
				$11, $12, $13, $14, $15, $16, now()
			)
			ON CONFLICT (user_id, vehicle_id, source, source_id, started_at)
			DO UPDATE SET
				ended_at = EXCLUDED.ended_at,
				distance_km = EXCLUDED.distance_km,
				duration_min = EXCLUDED.duration_min,
				energy_kwh = EXCLUDED.energy_kwh,
				cost_yuan = EXCLUDED.cost_yuan,
				start_address = EXCLUDED.start_address,
				end_address = EXCLUDED.end_address,
				lat_start = EXCLUDED.lat_start,
				lon_start = EXCLUDED.lon_start,
				lat_end = EXCLUDED.lat_end,
				lon_end = EXCLUDED.lon_end,
				updated_at = now()
		`, userID, vehicleID, item.Source, item.SourceID, item.StartedAt, item.EndedAt,
			item.DistanceKm, item.DurationMin, item.EnergyKwh, item.CostYuan,
			item.StartAddress, item.EndAddress, item.LatStart, item.LonStart, item.LatEnd, item.LonEnd)
		if err != nil {
			return 0, 0, err
		}
		if tag.RowsAffected() > 0 {
			inserted++
		} else {
			updated++
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return 0, 0, err
	}
	return inserted, updated, nil
}

func (s *store) getTripSummaries(ctx context.Context, userID string, vehicleID int, after time.Time, limit int) ([]tripSummaryItem, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if s == nil || s.pool == nil {
		fallbackSummaryStore.mu.RLock()
		defer fallbackSummaryStore.mu.RUnlock()
		key := summaryScopeKey(userID, vehicleID)
		items := fallbackSummaryStore.trips[key]
		res := make([]tripSummaryItem, 0, len(items))
		for _, item := range items {
			if after.IsZero() || item.StartedAt.After(after) {
				res = append(res, item)
				if len(res) >= limit {
					break
				}
			}
		}
		return res, nil
	}

	query := `
		SELECT source, source_id, started_at, ended_at, distance_km, duration_min, energy_kwh,
		       cost_yuan, start_address, end_address, lat_start, lon_start, lat_end, lon_end
		FROM jourvolt_trip_summaries
		WHERE user_id = $1 AND vehicle_id = $2
	`
	args := []any{userID, vehicleID}
	if !after.IsZero() {
		query += " AND started_at > $3"
		args = append(args, after)
	}
	query += " ORDER BY started_at DESC LIMIT " + strconv.Itoa(limit)

	rows, err := s.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	items := make([]tripSummaryItem, 0)
	for rows.Next() {
		var it tripSummaryItem
		err := rows.Scan(
			&it.Source, &it.SourceID, &it.StartedAt, &it.EndedAt,
			&it.DistanceKm, &it.DurationMin, &it.EnergyKwh, &it.CostYuan,
			&it.StartAddress, &it.EndAddress, &it.LatStart, &it.LonStart, &it.LatEnd, &it.LonEnd,
		)
		if err != nil {
			return nil, err
		}
		items = append(items, it)
	}
	return items, rows.Err()
}

func (s *store) upsertChargeSummaries(ctx context.Context, userID string, vehicleID int, items []chargeSummaryItem) (int, int, error) {
	if s == nil || s.pool == nil {
		fallbackSummaryStore.mu.Lock()
		defer fallbackSummaryStore.mu.Unlock()
		key := summaryScopeKey(userID, vehicleID)
		existing := fallbackSummaryStore.charges[key]
		inserted := 0
		updated := 0
		for _, item := range items {
			foundIndex := -1
			for i, ex := range existing {
				if ex.Source == item.Source && ex.SourceID == item.SourceID && ex.StartedAt.Equal(item.StartedAt) {
					foundIndex = i
					break
				}
			}
			if foundIndex >= 0 {
				existing[foundIndex] = item
				updated++
			} else {
				existing = append(existing, item)
				inserted++
			}
		}
		fallbackSummaryStore.charges[key] = existing
		return inserted, updated, nil
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback(ctx)

	inserted := 0
	updated := 0
	for _, item := range items {
		tag, err := tx.Exec(ctx, `
			INSERT INTO jourvolt_charge_summaries (
				user_id, vehicle_id, source, source_id, started_at, ended_at,
				energy_kwh, cost_yuan, station_name, charger_type, is_fast, updated_at
			) VALUES (
				$1, $2, $3, $4, $5, $6,
				$7, $8, $9, $10, $11, now()
			)
			ON CONFLICT (user_id, vehicle_id, source, source_id, started_at)
			DO UPDATE SET
				ended_at = EXCLUDED.ended_at,
				energy_kwh = EXCLUDED.energy_kwh,
				cost_yuan = EXCLUDED.cost_yuan,
				station_name = EXCLUDED.station_name,
				charger_type = EXCLUDED.charger_type,
				is_fast = EXCLUDED.is_fast,
				updated_at = now()
		`, userID, vehicleID, item.Source, item.SourceID, item.StartedAt, item.EndedAt,
			item.EnergyKwh, item.CostYuan, item.StationName, item.ChargerType, item.IsFast)
		if err != nil {
			return 0, 0, err
		}
		if tag.RowsAffected() > 0 {
			inserted++
		} else {
			updated++
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return 0, 0, err
	}
	return inserted, updated, nil
}

func (s *store) getChargeSummaries(ctx context.Context, userID string, vehicleID int, after time.Time, limit int) ([]chargeSummaryItem, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if s == nil || s.pool == nil {
		fallbackSummaryStore.mu.RLock()
		defer fallbackSummaryStore.mu.RUnlock()
		key := summaryScopeKey(userID, vehicleID)
		items := fallbackSummaryStore.charges[key]
		res := make([]chargeSummaryItem, 0, len(items))
		for _, item := range items {
			if after.IsZero() || item.StartedAt.After(after) {
				res = append(res, item)
				if len(res) >= limit {
					break
				}
			}
		}
		return res, nil
	}

	query := `
		SELECT source, source_id, started_at, ended_at, energy_kwh, cost_yuan, station_name, charger_type, is_fast
		FROM jourvolt_charge_summaries
		WHERE user_id = $1 AND vehicle_id = $2
	`
	args := []any{userID, vehicleID}
	if !after.IsZero() {
		query += " AND started_at > $3"
		args = append(args, after)
	}
	query += " ORDER BY started_at DESC LIMIT " + strconv.Itoa(limit)

	rows, err := s.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	items := make([]chargeSummaryItem, 0)
	for rows.Next() {
		var it chargeSummaryItem
		err := rows.Scan(
			&it.Source, &it.SourceID, &it.StartedAt, &it.EndedAt,
			&it.EnergyKwh, &it.CostYuan, &it.StationName, &it.ChargerType, &it.IsFast,
		)
		if err != nil {
			return nil, err
		}
		items = append(items, it)
	}
	return items, rows.Err()
}

func (a *app) summariesResource(w http.ResponseWriter, r *http.Request, userID string, carID int, subparts []string) {
	if len(subparts) == 0 {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}

	switch subparts[0] {
	case "trips":
		if r.Method == http.MethodPost {
			var body struct {
				Trips []tripSummaryItem `json:"trips"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_request"})
				return
			}
			inserted, updated, err := a.store.upsertTripSummaries(r.Context(), userID, carID, body.Trips)
			if err != nil {
				a.json(w, http.StatusInternalServerError, map[string]string{"error": "store_error"})
				return
			}
			a.json(w, http.StatusOK, map[string]any{
				"data": map[string]any{
					"inserted": inserted,
					"updated":  updated,
				},
			})
			return
		} else if r.Method == http.MethodGet {
			limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
			var after time.Time
			if rawAfter := r.URL.Query().Get("after"); rawAfter != "" {
				after, _ = time.Parse(time.RFC3339, rawAfter)
			}
			trips, err := a.store.getTripSummaries(r.Context(), userID, carID, after, limit)
			if err != nil {
				a.json(w, http.StatusInternalServerError, map[string]string{"error": "store_error"})
				return
			}
			a.json(w, http.StatusOK, map[string]any{
				"data": map[string]any{
					"trips": trips,
				},
			})
			return
		}
		a.json(w, http.StatusMethodNotAllowed, map[string]string{"error": "method_not_allowed"})

	case "charges":
		if r.Method == http.MethodPost {
			var body struct {
				Charges []chargeSummaryItem `json:"charges"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_request"})
				return
			}
			inserted, updated, err := a.store.upsertChargeSummaries(r.Context(), userID, carID, body.Charges)
			if err != nil {
				a.json(w, http.StatusInternalServerError, map[string]string{"error": "store_error"})
				return
			}
			a.json(w, http.StatusOK, map[string]any{
				"data": map[string]any{
					"inserted": inserted,
					"updated":  updated,
				},
			})
			return
		} else if r.Method == http.MethodGet {
			limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
			var after time.Time
			if rawAfter := r.URL.Query().Get("after"); rawAfter != "" {
				after, _ = time.Parse(time.RFC3339, rawAfter)
			}
			charges, err := a.store.getChargeSummaries(r.Context(), userID, carID, after, limit)
			if err != nil {
				a.json(w, http.StatusInternalServerError, map[string]string{"error": "store_error"})
				return
			}
			a.json(w, http.StatusOK, map[string]any{
				"data": map[string]any{
					"charges": charges,
				},
			})
			return
		}
		a.json(w, http.StatusMethodNotAllowed, map[string]string{"error": "method_not_allowed"})

	default:
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
	}
}
