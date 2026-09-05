package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

// This mirrors the non-null Android IDs and list-valued history fields in
// DriveModels.kt and ChargeModels.kt. Optional evidence is intentionally
// decoded as pointers so null remains distinguishable from zero.
func TestTaskDCompletedHistoryUsesAndroidIntIDsAndNullableEvidence(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "history-hash"}
	service.memory.registerVehicle(ref)
	start := time.Date(2026, time.August, 30, 8, 0, 0, 0, time.UTC)
	odometerStart, odometerEnd := 100.25, 112.75
	service.memory.addCompletedSession(ref, telemetrySession{ID: "internal-drive", Kind: "drive", StartAt: start, EndAt: timePointer(start.Add(12 * time.Minute)), OdometerStart: &odometerStart, OdometerEnd: &odometerEnd})
	service.memory.addCompletedSession(ref, telemetrySession{ID: "internal-charge", Kind: "charge", StartAt: start, EndAt: timePointer(start.Add(45 * time.Minute))})
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}

	for _, endpoint := range []struct {
		path string
		key  string
		id   string
		list string
	}{
		{"/api/v1/cars/1/drives", "drives", "drive_id", "drive_details"},
		{"/api/v1/cars/1/charges", "charges", "charge_id", "charge_details"},
	} {
		recorder := httptest.NewRecorder()
		a.carResource(recorder, httptest.NewRequest(http.MethodGet, endpoint.path, nil), "user-a", endpoint.path)
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s status=%d body=%s", endpoint.path, recorder.Code, recorder.Body.String())
		}
		var envelope struct {
			Data map[string]json.RawMessage `json:"data"`
		}
		if err := json.Unmarshal(recorder.Body.Bytes(), &envelope); err != nil {
			t.Fatal(err)
		}
		var entries []map[string]json.RawMessage
		if err := json.Unmarshal(envelope.Data[endpoint.key], &entries); err != nil || len(entries) != 1 {
			t.Fatalf("%s entries=%s err=%v", endpoint.path, envelope.Data[endpoint.key], err)
		}
		var id int
		if err := json.Unmarshal(entries[0][endpoint.id], &id); err != nil || id <= 0 {
			t.Fatalf("%s must expose positive Int-safe %s, raw=%s err=%v", endpoint.path, endpoint.id, entries[0][endpoint.id], err)
		}
		var detail []json.RawMessage
		if err := json.Unmarshal(entries[0][endpoint.list], &detail); err != nil || detail == nil {
			t.Fatalf("%s must expose %s as a JSON list, raw=%s err=%v", endpoint.path, endpoint.list, entries[0][endpoint.list], err)
		}
		if endpoint.id == "drive_id" {
			var odometer map[string]*float64
			if err := json.Unmarshal(entries[0]["odometer_details"], &odometer); err != nil {
				t.Fatal(err)
			}
			if odometer["odometer_distance"] == nil || *odometer["odometer_distance"] != 12.5 {
				t.Fatalf("odometer distance=%#v, want nullable 12.5", odometer)
			}
		}
	}
}

func TestTaskDDriveFinalizerClosesPersistedCandidateOnceWithoutRedelivery(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.config.StopDebounce = 10 * time.Second
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "finalize-hash"}
	service.memory.registerVehicle(ref)
	start := time.Date(2026, time.August, 30, 9, 0, 0, 0, time.UTC)
	for _, record := range []telemetryRecord{
		{VINHash: ref.VINHash, FieldName: "VehicleSpeed", Value: float64(12), ObservedAt: start, EventID: "drive"},
		{VINHash: ref.VINHash, FieldName: "VehicleSpeed", Value: float64(0), ObservedAt: start.Add(time.Second), EventID: "stop"},
	} {
		if accepted, err := service.ingest(context.Background(), record); err != nil || accepted != 1 {
			t.Fatalf("ingest %+v accepted=%d err=%v", record, accepted, err)
		}
	}
	// A same-value QoS1 redelivery must neither move the stored candidate nor
	// require a different field to make the finalizer eligible.
	if accepted, err := service.ingest(context.Background(), telemetryRecord{VINHash: ref.VINHash, FieldName: "VehicleSpeed", Value: float64(0), ObservedAt: start.Add(5 * time.Second), EventID: "stop-redelivery"}); err != nil || accepted != 0 {
		t.Fatalf("same-value redelivery accepted=%d err=%v", accepted, err)
	}
	if completed, err := service.finalizeDue(context.Background(), start.Add(10*time.Second)); err != nil || completed != 0 {
		t.Fatalf("premature finalization completed=%d err=%v", completed, err)
	}
	if completed, err := service.finalizeDue(context.Background(), start.Add(11*time.Second)); err != nil || completed != 1 {
		t.Fatalf("due finalization completed=%d err=%v", completed, err)
	}
	if completed, err := service.finalizeDue(context.Background(), start.Add(time.Minute)); err != nil || completed != 0 {
		t.Fatalf("restart-safe repeat completed=%d err=%v", completed, err)
	}
	sessions := service.memory.sessions(ref.UserID, ref.VehicleID, "drive")
	if len(sessions) != 1 || sessions[0].EndAt == nil || !sessions[0].EndAt.Equal(start.Add(11*time.Second)) || sessions[0].CompletionKey == "" {
		t.Fatalf("finalized drive=%#v", sessions)
	}
}

func TestTaskDMQTTCallbackQueuesCopiesAndSignalsBackpressure(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: keyedVINHash(service.vinHashKey, "VIN")}
	service.memory.registerVehicle(ref)
	subscriber := newTelemetrySubscriber(service)
	if subscriber == nil {
		t.Fatal("subscriber missing")
	}
	message := testMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}
	subscriber.message(nil, message)
	if _, exists := service.memory.latestSnapshot(ref.UserID, ref.VehicleID); exists {
		t.Fatal("MQTT callback wrote storage before the ordered worker ran")
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	subscriber.startWorker(ctx)
	t.Cleanup(subscriber.stop)
	deadline := time.Now().Add(time.Second)
	for {
		snapshot, exists := service.memory.latestSnapshot(ref.UserID, ref.VehicleID)
		if exists && snapshot.Fields["Soc"] == float64(42) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("ordered MQTT worker did not persist queued record")
		}
		time.Sleep(time.Millisecond)
	}

	blocked := newTelemetrySubscriber(service)
	blocked.queue = make(chan telemetryMQTTEnvelope, 1)
	blocked.backpressure = time.Millisecond
	if err := blocked.enqueue(context.Background(), "jourvolt/telemetry/VIN/v/Soc", []byte("43"), time.Now().UTC()); err != nil {
		t.Fatalf("first enqueue: %v", err)
	}
	if err := blocked.enqueue(context.Background(), "jourvolt/telemetry/VIN/v/Soc", []byte("44"), time.Now().UTC()); !errors.Is(err, errTelemetryMQTTBackpressure) {
		t.Fatalf("full queue error=%v", err)
	}
	if service.mqttHealthy.Load() {
		t.Fatal("queue backpressure must make telemetry readiness unhealthy")
	}
}

type testMQTTMessage struct {
	topic   string
	payload []byte
}

func (m testMQTTMessage) Duplicate() bool { return false }
func (m testMQTTMessage) Qos() byte       { return 1 }
func (m testMQTTMessage) Retained() bool  { return false }
func (m testMQTTMessage) Topic() string   { return m.topic }
func (m testMQTTMessage) MessageID() uint16 { return 1 }
func (m testMQTTMessage) Payload() []byte { return m.payload }
func (m testMQTTMessage) Ack() {}

var _ mqtt.Message = testMQTTMessage{}

func TestTaskDCurrentChargeUsesOpenTelemetryThenProviderFallback(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: "current-hash", DisplayName: "Tesla"}
	service.memory.registerVehicle(ref)
	start := time.Now().UTC()
	if accepted, err := service.ingest(nil, telemetryRecord{VINHash: ref.VINHash, FieldName: "DetailedChargeState", Value: "Charging", ObservedAt: start, EventID: "charging"}); err != nil || accepted != 1 {
		t.Fatalf("open telemetry charge accepted=%d err=%v", accepted, err)
	}
	a := &app{telemetry: service, provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}}}
	path := "/api/v1/cars/1/charges/current"
	recorder := httptest.NewRecorder()
	a.carResource(recorder, httptest.NewRequest(http.MethodGet, path, nil), "user-a", path)
	if recorder.Code != http.StatusOK || strings.Contains(recorder.Body.String(), "No active charging") || !strings.Contains(recorder.Body.String(), `"charge"`) {
		t.Fatalf("open telemetry current charge=%d %s", recorder.Code, recorder.Body.String())
	}

	charging := "Charging"
	fallback := &app{provider: testProvider{vehicles: map[string][]vehicle{"user-b": {{ID: 2}}}, statuses: map[string]vehicleStatus{"user-b": {ChargingState: &charging}}}}
	path = "/api/v1/cars/2/charges/current"
	recorder = httptest.NewRecorder()
	fallback.carResource(recorder, httptest.NewRequest(http.MethodGet, path, nil), "user-b", path)
	if recorder.Code != http.StatusOK || strings.Contains(recorder.Body.String(), "No active charging") || !strings.Contains(recorder.Body.String(), `"charge"`) {
		t.Fatalf("provider fallback current charge=%d %s", recorder.Code, recorder.Body.String())
	}
}

func TestTaskDComposeSeparatesAPICAFromOfficialKeyAndUsesEphemeralConfig(t *testing.T) {
	for _, composeFile := range []string{"docker-compose.yml", "docker-compose.pilot.ecs.yml"} {
		data, err := os.ReadFile(composeFile)
		if err != nil {
			t.Fatal(err)
		}
		text := string(data)
		apiStart := strings.Index(text, "  jourvolt-dev-api:")
		apiEnd := strings.Index(text[apiStart+1:], "\n  jourvolt-mqtt:")
		if apiStart < 0 || apiEnd < 0 {
			t.Fatalf("%s api service boundary missing", composeFile)
		}
		api := text[apiStart : apiStart+apiEnd+1]
		if strings.Contains(api, "TELEMETRY_CERT_DIR") || !strings.Contains(api, "TELEMETRY_CA_CHAIN_FILE") || !strings.Contains(api, ":/run/secrets/fleet/ca.pem:ro") {
			t.Fatalf("%s API must mount only the CA chain file: %s", composeFile, api)
		}
		if !strings.Contains(text, "fleet-telemetry-config:\n    driver: local\n    driver_opts:\n      type: tmpfs") {
			t.Fatalf("%s rendered telemetry config must be a tmpfs volume", composeFile)
		}
	}
	renderer, err := os.ReadFile("fleet-telemetry/render-server-config.sh")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(renderer), "chmod 0600 /rendered/server_config.json") {
		t.Fatalf("rendered MQTT credential config must be 0600, got: %s", renderer)
	}
}

func TestTaskDHTTPServerDoesNotUseListenAndServe(t *testing.T) {
	data, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "http.ListenAndServe") {
		t.Fatal("main must use an http.Server with explicit timeouts and graceful shutdown")
	}
}
