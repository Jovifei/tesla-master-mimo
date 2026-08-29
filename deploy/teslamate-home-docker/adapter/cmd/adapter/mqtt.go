package main

import (
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

const mqttFreshnessWindow = 120 * time.Second

type mqttTopic struct {
	carID int
	field string
}

type mqttValue struct {
	value      any
	observedAt time.Time
	retained   bool
}

type mqttSnapshotStore struct {
	mu   sync.RWMutex
	cars map[int]map[string]mqttValue
}

func newMQTTSnapshotStore() *mqttSnapshotStore {
	return &mqttSnapshotStore{cars: make(map[int]map[string]mqttValue)}
}

func parseTeslaMateTopic(topic string) (mqttTopic, bool) {
	parts := strings.Split(strings.Trim(topic, "/"), "/")
	if len(parts) == 4 && parts[0] == "teslamate" && parts[1] == "cars" {
		return parseMQTTCarTopic(parts[2], parts[3])
	}
	if len(parts) == 5 && parts[0] == "teslamate" && parts[2] == "cars" {
		return parseMQTTCarTopic(parts[3], parts[4])
	}
	return mqttTopic{}, false
}

func parseMQTTCarTopic(carIDText, field string) (mqttTopic, bool) {
	carID, err := strconv.Atoi(carIDText)
	if err != nil || carID <= 0 || !isMQTTField(field) {
		return mqttTopic{}, false
	}
	return mqttTopic{carID: carID, field: field}, true
}

func isMQTTField(field string) bool {
	_, ok := mqttFieldPaths[field]
	return ok || field == "location"
}

func decodeMQTTValue(field string, payload []byte) (any, bool) {
	if field == "location" {
		var value struct {
			Latitude  *float64 `json:"latitude"`
			Longitude *float64 `json:"longitude"`
		}
		if json.Unmarshal(payload, &value) != nil || value.Latitude == nil || value.Longitude == nil {
			return nil, false
		}
		if !finite(*value.Latitude) || !finite(*value.Longitude) {
			return nil, false
		}
		return map[string]any{
			"latitude":  *value.Latitude,
			"longitude": *value.Longitude,
		}, true
	}

	kind, ok := mqttFieldKinds[field]
	if !ok {
		return nil, false
	}
	text := strings.TrimSpace(string(payload))
	if text == "" {
		return nil, false
	}
	switch kind {
	case mqttString:
		return text, true
	case mqttBool:
		value, err := strconv.ParseBool(text)
		return value, err == nil
	case mqttNumber:
		value, err := strconv.ParseFloat(text, 64)
		return value, err == nil && finite(value)
	default:
		return nil, false
	}
}

func (s *mqttSnapshotStore) Apply(topic string, payload []byte, retained bool, observedAt time.Time) bool {
	parsed, ok := parseTeslaMateTopic(topic)
	if !ok {
		return false
	}
	value, ok := decodeMQTTValue(parsed.field, payload)
	if !ok {
		return false
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if s.cars[parsed.carID] == nil {
		s.cars[parsed.carID] = make(map[string]mqttValue)
	}
	if parsed.field == "location" {
		location := value.(map[string]any)
		for _, field := range []string{"latitude", "longitude"} {
			s.cars[parsed.carID][field] = mqttValue{
				value:      location[field],
				observedAt: observedAt,
				retained:   retained,
			}
		}
		return true
	}
	s.cars[parsed.carID][parsed.field] = mqttValue{
		value:      value,
		observedAt: observedAt,
		retained:   retained,
	}
	return true
}

func (s *mqttSnapshotStore) Snapshot(carID int, now time.Time, loc *time.Location) (snapshot, bool) {
	s.mu.RLock()
	values := make(map[string]mqttValue, len(s.cars[carID]))
	for field, value := range s.cars[carID] {
		values[field] = value
	}
	s.mu.RUnlock()
	if len(values) == 0 {
		return snapshot{}, false
	}

	status := make(map[string]any)
	fieldSources := make(map[string]string, len(values))
	source := "mqtt_latest"
	var latest time.Time
	for field, value := range values {
		path, ok := mqttFieldPaths[field]
		if !ok {
			continue
		}
		setSnapshotPath(status, path, value.value)
		fieldSource := mqttValueSource(value, now)
		fieldSources[field] = fieldSource
		if fieldSource == "live_mqtt" {
			source = "live_mqtt"
		}
		if value.observedAt.After(latest) {
			latest = value.observedAt
		}
	}
	if len(fieldSources) == 0 {
		return snapshot{}, false
	}
	if loc == nil {
		loc = time.Local
	}
	return snapshot{
		Status:       status,
		Units:        map[string]string{"unit_of_length": "km", "unit_of_pressure": "bar", "unit_of_temperature": "C"},
		ObservedAt:   formatTime(latest, loc),
		Source:       source,
		FieldSources: fieldSources,
	}, true
}

func mqttValueSource(value mqttValue, now time.Time) string {
	if !value.retained && !value.observedAt.After(now) && now.Sub(value.observedAt) <= mqttFreshnessWindow {
		return "live_mqtt"
	}
	return "mqtt_latest"
}

func setSnapshotPath(status map[string]any, path string, value any) {
	parts := strings.Split(path, ".")
	current := status
	for _, part := range parts[:len(parts)-1] {
		nested, ok := current[part].(map[string]any)
		if !ok {
			nested = make(map[string]any)
			current[part] = nested
		}
		current = nested
	}
	current[parts[len(parts)-1]] = value
}

func mergeSnapshot(base, live snapshot) snapshot {
	merged := base
	merged.Status = cloneSnapshotMap(base.Status)
	mergeSnapshotMap(merged.Status, live.Status)
	merged.FieldSources = cloneStringMap(base.FieldSources)
	for field, source := range live.FieldSources {
		merged.FieldSources[field] = source
	}
	if len(live.Units) > 0 {
		merged.Units = live.Units
	}
	if live.ObservedAt != "" {
		merged.ObservedAt = live.ObservedAt
	}
	if live.Source != "" {
		merged.Source = live.Source
	}
	return merged
}

func mergeSnapshotMap(base, overlay map[string]any) {
	for key, value := range overlay {
		nested, ok := value.(map[string]any)
		if !ok {
			base[key] = value
			continue
		}
		baseNested, _ := base[key].(map[string]any)
		if baseNested == nil {
			baseNested = make(map[string]any)
		}
		mergeSnapshotMap(baseNested, nested)
		base[key] = baseNested
	}
}

func cloneSnapshotMap(source map[string]any) map[string]any {
	result := make(map[string]any, len(source))
	for key, value := range source {
		if nested, ok := value.(map[string]any); ok {
			result[key] = cloneSnapshotMap(nested)
		} else {
			result[key] = value
		}
	}
	return result
}

func cloneStringMap(source map[string]string) map[string]string {
	result := make(map[string]string, len(source))
	for key, value := range source {
		result[key] = value
	}
	return result
}

type mqttFieldKind uint8

const (
	mqttString mqttFieldKind = iota
	mqttBool
	mqttNumber
)

var mqttFieldKinds = map[string]mqttFieldKind{
	"display_name": mqttString, "state": mqttString, "since": mqttString, "charging_state": mqttString,
	"version": mqttString, "update_version": mqttString, "geofence": mqttString,
	"shift_state": mqttString, "center_display_state": mqttString,
	"healthy": mqttBool, "locked": mqttBool, "sentry_mode": mqttBool,
	"windows_open": mqttBool, "doors_open": mqttBool, "trunk_open": mqttBool,
	"frunk_open": mqttBool, "is_user_present": mqttBool, "update_available": mqttBool,
	"is_climate_on": mqttBool, "is_preconditioning": mqttBool, "plugged_in": mqttBool,
	"charge_port_door_open": mqttBool,
	"latitude":              mqttNumber, "longitude": mqttNumber, "odometer": mqttNumber,
	"power": mqttNumber, "speed": mqttNumber, "heading": mqttNumber, "elevation": mqttNumber,
	"inside_temp": mqttNumber, "outside_temp": mqttNumber,
	"est_battery_range_km": mqttNumber, "rated_battery_range_km": mqttNumber, "ideal_battery_range_km": mqttNumber,
	"battery_level": mqttNumber, "usable_battery_level": mqttNumber,
	"charge_energy_added": mqttNumber, "charge_limit_soc": mqttNumber,
	"charger_actual_current": mqttNumber, "charger_phases": mqttNumber, "charger_power": mqttNumber,
	"charger_voltage": mqttNumber, "charge_current_request": mqttNumber,
	"charge_current_request_max": mqttNumber, "scheduled_charging_start_time": mqttString,
	"time_to_full_charge": mqttNumber,
	"tpms_pressure_fl":    mqttNumber, "tpms_pressure_fr": mqttNumber,
	"tpms_pressure_rl": mqttNumber, "tpms_pressure_rr": mqttNumber,
	"tpms_soft_warning_fl": mqttBool, "tpms_soft_warning_fr": mqttBool,
	"tpms_soft_warning_rl": mqttBool, "tpms_soft_warning_rr": mqttBool,
}

var mqttFieldPaths = map[string]string{
	"display_name": "display_name", "state": "state", "since": "state_since",
	"healthy": "car_status.healthy", "locked": "car_status.locked", "sentry_mode": "car_status.sentry_mode",
	"windows_open": "car_status.windows_open", "doors_open": "car_status.doors_open",
	"trunk_open": "car_status.trunk_open", "frunk_open": "car_status.frunk_open",
	"is_user_present": "car_status.is_user_present", "center_display_state": "car_status.center_display_state",
	"geofence": "car_geodata.geofence", "latitude": "car_geodata.latitude", "longitude": "car_geodata.longitude",
	"shift_state": "driving_details.shift_state", "power": "driving_details.power",
	"speed": "driving_details.speed", "heading": "driving_details.heading", "elevation": "driving_details.elevation",
	"is_climate_on": "climate_details.is_climate_on", "inside_temp": "climate_details.inside_temp",
	"outside_temp": "climate_details.outside_temp", "is_preconditioning": "climate_details.is_preconditioning",
	"battery_level": "battery_details.battery_level", "usable_battery_level": "battery_details.usable_battery_level",
	"est_battery_range_km": "battery_details.est_battery_range", "rated_battery_range_km": "battery_details.rated_battery_range",
	"ideal_battery_range_km": "battery_details.ideal_battery_range",
	"plugged_in":             "charging_details.plugged_in", "charging_state": "charging_details.charging_state",
	"charge_energy_added": "charging_details.charge_energy_added", "charge_limit_soc": "charging_details.charge_limit_soc",
	"charge_port_door_open": "charging_details.charge_port_door_open", "charger_actual_current": "charging_details.charger_actual_current",
	"charger_phases": "charging_details.charger_phases", "charger_power": "charging_details.charger_power",
	"charger_voltage": "charging_details.charger_voltage", "charge_current_request": "charging_details.charge_current_request",
	"charge_current_request_max":    "charging_details.charge_current_request_max",
	"scheduled_charging_start_time": "charging_details.scheduled_charging_start_time",
	"time_to_full_charge":           "charging_details.time_to_full_charge",
	"tpms_pressure_fl":              "tpms_details.tpms_pressure_fl", "tpms_pressure_fr": "tpms_details.tpms_pressure_fr",
	"tpms_pressure_rl": "tpms_details.tpms_pressure_rl", "tpms_pressure_rr": "tpms_details.tpms_pressure_rr",
	"tpms_soft_warning_fl": "tpms_details.tpms_soft_warning_fl", "tpms_soft_warning_fr": "tpms_details.tpms_soft_warning_fr",
	"tpms_soft_warning_rl": "tpms_details.tpms_soft_warning_rl", "tpms_soft_warning_rr": "tpms_details.tpms_soft_warning_rr",
}

type mqttConfig struct {
	enabled  bool
	broker   string
	username string
	password string
	filters  []string
}

func loadMQTTConfig() mqttConfig {
	host := env("MQTT_HOST", "mosquitto")
	port := env("MQTT_PORT", "1883")
	namespace := strings.Trim(os.Getenv("MQTT_NAMESPACE"), "/ ")
	filters := []string{"teslamate/cars/+/+"}
	if namespace != "" {
		filters = append(filters, "teslamate/"+namespace+"/cars/+/+")
	} else {
		filters = append(filters, "teslamate/+/cars/+/+")
	}
	return mqttConfig{
		enabled:  !strings.EqualFold(env("MQTT_ENABLED", "true"), "false"),
		broker:   mqttBrokerURL(host, port),
		username: strings.TrimSpace(os.Getenv("MQTT_USERNAME")),
		password: os.Getenv("MQTT_PASSWORD"),
		filters:  filters,
	}
}

func mqttBrokerURL(host, port string) string {
	if strings.Contains(host, "://") {
		return host
	}
	return "tcp://" + net.JoinHostPort(host, port)
}

func runMQTT(store *mqttSnapshotStore) {
	config := loadMQTTConfig()
	if !config.enabled {
		return
	}

	options := mqtt.NewClientOptions().
		AddBroker(config.broker).
		SetClientID(fmt.Sprintf("matelink-adapter-%d", os.Getpid())).
		SetAutoReconnect(true)
	if config.username != "" {
		options.SetUsername(config.username)
		options.SetPassword(config.password)
	}
	options.SetOnConnectHandler(func(client mqtt.Client) {
		for _, filter := range config.filters {
			token := client.Subscribe(filter, 1, func(_ mqtt.Client, message mqtt.Message) {
				store.Apply(message.Topic(), message.Payload(), message.Retained(), time.Now())
			})
			if token.Wait() && token.Error() != nil {
				log.Printf("MQTT subscription failed; PostgreSQL fallback remains available")
			}
		}
	})
	options.SetConnectionLostHandler(func(_ mqtt.Client, _ error) {
		log.Printf("MQTT connection lost; PostgreSQL fallback remains available")
	})

	client := mqtt.NewClient(options)
	for {
		token := client.Connect()
		if token.Wait() && token.Error() == nil {
			return
		}
		log.Printf("MQTT unavailable; PostgreSQL fallback remains available")
		time.Sleep(5 * time.Second)
	}
}

func finite(value float64) bool {
	return !math.IsNaN(value) && !math.IsInf(value, 0)
}
