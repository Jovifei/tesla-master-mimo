package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Named places / geofence-lite (design 2026-11-05 §7): the user names
// places for a vehicle; the status serializer fills car_geodata.geofence
// with the name of the place the vehicle is currently inside (the app
// already renders it). This is naming only — pricing stays on the
// existing time-of-use tariffs, unlike TeslaMate's per-geofence pricing.

const (
	defaultPlaceRadiusM = 200
	minPlaceRadiusM     = 50
	maxPlaceRadiusM     = 2000
	maxPlacesPerVehicle = 20
	maxPlaceNameRunes   = 40
)

type vehiclePlace struct {
	ID        int64   `json:"id"`
	Name      string  `json:"name"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
	RadiusM   int     `json:"radius_m"`
}

var errTooManyPlaces = errors.New("too_many_places")

// vehiclePlacesStore is the persistence surface the places handlers
// use; *store implements it in production.
type vehiclePlacesStore interface {
	listVehiclePlaces(ctx context.Context, userID string, vehicleID int) ([]vehiclePlace, error)
	insertVehiclePlace(ctx context.Context, userID string, vehicleID int, place vehiclePlace) (vehiclePlace, error)
	deleteVehiclePlace(ctx context.Context, userID string, vehicleID int, placeID int64) (bool, error)
}

func ensurePlaceSchema(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx, `
CREATE TABLE IF NOT EXISTS jourvolt_vehicle_places (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL,
    vehicle_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    radius_m INTEGER NOT NULL DEFAULT 200,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS jourvolt_vehicle_places_lookup
    ON jourvolt_vehicle_places (user_id, vehicle_id, id);`)
	return err
}

func (s *store) listVehiclePlaces(ctx context.Context, userID string, vehicleID int) ([]vehiclePlace, error) {
	rows, err := s.pool.Query(ctx, `
SELECT id, name, latitude, longitude, radius_m FROM jourvolt_vehicle_places
WHERE user_id = $1 AND vehicle_id = $2 ORDER BY id ASC`, userID, vehicleID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	places := []vehiclePlace{}
	for rows.Next() {
		var place vehiclePlace
		if err := rows.Scan(&place.ID, &place.Name, &place.Latitude, &place.Longitude, &place.RadiusM); err != nil {
			return nil, err
		}
		places = append(places, place)
	}
	return places, rows.Err()
}

func (s *store) insertVehiclePlace(ctx context.Context, userID string, vehicleID int, place vehiclePlace) (vehiclePlace, error) {
	var count int
	if err := s.pool.QueryRow(ctx, `
SELECT COUNT(*) FROM jourvolt_vehicle_places WHERE user_id = $1 AND vehicle_id = $2`, userID, vehicleID).Scan(&count); err != nil {
		return vehiclePlace{}, err
	}
	if count >= maxPlacesPerVehicle {
		return vehiclePlace{}, errTooManyPlaces
	}
	err := s.pool.QueryRow(ctx, `
INSERT INTO jourvolt_vehicle_places(user_id, vehicle_id, name, latitude, longitude, radius_m)
VALUES ($1, $2, $3, $4, $5, $6) RETURNING id`,
		userID, vehicleID, place.Name, place.Latitude, place.Longitude, place.RadiusM).Scan(&place.ID)
	return place, err
}

func (s *store) deleteVehiclePlace(ctx context.Context, userID string, vehicleID int, placeID int64) (bool, error) {
	tag, err := s.pool.Exec(ctx, `
DELETE FROM jourvolt_vehicle_places WHERE id = $1 AND user_id = $2 AND vehicle_id = $3`,
		placeID, userID, vehicleID)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() > 0, nil
}

// normalizeVehiclePlace validates create input and applies defaults.
// The returned code is the API error class when validation fails.
func normalizeVehiclePlace(name string, latitude, longitude float64, radiusM *int) (vehiclePlace, string) {
	name = strings.TrimSpace(name)
	if name == "" || utf8.RuneCountInString(name) > maxPlaceNameRunes {
		return vehiclePlace{}, "invalid_place_name"
	}
	if math.IsNaN(latitude) || math.IsInf(latitude, 0) || latitude < -90 || latitude > 90 ||
		math.IsNaN(longitude) || math.IsInf(longitude, 0) || longitude < -180 || longitude > 180 {
		return vehiclePlace{}, "invalid_place_coordinates"
	}
	radius := defaultPlaceRadiusM
	if radiusM != nil {
		radius = *radiusM
	}
	if radius < minPlaceRadiusM || radius > maxPlaceRadiusM {
		return vehiclePlace{}, "invalid_place_radius"
	}
	return vehiclePlace{Name: name, Latitude: latitude, Longitude: longitude, RadiusM: radius}, ""
}

func haversineMeters(lat1, lng1, lat2, lng2 float64) float64 {
	const earthRadiusM = 6371000.0
	toRad := func(deg float64) float64 { return deg * math.Pi / 180 }
	dLat := toRad(lat2 - lat1)
	dLng := toRad(lng2 - lng1)
	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(toRad(lat1))*math.Cos(toRad(lat2))*math.Sin(dLng/2)*math.Sin(dLng/2)
	return 2 * earthRadiusM * math.Asin(math.Sqrt(a))
}

// matchVehiclePlace returns the place whose radius covers the point;
// when several match, the nearest centre wins.
func matchVehiclePlace(places []vehiclePlace, latitude, longitude float64) (vehiclePlace, bool) {
	var best vehiclePlace
	bestDistance := math.Inf(1)
	found := false
	for _, place := range places {
		distance := haversineMeters(latitude, longitude, place.Latitude, place.Longitude)
		if distance <= float64(place.RadiusM) && distance < bestDistance {
			best, bestDistance, found = place, distance, true
		}
	}
	return best, found
}

func (a *app) placesBackend() vehiclePlacesStore {
	if a.places != nil {
		return a.places
	}
	if a.store != nil {
		return a.store
	}
	return nil
}

// matchedPlaceName resolves the geofence label for the status payload.
// Any failure leaves the label empty: a missing geofence must never
// break the status response.
func (a *app) matchedPlaceName(ctx context.Context, userID string, vehicleID int, latitude, longitude float64) string {
	backend := a.placesBackend()
	if backend == nil {
		return ""
	}
	places, err := backend.listVehiclePlaces(ctx, userID, vehicleID)
	if err != nil || len(places) == 0 {
		return ""
	}
	if place, ok := matchVehiclePlace(places, latitude, longitude); ok {
		return place.Name
	}
	return ""
}

type vehiclePlaceRequest struct {
	Name      string  `json:"name"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
	RadiusM   *int    `json:"radius_m"`
}

func (a *app) vehiclePlacesResource(w http.ResponseWriter, r *http.Request, userID string, carID int, parts []string) {
	backend := a.placesBackend()
	if backend == nil {
		a.json(w, http.StatusServiceUnavailable, map[string]string{"error": "places_unavailable"})
		return
	}
	switch {
	case r.Method == http.MethodGet && len(parts) == 2:
		places, err := backend.listVehiclePlaces(r.Context(), userID, carID)
		if err != nil {
			a.json(w, http.StatusBadGateway, map[string]string{"error": "places_error"})
			return
		}
		if places == nil {
			places = []vehiclePlace{}
		}
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{"places": places}})
	case r.Method == http.MethodPost && len(parts) == 2:
		body, err := io.ReadAll(io.LimitReader(r.Body, 16<<10))
		if err != nil {
			a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_place"})
			return
		}
		var request vehiclePlaceRequest
		if err := json.Unmarshal(body, &request); err != nil {
			a.json(w, http.StatusBadRequest, map[string]string{"error": "invalid_place"})
			return
		}
		place, code := normalizeVehiclePlace(request.Name, request.Latitude, request.Longitude, request.RadiusM)
		if code != "" {
			a.json(w, http.StatusBadRequest, map[string]string{"error": code})
			return
		}
		created, err := backend.insertVehiclePlace(r.Context(), userID, carID, place)
		if errors.Is(err, errTooManyPlaces) {
			a.json(w, http.StatusConflict, map[string]string{"error": "too_many_places"})
			return
		}
		if err != nil {
			a.json(w, http.StatusBadGateway, map[string]string{"error": "places_error"})
			return
		}
		a.json(w, http.StatusOK, map[string]any{"data": created})
	case r.Method == http.MethodDelete && len(parts) == 3:
		placeID, err := strconv.ParseInt(parts[2], 10, 64)
		if err != nil || placeID <= 0 {
			a.json(w, http.StatusNotFound, map[string]string{"error": "place_not_found"})
			return
		}
		deleted, err := backend.deleteVehiclePlace(r.Context(), userID, carID, placeID)
		if err != nil {
			a.json(w, http.StatusBadGateway, map[string]string{"error": "places_error"})
			return
		}
		if !deleted {
			a.json(w, http.StatusNotFound, map[string]string{"error": "place_not_found"})
			return
		}
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{"deleted": true}})
	default:
		a.json(w, http.StatusNotFound, map[string]string{"error": "not_found"})
	}
}
