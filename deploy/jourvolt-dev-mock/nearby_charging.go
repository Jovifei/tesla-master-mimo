package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Nearby charging sites (design 2026-11-05 §5): a read-only proxy of the
// Fleet API nearby_charging_sites endpoint. On-demand only — the app calls
// it when the user opens the charging view — and cached for ten minutes per
// vehicle so repeated opens do not burn Fleet quota.

type chargingSite struct {
	Name            string   `json:"name"`
	Type            string   `json:"type"` // "supercharger" | "destination"
	Latitude        *float64 `json:"latitude"`
	Longitude       *float64 `json:"longitude"`
	DistanceKM      *float64 `json:"distance_km"`
	AvailableStalls *int     `json:"available_stalls"`
	TotalStalls     *int     `json:"total_stalls"`
	SiteClosed      *bool    `json:"site_closed"`
}

type teslaNearbyChargingEnvelope struct {
	Response struct {
		Superchargers       []teslaChargingSite `json:"superchargers"`
		DestinationChargers []teslaChargingSite `json:"destination_charging"`
	} `json:"response"`
}

type teslaChargingSite struct {
	Name     *string `json:"name"`
	Location *struct {
		Latitude  *float64 `json:"lat"`
		Longitude *float64 `json:"long"`
	} `json:"location"`
	DistanceMiles   *float64 `json:"distance_miles"`
	AvailableStalls *int     `json:"available_stalls"`
	TotalStalls     *int     `json:"total_stalls"`
	SiteClosed      *bool    `json:"site_closed"`
}

func fleetNearbyChargingPath(vin string) string {
	return "/api/1/vehicles/" + url.PathEscape(vin) + "/nearby_charging_sites"
}

// mapNearbyChargingSites normalizes Tesla's two lists into one distance-
// sorted list in metric units. Fields Tesla omits stay nil; sites are never
// invented or dropped.
func mapNearbyChargingSites(envelope teslaNearbyChargingEnvelope) []chargingSite {
	sites := make([]chargingSite, 0, len(envelope.Response.Superchargers)+len(envelope.Response.DestinationChargers))
	appendSites := func(list []teslaChargingSite, siteType string) {
		for _, raw := range list {
			site := chargingSite{Type: siteType}
			if raw.Name != nil {
				site.Name = *raw.Name
			}
			if raw.Location != nil {
				site.Latitude = raw.Location.Latitude
				site.Longitude = raw.Location.Longitude
			}
			if raw.DistanceMiles != nil {
				km := *raw.DistanceMiles * milesToKilometres
				site.DistanceKM = &km
			}
			if siteType == "supercharger" {
				site.AvailableStalls = raw.AvailableStalls
				site.TotalStalls = raw.TotalStalls
				site.SiteClosed = raw.SiteClosed
			}
			sites = append(sites, site)
		}
	}
	appendSites(envelope.Response.Superchargers, "supercharger")
	appendSites(envelope.Response.DestinationChargers, "destination")
	sort.SliceStable(sites, func(i, j int) bool {
		left, right := sites[i].DistanceKM, sites[j].DistanceKM
		if left == nil {
			return false
		}
		if right == nil {
			return true
		}
		return *left < *right
	})
	return sites
}

func (p *fleetProvider) NearbyChargingSites(ctx context.Context, userID string, vehicleID int) ([]chargingSite, error) {
	stored, err := p.store.fleetVehicle(ctx, userID, vehicleID)
	if err != nil {
		if isVehicleLookupMiss(err) {
			return nil, errVehicleNotFound
		}
		return nil, err
	}
	vin, err := p.cipher.decrypt(stored.VINCiphertext)
	if err != nil {
		return nil, err
	}
	var envelope teslaNearbyChargingEnvelope
	if err := p.get(ctx, userID, fleetNearbyChargingPath(vin), &envelope); err != nil {
		return nil, err
	}
	return mapNearbyChargingSites(envelope), nil
}

// nearbyChargingProvider is the optional capability the HTTP layer looks
// for; providers without Fleet access (mock, unconfigured) simply do not
// implement it and the endpoint answers with an empty, clearly-labelled
// payload instead of an error.
type nearbyChargingProvider interface {
	NearbyChargingSites(context.Context, string, int) ([]chargingSite, error)
}

var errNearbyUnsupported = errors.New("nearby charging sites unsupported for this provider")

func (p routingProvider) NearbyChargingSites(ctx context.Context, userID string, vehicleID int) ([]chargingSite, error) {
	selected, ok := p.selectProvider(userID).(nearbyChargingProvider)
	if !ok {
		return nil, errNearbyUnsupported
	}
	return selected.NearbyChargingSites(ctx, userID, vehicleID)
}

const nearbyChargingCacheTTL = 10 * time.Minute

type nearbyCacheEntry struct {
	sites     []chargingSite
	fetchedAt time.Time
}

// nearbyCacheGet returns the cached sites when they are still fresh.
func (a *app) nearbyCacheGet(key string) ([]chargingSite, bool) {
	a.nearbyMu.Lock()
	defer a.nearbyMu.Unlock()
	entry, ok := a.nearbyCache[key]
	if !ok || time.Since(entry.fetchedAt) > nearbyChargingCacheTTL {
		return nil, false
	}
	return entry.sites, true
}

func (a *app) nearbyCachePut(key string, sites []chargingSite) {
	a.nearbyMu.Lock()
	defer a.nearbyMu.Unlock()
	if a.nearbyCache == nil {
		a.nearbyCache = make(map[string]nearbyCacheEntry)
	}
	a.nearbyCache[key] = nearbyCacheEntry{sites: sites, fetchedAt: time.Now()}
}

func nearbyCacheKey(userID string, carID int) string {
	return userID + "/" + strconv.Itoa(carID)
}

func amapNearbyCacheKey(userID string, carID int, latitude, longitude float64) string {
	return fmt.Sprintf("%s/%.3f,%.3f", nearbyCacheKey(userID, carID), latitude, longitude)
}

// amapPlaceAroundURL is a package variable so tests can point the POI
// fallback at a local server without network access.
var amapPlaceAroundURL = "https://restapi.amap.com/v3/place/around"

var nearbyHTTPClient = &http.Client{Timeout: 8 * time.Second}

type amapPlace struct {
	Name     string `json:"name"`
	Location string `json:"location"`
	Distance string `json:"distance"`
}

type amapPlaceAroundResponse struct {
	Status string      `json:"status"`
	Info   string      `json:"info"`
	POIs   []amapPlace `json:"pois"`
}

func amapCoordinatePair(value string) (*float64, *float64, bool) {
	parts := strings.Split(value, ",")
	if len(parts) != 2 {
		return nil, nil, false
	}
	longitude, lngErr := strconv.ParseFloat(strings.TrimSpace(parts[0]), 64)
	latitude, latErr := strconv.ParseFloat(strings.TrimSpace(parts[1]), 64)
	if lngErr != nil || latErr != nil || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 {
		return nil, nil, false
	}
	return &latitude, &longitude, true
}

func amapDistanceKM(value string) *float64 {
	meters, err := strconv.ParseFloat(strings.TrimSpace(value), 64)
	if err != nil || meters < 0 {
		return nil
	}
	km := meters / 1000
	return &km
}

// amapNearbyChargingSites is the configured fallback when the Tesla account
// cannot use Fleet nearby_charging_sites. Amap requires GCJ-02 coordinates;
// callers convert before querying. Only map facts are forwarded, never the
// upstream address or the service key.
func amapNearbyChargingSites(ctx context.Context, key string, latitude, longitude float64) ([]chargingSite, error) {
	if strings.TrimSpace(key) == "" {
		return nil, errNearbyUnsupported
	}
	query := url.Values{}
	query.Set("key", key)
	query.Set("location", fmt.Sprintf("%.6f,%.6f", longitude, latitude))
	query.Set("keywords", "充电站")
	query.Set("radius", "5000")
	query.Set("offset", "25")
	query.Set("page", "1")
	query.Set("extensions", "base")
	query.Set("sortrule", "distance")
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, amapPlaceAroundURL+"?"+query.Encode(), nil)
	if err != nil {
		return nil, err
	}
	response, err := nearbyHTTPClient.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("amap status %d", response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 256<<10))
	if err != nil {
		return nil, err
	}
	var decoded amapPlaceAroundResponse
	if err := json.Unmarshal(body, &decoded); err != nil {
		return nil, err
	}
	if decoded.Status != "1" {
		return nil, fmt.Errorf("amap query rejected")
	}
	sites := make([]chargingSite, 0, len(decoded.POIs))
	for _, poi := range decoded.POIs {
		name := strings.TrimSpace(poi.Name)
		latitude, longitude, ok := amapCoordinatePair(poi.Location)
		if name == "" || !ok {
			continue
		}
		sites = append(sites, chargingSite{
			Name: name, Type: "poi", Latitude: latitude, Longitude: longitude,
			DistanceKM: amapDistanceKM(poi.Distance),
		})
	}
	sort.SliceStable(sites, func(i, j int) bool {
		left, right := sites[i].DistanceKM, sites[j].DistanceKM
		if left == nil {
			return false
		}
		if right == nil {
			return true
		}
		return *left < *right
	})
	return sites, nil
}

func nearbyCoordinates(r *http.Request) (float64, float64, bool) {
	latitude, latErr := strconv.ParseFloat(strings.TrimSpace(r.URL.Query().Get("lat")), 64)
	longitude, lngErr := strconv.ParseFloat(strings.TrimSpace(r.URL.Query().Get("lng")), 64)
	if latErr != nil || lngErr != nil || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 {
		return 0, 0, false
	}
	return latitude, longitude, true
}

func (a *app) nearbyChargingSites(w http.ResponseWriter, r *http.Request, userID string, carID int) {
	latitude, longitude, hasLocation := nearbyCoordinates(r)
	key := nearbyCacheKey(userID, carID)
	if hasLocation {
		key = amapNearbyCacheKey(userID, carID, latitude, longitude)
	}
	if sites, ok := a.nearbyCacheGet(key); ok {
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
			"sites": sites, "meta": map[string]any{"source": "cache"},
		}})
		return
	}
	if provider, ok := a.provider.(nearbyChargingProvider); ok {
		sites, err := provider.NearbyChargingSites(r.Context(), userID, carID)
		if err == nil {
			if sites == nil {
				sites = []chargingSite{}
			}
			a.nearbyCachePut(nearbyCacheKey(userID, carID), sites)
			a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
				"sites": sites, "meta": map[string]any{"source": "fleet_api"},
			}})
			return
		}
		// A Fleet account without the nearby-charging scope, or a transient
		// Fleet failure, both fall through to the configured POI provider.
	}
	if strings.TrimSpace(a.amapKey) != "" && hasLocation {
		sites, err := amapNearbyChargingSites(r.Context(), a.amapKey, latitude, longitude)
		if err == nil {
			a.nearbyCachePut(key, sites)
			a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
				"sites": sites, "meta": map[string]any{"source": "amap"},
			}})
			return
		}
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
			"sites": []chargingSite{},
			"meta":  map[string]any{"source": "amap_unavailable", "reason": "amap_query_failed"},
		}})
		return
	}
	reason := "nearby_requires_fleet_or_amap_key"
	if strings.TrimSpace(a.amapKey) != "" && !hasLocation {
		reason = "nearby_requires_vehicle_location"
	}
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
		"sites": []chargingSite{}, "meta": map[string]any{"source": "unsupported", "reason": reason},
	}})
}
