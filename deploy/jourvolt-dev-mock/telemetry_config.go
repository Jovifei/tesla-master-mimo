package main

import (
	"encoding/base64"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type telemetryConfig struct {
	MQTTURL         string
	MQTTUsername    string
	MQTTPassword    string
	TopicBase       string
	PublicHost      string
	PublicPort      int
	StatusPort      int
	PartnerDomain   string
	CommandProxyURL string
	CACertPath      string
	VINHashKey      []byte
	CommandTimeout  time.Duration
	StopDebounce    time.Duration
	EventTTL        time.Duration
}

func loadTelemetryConfig(getenv func(string) string) (*telemetryConfig, error) {
	names := []string{
		"TELEMETRY_MQTT_URL", "TELEMETRY_MQTT_USERNAME", "TELEMETRY_MQTT_PASSWORD", "TELEMETRY_MQTT_TOPIC_BASE",
		"TELEMETRY_PUBLIC_HOST", "TELEMETRY_PUBLIC_PORT", "TELEMETRY_COMMAND_PROXY_URL",
		"TELEMETRY_CA_CERT_PATH", "TELEMETRY_VIN_HASH_KEY_BASE64",
	}
	values := make(map[string]string, len(names))
	configured := false
	for _, name := range names {
		values[name] = strings.TrimSpace(getenv(name))
		// Compose mounts this fixed in-container CA path for the API even when
		// the optional Telemetry profile is disabled. It is not an activation
		// signal; any externally supplied Telemetry value is.
		if name != "TELEMETRY_CA_CERT_PATH" {
			configured = configured || values[name] != ""
		}
	}
	if !configured {
		return nil, nil
	}
	missing := make([]string, 0)
	for _, name := range names {
		if values[name] == "" {
			missing = append(missing, name)
		}
	}
	if len(missing) > 0 {
		return nil, fmt.Errorf("incomplete telemetry configuration; missing %s", strings.Join(missing, ", "))
	}
	mqttURL, err := parseTelemetryMQTTURL(values["TELEMETRY_MQTT_URL"])
	if err != nil {
		return nil, err
	}
	if err := validateTopicBase(values["TELEMETRY_MQTT_TOPIC_BASE"]); err != nil {
		return nil, err
	}
	publicPort, err := strconv.Atoi(values["TELEMETRY_PUBLIC_PORT"])
	if err != nil || publicPort < 1 || publicPort > 65535 {
		return nil, fmt.Errorf("TELEMETRY_PUBLIC_PORT must be between 1 and 65535")
	}
	publicHost, err := normalizeTelemetryHostname(values["TELEMETRY_PUBLIC_HOST"])
	if err != nil {
		return nil, fmt.Errorf("TELEMETRY_PUBLIC_HOST: %w", err)
	}
	commandProxyURL, err := parseTelemetryHTTPURL("TELEMETRY_COMMAND_PROXY_URL", values["TELEMETRY_COMMAND_PROXY_URL"])
	if err != nil {
		return nil, err
	}
	if err := validateTelemetryMountPath("TELEMETRY_CA_CERT_PATH", values["TELEMETRY_CA_CERT_PATH"]); err != nil {
		return nil, err
	}
	key, err := base64.StdEncoding.DecodeString(values["TELEMETRY_VIN_HASH_KEY_BASE64"])
	if err != nil || len(key) != 32 {
		return nil, fmt.Errorf("TELEMETRY_VIN_HASH_KEY_BASE64 must encode exactly 32 bytes")
	}
	return &telemetryConfig{
		MQTTURL: mqttURL, MQTTUsername: values["TELEMETRY_MQTT_USERNAME"], MQTTPassword: values["TELEMETRY_MQTT_PASSWORD"],
		TopicBase: strings.Trim(values["TELEMETRY_MQTT_TOPIC_BASE"], "/"), PublicHost: publicHost, PublicPort: publicPort,
		PartnerDomain: publicHost,
		StatusPort:    8080, CommandProxyURL: commandProxyURL, CACertPath: values["TELEMETRY_CA_CERT_PATH"],
		VINHashKey: key, CommandTimeout: 5 * time.Second, StopDebounce: defaultDriveStopDebounce, EventTTL: telemetryEventTTL,
	}, nil
}

func parseTelemetryMQTTURL(raw string) (string, error) {
	parsed, err := url.Parse(raw)
	if err != nil || (parsed.Scheme != "mqtt" && parsed.Scheme != "mqtts") || parsed.Host == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", fmt.Errorf("TELEMETRY_MQTT_URL must be an mqtt:// or mqtts:// URL without credentials or query")
	}
	return raw, nil
}

func parseTelemetryHTTPURL(name, raw string) (string, error) {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", fmt.Errorf("%s must be an absolute HTTPS URL without credentials or query", name)
	}
	return strings.TrimRight(raw, "/"), nil
}

func validateTopicBase(raw string) error {
	base := strings.Trim(raw, "/")
	if base == "" || strings.ContainsAny(base, " #+\t\r\n") {
		return fmt.Errorf("TELEMETRY_MQTT_TOPIC_BASE must be a non-empty MQTT topic without wildcards")
	}
	for _, part := range strings.Split(base, "/") {
		if part == "" || part == "." || part == ".." {
			return fmt.Errorf("TELEMETRY_MQTT_TOPIC_BASE contains an invalid segment")
		}
	}
	return nil
}

func normalizeTelemetryHostname(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" || strings.ContainsAny(raw, "/?#:@\t\r\n") {
		return "", fmt.Errorf("must be a hostname without scheme, port, or path")
	}
	return strings.TrimSuffix(raw, "."), nil
}

func validateTelemetryMountPath(name, raw string) error {
	if !strings.HasPrefix(raw, "/") || strings.Contains(raw, "..") || strings.ContainsAny(raw, "\r\n") {
		return fmt.Errorf("%s must be an absolute read-only container mount path", name)
	}
	return nil
}

func telemetryVINHashKeyFromConfig(config *telemetryConfig) []byte {
	if config != nil && len(config.VINHashKey) > 0 {
		return append([]byte(nil), config.VINHashKey...)
	}
	return nil
}
