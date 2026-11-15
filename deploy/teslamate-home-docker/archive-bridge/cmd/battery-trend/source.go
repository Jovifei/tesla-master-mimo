package main

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type trendSource interface {
	HighWater(context.Context, int64) (int64, error)
	Fetch(context.Context, int64, int64, int) ([]sourceDrive, error)
}

type postgresSource struct {
	pool  *pgxpool.Pool
	carID int64
}

// An unfinished lower ID prevents silently advancing past a later completion.
const highWaterQuery = `SELECT COALESCE(MAX(d.id), 0),
       COALESCE((SELECT MIN(o.id) FROM drives o WHERE o.car_id=$1 AND o.id>$2 AND o.end_date IS NULL), 0),
       COALESCE((SELECT MAX(f.id) FROM drives f WHERE f.car_id=$1 AND f.id>$2 AND f.end_date IS NOT NULL), 0)
FROM drives d
WHERE d.car_id=$1 AND d.id>$2 AND d.end_date IS NOT NULL
  AND d.id < COALESCE((SELECT MIN(o.id) FROM drives o WHERE o.car_id=$1 AND o.id>$2 AND o.end_date IS NULL), 9223372036854775807)`

const trendRowsQuery = `SELECT d.id, d.start_date, d.end_date,
       d.start_rated_range_km, d.end_rated_range_km, d.outside_temp_avg,
       d.start_position_id, sp.id, sp.car_id, sp.drive_id, sp.date, sp.battery_level,
       d.end_position_id, ep.id, ep.car_id, ep.drive_id, ep.date, ep.battery_level
FROM drives d
LEFT JOIN positions sp ON sp.id=d.start_position_id
LEFT JOIN positions ep ON ep.id=d.end_position_id
WHERE d.car_id=$1 AND d.id>$2 AND d.id<=$3 AND d.end_date IS NOT NULL
  AND d.id < COALESCE((SELECT MIN(o.id) FROM drives o WHERE o.car_id=$1 AND o.id>$2 AND o.id<=$3 AND o.end_date IS NULL), 9223372036854775807)
ORDER BY d.id LIMIT $4`

func (s postgresSource) HighWater(ctx context.Context, after int64) (int64, error) {
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{AccessMode: pgx.ReadOnly})
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)
	var safe, firstOpen, latestComplete int64
	if err = tx.QueryRow(ctx, highWaterQuery, s.carID, after).Scan(&safe, &firstOpen, &latestComplete); err != nil {
		return 0, err
	}
	if safe == 0 && firstOpen > 0 && latestComplete > firstOpen {
		return 0, errors.New("unfinished drive blocks later completed history")
	}
	return safe, tx.Commit(ctx)
}

func (s postgresSource) Fetch(ctx context.Context, after, high int64, limit int) ([]sourceDrive, error) {
	if after < 0 || high <= after || limit < 1 || limit > 50 {
		return nil, errors.New("invalid source page")
	}
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{AccessMode: pgx.ReadOnly})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, trendRowsQuery, s.carID, after, high, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []sourceDrive
	for rows.Next() {
		var d sourceDrive
		var start, end *time.Time
		var sr, sid, scar, sdrive, er, eid, ecar, edrive *int64
		var st, et *time.Time
		var ss, es *int
		if err = rows.Scan(&d.ID, &start, &end, &d.StartRatedKm, &d.EndRatedKm, &d.OutsideTempAvg,
			&sr, &sid, &scar, &sdrive, &st, &ss, &er, &eid, &ecar, &edrive, &et, &es); err != nil {
			return nil, err
		}
		if start == nil || end == nil {
			return nil, errors.New("completed drive lacks time")
		}
		d.StartedAt, d.EndedAt = *start, *end
		position := func(ref, id, car, drive *int64, at *time.Time, soc *int) (*sourcePosition, error) {
			if ref == nil {
				return nil, nil
			}
			if id == nil || car == nil || drive == nil || at == nil || *id != *ref || *car != s.carID || *drive != d.ID {
				return nil, errors.New("position source mismatch")
			}
			return &sourcePosition{ID: *id, CarID: *car, DriveID: *drive, ObservedAt: *at, SocPercent: soc}, nil
		}
		if d.Start, err = position(sr, sid, scar, sdrive, st, ss); err != nil {
			return nil, err
		}
		if d.End, err = position(er, eid, ecar, edrive, et, es); err != nil {
			return nil, err
		}
		if _, err = observationFromDrive(d, s.carID); err != nil {
			return nil, err
		}
		result = append(result, d)
	}
	if err = rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return result, nil
}
