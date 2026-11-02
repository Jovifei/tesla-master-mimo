package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const archiveBindingIdentityHeader = "X-MateLink-Binding-Identity"

var errArchiveBindingIdentityMismatch = errors.New("archive_binding_identity_mismatch")
var errArchiveBindingIdentityUnavailable = errors.New("archive_binding_identity_unavailable")

// The binding ID is a server-generated random 256-bit value, never returned to clients.
func archiveBindingIdentity(bindingID string, binding archiveBinding) ([]byte, error) {
	key, err := hex.DecodeString(bindingID)
	if err != nil || len(key) != 32 || hex.EncodeToString(key) != bindingID || binding.UserID == "" ||
		len(binding.UserID) > 4096 || strings.ContainsRune(binding.UserID, 0) || binding.VehicleID <= 0 || binding.SourceType != "teslamate" ||
		binding.SourceInstanceID == "" || len(binding.SourceInstanceID) > 128 || strings.TrimSpace(binding.SourceInstanceID) != binding.SourceInstanceID || strings.ContainsRune(binding.SourceInstanceID, 0) ||
		binding.SourceVehicleID == "" || len(binding.SourceVehicleID) > 128 || strings.TrimSpace(binding.SourceVehicleID) != binding.SourceVehicleID || strings.ContainsRune(binding.SourceVehicleID, 0) {
		return nil, errArchiveBindingIdentityUnavailable
	}
	mac := hmac.New(sha256.New, key)
	for _, part := range []string{"matelink.archive-binding.identity.v1", binding.UserID, strconv.Itoa(binding.VehicleID),
		binding.SourceType, binding.SourceInstanceID, binding.SourceVehicleID} {
		var size [4]byte
		binary.BigEndian.PutUint32(size[:], uint32(len(part)))
		_, _ = mac.Write(size[:])
		_, _ = mac.Write([]byte(part))
	}
	return mac.Sum(nil), nil
}

func lockedArchiveBindingIdentity(ctx context.Context, tx pgx.Tx, binding archiveBinding, tokenHash string) ([]byte, error) {
	var id string
	err := tx.QueryRow(ctx, `SELECT id FROM jourvolt_history_archive_bindings WHERE token_hash=$1 AND user_id=$2 AND vehicle_id=$3 AND source_instance_id=$4 AND source_vehicle_id=$5 FOR SHARE`,
		tokenHash, binding.UserID, binding.VehicleID, binding.SourceInstanceID, binding.SourceVehicleID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errArchiveBindingScopeMismatch
	}
	if err != nil {
		return nil, err
	}
	return archiveBindingIdentity(id, binding)
}

func verifyLockedArchiveBindingIdentity(ctx context.Context, tx pgx.Tx, binding archiveBinding, tokenHash string, expected []byte) error {
	actual, err := lockedArchiveBindingIdentity(ctx, tx, binding, tokenHash)
	if err != nil {
		return err
	}
	if len(expected) != sha256.Size || subtle.ConstantTimeCompare(actual, expected) != 1 {
		return errArchiveBindingIdentityMismatch
	}
	return nil
}

func archiveBindingIdentityPath(path string) bool {
	parts := strings.Split(path, "/")
	return len(parts) == 8 && parts[0] == "" && parts[1] == "api" && parts[2] == "v1" && parts[3] == "cars" &&
		parts[5] == "history" && parts[6] == "archive" && parts[7] == "binding-identity"
}

func singleV2Header(r *http.Request, name string) (string, bool) {
	values := r.Header.Values(name)
	if len(values) != 1 || !batteryTrendHashPattern.MatchString(values[0]) {
		return "", false
	}
	return values[0], true
}

func (a *app) archiveBindingIdentityError(w http.ResponseWriter, err error) {
	if errors.Is(err, errArchiveBindingRequired) || errors.Is(err, errArchiveBindingInvalid) ||
		errors.Is(err, errArchiveBindingRevoked) || errors.Is(err, errArchiveBindingExpired) ||
		errors.Is(err, errArchiveBindingScopeMismatch) {
		a.archiveBindingError(w, err)
		return
	}
	a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "archive_binding_unavailable"})
}

// Identity is available only to the currently authorized, locked binding.
func (a *app) archiveBindingIdentity(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Pragma", "no-cache")
	if !archiveBindingIdentityPath(r.URL.Path) || r.Method != http.MethodGet || r.URL.RawQuery != "" || r.URL.EscapedPath() != r.URL.Path {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	vehicleID, err := strconv.Atoi(strings.Split(r.URL.Path, "/")[4])
	if err != nil || vehicleID <= 0 {
		a.json(w, http.StatusNotFound, map[string]string{"error": "vehicle_not_found"})
		return
	}
	token, ok := singleV2Header(r, archiveBindingHeader)
	if !ok {
		a.archiveBindingError(w, errArchiveBindingRequired)
		return
	}
	repository := a.archiveBindingRepository()
	if repository == nil || a.store == nil || a.store.pool == nil || (a.telemetry != nil && a.telemetry.memory != nil) {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "archive_binding_unavailable"})
		return
	}
	binding, err := repository.resolveArchiveBinding(r.Context(), token)
	if err != nil {
		a.archiveBindingIdentityError(w, err)
		return
	}
	if binding.VehicleID != vehicleID {
		a.archiveBindingError(w, errArchiveBindingScopeMismatch)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	tx, err := a.store.pool.Begin(ctx)
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "archive_binding_unavailable"})
		return
	}
	defer func() {
		rollbackCtx, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		_ = tx.Rollback(rollbackCtx)
	}()
	var owner int
	err = tx.QueryRow(ctx, `SELECT id FROM jourvolt_vehicles WHERE id=$1 AND user_id=$2 FOR SHARE`, binding.VehicleID, binding.UserID).Scan(&owner)
	if errors.Is(err, pgx.ErrNoRows) {
		a.archiveBindingError(w, errArchiveBindingScopeMismatch)
		return
	}
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "archive_binding_unavailable"})
		return
	}
	tokenHash := archiveBindingTokenHash(token)
	expires, err := lockBatteryTrendBinding(ctx, tx, binding, tokenHash)
	if err != nil {
		a.archiveBindingIdentityError(w, err)
		return
	}
	identity, err := lockedArchiveBindingIdentity(ctx, tx, binding, tokenHash)
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "archive_binding_unavailable"})
		return
	}
	if err = requireBatteryTrendBindingNotExpired(ctx, tx, expires); err != nil {
		a.archiveBindingIdentityError(w, err)
		return
	}
	if err = tx.Commit(ctx); err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "archive_binding_unavailable"})
		return
	}
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{"schema_version": 1, "binding_identity": hex.EncodeToString(identity)}})
}
