package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

const maxCredentialInputBytes = 16 << 10

// Stdin credentials stay in memory and never become environment variables or arguments.
func loadRunConfig(args []string, input io.Reader, getenv func(string) (string, bool)) (config, error) {
	if len(args) == 0 {
		return loadConfig(getenv)
	}
	if len(args) != 1 || args[0] != "--credentials-stdin" || input == nil {
		return config{}, errors.New("invalid credential input mode")
	}
	if _, ok := getenv("DATABASE_URL"); ok {
		return config{}, errors.New("credential environment must be absent")
	}
	if _, ok := getenv("ARCHIVE_TOKEN"); ok {
		return config{}, errors.New("credential environment must be absent")
	}
	data, err := io.ReadAll(io.LimitReader(input, maxCredentialInputBytes+1))
	defer clear(data)
	if err != nil || len(data) > maxCredentialInputBytes {
		return config{}, errors.New("invalid credential input")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	first, err := decoder.Token()
	if err != nil || first != json.Delim('{') {
		return config{}, errors.New("invalid credential input")
	}
	values := make(map[string]string, 2)
	for decoder.More() {
		key, err := decoder.Token()
		name, ok := key.(string)
		if err != nil || !ok || (name != "database_url" && name != "archive_token") {
			return config{}, errors.New("invalid credential input")
		}
		if _, duplicate := values[name]; duplicate {
			return config{}, errors.New("invalid credential input")
		}
		var value string
		if decoder.Decode(&value) != nil || value == "" || strings.TrimSpace(value) != value {
			return config{}, errors.New("invalid credential input")
		}
		values[name] = value
	}
	end, err := decoder.Token()
	if err != nil || end != json.Delim('}') || len(values) != 2 {
		return config{}, errors.New("invalid credential input")
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return config{}, errors.New("invalid credential input")
	}
	return loadConfig(func(name string) (string, bool) {
		switch name {
		case "DATABASE_URL":
			return values["database_url"], true
		case "ARCHIVE_TOKEN":
			return values["archive_token"], true
		default:
			return getenv(name)
		}
	})
}

type config struct {
	DatabaseURL, ArchiveURL, ArchiveToken, ArchiveVehicleID         string
	SourceInstanceID, SourceVehicleID, StateFile, OriginalStateFile string
	SourceTimezone                                                  string
	SourceCarID                                                     int64
	BatchSize                                                       int
	PollInterval                                                    time.Duration
	ScopeHash                                                       string
}

func loadConfig(getenv func(string) (string, bool)) (config, error) {
	var c config
	required := func(name string) (string, error) {
		v, ok := getenv(name)
		if !ok || v == "" || strings.TrimSpace(v) != v {
			return "", errors.New("invalid environment: " + name)
		}
		return v, nil
	}
	var err error
	if c.DatabaseURL, err = required("DATABASE_URL"); err != nil {
		return c, err
	}
	if c.ArchiveURL, err = required("ARCHIVE_URL"); err != nil {
		return c, err
	}
	if c.ArchiveToken, err = required("ARCHIVE_TOKEN"); err != nil {
		return c, err
	}
	if c.ArchiveVehicleID, err = required("ARCHIVE_VEHICLE_ID"); err != nil {
		return c, err
	}
	if c.SourceInstanceID, err = required("ARCHIVE_SOURCE_INSTANCE_ID"); err != nil {
		return c, err
	}
	if c.SourceVehicleID, err = required("ARCHIVE_SOURCE_VEHICLE_ID"); err != nil {
		return c, err
	}
	if c.OriginalStateFile, err = required("STATE_FILE"); err != nil {
		return c, err
	}
	if c.StateFile, err = required("BATTERY_TREND_STATE_FILE"); err != nil {
		return c, err
	}
	if c.SourceTimezone, err = required("BATTERY_TREND_SOURCE_TIMEZONE"); err != nil || c.SourceTimezone != "UTC" {
		return c, errors.New("source timestamp convention must be explicitly verified as UTC")
	}
	parsed, err := url.Parse(c.ArchiveURL)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return c, errors.New("invalid archive URL")
	}
	if id, e := strconv.ParseInt(c.ArchiveVehicleID, 10, 64); e != nil || id <= 0 || strconv.FormatInt(id, 10) != c.ArchiveVehicleID {
		return c, errors.New("invalid archive vehicle")
	}
	c.SourceCarID, err = strconv.ParseInt(c.SourceVehicleID, 10, 64)
	if err != nil || c.SourceCarID <= 0 || strconv.FormatInt(c.SourceCarID, 10) != c.SourceVehicleID || len(c.SourceVehicleID) > 128 || len(c.SourceInstanceID) > 128 {
		return c, errors.New("invalid source scope")
	}
	c.BatchSize = 50
	if v, ok := getenv("BATTERY_TREND_BATCH_SIZE"); ok {
		c.BatchSize, err = strconv.Atoi(v)
		if err != nil || c.BatchSize < 1 || c.BatchSize > 50 {
			return c, errors.New("invalid batch size")
		}
	}
	c.PollInterval = time.Minute
	if v, ok := getenv("BATTERY_TREND_POLL_INTERVAL"); ok {
		c.PollInterval, err = time.ParseDuration(v)
		if err != nil || c.PollInterval < time.Second {
			return c, errors.New("invalid poll interval")
		}
	}
	pg, err := pgxpool.ParseConfig(c.DatabaseURL)
	if err != nil || pg.ConnConfig.Host == "" || pg.ConnConfig.Database == "" {
		return c, errors.New("invalid source database")
	}
	if _, err = os.Stat(c.OriginalStateFile); err != nil {
		return c, errors.New("original bridge state must be visible for isolation")
	}
	if err = distinctPaths(c.OriginalStateFile, c.StateFile); err != nil {
		return c, err
	}
	if err = distinctPaths(c.OriginalStateFile, c.StateFile+".lock"); err != nil {
		return c, err
	}
	scope, _ := json.Marshal([]any{c.ArchiveURL, c.ArchiveVehicleID, c.SourceInstanceID, c.SourceVehicleID, c.SourceTimezone,
		pg.ConnConfig.Host, pg.ConnConfig.Port, pg.ConnConfig.Database})
	digest := sha256.Sum256(scope)
	c.ScopeHash = hex.EncodeToString(digest[:])
	return c, nil
}

func distinctPaths(original, candidate string) error {
	canonical := func(path string, rejectLink bool) (string, os.FileInfo, error) {
		if !filepath.IsAbs(path) {
			return "", nil, errors.New("state paths must be absolute")
		}
		parent, err := filepath.EvalSymlinks(filepath.Dir(filepath.Clean(path)))
		if err != nil {
			return "", nil, errors.New("state directory unavailable")
		}
		resolved := filepath.Join(parent, filepath.Base(filepath.Clean(path)))
		info, err := os.Lstat(resolved)
		if errors.Is(err, os.ErrNotExist) {
			return resolved, nil, nil
		}
		if err != nil || info.IsDir() {
			return "", nil, errors.New("invalid state path")
		}
		if info.Mode()&os.ModeSymlink != 0 {
			if rejectLink {
				return "", nil, errors.New("trend state may not be a symlink")
			}
			resolved, err = filepath.EvalSymlinks(resolved)
			if err != nil {
				return "", nil, errors.New("original state alias unavailable")
			}
			info, err = os.Stat(resolved)
			if err != nil {
				return "", nil, errors.New("original state unavailable")
			}
		}
		return resolved, info, nil
	}
	a, ai, err := canonical(original, false)
	if err != nil {
		return err
	}
	b, bi, err := canonical(candidate, true)
	if err != nil {
		return err
	}
	if strings.EqualFold(filepath.Clean(a), filepath.Clean(b)) || (ai != nil && bi != nil && os.SameFile(ai, bi)) {
		return errors.New("trend state aliases original bridge state")
	}
	return nil
}
