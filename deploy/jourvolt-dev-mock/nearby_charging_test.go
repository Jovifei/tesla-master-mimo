package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestMapNearbyChargingSitesMergesSortsAndConverts(t *testing.T) {
	var envelope teslaNearbyChargingEnvelope
	body := []byte(`{"response":{
		"superchargers":[
			{"name":"Far SC","location":{"lat":30.3,"long":120.2},"distance_miles":10.0,"available_stalls":3,"total_stalls":8,"site_closed":false},
			{"name":"Near SC","location":{"lat":30.1,"long":120.1},"distance_miles":2.0,"available_stalls":5,"total_stalls":6,"site_closed":false}],
		"destination_charging":[
			{"name":"Hotel","location":{"lat":30.2,"long":120.15},"distance_miles":5.0}]
	}}`)
	if err := json.Unmarshal(body, &envelope); err != nil {
		t.Fatalf("decode: %v", err)
	}
	sites := mapNearbyChargingSites(envelope)
	if len(sites) != 3 {
		t.Fatalf("sites = %d, want 3", len(sites))
	}
	if sites[0].Name != "Near SC" || sites[1].Name != "Hotel" || sites[2].Name != "Far SC" {
		t.Fatalf("order = %q,%q,%q", sites[0].Name, sites[1].Name, sites[2].Name)
	}
	if sites[0].Type != "supercharger" || sites[1].Type != "destination" {
		t.Fatalf("types = %q,%q", sites[0].Type, sites[1].Type)
	}
	if sites[0].DistanceKM == nil || *sites[0].DistanceKM != 2.0*milesToKilometres {
		t.Fatalf("distance_km = %#v", sites[0].DistanceKM)
	}
	if sites[0].AvailableStalls == nil || *sites[0].AvailableStalls != 5 || sites[0].TotalStalls == nil || *sites[0].TotalStalls != 6 {
		t.Fatalf("stalls = %#v/%#v", sites[0].AvailableStalls, sites[0].TotalStalls)
	}
	if sites[1].AvailableStalls != nil || sites[1].TotalStalls != nil {
		t.Fatalf("destination stalls must stay nil: %#v", sites[1])
	}
	if sites[0].Latitude == nil || *sites[0].Latitude != 30.1 {
		t.Fatalf("latitude = %#v", sites[0].Latitude)
	}
}

func TestMapNearbyChargingSitesEmpty(t *testing.T) {
	if sites := mapNearbyChargingSites(teslaNearbyChargingEnvelope{}); len(sites) != 0 {
		t.Fatalf("sites = %d, want 0", len(sites))
	}
}

func TestFleetNearbyChargingPath(t *testing.T) {
	if got := fleetNearbyChargingPath("VIN 123"); got != "/api/1/vehicles/VIN%20123/nearby_charging_sites" {
		t.Fatalf("path = %q", got)
	}
}

func TestNearbyCacheFreshness(t *testing.T) {
	application := &app{}
	key := nearbyCacheKey("user", 7)
	if _, ok := application.nearbyCacheGet(key); ok {
		t.Fatal("empty cache must miss")
	}
	sites := []chargingSite{{Name: "SC", Type: "supercharger"}}
	application.nearbyCachePut(key, sites)
	got, ok := application.nearbyCacheGet(key)
	if !ok || len(got) != 1 || got[0].Name != "SC" {
		t.Fatalf("cache get = %#v, %v", got, ok)
	}
	if _, ok := application.nearbyCacheGet(nearbyCacheKey("user", 8)); ok {
		t.Fatal("different vehicle must miss")
	}
	application.nearbyMu.Lock()
	application.nearbyCache[key] = nearbyCacheEntry{sites: sites, fetchedAt: time.Now().Add(-nearbyChargingCacheTTL - time.Second)}
	application.nearbyMu.Unlock()
	if _, ok := application.nearbyCacheGet(key); ok {
		t.Fatal("expired entry must miss")
	}
}

func TestAmapNearbyChargingSitesNormalizesPOIs(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("key") != "test-key" || r.URL.Query().Get("location") != "120.155100,30.274100" ||
			r.URL.Query().Get("keywords") != "充电站" || r.URL.Query().Get("sortrule") != "distance" {
			t.Errorf("query = %s", r.URL.RawQuery)
		}
		_, _ = w.Write([]byte(`{"status":"1","pois":[
			{"name":"远处充电站","location":"120.200000,30.300000","distance":"2600"},
			{"name":"附近充电站","location":"120.160000,30.280000","distance":"850"},
			{"name":"坐标无效","location":"bad","distance":"10"}
		]}`))
	}))
	defer upstream.Close()
	previous := amapPlaceAroundURL
	amapPlaceAroundURL = upstream.URL
	defer func() { amapPlaceAroundURL = previous }()

	sites, err := amapNearbyChargingSites(context.Background(), "test-key", 30.2741, 120.1551)
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	if len(sites) != 2 || sites[0].Name != "附近充电站" || sites[1].Name != "远处充电站" {
		t.Fatalf("sites = %#v", sites)
	}
	if sites[0].Type != "poi" || sites[0].DistanceKM == nil || *sites[0].DistanceKM != 0.85 {
		t.Fatalf("site = %#v", sites[0])
	}
	if sites[0].Latitude == nil || *sites[0].Latitude != 30.28 || sites[0].Longitude == nil || *sites[0].Longitude != 120.16 {
		t.Fatalf("coordinates = %#v/%#v", sites[0].Latitude, sites[0].Longitude)
	}
}

func TestAmapNearbyChargingSitesRejectsUpstreamError(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"status":"0","info":"INVALID_USER_KEY"}`))
	}))
	defer upstream.Close()
	previous := amapPlaceAroundURL
	amapPlaceAroundURL = upstream.URL
	defer func() { amapPlaceAroundURL = previous }()

	if sites, err := amapNearbyChargingSites(context.Background(), "bad-key", 30.2741, 120.1551); err == nil || sites != nil {
		t.Fatalf("error response accepted: %#v %v", sites, err)
	}
}

func TestNearbyCoordinatesRequiresBothValues(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/nearby-charging-sites?lat=30.27&lng=120.15", nil)
	if latitude, longitude, ok := nearbyCoordinates(request); !ok || latitude != 30.27 || longitude != 120.15 {
		t.Fatalf("coordinates = %v,%v ok=%v", latitude, longitude, ok)
	}
	if _, _, ok := nearbyCoordinates(httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/nearby-charging-sites?lat=30.27", nil)); ok {
		t.Fatal("missing longitude accepted")
	}
	if _, _, ok := nearbyCoordinates(httptest.NewRequest(http.MethodGet, "/api/v1/cars/1/nearby-charging-sites?lat=91&lng=120", nil)); ok {
		t.Fatal("out-of-range latitude accepted")
	}
}

func TestClimateHistoryDays(t *testing.T) {
	for raw, want := range map[string]int{"7": 7, "30": 30, "": 7, "14": 7, "365": 7} {
		if got := climateHistoryDays(raw); got != want {
			t.Fatalf("days(%q) = %d, want %d", raw, got, want)
		}
	}
}
