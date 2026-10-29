package main

import (
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"reflect"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const diagnosticsMaxBody = 256 << 10

type fleetDiagnosticRequest struct {
	Status     string  `json:"status"`
	HTTPStatus *int    `json:"http_status"`
	ErrorClass *string `json:"error_class"`
}

type fleetDiagnosticVehicle struct {
	Paired                *bool   `json:"paired"`
	FirmwareVersion       *string `json:"firmware_version"`
	FleetTelemetryVersion *string `json:"fleet_telemetry_version"`
	StreamingEnabled      *bool   `json:"streaming_enabled"`
}

type fleetDiagnosticDesired struct {
	Hostname *string                                `json:"hostname"`
	Port     *int                                   `json:"port"`
	Fields   map[string]telemetryFieldConfiguration `json:"fields"`
	// SHA256 of sorted, unique, newline-separated SHA256(DER) hex fingerprints.
	CASHA256 *string `json:"ca_sha256"`
}

type fleetDiagnosticMatches struct {
	Hostname *bool `json:"hostname"`
	Port     *bool `json:"port"`
	Fields   *bool `json:"fields"`
	CA       *bool `json:"ca"`
}

type fleetDiagnosticConfiguration struct {
	Hostname       *string                `json:"hostname"`
	Port           *int                   `json:"port"`
	Synced         *bool                  `json:"synced"`
	CASHA256       *string                `json:"ca_sha256"`
	Desired        fleetDiagnosticDesired `json:"desired"`
	MatchesDesired fleetDiagnosticMatches `json:"matches_desired"`
}

type fleetDiagnosticErrors struct {
	Counts map[string]int `json:"counts"`
	LastAt *string        `json:"last_at"`
}

type fleetDiagnosticsResponse struct {
	CheckedAt        string                            `json:"checked_at"`
	BuildSHA         string                            `json:"build_sha"`
	Availability     string                            `json:"availability"`
	Requests         map[string]fleetDiagnosticRequest `json:"requests"`
	Vehicle          fleetDiagnosticVehicle            `json:"vehicle"`
	Configuration    fleetDiagnosticConfiguration      `json:"configuration"`
	RecentErrors     fleetDiagnosticErrors             `json:"recent_errors"`
	ReadinessContext *diagnosticReadinessContext       `json:"readiness_context,omitempty"`
}

// This deliberately narrow reader cannot refresh, lock, or update tokens.
type diagnosticsAccessReader interface {
	diagnosticsAccess(context.Context, string) (string, time.Time, error)
}

func (s *store) diagnosticsAccess(ctx context.Context, userID string) (string, time.Time, error) {
	var ciphertext string
	var expiry time.Time
	err := s.pool.QueryRow(ctx, `SELECT access_ciphertext, access_expires_at
FROM jourvolt_tesla_tokens WHERE user_id=$1`, userID).Scan(&ciphertext, &expiry)
	return ciphertext, expiry, err
}

func (a *app) fleetTelemetryDiagnostics(w http.ResponseWriter, r *http.Request, userID string, carID int, parts []string) {
	canonical := "/api/v1/cars/" + strconv.Itoa(carID) + "/telemetry/diagnostics"
	if r.Method != http.MethodGet || carID <= 0 || carID > 1<<31-1 || len(parts) != 3 || r.URL.Path != canonical || r.URL.EscapedPath() != canonical {
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	if a.store == nil || a.store.pool == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "diagnostics_unavailable"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	var vinCiphertext string
	err := a.store.pool.QueryRow(ctx, `SELECT vin_ciphertext FROM jourvolt_vehicles WHERE id=$1 AND user_id=$2`, carID, userID).Scan(&vinCiphertext)
	if errors.Is(err, pgx.ErrNoRows) {
		a.json(w, http.StatusNotFound, map[string]string{"error": "vehicle_not_found"})
		return
	}
	if err != nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "diagnostics_unavailable"})
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	// Capture persisted evidence before bounded official requests. Local reads
	// remain useful when Tesla access is expired or a provider request fails.
	local := a.readOnlyReadinessContext(ctx, userID, carID)
	response := a.telemetry.readOnlyDiagnostics(ctx, userID, vinCiphertext, a.store)
	response.ReadinessContext = local
	a.json(w, http.StatusOK, map[string]any{"data": response})
}

func diagnosticFailure(status string, httpStatus *int, class string) fleetDiagnosticRequest {
	return fleetDiagnosticRequest{Status: status, HTTPStatus: httpStatus, ErrorClass: &class}
}

func (s *telemetryService) readOnlyDiagnostics(ctx context.Context, userID, vinCiphertext string, access diagnosticsAccessReader) fleetDiagnosticsResponse {
	result := fleetDiagnosticsResponse{
		CheckedAt: time.Now().UTC().Format(time.RFC3339), BuildSHA: buildSHA, Availability: "unavailable",
		Requests: make(map[string]fleetDiagnosticRequest, 3),
	}
	for _, name := range []string{"fleet_status", "fleet_telemetry_config", "fleet_telemetry_errors"} {
		result.Requests[name] = diagnosticFailure("not_attempted", nil, "not_configured")
	}
	if s == nil || s.cipher == nil || s.config == nil {
		return result
	}
	result.Configuration.Desired.Fields = desiredTelemetryConfiguration().Fields
	result.Configuration.Desired.Hostname = diagnosticHostname(s.config.PublicHost)
	if diagnosticContainsPrivate(result.Configuration.Desired.Hostname, []string{userID, vinCiphertext}) {
		result.Configuration.Desired.Hostname = nil
	}
	result.Configuration.Desired.Port = diagnosticPort(s.config.PublicPort)
	ca := s.caPEM
	if ca == "" && s.config.CACertPath != "" {
		// Read only the configured CA mount. No path is taken from the caller or
		// provider, and a malformed/oversized bundle remains explicitly unknown.
		if file, openErr := os.Open(s.config.CACertPath); openErr == nil {
			data, readErr := io.ReadAll(io.LimitReader(file, diagnosticsMaxBody+1))
			_ = file.Close()
			if readErr == nil && len(data) <= diagnosticsMaxBody {
				ca = string(data)
			}
		}
	}
	result.Configuration.Desired.CASHA256 = diagnosticCAFingerprint(ca)
	base := diagnosticOfficialOrigin(s.fleetAPIBase)
	if base == nil {
		return result
	}
	failAll := func(class string) fleetDiagnosticsResponse {
		for name := range result.Requests {
			result.Requests[name] = diagnosticFailure("not_attempted", nil, class)
		}
		return result
	}
	vin, err := s.cipher.decrypt(vinCiphertext)
	if err != nil || !diagnosticVINPattern.MatchString(vin) {
		return failAll("vehicle_identity_unavailable")
	}
	if diagnosticContainsPrivate(result.Configuration.Desired.Hostname, []string{vin}) {
		result.Configuration.Desired.Hostname = nil
	}
	ciphertext, expiry, err := access.diagnosticsAccess(ctx, userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return failAll("reauthorization_required")
	}
	if err != nil {
		return failAll("token_unavailable")
	}
	if !expiry.After(time.Now().UTC()) {
		return failAll("access_expired")
	}
	token, err := s.cipher.decrypt(ciphertext)
	if err != nil || token == "" {
		return failAll("token_unavailable")
	}
	// Nothing derived from these private inputs may be projected as a string.
	private := []string{vin, token, userID, vinCiphertext, ciphertext}
	if diagnosticContainsPrivate(result.Configuration.Desired.Hostname, private) {
		result.Configuration.Desired.Hostname = nil
	}
	body, _ := json.Marshal(struct {
		VINs []string `json:"vins"`
	}{[]string{vin}})
	client := credentialHTTPClient(s.httpClient)
	if client.Timeout <= 0 || client.Timeout > 4*time.Second {
		client.Timeout = 4 * time.Second
	}
	for _, name := range []string{"fleet_status", "fleet_telemetry_config", "fleet_telemetry_errors"} {
		method, path, payload := http.MethodGet, "/api/1/vehicles/"+vin+"/"+name, []byte(nil)
		if name == "fleet_status" {
			method, path, payload = http.MethodPost, "/api/1/vehicles/fleet_status", body
		}
		data, request := diagnosticRequest(ctx, client, strings.TrimRight(base.String(), "/")+path, method, token, payload)
		if request.Status == "ok" {
			var valid bool
			switch name {
			case "fleet_status":
				valid = result.readDiagnosticStatus(data, vin, private)
			case "fleet_telemetry_config":
				valid = result.readDiagnosticConfig(data, private)
			case "fleet_telemetry_errors":
				valid = result.readDiagnosticErrors(data)
			}
			if !valid {
				request = diagnosticFailure("error", request.HTTPStatus, "response_incomplete")
			}
		}
		result.Requests[name] = request
	}
	ok := 0
	for _, request := range result.Requests {
		if request.Status == "ok" {
			ok++
		}
	}
	if ok == 3 {
		result.Availability = "available"
	} else if ok > 0 {
		result.Availability = "partial"
	}
	return result
}

// Credentials are sent only to Tesla's documented regional Fleet API origins.
// Configuration alone cannot expand this boundary. HTTPS defaults to port 443;
// an explicit 443 is equivalent, while paths, queries and alternate ports fail.
func diagnosticOfficialOrigin(raw string) *url.URL {
	base, err := url.Parse(raw)
	if err != nil || base.Scheme != "https" || base.User != nil || base.RawQuery != "" || base.ForceQuery || base.Fragment != "" || base.RawFragment != "" || base.RawPath != "" || (base.Path != "" && base.Path != "/") {
		return nil
	}
	host := strings.ToLower(base.Host)
	switch host {
	case "fleet-api.prd.na.vn.cloud.tesla.com", "fleet-api.prd.na.vn.cloud.tesla.com:443",
		"fleet-api.prd.eu.vn.cloud.tesla.com", "fleet-api.prd.eu.vn.cloud.tesla.com:443",
		"fleet-api.prd.cn.vn.cloud.tesla.cn", "fleet-api.prd.cn.vn.cloud.tesla.cn:443":
		base.Host = host
		return base
	default:
		return nil
	}
}

func diagnosticRequest(ctx context.Context, client *http.Client, endpoint, method, token string, body []byte) ([]byte, fleetDiagnosticRequest) {
	req, err := http.NewRequestWithContext(ctx, method, endpoint, strings.NewReader(string(body)))
	if err != nil {
		return nil, diagnosticFailure("error", nil, "request_invalid")
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	response, err := client.Do(req)
	if err != nil {
		class := "transport_error"
		var timeout net.Error
		if errors.Is(err, context.DeadlineExceeded) || errors.As(err, &timeout) && timeout.Timeout() {
			class = "timeout"
		}
		return nil, diagnosticFailure("error", nil, class)
	}
	defer response.Body.Close()
	status := response.StatusCode
	if status < 200 || status >= 300 {
		class := "provider_error"
		switch {
		case status >= 300 && status < 400:
			class = "redirect_rejected"
		case status == 401:
			class = "reauthorization_required"
		case status == 402:
			class = "billing_blocked"
		case status == 403:
			class = "permission_required"
		case status == 404:
			class = "provider_not_found"
		case status == 429:
			class = "rate_limited"
		case status >= 400 && status < 500:
			class = "provider_rejected"
		}
		return nil, diagnosticFailure("error", &status, class)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, diagnosticsMaxBody+1))
	if err != nil {
		return nil, diagnosticFailure("error", &status, "response_read_failed")
	}
	if len(data) > diagnosticsMaxBody {
		return nil, diagnosticFailure("error", &status, "response_too_large")
	}
	if !json.Valid(data) {
		return nil, diagnosticFailure("error", &status, "response_invalid")
	}
	return data, fleetDiagnosticRequest{Status: "ok", HTTPStatus: &status}
}

var (
	diagnosticVINPattern      = regexp.MustCompile(`^[A-HJ-NPR-Z0-9]{17}$`)
	diagnosticFirmwarePattern = regexp.MustCompile(`^[0-9]{4}(\.[0-9]{1,3}){1,4}( [a-fA-F0-9]{7,40})?$`)
	diagnosticVersionPattern  = regexp.MustCompile(`^[0-9]{1,3}(\.[0-9]{1,3}){1,3}$`)
	diagnosticHostnamePattern = regexp.MustCompile(`^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$`)
)

func diagnosticContainsPrivate(value *string, private []string) bool {
	if value == nil {
		return false
	}
	for _, secret := range private {
		if secret != "" && strings.Contains(strings.ToLower(*value), strings.ToLower(secret)) {
			return true
		}
	}
	return false
}

func diagnosticString(value *string, pattern *regexp.Regexp, private []string) *string {
	if value == nil || len(*value) > 96 || !pattern.MatchString(*value) || diagnosticContainsPrivate(value, private) {
		return nil
	}
	return value
}

func diagnosticHostname(value string) *string {
	value = strings.ToLower(strings.TrimSuffix(value, "."))
	if !diagnosticHostnamePattern.MatchString(value) || strings.Contains(value, "..") {
		return nil
	}
	for _, label := range strings.Split(value, ".") {
		if len(label) > 63 || strings.HasPrefix(label, "-") || strings.HasSuffix(label, "-") {
			return nil
		}
	}
	return &value
}

func diagnosticPort(port int) *int {
	if port < 1 || port > 65535 {
		return nil
	}
	return &port
}

func diagnosticCAFingerprint(value string) *string {
	unique := make(map[string]bool)
	remaining := []byte(strings.TrimSpace(value))
	for len(remaining) > 0 {
		if !strings.HasPrefix(string(remaining), "-----BEGIN CERTIFICATE-----") {
			return nil
		}
		block, rest := pem.Decode(remaining)
		if block == nil || block.Type != "CERTIFICATE" || len(block.Headers) != 0 {
			return nil
		}
		cert, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			return nil
		}
		sum := sha256.Sum256(cert.Raw)
		unique[hex.EncodeToString(sum[:])] = true
		remaining = []byte(strings.TrimSpace(string(rest)))
	}
	if len(unique) == 0 {
		return nil
	}
	keys := make([]string, 0, len(unique))
	for key := range unique {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	sum := sha256.Sum256([]byte(strings.Join(keys, "\n")))
	fingerprint := hex.EncodeToString(sum[:])
	return &fingerprint
}

func (d *fleetDiagnosticsResponse) readDiagnosticStatus(data []byte, vin string, private []string) bool {
	var envelope struct {
		Response *struct {
			Paired      *[]string `json:"key_paired_vins"`
			Unpaired    *[]string `json:"unpaired_vins"`
			VehicleInfo map[string]struct {
				Firmware  *string `json:"firmware_version"`
				Version   *string `json:"fleet_telemetry_version"`
				Streaming *bool   `json:"safety_screen_streaming_toggle_enabled"`
			} `json:"vehicle_info"`
		} `json:"response"`
	}
	if json.Unmarshal(data, &envelope) != nil || envelope.Response == nil {
		return false
	}
	response := envelope.Response
	paired, unpaired := false, false
	if response.Paired != nil {
		for _, value := range *response.Paired {
			paired = paired || value == vin
		}
	}
	if response.Unpaired != nil {
		for _, value := range *response.Unpaired {
			unpaired = unpaired || value == vin
		}
	}
	if paired != unpaired {
		d.Vehicle.Paired = &paired
	}
	if info, ok := response.VehicleInfo[vin]; ok {
		d.Vehicle.FirmwareVersion = diagnosticString(info.Firmware, diagnosticFirmwarePattern, private)
		d.Vehicle.FleetTelemetryVersion = diagnosticString(info.Version, diagnosticVersionPattern, private)
		d.Vehicle.StreamingEnabled = info.Streaming
	}
	return d.Vehicle.Paired != nil || d.Vehicle.FirmwareVersion != nil || d.Vehicle.FleetTelemetryVersion != nil || d.Vehicle.StreamingEnabled != nil
}

func (d *fleetDiagnosticsResponse) readDiagnosticConfig(data []byte, private []string) bool {
	var envelope struct {
		Response *struct {
			Synced *bool `json:"synced"`
			Config *struct {
				Hostname *string                                `json:"hostname"`
				Port     *int                                   `json:"port"`
				CA       *string                                `json:"ca"`
				Fields   map[string]telemetryFieldConfiguration `json:"fields"`
			} `json:"config"`
		} `json:"response"`
	}
	if json.Unmarshal(data, &envelope) != nil || envelope.Response == nil {
		return false
	}
	d.Configuration.Synced = envelope.Response.Synced
	config := envelope.Response.Config
	if config == nil {
		return envelope.Response.Synced != nil
	}
	if config.Hostname != nil {
		d.Configuration.Hostname = diagnosticHostname(*config.Hostname)
		if diagnosticContainsPrivate(d.Configuration.Hostname, private) {
			d.Configuration.Hostname = nil
		}
	}
	if config.Port != nil {
		d.Configuration.Port = diagnosticPort(*config.Port)
	}
	if config.CA != nil {
		d.Configuration.CASHA256 = diagnosticCAFingerprint(*config.CA)
	}
	desired := d.Configuration.Desired
	if desired.Hostname != nil && d.Configuration.Hostname != nil {
		d.Configuration.MatchesDesired.Hostname = boolPointer(*desired.Hostname == *d.Configuration.Hostname)
	}
	if desired.Port != nil && d.Configuration.Port != nil {
		d.Configuration.MatchesDesired.Port = boolPointer(*desired.Port == *d.Configuration.Port)
	}
	if desired.CASHA256 != nil && d.Configuration.CASHA256 != nil {
		d.Configuration.MatchesDesired.CA = boolPointer(*desired.CASHA256 == *d.Configuration.CASHA256)
	}
	if config.Fields != nil {
		d.Configuration.MatchesDesired.Fields = boolPointer(reflect.DeepEqual(config.Fields, desired.Fields))
	}
	return d.Configuration.Synced != nil || d.Configuration.Hostname != nil || d.Configuration.Port != nil || d.Configuration.CASHA256 != nil || d.Configuration.MatchesDesired.Fields != nil
}

func (d *fleetDiagnosticsResponse) readDiagnosticErrors(data []byte) bool {
	var envelope struct {
		Response *struct {
			Errors *[]struct {
				Name      string `json:"error_name"`
				CreatedAt string `json:"created_at"`
			} `json:"fleet_telemetry_errors"`
		} `json:"response"`
	}
	if json.Unmarshal(data, &envelope) != nil || envelope.Response == nil || envelope.Response.Errors == nil {
		return false
	}
	d.RecentErrors.Counts = make(map[string]int)
	var last time.Time
	for _, item := range *envelope.Response.Errors {
		category := "unknown"
		switch item.Name {
		case "cloud_manager_error":
			category = "connection"
		case "tls_error", "certificate_error":
			category = "tls"
		case "configuration_error", "config_error":
			category = "configuration"
		case "rate_limit_error":
			category = "rate_limited"
		case "authentication_error":
			category = "authentication"
		}
		d.RecentErrors.Counts[category]++
		if created, err := time.Parse(time.RFC3339Nano, item.CreatedAt); err == nil && created.After(last) && !created.After(time.Now().UTC().Add(time.Minute)) {
			last = created
		}
	}
	if !last.IsZero() {
		value := last.UTC().Format(time.RFC3339Nano)
		d.RecentErrors.LastAt = &value
	}
	return true
}
