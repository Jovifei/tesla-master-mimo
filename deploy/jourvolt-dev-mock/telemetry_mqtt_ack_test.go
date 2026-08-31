package main

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

func TestTask1PahoClientUsesManualAckAndPersistentSession(t *testing.T) {
	subscriber := newTelemetrySubscriber(newTelemetryServiceForTest("partner.example.com"))
	options := subscriber.clientOptions()
	if !options.AutoAckDisabled || options.CleanSession || !options.ResumeSubs || !options.AutoReconnect || !options.ConnectRetry {
		t.Fatalf("Paho options = %#v", options)
	}
}

func TestTask1ServiceStartedUsesAtomicState(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	if startedType := reflect.TypeOf(&service.started).Elem(); startedType.PkgPath() != "sync/atomic" || startedType.Name() != "Bool" {
		t.Fatalf("service.started type = %s, want sync/atomic.Bool so readiness and lifecycle writes cannot race", startedType)
	}
}

func TestTask1StaleOnConnectCannotRestoreReadinessAfterConnectionLost(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.mqttPersistence.Store(true)
	subscriber := newTelemetrySubscriber(service)
	client := mqtt.NewClient(mqtt.NewClientOptions())
	subscriber.client = client
	subscriber.clientConnected = func(mqtt.Client) bool { return true }

	subscribeStarted := make(chan struct{})
	allowSubscriptions := make(chan struct{})
	subscriber.subscribe = func(mqtt.Client, string, byte, mqtt.MessageHandler) mqtt.Token {
		select {
		case <-subscribeStarted:
		default:
			close(subscribeStarted)
		}
		<-allowSubscriptions
		return successfulMQTTToken{}
	}
	connectedDone := make(chan struct{})
	go func() {
		subscriber.connected(client)
		close(connectedDone)
	}()
	select {
	case <-subscribeStarted:
	case <-time.After(time.Second):
		t.Fatal("OnConnect did not begin subscriptions")
	}

	subscriber.connectionLost(client, errors.New("connection lost"))
	close(allowSubscriptions)
	select {
	case <-connectedDone:
	case <-time.After(time.Second):
		t.Fatal("stale OnConnect did not return")
	}
	if service.mqttConnected.Load() || service.mqttSubscribed.Load() || service.mqttPersistence.Load() || service.mqttHealthy.Load() {
		t.Fatalf("stale OnConnect restored readiness after connection loss: connected=%t subscribed=%t persistence=%t healthy=%t", service.mqttConnected.Load(), service.mqttSubscribed.Load(), service.mqttPersistence.Load(), service.mqttHealthy.Load())
	}
	if subscriber.ackGate {
		t.Fatal("stale OnConnect reopened the ACK gate after connection loss")
	}
}

func TestTask1ReadinessDistinguishesMQTTConnectionSubscriptionAndPersistence(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.started.Store(true)
	service.persistenceReady = func(context.Context) bool { return true }
	if state := service.telemetryReadinessState(context.Background()); state != "mqtt_not_connected" {
		t.Fatalf("disconnected readiness state = %q", state)
	}
	service.mqttConnected.Store(true)
	if state := service.telemetryReadinessState(context.Background()); state != "mqtt_not_subscribed" {
		t.Fatalf("unsubscribed readiness state = %q", state)
	}
	service.mqttSubscribed.Store(true)
	if state := service.telemetryReadinessState(context.Background()); state != "mqtt_persistence_not_ready" {
		t.Fatalf("unpersisted readiness state = %q", state)
	}
	service.mqttPersistence.Store(true)
	if state := service.telemetryReadinessState(context.Background()); state != "" {
		t.Fatalf("durable readiness state = %q, want ready", state)
	}
}

func TestTask1ReadinessRequiresTelemetryStoreSchema(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.started.Store(true)
	service.mqttConnected.Store(true)
	service.mqttSubscribed.Store(true)
	service.mqttPersistence.Store(true)
	service.persistenceReady = func(context.Context) bool { return false }

	if state := service.telemetryReadinessState(context.Background()); state != "telemetry_store_not_ready" {
		t.Fatalf("readyz telemetry state with unavailable store/schema = %q, want telemetry_store_not_ready", state)
	}

	service.persistenceReady = func(context.Context) bool { return true }
	if state := service.telemetryReadinessState(context.Background()); state != "" {
		t.Fatalf("readyz telemetry state after verified persistence = %q, want ready", state)
	}
}

func TestTask1AcknowledgesOnlyAfterOrderedWorkerDurablyPersists(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: keyedVINHash(service.vinHashKey, "VIN")}
	if err := service.memory.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	subscriber := newTelemetrySubscriber(service)
	configureConnectedTelemetryACKGate(subscriber)
	message := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(ctx)
	subscriber.message(nil, message)

	deadline := time.Now().Add(time.Second)
	for message.ackCount.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if message.ackCount.Load() != 1 {
		t.Fatalf("ack count = %d, want 1 after durable worker success", message.ackCount.Load())
	}
	if snapshot, ok := service.memory.latestSnapshot(ref.UserID, ref.VehicleID); !ok || snapshot.Fields["Soc"] != float64(42) {
		t.Fatalf("worker did not durably persist before ACK: %#v, exists=%t", snapshot, ok)
	}
}

func TestTask1DoesNotAcknowledgeRetryablePersistenceFailure(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.memory = nil
	service.store = nil
	subscriber := newTelemetrySubscriber(service)
	message := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(ctx)
	subscriber.message(nil, message)

	time.Sleep(25 * time.Millisecond)
	if message.ackCount.Load() != 0 {
		t.Fatalf("ack count = %d, want 0 for retryable persistence failure", message.ackCount.Load())
	}
}

func TestTask1AcknowledgesPermanentInvalidMessageAfterSafeCounter(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	subscriber := newTelemetrySubscriber(service)
	configureConnectedTelemetryACKGate(subscriber)
	message := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/UnsupportedField", payload: []byte("42")}

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(ctx)
	subscriber.message(nil, message)

	deadline := time.Now().Add(time.Second)
	for message.ackCount.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if message.ackCount.Load() != 1 {
		t.Fatalf("ack count = %d, want 1 for permanent invalid message", message.ackCount.Load())
	}
	if service.mqttInvalid.Load() != 1 {
		t.Fatalf("permanent invalid counter = %d, want 1", service.mqttInvalid.Load())
	}
}

func TestTask1QueueFullLeavesMessagesUnacknowledged(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.mqttHealthy.Store(true)
	subscriber := newTelemetrySubscriber(service)
	subscriber.queue = make(chan telemetryMQTTEnvelope, 1)
	subscriber.backpressure = time.Millisecond
	first := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}
	second := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("43")}
	t.Cleanup(subscriber.stop)

	subscriber.message(nil, first)
	subscriber.message(nil, second)

	if first.ackCount.Load() != 0 || second.ackCount.Load() != 0 {
		t.Fatalf("queue full must leave messages unacknowledged: first=%d second=%d", first.ackCount.Load(), second.ackCount.Load())
	}
	if service.mqttHealthy.Load() {
		t.Fatal("queue full must expose unhealthy backpressure")
	}
}

func TestTask1OrderedWorkerAcknowledgesInQueueOrder(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	subscriber := newTelemetrySubscriber(service)
	configureConnectedTelemetryACKGate(subscriber)
	var mu sync.Mutex
	processed := make([]string, 0, 3)
	acked := make([]string, 0, 3)
	subscriber.consume = func(_ context.Context, _ string, payload []byte, _ time.Time) telemetryMQTTConsumeResult {
		mu.Lock()
		processed = append(processed, string(payload))
		mu.Unlock()
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTDurable}
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(ctx)
	for _, value := range []string{"1", "2", "3"} {
		value := value
		subscriber.message(nil, &ackCountingMQTTMessage{
			topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte(value),
			ackHook: func() {
				mu.Lock()
				acked = append(acked, value)
				mu.Unlock()
			},
		})
	}
	deadline := time.Now().Add(time.Second)
	for {
		mu.Lock()
		complete := len(acked) == 3
		mu.Unlock()
		if complete || time.Now().After(deadline) {
			break
		}
		time.Sleep(time.Millisecond)
	}
	mu.Lock()
	defer mu.Unlock()
	if strings.Join(processed, ",") != "1,2,3" || strings.Join(acked, ",") != "1,2,3" {
		t.Fatalf("ordered worker processed=%v acked=%v", processed, acked)
	}
}

func TestTask1ReconnectRestoresConnectedSubscribedAndPersistenceHealth(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	subscriber := newTelemetrySubscriber(service)
	client := mqtt.NewClient(mqtt.NewClientOptions())
	subscriber.client = client
	subscriber.clientConnected = func(mqtt.Client) bool { return true }
	subscriptions := 0
	subscriber.subscribe = func(_ mqtt.Client, _ string, _ byte, _ mqtt.MessageHandler) mqtt.Token {
		subscriptions++
		return successfulMQTTToken{}
	}
	options := subscriber.clientOptions()
	options.OnConnectionLost(client, nil)
	if service.mqttConnected.Load() || service.mqttSubscribed.Load() || service.mqttPersistence.Load() || service.mqttHealthy.Load() {
		t.Fatal("connection loss must clear all MQTT readiness health")
	}
	options.OnConnect(client)
	if subscriptions != 4 || !service.mqttConnected.Load() || !service.mqttSubscribed.Load() || service.mqttPersistence.Load() || service.mqttHealthy.Load() {
		t.Fatalf("reconnect must restore connection/subscription but wait for durable persistence: subscriptions=%d connected=%t subscribed=%t persistence=%t healthy=%t", subscriptions, service.mqttConnected.Load(), service.mqttSubscribed.Load(), service.mqttPersistence.Load(), service.mqttHealthy.Load())
	}
	recovered := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42"), acknowledged: make(chan struct{}, 1)}
	subscriber.consume = func(_ context.Context, _ string, _ []byte, _ time.Time) telemetryMQTTConsumeResult {
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTDurable}
	}
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(context.Background())
	subscriber.message(client, recovered)
	select {
	case <-recovered.acknowledged:
	case <-time.After(time.Second):
		t.Fatal("durable message did not restore connected-client ACK gate after reconnect")
	}
	if !service.mqttPersistence.Load() || !service.mqttHealthy.Load() {
		t.Fatalf("durable recovery did not restore readiness: persistence=%t healthy=%t", service.mqttPersistence.Load(), service.mqttHealthy.Load())
	}
}

func TestTask1GracefulShutdownIsBoundedAndLeavesUnfinishedMessageUnacknowledged(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	subscriber := newTelemetrySubscriber(service)
	configureConnectedTelemetryACKGate(subscriber)
	subscriber.shutdownTimeout = 20 * time.Millisecond
	started := make(chan struct{})
	subscriber.consume = func(ctx context.Context, _ string, _ []byte, _ time.Time) telemetryMQTTConsumeResult {
		close(started)
		<-ctx.Done()
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTDurable}
	}
	message := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}
	subscriber.startWorker(context.Background())
	subscriber.message(nil, message)
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("worker did not begin processing")
	}

	start := time.Now()
	subscriber.stop()
	if elapsed := time.Since(start); elapsed > 250*time.Millisecond {
		t.Fatalf("graceful shutdown exceeded bounded timeout: %s", elapsed)
	}
	select {
	case <-subscriber.workerDone:
	case <-time.After(time.Second):
		t.Fatal("worker did not exit after graceful shutdown cancelled it")
	}
	if message.ackCount.Load() != 0 {
		t.Fatalf("unfinished message ACK count = %d, want 0", message.ackCount.Load())
	}
}

func TestTask1ConsumeClassifiesPermanentInvalidAndRetryablePersistence(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	invalid := service.consumeMQTTMessage(context.Background(), "jourvolt/telemetry/VIN/v/UnsupportedField", []byte("42"), time.Now().UTC())
	if invalid.Classification != telemetryMQTTPermanentInvalid || invalid.ErrorClass == "" {
		t.Fatalf("invalid result = %#v", invalid)
	}
	service.memory = nil
	service.store = nil
	retryable := service.consumeMQTTMessage(context.Background(), "jourvolt/telemetry/VIN/v/Soc", []byte("42"), time.Now().UTC())
	if retryable.Classification != telemetryMQTTPersistenceError || retryable.ErrorClass != "persistence_unavailable" {
		t.Fatalf("retryable result = %#v", retryable)
	}
}

func TestTask1UnknownMappedVINIsRetryableAndUnacknowledged(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.mqttHealthy.Store(true)
	subscriber := newTelemetrySubscriber(service)
	configureConnectedTelemetryACKGate(subscriber)
	result := make(chan telemetryMQTTConsumeResult, 1)
	subscriber.consume = func(ctx context.Context, topic string, payload []byte, observedAt time.Time) telemetryMQTTConsumeResult {
		outcome := service.consumeMQTTMessage(ctx, topic, payload, observedAt)
		result <- outcome
		return outcome
	}
	message := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/UNKNOWN/v/Soc", payload: []byte("42")}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(ctx)
	subscriber.message(nil, message)

	select {
	case outcome := <-result:
		if outcome.Classification != telemetryMQTTUnknownMapping || outcome.Persistence != telemetryPersistenceUnknownMapping || outcome.ErrorClass != "unknown_mapping" {
			t.Fatalf("unknown mapping outcome = %#v", outcome)
		}
	case <-time.After(time.Second):
		t.Fatal("worker did not classify unknown mapping")
	}
	if message.ackCount.Load() != 0 || service.mqttHealthy.Load() {
		t.Fatalf("unknown mapping must remain unacknowledged and unhealthy: ack=%d healthy=%t", message.ackCount.Load(), service.mqttHealthy.Load())
	}
}

func TestTask1ControlTopicZeroRowStatusUpdateIsRetryableAndUnacknowledged(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	vinHash := keyedVINHash(service.vinHashKey, "VIN")
	if err := service.memory.registerVehicle(telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: vinHash}); err != nil {
		t.Fatal(err)
	}
	if mapping := service.telemetryMapping(context.Background(), vinHash); mapping.Classification != telemetryPersistenceMappingFound {
		t.Fatalf("initial mapping = %#v", mapping)
	}
	service.memory.mu.Lock()
	delete(service.memory.vehicles, vinHash)
	service.memory.mu.Unlock()

	statusUpdate := service.updateStatusForVIN(context.Background(), vinHash, "collecting")
	if statusUpdate.Classification != telemetryPersistenceUnknownMapping || statusUpdate.ErrorClass != "unknown_mapping" {
		t.Fatalf("zero-row status update result = %#v", statusUpdate)
	}

	subscriber := newTelemetrySubscriber(service)
	configureConnectedTelemetryACKGate(subscriber)
	result := make(chan telemetryMQTTConsumeResult, 1)
	subscriber.consume = func(ctx context.Context, topic string, payload []byte, observedAt time.Time) telemetryMQTTConsumeResult {
		outcome := service.consumeMQTTMessage(ctx, topic, payload, observedAt)
		result <- outcome
		return outcome
	}
	message := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/connectivity", payload: []byte(`{"Status":"online"}`)}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(ctx)
	subscriber.message(nil, message)

	select {
	case outcome := <-result:
		if outcome.Classification != telemetryMQTTUnknownMapping || outcome.Persistence != telemetryPersistenceUnknownMapping || outcome.ErrorClass != "unknown_mapping" {
			t.Fatalf("control-topic outcome = %#v", outcome)
		}
	case <-time.After(time.Second):
		t.Fatal("worker did not classify zero-row control update")
	}
	if message.ackCount.Load() != 0 {
		t.Fatalf("ack count = %d, want 0 for retryable zero-row control update", message.ackCount.Load())
	}
}

func TestTask1AcknowledgesDurableMappedDuplicate(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: keyedVINHash(service.vinHashKey, "VIN")}
	if err := service.memory.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	observedAt := time.Date(2026, time.August, 31, 1, 2, 3, 0, time.UTC)
	if first := service.consumeMQTTMessage(context.Background(), "jourvolt/telemetry/VIN/v/Soc", []byte("42"), observedAt); first.Classification != telemetryMQTTDurable {
		t.Fatalf("initial mapped persistence outcome = %#v", first)
	}
	subscriber := newTelemetrySubscriber(service)
	configureConnectedTelemetryACKGate(subscriber)
	result := make(chan telemetryMQTTConsumeResult, 1)
	subscriber.consume = func(ctx context.Context, topic string, payload []byte, timestamp time.Time) telemetryMQTTConsumeResult {
		outcome := service.consumeMQTTMessage(ctx, topic, payload, timestamp)
		result <- outcome
		return outcome
	}
	message := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(ctx)
	if err := subscriber.enqueueWithAck(context.Background(), message.topic, message.payload, observedAt, message.Ack); err != nil {
		t.Fatal(err)
	}

	select {
	case outcome := <-result:
		if outcome.Classification != telemetryMQTTDurableDuplicate || outcome.Persistence != telemetryPersistenceDurableDuplicate || outcome.ErrorClass != "durable_duplicate" {
			t.Fatalf("duplicate persistence outcome = %#v", outcome)
		}
	case <-time.After(time.Second):
		t.Fatal("worker did not process mapped duplicate")
	}
	deadline := time.Now().Add(time.Second)
	for message.ackCount.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if message.ackCount.Load() != 1 {
		t.Fatalf("durable duplicate ACK count = %d, want 1", message.ackCount.Load())
	}
}

func TestTask1CancelledPersistenceContextIsRetryableAndUnacknowledged(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	ref := telemetryVehicleRef{UserID: "user-a", VehicleID: 1, VINHash: keyedVINHash(service.vinHashKey, "VIN")}
	if err := service.memory.registerVehicle(ref); err != nil {
		t.Fatal(err)
	}
	service.mqttHealthy.Store(true)
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	subscriber := newTelemetrySubscriber(service)
	result := make(chan telemetryMQTTConsumeResult, 1)
	subscriber.consume = func(_ context.Context, topic string, payload []byte, observedAt time.Time) telemetryMQTTConsumeResult {
		outcome := service.consumeMQTTMessage(cancelled, topic, payload, observedAt)
		result <- outcome
		return outcome
	}
	message := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(context.Background())
	subscriber.message(nil, message)

	select {
	case outcome := <-result:
		if outcome.Classification != telemetryMQTTContextCancelled || outcome.Persistence != telemetryPersistenceError || outcome.ErrorClass != "context_cancelled" {
			t.Fatalf("cancelled context outcome = %#v", outcome)
		}
	case <-time.After(time.Second):
		t.Fatal("worker did not process cancelled context")
	}
	if message.ackCount.Load() != 0 || service.mqttHealthy.Load() {
		t.Fatalf("cancelled persistence must remain unacknowledged and unhealthy: ack=%d healthy=%t", message.ackCount.Load(), service.mqttHealthy.Load())
	}
}

func TestTask1DatabasePersistenceErrorIsRetryableAndUnacknowledged(t *testing.T) {
	pool := canceledTestPool(t)
	t.Cleanup(pool.Close)
	service := newTelemetryServiceForTest("partner.example.com")
	service.memory = nil
	service.store = &store{pool: pool}
	service.mqttHealthy.Store(true)
	subscriber := newTelemetrySubscriber(service)
	result := make(chan telemetryMQTTConsumeResult, 1)
	subscriber.consume = func(ctx context.Context, topic string, payload []byte, observedAt time.Time) telemetryMQTTConsumeResult {
		outcome := service.consumeMQTTMessage(ctx, topic, payload, observedAt)
		result <- outcome
		return outcome
	}
	message := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(context.Background())
	subscriber.message(nil, message)

	select {
	case outcome := <-result:
		if outcome.Classification != telemetryMQTTPersistenceError || outcome.Persistence != telemetryPersistenceError || outcome.ErrorClass != "persistence_error" {
			t.Fatalf("database error outcome = %#v", outcome)
		}
	case <-time.After(time.Second):
		t.Fatal("worker did not return database persistence error")
	}
	if message.ackCount.Load() != 0 || service.mqttHealthy.Load() {
		t.Fatalf("database persistence error must remain unacknowledged and unhealthy: ack=%d healthy=%t", message.ackCount.Load(), service.mqttHealthy.Load())
	}
}

func TestTask1DisconnectBeforeWorkerAckLeavesMessageUnacknowledged(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	subscriber := newTelemetrySubscriber(service)
	configureConnectedTelemetryACKGate(subscriber)
	processed := make(chan struct{})
	subscriber.consume = func(_ context.Context, _ string, _ []byte, _ time.Time) telemetryMQTTConsumeResult {
		subscriber.connectionLost(nil, nil)
		close(processed)
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTDurable}
	}
	message := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(context.Background())
	subscriber.message(nil, message)
	select {
	case <-processed:
	case <-time.After(time.Second):
		t.Fatal("worker did not process the message")
	}
	deadline := time.Now().Add(time.Second)
	for message.ackCount.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if message.ackCount.Load() != 0 {
		t.Fatalf("disconnect-before-ack count = %d, want 0", message.ackCount.Load())
	}
}

func TestTask1PahoInstallsDefaultPublishHandlerBeforeConnect(t *testing.T) {
	subscriber := newTelemetrySubscriber(newTelemetryServiceForTest("partner.example.com"))
	options := subscriber.clientOptions()
	if options.DefaultPublishHandler == nil {
		t.Fatal("persistent-session client must preinstall the default publish handler")
	}
}

func TestTask1QueuePressureReturnsImmediatelyWithoutAcknowledging(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.mqttHealthy.Store(true)
	subscriber := newTelemetrySubscriber(service)
	subscriber.queue = make(chan telemetryMQTTEnvelope, 1)
	subscriber.backpressure = 150 * time.Millisecond
	if err := subscriber.enqueue(context.Background(), "jourvolt/telemetry/VIN/v/Soc", []byte("41"), time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	message := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}
	returned := make(chan struct{})
	started := time.Now()
	go func() {
		subscriber.message(nil, message)
		close(returned)
	}()
	select {
	case <-returned:
		if elapsed := time.Since(started); elapsed > 50*time.Millisecond {
			t.Fatalf("queue-pressure callback blocked for %s", elapsed)
		}
	case <-time.After(75 * time.Millisecond):
		t.Fatal("queue-pressure callback must return without waiting for backpressure timeout")
	}
	if message.ackCount.Load() != 0 || service.mqttHealthy.Load() {
		t.Fatalf("queue pressure must leave unacknowledged and unhealthy: ack=%d healthy=%t", message.ackCount.Load(), service.mqttHealthy.Load())
	}
	t.Cleanup(subscriber.stop)
}

func TestTask1RetryableFailureClearsPersistenceUntilDurableRecovery(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.started.Store(true)
	service.mqttConnected.Store(true)
	service.mqttSubscribed.Store(true)
	service.mqttPersistence.Store(true)
	service.mqttHealthy.Store(true)
	subscriber := newTelemetrySubscriber(service)
	subscriber.client = mqtt.NewClient(mqtt.NewClientOptions())
	subscriber.clientConnected = func(mqtt.Client) bool { return true }
	subscriber.ackGate = true
	results := []telemetryMQTTConsumeResult{
		{Classification: telemetryMQTTPersistenceError, ErrorClass: "persistence_error"},
		{Classification: telemetryMQTTDurable},
	}
	subscriber.consume = func(_ context.Context, _ string, _ []byte, _ time.Time) telemetryMQTTConsumeResult {
		result := results[0]
		results = results[1:]
		return result
	}
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(context.Background())
	subscriber.message(nil, &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("41")})
	deadline := time.Now().Add(time.Second)
	for service.mqttPersistence.Load() && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if service.mqttPersistence.Load() || service.mqttHealthy.Load() {
		t.Fatal("retryable failure must clear MQTT persistence health")
	}
	recovered := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}
	subscriber.message(nil, recovered)
	for (!service.mqttPersistence.Load() || recovered.ackCount.Load() != 1) && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if !service.mqttPersistence.Load() || !service.mqttHealthy.Load() || recovered.ackCount.Load() != 1 {
		t.Fatalf("durable recovery must restore persistence and connected-client ACK gate: persistence=%t healthy=%t ack=%d", service.mqttPersistence.Load(), service.mqttHealthy.Load(), recovered.ackCount.Load())
	}
}

func TestTask1DurableRecoveryRestoresPersistenceBeforeConnectedClientAck(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	service.started.Store(true)
	service.mqttConnected.Store(true)
	service.mqttSubscribed.Store(true)
	subscriber := newTelemetrySubscriber(service)
	subscriber.client = mqtt.NewClient(mqtt.NewClientOptions())
	subscriber.clientConnected = func(mqtt.Client) bool { return true }
	subscriber.consume = func(_ context.Context, _ string, _ []byte, _ time.Time) telemetryMQTTConsumeResult {
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTDurable}
	}
	recovered := &ackCountingMQTTMessage{topic: "jourvolt/telemetry/VIN/v/Soc", payload: []byte("42")}
	t.Cleanup(subscriber.stop)
	subscriber.startWorker(context.Background())
	subscriber.message(nil, recovered)

	deadline := time.Now().Add(time.Second)
	for !service.mqttPersistence.Load() && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if !service.mqttPersistence.Load() || !service.mqttHealthy.Load() {
		t.Fatalf("durably persisted message must restore readiness before ACK is permitted: persistence=%t healthy=%t", service.mqttPersistence.Load(), service.mqttHealthy.Load())
	}
	if recovered.ackCount.Load() != 0 {
		t.Fatalf("message ACK count = %d, want 0 without connected-client ACK gate", recovered.ackCount.Load())
	}
}

func TestTask1CancelledStartDoesNotStopQueueOrMarkServiceStarted(t *testing.T) {
	service := newTelemetryServiceForTest("partner.example.com")
	subscriber := newTelemetrySubscriber(service)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	err := subscriber.start(ctx)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled start error = %v, want context.Canceled", err)
	}
	if subscriber.stopped || service.started.Load() {
		t.Fatalf("cancelled start must leave subscriber reusable: stopped=%t started=%t", subscriber.stopped, service.started.Load())
	}
}

type ackCountingMQTTMessage struct {
	topic        string
	payload      []byte
	ackCount     atomic.Int32
	ackHook      func()
	acknowledged chan struct{}
}

func (m *ackCountingMQTTMessage) Duplicate() bool   { return false }
func (m *ackCountingMQTTMessage) Qos() byte         { return 1 }
func (m *ackCountingMQTTMessage) Retained() bool    { return false }
func (m *ackCountingMQTTMessage) Topic() string     { return m.topic }
func (m *ackCountingMQTTMessage) MessageID() uint16 { return 1 }
func (m *ackCountingMQTTMessage) Payload() []byte   { return m.payload }
func (m *ackCountingMQTTMessage) Ack() {
	m.ackCount.Add(1)
	if m.acknowledged != nil {
		select {
		case m.acknowledged <- struct{}{}:
		default:
		}
	}
	if m.ackHook != nil {
		m.ackHook()
	}
}

func configureConnectedTelemetryACKGate(subscriber *telemetrySubscriber) {
	subscriber.service.started.Store(true)
	subscriber.service.mqttConnected.Store(true)
	subscriber.service.mqttSubscribed.Store(true)
	subscriber.client = mqtt.NewClient(mqtt.NewClientOptions())
	subscriber.clientConnected = func(mqtt.Client) bool { return true }
	subscriber.ackGate = true
}

type successfulMQTTToken struct{}

func (successfulMQTTToken) Wait() bool                     { return true }
func (successfulMQTTToken) WaitTimeout(time.Duration) bool { return true }
func (successfulMQTTToken) Done() <-chan struct{} {
	done := make(chan struct{})
	close(done)
	return done
}
func (successfulMQTTToken) Error() error { return nil }
