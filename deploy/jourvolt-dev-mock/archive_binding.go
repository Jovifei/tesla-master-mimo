package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const (
	archiveBindingLifetime = 30 * 24 * time.Hour
	archiveBindingHeader   = "X-MateLink-Archive-Binding"
)

var (
	errArchiveBindingRequired      = errors.New("archive_binding_required")
	errArchiveBindingInvalid       = errors.New("archive_binding_invalid")
	errArchiveBindingRevoked       = errors.New("archive_binding_revoked")
	errArchiveBindingExpired       = errors.New("archive_binding_expired")
	errArchiveBindingScopeMismatch = errors.New("archive_binding_scope_mismatch")
	errArchiveBindingNotFound      = errors.New("archive_binding_not_found")
)

type archiveBindingRequest struct {
	SourceType       string `json:"source_type,omitempty"`
	SourceInstanceID string `json:"source_instance_id"`
	SourceVehicleID  string `json:"source_vehicle_id"`
}

type archiveBinding struct {
	UserID           string
	VehicleID        int
	SourceType       string
	SourceInstanceID string
	SourceVehicleID  string
	CreatedAt        time.Time
	ExpiresAt        time.Time
	RevokedAt        *time.Time
}

type archiveBindingRepository interface {
	createArchiveBinding(context.Context, string, int, archiveBindingRequest) (string, archiveBinding, error)
	resolveArchiveBinding(context.Context, string) (archiveBinding, error)
	getArchiveBinding(context.Context, string, int, archiveBindingRequest) (archiveBinding, error)
	revokeArchiveBinding(context.Context, string, int, archiveBindingRequest) error
}

func normalizeArchiveBindingRequest(request archiveBindingRequest) (archiveBindingRequest, error) {
	request.SourceType = strings.TrimSpace(request.SourceType)
	if request.SourceType == "" {
		request.SourceType = "teslamate"
	}
	request.SourceInstanceID = strings.TrimSpace(request.SourceInstanceID)
	request.SourceVehicleID = strings.TrimSpace(request.SourceVehicleID)
	if request.SourceType != "teslamate" {
		return archiveBindingRequest{}, errors.New("archive_source_type_unsupported")
	}
	if request.SourceInstanceID == "" || request.SourceVehicleID == "" {
		return archiveBindingRequest{}, errors.New("archive_source_binding_required")
	}
	return request, nil
}

func validateArchiveBindingRequest(request archiveBindingRequest) error {
	_, err := normalizeArchiveBindingRequest(request)
	return err
}

func archiveBindingTokenHash(token string) string { return hashToken(strings.TrimSpace(token)) }

func archiveBindingMatches(binding archiveBinding, userID string, vehicleID int, request archiveBindingRequest) bool {
	request, err := normalizeArchiveBindingRequest(request)
	return err == nil && binding.UserID == userID && binding.VehicleID == vehicleID &&
		binding.SourceType == request.SourceType && binding.SourceInstanceID == request.SourceInstanceID &&
		binding.SourceVehicleID == request.SourceVehicleID
}

func (b archiveBinding) state(now time.Time) string {
	if b.RevokedAt != nil {
		return "revoked"
	}
	if !b.ExpiresAt.After(now) {
		return "expired"
	}
	return "active"
}

func (b archiveBinding) metadata(now time.Time) map[string]any {
	data := map[string]any{
		"vehicle_id":         b.VehicleID,
		"source_type":        b.SourceType,
		"source_instance_id": b.SourceInstanceID,
		"source_vehicle_id":  b.SourceVehicleID,
		"created_at":         b.CreatedAt.UTC().Format(time.RFC3339Nano),
		"expires_at":         b.ExpiresAt.UTC().Format(time.RFC3339Nano),
		"revoked_at":         nil,
		"state":              b.state(now),
	}
	scope := "teslamate:" + b.SourceInstanceID + ":" + b.SourceVehicleID
	data["scope"] = scope
	data["scopes"] = []string{scope}
	if b.RevokedAt != nil {
		data["revoked_at"] = b.RevokedAt.UTC().Format(time.RFC3339Nano)
	}
	return data
}

func (s *store) createArchiveBinding(ctx context.Context, userID string, vehicleID int, request archiveBindingRequest) (string, archiveBinding, error) {
	if s == nil || s.pool == nil {
		return "", archiveBinding{}, errors.New("store_unavailable")
	}
	request, err := normalizeArchiveBindingRequest(request)
	if err != nil {
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
	now := time.Now().UTC()
	expiresAt := now.Add(archiveBindingLifetime)
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", archiveBinding{}, err
	}
	defer tx.Rollback(ctx)
	var binding archiveBinding
	err = tx.QueryRow(ctx, `
INSERT INTO jourvolt_history_archive_bindings
(id,user_id,vehicle_id,source_instance_id,source_vehicle_id,token_hash,created_at,expires_at,revoked_at)
VALUES ($1,$2,$3,$4,$5,$6,$7,$8,NULL)
ON CONFLICT (user_id, vehicle_id, source_instance_id, source_vehicle_id) DO UPDATE SET
  id=EXCLUDED.id,
  token_hash=EXCLUDED.token_hash,
  created_at=EXCLUDED.created_at,
  expires_at=EXCLUDED.expires_at,
  revoked_at=NULL
RETURNING user_id, vehicle_id, source_instance_id, source_vehicle_id, created_at, expires_at, revoked_at`,
		id, userID, vehicleID, request.SourceInstanceID, request.SourceVehicleID,
		archiveBindingTokenHash(token), now, expiresAt).Scan(
		&binding.UserID, &binding.VehicleID, &binding.SourceInstanceID, &binding.SourceVehicleID,
		&binding.CreatedAt, &binding.ExpiresAt, &binding.RevokedAt)
	if err != nil {
		return "", archiveBinding{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return "", archiveBinding{}, err
	}
	binding.SourceType = request.SourceType
	return token, binding, nil
}

func (s *store) resolveArchiveBinding(ctx context.Context, token string) (archiveBinding, error) {
	if s == nil || s.pool == nil {
		return archiveBinding{}, errors.New("store_unavailable")
	}
	if strings.TrimSpace(token) == "" {
		return archiveBinding{}, errArchiveBindingRequired
	}
	var binding archiveBinding
	err := s.pool.QueryRow(ctx, `SELECT user_id, vehicle_id, source_instance_id, source_vehicle_id, created_at, expires_at, revoked_at
FROM jourvolt_history_archive_bindings WHERE token_hash=$1`, archiveBindingTokenHash(token)).Scan(
		&binding.UserID, &binding.VehicleID, &binding.SourceInstanceID, &binding.SourceVehicleID,
		&binding.CreatedAt, &binding.ExpiresAt, &binding.RevokedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return archiveBinding{}, errArchiveBindingInvalid
	}
	if err != nil {
		return archiveBinding{}, err
	}
	binding.SourceType = "teslamate"
	if binding.RevokedAt != nil {
		return binding, errArchiveBindingRevoked
	}
	if !binding.ExpiresAt.After(time.Now().UTC()) {
		return binding, errArchiveBindingExpired
	}
	return binding, nil
}

func (s *store) getArchiveBinding(ctx context.Context, userID string, vehicleID int, request archiveBindingRequest) (archiveBinding, error) {
	if s == nil || s.pool == nil {
		return archiveBinding{}, errors.New("store_unavailable")
	}
	request, err := normalizeArchiveBindingRequest(request)
	if err != nil {
		return archiveBinding{}, err
	}
	var binding archiveBinding
	err = s.pool.QueryRow(ctx, `SELECT user_id, vehicle_id, source_instance_id, source_vehicle_id, created_at, expires_at, revoked_at
FROM jourvolt_history_archive_bindings
WHERE user_id=$1 AND vehicle_id=$2 AND source_instance_id=$3 AND source_vehicle_id=$4`,
		userID, vehicleID, request.SourceInstanceID, request.SourceVehicleID).Scan(
		&binding.UserID, &binding.VehicleID, &binding.SourceInstanceID, &binding.SourceVehicleID,
		&binding.CreatedAt, &binding.ExpiresAt, &binding.RevokedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return archiveBinding{}, errArchiveBindingNotFound
	}
	if err != nil {
		return archiveBinding{}, err
	}
	binding.SourceType = request.SourceType
	return binding, nil
}

func (s *store) revokeArchiveBinding(ctx context.Context, userID string, vehicleID int, request archiveBindingRequest) error {
	if s == nil || s.pool == nil {
		return errors.New("store_unavailable")
	}
	request, err := normalizeArchiveBindingRequest(request)
	if err != nil {
		return err
	}
	commandTag, err := s.pool.Exec(ctx, `UPDATE jourvolt_history_archive_bindings SET revoked_at=COALESCE(revoked_at, now())
WHERE user_id=$1 AND vehicle_id=$2 AND source_instance_id=$3 AND source_vehicle_id=$4`,
		userID, vehicleID, request.SourceInstanceID, request.SourceVehicleID)
	if err != nil {
		return err
	}
	if commandTag.RowsAffected() != 1 {
		return errArchiveBindingNotFound
	}
	return nil
}

func (s *telemetryMemoryStore) createArchiveBinding(ctx context.Context, userID string, vehicleID int, request archiveBindingRequest) (string, archiveBinding, error) {
	request, err := normalizeArchiveBindingRequest(request)
	if err != nil {
		return "", archiveBinding{}, err
	}
	token, err := randomToken()
	if err != nil {
		return "", archiveBinding{}, err
	}
	now := time.Now().UTC()
	return token, s.createArchiveBindingAt(token, userID, vehicleID, request, now, now.Add(archiveBindingLifetime)), nil
}

func (s *telemetryMemoryStore) createArchiveBindingAt(token, userID string, vehicleID int, request archiveBindingRequest, createdAt, expiresAt time.Time) archiveBinding {
	request, _ = normalizeArchiveBindingRequest(request)
	binding := archiveBinding{UserID: userID, VehicleID: vehicleID, SourceType: request.SourceType,
		SourceInstanceID: request.SourceInstanceID, SourceVehicleID: request.SourceVehicleID,
		CreatedAt: createdAt.UTC(), ExpiresAt: expiresAt.UTC()}
	tokenHash := archiveBindingTokenHash(token)
	s.mu.Lock()
	defer s.mu.Unlock()
	for hash, existing := range s.archiveBindings {
		if archiveBindingMatches(existing, userID, vehicleID, request) {
			delete(s.archiveBindings, hash)
		}
	}
	s.archiveBindings[tokenHash] = binding
	return binding
}

func (s *telemetryMemoryStore) resolveArchiveBinding(_ context.Context, token string) (archiveBinding, error) {
	if strings.TrimSpace(token) == "" {
		return archiveBinding{}, errArchiveBindingRequired
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	binding, ok := s.archiveBindings[archiveBindingTokenHash(token)]
	if !ok {
		return archiveBinding{}, errArchiveBindingInvalid
	}
	if binding.RevokedAt != nil {
		return binding, errArchiveBindingRevoked
	}
	if !binding.ExpiresAt.After(time.Now().UTC()) {
		return binding, errArchiveBindingExpired
	}
	return binding, nil
}

func (s *telemetryMemoryStore) getArchiveBinding(_ context.Context, userID string, vehicleID int, request archiveBindingRequest) (archiveBinding, error) {
	request, err := normalizeArchiveBindingRequest(request)
	if err != nil {
		return archiveBinding{}, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, binding := range s.archiveBindings {
		if archiveBindingMatches(binding, userID, vehicleID, request) {
			return binding, nil
		}
	}
	return archiveBinding{}, errArchiveBindingNotFound
}

func (s *telemetryMemoryStore) revokeArchiveBinding(_ context.Context, userID string, vehicleID int, request archiveBindingRequest) error {
	request, err := normalizeArchiveBindingRequest(request)
	if err != nil {
		return err
	}
	now := time.Now().UTC()
	s.mu.Lock()
	defer s.mu.Unlock()
	for hash, binding := range s.archiveBindings {
		if archiveBindingMatches(binding, userID, vehicleID, request) {
			if binding.RevokedAt == nil {
				binding.RevokedAt = &now
				s.archiveBindings[hash] = binding
			}
			return nil
		}
	}
	return errArchiveBindingNotFound
}

func (a *app) archiveBindingRepository() archiveBindingRepository {
	if a == nil {
		return nil
	}
	if a.telemetry != nil && a.telemetry.memory != nil {
		return a.telemetry.memory
	}
	if a.store != nil && a.store.pool != nil {
		return a.store
	}
	return nil
}

func decodeArchiveBindingRequest(r *http.Request) (archiveBindingRequest, error) {
	decoder := json.NewDecoder(r.Body)
	var request archiveBindingRequest
	if err := decoder.Decode(&request); err != nil {
		return archiveBindingRequest{}, errors.New("invalid_json")
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return archiveBindingRequest{}, errors.New("invalid_json")
	}
	return normalizeArchiveBindingRequest(request)
}

func archiveBindingRequestFromQuery(r *http.Request) (archiveBindingRequest, error) {
	return normalizeArchiveBindingRequest(archiveBindingRequest{
		SourceType:       r.URL.Query().Get("source_type"),
		SourceInstanceID: r.URL.Query().Get("source_instance_id"),
		SourceVehicleID:  r.URL.Query().Get("source_vehicle_id"),
	})
}

func (a *app) historyArchiveBind(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	switch r.Method {
	case http.MethodPost:
		repository := a.archiveBindingRepository()
		if repository == nil {
			a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "archive_binding_unavailable"})
			return
		}
		request, err := decodeArchiveBindingRequest(r)
		if err != nil {
			a.archiveBindingError(w, err)
			return
		}
		token, binding, err := repository.createArchiveBinding(r.Context(), userID, vehicleID, request)
		if err != nil {
			a.archiveBindingError(w, err)
			return
		}
		data := binding.metadata(time.Now().UTC())
		data["token"] = token
		a.json(w, http.StatusOK, map[string]any{"data": data})
	case http.MethodGet:
		a.historyArchiveStatus(w, r, userID, vehicleID)
	case http.MethodDelete:
		a.historyArchiveRevoke(w, r, userID, vehicleID)
	default:
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
	}
}

func (a *app) historyArchiveStatus(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	repository := a.archiveBindingRepository()
	if repository == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "archive_binding_unavailable"})
		return
	}
	request, err := archiveBindingRequestFromQuery(r)
	if err != nil {
		a.archiveBindingError(w, err)
		return
	}
	binding, err := repository.getArchiveBinding(r.Context(), userID, vehicleID, request)
	if err != nil {
		a.archiveBindingError(w, err)
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": binding.metadata(time.Now().UTC())})
}

func (a *app) historyArchiveRevoke(w http.ResponseWriter, r *http.Request, userID string, vehicleID int) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	repository := a.archiveBindingRepository()
	if repository == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "archive_binding_unavailable"})
		return
	}
	request, err := archiveBindingRequestFromQuery(r)
	if err != nil {
		request, err = decodeArchiveBindingRequest(r)
	}
	if err != nil {
		a.archiveBindingError(w, err)
		return
	}
	if err := repository.revokeArchiveBinding(r.Context(), userID, vehicleID, request); err != nil {
		a.archiveBindingError(w, err)
		return
	}
	binding, err := repository.getArchiveBinding(r.Context(), userID, vehicleID, request)
	if err != nil {
		a.archiveBindingError(w, err)
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": binding.metadata(time.Now().UTC())})
}

func archiveImportPath(path string) bool {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	return len(parts) == 7 && parts[0] == "api" && parts[1] == "v1" && parts[2] == "cars" &&
		parts[4] == "history" && parts[5] == "archive" && parts[6] == "import"
}

func archiveImportParts(parts []string) bool {
	return len(parts) == 4 && parts[1] == "history" && parts[2] == "archive" && parts[3] == "import"
}

func (a *app) archiveBindingResource(w http.ResponseWriter, r *http.Request, expectedUserID string) {
	parts := strings.Split(strings.TrimPrefix(strings.TrimSuffix(r.URL.Path, "/"), "/api/v1/cars/"), "/")
	if !archiveImportParts(parts) {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	vehicleID, err := strconv.Atoi(parts[0])
	if err != nil || vehicleID <= 0 {
		a.json(w, http.StatusNotFound, map[string]string{"error": "vehicle_not_found"})
		return
	}
	if strings.TrimSpace(r.Header.Get(archiveBindingHeader)) == "" {
		a.json(w, http.StatusUnauthorized, map[string]string{"error": errArchiveBindingRequired.Error()})
		return
	}
	repository := a.archiveBindingRepository()
	if repository == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "archive_binding_unavailable"})
		return
	}
	binding, err := repository.resolveArchiveBinding(r.Context(), r.Header.Get(archiveBindingHeader))
	if err != nil {
		a.archiveBindingError(w, err)
		return
	}
	if binding.VehicleID != vehicleID || (expectedUserID != "" && binding.UserID != expectedUserID) {
		a.archiveBindingError(w, errArchiveBindingScopeMismatch)
		return
	}
	a.historyArchiveImportForBinding(w, r, binding)
}

func (a *app) historyArchiveImportForBinding(w http.ResponseWriter, r *http.Request, binding archiveBinding) {
	if r.Method != http.MethodPost {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	release, err := acquireHistoryHeavyBudget(r.Context(), binding.UserID, binding.VehicleID)
	if err != nil {
		a.historyAdmissionError(w, err)
		return
	}
	defer release()
	request, err := importRequestFromBody(w, r)
	if err == nil {
		err = validateArchiveImportRequest(request)
	}
	if err == nil && (request.SourceInstanceID != binding.SourceInstanceID || request.SourceVehicleID != binding.SourceVehicleID) {
		err = errArchiveBindingScopeMismatch
	}
	if err != nil {
		var validationErr *historyImportSessionValidationError
		if errors.As(err, &validationErr) {
			status := http.StatusBadRequest
			if validationErr.Message == "request_body_too_large" {
				status = http.StatusRequestEntityTooLarge
			}
			a.json(w, status, map[string]string{"error": validationErr.Message})
			return
		}
		a.archiveBindingError(w, err)
		return
	}
	if a.telemetry == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "history_archive_import_failed"})
		return
	}
	result, err := a.telemetry.importHistoryAdmitted(r.Context(), binding.UserID, binding.VehicleID, request)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			a.json(w, http.StatusRequestTimeout, map[string]string{"error": err.Error()})
			return
		}
		if errors.Is(err, errHistoryResourceOverloaded) {
			w.Header().Set("Retry-After", "1")
			a.json(w, http.StatusTooManyRequests, map[string]string{"error": err.Error()})
			return
		}
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "history_archive_import_failed"})
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": result})
}

func (a *app) archiveBindingError(w http.ResponseWriter, err error) {
	status := http.StatusServiceUnavailable
	switch {
	case errors.Is(err, errArchiveBindingRequired), errors.Is(err, errArchiveBindingInvalid):
		status = http.StatusUnauthorized
	case errors.Is(err, errArchiveBindingRevoked), errors.Is(err, errArchiveBindingExpired), errors.Is(err, errArchiveBindingScopeMismatch):
		status = http.StatusForbidden
	case errors.Is(err, errArchiveBindingNotFound):
		status = http.StatusNotFound
	case err != nil && (err.Error() == "invalid_json" || err.Error() == "archive_source_binding_required" || err.Error() == "archive_source_type_unsupported"):
		status = http.StatusBadRequest
	}
	message := "archive_binding_unavailable"
	if err != nil {
		message = err.Error()
	}
	a.json(w, status, map[string]string{"error": message})
}
