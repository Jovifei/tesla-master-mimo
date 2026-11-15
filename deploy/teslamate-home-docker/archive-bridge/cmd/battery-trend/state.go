package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"math"
	"os"
	"path/filepath"
	"runtime"
	"time"
)

type pendingBatch struct {
	Body        []byte `json:"body"`
	LastDriveID int64  `json:"last_drive_id"`
}

type trendState struct {
	Version     int           `json:"version"`
	ScopeHash   string        `json:"scope_hash"`
	LastDriveID int64         `json:"last_drive_id"`
	HighWaterID int64         `json:"high_water_id"`
	Pending     *pendingBatch `json:"pending"`
}

func loadState(c config) (trendState, error) {
	if err := distinctPaths(c.OriginalStateFile, c.StateFile); err != nil {
		return trendState{}, err
	}
	f, err := os.Open(c.StateFile)
	if errors.Is(err, os.ErrNotExist) {
		return trendState{Version: 1, ScopeHash: c.ScopeHash}, nil
	}
	if err != nil {
		return trendState{}, err
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, (1<<20)+1))
	if err != nil || len(data) > 1<<20 {
		return trendState{}, errors.New("trend state unreadable or oversized")
	}
	var state trendState
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&state); err != nil {
		return trendState{}, errors.New("invalid trend state")
	}
	if err = decoder.Decode(&struct{}{}); err != io.EOF {
		return trendState{}, errors.New("invalid trend state trailing data")
	}
	if err = validateState(c, state); err != nil {
		return trendState{}, err
	}
	return state, nil
}

func validateState(c config, state trendState) error {
	if state.Version != 1 || state.ScopeHash != c.ScopeHash || state.LastDriveID < 0 || state.HighWaterID < 0 ||
		(state.HighWaterID != 0 && state.HighWaterID < state.LastDriveID) {
		return errors.New("trend state scope or cursor invalid")
	}
	if state.Pending == nil {
		return nil
	}
	var request trendRequest
	decoder := json.NewDecoder(bytes.NewReader(state.Pending.Body))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&request) != nil || decoder.Decode(&struct{}{}) != io.EOF ||
		request.SchemaVersion != 1 || request.Source != "teslamate" || request.SourceInstanceID != c.SourceInstanceID ||
		request.SourceVehicleID != c.SourceVehicleID || request.Units != (trendUnits{"km", "C", "percent"}) ||
		state.HighWaterID == 0 || len(request.Observations) == 0 || len(request.Observations) > 50 {
		return errors.New("invalid pending trend batch")
	}
	last := state.LastDriveID
	for _, observation := range request.Observations {
		id, err := driveID(observation.SourceRecordID)
		if err != nil || id <= last || id > state.HighWaterID || validateObservation(observation) != nil {
			return errors.New("invalid pending trend observation")
		}
		last = id
	}
	expected, batch, hash, err := makeTrendRequest(c.SourceInstanceID, c.SourceVehicleID, request.Observations)
	if err != nil || !bytes.Equal(expected, state.Pending.Body) || request.BatchID != batch ||
		request.PayloadSHA256 != hash || state.Pending.LastDriveID != last {
		return errors.New("pending trend batch integrity mismatch")
	}
	return nil
}

func validateObservation(o trendObservation) error {
	parse := func(value string) (time.Time, error) {
		t, err := time.Parse(time.RFC3339Nano, value)
		if err != nil || utcString(t) != value {
			return time.Time{}, errors.New("noncanonical timestamp")
		}
		return t, nil
	}
	start, err := parse(o.StartedAt)
	if err != nil {
		return err
	}
	end, err := parse(o.EndedAt)
	if err != nil || end.Before(start) || o.Start == nil || o.End == nil {
		return errors.New("invalid observation window")
	}
	check := func(b *trendBoundary, at time.Time, expectedSource string) bool {
		if (b.SocPositionID == nil) != (b.SocPositionObservedAt == nil) ||
			(b.SocPercent != nil && (b.SocPositionID == nil || *b.SocPercent < 0 || *b.SocPercent > 100)) {
			return false
		}
		if b.SocPositionID != nil {
			if *b.SocPositionID <= 0 {
				return false
			}
			observed, e := parse(*b.SocPositionObservedAt)
			if e != nil || observed.Before(at.Add(-time.Second)) || observed.After(at.Add(time.Second)) {
				return false
			}
		}
		if b.RatedRangeObservedAt != nil {
			return false
		}
		if b.RatedRangeKm == nil {
			return b.RatedRangeSource == nil
		}
		return b.RatedRangeSource != nil && *b.RatedRangeSource == expectedSource &&
			!math.IsNaN(*b.RatedRangeKm) && !math.IsInf(*b.RatedRangeKm, 0) && *b.RatedRangeKm >= 0
	}
	if !check(o.Start, start, "teslamate_drive_start_summary") || !check(o.End, end, "teslamate_drive_end_summary") {
		return errors.New("invalid boundary evidence")
	}
	if o.OutsideTempAvgC == nil {
		if o.TemperatureSource != nil {
			return errors.New("invalid temperature source")
		}
		return nil
	}
	if math.IsNaN(*o.OutsideTempAvgC) || math.IsInf(*o.OutsideTempAvgC, 0) || o.TemperatureSource == nil ||
		*o.TemperatureSource != "teslamate_drive_average" {
		return errors.New("invalid temperature evidence")
	}
	return nil
}

func saveState(c config, state trendState) (err error) {
	if err = distinctPaths(c.OriginalStateFile, c.StateFile); err != nil {
		return err
	}
	if err = validateState(c, state); err != nil {
		return err
	}
	data, err := json.Marshal(state)
	if err != nil || len(data) >= 1<<20 {
		return errors.New("trend state encoding failed")
	}
	dir := filepath.Dir(c.StateFile)
	f, err := os.CreateTemp(dir, ".battery-trend-*.tmp")
	if err != nil {
		return err
	}
	name := f.Name()
	defer func() {
		if name != "" {
			_ = os.Remove(name)
		}
	}()
	if err = f.Chmod(0o600); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = distinctPaths(c.OriginalStateFile, c.StateFile); err != nil {
		return err
	}
	if err = os.Rename(name, c.StateFile); err != nil {
		return err
	}
	name = ""
	if runtime.GOOS != "windows" {
		d, openErr := os.Open(dir)
		if openErr != nil {
			return openErr
		}
		err = d.Sync()
		_ = d.Close()
	}
	return err
}
