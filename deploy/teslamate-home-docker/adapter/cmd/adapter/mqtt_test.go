package main

import (
	"errors"
	"net/http"
	"testing"
	"time"
)

func TestParseTeslaMateTopicSupportsDefaultAndNamespace(t *testing.T) {
	for _, test := range []struct {
		name  string
		topic string
		want  mqttTopic
	}{
		{name: "default", topic: "teslamate/cars/7/state", want: mqttTopic{carID: 7, field: "state"}},
		{name: "namespace", topic: "teslamate/home/cars/8/charger_power", want: mqttTopic{carID: 8, field: "charger_power"}},
	} {
		t.Run(test.name, func(t *testing.T) {
			got, ok := parseTeslaMateTopic(test.topic)
			if !ok || got != test.want {
				t.Fatalf("parseTeslaMateTopic(%q) = %#v, %v; want %#v, true", test.topic, got, ok, test.want)
			}
		})
	}
}

func TestParseTeslaMateTopicRejectsUnrelatedTopics(t *testing.T) {
	for _, topic := range []string{
		"other/cars/7/state",
		"teslamate/cars/0/state",
		"teslamate/home/7/state",
		"teslamate/cars/7",
	} {
		if _, ok := parseTeslaMateTopic(topic); ok {
			t.Fatalf("parseTeslaMateTopic(%q) accepted an unrelated topic", topic)
		}
	}
}

func TestDecodeMQTTValuePreservesFalseZeroNegativeAndDecimal(t *testing.T) {
	for _, test := range []struct {
		field   string
		payload string
		want    any
	}{
		{field: "locked", payload: "false", want: false},
		{field: "power", payload: "0", want: float64(0)},
		{field: "power", payload: "-9", want: float64(-9)},
		{field: "charger_power", payload: "48.9", want: float64(48.9)},
	} {
		got, ok := decodeMQTTValue(test.field, []byte(test.payload))
		if !ok || got != test.want {
			t.Fatalf("decodeMQTTValue(%q, %q) = %#v, %v; want %#v, true", test.field, test.payload, got, ok, test.want)
		}
	}
}

func TestMQTTSnapshotStoreIgnoresInvalidPayloadWithoutOverwriting(t *testing.T) {
	store := newMQTTSnapshotStore()
	now := time.Date(2026, 8, 26, 12, 0, 0, 0, time.UTC)
	if !store.Apply("teslamate/cars/7/locked", []byte("false"), false, now) {
		t.Fatal("valid false payload was rejected")
	}
	if !store.Apply("teslamate/cars/7/power", []byte("0"), false, now) {
		t.Fatal("valid zero payload was rejected")
	}
	if store.Apply("teslamate/cars/7/power", []byte("NaN"), false, now) {
		t.Fatal("invalid numeric payload was accepted")
	}

	value, ok := store.Snapshot(7, now, time.UTC)
	if !ok {
		t.Fatal("snapshot was not created")
	}
	status := value.Status
	carStatus := status["car_status"].(map[string]any)
	driving := status["driving_details"].(map[string]any)
	if carStatus["locked"] != false || driving["power"] != float64(0) {
		t.Fatalf("snapshot lost false/zero observations: %#v", status)
	}
	if value.Source != "live_mqtt" || value.FieldSources["locked"] != "live_mqtt" {
		t.Fatalf("fresh non-retained source classification = %#v / %#v", value.Source, value.FieldSources)
	}
}

func TestMQTTSnapshotStoreClassifiesRetainedAndStaleValuesAsLatest(t *testing.T) {
	store := newMQTTSnapshotStore()
	observed := time.Date(2026, 8, 26, 12, 0, 0, 0, time.UTC)
	if !store.Apply("teslamate/cars/7/state", []byte("asleep"), true, observed) {
		t.Fatal("retained payload was rejected")
	}
	if !store.Apply("teslamate/cars/7/speed", []byte("12"), false, observed) {
		t.Fatal("valid payload was rejected")
	}

	value, ok := store.Snapshot(7, observed.Add(121*time.Second), time.UTC)
	if !ok || value.Source != "mqtt_latest" {
		t.Fatalf("stale source classification = %#v, %v", value.Source, ok)
	}
	if value.FieldSources["state"] != "mqtt_latest" || value.FieldSources["speed"] != "mqtt_latest" {
		t.Fatalf("stale field sources = %#v", value.FieldSources)
	}
}

func TestMQTTSnapshotStoreIsolatesVehicles(t *testing.T) {
	store := newMQTTSnapshotStore()
	now := time.Date(2026, 8, 26, 12, 0, 0, 0, time.UTC)
	store.Apply("teslamate/cars/7/state", []byte("driving"), false, now)
	store.Apply("teslamate/cars/8/state", []byte("charging"), false, now)

	first, ok := store.Snapshot(7, now, time.UTC)
	if !ok || first.Status["state"] != "driving" {
		t.Fatalf("vehicle 7 snapshot = %#v, %v", first, ok)
	}
	second, ok := store.Snapshot(8, now, time.UTC)
	if !ok || second.Status["state"] != "charging" {
		t.Fatalf("vehicle 8 snapshot = %#v, %v", second, ok)
	}
}

func TestMergeSnapshotKeepsDatabaseFallbackForMissingMQTTFields(t *testing.T) {
	base := snapshot{
		Status: map[string]any{
			"car_geodata": map[string]any{"latitude": 31.2, "longitude": 121.5},
			"car_status":  map[string]any{"locked": true},
		},
		Source:       "database_latest",
		FieldSources: map[string]string{"location": "database_latest", "locked": "database_latest"},
	}
	live := snapshot{
		Status:       map[string]any{"car_status": map[string]any{"locked": false}},
		Source:       "live_mqtt",
		FieldSources: map[string]string{"locked": "live_mqtt"},
	}

	merged := mergeSnapshot(base, live)
	location := merged.Status["car_geodata"].(map[string]any)
	locked := merged.Status["car_status"].(map[string]any)["locked"]
	if location["latitude"] != 31.2 || locked != false {
		t.Fatalf("merged snapshot did not preserve fallback/false observation: %#v", merged.Status)
	}
	if merged.FieldSources["location"] != "database_latest" || merged.FieldSources["locked"] != "live_mqtt" {
		t.Fatalf("merged field sources = %#v", merged.FieldSources)
	}
}

func TestMQTTLocationPayloadPreservesZeroCoordinates(t *testing.T) {
	store := newMQTTSnapshotStore()
	now := time.Date(2026, 8, 26, 12, 0, 0, 0, time.UTC)
	if !store.Apply("teslamate/cars/7/location", []byte(`{"latitude":0,"longitude":0}`), false, now) {
		t.Fatal("zero coordinate payload was rejected")
	}

	value, ok := store.Snapshot(7, now, time.UTC)
	if !ok {
		t.Fatal("location snapshot was not created")
	}
	location := value.Status["car_geodata"].(map[string]any)
	if location["latitude"] != float64(0) || location["longitude"] != float64(0) {
		t.Fatalf("zero coordinates were not preserved: %#v", location)
	}
}

func TestLoadMQTTConfigBuildsDefaultAndNamespaceFilters(t *testing.T) {
	t.Setenv("MQTT_ENABLED", "true")
	t.Setenv("MQTT_HOST", "broker")
	t.Setenv("MQTT_PORT", "1884")
	t.Setenv("MQTT_NAMESPACE", "home")

	config := loadMQTTConfig()
	if config.broker != "tcp://broker:1884" {
		t.Fatalf("broker = %q", config.broker)
	}
	if len(config.filters) != 2 || config.filters[0] != "teslamate/cars/+/+" || config.filters[1] != "teslamate/home/cars/+/+" {
		t.Fatalf("filters = %#v", config.filters)
	}
}

func TestSnapshotEndpointUsesMQTTWhenDatabaseIsUnavailable(t *testing.T) {
	store := &recordingStore{snapshotErr: errors.New("database unavailable")}
	live := newMQTTSnapshotStore()
	now := time.Now().UTC()
	live.Apply("teslamate/cars/7/state", []byte("driving"), false, now)

	server := testServer(store)
	server.mqtt = live
	server.loc = time.UTC
	res, logs := serve(t, server, "/api/matelink/v1/cars/7/snapshot", validTestSecret)
	if res.Code != http.StatusOK || !containsAll(res.Body.String(), "live_mqtt", "driving") {
		t.Fatalf("MQTT fallback response = %d %s", res.Code, res.Body.String())
	}
	assertNoSyntheticCredentials(t, res.Body.String(), logs.String())
}
