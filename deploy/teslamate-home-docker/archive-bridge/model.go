package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"time"
)

type Config struct {
	DatabaseURL      string
	ArchiveURL       string
	ArchiveToken     string
	ArchiveVehicleID string
	SourceInstanceID string
	SourceVehicleID  string
	SourceCarID      int64
	StateFile        string
	BatchSize        int
	PollInterval     time.Duration
}

type Cursor struct {
	LastDriveID      int64  `json:"last_drive_id"`
	LastChargeID     int64  `json:"last_charge_id"`
	ArchiveVehicleID string `json:"archive_vehicle_id"`
	SourceInstanceID string `json:"source_instance_id"`
	SourceVehicleID  string `json:"source_vehicle_id"`
}

type DriveRecord struct {
	ID             int64
	StartedAt      *time.Time
	EndedAt        *time.Time
	OdometerStart  *float64
	OdometerEnd    *float64
	EnergyConsumed *float64
	StartAddress   *string
	EndAddress     *string
	Route          []RoutePoint
}

type RoutePoint struct {
	Date      *time.Time `json:"date"`
	Latitude  *float64   `json:"latitude"`
	Longitude *float64   `json:"longitude"`
	Speed     *float64   `json:"speed"`
	Power     *float64   `json:"power"`
	Heading   *float64   `json:"heading"`
}

type ChargeRecord struct {
	ID           int64
	StartedAt    *time.Time
	EndedAt      *time.Time
	EnergyAdded  *float64
	Cost         *float64
	Address      *string
	ChargePoints []ChargePoint
}

type ChargePoint struct {
	Date         *time.Time `json:"date"`
	BatteryLevel *int       `json:"battery_level"`
	EnergyAdded  *float64   `json:"energy_added"`
	ChargerPower *float64   `json:"charger_power"`
	Latitude     *float64   `json:"latitude"`
	Longitude    *float64   `json:"longitude"`
}

type ArchiveBatch struct {
	SourceInstanceID string          `json:"source_instance_id"`
	SourceVehicleID  string          `json:"source_vehicle_id"`
	Source           string          `json:"source"`
	SourceType       string          `json:"source_type"`
	ChunkID          string          `json:"chunk_id"`
	Drives           []ArchiveDrive  `json:"drives"`
	Charges          []ArchiveCharge `json:"charges"`
}

type ArchiveDrive struct {
	SourceRecordID string       `json:"source_record_id"`
	SessionID      string       `json:"session_id"`
	StartedAt      *time.Time   `json:"started_at"`
	EndedAt        *time.Time   `json:"ended_at"`
	OdometerStart  *float64     `json:"odometer_start"`
	OdometerEnd    *float64     `json:"odometer_end"`
	EnergyConsumed *float64     `json:"energy_consumed"`
	StartAddress   *string      `json:"start_address"`
	EndAddress     *string      `json:"end_address"`
	Route          []RoutePoint `json:"route"`
}

type ArchiveCharge struct {
	SourceRecordID string        `json:"source_record_id"`
	SessionID      string        `json:"session_id"`
	StartedAt      *time.Time    `json:"started_at"`
	EndedAt        *time.Time    `json:"ended_at"`
	EnergyAdded    *float64      `json:"energy_added"`
	Cost           *float64      `json:"cost"`
	Address        *string       `json:"address"`
	ChargePoints   []ChargePoint `json:"charge_points"`
}

func buildArchiveBatch(config Config, cursor Cursor, drives []DriveRecord, charges []ChargeRecord) ArchiveBatch {
	batch := ArchiveBatch{
		SourceInstanceID: config.SourceInstanceID,
		SourceVehicleID:  config.SourceVehicleID,
		Source:           "teslamate",
		SourceType:       "teslamate",
		Drives:           make([]ArchiveDrive, 0, len(drives)),
		Charges:          make([]ArchiveCharge, 0, len(charges)),
	}
	for _, drive := range drives {
		sourceID := sourceRecordID("drive", drive.ID)
		batch.Drives = append(batch.Drives, ArchiveDrive{
			SourceRecordID: sourceID,
			SessionID:      sourceID,
			StartedAt:      drive.StartedAt,
			EndedAt:        drive.EndedAt,
			OdometerStart:  drive.OdometerStart,
			OdometerEnd:    drive.OdometerEnd,
			EnergyConsumed: drive.EnergyConsumed,
			StartAddress:   drive.StartAddress,
			EndAddress:     drive.EndAddress,
			Route:          append([]RoutePoint{}, drive.Route...),
		})
	}
	for _, charge := range charges {
		sourceID := sourceRecordID("charging_process", charge.ID)
		batch.Charges = append(batch.Charges, ArchiveCharge{
			SourceRecordID: sourceID,
			SessionID:      sourceID,
			StartedAt:      charge.StartedAt,
			EndedAt:        charge.EndedAt,
			EnergyAdded:    charge.EnergyAdded,
			Cost:           charge.Cost,
			Address:        charge.Address,
			ChargePoints:   append([]ChargePoint{}, charge.ChargePoints...),
		})
	}
	batch.ChunkID = chunkID(config, cursor, batch)
	return batch
}

func sourceRecordID(kind string, id int64) string {
	return fmt.Sprintf("teslamate:%s:%d", kind, id)
}

func chunkID(config Config, cursor Cursor, batch ArchiveBatch) string {
	hash := sha256.New()
	fmt.Fprintf(hash, "v1\x00%s\x00%s\x00%d\x00%d\x00", config.SourceInstanceID, config.SourceVehicleID, cursor.LastDriveID, cursor.LastChargeID)
	for _, drive := range batch.Drives {
		hash.Write([]byte(drive.SourceRecordID))
		hash.Write([]byte{0})
	}
	for _, charge := range batch.Charges {
		hash.Write([]byte(charge.SourceRecordID))
		hash.Write([]byte{0})
	}
	return "teslamate-" + hex.EncodeToString(hash.Sum(nil)[:16])
}

func nextCursor(cursor Cursor, drives []DriveRecord, charges []ChargeRecord) Cursor {
	next := cursor
	for _, drive := range drives {
		if drive.ID > next.LastDriveID {
			next.LastDriveID = drive.ID
		}
	}
	for _, charge := range charges {
		if charge.ID > next.LastChargeID {
			next.LastChargeID = charge.ID
		}
	}
	return next
}
