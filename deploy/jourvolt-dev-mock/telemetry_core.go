package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	maxTelemetryPayloadBytes = 64 << 10
	telemetryStaleAfter      = 2 * time.Minute
	telemetryEventTTL        = 24 * time.Hour
	defaultDriveStopDebounce = 20 * time.Second
)

type telemetryFieldDefinition struct {
	Name     string
	Interval time.Duration
	Kind     string
}

var telemetryFieldSpecs = map[string]telemetryFieldDefinition{
	"VehicleSpeed":        {Name: "VehicleSpeed", Interval: 10 * time.Second, Kind: "number"},
	"Location":            {Name: "Location", Interval: 10 * time.Second, Kind: "json"},
	"GpsHeading":          {Name: "GpsHeading", Interval: 10 * time.Second, Kind: "number"},
	"Soc":                 {Name: "Soc", Interval: 60 * time.Second, Kind: "number"},
	"Odometer":            {Name: "Odometer", Interval: 60 * time.Second, Kind: "number"},
	"EstBatteryRange":     {Name: "EstBatteryRange", Interval: 60 * time.Second, Kind: "number"},
	"DoorState":           {Name: "DoorState", Interval: time.Second, Kind: "string"},
	"Locked":              {Name: "Locked", Interval: time.Second, Kind: "bool"},
	"DetailedChargeState": {Name: "DetailedChargeState", Interval: time.Second, Kind: "string"},
	"Gear":                {Name: "Gear", Interval: time.Second, Kind: "string"},
	"InsideTemp":          {Name: "InsideTemp", Interval: 60 * time.Second, Kind: "number"},
	"OutsideTemp":         {Name: "OutsideTemp", Interval: 60 * time.Second, Kind: "number"},
	"TpmsPressureFl":      {Name: "TpmsPressureFl", Interval: time.Second, Kind: "number"},
	"TpmsPressureFr":      {Name: "TpmsPressureFr", Interval: time.Second, Kind: "number"},
	"TpmsPressureRl":      {Name: "TpmsPressureRl", Interval: time.Second, Kind: "number"},
	"TpmsPressureRr":      {Name: "TpmsPressureRr", Interval: time.Second, Kind: "number"},
	"TpmsHardWarnings":    {Name: "TpmsHardWarnings", Interval: time.Second, Kind: "json"},
	"TpmsSoftWarnings":    {Name: "TpmsSoftWarnings", Interval: time.Second, Kind: "json"},
}

func telemetryFieldSpec(name string) (telemetryFieldDefinition, bool) {
	spec, ok := telemetryFieldSpecs[strings.TrimSpace(name)]
	return spec, ok
}

type telemetryRecord struct {
	VINHash         string
	FieldName       string
	Value           any
	ObservedAt      time.Time
	EventID         string
	Source          string
	ReceiveSequence uint64
}

func (r telemetryRecord) debugString() string {
	return fmt.Sprintf("telemetry field=%s vin_hash=%s observed_at=%s event_id=%s", r.FieldName, r.VINHash, r.ObservedAt.UTC().Format(time.RFC3339), r.EventID)
}

func keyedVINHash(key []byte, vin string) string {
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write([]byte(strings.TrimSpace(vin)))
	return hex.EncodeToString(mac.Sum(nil))
}

func telemetryEventIdentity(key []byte, topic string, observedAt time.Time, value any) string {
	encoded, _ := json.Marshal(value)
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write([]byte(topic))
	_, _ = mac.Write([]byte{0})
	_, _ = mac.Write([]byte(observedAt.UTC().Format(time.RFC3339Nano)))
	_, _ = mac.Write([]byte{0})
	_, _ = mac.Write(encoded)
	return hex.EncodeToString(mac.Sum(nil))
}

func hashTelemetryValue(value any) string {
	encoded, _ := json.Marshal(value)
	sum := sha256.Sum256(encoded)
	return hex.EncodeToString(sum[:])
}

func parseTelemetryPayload(topicBase, topic string, payload []byte, observedAt time.Time) (telemetryRecord, error) {
	return parseTelemetryPayloadWithKey(nil, topicBase, topic, payload, observedAt)
}

func parseTelemetryPayloadWithKey(key []byte, topicBase, topic string, payload []byte, observedAt time.Time) (telemetryRecord, error) {
	if len(payload) == 0 || len(payload) > maxTelemetryPayloadBytes {
		return telemetryRecord{}, errors.New("telemetry payload size is invalid")
	}
	base := strings.Trim(topicBase, "/")
	parts := strings.Split(strings.Trim(topic, "/"), "/")
	baseParts := strings.Split(base, "/")
	if base == "" || len(parts) != len(baseParts)+3 || strings.Join(parts[:len(baseParts)], "/") != base || parts[len(baseParts)+1] != "v" || parts[len(baseParts)] == "" {
		return telemetryRecord{}, errors.New("telemetry topic is invalid")
	}
	fieldName := parts[len(baseParts)+2]
	spec, ok := telemetryFieldSpec(fieldName)
	if !ok {
		return telemetryRecord{}, fmt.Errorf("telemetry field %q is not allowed", fieldName)
	}
	value, err := normalizeTelemetryValue(spec.Name, json.RawMessage(payload))
	if err != nil {
		return telemetryRecord{}, err
	}
	if observedAt.IsZero() || observedAt.Location() == nil {
		return telemetryRecord{}, errors.New("telemetry observed_at is invalid")
	}
	vinHash := keyedVINHash(key, parts[len(baseParts)])
	return telemetryRecord{
		VINHash: vinHash, FieldName: spec.Name, Value: value,
		ObservedAt: observedAt.UTC(), EventID: telemetryEventIdentity(key, topic, observedAt, value), Source: "telemetry_mqtt",
	}, nil
}

func normalizeTelemetryValue(fieldName string, raw json.RawMessage) (any, error) {
	spec, ok := telemetryFieldSpec(fieldName)
	if !ok {
		return nil, fmt.Errorf("telemetry field %q is not allowed", fieldName)
	}
	raw = json.RawMessage(strings.TrimSpace(string(raw)))
	if len(raw) == 0 || len(raw) > maxTelemetryPayloadBytes {
		return nil, errors.New("telemetry value is empty or oversized")
	}
	switch spec.Kind {
	case "number":
		if raw[0] == '"' {
			var text string
			if err := json.Unmarshal(raw, &text); err != nil {
				return nil, errors.New("telemetry number string is invalid")
			}
			if len(text) > 128 {
				return nil, errors.New("telemetry number string is oversized")
			}
			return parseFiniteNumber(text)
		}
		var number json.Number
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.UseNumber()
		if err := decodeJSONExactly(decoder, &number); err != nil || number.String() == "" {
			return nil, errors.New("telemetry number is invalid")
		}
		return parseFiniteNumber(number.String())
	case "bool":
		var value bool
		if json.Unmarshal(raw, &value) == nil {
			return value, nil
		}
		var text string
		if json.Unmarshal(raw, &text) == nil {
			switch strings.ToLower(strings.TrimSpace(text)) {
			case "true":
				return true, nil
			case "false":
				return false, nil
			}
		}
		return nil, errors.New("telemetry boolean is invalid")
	case "string":
		var value string
		if err := json.Unmarshal(raw, &value); err != nil || len(value) > 512 {
			return nil, errors.New("telemetry string is invalid")
		}
		return value, nil
	case "json":
		var value any
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.UseNumber()
		if err := decodeJSONExactly(decoder, &value); err != nil || !finiteJSON(value) {
			return nil, errors.New("telemetry JSON value is invalid")
		}
		return value, nil
	default:
		return nil, errors.New("telemetry field kind is unsupported")
	}
}

func decodeJSONExactly(decoder *json.Decoder, target any) error {
	if err := decoder.Decode(target); err != nil {
		return err
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		if err == nil {
			return errors.New("trailing JSON content")
		}
		return err
	}
	return nil
}

func parseFiniteNumber(value string) (float64, error) {
	parsed, err := strconv.ParseFloat(strings.TrimSpace(value), 64)
	if err != nil || math.IsNaN(parsed) || math.IsInf(parsed, 0) {
		return 0, errors.New("telemetry number is not finite")
	}
	return parsed, nil
}

func finiteJSON(value any) bool {
	switch value := value.(type) {
	case json.Number:
		_, err := parseFiniteNumber(value.String())
		return err == nil
	case float64:
		return !math.IsNaN(value) && !math.IsInf(value, 0)
	case []any:
		for _, item := range value {
			if !finiteJSON(item) {
				return false
			}
		}
	case map[string]any:
		for _, item := range value {
			if !finiteJSON(item) {
				return false
			}
		}
	}
	return true
}

type telemetryVehicleRef struct {
	UserID            string
	VehicleID         int
	VINHash           string
	VINCiphertext     string
	ProviderVehicleID string
	DisplayName       string
}

type telemetryLatestValue struct {
	FieldName  string
	Value      any
	ValueHash  string
	ObservedAt time.Time
	Source     string
	EventID    string
}

type telemetrySnapshot struct {
	Fields          map[string]any
	FieldObservedAt map[string]time.Time
	ObservedAt      time.Time
	Source          string
	EventCount      int
}

type telemetryRoutePoint struct {
	ObservedAt time.Time
	Latitude   float64
	Longitude  float64
	Speed      *float64
	Power      *float64
	Heading    *float64
}

func downsampleRoutePoints(points []telemetryRoutePoint, minInterval time.Duration) []telemetryRoutePoint {
	if len(points) == 0 {
		return nil
	}
	ordered := append([]telemetryRoutePoint(nil), points...)
	sort.SliceStable(ordered, func(i, j int) bool { return ordered[i].ObservedAt.Before(ordered[j].ObservedAt) })
	result := make([]telemetryRoutePoint, 0, len(ordered))
	for _, point := range ordered {
		if !point.ObservedAt.IsZero() && (len(result) == 0 || point.ObservedAt.Sub(result[len(result)-1].ObservedAt) >= minInterval) {
			result = append(result, point)
		}
	}
	return result
}

type telemetrySessionEvent struct {
	FieldName  string
	Value      any
	ObservedAt time.Time
	EventID    string
}

type telemetrySession struct {
	ID            string
	PublicID      int
	Kind          string
	StartAt       time.Time
	EndAt         *time.Time
	OdometerStart *float64
	OdometerEnd   *float64
	EnergyAdded   *float64
	CompletionKey string
	Route         []telemetryRoutePoint
}

type telemetrySessionMachineSnapshot struct {
	Drive         *telemetrySession
	Charge        *telemetrySession
	StopCandidate *time.Time
	Seen          map[string]bool
	LastByField   map[string]time.Time
	Completed     []telemetrySession
}

type telemetrySessionMachine struct {
	stopDebounce  time.Duration
	drive         *telemetrySession
	charge        *telemetrySession
	stopCandidate *time.Time
	seen          map[string]bool
	lastByField   map[string]time.Time
	completed     []telemetrySession
}

func newTelemetrySessionMachine(stopDebounce time.Duration) *telemetrySessionMachine {
	if stopDebounce <= 0 {
		stopDebounce = defaultDriveStopDebounce
	}
	return &telemetrySessionMachine{stopDebounce: stopDebounce, seen: map[string]bool{}, lastByField: map[string]time.Time{}}
}

func newTelemetrySessionMachineFromSnapshot(debounce time.Duration, snapshot telemetrySessionMachineSnapshot) *telemetrySessionMachine {
	machine := newTelemetrySessionMachine(debounce)
	machine.drive, machine.charge = cloneTelemetrySession(snapshot.Drive), cloneTelemetrySession(snapshot.Charge)
	if snapshot.StopCandidate != nil {
		candidate := *snapshot.StopCandidate
		machine.stopCandidate = &candidate
	}
	for key, value := range snapshot.Seen {
		machine.seen[key] = value
	}
	for key, value := range snapshot.LastByField {
		machine.lastByField[key] = value
	}
	machine.completed = append(machine.completed, cloneTelemetrySessions(snapshot.Completed)...)
	return machine
}

func (m *telemetrySessionMachine) snapshot() telemetrySessionMachineSnapshot {
	seen := make(map[string]bool, len(m.seen))
	for key, value := range m.seen {
		seen[key] = value
	}
	lastByField := make(map[string]time.Time, len(m.lastByField))
	for key, value := range m.lastByField {
		lastByField[key] = value
	}
	var candidate *time.Time
	if m.stopCandidate != nil {
		value := *m.stopCandidate
		candidate = &value
	}
	return telemetrySessionMachineSnapshot{
		Drive: cloneTelemetrySession(m.drive), Charge: cloneTelemetrySession(m.charge), StopCandidate: candidate,
		Seen: seen, LastByField: lastByField, Completed: cloneTelemetrySessions(m.completed),
	}
}

func (m *telemetrySessionMachine) apply(event telemetrySessionEvent) {
	if event.EventID == "" || event.ObservedAt.IsZero() || m.seen[event.EventID] {
		return
	}
	if previous, ok := m.lastByField[event.FieldName]; ok && event.ObservedAt.Before(previous) {
		m.seen[event.EventID] = true
		return
	}
	m.seen[event.EventID] = true
	m.lastByField[event.FieldName] = event.ObservedAt
	switch event.FieldName {
	case "Gear", "VehicleSpeed":
		m.applyDrive(event)
	case "DetailedChargeState":
		m.applyCharge(event)
	case "Odometer":
		if m.drive != nil {
			if number, ok := event.Value.(float64); ok {
				if m.drive.OdometerStart == nil {
					m.drive.OdometerStart = &number
				} else {
					m.drive.OdometerEnd = &number
				}
			}
		}
	case "Location":
		if point, ok := routePointFromLocation(event); ok && m.drive != nil {
			m.drive.Route = append(m.drive.Route, point)
		}
	}
	if m.drive != nil && m.drive.EndAt == nil && m.stopCandidate != nil && event.ObservedAt.Sub(*m.stopCandidate) >= m.stopDebounce {
		end := event.ObservedAt
		m.drive.EndAt = &end
		m.complete(m.drive)
		m.drive = nil
		m.stopCandidate = nil
	}
}

func (m *telemetrySessionMachine) finalizeDue(now time.Time) bool {
	if m == nil || m.drive == nil || m.drive.EndAt != nil || m.stopCandidate == nil || now.Before(m.stopCandidate.Add(m.stopDebounce)) {
		return false
	}
	end := m.stopCandidate.Add(m.stopDebounce)
	m.drive.EndAt = &end
	m.complete(m.drive)
	m.drive = nil
	m.stopCandidate = nil
	return true
}

func (m *telemetrySessionMachine) applyDrive(event telemetrySessionEvent) {
	if m.drive == nil && isDriveEvidence(event) {
		m.drive = &telemetrySession{ID: sessionID("drive", event.ObservedAt), Kind: "drive", StartAt: event.ObservedAt}
		m.stopCandidate = nil
		return
	}
	if m.drive == nil {
		return
	}
	if isDriveStopEvidence(event) {
		if m.stopCandidate == nil {
			candidate := event.ObservedAt
			m.stopCandidate = &candidate
		}
		return
	}
	m.stopCandidate = nil
}

func (m *telemetrySessionMachine) applyCharge(event telemetrySessionEvent) {
	state := strings.ToLower(strings.TrimSpace(fmt.Sprint(event.Value)))
	if m.charge == nil && event.FieldName == "DetailedChargeState" && (state == "charging" || state == "starting") {
		m.charge = &telemetrySession{ID: sessionID("charge", event.ObservedAt), Kind: "charge", StartAt: event.ObservedAt}
		return
	}
	if m.charge == nil {
		return
	}
	terminal := event.FieldName == "DetailedChargeState" && (state == "disconnected" || state == "complete" || state == "completed" || state == "stopped")
	if terminal {
		end := event.ObservedAt
		m.charge.EndAt = &end
		m.complete(m.charge)
		m.charge = nil
	}
}

func isDriveEvidence(event telemetrySessionEvent) bool {
	if event.FieldName == "VehicleSpeed" {
		value, ok := event.Value.(float64)
		return ok && value > 0
	}
	if event.FieldName == "Gear" {
		gear := strings.ToUpper(strings.TrimSpace(fmt.Sprint(event.Value)))
		return gear == "D" || gear == "R" || gear == "DRIVE" || gear == "REVERSE"
	}
	return false
}

func isDriveStopEvidence(event telemetrySessionEvent) bool {
	if event.FieldName == "VehicleSpeed" {
		value, ok := event.Value.(float64)
		return ok && value <= 0
	}
	if event.FieldName == "Gear" {
		gear := strings.ToUpper(strings.TrimSpace(fmt.Sprint(event.Value)))
		return gear == "P" || gear == "N" || gear == "PARK" || gear == "NEUTRAL"
	}
	return false
}

func (m *telemetrySessionMachine) complete(session *telemetrySession) {
	if session == nil || session.EndAt == nil {
		return
	}
	session.CompletionKey = sessionCompletionKey(*session)
	m.completed = append(m.completed, *cloneTelemetrySession(session))
}

func sessionCompletionKey(session telemetrySession) string {
	if session.EndAt == nil {
		return ""
	}
	sum := sha256.Sum256([]byte(session.Kind + "\x00" + session.ID + "\x00" + session.EndAt.UTC().Format(time.RFC3339Nano)))
	return hex.EncodeToString(sum[:])
}

func (m *telemetrySessionMachine) completedSessions() []telemetrySession {
	return cloneTelemetrySessions(m.completed)
}

func sessionID(kind string, start time.Time) string {
	sum := sha256.Sum256([]byte(kind + "\x00" + start.UTC().Format(time.RFC3339Nano)))
	return hex.EncodeToString(sum[:])
}

func cloneTelemetrySession(value *telemetrySession) *telemetrySession {
	if value == nil {
		return nil
	}
	copyValue := *value
	copyValue.Route = append([]telemetryRoutePoint(nil), value.Route...)
	return &copyValue
}

func cloneTelemetrySessions(values []telemetrySession) []telemetrySession {
	result := make([]telemetrySession, 0, len(values))
	for index := range values {
		result = append(result, *cloneTelemetrySession(&values[index]))
	}
	return result
}

func routePointFromLocation(event telemetrySessionEvent) (telemetryRoutePoint, bool) {
	value, ok := event.Value.(map[string]any)
	if !ok {
		return telemetryRoutePoint{}, false
	}
	latitude, latOK := numberFromJSONValue(value["latitude"])
	longitude, lonOK := numberFromJSONValue(value["longitude"])
	if !latOK || !lonOK || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 {
		return telemetryRoutePoint{}, false
	}
	point := telemetryRoutePoint{ObservedAt: event.ObservedAt, Latitude: latitude, Longitude: longitude}
	if speed, ok := numberFromJSONValue(value["speed"]); ok {
		point.Speed = &speed
	} else if speed, ok := numberFromJSONValue(value["vehicle_speed"]); ok {
		point.Speed = &speed
	}
	if heading, ok := numberFromJSONValue(value["heading"]); ok {
		point.Heading = &heading
	} else if heading, ok := numberFromJSONValue(value["gps_heading"]); ok {
		point.Heading = &heading
	}
	if power, ok := numberFromJSONValue(value["power"]); ok {
		point.Power = &power
	}
	return point, true
}

func numberFromJSONValue(value any) (float64, bool) {
	switch value := value.(type) {
	case float64:
		return value, !math.IsNaN(value) && !math.IsInf(value, 0)
	case json.Number:
		parsed, err := parseFiniteNumber(value.String())
		return parsed, err == nil
	case string:
		parsed, err := parseFiniteNumber(value)
		return parsed, err == nil
	default:
		return 0, false
	}
}

type telemetryKey struct {
	UserID    string
	VehicleID int
}

type telemetryMemoryStore struct {
	mu           sync.Mutex
	vehicles     map[string][]telemetryVehicleRef
	latest       map[telemetryKey]map[string]telemetryLatestValue
	events       map[string]time.Time
	routes       map[telemetryKey][]telemetryRoutePoint
	machines     map[telemetryKey]*telemetrySessionMachine
	completed    map[telemetryKey][]telemetrySession
	pairings     map[telemetryKey]telemetryPairing
	startedAt    map[telemetryKey]time.Time
	nextPublicID int
}

func newTelemetryMemoryStore() *telemetryMemoryStore {
	return &telemetryMemoryStore{
		vehicles: map[string][]telemetryVehicleRef{}, latest: map[telemetryKey]map[string]telemetryLatestValue{},
		events: map[string]time.Time{}, routes: map[telemetryKey][]telemetryRoutePoint{},
		machines: map[telemetryKey]*telemetrySessionMachine{}, completed: map[telemetryKey][]telemetrySession{},
		pairings: map[telemetryKey]telemetryPairing{}, startedAt: map[telemetryKey]time.Time{}, nextPublicID: 1,
	}
}

func (s *telemetryMemoryStore) registerVehicle(ref telemetryVehicleRef) error {
	if strings.TrimSpace(ref.UserID) == "" || ref.VehicleID <= 0 || strings.TrimSpace(ref.VINHash) == "" {
		return errors.New("telemetry vehicle binding is incomplete")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, current := range s.vehicles[ref.VINHash] {
		if current.UserID == ref.UserID && current.VehicleID == ref.VehicleID {
			return nil
		}
	}
	s.vehicles[ref.VINHash] = append(s.vehicles[ref.VINHash], ref)
	return nil
}

func (s *telemetryMemoryStore) ingest(record telemetryRecord, stopDebounce time.Duration) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ingestLocked(record, stopDebounce)
}

func (s *telemetryMemoryStore) ingestLocked(record telemetryRecord, stopDebounce time.Duration) (int, error) {
	if record.EventID == "" {
		return 0, errors.New("telemetry event identity is empty")
	}
	accepted := 0
	for _, ref := range s.vehicles[record.VINHash] {
		key := telemetryKey{UserID: ref.UserID, VehicleID: ref.VehicleID}
		eventKey := record.EventID + "\x00" + ref.UserID + "\x00" + fmt.Sprint(ref.VehicleID)
		if _, exists := s.events[eventKey]; exists {
			continue
		}
		s.events[eventKey] = record.ObservedAt
		if s.latest[key] == nil {
			s.latest[key] = map[string]telemetryLatestValue{}
		}
		current, exists := s.latest[key][record.FieldName]
		valueHash := hashTelemetryValue(record.Value)
		if exists && current.ValueHash == valueHash {
			continue
		}
		if exists && !record.ObservedAt.After(current.ObservedAt) {
			continue
		}
		s.latest[key][record.FieldName] = telemetryLatestValue{FieldName: record.FieldName, Value: record.Value, ValueHash: valueHash, ObservedAt: record.ObservedAt, Source: record.Source, EventID: record.EventID}
		machine := s.machines[key]
		if machine == nil {
			machine = newTelemetrySessionMachine(stopDebounce)
			s.machines[key] = machine
		}
		machine.apply(telemetrySessionEvent{FieldName: record.FieldName, Value: record.Value, ObservedAt: record.ObservedAt, EventID: record.EventID})
		s.assignPublicIDsLocked(machine)
		if len(machine.completedSessions()) > len(s.completed[key]) {
			s.completed[key] = machine.completedSessions()
		}
		if record.FieldName == "Location" {
			if point, ok := routePointFromLocation(telemetrySessionEvent{Value: record.Value, ObservedAt: record.ObservedAt}); ok {
				s.routes[key] = downsampleRoutePoints(append(s.routes[key], point), 10*time.Second)
			}
		}
		if s.startedAt[key].IsZero() {
			s.startedAt[key] = record.ObservedAt
		}
		accepted++
	}
	return accepted, nil
}

func (s *telemetryMemoryStore) latestSnapshot(userID string, vehicleID int) (telemetrySnapshot, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	values, ok := s.latest[telemetryKey{UserID: userID, VehicleID: vehicleID}]
	if !ok || len(values) == 0 {
		return telemetrySnapshot{}, false
	}
	fields := make(map[string]any, len(values))
	fieldObservedAt := make(map[string]time.Time, len(values))
	var observedAt time.Time
	source := "telemetry_mqtt"
	for field, value := range values {
		fields[field] = value.Value
		fieldObservedAt[field] = value.ObservedAt
		if value.ObservedAt.After(observedAt) {
			observedAt = value.ObservedAt
		}
		if value.Source != "" {
			source = value.Source
		}
	}
	return telemetrySnapshot{Fields: fields, FieldObservedAt: fieldObservedAt, ObservedAt: observedAt, Source: source, EventCount: len(values)}, true
}

func (s *telemetryMemoryStore) putLatest(ref telemetryVehicleRef, field string, value any, observedAt time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := telemetryKey{UserID: ref.UserID, VehicleID: ref.VehicleID}
	if s.latest[key] == nil {
		s.latest[key] = map[string]telemetryLatestValue{}
	}
	s.latest[key][field] = telemetryLatestValue{FieldName: field, Value: value, ValueHash: hashTelemetryValue(value), ObservedAt: observedAt, Source: "telemetry_mqtt", EventID: "manual-" + field}
}

func (s *telemetryMemoryStore) setPairing(ref telemetryVehicleRef, pairing telemetryPairing) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pairings[telemetryKey{UserID: ref.UserID, VehicleID: ref.VehicleID}] = pairing
}

func (s *telemetryMemoryStore) pairing(userID string, vehicleID int) (telemetryPairing, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	pairing, ok := s.pairings[telemetryKey{UserID: userID, VehicleID: vehicleID}]
	return pairing, ok
}

func (s *telemetryMemoryStore) addCompletedSession(ref telemetryVehicleRef, session telemetrySession) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := telemetryKey{UserID: ref.UserID, VehicleID: ref.VehicleID}
	if session.PublicID <= 0 {
		session.PublicID = s.allocatePublicIDLocked()
	}
	s.completed[key] = append(s.completed[key], session)
	if s.startedAt[key].IsZero() {
		s.startedAt[key] = session.StartAt
	}
}

func (s *telemetryMemoryStore) assignPublicIDsLocked(machine *telemetrySessionMachine) {
	if machine == nil {
		return
	}
	if machine.drive != nil && machine.drive.PublicID <= 0 {
		machine.drive.PublicID = s.allocatePublicIDLocked()
	}
	if machine.charge != nil && machine.charge.PublicID <= 0 {
		machine.charge.PublicID = s.allocatePublicIDLocked()
	}
	for index := range machine.completed {
		if machine.completed[index].PublicID <= 0 {
			machine.completed[index].PublicID = s.allocatePublicIDLocked()
		}
	}
}

func (s *telemetryMemoryStore) allocatePublicIDLocked() int {
	if s.nextPublicID <= 0 {
		s.nextPublicID = 1
	}
	id := s.nextPublicID
	s.nextPublicID++
	return id
}

func (s *telemetryMemoryStore) openSession(userID string, vehicleID int, kind string) (telemetrySession, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	machine := s.machines[telemetryKey{UserID: userID, VehicleID: vehicleID}]
	if machine == nil {
		return telemetrySession{}, false
	}
	var session *telemetrySession
	if kind == "charge" {
		session = machine.charge
	} else if kind == "drive" {
		session = machine.drive
	}
	if session == nil || session.EndAt != nil {
		return telemetrySession{}, false
	}
	return *cloneTelemetrySession(session), true
}

func (s *telemetryMemoryStore) finalizeDue(now time.Time) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	completed := 0
	for key, machine := range s.machines {
		if !machine.finalizeDue(now) {
			continue
		}
		s.assignPublicIDsLocked(machine)
		s.completed[key] = machine.completedSessions()
		completed++
	}
	return completed
}

func (s *telemetryMemoryStore) sessions(userID string, vehicleID int, kind string) []telemetrySession {
	s.mu.Lock()
	defer s.mu.Unlock()
	all := s.completed[telemetryKey{UserID: userID, VehicleID: vehicleID}]
	result := make([]telemetrySession, 0, len(all))
	for _, session := range all {
		if session.Kind == kind && session.EndAt != nil {
			result = append(result, *cloneTelemetrySession(&session))
		}
	}
	return result
}

func (s *telemetryMemoryStore) historyStartedAt(userID string, vehicleID int) time.Time {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.startedAt[telemetryKey{UserID: userID, VehicleID: vehicleID}]
}

// importSessions upserts locally-imported completed sessions into the store,
// assigning public ids for new sessions and preserving existing public ids on update.
func (s *telemetryMemoryStore) importSessions(userID string, vehicleID int, drives, charges []telemetrySession) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := telemetryKey{UserID: userID, VehicleID: vehicleID}
	existingSessions := append([]telemetrySession(nil), s.completed[key]...)
	for _, session := range append(append([]telemetrySession(nil), drives...), charges...) {
		imported := cloneTelemetrySession(&session)
		foundIdx := -1
		for i, existing := range existingSessions {
			if existing.ID == imported.ID {
				foundIdx = i
				break
			}
		}
		if foundIdx >= 0 {
			imported.PublicID = existingSessions[foundIdx].PublicID
			existingSessions[foundIdx] = *imported
		} else {
			if imported.PublicID <= 0 {
				imported.PublicID = s.allocatePublicIDLocked()
			}
			existingSessions = append(existingSessions, *imported)
		}
		if s.startedAt[key].IsZero() || session.StartAt.Before(s.startedAt[key]) {
			s.startedAt[key] = session.StartAt
		}
	}
	s.completed[key] = existingSessions
}

// retainLatestDays deletes completed sessions outside the account's two most
// recent calendar days that contain data, across all vehicles of that account.
// It returns the retained day strings (YYYY-MM-DD, newest first).
func (s *telemetryMemoryStore) retainLatestDays(userID string) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	daySet := map[string]bool{}
	for key, sessions := range s.completed {
		if key.UserID != userID {
			continue
		}
		for _, session := range sessions {
			if session.EndAt == nil {
				continue
			}
			daySet[session.StartAt.UTC().Format("2006-01-02")] = true
		}
	}
	days := make([]string, 0, len(daySet))
	for day := range daySet {
		days = append(days, day)
	}
	sort.Strings(days)
	// descending order (newest first)
	for i, j := 0, len(days)-1; i < j; i, j = i+1, j-1 {
		days[i], days[j] = days[j], days[i]
	}
	if len(days) <= 2 {
		return days
	}
	retained := days[:2]
	retainedSet := map[string]bool{retained[0]: true, retained[1]: true}
	for key, sessions := range s.completed {
		if key.UserID != userID {
			continue
		}
		filtered := sessions[:0]
		for _, session := range sessions {
			if session.EndAt == nil || retainedSet[session.StartAt.UTC().Format("2006-01-02")] {
				filtered = append(filtered, session)
			}
		}
		s.completed[key] = filtered
	}
	return retained
}
