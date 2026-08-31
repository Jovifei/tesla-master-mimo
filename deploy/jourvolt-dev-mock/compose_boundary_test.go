package main

import (
	"os"
	"strings"
	"testing"
)

func TestTelemetryComposeDefersRequiredValuesToStartupRenderer(t *testing.T) {
	for _, composeFile := range []string{"docker-compose.yml", "docker-compose.pilot.ecs.yml"} {
		data, err := os.ReadFile(composeFile)
		if err != nil {
			t.Fatal(err)
		}
		text := string(data)
		if strings.Contains(text, "${TELEMETRY_") && strings.Contains(text, ":?set TELEMETRY_") {
			t.Fatalf("%s requires telemetry variables during Compose interpolation", composeFile)
		}
		if !strings.Contains(text, "TELEMETRY_CA_CHAIN_FILE") {
			t.Fatalf("%s must define an API-only CA chain mount", composeFile)
		}
	}
}

func TestTelemetryRendererFailsClosedForPinnedImageAndMountedCertificates(t *testing.T) {
	data, err := os.ReadFile("fleet-telemetry/render-server-config.sh")
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	for _, required := range []string{
		"FLEET_TELEMETRY_IMAGE is required",
		"@sha256:",
		"TELEMETRY_SERVER_CERT_PATH is required",
		"TELEMETRY_SERVER_KEY_PATH is required",
		"missing or unreadable telemetry certificate",
		"missing or unreadable telemetry private key",
	} {
		if !strings.Contains(text, required) {
			t.Fatalf("renderer must fail closed for %q", required)
		}
	}
}
