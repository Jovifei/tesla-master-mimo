package main

import (
	"context"
	"net/http"
	"strconv"
	"time"
)

const (
	climateHistoryDefaultDays = 7
	climateHistoryMaxDays     = 30
)

type climateSample struct {
	ObservedAt   string   `json:"observed_at"`
	InsideTempC  *float64 `json:"inside_temp_c"`
	OutsideTempC *float64 `json:"outside_temp_c"`
}

// climateHistoryContext returns hour-bucketed averages of the live
// InsideTemp/OutsideTemp observations recorded by the collector. The archive
// contributes per-drive outside averages through history enrichment; this
// table is the only cabin-temperature history. Hour buckets keep a 30-day
// response small enough for the mini-program chart.
func (s *telemetryService) climateHistoryContext(ctx context.Context, userID string, vehicleID, days int) ([]climateSample, error) {
	if s == nil || s.store == nil || s.store.pool == nil {
		return []climateSample{}, nil
	}
	if days < 1 || days > climateHistoryMaxDays {
		days = climateHistoryDefaultDays
	}
	readCtx, cancel := boundedHistoryContext(ctx)
	defer cancel()
	rows, err := s.store.pool.Query(readCtx, `
SELECT date_trunc('hour', observed_at) AS bucket,
       AVG(inside_temp_c), AVG(outside_temp_c)
FROM jourvolt_climate_samples
WHERE user_id = $1 AND vehicle_id = $2
  AND observed_at >= now() - make_interval(days => $3)
GROUP BY bucket
ORDER BY bucket
`, userID, vehicleID, days)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	samples := make([]climateSample, 0, days*24)
	for rows.Next() {
		if err := readCtx.Err(); err != nil {
			return nil, err
		}
		var bucket time.Time
		var inside, outside *float64
		if err := rows.Scan(&bucket, &inside, &outside); err != nil {
			return nil, err
		}
		if inside == nil && outside == nil {
			continue
		}
		samples = append(samples, climateSample{
			ObservedAt:   bucket.UTC().Format(time.RFC3339),
			InsideTempC:  roundedTemperature(inside),
			OutsideTempC: roundedTemperature(outside),
		})
	}
	return samples, rows.Err()
}

func roundedTemperature(value *float64) *float64 {
	if value == nil {
		return nil
	}
	rounded := batteryHealthRound(*value, 1)
	return &rounded
}

func climateHistoryDays(raw string) int {
	days, err := strconv.Atoi(raw)
	if err != nil || (days != 7 && days != climateHistoryMaxDays) {
		return climateHistoryDefaultDays
	}
	return days
}

func (a *app) climateHistory(w http.ResponseWriter, r *http.Request, userID string, carID int) {
	days := climateHistoryDays(r.URL.Query().Get("days"))
	samples := []climateSample{}
	if a.telemetry != nil {
		found, err := a.telemetry.climateHistoryContext(r.Context(), userID, carID, days)
		if err != nil {
			a.providerError(w, err)
			return
		}
		if found != nil {
			samples = found
		}
	}
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
		"samples": samples,
		"meta": map[string]any{
			"days":   days,
			"source": "telemetry_mqtt",
			// An empty series is honest: cabin temperature is recorded from
			// this version onward, not reconstructed from drive summaries.
			"reason": func() any {
				if len(samples) == 0 {
					return "no_recorded_climate_samples"
				}
				return nil
			}(),
		},
	}})
}
