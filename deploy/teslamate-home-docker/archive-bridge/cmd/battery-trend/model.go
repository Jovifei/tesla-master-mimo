package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"
)

const trendSchemaVersion = 1

type trendUnits struct {
	RatedRange  string `json:"rated_range"`
	Temperature string `json:"temperature"`
	Soc         string `json:"soc"`
}

type trendBoundary struct {
	SocPercent            *int     `json:"soc_percent"`
	SocPositionObservedAt *string  `json:"soc_position_observed_at"`
	SocPositionID         *int64   `json:"soc_position_id"`
	RatedRangeKm          *float64 `json:"rated_range_km"`
	RatedRangeSource      *string  `json:"rated_range_source"`
	RatedRangeObservedAt  *string  `json:"rated_range_observed_at"`
}

type trendObservation struct {
	SourceRecordID    string         `json:"source_record_id"`
	StartedAt         string         `json:"started_at"`
	EndedAt           string         `json:"ended_at"`
	Start             *trendBoundary `json:"start"`
	End               *trendBoundary `json:"end"`
	OutsideTempAvgC   *float64       `json:"outside_temp_avg_c"`
	TemperatureSource *string        `json:"temperature_source"`
}

// Field order must match the server's canonical encoding/json payload.
type trendPayload struct {
	SchemaVersion    int                `json:"schema_version"`
	Source           string             `json:"source"`
	SourceInstanceID string             `json:"source_instance_id"`
	SourceVehicleID  string             `json:"source_vehicle_id"`
	Units            trendUnits         `json:"units"`
	Observations     []trendObservation `json:"observations"`
}

type trendRequest struct {
	trendPayload
	BatchID       string `json:"batch_id"`
	PayloadSHA256 string `json:"payload_sha256"`
}

type sourcePosition struct {
	ID         int64
	CarID      int64
	DriveID    int64
	ObservedAt time.Time
	SocPercent *int
}

type sourceDrive struct {
	ID             int64
	StartedAt      time.Time
	EndedAt        time.Time
	Start          *sourcePosition
	End            *sourcePosition
	StartRatedKm   *float64
	EndRatedKm     *float64
	OutsideTempAvg *float64
}

func utcString(t time.Time) string { return t.UTC().Format(time.RFC3339Nano) }

func observationFromDrive(d sourceDrive, carID int64) (trendObservation, error) {
	if d.ID <= 0 || d.StartedAt.IsZero() || d.EndedAt.IsZero() || d.EndedAt.Before(d.StartedAt) {
		return trendObservation{}, errors.New("invalid completed drive")
	}
	boundary := func(p *sourcePosition, at time.Time, value *float64, source string) (*trendBoundary, error) {
		b := &trendBoundary{RatedRangeKm: value}
		if value != nil {
			if math.IsNaN(*value) || math.IsInf(*value, 0) || *value < 0 {
				return nil, errors.New("invalid rated range")
			}
			b.RatedRangeSource = &source
		}
		if p != nil {
			if p.ID <= 0 || p.CarID != carID || p.DriveID != d.ID || p.ObservedAt.IsZero() ||
				p.ObservedAt.Before(at.Add(-time.Second)) || p.ObservedAt.After(at.Add(time.Second)) ||
				(p.SocPercent != nil && (*p.SocPercent < 0 || *p.SocPercent > 100)) {
				return nil, errors.New("invalid boundary position")
			}
			timestamp := utcString(p.ObservedAt)
			b.SocPercent, b.SocPositionID, b.SocPositionObservedAt = p.SocPercent, &p.ID, &timestamp
		}
		return b, nil
	}
	start, err := boundary(d.Start, d.StartedAt, d.StartRatedKm, "teslamate_drive_start_summary")
	if err != nil {
		return trendObservation{}, err
	}
	end, err := boundary(d.End, d.EndedAt, d.EndRatedKm, "teslamate_drive_end_summary")
	if err != nil {
		return trendObservation{}, err
	}
	var temperatureSource *string
	if d.OutsideTempAvg != nil {
		if math.IsNaN(*d.OutsideTempAvg) || math.IsInf(*d.OutsideTempAvg, 0) {
			return trendObservation{}, errors.New("invalid temperature")
		}
		value := "teslamate_drive_average"
		temperatureSource = &value
	}
	return trendObservation{
		SourceRecordID: fmt.Sprintf("teslamate:drive:%d", d.ID), StartedAt: utcString(d.StartedAt),
		EndedAt: utcString(d.EndedAt), Start: start, End: end,
		OutsideTempAvgC: d.OutsideTempAvg, TemperatureSource: temperatureSource,
	}, nil
}

func makeTrendRequest(instance, vehicle string, observations []trendObservation) ([]byte, string, string, error) {
	if len(observations) == 0 || len(observations) > 50 {
		return nil, "", "", errors.New("invalid batch size")
	}
	payload := trendPayload{trendSchemaVersion, "teslamate", instance, vehicle, trendUnits{"km", "C", "percent"}, observations}
	canonical, err := json.Marshal(payload)
	if err != nil {
		return nil, "", "", err
	}
	digest := sha256.Sum256(canonical)
	hash := hex.EncodeToString(digest[:])
	batchID := "battery-v1-" + hash
	body, err := json.Marshal(trendRequest{payload, batchID, hash})
	if err != nil || len(body) > 256*1024 {
		return nil, "", "", errors.New("batch encoding or size invalid")
	}
	return body, batchID, hash, nil
}

func driveID(record string) (int64, error) {
	const prefix = "teslamate:drive:"
	if !strings.HasPrefix(record, prefix) {
		return 0, errors.New("invalid source record")
	}
	raw := strings.TrimPrefix(record, prefix)
	id, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || id <= 0 || strconv.FormatInt(id, 10) != raw {
		return 0, errors.New("invalid source record")
	}
	return id, nil
}
