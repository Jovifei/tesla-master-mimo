package main

import (
	"context"
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHaversineMeters(t *testing.T) {
	// 0.001 degrees of latitude is ~111.19 m anywhere on earth.
	if got := haversineMeters(30.2741, 120.1551, 30.2751, 120.1551); math.Abs(got-111.19) > 1 {
		t.Fatalf("0.001 deg latitude = %.2f m, want ~111.19", got)
	}
	if got := haversineMeters(30.2741, 120.1551, 30.2741, 120.1551); got != 0 {
		t.Fatalf("same point distance = %v, want 0", got)
	}
}

func TestNormalizeVehiclePlace(t *testing.T) {
	place, code := normalizeVehiclePlace(" 家 ", 30.2741, 120.1551, nil)
	if code != "" || place.Name != "家" || place.RadiusM != defaultPlaceRadiusM {
		t.Fatalf("normalize = %+v, code %q", place, code)
	}
	radius := 500
	if place, code := normalizeVehiclePlace("公司", 31, 121, &radius); code != "" || place.RadiusM != 500 {
		t.Fatalf("explicit radius = %+v, code %q", place, code)
	}
	for _, tc := range []struct {
		name   string
		lat    float64
		lng    float64
		radius *int
	}{
		{"", 30, 120, nil},
		{strings.Repeat("长", maxPlaceNameRunes+1), 30, 120, nil},
		{"x", 91, 120, nil},
		{"x", math.NaN(), 120, nil},
		{"x", 30, -181, nil},
		{"x", 30, 120, placeRadiusPointer(minPlaceRadiusM - 1)},
		{"x", 30, 120, placeRadiusPointer(maxPlaceRadiusM + 1)},
	} {
		if _, code := normalizeVehiclePlace(tc.name, tc.lat, tc.lng, tc.radius); code == "" {
			t.Fatalf("input %+v must be rejected", tc)
		}
	}
}

func placeRadiusPointer(value int) *int { return &value }

func TestMatchVehiclePlace(t *testing.T) {
	home := vehiclePlace{ID: 1, Name: "家", Latitude: 30.2741, Longitude: 120.1551, RadiusM: 200}
	office := vehiclePlace{ID: 2, Name: "公司", Latitude: 30.2751, Longitude: 120.1551, RadiusM: 200}
	if _, ok := matchVehiclePlace(nil, 30.2741, 120.1551); ok {
		t.Fatal("no places must not match")
	}
	if got, ok := matchVehiclePlace([]vehiclePlace{home, office}, 30.27415, 120.1551); !ok || got.Name != "家" {
		t.Fatalf("near home = %+v, %v", got, ok)
	}
	// Between both centres (~55 m from each): the nearest centre wins.
	if got, ok := matchVehiclePlace([]vehiclePlace{home, office}, 30.27455, 120.1551); !ok || got.Name != "家" {
		t.Fatalf("slightly nearer home = %+v, %v", got, ok)
	}
	if got, ok := matchVehiclePlace([]vehiclePlace{home, office}, 30.27465, 120.1551); !ok || got.Name != "公司" {
		t.Fatalf("slightly nearer office = %+v, %v", got, ok)
	}
	// ~1.1 km north of the office centre: outside every radius.
	if _, ok := matchVehiclePlace([]vehiclePlace{home, office}, 30.2851, 120.1551); ok {
		t.Fatal("far point must not match")
	}
}

type fakePlacesStore struct {
	places []vehiclePlace
	nextID int64
}

func (f *fakePlacesStore) listVehiclePlaces(_ context.Context, _ string, _ int) ([]vehiclePlace, error) {
	return append([]vehiclePlace{}, f.places...), nil
}

func (f *fakePlacesStore) insertVehiclePlace(_ context.Context, _ string, _ int, place vehiclePlace) (vehiclePlace, error) {
	if len(f.places) >= maxPlacesPerVehicle {
		return vehiclePlace{}, errTooManyPlaces
	}
	f.nextID++
	place.ID = f.nextID
	f.places = append(f.places, place)
	return place, nil
}

func (f *fakePlacesStore) deleteVehiclePlace(_ context.Context, _ string, _ int, placeID int64) (bool, error) {
	for i, place := range f.places {
		if place.ID == placeID {
			f.places = append(f.places[:i], f.places[i+1:]...)
			return true, nil
		}
	}
	return false, nil
}

func TestVehiclePlacesResourceFlow(t *testing.T) {
	fake := &fakePlacesStore{}
	a := &app{
		provider: testProvider{vehicles: map[string][]vehicle{"user-a": {{ID: 1}}}},
		places:   fake,
	}
	call := func(method, path, body string) *httptest.ResponseRecorder {
		recorder := httptest.NewRecorder()
		var reader *strings.Reader
		if body != "" {
			reader = strings.NewReader(body)
		} else {
			reader = strings.NewReader("")
		}
		a.carResource(recorder, httptest.NewRequest(method, path, reader), "user-a", path)
		return recorder
	}

	create := call(http.MethodPost, "/api/v1/cars/1/places", `{"name":"家","latitude":30.2741,"longitude":120.1551}`)
	if create.Code != http.StatusOK {
		t.Fatalf("create status = %d, body = %s", create.Code, create.Body.String())
	}
	var created struct {
		Data vehiclePlace `json:"data"`
	}
	if err := json.Unmarshal(create.Body.Bytes(), &created); err != nil || created.Data.ID != 1 || created.Data.RadiusM != defaultPlaceRadiusM {
		t.Fatalf("created = %s, err = %v", create.Body.String(), err)
	}

	list := call(http.MethodGet, "/api/v1/cars/1/places", "")
	var listed struct {
		Data struct {
			Places []vehiclePlace `json:"places"`
		} `json:"data"`
	}
	if err := json.Unmarshal(list.Body.Bytes(), &listed); err != nil || len(listed.Data.Places) != 1 || listed.Data.Places[0].Name != "家" {
		t.Fatalf("list = %s, err = %v", list.Body.String(), err)
	}

	if bad := call(http.MethodPost, "/api/v1/cars/1/places", `{"name":"","latitude":30,"longitude":120}`); bad.Code != http.StatusBadRequest {
		t.Fatalf("empty name status = %d, want 400", bad.Code)
	}
	if bad := call(http.MethodPost, "/api/v1/cars/1/places", `{"name":"x","latitude":91,"longitude":120}`); bad.Code != http.StatusBadRequest {
		t.Fatalf("bad latitude status = %d, want 400", bad.Code)
	}

	if deleted := call(http.MethodDelete, "/api/v1/cars/1/places/1", ""); deleted.Code != http.StatusOK {
		t.Fatalf("delete status = %d, body = %s", deleted.Code, deleted.Body.String())
	}
	if again := call(http.MethodDelete, "/api/v1/cars/1/places/1", ""); again.Code != http.StatusNotFound {
		t.Fatalf("second delete status = %d, want 404", again.Code)
	}
	if name := a.matchedPlaceName(context.Background(), "user-a", 1, 30.2741, 120.1551); name != "" {
		t.Fatalf("after deletion the geofence label must be empty, got %q", name)
	}
}

func TestMatchedPlaceNameViaBackend(t *testing.T) {
	fake := &fakePlacesStore{places: []vehiclePlace{
		{ID: 1, Name: "家", Latitude: 30.2741, Longitude: 120.1551, RadiusM: 200},
	}}
	a := &app{places: fake}
	if name := a.matchedPlaceName(context.Background(), "user-a", 1, 30.2742, 120.1551); name != "家" {
		t.Fatalf("matched place = %q, want 家", name)
	}
	if name := a.matchedPlaceName(context.Background(), "user-a", 1, 31.2741, 120.1551); name != "" {
		t.Fatalf("far vehicle must have no label, got %q", name)
	}
	if name := (&app{}).matchedPlaceName(context.Background(), "user-a", 1, 30.2741, 120.1551); name != "" {
		t.Fatalf("no backend must yield no label, got %q", name)
	}
}
