package main

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"sort"
	"strconv"
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
func (a *app) nearbyCacheGet(userID string, carID int) ([]chargingSite, bool) {
	a.nearbyMu.Lock()
	defer a.nearbyMu.Unlock()
	entry, ok := a.nearbyCache[nearbyCacheKey(userID, carID)]
	if !ok || time.Since(entry.fetchedAt) > nearbyChargingCacheTTL {
		return nil, false
	}
	return entry.sites, true
}

func (a *app) nearbyCachePut(userID string, carID int, sites []chargingSite) {
	a.nearbyMu.Lock()
	defer a.nearbyMu.Unlock()
	if a.nearbyCache == nil {
		a.nearbyCache = make(map[string]nearbyCacheEntry)
	}
	a.nearbyCache[nearbyCacheKey(userID, carID)] = nearbyCacheEntry{sites: sites, fetchedAt: time.Now()}
}

func nearbyCacheKey(userID string, carID int) string {
	return userID + "/" + strconv.Itoa(carID)
}

func (a *app) nearbyChargingSites(w http.ResponseWriter, r *http.Request, userID string, carID int) {
	if sites, ok := a.nearbyCacheGet(userID, carID); ok {
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
			"sites": sites, "meta": map[string]any{"source": "cache"},
		}})
		return
	}
	provider, ok := a.provider.(nearbyChargingProvider)
	if !ok {
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
			"sites": []chargingSite{}, "meta": map[string]any{"source": "unsupported"},
		}})
		return
	}
	sites, err := provider.NearbyChargingSites(r.Context(), userID, carID)
	if errors.Is(err, errNearbyUnsupported) {
		a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
			"sites": []chargingSite{}, "meta": map[string]any{"source": "unsupported"},
		}})
		return
	}
	if err != nil {
		a.providerError(w, err)
		return
	}
	if sites == nil {
		sites = []chargingSite{}
	}
	a.nearbyCachePut(userID, carID, sites)
	a.json(w, http.StatusOK, map[string]any{"data": map[string]any{
		"sites": sites, "meta": map[string]any{"source": "fleet_api"},
	}})
}
