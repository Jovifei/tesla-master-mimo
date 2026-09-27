package main

import (
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

func loadConfig(getenv func(string) (string, bool)) (Config, error) {
	var config Config
	var err error
	if config.DatabaseURL, err = requiredEnv(getenv, "DATABASE_URL"); err != nil {
		return Config{}, err
	}
	if config.ArchiveURL, err = requiredEnv(getenv, "ARCHIVE_URL"); err != nil {
		return Config{}, err
	}
	config.ArchiveURL = strings.TrimRight(strings.TrimSpace(config.ArchiveURL), "/")
	parsed, err := url.Parse(config.ArchiveURL)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" || parsed.User != nil {
		return Config{}, envError("ARCHIVE_URL")
	}
	if config.ArchiveToken, err = requiredEnv(getenv, "ARCHIVE_TOKEN"); err != nil {
		return Config{}, err
	}
	if config.ArchiveVehicleID, err = requiredEnv(getenv, "ARCHIVE_VEHICLE_ID"); err != nil {
		return Config{}, err
	}
	if config.SourceInstanceID, err = requiredEnv(getenv, "ARCHIVE_SOURCE_INSTANCE_ID"); err != nil {
		return Config{}, err
	}
	if config.SourceVehicleID, err = requiredEnv(getenv, "ARCHIVE_SOURCE_VEHICLE_ID"); err != nil {
		return Config{}, err
	}
	config.SourceCarID, err = strconv.ParseInt(config.SourceVehicleID, 10, 64)
	if err != nil || config.SourceCarID <= 0 {
		return Config{}, envError("ARCHIVE_SOURCE_VEHICLE_ID")
	}
	if config.StateFile, err = requiredEnv(getenv, "STATE_FILE"); err != nil {
		return Config{}, err
	}
	config.BatchSize, err = optionalPositiveInt(getenv, "BATCH_SIZE", 100)
	if err != nil {
		return Config{}, err
	}
	config.PollInterval, err = optionalDuration(getenv, "POLL_INTERVAL", time.Minute)
	if err != nil {
		return Config{}, err
	}
	return config, nil
}

func LoadConfig() (Config, error) {
	return loadConfig(os.LookupEnv)
}

func requiredEnv(getenv func(string) (string, bool), key string) (string, error) {
	value, ok := getenv(key)
	if !ok || strings.TrimSpace(value) == "" {
		return "", envError(key)
	}
	return strings.TrimSpace(value), nil
}

func optionalPositiveInt(getenv func(string) (string, bool), key string, fallback int) (int, error) {
	value, ok := getenv(key)
	if !ok || strings.TrimSpace(value) == "" {
		return fallback, nil
	}
	parsed, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil || parsed <= 0 {
		return 0, envError(key)
	}
	return parsed, nil
}

func optionalDuration(getenv func(string) (string, bool), key string, fallback time.Duration) (time.Duration, error) {
	value, ok := getenv(key)
	if !ok || strings.TrimSpace(value) == "" {
		return fallback, nil
	}
	parsed, err := time.ParseDuration(strings.TrimSpace(value))
	if err != nil || parsed <= 0 {
		return 0, envError(key)
	}
	return parsed, nil
}

type configEnvError string

func (e configEnvError) Error() string { return "invalid environment variable: " + string(e) }

func envError(key string) error { return configEnvError(key) }
