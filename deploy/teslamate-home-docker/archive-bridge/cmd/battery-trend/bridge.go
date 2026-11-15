package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"mime"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type bridge struct {
	config config
	source trendSource
	client *http.Client
}

type trendHTTPError struct {
	status     int
	retryAfter time.Duration
}

func (e trendHTTPError) Error() string { return fmt.Sprintf("trend import HTTP %d", e.status) }

func trendRetryAfter(value string, now time.Time) time.Duration {
	value = strings.TrimSpace(value)
	if value == "" {
		return 0
	}
	if strings.Trim(value, "0123456789") == "" {
		seconds, err := strconv.ParseUint(value, 10, 64)
		if err != nil || seconds > uint64((1<<63-1)/int64(time.Second)) {
			return time.Duration(1<<63 - 1)
		}
		return time.Duration(seconds) * time.Second
	}
	if at, err := http.ParseTime(value); err == nil && at.After(now) {
		return at.Sub(now)
	}
	return 0
}

func (b *bridge) pollOnce(ctx context.Context) error {
	state, err := loadState(b.config)
	if err != nil {
		return err
	}
	if state.Pending != nil {
		return b.sendPending(ctx, state)
	}
	if state.HighWaterID == 0 {
		queryCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		high, err := b.source.HighWater(queryCtx, state.LastDriveID)
		cancel()
		if err != nil {
			return err
		}
		if high == 0 {
			return nil
		}
		if high <= state.LastDriveID {
			return errors.New("invalid high water")
		}
		state.HighWaterID = high
		if err = saveState(b.config, state); err != nil {
			return err
		}
	}
	queryCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	rows, err := b.source.Fetch(queryCtx, state.LastDriveID, state.HighWaterID, b.config.BatchSize)
	cancel()
	if err != nil {
		return err
	}
	if len(rows) == 0 || len(rows) > b.config.BatchSize {
		return errors.New("source page incomplete")
	}
	last := state.LastDriveID
	observations := make([]trendObservation, 0, len(rows))
	for _, row := range rows {
		if row.ID <= last || row.ID > state.HighWaterID {
			return errors.New("invalid source order")
		}
		observation, err := observationFromDrive(row, b.config.SourceCarID)
		if err != nil || validateObservation(observation) != nil {
			return errors.New("invalid source observation")
		}
		observations = append(observations, observation)
		last = row.ID
	}
	body, _, _, err := makeTrendRequest(b.config.SourceInstanceID, b.config.SourceVehicleID, observations)
	if err != nil {
		return err
	}
	state.Pending = &pendingBatch{Body: body, LastDriveID: last}
	if err = saveState(b.config, state); err != nil {
		return err
	}
	return b.sendPending(ctx, state)
}

func (b *bridge) sendPending(ctx context.Context, state trendState) error {
	if err := validateState(b.config, state); err != nil || state.Pending == nil {
		return errors.New("invalid pending batch")
	}
	var request trendRequest
	if json.Unmarshal(state.Pending.Body, &request) != nil {
		return errors.New("invalid pending request")
	}
	endpoint, err := url.Parse(b.config.ArchiveURL)
	if err != nil || endpoint.Scheme != "https" || endpoint.Host == "" || endpoint.User != nil {
		return errors.New("invalid archive URL")
	}
	endpoint.Path = strings.TrimRight(endpoint.Path, "/") + "/api/v1/cars/" + b.config.ArchiveVehicleID + "/history/archive/battery-trend/import"
	endpoint.RawPath, endpoint.RawQuery, endpoint.Fragment = "", "", ""
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint.String(), bytes.NewReader(state.Pending.Body))
	if err != nil {
		return errors.New("request creation failed")
	}
	req.Header.Set("X-MateLink-Archive-Binding", b.config.ArchiveToken)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	if b.client == nil {
		return errors.New("trend HTTP client unavailable")
	}
	client := *b.client
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	response, err := client.Do(req)
	if err != nil {
		return errors.New("trend import request failed")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return trendHTTPError{status: response.StatusCode, retryAfter: trendRetryAfter(response.Header.Get("Retry-After"), time.Now())}
	}
	mediaType, _, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		return errors.New("invalid trend receipt type")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, (64<<10)+1))
	if err != nil || len(body) > 64<<10 {
		return errors.New("invalid trend receipt")
	}
	var receipt struct {
		Data struct {
			SchemaVersion *int    `json:"schema_version"`
			BatchID       *string `json:"batch_id"`
			PayloadSHA256 *string `json:"payload_sha256"`
			Inserted      *int    `json:"inserted"`
			Unchanged     *int    `json:"unchanged"`
			Conflicted    *int    `json:"conflicted"`
		} `json:"data"`
	}
	if json.Unmarshal(body, &receipt) != nil || receipt.Data.SchemaVersion == nil || *receipt.Data.SchemaVersion != 1 ||
		receipt.Data.BatchID == nil || *receipt.Data.BatchID != request.BatchID ||
		receipt.Data.PayloadSHA256 == nil || *receipt.Data.PayloadSHA256 != request.PayloadSHA256 ||
		receipt.Data.Inserted == nil || receipt.Data.Unchanged == nil || receipt.Data.Conflicted == nil ||
		*receipt.Data.Conflicted != 0 || *receipt.Data.Inserted < 0 || *receipt.Data.Unchanged < 0 ||
		*receipt.Data.Inserted > len(request.Observations) ||
		*receipt.Data.Unchanged != len(request.Observations)-*receipt.Data.Inserted {
		return errors.New("trend receipt mismatch")
	}
	state.LastDriveID = state.Pending.LastDriveID
	state.Pending = nil
	if state.LastDriveID == state.HighWaterID {
		state.HighWaterID = 0
	}
	return saveState(b.config, state)
}

func (b *bridge) run(ctx context.Context) error {
	for {
		delay := b.config.PollInterval
		if err := b.pollOnce(ctx); err != nil && ctx.Err() == nil {
			// Never log source rows, credentials, or response bodies.
			var status trendHTTPError
			if errors.As(err, &status) {
				log.Printf("battery trend poll failed stage=import_http_%d", status.status)
				if status.status >= 400 && status.status < 500 && status.status != http.StatusRequestTimeout && status.status != http.StatusTooManyRequests {
					return status
				}
				if status.retryAfter > delay {
					delay = status.retryAfter
				}
			} else {
				log.Print("battery trend poll failed")
			}
		}
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
}
