package main

import (
	"database/sql"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"net/url"
	"testing"
	"time"
)

// Exercises the actual authenticated legacy proxy, rather than a replacement mapper.
// The upstream is synthetic: this is not TeslaMate 4.3.0 deployment evidence.
func TestCompatibilityProxyPreservesPagingUnitsNullNegativeAndTime(t *testing.T) {
	const query = "page=2&size=50&startDate=2026-10-01T00%3A00%3A00Z&endDate=2026-10-02T00%3A00%3A00Z"
	const payload = `{"data":{"units":{"unit_of_length":"mi","unit_of_temperature":"F"},"drives":[{"drive_id":7,"start_date":"2026-10-01T00:00:00Z","distance":null,"power":-3,"energy":0,"unknown_field":"preserved"}],"pagination":{"page":2,"total_pages":3}}}`
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.RawQuery != query || r.URL.Path != "/api/v1/cars/7/drives" {
			t.Errorf("paging/date query changed: %s", r.URL.String())
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(payload))
	}))
	defer upstream.Close()
	endpoint, err := url.Parse(upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	s := testServer(&recordingStore{})
	s.proxy = httputil.NewSingleHostReverseProxy(endpoint)
	response, _ := serve(t, s, "/api/v1/cars/7/drives?"+query, validTestSecret)
	if response.Code != 200 || response.Body.String() != payload {
		t.Fatalf("legacy response changed: status=%d body=%s", response.Code, response.Body.String())
	}
}

func TestCompatibilityDatabaseHelpersPreserveUnknownZeroNegativeAndInstant(t *testing.T) {
	if nullFloat(sql.NullFloat64{}) != nil || nullFloat(sql.NullFloat64{Float64: 0, Valid: true}) != float64(0) || nullFloat(sql.NullFloat64{Float64: -3, Valid: true}) != float64(-3) {
		t.Fatal("null/zero/negative was coerced")
	}
	instant := time.Date(2026, 10, 1, 23, 30, 0, 123000000, time.UTC)
	formatted := formatTime(instant, time.FixedZone("synthetic+8", 8*3600))
	parsed, err := time.Parse(time.RFC3339Nano, formatted)
	if err != nil || !parsed.Equal(instant) {
		t.Fatalf("time instant changed: %s", formatted)
	}
}
