package main

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

const (
	telemetryMQTTQueueCapacity   = 256
	telemetryMQTTBackpressure    = 250 * time.Millisecond
	telemetryMQTTProcessTimeout  = 5 * time.Second
	telemetryMQTTShutdownTimeout = 10 * time.Second
)

var errTelemetryMQTTBackpressure = errors.New("telemetry MQTT queue backpressure")

type telemetryMQTTConsumeClassification string

const (
	telemetryMQTTDurable          telemetryMQTTConsumeClassification = "durable"
	telemetryMQTTDurableDuplicate telemetryMQTTConsumeClassification = "durable_duplicate"
	telemetryMQTTUnknownMapping   telemetryMQTTConsumeClassification = "unknown_mapping"
	telemetryMQTTPermanentInvalid telemetryMQTTConsumeClassification = "permanent_invalid"
	telemetryMQTTContextCancelled telemetryMQTTConsumeClassification = "context_cancelled"
	telemetryMQTTPersistenceError telemetryMQTTConsumeClassification = "persistence_error"
)

type telemetryPersistenceClassification string

const (
	telemetryPersistenceMappingFound     telemetryPersistenceClassification = "mapping_found"
	telemetryPersistenceDurable          telemetryPersistenceClassification = "durable"
	telemetryPersistenceDurableDuplicate telemetryPersistenceClassification = "durable_duplicate"
	telemetryPersistenceUnknownMapping   telemetryPersistenceClassification = "unknown_mapping"
	telemetryPersistencePermanentInvalid telemetryPersistenceClassification = "permanent_invalid"
	telemetryPersistenceError            telemetryPersistenceClassification = "persistence_error"
)

type telemetryPersistenceResult struct {
	Classification telemetryPersistenceClassification
	ErrorClass     string
}

type telemetryMQTTConsumeResult struct {
	Classification telemetryMQTTConsumeClassification
	Persistence    telemetryPersistenceClassification
	ErrorClass     string
}

type telemetryMQTTEnvelope struct {
	topic      string
	payload    []byte
	observedAt time.Time
	ack        func()
	generation uint64
}

type telemetrySubscriber struct {
	service           *telemetryService
	config            *telemetryConfig
	client            mqtt.Client
	clientGeneration  uint64
	ackGate           bool
	clientConnected   func(mqtt.Client) bool
	queue             chan telemetryMQTTEnvelope
	backpressure      time.Duration
	consume           func(context.Context, string, []byte, time.Time) telemetryMQTTConsumeResult
	subscribe         func(mqtt.Client, string, byte, mqtt.MessageHandler) mqtt.Token
	shutdownTimeout   time.Duration
	mu                sync.RWMutex
	stopped           bool
	workerOnce        sync.Once
	stopOnce          sync.Once
	acceptContext     context.Context
	acceptCancel      context.CancelFunc
	workerCancel      context.CancelFunc
	workerDone        chan struct{}
	workerWatcherDone chan struct{}
	leaveUnacked      atomic.Bool
}

func newTelemetrySubscriber(service *telemetryService) *telemetrySubscriber {
	if service == nil || service.config == nil {
		return nil
	}
	return &telemetrySubscriber{
		service: service, config: service.config, queue: make(chan telemetryMQTTEnvelope, telemetryMQTTQueueCapacity),
		backpressure: telemetryMQTTBackpressure, shutdownTimeout: telemetryMQTTShutdownTimeout,
		consume: service.consumeMQTTMessage,
	}
}

func (s *telemetrySubscriber) start(ctx context.Context) error {
	if s == nil || s.service == nil || s.config == nil {
		return nil
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	options := s.clientOptions()
	client := mqtt.NewClient(options)
	s.mu.Lock()
	if err := ctx.Err(); err != nil || s.stopped {
		s.mu.Unlock()
		if err != nil {
			return err
		}
		return errors.New("telemetry MQTT subscriber stopped")
	}
	s.client = client
	s.clientGeneration++
	s.ackGate = false
	s.mu.Unlock()
	if err := ctx.Err(); err != nil {
		s.abortStart(client)
		return err
	}
	token := client.Connect()
	timer := time.NewTimer(10 * time.Second)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		s.abortStart(client)
		return ctx.Err()
	case <-timer.C:
		s.stop()
		return errors.New("telemetry MQTT connection timed out")
	case <-token.Done():
	}
	if err := token.Error(); err != nil {
		s.stop()
		return err
	}
	if err := ctx.Err(); err != nil {
		s.abortStart(client)
		return err
	}
	s.startWorker(ctx)
	s.mu.Lock()
	if err := ctx.Err(); err != nil || s.stopped || s.client != client {
		s.mu.Unlock()
		s.abortStart(client)
		if err != nil {
			return err
		}
		return errors.New("telemetry MQTT startup interrupted")
	}
	s.service.started.Store(true)
	s.mu.Unlock()
	return nil
}

func (s *telemetrySubscriber) abortStart(client mqtt.Client) {
	if s == nil || s.service == nil {
		return
	}
	s.mu.Lock()
	if s.client == client {
		s.ackGate = false
		s.clientGeneration++
		s.client = nil
	}
	s.service.started.Store(false)
	s.mu.Unlock()
	if client != nil && client.IsConnected() {
		client.Disconnect(250)
	}
}

func (s *telemetrySubscriber) clientOptions() *mqtt.ClientOptions {
	options := mqtt.NewClientOptions().
		AddBroker(s.config.MQTTURL).
		SetClientID("jourvolt-telemetry-consumer").
		SetCleanSession(false).
		SetResumeSubs(true).
		SetAutoAckDisabled(true).
		SetAutoReconnect(true).
		SetConnectRetry(true).
		SetConnectRetryInterval(10 * time.Second).
		SetKeepAlive(30 * time.Second).
		SetDefaultPublishHandler(s.message).
		SetConnectionLostHandler(s.connectionLost).
		SetOnConnectHandler(s.connected)
	if s.config.MQTTUsername != "" {
		options.SetUsername(s.config.MQTTUsername).SetPassword(s.config.MQTTPassword)
	}
	return options
}

func (s *telemetrySubscriber) connectionLost(client mqtt.Client, _ error) {
	if s == nil || s.service == nil {
		return
	}
	s.mu.Lock()
	if client != nil && s.client != client {
		s.mu.Unlock()
		return
	}
	s.ackGate = false
	s.clientGeneration++
	s.service.mqttConnected.Store(false)
	s.service.mqttSubscribed.Store(false)
	s.service.mqttPersistence.Store(false)
	s.service.mqttHealthy.Store(false)
	s.mu.Unlock()
}

func (s *telemetrySubscriber) connected(client mqtt.Client) {
	if s == nil || s.service == nil || s.config == nil {
		return
	}
	s.mu.RLock()
	generation := s.clientGeneration
	current := !s.stopped && s.client == client
	s.mu.RUnlock()
	if !current {
		return
	}

	base := strings.Trim(s.config.TopicBase, "/")
	topics := []string{base + "/+/v/#", base + "/+/connectivity", base + "/+/alerts/#", base + "/+/errors/#"}
	subscribed := true
	for _, topic := range topics {
		var token mqtt.Token
		if s.subscribe != nil {
			token = s.subscribe(client, topic, 1, s.message)
		} else if client != nil {
			token = client.Subscribe(topic, 1, s.message)
		}
		if token == nil || !token.WaitTimeout(5*time.Second) || token.Error() != nil {
			subscribed = false
		}
	}
	s.mu.Lock()
	if s.stopped || s.client != client || s.clientGeneration != generation || !s.clientIsConnectedLocked() {
		s.mu.Unlock()
		return
	}
	s.ackGate = subscribed
	s.service.mqttConnected.Store(true)
	s.service.mqttSubscribed.Store(subscribed)
	s.service.mqttPersistence.Store(false)
	s.service.mqttHealthy.Store(false)
	s.mu.Unlock()
}

func (s *telemetrySubscriber) clientIsConnectedLocked() bool {
	if s.client == nil {
		return false
	}
	if s.clientConnected != nil {
		return s.clientConnected(s.client)
	}
	return s.client.IsConnected()
}

func (s *telemetrySubscriber) acknowledge(envelope telemetryMQTTEnvelope) bool {
	if s == nil || envelope.ack == nil {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stopped || s.leaveUnacked.Load() || !s.ackGate || envelope.generation != s.clientGeneration || !s.clientIsConnectedLocked() {
		return false
	}
	envelope.ack()
	return true
}

func (s *telemetrySubscriber) markRetryableFailure() {
	if s == nil || s.service == nil {
		return
	}
	s.service.mqttPersistence.Store(false)
	s.service.mqttHealthy.Store(false)
}

func (s *telemetrySubscriber) markDurablePersistence() {
	if s == nil || s.service == nil {
		return
	}
	s.service.mqttPersistence.Store(true)
	if s.service.mqttConnected.Load() && s.service.mqttSubscribed.Load() {
		s.service.mqttHealthy.Store(true)
	}
}

func (s *telemetryService) telemetryReadinessState(ctx context.Context) string {
	if s == nil {
		return ""
	}
	switch {
	case !s.started.Load() || !s.mqttConnected.Load():
		return "mqtt_not_connected"
	case !s.mqttSubscribed.Load():
		return "mqtt_not_subscribed"
	case !s.telemetryStoreReady(ctx):
		return "telemetry_store_not_ready"
	case !s.mqttPersistence.Load():
		return "mqtt_persistence_not_ready"
	default:
		return ""
	}
}

func (s *telemetryService) telemetryStoreReady(ctx context.Context) bool {
	if s == nil {
		return false
	}
	if s.persistenceReady != nil {
		return s.persistenceReady(ctx)
	}
	if s.memory != nil {
		return true
	}
	if s.store == nil || s.store.pool == nil {
		return false
	}
	var ready bool
	err := s.store.pool.QueryRow(ctx, `SELECT to_regclass('public.jourvolt_telemetry_vehicle_keys') IS NOT NULL`).Scan(&ready)
	return err == nil && ready
}

func (s *telemetrySubscriber) stop() {
	if s == nil {
		return
	}
	s.stopOnce.Do(func() {
		s.mu.Lock()
		s.stopped = true
		s.ackGate = false
		s.clientGeneration++
		close(s.queue)
		acceptCancel, workerCancel, workerDone := s.acceptCancel, s.workerCancel, s.workerDone
		client := s.client
		timeout := s.shutdownTimeout
		s.mu.Unlock()
		if acceptCancel != nil {
			acceptCancel()
		}
		if timeout <= 0 {
			timeout = telemetryMQTTShutdownTimeout
		}
		if workerDone != nil {
			timer := time.NewTimer(timeout)
			select {
			case <-workerDone:
				if !timer.Stop() {
					select {
					case <-timer.C:
					default:
					}
				}
			case <-timer.C:
				s.leaveUnacked.Store(true)
				if workerCancel != nil {
					workerCancel()
				}
			}
		}
		if client != nil && client.IsConnected() {
			client.Disconnect(250)
		}
		s.service.started.Store(false)
	})
}

func (s *telemetrySubscriber) message(_ mqtt.Client, message mqtt.Message) {
	if s == nil || s.service == nil {
		return
	}
	s.mu.RLock()
	acceptContext := s.acceptContext
	s.mu.RUnlock()
	if acceptContext == nil {
		acceptContext = context.Background()
	}
	_ = s.enqueueWithAck(acceptContext, message.Topic(), message.Payload(), time.Now().UTC(), message.Ack)
}

func (s *telemetrySubscriber) startWorker(ctx context.Context) {
	if s == nil {
		return
	}
	s.workerOnce.Do(func() {
		if ctx == nil {
			ctx = context.Background()
		}
		acceptContext, acceptCancel := context.WithCancel(context.Background())
		workerContext, workerCancel := context.WithCancel(context.Background())
		workerDone := make(chan struct{})
		workerWatcherDone := make(chan struct{})
		s.mu.Lock()
		if s.stopped {
			s.mu.Unlock()
			acceptCancel()
			workerCancel()
			close(workerDone)
			close(workerWatcherDone)
			return
		}
		s.acceptContext, s.acceptCancel = acceptContext, acceptCancel
		s.workerCancel, s.workerDone, s.workerWatcherDone = workerCancel, workerDone, workerWatcherDone
		s.mu.Unlock()
		go func() {
			defer close(workerDone)
			for envelope := range s.queue {
				processCtx, cancel := context.WithTimeout(workerContext, telemetryMQTTProcessTimeout)
				consume := s.consume
				if consume == nil {
					consume = s.service.consumeMQTTMessage
				}
				result := consume(processCtx, envelope.topic, envelope.payload, envelope.observedAt)
				cancel()
				switch result.Classification {
				case telemetryMQTTDurable, telemetryMQTTDurableDuplicate:
					s.markDurablePersistence()
					_ = s.acknowledge(envelope)
				case telemetryMQTTPermanentInvalid:
					s.service.mqttInvalid.Add(1)
					_ = s.acknowledge(envelope)
				default:
					s.markRetryableFailure()
				}
			}
		}()
		go func() {
			defer close(workerWatcherDone)
			select {
			case <-ctx.Done():
				s.stop()
			case <-workerDone:
			}
		}()
	})
}

func (s *telemetrySubscriber) enqueue(ctx context.Context, topic string, payload []byte, observedAt time.Time) error {
	return s.enqueueWithAck(ctx, topic, payload, observedAt, nil)
}

func (s *telemetrySubscriber) enqueueWithAck(ctx context.Context, topic string, payload []byte, observedAt time.Time, ack func()) error {
	if s == nil || s.service == nil {
		return errors.New("telemetry MQTT subscriber is unavailable")
	}
	if ctx != nil {
		if err := ctx.Err(); err != nil {
			s.markRetryableFailure()
			return err
		}
	}
	copyPayload := append([]byte(nil), payload...)
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.stopped {
		return errors.New("telemetry MQTT subscriber stopped")
	}
	envelope := telemetryMQTTEnvelope{topic: topic, payload: copyPayload, observedAt: observedAt, ack: ack, generation: s.clientGeneration}
	select {
	case s.queue <- envelope:
		return nil
	default:
		s.markRetryableFailure()
		return errTelemetryMQTTBackpressure
	}
}

func (s *telemetryService) consumeMQTTMessage(ctx context.Context, topic string, payload []byte, observedAt time.Time) telemetryMQTTConsumeResult {
	return s.consumeMQTTMessageWithSequence(ctx, topic, payload, observedAt, s.receiveSequence.Add(1))
}

func (s *telemetryService) consumeMQTTMessageWithSequence(ctx context.Context, topic string, payload []byte, observedAt time.Time, receiveSequence uint64) telemetryMQTTConsumeResult {
	if s == nil || s.config == nil || (s.memory == nil && (s.store == nil || s.store.pool == nil)) {
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTPersistenceError, Persistence: telemetryPersistenceError, ErrorClass: "persistence_unavailable"}
	}
	if err := ctx.Err(); err != nil {
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTContextCancelled, Persistence: telemetryPersistenceError, ErrorClass: "context_cancelled"}
	}
	if strings.Contains(topic, "/v/") {
		record, err := parseTelemetryPayloadWithKey(s.vinHashKey, s.config.TopicBase, topic, payload, observedAt)
		if err != nil {
			return telemetryMQTTConsumeResult{Classification: telemetryMQTTPermanentInvalid, Persistence: telemetryPersistencePermanentInvalid, ErrorClass: "invalid_telemetry_message"}
		}
		record.ReceiveSequence = receiveSequence
		return mqttConsumeResultFromPersistence(s.persistMQTTRecord(ctx, record))
	}
	return s.consumeTelemetryControlMessage(ctx, topic, payload)
}

func mqttConsumeResultFromPersistence(result telemetryPersistenceResult) telemetryMQTTConsumeResult {
	switch result.Classification {
	case telemetryPersistenceDurable:
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTDurable, Persistence: result.Classification, ErrorClass: result.ErrorClass}
	case telemetryPersistenceDurableDuplicate:
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTDurableDuplicate, Persistence: result.Classification, ErrorClass: result.ErrorClass}
	case telemetryPersistenceUnknownMapping:
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTUnknownMapping, Persistence: result.Classification, ErrorClass: result.ErrorClass}
	case telemetryPersistencePermanentInvalid:
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTPermanentInvalid, Persistence: result.Classification, ErrorClass: result.ErrorClass}
	default:
		classification := telemetryMQTTPersistenceError
		if result.ErrorClass == "context_cancelled" {
			classification = telemetryMQTTContextCancelled
		}
		return telemetryMQTTConsumeResult{Classification: classification, Persistence: result.Classification, ErrorClass: result.ErrorClass}
	}
}

func (s *telemetryService) persistMQTTRecord(ctx context.Context, record telemetryRecord) telemetryPersistenceResult {
	if err := ctx.Err(); err != nil {
		return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "context_cancelled"}
	}
	if s.memory != nil {
		s.memory.mu.Lock()
		defer s.memory.mu.Unlock()
		if len(s.memory.vehicles[record.VINHash]) == 0 {
			return telemetryPersistenceResult{Classification: telemetryPersistenceUnknownMapping, ErrorClass: "unknown_mapping"}
		}
		accepted, err := s.memory.ingestLocked(record, s.stopDebounceOrDefault())
		if err != nil {
			return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "persistence_error"}
		}
		if accepted == 0 {
			return telemetryPersistenceResult{Classification: telemetryPersistenceDurableDuplicate, ErrorClass: "durable_duplicate"}
		}
		return telemetryPersistenceResult{Classification: telemetryPersistenceDurable, ErrorClass: "durable"}
	}
	if s.store == nil || s.store.pool == nil {
		return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "persistence_unavailable"}
	}
	result, err := s.ingestPostgresWithMapping(ctx, record)
	if err != nil {
		return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "persistence_error"}
	}
	if !result.Mapped {
		return telemetryPersistenceResult{Classification: telemetryPersistenceUnknownMapping, ErrorClass: "unknown_mapping"}
	}
	if result.Accepted == 0 {
		return telemetryPersistenceResult{Classification: telemetryPersistenceDurableDuplicate, ErrorClass: "durable_duplicate"}
	}
	return telemetryPersistenceResult{Classification: telemetryPersistenceDurable, ErrorClass: "durable"}
}

func (s *telemetryService) telemetryMapping(ctx context.Context, vinHash string) telemetryPersistenceResult {
	if err := ctx.Err(); err != nil {
		return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "context_cancelled"}
	}
	if s.memory != nil {
		s.memory.mu.Lock()
		mapped := len(s.memory.vehicles[vinHash]) > 0
		s.memory.mu.Unlock()
		if !mapped {
			return telemetryPersistenceResult{Classification: telemetryPersistenceUnknownMapping, ErrorClass: "unknown_mapping"}
		}
		return telemetryPersistenceResult{Classification: telemetryPersistenceMappingFound, ErrorClass: "mapped"}
	}
	if s.store == nil || s.store.pool == nil {
		return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "persistence_unavailable"}
	}
	var mapped bool
	if err := s.store.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM jourvolt_telemetry_vehicle_keys WHERE vin_hash=$1)`, vinHash).Scan(&mapped); err != nil {
		return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "persistence_error"}
	}
	if !mapped {
		return telemetryPersistenceResult{Classification: telemetryPersistenceUnknownMapping, ErrorClass: "unknown_mapping"}
	}
	return telemetryPersistenceResult{Classification: telemetryPersistenceMappingFound, ErrorClass: "mapped"}
}

func (s *telemetryService) consumeTelemetryControlMessage(ctx context.Context, topic string, payload []byte) telemetryMQTTConsumeResult {
	if s == nil || s.config == nil || len(payload) == 0 || len(payload) > maxTelemetryPayloadBytes {
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTPermanentInvalid, ErrorClass: "invalid_control_message"}
	}
	baseParts := strings.Split(strings.Trim(s.config.TopicBase, "/"), "/")
	parts := strings.Split(strings.Trim(topic, "/"), "/")
	if len(parts) < len(baseParts)+2 || strings.Join(parts[:len(baseParts)], "/") != strings.Trim(s.config.TopicBase, "/") || parts[len(baseParts)] == "" {
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTPermanentInvalid, ErrorClass: "invalid_control_topic"}
	}
	vinHash := keyedVINHash(s.vinHashKey, parts[len(baseParts)])
	kind := parts[len(baseParts)+1]
	switch kind {
	case "connectivity":
		var value struct {
			Status string `json:"Status"`
		}
		if err := json.Unmarshal(payload, &value); err != nil || value.Status == "" {
			return telemetryMQTTConsumeResult{Classification: telemetryMQTTPermanentInvalid, ErrorClass: "invalid_connectivity_payload"}
		}
		status := "collecting"
		if strings.EqualFold(value.Status, "offline") || strings.EqualFold(value.Status, "disconnected") {
			status = "waiting_vehicle"
		}
		return mqttConsumeResultFromPersistence(s.updateStatusForVIN(ctx, vinHash, status))
	case "alerts", "errors":
		return mqttConsumeResultFromPersistence(s.updateStatusForVIN(ctx, vinHash, "telemetry_error"))
	default:
		return telemetryMQTTConsumeResult{Classification: telemetryMQTTPermanentInvalid, ErrorClass: "unsupported_control_topic"}
	}
}

func (s *telemetryService) updateStatusForVIN(ctx context.Context, vinHash, status string) telemetryPersistenceResult {
	if err := ctx.Err(); err != nil {
		return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "context_cancelled"}
	}
	if s.memory != nil {
		s.memory.mu.Lock()
		defer s.memory.mu.Unlock()
		if err := ctx.Err(); err != nil {
			return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "context_cancelled"}
		}
		refs := s.memory.vehicles[vinHash]
		if len(refs) == 0 {
			return telemetryPersistenceResult{Classification: telemetryPersistenceUnknownMapping, ErrorClass: "unknown_mapping"}
		}
		for _, ref := range refs {
			key := telemetryKey{UserID: ref.UserID, VehicleID: ref.VehicleID}
			pairing := s.memory.pairings[key]
			pairing.Status, pairing.UpdatedAt = status, time.Now().UTC()
			s.memory.pairings[key] = pairing
		}
		return telemetryPersistenceResult{Classification: telemetryPersistenceDurable, ErrorClass: "durable"}
	}
	if s.store == nil || s.store.pool == nil {
		return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "persistence_unavailable"}
	}
	commandTag, err := s.store.pool.Exec(ctx, `
INSERT INTO jourvolt_telemetry_pairing(user_id, vehicle_id, status, updated_at)
SELECT user_id, vehicle_id, $2, now() FROM jourvolt_telemetry_vehicle_keys WHERE vin_hash=$1
ON CONFLICT (user_id, vehicle_id) DO UPDATE SET status=EXCLUDED.status, updated_at=EXCLUDED.updated_at`, vinHash, status)
	if err != nil {
		if ctx.Err() != nil || errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "context_cancelled"}
		}
		return telemetryPersistenceResult{Classification: telemetryPersistenceError, ErrorClass: "persistence_error"}
	}
	if commandTag.RowsAffected() == 0 {
		return telemetryPersistenceResult{Classification: telemetryPersistenceUnknownMapping, ErrorClass: "unknown_mapping"}
	}
	return telemetryPersistenceResult{Classification: telemetryPersistenceDurable, ErrorClass: "durable"}
}
