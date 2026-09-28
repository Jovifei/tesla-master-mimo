package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type HistorySource interface {
	FetchDrives(context.Context, int64, int64) ([]DriveRecord, error)
	FetchCharges(context.Context, int64, int64) ([]ChargeRecord, error)
}

type Bridge struct {
	config     Config
	source     HistorySource
	httpClient *http.Client
}

type archiveStageError struct {
	stage string
	err   error
}

func (e *archiveStageError) Error() string { return "archive " + e.stage + " failed" }
func (e *archiveStageError) Unwrap() error { return e.err }

type archiveHTTPStatusError struct{ status int }

func (e *archiveHTTPStatusError) Error() string {
	return fmt.Sprintf("archive import returned HTTP status %d", e.status)
}

func archiveFailureStage(err error) string {
	if err == nil {
		return "none"
	}
	var stage *archiveStageError
	if errors.As(err, &stage) {
		if stage.stage == "import" {
			var status *archiveHTTPStatusError
			if errors.As(stage.err, &status) {
				return fmt.Sprintf("import_http_%d", status.status)
			}
		}
		return stage.stage
	}
	return "unknown"
}

func atArchiveStage(stage string, err error) error {
	if err == nil {
		return nil
	}
	return &archiveStageError{stage: stage, err: err}
}

func NewBridge(config Config, source HistorySource) *Bridge {
	return &Bridge{config: config, source: source, httpClient: &http.Client{
		Timeout: 30 * time.Second,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}}
}

func (b *Bridge) PollOnce(ctx context.Context) error {
	cursor, err := loadCursor(b.config.StateFile)
	if err != nil {
		return atArchiveStage("cursor_read", err)
	}
	cursor, err = scopeCursor(b.config, cursor)
	if err != nil {
		return atArchiveStage("cursor_scope", err)
	}
	drives, err := b.source.FetchDrives(ctx, cursor.LastDriveID, int64(b.config.BatchSize))
	if err != nil {
		return atArchiveStage("source_drives", err)
	}
	charges, err := b.source.FetchCharges(ctx, cursor.LastChargeID, int64(b.config.BatchSize))
	if err != nil {
		return atArchiveStage("source_charges", err)
	}
	if len(drives) == 0 && len(charges) == 0 {
		return nil
	}
	batch := buildArchiveBatch(b.config, cursor, drives, charges)
	if err := b.importBatch(ctx, batch); err != nil {
		return atArchiveStage("import", err)
	}
	return atArchiveStage("cursor_write", saveCursor(b.config.StateFile, nextCursor(cursor, drives, charges)))
}

func (b *Bridge) importBatch(ctx context.Context, batch ArchiveBatch) error {
	payload, err := json.Marshal(batch)
	if err != nil {
		return errors.New("archive request encoding failed")
	}
	endpoint, err := archiveImportURL(b.config)
	if err != nil {
		return errors.New("archive URL is invalid")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(string(payload)))
	if err != nil {
		return errors.New("archive request creation failed")
	}
	request.Header.Set("X-MateLink-Archive-Binding", b.config.ArchiveToken)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	response, err := b.httpClient.Do(request)
	if err != nil {
		return errors.New("archive request failed")
	}
	defer response.Body.Close()
	body, readErr := io.ReadAll(io.LimitReader(response.Body, 64<<10))
	if readErr != nil {
		return errors.New("archive import response could not be read")
	}
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		return &archiveHTTPStatusError{status: response.StatusCode}
	}
	var receipt struct {
		Data struct {
			ImportedDrives  *int `json:"imported_drives"`
			ImportedCharges *int `json:"imported_charges"`
		} `json:"data"`
	}
	if json.Unmarshal(body, &receipt) != nil || receipt.Data.ImportedDrives == nil || receipt.Data.ImportedCharges == nil {
		return errors.New("archive import receipt is incomplete")
	}
	if *receipt.Data.ImportedDrives != len(batch.Drives) || *receipt.Data.ImportedCharges != len(batch.Charges) {
		return errors.New("archive import receipt does not match the submitted batch")
	}
	return nil
}

func archiveImportURL(config Config) (string, error) {
	parsed, err := url.Parse(config.ArchiveURL)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" || parsed.User != nil || strings.TrimSpace(config.ArchiveVehicleID) == "" {
		return "", errors.New("invalid archive URL")
	}
	parsed.Path = strings.TrimRight(parsed.Path, "/") + "/api/v1/cars/" + url.PathEscape(config.ArchiveVehicleID) + "/history/archive/import"
	parsed.RawQuery = ""
	parsed.Fragment = ""
	return parsed.String(), nil
}

func (b *Bridge) Run(ctx context.Context) error {
	if err := b.PollOnce(ctx); err != nil && !errors.Is(err, context.Canceled) {
		// Keep operational logs free of tokens, VINs, coordinates, and response bodies.
		fmt.Printf("archive poll failed stage=%s\n", archiveFailureStage(err))
	}
	ticker := time.NewTicker(b.config.PollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			if err := b.PollOnce(ctx); err != nil && !errors.Is(err, context.Canceled) {
				fmt.Printf("archive poll failed stage=%s\n", archiveFailureStage(err))
			}
		}
	}
}
