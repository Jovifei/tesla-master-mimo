package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"net"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const archiveEnrollmentBase = "/api/v1/archive/enrollments"
const archiveEnrollmentLifetime = 10 * time.Minute
const archiveEnrollmentInterval = 5 * time.Second

// Enrollment rows are independent of archive history and contain no plaintext
// credential. The short-lived encrypted receipt makes a lost claim response safe.
const archiveEnrollmentSchema = `
CREATE TABLE IF NOT EXISTS jourvolt_archive_enrollments (
 handle TEXT PRIMARY KEY,
 secret_hash TEXT NOT NULL,
 ip_hash TEXT NOT NULL,
 vehicle_id INTEGER NOT NULL,
 source_instance_id TEXT NOT NULL,
 source_vehicle_id TEXT NOT NULL,
 verification_code TEXT NOT NULL,
 status TEXT NOT NULL CHECK (status IN ('pending','approved','rejected','issued','installed')),
 created_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 next_poll_at TIMESTAMPTZ NOT NULL,
 approved_user_id TEXT NOT NULL DEFAULT '',
 binding_id TEXT NOT NULL DEFAULT '',
 approved_token_hash TEXT NOT NULL DEFAULT '',
 issued_token_hash TEXT NOT NULL DEFAULT '',
 issued_ciphertext TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS jourvolt_archive_enrollments_expiry_idx ON jourvolt_archive_enrollments(expires_at);
CREATE INDEX IF NOT EXISTS jourvolt_archive_enrollments_created_idx ON jourvolt_archive_enrollments(created_at);
CREATE INDEX IF NOT EXISTS jourvolt_archive_enrollments_ip_created_idx ON jourvolt_archive_enrollments(ip_hash,created_at);
`

var (
	errEnrollmentInvalid     = errors.New("archive_enrollment_invalid_request")
	errEnrollmentSession     = errors.New("archive_enrollment_session_required")
	errEnrollmentProof       = errors.New("archive_enrollment_proof_invalid")
	errEnrollmentForbidden   = errors.New("archive_enrollment_forbidden")
	errEnrollmentExpired     = errors.New("archive_enrollment_expired")
	errEnrollmentRate        = errors.New("archive_enrollment_rate_limited")
	errEnrollmentUnavailable = errors.New("archive_enrollment_unavailable")
	enrollmentOpaque         = regexp.MustCompile(`^[0-9a-f]{64}$`)
	enrollmentSource         = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)
)

type archiveEnrollmentScope struct {
	VehicleID        int    `json:"vehicle_id"`
	SourceInstanceID string `json:"source_instance_id"`
	SourceVehicleID  string `json:"source_vehicle_id"`
}

func (s archiveEnrollmentScope) fingerprint() string {
	var b bytes.Buffer
	encoder := json.NewEncoder(&b)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode([]any{s.VehicleID, s.SourceInstanceID, s.SourceVehicleID})
	return hashToken(strings.TrimSuffix(b.String(), "\n"))[:16]
}

type archiveEnrollment struct {
	Handle           string
	SecretHash       string
	Scope            archiveEnrollmentScope
	Code             string
	Status           string
	ExpiresAt        time.Time
	NextPollAt       time.Time
	ApprovedUser     string
	BindingID        string
	ApprovedHash     string
	IssuedHash       string
	IssuedCiphertext string
}

type enrollmentBinding struct {
	ID        string
	TokenHash string
	Name      string
	ExpiresAt time.Time
	RevokedAt *time.Time
}

func decodeEnrollmentBody(w http.ResponseWriter, r *http.Request, target any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if decoder.Decode(target) != nil || decoder.Decode(&struct{}{}) != io.EOF {
		return errEnrollmentInvalid
	}
	return nil
}

// No redirect, suffix trimming, query proof or alternate escaped spelling is
// accepted at this credential boundary.
func (a *app) archiveEnrollmentRoute(w http.ResponseWriter, r *http.Request) bool {
	if !strings.HasPrefix(r.URL.Path, archiveEnrollmentBase) {
		return false
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Pragma", "no-cache")
	if r.URL.RawQuery != "" || r.URL.EscapedPath() != r.URL.Path {
		a.enrollmentError(w, errEnrollmentInvalid)
		return true
	}
	rest := strings.TrimPrefix(r.URL.Path, archiveEnrollmentBase)
	parts := strings.Split(strings.TrimPrefix(rest, "/"), "/")
	action, handle := "create", ""
	if rest != "" {
		if !strings.HasPrefix(rest, "/") || len(parts) > 2 || !enrollmentOpaque.MatchString(parts[0]) {
			a.json(w, 404, map[string]string{"error": "not_found"})
			return true
		}
		handle = parts[0]
		action = "preview"
		if len(parts) == 2 {
			action = parts[1]
			if action != "decision" && action != "claim" && action != "ack" {
				a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
				return true
			}
		}
	}
	if (action == "preview" && r.Method != http.MethodGet) || (action != "preview" && r.Method != http.MethodPost) ||
		(action != "create" && action != "preview" && action != "decision" && action != "claim" && action != "ack") {
		a.json(w, 404, map[string]string{"error": "not_found"})
		return true
	}
	if a.store == nil || a.store.pool == nil {
		a.enrollmentError(w, errEnrollmentUnavailable)
		return true
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	if action == "create" {
		var scope archiveEnrollmentScope
		if err := decodeEnrollmentBody(w, r, &scope); err != nil || scope.VehicleID <= 0 || scope.VehicleID > 2147483647 ||
			!enrollmentSource.MatchString(scope.SourceInstanceID) || !enrollmentSource.MatchString(scope.SourceVehicleID) {
			a.enrollmentError(w, errEnrollmentInvalid)
			return true
		}
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil || net.ParseIP(ip) == nil {
			a.enrollmentError(w, errEnrollmentInvalid)
			return true
		}
		record, secret, err := a.store.createArchiveEnrollment(ctx, scope, hashToken(net.ParseIP(ip).String()))
		if err != nil {
			a.enrollmentError(w, err)
			return true
		}
		a.json(w, 201, map[string]any{"data": map[string]any{"handle": record.Handle, "device_secret": secret,
			"verification_code": record.Code, "expires_at": record.ExpiresAt.UTC().Format(time.RFC3339Nano), "interval_seconds": 5}})
		return true
	}
	var code, proof string
	var approve *bool
	if action == "decision" {
		var body struct {
			Approve *bool  `json:"approve"`
			Code    string `json:"verification_code"`
		}
		if decodeEnrollmentBody(w, r, &body) != nil || body.Approve == nil || len(body.Code) != 6 {
			a.enrollmentError(w, errEnrollmentInvalid)
			return true
		}
		approve, code = body.Approve, body.Code
	} else if action == "claim" || action == "ack" {
		var body struct {
			Secret string `json:"device_secret"`
		}
		if decodeEnrollmentBody(w, r, &body) != nil {
			a.enrollmentError(w, errEnrollmentInvalid)
			return true
		}
		if !enrollmentOpaque.MatchString(body.Secret) {
			a.enrollmentError(w, errEnrollmentProof)
			return true
		}
		proof = body.Secret
	}
	var cipher *tokenCipher
	if a.oauth != nil {
		cipher = a.oauth.cipher
	}
	if cipher == nil && a.telemetry != nil {
		cipher = a.telemetry.cipher
	}
	data, status, err := a.store.archiveEnrollmentAction(ctx, handle, action, bearerToken(r), proof, code, approve, cipher)
	if err != nil {
		a.enrollmentError(w, err)
		return true
	}
	a.json(w, status, map[string]any{"data": data})
	return true
}

func (a *app) enrollmentError(w http.ResponseWriter, err error) {
	status, code := 503, errEnrollmentUnavailable.Error()
	switch {
	case errors.Is(err, errEnrollmentInvalid):
		status, code = 400, errEnrollmentInvalid.Error()
	case errors.Is(err, errEnrollmentSession):
		status, code = 401, errEnrollmentSession.Error()
	case errors.Is(err, errEnrollmentProof):
		status, code = 401, errEnrollmentProof.Error()
	case errors.Is(err, errEnrollmentForbidden):
		status, code = 403, errEnrollmentForbidden.Error()
	case errors.Is(err, errEnrollmentExpired):
		status, code = 410, errEnrollmentExpired.Error()
	case errors.Is(err, errEnrollmentRate):
		status, code = 429, errEnrollmentRate.Error()
		w.Header().Set("Retry-After", "5")
	}
	a.json(w, status, map[string]string{"error": code})
}

func (s *store) createArchiveEnrollment(ctx context.Context, scope archiveEnrollmentScope, ipHash string) (archiveEnrollment, string, error) {
	handle, err := randomToken()
	if err != nil {
		return archiveEnrollment{}, "", err
	}
	secret, err := randomToken()
	if err != nil {
		return archiveEnrollment{}, "", err
	}
	code, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		return archiveEnrollment{}, "", err
	}
	codeText := code.String()
	codeText = strings.Repeat("0", 6-len(codeText)) + codeText
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return archiveEnrollment{}, "", err
	}
	defer tx.Rollback(ctx)
	// Across API instances this one bounded creation lock prevents rate/capacity
	// races. RemoteAddr identifies the immediate peer; behind nginx this is a
	// shared peer quota. Untrusted X-Forwarded-For is never an identity source.
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(714923061)`); err != nil {
		return archiveEnrollment{}, "", err
	}
	_, err = tx.Exec(ctx, `WITH expired AS (SELECT handle FROM jourvolt_archive_enrollments
 WHERE expires_at<=clock_timestamp() AND issued_ciphertext<>'' ORDER BY expires_at LIMIT 100 FOR UPDATE SKIP LOCKED)
 UPDATE jourvolt_archive_enrollments e SET issued_ciphertext='' FROM expired WHERE e.handle=expired.handle`)
	if err != nil {
		return archiveEnrollment{}, "", err
	}
	_, err = tx.Exec(ctx, `WITH expired AS (SELECT handle FROM jourvolt_archive_enrollments
 WHERE expires_at<clock_timestamp()-interval '24 hours' ORDER BY expires_at LIMIT 100 FOR UPDATE SKIP LOCKED)
 DELETE FROM jourvolt_archive_enrollments e USING expired WHERE e.handle=expired.handle`)
	if err != nil {
		return archiveEnrollment{}, "", err
	}
	var total, active, recent, ipRecent int
	err = tx.QueryRow(ctx, `SELECT count(*),count(*) FILTER (WHERE expires_at>clock_timestamp()),
 count(*) FILTER (WHERE created_at>clock_timestamp()-interval '1 minute'),
 count(*) FILTER (WHERE ip_hash=$1 AND created_at>clock_timestamp()-interval '1 minute') FROM jourvolt_archive_enrollments`, ipHash).Scan(&total, &active, &recent, &ipRecent)
	if err != nil {
		return archiveEnrollment{}, "", err
	}
	if total >= 10000 || active >= 1000 || recent >= 60 || ipRecent >= 5 {
		return archiveEnrollment{}, "", errEnrollmentRate
	}
	now := time.Now().UTC()
	record := archiveEnrollment{Handle: handle, SecretHash: hashToken(secret), Scope: scope, Code: codeText, Status: "pending", ExpiresAt: now.Add(archiveEnrollmentLifetime)}
	_, err = tx.Exec(ctx, `INSERT INTO jourvolt_archive_enrollments
 (handle,secret_hash,ip_hash,vehicle_id,source_instance_id,source_vehicle_id,verification_code,status,created_at,expires_at,next_poll_at)
 VALUES ($1,$2,$3,$4,$5,$6,$7,'pending',$8,$9,$8)`, handle, record.SecretHash, ipHash, scope.VehicleID, scope.SourceInstanceID, scope.SourceVehicleID, codeText, now, record.ExpiresAt)
	if err != nil {
		return archiveEnrollment{}, "", err
	}
	err = tx.Commit(ctx)
	return record, secret, err
}

func readArchiveEnrollment(ctx context.Context, tx pgx.Tx, handle string) (archiveEnrollment, error) {
	var e archiveEnrollment
	err := tx.QueryRow(ctx, `SELECT handle,secret_hash,vehicle_id,source_instance_id,source_vehicle_id,verification_code,status,expires_at,next_poll_at,
 approved_user_id,binding_id,approved_token_hash,issued_token_hash,issued_ciphertext
 FROM jourvolt_archive_enrollments WHERE handle=$1 FOR UPDATE`, handle).Scan(&e.Handle, &e.SecretHash, &e.Scope.VehicleID, &e.Scope.SourceInstanceID,
		&e.Scope.SourceVehicleID, &e.Code, &e.Status, &e.ExpiresAt, &e.NextPollAt, &e.ApprovedUser, &e.BindingID, &e.ApprovedHash, &e.IssuedHash, &e.IssuedCiphertext)
	return e, err
}

func readEnrollmentBinding(ctx context.Context, tx pgx.Tx, e archiveEnrollment, user string) (enrollmentBinding, error) {
	var b enrollmentBinding
	err := tx.QueryRow(ctx, `SELECT b.id,b.token_hash,v.display_name,b.expires_at,b.revoked_at
 FROM jourvolt_history_archive_bindings b JOIN jourvolt_vehicles v ON v.id=b.vehicle_id AND v.user_id=b.user_id
 WHERE b.user_id=$1 AND b.vehicle_id=$2 AND b.source_instance_id=$3 AND b.source_vehicle_id=$4
 FOR UPDATE OF b FOR SHARE OF v`, user, e.Scope.VehicleID, e.Scope.SourceInstanceID, e.Scope.SourceVehicleID).Scan(&b.ID, &b.TokenHash, &b.Name, &b.ExpiresAt, &b.RevokedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return b, errEnrollmentForbidden
	}
	if err != nil {
		return b, err
	}
	if b.RevokedAt != nil {
		return b, errEnrollmentForbidden
	}
	if e.Status != "pending" && (e.ApprovedUser != user || e.BindingID != b.ID) {
		return b, errEnrollmentForbidden
	}
	if e.Status == "approved" && e.ApprovedHash != b.TokenHash {
		return b, errEnrollmentForbidden
	}
	if (e.Status == "issued" || e.Status == "installed") && e.IssuedHash != b.TokenHash {
		return b, errEnrollmentForbidden
	}
	return b, nil
}

func enrollmentPreview(e archiveEnrollment, b enrollmentBinding) map[string]any {
	state := "active"
	if !b.ExpiresAt.After(time.Now()) {
		state = "expired"
	}
	return map[string]any{"handle": e.Handle, "status": e.Status, "verification_code": e.Code, "expires_at": e.ExpiresAt.UTC().Format(time.RFC3339Nano),
		"vehicle_id": e.Scope.VehicleID, "vehicle_name": b.Name, "source_label": "TeslaMate", "scope_fingerprint": e.Scope.fingerprint(), "binding_state": state}
}

func (s *store) archiveEnrollmentAction(ctx context.Context, handle, action, access, proof, code string, approve *bool, cipher *tokenCipher) (map[string]any, int, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, 0, err
	}
	defer tx.Rollback(ctx)
	owner := ""
	if action == "preview" || action == "decision" {
		if access == "" {
			return nil, 0, errEnrollmentSession
		}
		var expiry time.Time
		err = tx.QueryRow(ctx, `SELECT user_id,access_expires_at FROM jourvolt_sessions WHERE access_hash=$1 AND revoked_at IS NULL FOR SHARE`, hashToken(access)).Scan(&owner, &expiry)
		if errors.Is(err, pgx.ErrNoRows) || (err == nil && !expiry.After(time.Now())) {
			return nil, 0, errEnrollmentSession
		}
		if err != nil {
			return nil, 0, err
		}
	}
	e, err := readArchiveEnrollment(ctx, tx, handle)
	if errors.Is(err, pgx.ErrNoRows) {
		if owner == "" {
			return nil, 0, errEnrollmentProof
		}
		return nil, 0, errEnrollmentForbidden
	}
	if err != nil {
		return nil, 0, err
	}
	if owner == "" && subtle.ConstantTimeCompare([]byte(e.SecretHash), []byte(hashToken(proof))) != 1 {
		return nil, 0, errEnrollmentProof
	}
	// This wall-clock check occurs after all potentially blocking request locks.
	if !e.ExpiresAt.After(time.Now()) {
		return nil, 0, errEnrollmentExpired
	}
	if owner != "" {
		b, err := readEnrollmentBinding(ctx, tx, e, owner)
		if err != nil {
			return nil, 0, err
		}
		if !e.ExpiresAt.After(time.Now()) {
			return nil, 0, errEnrollmentExpired
		}
		// A session may expire while waiting for the request or binding lock.
		var valid bool
		if err = tx.QueryRow(ctx, `SELECT access_expires_at>clock_timestamp() AND revoked_at IS NULL FROM jourvolt_sessions WHERE access_hash=$1`, hashToken(access)).Scan(&valid); err != nil {
			return nil, 0, err
		}
		if !valid {
			return nil, 0, errEnrollmentSession
		}
		if action == "preview" {
			return enrollmentPreview(e, b), 200, nil
		}
		if code != e.Code {
			return nil, 0, errEnrollmentForbidden
		}
		if e.Status == "approved" && *approve {
			return enrollmentPreview(e, b), 200, nil
		}
		if e.Status == "rejected" && !*approve {
			return enrollmentPreview(e, b), 200, nil
		}
		if e.Status != "pending" && !(e.Status == "approved" && !*approve) {
			return nil, 0, errEnrollmentForbidden
		}
		e.Status = "rejected"
		if *approve {
			e.Status = "approved"
		}
		e.ApprovedUser = owner
		e.BindingID = b.ID
		e.ApprovedHash = b.TokenHash
		_, err = tx.Exec(ctx, `UPDATE jourvolt_archive_enrollments SET status=$2,approved_user_id=$3,binding_id=$4,approved_token_hash=$5 WHERE handle=$1`, e.Handle, e.Status, owner, b.ID, b.TokenHash)
		if err != nil {
			return nil, 0, err
		}
		if err = tx.Commit(ctx); err != nil {
			return nil, 0, err
		}
		return enrollmentPreview(e, b), 200, nil
	}
	if e.Status == "rejected" {
		return nil, 0, errEnrollmentForbidden
	}
	if action == "claim" && e.Status == "pending" {
		if e.NextPollAt.After(time.Now()) {
			return nil, 0, errEnrollmentRate
		}
		_, err = tx.Exec(ctx, `UPDATE jourvolt_archive_enrollments SET next_poll_at=clock_timestamp()+interval '5 seconds' WHERE handle=$1`, e.Handle)
		if err != nil {
			return nil, 0, err
		}
		if err = tx.Commit(ctx); err != nil {
			return nil, 0, err
		}
		return map[string]any{"state": "pending", "interval_seconds": 5}, 202, nil
	}
	if e.Status == "pending" {
		return nil, 0, errEnrollmentForbidden
	}
	b, err := readEnrollmentBinding(ctx, tx, e, e.ApprovedUser)
	if err != nil {
		return nil, 0, err
	}
	if !e.ExpiresAt.After(time.Now()) {
		return nil, 0, errEnrollmentExpired
	}
	if action == "ack" {
		if e.Status == "installed" {
			return map[string]any{"state": "installed"}, 200, nil
		}
		if e.Status != "issued" {
			return nil, 0, errEnrollmentForbidden
		}
		_, err = tx.Exec(ctx, `UPDATE jourvolt_archive_enrollments SET status='installed',issued_ciphertext='' WHERE handle=$1`, e.Handle)
		if err != nil {
			return nil, 0, err
		}
		if err = tx.Commit(ctx); err != nil {
			return nil, 0, err
		}
		return map[string]any{"state": "installed"}, 200, nil
	}
	if e.Status == "installed" {
		return map[string]any{"state": "installed"}, 200, nil
	}
	if e.Status != "approved" && e.Status != "issued" {
		return nil, 0, errEnrollmentForbidden
	}
	if cipher == nil {
		return nil, 0, errEnrollmentUnavailable
	}
	credential := ""
	if e.Status == "issued" {
		credential, err = cipher.decrypt(e.IssuedCiphertext)
		if err != nil || archiveBindingTokenHash(credential) != e.IssuedHash {
			return nil, 0, errEnrollmentUnavailable
		}
	} else {
		if e.NextPollAt.After(time.Now()) {
			return nil, 0, errEnrollmentRate
		}
		credential, err = randomToken()
		if err != nil {
			return nil, 0, err
		}
		ciphertext, err := cipher.encrypt(credential)
		if err != nil {
			return nil, 0, err
		}
		issuedHash := archiveBindingTokenHash(credential)
		// PostgreSQL timestamps preserve microseconds. Use that same precision
		// in the first response so every retried receipt is byte-for-byte stable.
		b.ExpiresAt = time.Now().UTC().Add(archiveBindingLifetime).Truncate(time.Microsecond)
		tag, err := tx.Exec(ctx, `UPDATE jourvolt_history_archive_bindings SET token_hash=$2,expires_at=$3
 WHERE id=$1 AND user_id=$4 AND vehicle_id=$5 AND source_instance_id=$6 AND source_vehicle_id=$7
 AND token_hash=$8 AND revoked_at IS NULL`, b.ID, issuedHash, b.ExpiresAt, e.ApprovedUser, e.Scope.VehicleID, e.Scope.SourceInstanceID, e.Scope.SourceVehicleID, e.ApprovedHash)
		if err != nil {
			return nil, 0, err
		}
		if tag.RowsAffected() != 1 {
			return nil, 0, errEnrollmentForbidden
		}
		_, err = tx.Exec(ctx, `UPDATE jourvolt_archive_enrollments SET status='issued',issued_token_hash=$2,issued_ciphertext=$3 WHERE handle=$1`, e.Handle, issuedHash, ciphertext)
		if err != nil {
			return nil, 0, err
		}
		if err = tx.Commit(ctx); err != nil {
			return nil, 0, err
		}
	}
	return map[string]any{"state": "issued", "credential": credential, "expires_at": b.ExpiresAt.UTC().Format(time.RFC3339Nano), "scope_fingerprint": e.Scope.fingerprint()}, 200, nil
}
