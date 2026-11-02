package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"testing"
)

type failingCredentialReader struct{}

func (failingCredentialReader) Read([]byte) (int, error) {
	return 0, errors.New("synthetic-secret-in-reader-error")
}

func TestCredentialsStdinActualEntrypointDoesNotLogSecrets(t *testing.T) {
	if os.Getenv("MATELINK_BATTERY_STDIN_CHILD") == "1" {
		os.Args = []string{"battery-trend", "--credentials-stdin"}
		main()
		return
	}
	base := syntheticConfig(t, "https://synthetic.example")
	variables := testNonSecretEnv(base)
	variables["MATELINK_BATTERY_STDIN_CHILD"] = "1"
	var env []string
	for _, item := range os.Environ() {
		name, _, _ := strings.Cut(item, "=")
		if name == "DATABASE_URL" || name == "ARCHIVE_TOKEN" {
			continue
		}
		if _, replaced := variables[name]; replaced {
			continue
		}
		env = append(env, item)
	}
	for name, value := range variables {
		env = append(env, name+"="+value)
	}
	database := "postgres://synthetic-user:synthetic-secret-password@127.0.0.1:1/synthetic?connect_timeout=1"
	token := "synthetic-secret-archive-token"
	input := fmt.Sprintf(`{"database_url":%q,"archive_token":%q}`, database, token)
	cmd := exec.Command(os.Args[0], "-test.run=^TestCredentialsStdinActualEntrypointDoesNotLogSecrets$")
	cmd.Env, cmd.Stdin = env, strings.NewReader(input)
	output, err := cmd.CombinedOutput()
	if err == nil || (!strings.Contains(string(output), "battery trend requires verified owner") &&
		!strings.Contains(string(output), "battery trend owner identity unavailable")) {
		t.Fatal("stdin did not stop before unverified archive sending")
	}
	if strings.Contains(string(output), database) || strings.Contains(string(output), token) || strings.Contains(string(output), "synthetic-secret-password") {
		t.Fatal("credentials appeared in process output")
	}
}

func testNonSecretEnv(c config) map[string]string {
	return map[string]string{
		"ARCHIVE_URL": c.ArchiveURL, "ARCHIVE_VEHICLE_ID": c.ArchiveVehicleID,
		"ARCHIVE_SOURCE_INSTANCE_ID": c.SourceInstanceID, "ARCHIVE_SOURCE_VEHICLE_ID": c.SourceVehicleID,
		"STATE_FILE": c.OriginalStateFile, "BATTERY_TREND_STATE_FILE": c.StateFile,
		"BATTERY_TREND_SOURCE_TIMEZONE": "UTC",
	}
}

func TestCredentialsStdinModeIsBoundedPrivateAndCompatible(t *testing.T) {
	base := syntheticConfig(t, "https://synthetic.example")
	env := testNonSecretEnv(base)
	lookup := func(name string) (string, bool) { value, ok := env[name]; return value, ok }
	database := "postgres://synthetic-user:synthetic-secret-password@127.0.0.1:5433/synthetic"
	token := "synthetic-secret-archive-token"
	input := fmt.Sprintf(`{"database_url":%q,"archive_token":%q}`, database, token)
	config, err := loadRunConfig([]string{"--credentials-stdin"}, strings.NewReader(input), lookup)
	if err != nil || config.DatabaseURL != database || config.ArchiveToken != token {
		t.Fatal("valid stdin configuration rejected")
	}
	if _, ok := env["DATABASE_URL"]; ok {
		t.Fatal("database credential leaked to environment")
	}
	if _, ok := env["ARCHIVE_TOKEN"]; ok {
		t.Fatal("archive credential leaked to environment")
	}
	rotated := fmt.Sprintf(`{"database_url":%q,"archive_token":%q}`, database, "synthetic-rotated-token")
	newConfig, err := loadRunConfig([]string{"--credentials-stdin"}, strings.NewReader(rotated), lookup)
	if err != nil || newConfig.ScopeHash != config.ScopeHash {
		t.Fatal("credential rotation changed source identity")
	}

	env["DATABASE_URL"], env["ARCHIVE_TOKEN"] = database, token
	legacy, err := loadRunConfig(nil, failingCredentialReader{}, lookup)
	if err != nil || legacy.DatabaseURL != database || legacy.ArchiveToken != token {
		t.Fatal("legacy environment mode changed")
	}
	if _, err = loadRunConfig([]string{"--credentials-stdin"}, strings.NewReader(input), lookup); err == nil {
		t.Fatal("stdin mode accepted inherited secret environment")
	}
	delete(env, "DATABASE_URL")
	delete(env, "ARCHIVE_TOKEN")

	tests := []struct {
		name   string
		args   []string
		reader io.Reader
	}{
		{"wrong-option", []string{"--unknown", token}, strings.NewReader(input)},
		{"extra-option", []string{"--credentials-stdin", token}, strings.NewReader(input)},
		{"missing-value", []string{"--credentials-stdin"}, strings.NewReader(`{"database_url":"synthetic"}`)},
		{"empty-value", []string{"--credentials-stdin"}, strings.NewReader(`{"database_url":"","archive_token":"synthetic"}`)},
		{"unknown-field", []string{"--credentials-stdin"}, strings.NewReader(`{"database_url":"synthetic","archive_token":"synthetic","extra":"synthetic"}`)},
		{"duplicate-field", []string{"--credentials-stdin"}, strings.NewReader(`{"database_url":"synthetic","database_url":"other","archive_token":"synthetic"}`)},
		{"trailing-json", []string{"--credentials-stdin"}, strings.NewReader(input + `{}`)},
		{"invalid-json", []string{"--credentials-stdin"}, strings.NewReader(input[:len(input)-1])},
		{"oversize", []string{"--credentials-stdin"}, strings.NewReader(strings.Repeat("x", maxCredentialInputBytes+1))},
		{"read-error", []string{"--credentials-stdin"}, failingCredentialReader{}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			_, err := loadRunConfig(test.args, test.reader, lookup)
			if err == nil {
				t.Fatal("invalid credential input accepted")
			}
			if strings.Contains(err.Error(), token) || strings.Contains(err.Error(), database) || strings.Contains(err.Error(), "synthetic-secret-in-reader-error") {
				t.Fatal("credential material appeared in an error")
			}
		})
	}
}
