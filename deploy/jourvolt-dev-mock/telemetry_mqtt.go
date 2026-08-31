package main

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

const (
	telemetryMQTTQueueCapacity = 256
	telemetryMQTTBackpressure  = 250 * time.Millisecond
	telemetryMQTTProcessTimeout = 5 * time.Second
)

var errTelemetryMQTTBackpressure = errors.New("telemetry MQTT queue backpressure")

type telemetryMQTTEnvelope struct {
	topic      string
	payload    []byte
	observedAt time.Time
}

type telemetrySubscriber struct {
	service *telemetryService
	config  *telemetryConfig
	client  mqtt.Client
	queue   chan telemetryMQTTEnvelope
	backpressure time.Duration
	mu      sync.RWMutex
	stopped bool
	workerOnce sync.Once
	stopOnce sync.Once
	workerWG sync.WaitGroup
}

func newTelemetrySubscriber(service *telemetryService) *telemetrySubscriber {
	if service == nil || service.config == nil {
		return nil
	}
	return &telemetrySubscriber{service: service, config: service.config, queue: make(chan telemetryMQTTEnvelope, telemetryMQTTQueueCapacity), backpressure: telemetryMQTTBackpressure}
}

func (s *telemetrySubscriber) start(ctx context.Context) error {
	if s == nil || s.service == nil || s.config == nil {
		return nil
	}
	s.startWorker(ctx)
	options := mqtt.NewClientOptions().
		AddBroker(s.config.MQTTURL).
		SetClientID("jourvolt-telemetry-consumer").
		SetCleanSession(false).
		SetAutoReconnect(true).
		SetConnectRetry(true).
		SetConnectRetryInterval(10 * time.Second).
		SetKeepAlive(30 * time.Second).
		SetConnectionLostHandler(func(_ mqtt.Client, _ error) {
			s.service.mqttConnected.Store(false)
			s.service.mqttSubscribed.Store(false)
			s.service.mqttHealthy.Store(false)
		}).
		SetOnConnectHandler(func(client mqtt.Client) {
			s.client = client
			topic := strings.Trim(s.config.TopicBase, "/") + "/+/v/#"
			tokens := []mqtt.Token{
				client.Subscribe(topic, 1, s.message),
				client.Subscribe(strings.Trim(s.config.TopicBase, "/")+"/+/connectivity", 1, s.message),
				client.Subscribe(strings.Trim(s.config.TopicBase, "/")+"/+/alerts/#", 1, s.message),
				client.Subscribe(strings.Trim(s.config.TopicBase, "/")+"/+/errors/#", 1, s.message),
			}
			subscribed := true
			for _, token := range tokens {
				if !token.WaitTimeout(5*time.Second) || token.Error() != nil {
					subscribed = false
				}
			}
			s.service.mqttConnected.Store(true)
			s.service.mqttSubscribed.Store(subscribed)
			s.service.mqttHealthy.Store(subscribed)
		})
	if s.config.MQTTUsername != "" {
		options.SetUsername(s.config.MQTTUsername).SetPassword(s.config.MQTTPassword)
	}
	s.client = mqtt.NewClient(options)
	token := s.client.Connect()
	if !token.WaitTimeout(10 * time.Second) {
		s.stop()
		return errors.New("telemetry MQTT connection timed out")
	}
	if err := token.Error(); err != nil {
		s.stop()
		return err
	}
	s.service.started = true
	return nil
}

func (s *telemetrySubscriber) stop() {
	if s == nil {
		return
	}
	s.stopOnce.Do(func() {
		if s.client != nil && s.client.IsConnected() {
			s.client.Disconnect(250)
		}
		s.mu.Lock()
		s.stopped = true
		close(s.queue)
		s.mu.Unlock()
		s.workerWG.Wait()
	})
}

func (s *telemetrySubscriber) message(_ mqtt.Client, message mqtt.Message) {
	if s == nil || s.service == nil {
		return
	}
	_ = s.enqueue(context.Background(), message.Topic(), message.Payload(), time.Now().UTC())
}

func (s *telemetrySubscriber) startWorker(ctx context.Context) {
	if s == nil {
		return
	}
	s.workerOnce.Do(func() {
		s.workerWG.Add(1)
		go func() {
			defer s.workerWG.Done()
			for envelope := range s.queue {
				processCtx, cancel := context.WithTimeout(context.Background(), telemetryMQTTProcessTimeout)
				err := s.service.consumeMQTTMessage(processCtx, envelope.topic, envelope.payload, envelope.observedAt)
				cancel()
				if err != nil {
					s.service.mqttHealthy.Store(false)
				}
			}
		}()
		go func() {
			<-ctx.Done()
			s.stop()
		}()
	})
}

func (s *telemetrySubscriber) enqueue(ctx context.Context, topic string, payload []byte, observedAt time.Time) error {
	if s == nil || s.service == nil {
		return errors.New("telemetry MQTT subscriber is unavailable")
	}
	copyPayload := append([]byte(nil), payload...)
	envelope := telemetryMQTTEnvelope{topic: topic, payload: copyPayload, observedAt: observedAt}
	timeout := s.backpressure
	if timeout <= 0 {
		timeout = telemetryMQTTBackpressure
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.stopped {
		return errors.New("telemetry MQTT subscriber stopped")
	}
	select {
	case s.queue <- envelope:
		return nil
	case <-ctx.Done():
		s.service.mqttHealthy.Store(false)
		return ctx.Err()
	case <-timer.C:
		s.service.mqttHealthy.Store(false)
		return errTelemetryMQTTBackpressure
	}
}

func (s *telemetryService) consumeMQTTMessage(ctx context.Context, topic string, payload []byte, observedAt time.Time) error {
	return s.consumeMQTTMessageWithSequence(ctx, topic, payload, observedAt, s.receiveSequence.Add(1))
}

func (s *telemetryService) consumeMQTTMessageWithSequence(ctx context.Context, topic string, payload []byte, observedAt time.Time, receiveSequence uint64) error {
	if strings.Contains(topic, "/v/") {
		record, err := parseTelemetryPayloadWithKey(s.vinHashKey, s.config.TopicBase, topic, payload, observedAt)
		if err != nil {
			return err
		}
		record.ReceiveSequence = receiveSequence
		_, err = s.ingest(ctx, record)
		return err
	}
	return s.consumeTelemetryControlMessage(ctx, topic, payload)
}

func (s *telemetryService) consumeTelemetryControlMessage(ctx context.Context, topic string, payload []byte) error {
	if s == nil || s.config == nil || len(payload) == 0 || len(payload) > maxTelemetryPayloadBytes {
		return errors.New("telemetry control message is invalid")
	}
	baseParts := strings.Split(strings.Trim(s.config.TopicBase, "/"), "/")
	parts := strings.Split(strings.Trim(topic, "/"), "/")
	if len(parts) < len(baseParts)+2 || strings.Join(parts[:len(baseParts)], "/") != strings.Trim(s.config.TopicBase, "/") || parts[len(baseParts)] == "" {
		return errors.New("telemetry control topic is invalid")
	}
	vinHash := keyedVINHash(s.vinHashKey, parts[len(baseParts)])
	kind := parts[len(baseParts)+1]
	switch kind {
	case "connectivity":
		var value struct {
			Status string `json:"Status"`
		}
		if err := json.Unmarshal(payload, &value); err != nil || value.Status == "" {
			return errors.New("telemetry connectivity payload is invalid")
		}
		status := "collecting"
		if strings.EqualFold(value.Status, "offline") || strings.EqualFold(value.Status, "disconnected") {
			status = "waiting_vehicle"
		}
		return s.updateStatusForVIN(ctx, vinHash, status)
	case "alerts", "errors":
		return s.updateStatusForVIN(ctx, vinHash, "telemetry_error")
	default:
		return errors.New("telemetry control topic is unsupported")
	}
}

func (s *telemetryService) updateStatusForVIN(ctx context.Context, vinHash, status string) error {
	if s.memory != nil {
		s.memory.mu.Lock()
		defer s.memory.mu.Unlock()
		for _, ref := range s.memory.vehicles[vinHash] {
			s.memory.pairings[telemetryKey{UserID: ref.UserID, VehicleID: ref.VehicleID}] = telemetryPairing{Status: status, UpdatedAt: time.Now().UTC()}
		}
		return nil
	}
	if s.store == nil || s.store.pool == nil {
		return nil
	}
	_, err := s.store.pool.Exec(ctx, `
INSERT INTO jourvolt_telemetry_pairing(user_id, vehicle_id, status, updated_at)
SELECT user_id, vehicle_id, $2, now() FROM jourvolt_telemetry_vehicle_keys WHERE vin_hash=$1
ON CONFLICT (user_id, vehicle_id) DO UPDATE SET status=EXCLUDED.status, updated_at=EXCLUDED.updated_at`, vinHash, status)
	return err
}
