package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestSummariesResource(t *testing.T) {
	app := &app{}

	// 1. Post trip summaries
	startTime := time.Now().UTC().Add(-time.Hour).Truncate(time.Second)
	endTime := time.Now().UTC().Truncate(time.Second)
	dist := 18.5
	dur := 42
	energy := 3.2
	cost := 5.4
	latStart := 30.27
	lonStart := 120.15
	latEnd := 30.30
	lonEnd := 120.20

	tripPayload := map[string]any{
		"trips": []tripSummaryItem{
			{
				Source:       "fleet",
				SourceID:     "trip-001",
				StartedAt:    startTime,
				EndedAt:      endTime,
				DistanceKm:   &dist,
				DurationMin:  &dur,
				EnergyKwh:    &energy,
				CostYuan:     &cost,
				StartAddress: "杭州市西湖区",
				EndAddress:   "杭州市余杭区",
				LatStart:     &latStart,
				LonStart:     &lonStart,
				LatEnd:       &latEnd,
				LonEnd:       &lonEnd,
			},
		},
	}
	bodyBytes, _ := json.Marshal(tripPayload)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/summaries/trips", bytes.NewReader(bodyBytes))
	rec := httptest.NewRecorder()
	app.summariesResource(rec, req, "test-user-1", 1, []string{"trips"})
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d: %s", rec.Code, rec.Body.String())
	}

	// 2. Query trip summaries
	req = httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/summaries/trips?limit=10", nil)
	rec = httptest.NewRecorder()
	app.summariesResource(rec, req, "test-user-1", 1, []string{"trips"})
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var res struct {
		Data struct {
			Trips []tripSummaryItem `json:"trips"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Data.Trips) != 1 {
		t.Fatalf("expected 1 trip summary, got %d", len(res.Data.Trips))
	}
	trip := res.Data.Trips[0]
	if trip.SourceID != "trip-001" || *trip.DistanceKm != 18.5 || trip.StartAddress != "杭州市西湖区" {
		t.Fatalf("trip summary data mismatch: %+v", trip)
	}

	// 3. Post charge summaries
	chgEnergy := 42.0
	chgCost := 48.5
	isFast := true
	chargePayload := map[string]any{
		"charges": []chargeSummaryItem{
			{
				Source:      "fleet",
				SourceID:    "charge-001",
				StartedAt:   startTime,
				EndedAt:     endTime,
				EnergyKwh:   &chgEnergy,
				CostYuan:    &chgCost,
				StationName: "特来电快充站",
				ChargerType: "GB_T",
				IsFast:      &isFast,
			},
		},
	}
	chgBytes, _ := json.Marshal(chargePayload)
	req = httptest.NewRequest(http.MethodPost, "/api/v1/cars/1/summaries/charges", bytes.NewReader(chgBytes))
	rec = httptest.NewRecorder()
	app.summariesResource(rec, req, "test-user-1", 1, []string{"charges"})
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d: %s", rec.Code, rec.Body.String())
	}

	// 4. Query charge summaries
	req = httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/summaries/charges", nil)
	rec = httptest.NewRecorder()
	app.summariesResource(rec, req, "test-user-1", 1, []string{"charges"})
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var chgRes struct {
		Data struct {
			Charges []chargeSummaryItem `json:"charges"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &chgRes); err != nil {
		t.Fatal(err)
	}
	if len(chgRes.Data.Charges) != 1 {
		t.Fatalf("expected 1 charge summary, got %d", len(chgRes.Data.Charges))
	}
	chg := chgRes.Data.Charges[0]
	if chg.StationName != "特来电快充站" || *chg.EnergyKwh != 42.0 || *chg.IsFast != true {
		t.Fatalf("charge summary data mismatch: %+v", chg)
	}

	// 5. Cross-user isolation test: user-2 should see 0 summaries for vehicle 1
	req = httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/summaries/trips", nil)
	rec = httptest.NewRecorder()
	app.summariesResource(rec, req, "test-user-2", 1, []string{"trips"})
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}
	var resUser2 struct {
		Data struct {
			Trips []tripSummaryItem `json:"trips"`
		} `json:"data"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &resUser2)
	if len(resUser2.Data.Trips) != 0 {
		t.Fatalf("expected 0 trips for user-2, got %d", len(resUser2.Data.Trips))
	}
}
