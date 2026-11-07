package main

import (
	"encoding/json"
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
	if _, ok := application.nearbyCacheGet("user", 7); ok {
		t.Fatal("empty cache must miss")
	}
	sites := []chargingSite{{Name: "SC", Type: "supercharger"}}
	application.nearbyCachePut("user", 7, sites)
	got, ok := application.nearbyCacheGet("user", 7)
	if !ok || len(got) != 1 || got[0].Name != "SC" {
		t.Fatalf("cache get = %#v, %v", got, ok)
	}
	if _, ok := application.nearbyCacheGet("user", 8); ok {
		t.Fatal("different vehicle must miss")
	}
	application.nearbyMu.Lock()
	application.nearbyCache[nearbyCacheKey("user", 7)] = nearbyCacheEntry{sites: sites, fetchedAt: time.Now().Add(-nearbyChargingCacheTTL - time.Second)}
	application.nearbyMu.Unlock()
	if _, ok := application.nearbyCacheGet("user", 7); ok {
		t.Fatal("expired entry must miss")
	}
}
