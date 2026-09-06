package main

import (
	"fmt"
	"math"
	"strconv"
	"strings"
)

const milesToKilometresFactor = 1.609344

func canonicalGear(value any) (string, bool) {
	text := strings.TrimSpace(fmt.Sprint(value))
	if number, err := strconv.Atoi(text); err == nil {
		gear, ok := map[int]string{2: "P", 3: "R", 4: "N", 5: "D"}[number]
		return gear, ok
	}
	text = strings.TrimPrefix(text, "ShiftState")
	switch strings.ToUpper(text) {
	case "P", "R", "N", "D":
		return strings.ToUpper(text), true
	case "PARK", "REVERSE", "NEUTRAL", "DRIVE":
		return map[string]string{"PARK": "P", "REVERSE": "R", "NEUTRAL": "N", "DRIVE": "D"}[strings.ToUpper(text)], true
	default:
		return "", false
	}
}

func canonicalDetailedChargeState(value any) string {
	text := strings.TrimSpace(fmt.Sprint(value))
	if number, err := strconv.Atoi(text); err == nil {
		return map[int]string{0: "unknown", 1: "disconnected", 2: "nopower", 3: "starting", 4: "charging", 5: "complete", 6: "stopped"}[number]
	}
	text = strings.TrimPrefix(text, "DetailedChargeState")
	text = strings.TrimPrefix(text, "ChargeState")
	switch strings.ToLower(text) {
	case "charging", "starting", "complete", "disconnected", "stopped", "nopower", "unknown":
		return strings.ToLower(text)
	case "completed":
		return "complete"
	default:
		return ""
	}
}

func normalizeTelemetryNumber(field string, value float64) float64 {
	if !math.IsNaN(value) && !math.IsInf(value, 0) {
		switch field {
		case "VehicleSpeed", "Odometer", "EstBatteryRange", "RatedRange":
			return value * milesToKilometresFactor
		}
	}
	return value
}
