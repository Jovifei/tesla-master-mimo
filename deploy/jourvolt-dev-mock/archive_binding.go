package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const archiveBindingLifetime = 30 * 24 * time.Hour

type archiveBindingRequest struct {
	SourceInstanceID string `json:"source_instance_id"`
	SourceVehicleID  string `json:"source_vehicle_id"`
}

type archiveBinding struct {
	UserID           string
	VehicleID        int
	SourceInstanceID string
	SourceVehicleID  string
	ExpiresAt        time.Time
}

func validateArchiveBindingRequest(request archiveBindingRequest) error {
	if strings.TrimSpace(request.SourceInstanceID) == "" || strings.TrimSpace(request.SourceVehicleID) == "" {
		return errors.New("archive_source_binding_required")
	}
	return nil
}

func archiveBindingTokenHash(token string) string { return hashToken(token) }

func (s *store) createArchiveBinding(ctx context.Context, userID string, vehicleID int, request archiveBindingRequest) (string, archiveBinding, error) {
	if s == nil || s.pool == nil {
		return "", archiveBinding{}, errors.New("store_unavailable")
	}
	if err := validateArchiveBindingRequest(request); err != nil {
		return "", archiveBinding{}, err
	}
	token, err := randomToken()
	if err != nil {
		return "", archiveBinding{}, err
	}
	id, err := randomToken()
	if err != nil {
		return "", archiveBinding{}, err
	}
	expiresAt := time.Now().UTC().Add(archiveBindingLifetime)
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", archiveBinding{}, err
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `UPDATE jourvolt_history_archive_bindings SET revoked_at=now()
WHERE user_id=$1 AND vehicle_id=$2 AND source_instance_id=$3 AND source_vehicle_id=$4 AND revoked_at IS NULL`,
		userID, vehicleID, request.SourceInstanceID, request.SourceVehicleID); err != nil {
		return "", archiveBinding{}, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO jourvolt_history_archive_bindings
(id,user_id,vehicle_id,source_instance_id,source_vehicle_id,token_hash,created_at,expires_at)
VALUES ($1,$2,$3,$4,$5,$6,now(),$7)`, id, userID, vehicleID, request.SourceInstanceID, request.SourceVehicleID, archiveBindingTokenHash(token), expiresAt); err != nil {
		return "", archiveBinding{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return "", archiveBinding{}, err
	}
	return token, archiveBinding{UserID: userID, VehicleID: vehicleID, SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID, ExpiresAt: expiresAt}, nil
}

func (s *store) resolveArchiveBinding(ctx context.Context, token string) (archiveBinding, error) {
	if s == nil || s.pool == nil {
		return archiveBinding{}, errors.New("store_unavailable")
	}
	var binding archiveBinding
	var revokedAt *time.Time
	err := s.pool.QueryRow(ctx, `SELECT user_id,vehicle_id,source_instance_id,source_vehicle_id,expires_at,revoked_at
FROM jourvolt_history_archive_bindings WHERE token_hash=$1`, archiveBindingTokenHash(token)).Scan(
		&binding.UserID, &binding.VehicleID, &binding.SourceInstanceID, &binding.SourceVehicleID, &binding.ExpiresAt, &revokedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return archiveBinding{}, errors.New("archive_binding_invalid")
	}
	if err != nil {
		return archiveBinding{}, err
	}
	if revokedAt != nil || !binding.ExpiresAt.After(time.Now().UTC()) {
		return archiveBinding{}, errors.New("archive_binding_expired")
	}
	return binding, nil
}

func (s *store) revokeArchiveBinding(ctx context.Context, userID string, vehicleID int, request archiveBindingRequest) error {
	if s == nil || s.pool == nil {
		return errors.New("store_unavailable")
	}
	if err := validateArchiveBindingRequest(request); err != nil {
		return err
	}
	_, err := s.pool.Exec(ctx, `UPDATE jourvolt_history_archive_bindings SET revoked_at=now()
WHERE user_id=$1 AND vehicle_id=$2 AND source_instance_id=$3 AND source_vehicle_id=$4 AND revoked_at IS NULL`, userID, vehicleID, request.SourceInstanceID, request.SourceVehicleID)
	return err
}

func (a *app) historyArchiveBind(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	if r.Method != http.MethodPost {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	var request archiveBindingRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_json"})
		return
	}
	token, binding, err := a.store.createArchiveBinding(r.Context(), userID, vehicleID, request)
	if err != nil {
		a.json(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
		"binding_token": token, "source_instance_id": binding.SourceInstanceID, "source_vehicle_id": binding.SourceVehicleID,
		"vehicle_id": binding.VehicleID, "expires_at": binding.ExpiresAt.UTC().Format(time.RFC3339),
	}})
}

func (a *app) historyArchiveRevoke(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	var request archiveBindingRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_json"})
		return
	}
	if err := a.store.revokeArchiveBinding(r.Context(), userID, vehicleID, request); err != nil {
		a.json(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	a.json(w, http.StatusOK, map[string]string{"status": "revoked"})
}

func (a *app) archiveBindingResource(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/api/v1/cars/"), "/")
	if len(parts) < 4 || parts[1] != "history" || parts[2] != "archive" || parts[3] != "import" {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	vehicleID, err := strconv.Atoi(parts[0])
	if err != nil {
		a.json(w, http.StatusNotFound, map[string]string{"error": "vehicle_not_found"})
		return
	}
	binding, err := a.store.resolveArchiveBinding(r.Context(), strings.TrimSpace(r.Header.Get("X-MateLink-Archive-Binding")))
	if err != nil || binding.VehicleID != vehicleID {
		a.json(w, http.StatusUnauthorized, map[string]string{"error": "archive_binding_invalid"})
		return
	}
	a.historyArchiveImportForBinding(w, r, binding)
}

func (a *app) historyArchiveImportForBinding(w http.ResponseWriter, r *http.Request, binding archiveBinding) {
	if r.Method != http.MethodPost {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	request, err := importRequestFromBody(w, r)
	if err == nil {
		err = validateArchiveImportRequest(request)
	}
	if err == nil && (request.SourceInstanceID != binding.SourceInstanceID || request.SourceVehicleID != binding.SourceVehicleID) {
		err = &historyImportSessionValidationError{Message: "archive_binding_scope_mismatch"}
	}
	if err != nil {
		var validationErr *historyImportSessionValidationError
		if errors.As(err, &validationErr) {
			a.json(w, http.StatusBadRequest, map[string]string{"error": validationErr.Message})
			return
		}
		a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_request"})
		return
	}
	result, err := a.telemetry.importHistory(r.Context(), binding.UserID, binding.VehicleID, request)
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "history_archive_import_failed"})
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": result})
}
