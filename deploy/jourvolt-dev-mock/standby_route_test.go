package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCompatibilityStandbyRouteReturnsCollectingState(t *testing.T) {
	a := &app{provider: testProvider{
		vehicles: map[string][]vehicle{"user-a": {{ID: 7}}},
	}}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/api/matelink/v1/cars/7/standby", nil)
	a.compat(recorder, request, "user-a")

	if recorder.Code != http.StatusOK {
		t.Fatalf("standby status = %d, body=%s", recorder.Code, recorder.Body.String())
	}
	var response struct {
		Data struct {
			Windows []json.RawMessage `json:"windows"`
			Meta    struct {
				Availability string `json:"availability"`
				Source       string `json:"source"`
			} `json:"meta"`
		} `json:"data"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("decode standby response: %v", err)
	}
	if len(response.Data.Windows) != 0 || response.Data.Meta.Availability != "collecting" {
		t.Fatalf("standby response = %#v, want empty collecting data", response.Data)
	}
}
