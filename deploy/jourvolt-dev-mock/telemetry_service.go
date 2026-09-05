package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jackc/pgx/v5"
)

var (
	errTelemetryPermission = errors.New("telemetry_permission_required")
	errTelemetryPairing    = errors.New("telemetry_pairing_required")
	errTelemetryBilling    = errors.New("telemetry_billing_blocked")
	errTelemetryCommand    = errors.New("telemetry_command_failed")
)

type telemetryService struct {
	store                    *store
	config                   *telemetryConfig
	memory                   *telemetryMemoryStore
	vinHashKey               []byte
	cipher                   *tokenCipher
	tokens                   fleetAccessTokens
	caPEM                    string
	commandProxyURL          string
	httpClient               *http.Client
	started                  atomic.Bool
	mqttConnected            atomic.Bool
	mqttSubscribed           atomic.Bool
	mqttPersistence          atomic.Bool
	mqttHealthy              atomic.Bool
	persistenceReady         func(context.Context) bool
	pairingConfigTruthWriter func(context.Context, string, int, string, bool) error
	mqttInvalid              atomic.Uint64
	receiveSequence          atomic.Uint64
	finalizerMu              sync.Mutex
	finalizerCancel          context.CancelFunc
	finalizerDone            chan struct{}
}

func newTelemetryService(config *telemetryConfig, database *store) *telemetryService {
	if config == nil {
		return nil
	}
	return &telemetryService{
		store: database, config: config, vinHashKey: telemetryVINHashKeyFromConfig(config),
		commandProxyURL: config.CommandProxyURL, httpClient: &http.Client{Timeout: config.CommandTimeout},
	}
}

func (s *telemetryService) registerVehicle(ctx context.Context, ref telemetryVehicleRef) error {
	if s == nil {
		return nil
	}
	if s.memory != nil {
		s.memory.registerVehicle(ref)
	}
	if s.store == nil || s.store.pool == nil {
		return nil
	}
	_, err := s.store.pool.Exec(ctx, `
INSERT INTO jourvolt_telemetry_vehicle_keys(user_id, vehicle_id, vin_hash)
VALUES ($1, $2, $3)
ON CONFLICT (user_id, vehicle_id) DO UPDATE SET vin_hash=EXCLUDED.vin_hash`, ref.UserID, ref.VehicleID, ref.VINHash)
	return err
}

func (s *telemetryService) ingest(ctx context.Context, record telemetryRecord) (int, error) {
	if s == nil {
		return 0, nil
	}
	if s.memory != nil {
		return s.memory.ingest(record, s.stopDebounceOrDefault())
	}
	return s.ingestPostgres(ctx, record)
}

func (s *telemetryService) stopDebounceOrDefault() time.Duration {
	if s == nil || s.config == nil || s.config.StopDebounce <= 0 {
		return defaultDriveStopDebounce
	}
	return s.config.StopDebounce
}

func (s *telemetryService) finalizeDue(ctx context.Context, now time.Time) (int, error) {
	if s == nil {
		return 0, nil
	}
	if s.memory != nil {
		return s.memory.finalizeDue(now), nil
	}
	return s.finalizeDuePostgres(ctx, now)
}

func (s *telemetryService) startFinalizer(ctx context.Context) {
	if s == nil || s.config == nil {
		return
	}
	s.finalizerMu.Lock()
	defer s.finalizerMu.Unlock()
	if s.finalizerCancel != nil {
		return
	}
	workerCtx, cancel := context.WithCancel(ctx)
	s.finalizerCancel = cancel
	s.finalizerDone = make(chan struct{})
	interval := s.config.StopDebounce / 2
	if interval < time.Second {
		interval = time.Second
	}
	go func() {
		defer close(s.finalizerDone)
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			if _, err := s.finalizeDue(workerCtx, time.Now().UTC()); err != nil {
				s.mqttHealthy.Store(false)
			}
			select {
			case <-workerCtx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
}

func (s *telemetryService) stopFinalizer() {
	if s == nil {
		return
	}
	s.finalizerMu.Lock()
	cancel, done := s.finalizerCancel, s.finalizerDone
	s.finalizerCancel, s.finalizerDone = nil, nil
	s.finalizerMu.Unlock()
	if cancel != nil {
		cancel()
	}
	if done != nil {
		<-done
	}
}

func (s *telemetryService) latest(ctx context.Context, userID string, vehicleID int) (telemetrySnapshot, bool, error) {
	if s == nil {
		return telemetrySnapshot{}, false, nil
	}
	if s.memory != nil {
		snapshot, ok := s.memory.latestSnapshot(userID, vehicleID)
		return snapshot, ok, nil
	}
	return s.latestPostgres(ctx, userID, vehicleID)
}

func (s *telemetryService) mergeOrFallback(ctx context.Context, ref telemetryVehicleRef, base vehicleStatus, now time.Time) (vehicleStatus, bool) {
	snapshot, ok, err := s.latest(ctx, ref.UserID, ref.VehicleID)
	if err != nil || !ok {
		return base, true
	}
	merged, fresh := mergeFreshTelemetryStatus(base, snapshot, now), false
	for field, observedAt := range snapshot.FieldObservedAt {
		if !observedAt.Before(now.Add(-telemetryStaleAfter)) && !observedAt.After(now.Add(time.Minute)) {
			if _, exists := snapshot.Fields[field]; exists {
				fresh = true
				break
			}
		}
	}
	return merged, !fresh
}

func mergeFreshTelemetryStatus(base vehicleStatus, snapshot telemetrySnapshot, now time.Time) vehicleStatus {
	fresh := telemetrySnapshot{Fields: make(map[string]any), FieldObservedAt: make(map[string]time.Time), Source: snapshot.Source}
	for field, value := range snapshot.Fields {
		observedAt := snapshot.ObservedAt
		if valueObservedAt, ok := snapshot.FieldObservedAt[field]; ok {
			observedAt = valueObservedAt
		}
		if observedAt.IsZero() || observedAt.Before(now.Add(-telemetryStaleAfter)) || observedAt.After(now.Add(time.Minute)) {
			continue
		}
		fresh.Fields[field] = value
		fresh.FieldObservedAt[field] = observedAt
		if observedAt.After(fresh.ObservedAt) {
			fresh.ObservedAt = observedAt
		}
	}
	if len(fresh.Fields) == 0 {
		return base
	}
	return mergeTelemetryStatus(base, fresh)
}

func mergeTelemetryStatus(base vehicleStatus, snapshot telemetrySnapshot) vehicleStatus {
	merged := base
	if !snapshot.ObservedAt.IsZero() {
		merged.ObservedAt = snapshot.ObservedAt
	}
	if snapshot.Source != "" {
		merged.Source = snapshot.Source
	}
	for field, value := range snapshot.Fields {
		setTelemetryStatusField(&merged, field, value)
	}
	return merged
}

func setTelemetryStatusField(status *vehicleStatus, field string, value any) {
	switch field {
	case "Locked":
		status.Locked = boolPointerFromAny(value)
	case "DetailedChargeState":
		if text, ok := value.(string); ok {
			status.ChargingState = &text
		}
	case "Gear":
		if text, ok := value.(string); ok {
			status.ShiftState = &text
		}
	case "VehicleSpeed":
		status.Speed = intPointerFromNumber(value)
	case "GpsHeading":
		status.Heading = intPointerFromNumber(value)
	case "Soc":
		status.BatteryLevel = intPointerFromNumber(value)
	case "EstBatteryRange":
		status.EstimatedBatteryRange = floatPointerFromNumber(value)
	case "Odometer":
		status.Odometer = floatPointerFromNumber(value)
	case "InsideTemp":
		status.InsideTemp = floatPointerFromNumber(value)
	case "OutsideTemp":
		status.OutsideTemp = floatPointerFromNumber(value)
	case "Latitude":
		status.Latitude = floatPointerFromNumber(value)
	case "Longitude":
		status.Longitude = floatPointerFromNumber(value)
	case "Location":
		if location, ok := value.(map[string]any); ok {
			if latitude, valid := numberFromJSONValue(location["latitude"]); valid {
				status.Latitude = &latitude
			}
			if longitude, valid := numberFromJSONValue(location["longitude"]); valid {
				status.Longitude = &longitude
			}
		}
	case "TpmsPressureFl":
		status.TPMSPressureFL = floatPointerFromNumber(value)
	case "TpmsPressureFr":
		status.TPMSPressureFR = floatPointerFromNumber(value)
	case "TpmsPressureRl":
		status.TPMSPressureRL = floatPointerFromNumber(value)
	case "TpmsPressureRr":
		status.TPMSPressureRR = floatPointerFromNumber(value)
	}
}

func boolPointerFromAny(value any) *bool {
	if parsed, ok := value.(bool); ok {
		return &parsed
	}
	return nil
}

func floatPointerFromNumber(value any) *float64 {
	parsed, ok := numberFromJSONValue(value)
	if !ok {
		return nil
	}
	return &parsed
}

func intPointerFromNumber(value any) *int {
	parsed, ok := numberFromJSONValue(value)
	if !ok {
		return nil
	}
	rounded := int(parsed)
	return &rounded
}

type telemetryPairing struct {
	Status        string
	UpdatedAt     time.Time
	ErrorClass    string
	VirtualKeyURL string
	ConfigSynced  *bool
}

type telemetryPairingResponse struct {
	Status        string `json:"status"`
	VirtualKeyURL string `json:"virtual_key_url"`
	UpdatedAt     string `json:"updated_at,omitempty"`
	ConfigSynced  *bool  `json:"config_synced"`
}

func (s *telemetryService) pairing(ctx context.Context, userID string, vehicleID int) (telemetryPairingResponse, error) {
	if s == nil || s.config == nil {
		return telemetryPairingResponse{Status: "pairing_required"}, nil
	}
	status := telemetryPairing{Status: "pairing_required"}
	if s.memory != nil {
		if value, ok := s.memory.pairing(userID, vehicleID); ok {
			status = value
		}
	} else if s.store != nil && s.store.pool != nil {
		var updatedAt time.Time
		var errorClass string
		var configSynced *bool
		err := s.store.pool.QueryRow(ctx, `SELECT status, updated_at, error_class, config_synced FROM jourvolt_telemetry_pairing WHERE user_id=$1 AND vehicle_id=$2`, userID, vehicleID).Scan(&status.Status, &updatedAt, &errorClass, &configSynced)
		if err == nil {
			status.UpdatedAt, status.ErrorClass, status.ConfigSynced = updatedAt, errorClass, configSynced
		} else if !isVehicleLookupMiss(err) {
			return telemetryPairingResponse{}, err
		}
	}
	if status.Status == "" {
		status.Status = "pairing_required"
	}
	response := telemetryPairingResponse{
		Status:        status.Status,
		VirtualKeyURL: "https://tesla.com/_ak/" + s.config.PartnerDomain,
		ConfigSynced:  status.ConfigSynced,
	}
	if !status.UpdatedAt.IsZero() {
		response.UpdatedAt = status.UpdatedAt.UTC().Format(time.RFC3339)
	}
	return response, nil
}

type telemetryDesiredConfiguration struct {
	Fields                      map[string]telemetryFieldConfiguration `json:"fields"`
	VehicleSpeedIntervalSeconds int                                    `json:"vehicle_speed_interval_seconds"`
	LocationIntervalSeconds     int                                    `json:"location_interval_seconds"`
	SocIntervalSeconds          int                                    `json:"soc_interval_seconds"`
	RangeIntervalSeconds        int                                    `json:"range_interval_seconds"`
	OdometerIntervalSeconds     int                                    `json:"odometer_interval_seconds"`
	TemperatureIntervalSeconds  int                                    `json:"temperature_interval_seconds"`
	TPMSIntervalSeconds         int                                    `json:"tpms_interval_seconds"`
}

type telemetryFieldConfiguration struct {
	IntervalSeconds int      `json:"interval_seconds"`
	MinimumDelta    *float64 `json:"minimum_delta,omitempty"`
}

func desiredTelemetryConfiguration() telemetryDesiredConfiguration {
	fields := make(map[string]telemetryFieldConfiguration, len(telemetryFieldSpecs))
	for name := range telemetryFieldSpecs {
		spec := telemetryFieldSpecs[name]
		field := telemetryFieldConfiguration{IntervalSeconds: int(spec.Interval.Seconds())}
		if spec.Kind == "number" || spec.Name == "Location" {
			delta := 0.1
			if spec.Name == "Location" {
				delta = 10
			}
			field.MinimumDelta = &delta
		}
		fields[name] = field
	}
	return telemetryDesiredConfiguration{
		Fields: fields, VehicleSpeedIntervalSeconds: 10, LocationIntervalSeconds: 10,
		SocIntervalSeconds: 60, RangeIntervalSeconds: 60, OdometerIntervalSeconds: 60,
		TemperatureIntervalSeconds: 60, TPMSIntervalSeconds: 60,
	}
}

type officialFleetTelemetryConfiguration struct {
	Hostname   string                                 `json:"hostname"`
	Port       int                                    `json:"port"`
	CA         string                                 `json:"ca"`
	Fields     map[string]telemetryFieldConfiguration `json:"fields"`
	AlertTypes []string                               `json:"alert_types,omitempty"`
}

type officialFleetTelemetryConfigureRequest struct {
	VINs   []string                            `json:"vins"`
	Config officialFleetTelemetryConfiguration `json:"config"`
}

type officialFleetTelemetryResponse struct {
	Response struct {
		Synced          *bool `json:"synced"`
		UpdatedVehicles int   `json:"updated_vehicles"`
		Config          any   `json:"config"`
		SkippedVehicles struct {
			MissingKey          []string `json:"missing_key"`
			UnsupportedHardware []string `json:"unsupported_hardware"`
			UnsupportedFirmware []string `json:"unsupported_firmware"`
			MaxConfigs          []string `json:"max_configs"`
		} `json:"skipped_vehicles"`
		LimitReached bool `json:"limit_reached"`
	} `json:"response"`
}

func (s *telemetryService) configure(ctx context.Context, userID string, vehicleID int) error {
	if s == nil || s.config == nil || s.commandProxyURL == "" {
		return errNotConfigured
	}
	if ctx == nil {
		ctx = context.Background()
	}
	vin, err := s.vinForVehicle(ctx, userID, vehicleID)
	if err != nil {
		return errTelemetryCommand
	}
	ca, err := s.telemetryCA()
	if err != nil {
		return errTelemetryCommand
	}
	desired := desiredTelemetryConfiguration()
	official := officialFleetTelemetryConfiguration{
		Hostname: s.config.PublicHost, Port: s.config.PublicPort, CA: string(ca),
		Fields: desired.Fields, AlertTypes: []string{"service"},
	}
	body, err := json.Marshal(officialFleetTelemetryConfigureRequest{VINs: []string{vin}, Config: official})
	if err != nil {
		return errTelemetryCommand
	}
	requestURL := strings.TrimRight(s.commandProxyURL, "/") + "/api/1/vehicles/fleet_telemetry_config"
	response, err := s.commandProxyRequest(ctx, userID, http.MethodPost, requestURL, body)
	if err != nil {
		if errors.Is(err, errTeslaReauthorization) {
			return errTelemetryPermission
		}
		return errTelemetryCommand
	}
	defer response.Body.Close()
	responseBody, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
	classification := telemetryCommandErrorClass(response.StatusCode, responseBody)
	if classification != "" {
		s.setPairingStatus(ctx, userID, vehicleID, classification)
		switch classification {
		case "permission_required":
			return errTelemetryPermission
		case "pairing_required":
			return errTelemetryPairing
		case "billing_blocked":
			return errTelemetryBilling
		default:
			return errTelemetryCommand
		}
	}
	var configureResponse officialFleetTelemetryResponse
	if json.Unmarshal(responseBody, &configureResponse) == nil {
		skipped := configureResponse.Response.SkippedVehicles
		if len(skipped.MissingKey) > 0 || len(skipped.UnsupportedHardware) > 0 || len(skipped.UnsupportedFirmware) > 0 || len(skipped.MaxConfigs) > 0 {
			status := "pairing_required"
			if len(skipped.MissingKey) == 0 {
				status = "telemetry_error"
			}
			if err := s.setPairingConfigTruth(ctx, userID, vehicleID, status, false); err != nil {
				return errTelemetryCommand
			}
			if status == "pairing_required" {
				return errTelemetryPairing
			}
			return errTelemetryCommand
		}
	}
	s.setPairingStatus(ctx, userID, vehicleID, "waiting_vehicle")
	for attempt, delay := range []time.Duration{0, 100 * time.Millisecond, 250 * time.Millisecond, 500 * time.Millisecond} {
		if attempt > 0 {
			timer := time.NewTimer(delay)
			select {
			case <-ctx.Done():
				timer.Stop()
				return errTelemetryCommand
			case <-timer.C:
			}
		}
		configResponse, err := s.getFleetTelemetryConfig(ctx, userID, vin)
		if err != nil {
			continue
		}
		if configResponse.Response.Synced == nil {
			continue
		}
		if *configResponse.Response.Synced {
			if err := s.setPairingConfigTruth(ctx, userID, vehicleID, "available", true); err != nil {
				return errTelemetryCommand
			}
			return nil
		}
		if err := s.setPairingConfigTruth(ctx, userID, vehicleID, "waiting_vehicle", false); err != nil {
			return errTelemetryCommand
		}
	}
	// The vehicle may be asleep; expose a pending state and let the next GET
	// refresh observe synced=true without blocking the API request indefinitely.
	return nil
}

func (s *telemetryService) commandProxyRequest(ctx context.Context, userID, method, requestURL string, body []byte) (*http.Response, error) {
	if s == nil || s.tokens == nil {
		return nil, errTeslaReauthorization
	}
	accessToken, err := s.tokens.accessToken(ctx, userID, "")
	if err != nil {
		return nil, err
	}
	response, err := s.commandProxyRequestWithToken(ctx, method, requestURL, body, accessToken)
	if err != nil || response.StatusCode != http.StatusUnauthorized {
		return response, err
	}
	response.Body.Close()
	accessToken, err = s.tokens.accessToken(ctx, userID, accessToken)
	if err != nil {
		return nil, err
	}
	return s.commandProxyRequestWithToken(ctx, method, requestURL, body, accessToken)
}

func (s *telemetryService) commandProxyRequestWithToken(ctx context.Context, method, requestURL string, body []byte, accessToken string) (*http.Response, error) {
	timeout := 5 * time.Second
	if s != nil && s.config != nil && s.config.CommandTimeout > 0 {
		timeout = s.config.CommandTimeout
	}
	requestContext, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(requestContext, method, requestURL, strings.NewReader(string(body)))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	client, err := s.commandProxyHTTPClient(requestURL, timeout)
	if err != nil {
		return nil, err
	}
	return client.Do(req)
}

func (s *telemetryService) commandProxyHTTPClient(requestURL string, timeout time.Duration) (*http.Client, error) {
	client := s.httpClient
	if client == nil {
		client = &http.Client{Timeout: timeout}
	}
	parsed, err := url.Parse(requestURL)
	if err != nil || parsed.Scheme != "https" {
		return client, err
	}
	ca, err := s.telemetryCA()
	if err != nil {
		return nil, err
	}
	roots, err := x509.SystemCertPool()
	if err != nil || roots == nil {
		roots = x509.NewCertPool()
	}
	if !roots.AppendCertsFromPEM(ca) {
		return nil, errors.New("telemetry CA is not a certificate bundle")
	}
	transport, ok := client.Transport.(*http.Transport)
	if !ok || transport == nil {
		transport = http.DefaultTransport.(*http.Transport)
	}
	transport = transport.Clone()
	tlsConfig := &tls.Config{MinVersion: tls.VersionTLS12}
	if transport.TLSClientConfig != nil {
		tlsConfig = transport.TLSClientConfig.Clone()
		tlsConfig.MinVersion = tls.VersionTLS12
	}
	tlsConfig.RootCAs = roots
	transport.TLSClientConfig = tlsConfig
	configuredClient := *client
	configuredClient.Transport = transport
	if configuredClient.Timeout <= 0 {
		configuredClient.Timeout = timeout
	}
	return &configuredClient, nil
}

func (s *telemetryService) telemetryCA() ([]byte, error) {
	if s.caPEM != "" {
		return []byte(s.caPEM), nil
	}
	if s.config == nil || s.config.CACertPath == "" {
		return nil, errors.New("telemetry CA path is not configured")
	}
	ca, err := os.ReadFile(s.config.CACertPath)
	if err != nil || len(ca) == 0 {
		return nil, errors.New("telemetry CA is unavailable")
	}
	return ca, nil
}

func (s *telemetryService) openSession(ctx context.Context, userID string, vehicleID int, kind string) (telemetrySession, bool, error) {
	if s == nil {
		return telemetrySession{}, false, nil
	}
	if s.memory != nil {
		session, ok := s.memory.openSession(userID, vehicleID, kind)
		return session, ok, nil
	}
	return s.openSessionPostgres(ctx, userID, vehicleID, kind)
}

func (s *telemetryService) vinForVehicle(ctx context.Context, userID string, vehicleID int) (string, error) {
	var encrypted string
	if s.memory != nil {
		s.memory.mu.Lock()
		defer s.memory.mu.Unlock()
		for _, refs := range s.memory.vehicles {
			for _, ref := range refs {
				if ref.UserID == userID && ref.VehicleID == vehicleID {
					encrypted = ref.VINCiphertext
					break
				}
			}
		}
	} else if s.store != nil && s.store.pool != nil {
		if err := s.store.pool.QueryRow(ctx, `SELECT vin_ciphertext FROM jourvolt_vehicles WHERE id=$1 AND user_id=$2`, vehicleID, userID).Scan(&encrypted); err != nil {
			return "", err
		}
	}
	if encrypted == "" || s.cipher == nil {
		return "", errors.New("vehicle VIN unavailable")
	}
	return s.cipher.decrypt(encrypted)
}

func (s *telemetryService) getFleetTelemetryConfig(ctx context.Context, userID, vin string) (officialFleetTelemetryResponse, error) {
	var result officialFleetTelemetryResponse
	requestURL := strings.TrimRight(s.commandProxyURL, "/") + "/api/1/vehicles/" + url.PathEscape(vin) + "/fleet_telemetry_config"
	response, err := s.commandProxyRequest(ctx, userID, http.MethodGet, requestURL, nil)
	if err != nil {
		return result, err
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return result, errors.New("telemetry config query failed")
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return result, err
	}
	return result, nil
}

func telemetryCommandErrorClass(status int, body []byte) string {
	if status == http.StatusUnauthorized || status == http.StatusForbidden {
		return "permission_required"
	}
	if status == http.StatusPaymentRequired || bodyHasExactCode(body, "billing_blocked", "payment_required", "billing_required") {
		return "billing_blocked"
	}
	if status == http.StatusNotFound || status == http.StatusConflict || bodyHasExactCode(body, "pairing_required", "vehicle_not_paired", "virtual_key_required") {
		return "pairing_required"
	}
	if status < 200 || status >= 300 {
		return "telemetry_error"
	}
	return ""
}

func bodyHasExactCode(body []byte, expected ...string) bool {
	var payload map[string]any
	if json.Unmarshal(body, &payload) != nil {
		return false
	}
	for _, key := range []string{"error", "code", "error_code", "class", "type"} {
		if value, ok := payload[key].(string); ok {
			for _, candidate := range expected {
				if strings.EqualFold(strings.TrimSpace(value), candidate) {
					return true
				}
			}
		}
	}
	return false
}

func (s *telemetryService) setPairingStatus(ctx context.Context, userID string, vehicleID int, status string) {
	if s.memory != nil {
		s.memory.mu.Lock()
		defer s.memory.mu.Unlock()
		key := telemetryKey{UserID: userID, VehicleID: vehicleID}
		pairing := s.memory.pairings[key]
		pairing.Status, pairing.UpdatedAt = status, time.Now().UTC()
		s.memory.pairings[key] = pairing
		return
	}
	if s.store == nil || s.store.pool == nil {
		return
	}
	_, _ = s.store.pool.Exec(ctx, `
INSERT INTO jourvolt_telemetry_pairing(user_id, vehicle_id, status, updated_at)
VALUES ($1, $2, $3, now())
ON CONFLICT (user_id, vehicle_id) DO UPDATE SET status=EXCLUDED.status, updated_at=EXCLUDED.updated_at`, userID, vehicleID, status)
}

func (s *telemetryService) setPairingConfigTruth(ctx context.Context, userID string, vehicleID int, status string, configSynced bool) error {
	if s.pairingConfigTruthWriter != nil {
		return s.pairingConfigTruthWriter(ctx, userID, vehicleID, status, configSynced)
	}
	if s.memory != nil {
		s.memory.setPairing(telemetryVehicleRef{UserID: userID, VehicleID: vehicleID}, telemetryPairing{Status: status, ConfigSynced: boolPointer(configSynced), UpdatedAt: time.Now().UTC()})
		return nil
	}
	if s.store == nil || s.store.pool == nil {
		return nil
	}
	_, err := s.store.pool.Exec(ctx, `
INSERT INTO jourvolt_telemetry_pairing(user_id, vehicle_id, status, config_synced, updated_at)
VALUES ($1, $2, $3, $4, now())
ON CONFLICT (user_id, vehicle_id) DO UPDATE SET status=EXCLUDED.status, config_synced=EXCLUDED.config_synced, updated_at=EXCLUDED.updated_at`, userID, vehicleID, status, configSynced)
	return err
}

func (s *telemetryService) providerVehicleID(ctx context.Context, userID string, vehicleID int) (string, error) {
	if s.memory != nil {
		s.memory.mu.Lock()
		defer s.memory.mu.Unlock()
		for _, refs := range s.memory.vehicles {
			for _, ref := range refs {
				if ref.UserID == userID && ref.VehicleID == vehicleID {
					return ref.ProviderVehicleID, nil
				}
			}
		}
		return "", errVehicleNotFound
	}
	if s.store == nil || s.store.pool == nil {
		return "", errVehicleNotFound
	}
	var providerID string
	err := s.store.pool.QueryRow(ctx, `SELECT provider_vehicle_id FROM jourvolt_vehicles WHERE id=$1 AND user_id=$2`, vehicleID, userID).Scan(&providerID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", errVehicleNotFound
	}
	return providerID, err
}

func telemetryReadinessItem(status string) dataReadinessItem {
	item := dataReadinessItem{Key: "telemetry", Status: status, Source: "telemetry_mqtt"}
	switch status {
	case "pairing_required":
		item.MessageKey, item.Action = "tesla_pairing_required", "pair_tesla"
	case "waiting_vehicle":
		item.MessageKey, item.Action = "telemetry_waiting_vehicle", "wake_vehicle"
	case "collecting":
		item.MessageKey, item.Action = "telemetry_collecting", "keep_vehicle_connected"
	case "available":
	case "billing_blocked":
		item.MessageKey, item.Action = "billing_blocked", "resolve_billing"
	default:
		item.Status, item.MessageKey, item.Action = "telemetry_error", "telemetry_error", "retry_later"
	}
	return item
}

func historySessionMap(session telemetrySession, kind string, index int) map[string]any {
	end := session.EndAt
	result := map[string]any{
		"start_date": session.StartAt.UTC().Format(time.RFC3339), "end_date": nil,
		"source": "telemetry_mqtt", "session_id": session.ID,
	}
	if end != nil {
		result["end_date"] = end.UTC().Format(time.RFC3339)
		result["duration_min"] = int(end.Sub(session.StartAt).Minutes())
	}
	if kind == "drive" {
		result["drive_id"] = session.PublicID
		result["start_address"], result["end_address"] = nil, nil
		result["duration_str"], result["speed_max"], result["speed_avg"] = nil, nil, nil
		result["power_max"], result["power_min"] = nil, nil
		result["battery_details"], result["range_ideal"], result["range_rated"] = nil, nil, nil
		result["outside_temp_avg"], result["inside_temp_avg"] = nil, nil
		result["energy_consumed_net"], result["consumption_net"] = nil, nil
		result["odometer_details"] = map[string]any{"odometer_start": session.OdometerStart, "odometer_end": session.OdometerEnd, "odometer_distance": odometerDistance(session.OdometerStart, session.OdometerEnd)}
		route := make([]map[string]any, 0, len(session.Route))
		for _, point := range session.Route {
			route = append(route, map[string]any{
				"date": point.ObservedAt.UTC().Format(time.RFC3339), "latitude": point.Latitude, "longitude": point.Longitude,
				"speed": point.Speed, "power": point.Power, "heading": point.Heading,
			})
		}
		result["drive_details"] = route
	} else {
		result["charge_id"] = session.PublicID
		result["address"], result["charge_energy_used"], result["cost"] = nil, nil, nil
		result["duration_str"], result["battery_details"], result["range_ideal"] = nil, nil, nil
		result["range_rated"], result["outside_temp_avg"], result["odometer"] = nil, nil, nil
		result["latitude"], result["longitude"] = nil, nil
		result["charge_energy_added"] = session.EnergyAdded
		chargeDetail := map[string]any{"date": session.StartAt.UTC().Format(time.RFC3339), "charge_energy_added": session.EnergyAdded}
		if session.EndAt != nil {
			chargeDetail["date"] = session.EndAt.UTC().Format(time.RFC3339)
		}
		result["charge_details"] = []map[string]any{chargeDetail}
	}
	result["sequence"] = index
	return result
}

func odometerDistance(start, end *float64) *float64 {
	if start == nil || end == nil {
		return nil
	}
	distance := *end - *start
	return &distance
}

func (s *telemetryService) history(userID string, vehicleID int, kind string) ([]map[string]any, map[string]any, error) {
	if s == nil {
		return nil, nil, nil
	}
	var sessions []telemetrySession
	var startedAt time.Time
	if s.memory != nil {
		sessions = s.memory.sessions(userID, vehicleID, kind)
		startedAt = s.memory.historyStartedAt(userID, vehicleID)
	} else {
		var err error
		sessions, startedAt, err = s.historyPostgres(context.Background(), userID, vehicleID, kind)
		if err != nil {
			return nil, nil, err
		}
	}
	items := make([]map[string]any, 0, len(sessions))
	for index, session := range sessions {
		items = append(items, historySessionMap(session, kind, index))
	}
	meta := map[string]any{"availability": "collecting", "source": "telemetry_mqtt"}
	if startedAt.IsZero() && len(items) > 0 {
		startedAt = sessions[0].StartAt
	}
	if len(items) > 0 {
		meta["availability"] = "available"
		meta["coverage_percent"] = 100.0
		meta["collection_started_at"] = startedAt.UTC().Format(time.RFC3339)
	}
	return items, meta, nil
}

func (s *telemetryService) hasHistory(ctx context.Context, userID string, vehicleID int) bool {
	items, _, err := s.history(userID, vehicleID, "drive")
	return err == nil && len(items) > 0
}
