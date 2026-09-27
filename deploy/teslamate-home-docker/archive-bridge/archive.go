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

func NewBridge(config Config, source HistorySource) *Bridge {
	return &Bridge{config: config, source: source, httpClient: &http.Client{Timeout: 30 * time.Second}}
}

func (b *Bridge) PollOnce(ctx context.Context) error {
	cursor, err := loadCursor(b.config.StateFile)
	if err != nil {
		return err
	}
	drives, err := b.source.FetchDrives(ctx, cursor.LastDriveID, int64(b.config.BatchSize))
	if err != nil {
		return err
	}
	charges, err := b.source.FetchCharges(ctx, cursor.LastChargeID, int64(b.config.BatchSize))
	if err != nil {
		return err
	}
	if len(drives) == 0 && len(charges) == 0 {
		return nil
	}
	batch := buildArchiveBatch(b.config, cursor, drives, charges)
	if err := b.importBatch(ctx, batch); err != nil {
		return err
	}
	return saveCursor(b.config.StateFile, nextCursor(cursor, drives, charges))
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
	_, _ = io.Copy(io.Discard, response.Body)
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		return fmt.Errorf("archive import returned HTTP status %d", response.StatusCode)
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
		fmt.Println("archive poll failed")
	}
	ticker := time.NewTicker(b.config.PollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			if err := b.PollOnce(ctx); err != nil && !errors.Is(err, context.Canceled) {
				fmt.Println("archive poll failed")
			}
		}
	}
}
