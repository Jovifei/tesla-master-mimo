package main

import (
	"encoding/json"
	"fmt"
	"log"
	"regexp"
	"strings"
)

var teslaBearerLogPattern = regexp.MustCompile(`(?i)bearer\s+[A-Za-z0-9._\-]+`)

func teslaAPILogBody(body []byte) string {
	class := "upstream"
	var payload map[string]any
	if json.Unmarshal(body, &payload) == nil {
		for _, key := range []string{"error", "code", "error_code", "class", "type"} {
			if value, ok := payload[key].(string); ok {
				if safe := sanitizeTeslaErrorCode(value); safe != "" {
					class = safe
					break
				}
			}
		}
	}
	if teslaBearerLogPattern.Match(body) {
		return fmt.Sprintf("error_class=%s authorization=Bearer <redacted>", class)
	}
	return "error_class=" + class
}

func logTeslaAPI(method, path string, status int, body []byte) {
	log.Printf("tesla_api method=%s endpoint=%s status=%d %s", method, teslaEndpointLabel(path), status, teslaAPILogBody(body))
}

func teslaEndpointLabel(path string) string {
	switch {
	case strings.Contains(path, "partner_accounts"):
		return "partner_accounts"
	case strings.Contains(path, "vehicle_data"):
		return "vehicle_data"
	case strings.Contains(path, "fleet_telemetry_config"):
		return "fleet_telemetry_config"
	case strings.Contains(path, "token"):
		return "token"
	default:
		return "unknown"
	}
}
