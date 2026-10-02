package main

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const drivesQuery = `SELECT d.id, d.start_date, d.end_date,
       (SELECT p.odometer FROM positions AS p WHERE p.drive_id = d.id AND p.odometer IS NOT NULL ORDER BY p.date LIMIT 1),
       (SELECT p.odometer FROM positions AS p WHERE p.drive_id = d.id AND p.odometer IS NOT NULL ORDER BY p.date DESC LIMIT 1),
       NULL::double precision,
       COALESCE(start_address.display_name, start_address.name),
       COALESCE(end_address.display_name, end_address.name),
       COALESCE(jsonb_agg(to_jsonb(position) ORDER BY position.date) FILTER (WHERE position.id IS NOT NULL), '[]'::jsonb)
FROM drives AS d
LEFT JOIN addresses AS start_address ON start_address.id = d.start_address_id
LEFT JOIN addresses AS end_address ON end_address.id = d.end_address_id
LEFT JOIN positions AS position ON position.drive_id = d.id
WHERE d.car_id = $1 AND d.end_date IS NOT NULL AND d.id > $2
GROUP BY d.id, d.start_date, d.end_date,
         start_address.display_name, start_address.name,
         end_address.display_name, end_address.name
ORDER BY d.id
LIMIT $3`

const chargesQuery = `SELECT process.id, process.start_date, process.end_date,
       process.charge_energy_added, process.cost,
       COALESCE(address.display_name, address.name),
       COALESCE(jsonb_agg(to_jsonb(charge) ORDER BY charge.date) FILTER (WHERE charge.id IS NOT NULL), '[]'::jsonb)
FROM charging_processes AS process
LEFT JOIN addresses AS address ON address.id = process.address_id
LEFT JOIN charges AS charge ON charge.charging_process_id = process.id
WHERE process.car_id = $1 AND process.end_date IS NOT NULL AND process.id > $2
GROUP BY process.id, process.start_date, process.end_date,
         process.charge_energy_added, process.cost,
         address.display_name, address.name
ORDER BY process.id
LIMIT $3`

type selectQueryer interface {
	Query(context.Context, string, ...any) (pgx.Rows, error)
}

type PostgresSource struct {
	pool  selectQueryer
	carID int64
}

func NewPostgresSource(pool *pgxpool.Pool, carID int64) *PostgresSource {
	return &PostgresSource{pool: pool, carID: carID}
}

func (s *PostgresSource) FetchDrives(ctx context.Context, afterID, limit int64) ([]DriveRecord, error) {
	rows, err := selectRows(ctx, s.pool, drivesQuery, s.carID, afterID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]DriveRecord, 0)
	for rows.Next() {
		var record DriveRecord
		var routeJSON []byte
		if err := rows.Scan(&record.ID, &record.StartedAt, &record.EndedAt, &record.OdometerStart, &record.OdometerEnd, &record.EnergyConsumed, &record.StartAddress, &record.EndAddress, &routeJSON); err != nil {
			return nil, err
		}
		record.Route, err = decodeRoute(routeJSON)
		if err != nil {
			return nil, err
		}
		result = append(result, record)
	}
	return result, rows.Err()
}

func (s *PostgresSource) FetchCharges(ctx context.Context, afterID, limit int64) ([]ChargeRecord, error) {
	rows, err := selectRows(ctx, s.pool, chargesQuery, s.carID, afterID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]ChargeRecord, 0)
	for rows.Next() {
		var record ChargeRecord
		var pointsJSON []byte
		if err := rows.Scan(&record.ID, &record.StartedAt, &record.EndedAt, &record.EnergyAdded, &record.Cost, &record.Address, &pointsJSON); err != nil {
			return nil, err
		}
		record.ChargePoints, err = decodeChargePoints(pointsJSON)
		if err != nil {
			return nil, err
		}
		result = append(result, record)
	}
	return result, rows.Err()
}

func selectRows(ctx context.Context, queryer selectQueryer, query string, args ...any) (pgx.Rows, error) {
	trimmed := strings.TrimSpace(query)
	if !strings.HasPrefix(strings.ToUpper(trimmed), "SELECT ") || strings.Contains(trimmed, ";") {
		return nil, errors.New("non-SELECT query rejected")
	}
	return queryer.Query(ctx, query, args...)
}

func decodeRoute(data []byte) ([]RoutePoint, error) {
	if len(data) == 0 || string(data) == "null" {
		return []RoutePoint{}, nil
	}
	var raw []map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}
	result := make([]RoutePoint, 0, len(raw))
	for _, item := range raw {
		date, err := rawTime(item, "date")
		if err != nil {
			return nil, err
		}
		latitude, err := rawPointer[float64](item, "latitude")
		if err != nil {
			return nil, err
		}
		longitude, err := rawPointer[float64](item, "longitude")
		if err != nil {
			return nil, err
		}
		speed, err := rawPointer[float64](item, "speed")
		if err != nil {
			return nil, err
		}
		power, err := rawPointer[float64](item, "power")
		if err != nil {
			return nil, err
		}
		heading, err := rawPointer[float64](item, "heading")
		if err != nil {
			return nil, err
		}
		result = append(result, RoutePoint{Date: date, Latitude: latitude, Longitude: longitude, Speed: speed, Power: power, Heading: heading})
	}
	return result, nil
}

func decodeChargePoints(data []byte) ([]ChargePoint, error) {
	if len(data) == 0 || string(data) == "null" {
		return []ChargePoint{}, nil
	}
	var rawPoints []map[string]json.RawMessage
	if err := json.Unmarshal(data, &rawPoints); err != nil {
		return nil, err
	}
	points := make([]ChargePoint, 0, len(rawPoints))
	for _, raw := range rawPoints {
		date, err := rawTime(raw, "date")
		if err != nil {
			return nil, err
		}
		batteryLevel, err := rawPointer[int](raw, "battery_level")
		if err != nil {
			return nil, err
		}
		energyAdded, err := rawPointer[float64](raw, "energy_added")
		if err != nil {
			return nil, err
		}
		if energyAdded == nil {
			energyAdded, err = rawPointer[float64](raw, "charge_energy_added")
			if err != nil {
				return nil, err
			}
		}
		chargerPower, err := rawPointer[float64](raw, "charger_power")
		if err != nil {
			return nil, err
		}
		latitude, err := rawPointer[float64](raw, "latitude")
		if err != nil {
			return nil, err
		}
		longitude, err := rawPointer[float64](raw, "longitude")
		if err != nil {
			return nil, err
		}
		points = append(points, ChargePoint{Date: date, BatteryLevel: batteryLevel, EnergyAdded: energyAdded, ChargerPower: chargerPower, Latitude: latitude, Longitude: longitude})
	}
	if points == nil {
		return []ChargePoint{}, nil
	}
	return points, nil
}

func rawPointer[T any](values map[string]json.RawMessage, key string) (*T, error) {
	raw, ok := values[key]
	if !ok || string(raw) == "null" {
		return nil, nil
	}
	var value T
	if err := json.Unmarshal(raw, &value); err != nil {
		return nil, err
	}
	return &value, nil
}

func rawTime(values map[string]json.RawMessage, key string) (*time.Time, error) {
	raw, ok := values[key]
	if !ok || string(raw) == "null" {
		return nil, nil
	}
	var text string
	if err := json.Unmarshal(raw, &text); err != nil {
		return nil, err
	}
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05.999999", "2006-01-02T15:04:05"} {
		if value, err := time.Parse(layout, text); err == nil {
			return &value, nil
		}
	}
	return nil, errors.New("invalid database timestamp")
}
