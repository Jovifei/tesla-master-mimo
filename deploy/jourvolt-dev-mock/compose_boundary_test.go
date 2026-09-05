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
		text := strings.ReplaceAll(string(data), "\r\n", "\n")
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

func TestTelemetryComposeProvidesPrivateTLSVehicleCommandProxy(t *testing.T) {
	for _, composeFile := range []string{"docker-compose.yml", "docker-compose.pilot.ecs.yml"} {
		data, err := os.ReadFile(composeFile)
		if err != nil {
			t.Fatal(err)
		}
		text := strings.ReplaceAll(string(data), "\r\n", "\n")
		proxyStart := strings.Index(text, "  vehicle-command-proxy:\n")
		if proxyStart < 0 {
			t.Fatalf("%s must define a private vehicle-command proxy", composeFile)
		}
		proxyEnd := strings.Index(text[proxyStart+1:], "\n  fleet-telemetry:")
		if proxyEnd < 0 {
			t.Fatalf("%s proxy service boundary missing", composeFile)
		}
		proxy := text[proxyStart : proxyStart+1+proxyEnd]
		for _, required := range []string{
			"profiles: [\"telemetry\"]",
			"TESLA_VEHICLE_COMMAND_IMAGE",
			"- \"4444\"",
			"TELEMETRY_COMMAND_PROXY_CERT_FILE",
			"TELEMETRY_COMMAND_PROXY_KEY_FILE",
			"TELEMETRY_VEHICLE_COMMAND_KEY_FILE",
			"no-new-privileges:true",
		} {
			if !strings.Contains(proxy, required) {
				t.Fatalf("%s vehicle-command proxy missing %q", composeFile, required)
			}
		}
		if strings.Contains(proxy, "ports:") {
			t.Fatalf("%s vehicle-command proxy must not expose a host port", composeFile)
		}
		apiStart := strings.Index(text, "  jourvolt-dev-api:\n")
		apiEnd := strings.Index(text[apiStart+1:], "\n  jourvolt-mqtt:")
		if apiStart < 0 || apiEnd < 0 {
			t.Fatalf("%s API service boundary missing", composeFile)
		}
		api := text[apiStart : apiStart+1+apiEnd]
		if !strings.Contains(api, "TELEMETRY_COMMAND_PROXY_URL: ${TELEMETRY_COMMAND_PROXY_URL:-}") {
			t.Fatalf("%s API must keep the command proxy URL opt-in", composeFile)
		}
	}
}
