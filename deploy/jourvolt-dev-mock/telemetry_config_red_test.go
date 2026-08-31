package main

import (
	"encoding/base64"
	"strings"
	"testing"
)

func TestTelemetryConfigIsOptionalButAllFieldsFailClosedWhenEnabled(t *testing.T) {
	empty, err := loadTelemetryConfig(func(string) string { return "" })
	if err != nil || empty != nil {
		t.Fatalf("empty telemetry config = %#v, %v; want disabled", empty, err)
	}
	values := telemetryTestEnv()
	delete(values, "TELEMETRY_COMMAND_PROXY_URL")
	if _, err := loadTelemetryConfig(func(name string) string { return values[name] }); err == nil {
		t.Fatal("partial telemetry configuration must fail closed")
	}
	values = telemetryTestEnv()
	config, err := loadTelemetryConfig(func(name string) string { return values[name] })
	if err != nil {
		t.Fatal(err)
	}
	if config.MQTTURL != "mqtt://broker:1883" || config.TopicBase != "jourvolt/telemetry" || config.PublicPort != 4443 || len(config.VINHashKey) != 32 {
		t.Fatalf("telemetry config = %#v", config)
	}
}

func TestTelemetryConfigRejectsUnsafeEndpointsAndCertificatePaths(t *testing.T) {
	for name, mutate := range map[string]func(map[string]string){
		"mqtt scheme": func(v map[string]string) { v["TELEMETRY_MQTT_URL"] = "https://broker:1883" },
		"public port": func(v map[string]string) { v["TELEMETRY_PUBLIC_PORT"] = "70000" },
		"public host": func(v map[string]string) { v["TELEMETRY_PUBLIC_HOST"] = "https://fleet.example.com" },
		"CA path":     func(v map[string]string) { v["TELEMETRY_CA_CERT_PATH"] = "relative/ca.pem" },
		"proxy":       func(v map[string]string) { v["TELEMETRY_COMMAND_PROXY_URL"] = "file:///private/key" },
	} {
		t.Run(name, func(t *testing.T) {
			values := telemetryTestEnv()
			mutate(values)
			if _, err := loadTelemetryConfig(func(key string) string { return values[key] }); err == nil {
				t.Fatal("unsafe telemetry configuration must be rejected")
			}
		})
	}
}

func telemetryTestEnv() map[string]string {
	return map[string]string{
		"TELEMETRY_MQTT_URL":            "mqtt://broker:1883",
		"TELEMETRY_MQTT_USERNAME":       "mqtt-user",
		"TELEMETRY_MQTT_PASSWORD":       "mqtt-password",
		"TELEMETRY_MQTT_TOPIC_BASE":     "jourvolt/telemetry",
		"TELEMETRY_PUBLIC_HOST":         "fleet.example.com",
		"TELEMETRY_PUBLIC_PORT":         "4443",
		"TELEMETRY_COMMAND_PROXY_URL":   "http://vehicle-command-proxy:4444",
		"TELEMETRY_CA_CERT_PATH":        "/run/secrets/fleet/ca.pem",
		"TELEMETRY_VIN_HASH_KEY_BASE64": base64.StdEncoding.EncodeToString([]byte(strings.Repeat("k", 32))),
	}
}
