package main

import (
	"strings"
	"testing"
)

func TestTeslaAPILogBodyRedactsBearer(t *testing.T) {
	got := teslaAPILogBody([]byte(`{"error":"nope","token":"Bearer abc.def.ghi"}`))
	if got == "" || strings.Contains(got, "abc.def.ghi") {
		t.Fatalf("body = %q", got)
	}
	if !strings.Contains(got, "Bearer <redacted>") {
		t.Fatalf("expected redaction, got %q", got)
	}
}
