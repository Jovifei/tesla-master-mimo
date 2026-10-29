package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgproto3"
	"github.com/jackc/pgx/v5/pgxpool"
)

type diagnosticsNoDiscovery struct{ calls int }

func (p *diagnosticsNoDiscovery) Vehicles(context.Context, string) ([]vehicle, error) {
	p.calls++
	return nil, errVehicleNotFound
}
func (p *diagnosticsNoDiscovery) Status(context.Context, string, int) (vehicleStatus, error) {
	p.calls++
	return vehicleStatus{}, errVehicleNotFound
}

func TestFleetDiagnosticsDoesNotDiscoverVehicles(t *testing.T) {
	p := &diagnosticsNoDiscovery{}
	a := &app{provider: p}
	path := "/api/v1/cars/1/telemetry/diagnostics"
	w := httptest.NewRecorder()
	a.carResource(w, httptest.NewRequest(http.MethodGet, path, nil), "synthetic-user", path)
	if p.calls != 0 {
		t.Fatalf("diagnostics triggered %d live vehicle calls; want none", p.calls)
	}
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("missing local store status = %d; want 503", w.Code)
	}
}

func TestFleetDiagnosticsRequiresAppSession(t *testing.T) {
	w := httptest.NewRecorder()
	(&app{}).ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/telemetry/diagnostics", nil))
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated status = %d; want 401", w.Code)
	}
}

func TestFleetDiagnosticsUnavailableSessionStoreFailsClosed(t *testing.T) {
	defer func() {
		if recover() != nil {
			t.Error("diagnostics panicked without session store")
		}
	}()
	r := httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/telemetry/diagnostics", nil)
	r.Header.Set("Authorization", "Bearer app-session")
	w := httptest.NewRecorder()
	(&app{}).ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("unavailable session store status=%d want401", w.Code)
	}
}

const diagnosticsTestVIN = "5YJ3E1EA7KF123456"
const diagnosticsTestUser = "{{SECRET_b1rcw9ig}}-user"
const diagnosticsTestAccess = "{{SECRET_tio3yg37}}-access-token"

type diagnosticsTestAccessReader struct {
	ciphertext string
	expiry     time.Time
	err        error
	calls      int
}

func (r *diagnosticsTestAccessReader) diagnosticsAccess(context.Context, string) (string, time.Time, error) {
	r.calls++
	return r.ciphertext, r.expiry, r.err
}

func diagnosticsFixture(t *testing.T, handler http.Handler) (*telemetryService, string, *diagnosticsTestAccessReader) {
	t.Helper()
	server, ca := telemetryTestTLSServer(t, handler)
	t.Cleanup(server.Close)
	s := newTelemetryServiceForTest("partner.example.com")
	s.caPEM, s.fleetAPIBase = ca, defaultTeslaFleetURL
	target, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	transport := server.Client().Transport
	// The production origin stays official. Only the synthetic transport
	// redirects the test socket to this loopback TLS fixture.
	s.httpClient = &http.Client{Transport: diagnosticTestTransport(func(r *http.Request) (*http.Response, error) {
		if r.URL.Scheme != "https" || r.URL.Host != strings.TrimPrefix(defaultTeslaFleetURL, "https://") {
			t.Error("unexpected request origin at transport seam")
		}
		clone := r.Clone(r.Context())
		endpoint := *r.URL
		endpoint.Scheme, endpoint.Host = target.Scheme, target.Host
		clone.URL, clone.Host = &endpoint, target.Host
		return transport.RoundTrip(clone)
	})}
	vin, err := s.cipher.encrypt(diagnosticsTestVIN)
	if err != nil {
		t.Fatal(err)
	}
	access, err := s.cipher.encrypt(diagnosticsTestAccess)
	if err != nil {
		t.Fatal(err)
	}
	return s, vin, &diagnosticsTestAccessReader{ciphertext: access, expiry: time.Now().Add(time.Hour)}
}

func TestFleetDiagnosticsEmptyProviderObjectsRemainIncomplete(t *testing.T) {
	s, vin, access := diagnosticsFixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "fleet_status"):
			_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]any{"vehicle_info": map[string]any{diagnosticsTestVIN: map[string]any{}}}})
		case strings.HasSuffix(r.URL.Path, "fleet_telemetry_config"):
			_, _ = io.WriteString(w, `{"response":{"config":{}}}`)
		default:
			_, _ = io.WriteString(w, `{"response":{"fleet_telemetry_errors":[]}}`)
		}
	}))
	result := s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, vin, access)
	if result.Availability != "partial" {
		t.Errorf("empty provider objects availability=%s; want partial", result.Availability)
	}
	for _, name := range []string{"fleet_status", "fleet_telemetry_config"} {
		request := result.Requests[name]
		if request.Status != "error" || request.ErrorClass == nil || *request.ErrorClass != "response_incomplete" || request.HTTPStatus == nil || *request.HTTPStatus != 200 {
			t.Errorf("%s empty evidence was promoted", name)
		}
	}
	if result.Vehicle.Paired != nil || result.Vehicle.FirmwareVersion != nil || result.Vehicle.FleetTelemetryVersion != nil || result.Vehicle.StreamingEnabled != nil || result.Configuration.Hostname != nil || result.Configuration.Port != nil || result.Configuration.Synced != nil {
		t.Fatal("empty provider observations did not stay unknown")
	}
}

func TestFleetDiagnosticsForeignHTTPSOriginRejectedBeforeCredentials(t *testing.T) {
	s, vin, access := diagnosticsFixture(t, http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Error("foreign origin reached provider transport") }))
	for _, origin := range []string{
		"https://arbitrary.example",
		"https://fleet-api.prd.cn.vn.cloud.tesla.cn:444",
		"https://fleet-api.prd.cn.vn.cloud.tesla.cn:443.attacker.example",
		"https://fleet-api.prd.cn.vn.cloud.tesla.cn.attacker.example",
		"https://fleet-api.prd.cn.vn.cloud.tesla.cn?",
		"https://fleet-api.prd.cn.vn.cloud.tesla.cn/%2f",
	} {
		s.fleetAPIBase = origin
		before := access.calls
		result := s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, vin, access)
		if access.calls != before || result.Availability != "unavailable" {
			t.Error("foreign origin read access ciphertext")
		}
		for _, request := range result.Requests {
			if request.Status != "not_attempted" {
				t.Error("foreign origin attempted HTTP")
			}
		}
		// Corrupt ciphertext would produce vehicle_identity_unavailable if
		// decryption were reached. An origin rejection must happen first.
		result = s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, "invalid-ciphertext", access)
		for _, request := range result.Requests {
			if request.ErrorClass == nil || *request.ErrorClass != "not_configured" {
				t.Error("foreign origin reached VIN decryption")
			}
		}
	}
}

func TestFleetDiagnosticsAcceptsOnlyOfficialRegionalOrigins(t *testing.T) {
	s := newTelemetryServiceForTest("partner.example.com")
	vin, err := s.cipher.encrypt(diagnosticsTestVIN)
	if err != nil {
		t.Fatal(err)
	}
	ciphertext, err := s.cipher.encrypt(diagnosticsTestAccess)
	if err != nil {
		t.Fatal(err)
	}
	access := &diagnosticsTestAccessReader{ciphertext: ciphertext, expiry: time.Now().Add(time.Hour)}
	for _, host := range []string{"fleet-api.prd.na.vn.cloud.tesla.com", "fleet-api.prd.eu.vn.cloud.tesla.com", "fleet-api.prd.cn.vn.cloud.tesla.cn"} {
		for _, port := range []string{"", ":443"} {
			calls := 0
			wanted := host + port
			s.fleetAPIBase = "https://" + wanted
			s.httpClient = &http.Client{Transport: diagnosticTestTransport(func(r *http.Request) (*http.Response, error) {
				calls++
				if r.URL.Host != wanted || r.URL.Scheme != "https" {
					t.Error("official regional origin changed")
				}
				return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"response":{"fleet_telemetry_errors":[]}}`))}, nil
			})}
			before := access.calls
			result := s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, vin, access)
			if calls != 3 || access.calls != before+1 || result.Availability != "partial" {
				t.Error("official regional origin rejected")
			}
		}
	}
}

func diagnosticsResultJSON(t *testing.T, d fleetDiagnosticsResponse) string {
	t.Helper()
	data, err := json.Marshal(d)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func TestFleetDiagnosticsPositiveOnlyUsesThreeFixedEndpoints(t *testing.T) {
	var paths []string
	var s *telemetryService
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.Path)
		if r.Header.Get("Authorization") != "Bearer "+diagnosticsTestAccess {
			t.Error("wrong bearer credential")
		}
		if r.URL.RawQuery != "" {
			t.Error("diagnostics forwarded query")
		}
		switch r.URL.Path {
		case "/api/1/vehicles/fleet_status":
			var body struct {
				VINs []string `json:"vins"`
			}
			if r.Method != http.MethodPost || json.NewDecoder(r.Body).Decode(&body) != nil || len(body.VINs) != 1 || body.VINs[0] != diagnosticsTestVIN {
				t.Error("invalid fixed status query")
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]any{"key_paired_vins": []string{diagnosticsTestVIN}, "vehicle_info": map[string]any{diagnosticsTestVIN: map[string]any{"firmware_version": "2026.8.3", "fleet_telemetry_version": "1.1.0", "safety_screen_streaming_toggle_enabled": false}}}})
		case "/api/1/vehicles/" + diagnosticsTestVIN + "/fleet_telemetry_config":
			if r.Method != http.MethodGet {
				t.Error("configuration write attempted")
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]any{"synced": true, "config": map[string]any{"hostname": "FLEET.EXAMPLE.COM.", "port": 4443, "ca": "\r\n" + strings.ReplaceAll(s.caPEM, "\n", "\r\n") + "\n" + s.caPEM, "fields": desiredTelemetryConfiguration().Fields}}})
		case "/api/1/vehicles/" + diagnosticsTestVIN + "/fleet_telemetry_errors":
			if r.Method != http.MethodGet {
				t.Error("errors write attempted")
			}
			_, _ = io.WriteString(w, `{"response":{"fleet_telemetry_errors":[{"error_name":"cloud_manager_error","created_at":"2026-01-01T00:00:00Z","error":"private raw text"},{"error_name":"tls_error","created_at":"2026-01-02T00:00:00Z"}]}}`)
		default:
			t.Error("unexpected provider endpoint")
			w.WriteHeader(500)
		}
	})
	var vin string
	var access *diagnosticsTestAccessReader
	s, vin, access = diagnosticsFixture(t, handler)
	ref := telemetryRefWithVIN(s, diagnosticsTestUser, 1, diagnosticsTestVIN)
	s.memory.registerVehicle(ref)
	s.memory.setPairing(ref, telemetryPairing{Status: "available", ConfigSynced: boolPointer(true)})
	refresh := s.tokens.(*staticFleetAccessTokens)
	result := s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, vin, access)
	if result.Availability != "available" || len(paths) != 3 || len(refresh.calls) != 0 || access.calls != 1 {
		t.Fatalf("wrong request boundaries: availability=%s calls=%d refresh=%d access=%d", result.Availability, len(paths), len(refresh.calls), access.calls)
	}
	if result.Vehicle.Paired == nil || !*result.Vehicle.Paired || result.Vehicle.StreamingEnabled == nil || *result.Vehicle.StreamingEnabled || result.Vehicle.FirmwareVersion == nil || *result.Vehicle.FirmwareVersion != "2026.8.3" {
		t.Fatal("missing positive status evidence")
	}
	for name, match := range map[string]*bool{"hostname": result.Configuration.MatchesDesired.Hostname, "port": result.Configuration.MatchesDesired.Port, "fields": result.Configuration.MatchesDesired.Fields, "ca": result.Configuration.MatchesDesired.CA} {
		if match == nil || !*match {
			t.Errorf("%s comparison did not match", name)
		}
	}
	if result.RecentErrors.Counts["connection"] != 1 || result.RecentErrors.Counts["tls"] != 1 || result.RecentErrors.LastAt == nil || *result.RecentErrors.LastAt != "2026-01-02T00:00:00Z" {
		t.Fatal("wrong sanitized errors")
	}
	if result.BuildSHA != buildSHA {
		t.Fatal("wrong build provenance")
	}
	if _, err := time.Parse(time.RFC3339, result.CheckedAt); err != nil {
		t.Fatal("invalid checked_at")
	}
	encoded := diagnosticsResultJSON(t, result)
	for _, private := range []string{diagnosticsTestUser, diagnosticsTestVIN, diagnosticsTestAccess, "private raw text", s.fleetAPIBase, vin, access.ciphertext} {
		if strings.Contains(encoded, private) {
			t.Fatal("response exposed private value")
		}
	}
}

func TestFleetDiagnosticsExpiredMissingAndRejectedAccessNeverRefresh(t *testing.T) {
	for _, tc := range []struct {
		name   string
		expiry time.Time
		err    error
		status int
		want   string
		calls  int
	}{
		{"expired", time.Now().Add(-time.Minute), nil, 200, "access_expired", 0},
		{"missing", time.Now().Add(time.Hour), pgx.ErrNoRows, 200, "reauthorization_required", 0},
		{"store error", time.Now().Add(time.Hour), errors.New("private store error"), 200, "token_unavailable", 0},
		{"unauthorized", time.Now().Add(time.Hour), nil, 401, "reauthorization_required", 3},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			s, vin, access := diagnosticsFixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, `{"error":"`+diagnosticsTestAccess+`"}`)
			}))
			access.expiry, access.err = tc.expiry, tc.err
			result := s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, vin, access)
			if result.Availability != "unavailable" || calls != tc.calls || len(s.tokens.(*staticFleetAccessTokens).calls) != 0 {
				t.Fatalf("refresh/request boundary violated: calls=%d", calls)
			}
			for _, request := range result.Requests {
				if request.ErrorClass == nil || *request.ErrorClass != tc.want {
					t.Fatal("unexpected failure classification")
				}
			}
			if strings.Contains(diagnosticsResultJSON(t, result), diagnosticsTestAccess) {
				t.Fatal("credential exposed")
			}
		})
	}
}

func TestFleetDiagnosticsRejectsRedirectAndBoundsBodies(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
		want   string
	}{
		{"redirect", 302, `{"secret":"raw"}`, "redirect_rejected"},
		{"too large", 200, strings.Repeat("x", diagnosticsMaxBody+1), "response_too_large"},
		{"invalid JSON", 200, `{invalid}`, "response_invalid"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			followed := 0
			target := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { followed++ }))
			defer target.Close()
			s, vin, access := diagnosticsFixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Location", target.URL)
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, tc.body)
			}))
			s.httpClient.CheckRedirect = func(*http.Request, []*http.Request) error { return nil }
			result := s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, vin, access)
			if followed != 0 {
				t.Fatal("credential redirect followed")
			}
			for _, request := range result.Requests {
				if request.ErrorClass == nil || *request.ErrorClass != tc.want {
					t.Fatalf("wanted %s", tc.want)
				}
			}
		})
	}
}

func TestFleetDiagnosticsPartialUnknownAndSensitiveProjection(t *testing.T) {
	s, vin, access := diagnosticsFixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "fleet_status"):
			_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]any{"key_paired_vins": []string{}, "vehicle_info": map[string]any{diagnosticsTestVIN: map[string]any{"firmware_version": diagnosticsTestVIN, "fleet_telemetry_version": "https://raw.example/" + diagnosticsTestAccess}}}})
		case strings.HasSuffix(r.URL.Path, "fleet_telemetry_config"):
			_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]any{"config": map[string]any{"hostname": diagnosticsTestAccess + ".example.com", "port": 0, "ca": "https://raw.example/" + diagnosticsTestVIN, "fields": map[string]any{diagnosticsTestAccess: map[string]any{"interval_seconds": 5}}}}})
		default:
			w.WriteHeader(503)
			_, _ = io.WriteString(w, `{"error":"`+diagnosticsTestAccess+`"}`)
		}
	}))
	var captured bytes.Buffer
	old := log.Writer()
	log.SetOutput(&captured)
	defer log.SetOutput(old)
	result := s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, vin, access)
	if result.Availability != "partial" || result.Vehicle.Paired != nil || result.Vehicle.FirmwareVersion != nil || result.Vehicle.FleetTelemetryVersion != nil || result.Vehicle.StreamingEnabled != nil || result.Configuration.Hostname != nil || result.Configuration.Port != nil || result.Configuration.CASHA256 != nil || result.Configuration.Synced != nil || result.RecentErrors.Counts != nil {
		t.Fatal("unknown observations were invented")
	}
	encoded := diagnosticsResultJSON(t, result) + captured.String()
	for _, private := range []string{diagnosticsTestUser, diagnosticsTestVIN, diagnosticsTestAccess, "https://raw.example/"} {
		if strings.Contains(encoded, private) {
			t.Fatal("sensitive response or log value")
		}
	}
}

func TestFleetDiagnosticsEmptyErrorsAndUnknownNames(t *testing.T) {
	var d fleetDiagnosticsResponse
	if !d.readDiagnosticErrors([]byte(`{"response":{"fleet_telemetry_errors":[]}}`)) || d.RecentErrors.Counts == nil || len(d.RecentErrors.Counts) != 0 || d.RecentErrors.LastAt != nil {
		t.Fatal("known empty errors were lost")
	}
	if !d.readDiagnosticErrors([]byte(`{"response":{"fleet_telemetry_errors":[{"error_name":"`+diagnosticsTestAccess+`","created_at":"private raw text"}]}}`)) || d.RecentErrors.Counts["unknown"] != 1 || len(d.RecentErrors.Counts) != 1 || d.RecentErrors.LastAt != nil {
		t.Fatal("unknown error name was not sanitized")
	}
	for _, payload := range []string{`{}`, `{"response":null}`, `{"response":{}}`, `{"response":{"fleet_telemetry_errors":null}}`} {
		if (&fleetDiagnosticsResponse{}).readDiagnosticErrors([]byte(payload)) {
			t.Error("unknown errors interpreted as empty")
		}
	}
}

// A synthetic PostgreSQL wire endpoint exercises real pgx SELECTs and App
// authentication without requiring a database or touching any real credentials.
func diagnosticsTestStore(t *testing.T, vin, access string, authError bool) (*store, func() []string) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	queries := []string{}
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				b := pgproto3.NewBackend(conn, conn)
				if _, err := b.ReceiveStartupMessage(); err != nil {
					return
				}
				b.Send(&pgproto3.AuthenticationOk{})
				b.Send(&pgproto3.ParameterStatus{Name: "server_version", Value: "16.0"})
				b.Send(&pgproto3.ParameterStatus{Name: "client_encoding", Value: "UTF8"})
				b.Send(&pgproto3.ParameterStatus{Name: "standard_conforming_strings", Value: "on"})
				b.Send(&pgproto3.BackendKeyData{ProcessID: 1, SecretKey: 1})
				b.Send(&pgproto3.ReadyForQuery{TxStatus: 'I'})
				if b.Flush() != nil {
					return
				}
				for {
					message, err := b.Receive()
					if err != nil {
						return
					}
					query, ok := message.(*pgproto3.Query)
					if !ok {
						return
					}
					mu.Lock()
					queries = append(queries, query.String)
					mu.Unlock()
					var fields []pgproto3.FieldDescription
					var values [][]byte
					q := query.String
					switch {
					case strings.Contains(q, "SELECT user_id FROM jourvolt_sessions"):
						if authError {
							b.Send(&pgproto3.ErrorResponse{Severity: "ERROR", Code: "XX000", Message: diagnosticsTestAccess})
							break
						}
						fields = []pgproto3.FieldDescription{{Name: []byte("user_id"), DataTypeOID: 25, DataTypeSize: -1}}
						if strings.Contains(q, hashToken("app-session")) {
							values = [][]byte{[]byte(diagnosticsTestUser)}
						}
					case strings.Contains(q, "SELECT vin_ciphertext FROM jourvolt_vehicles WHERE id="):
						fields = []pgproto3.FieldDescription{{Name: []byte("vin_ciphertext"), DataTypeOID: 25, DataTypeSize: -1}}
						if regexp.MustCompile(`id=\s*'1'\s`).MatchString(q) && strings.Contains(q, diagnosticsTestUser) {
							values = [][]byte{[]byte(vin)}
						}
					case strings.Contains(q, "SELECT access_ciphertext, access_expires_at"):
						fields = []pgproto3.FieldDescription{{Name: []byte("access_ciphertext"), DataTypeOID: 25, DataTypeSize: -1}, {Name: []byte("access_expires_at"), DataTypeOID: 1184, DataTypeSize: 8}}
						values = [][]byte{[]byte(access), []byte(time.Now().Add(time.Hour).Format("2006-01-02 15:04:05.999999Z07:00"))}
					default:
						b.Send(&pgproto3.ErrorResponse{Severity: "ERROR", Code: "XX000", Message: "unexpected SQL"})
					}
					if fields != nil {
						b.Send(&pgproto3.RowDescription{Fields: fields})
						if values != nil {
							b.Send(&pgproto3.DataRow{Values: values})
						}
						b.Send(&pgproto3.CommandComplete{CommandTag: []byte("SELECT 1")})
					}
					b.Send(&pgproto3.ReadyForQuery{TxStatus: 'I'})
					if b.Flush() != nil {
						return
					}
				}
			}()
		}
	}()
	config, err := pgxpool.ParseConfig(fmt.Sprintf("postgres://test:test@%s/test?sslmode=disable", listener.Addr()))
	if err != nil {
		t.Fatal(err)
	}
	config.ConnConfig.DefaultQueryExecMode = pgx.QueryExecModeSimpleProtocol
	pool, err := pgxpool.NewWithConfig(context.Background(), config)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pool.Close(); _ = listener.Close() })
	return &store{pool: pool}, func() []string { mu.Lock(); defer mu.Unlock(); return append([]string(nil), queries...) }
}

func TestFleetDiagnosticsAuthenticatedOwnershipAndReadOnlySQL(t *testing.T) {
	s, vin, access := diagnosticsFixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, `{"response":{}}`) }))
	database, queries := diagnosticsTestStore(t, vin, access.ciphertext, false)
	p := &diagnosticsNoDiscovery{}
	a := &app{store: database, telemetry: s, provider: p}
	for _, tc := range []struct {
		car    string
		status int
	}{{"2", 404}, {"1", 200}} {
		path := "/api/v1/cars/" + tc.car + "/telemetry/diagnostics"
		r := httptest.NewRequest(http.MethodGet, path+"?url=https://ignored.example", nil)
		r.Header.Set("Authorization", "Bearer app-session")
		w := httptest.NewRecorder()
		a.ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Fatalf("car=%s status=%d want=%d", tc.car, w.Code, tc.status)
		}
		if tc.status == 200 && w.Header().Get("Cache-Control") != "no-store" {
			t.Error("diagnostics must not be cached")
		}
	}
	if p.calls != 0 {
		t.Fatal("discovery or vehicle operation invoked")
	}
	if len(queries()) != 5 {
		t.Fatalf("SQL count=%d want5", len(queries()))
	}
	for _, q := range queries() {
		upper := strings.ToUpper(strings.TrimSpace(q))
		if !strings.HasPrefix(upper, "SELECT ") || strings.Contains(upper, "FOR UPDATE") || strings.Contains(upper, "REFRESH_CIPHERTEXT") || strings.Contains(upper, "CONFIG_SYNCED") {
			t.Fatal("non-read-only diagnostic SQL")
		}
	}
}

func TestFleetDiagnosticsRejectsInvalidAppSession(t *testing.T) {
	database, queries := diagnosticsTestStore(t, "", "", false)
	r := httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/telemetry/diagnostics", nil)
	r.Header.Set("Authorization", "Bearer invalid-session")
	w := httptest.NewRecorder()
	(&app{store: database}).ServeHTTP(w, r)
	if w.Code != 401 || len(queries()) != 1 {
		t.Fatal("invalid session reached owner/provider lookup")
	}
}

func TestFleetDiagnosticsRejectsNonCanonicalAndWriteRoutes(t *testing.T) {
	p := &diagnosticsNoDiscovery{}
	a := &app{provider: p}
	for _, tc := range []struct{ method, path string }{
		{http.MethodPost, "/api/v1/cars/1/telemetry/diagnostics"},
		{http.MethodDelete, "/api/v1/cars/1/telemetry/diagnostics"},
		{http.MethodGet, "/api/v1/cars/01/telemetry/diagnostics"},
		{http.MethodGet, "/api/v1/cars/0/telemetry/diagnostics"},
		{http.MethodGet, "/api/v1/cars/1/telemetry/diagnostics/extra"},
	} {
		w := httptest.NewRecorder()
		a.carResource(w, httptest.NewRequest(tc.method, tc.path, nil), diagnosticsTestUser, tc.path)
		if w.Code != 404 {
			t.Errorf("noncanonical method/path status=%d", w.Code)
		}
	}
	if p.calls != 0 {
		t.Fatal("noncanonical diagnostics triggered provider fallback")
	}
}

func TestFleetDiagnosticsCAFingerprintNormalizesBundleSet(t *testing.T) {
	first, ca1 := telemetryTestTLSServer(t, http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer first.Close()
	second, ca2 := telemetryTestTLSServer(t, http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer second.Close()
	a := diagnosticCAFingerprint(ca1 + ca2)
	b := diagnosticCAFingerprint(strings.ReplaceAll(ca2+ca1+ca1, "\n", "\r\n"))
	c := diagnosticCAFingerprint(ca1)
	if a == nil || b == nil || c == nil || *a != *b || *a == *c {
		t.Fatal("CA set comparison failed")
	}
	for _, invalid := range []string{"", ca1 + "raw trailing text", "raw prefix" + ca1, "-----BEGIN CERTIFICATE-----\nnot-der\n-----END CERTIFICATE-----"} {
		if diagnosticCAFingerprint(invalid) != nil {
			t.Error("malformed certificate accepted")
		}
	}
}

type diagnosticTestTransport func(*http.Request) (*http.Response, error)

func (f diagnosticTestTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestFleetDiagnosticsTimeoutAndOriginBoundary(t *testing.T) {
	s, vin, access := diagnosticsFixture(t, http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Error("unexpected real HTTP") }))
	calls := 0
	s.httpClient = &http.Client{Timeout: 10 * time.Millisecond, Transport: diagnosticTestTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		<-r.Context().Done()
		return nil, r.Context().Err()
	})}
	started := time.Now()
	result := s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, vin, access)
	if calls != 3 || time.Since(started) > time.Second {
		t.Fatal("unbounded diagnostics request timeout")
	}
	for _, request := range result.Requests {
		if request.ErrorClass == nil || *request.ErrorClass != "timeout" {
			t.Fatal("timeout not classified")
		}
	}
	for _, origin := range []string{"http://unsafe.example", "https://user:pass@unsafe.example", "https://unsafe.example/path", "https://unsafe.example?url=other", "https://unsafe.example#other"} {
		before := access.calls
		s.fleetAPIBase = origin
		result = s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, vin, access)
		if access.calls != before || calls != 3 || result.Availability != "unavailable" {
			t.Error("invalid provider origin accessed credentials or network")
		}
	}
}

func TestFleetDiagnosticsPrivateDesiredHostnameIsHiddenBeforeTokenFailure(t *testing.T) {
	s, vin, access := diagnosticsFixture(t, http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Error("unexpected expired-token HTTP") }))
	access.expiry = time.Now().Add(-time.Minute)
	for _, private := range []string{diagnosticsTestVIN, diagnosticsTestUser} {
		s.config.PublicHost = private + ".example.com"
		result := s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, vin, access)
		if result.Configuration.Desired.Hostname != nil {
			t.Error("private desired hostname leaked on early return")
		}
	}
}

func TestFleetDiagnosticsSanitizesSessionStoreError(t *testing.T) {
	database, _ := diagnosticsTestStore(t, "", "", true)
	r := httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/telemetry/diagnostics", nil)
	r.Header.Set("Authorization", "Bearer app-session")
	w := httptest.NewRecorder()
	(&app{store: database}).ServeHTTP(w, r)
	if w.Code != 401 || strings.Contains(w.Body.String(), diagnosticsTestAccess) {
		t.Fatal("session SQL error leaked private text")
	}
}

func TestFleetDiagnosticsDesiredCAReadsConfiguredMount(t *testing.T) {
	s, vin, access := diagnosticsFixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, `{"response":{}}`) }))
	want := diagnosticCAFingerprint(s.caPEM)
	path := t.TempDir() + "/ca.pem"
	if err := os.WriteFile(path, []byte(s.caPEM), 0600); err != nil {
		t.Fatal(err)
	}
	s.caPEM = ""
	s.config.CACertPath = path
	result := s.readOnlyDiagnostics(context.Background(), diagnosticsTestUser, vin, access)
	if result.Configuration.Desired.CASHA256 == nil || want == nil || *result.Configuration.Desired.CASHA256 != *want {
		t.Fatal("configured CA mount was not read")
	}
}
