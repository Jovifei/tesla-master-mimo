package main

import (
	"os"
	"strings"
	"testing"
)

func TestLoadConfigRejectsInsecurePublicArchiveURL(t *testing.T) {
	values := map[string]string{
		"DATABASE_URL":               "postgres://readonly:secret@database/teslamate",
		"ARCHIVE_URL":                "http://api.example.com",
		"ARCHIVE_TOKEN":              "binding-token",
		"ARCHIVE_VEHICLE_ID":         "7",
		"ARCHIVE_SOURCE_INSTANCE_ID": "home",
		"ARCHIVE_SOURCE_VEHICLE_ID":  "1",
		"STATE_FILE":                 "/state/cursor.json",
	}
	_, err := loadConfig(func(key string) (string, bool) {
		value, ok := values[key]
		return value, ok
	})
	if err == nil {
		t.Fatal("insecure public archive URL was accepted")
	}
}

func TestComposeDoesNotFallBackToTheTeslaMateOwnerDatabaseRole(t *testing.T) {
	compose, err := os.ReadFile("../docker-compose.yml")
	if err != nil {
		t.Fatal(err)
	}
	line := "DATABASE_URL=${ARCHIVE_DATABASE_URL:?ARCHIVE_DATABASE_URL must use a read-only TeslaMate role}"
	if !strings.Contains(string(compose), line) {
		t.Fatal("archive bridge database URL is not fail-closed to a dedicated read-only role")
	}
}
