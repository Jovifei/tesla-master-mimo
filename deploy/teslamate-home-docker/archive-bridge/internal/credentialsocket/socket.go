package credentialsocket

import "errors"

var (
	ErrUnavailable = errors.New("credential broker unavailable")
	ErrRejected    = errors.New("credential broker rejected")
)

type Credentials struct {
	DatabaseURL      string `json:"database_url"`
	ArchiveToken     string `json:"archive_token"`
	ArchiveURL       string `json:"archive_url"`
	VehicleID        string `json:"vehicle_id"`
	SourceInstanceID string `json:"source_instance_id"`
	SourceVehicleID  string `json:"source_vehicle_id"`
}
