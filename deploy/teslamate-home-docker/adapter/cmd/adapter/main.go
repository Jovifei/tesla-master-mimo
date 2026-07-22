package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
)

type snapshot struct {
	Status       map[string]any    `json:"status"`
	Units        map[string]string `json:"units"`
	ObservedAt   string            `json:"observed_at"`
	Source       string            `json:"source"`
	FieldSources map[string]string `json:"field_sources"`
}

type parkedDetail struct {
	OlderDriveID       int      `json:"older_drive_id"`
	NewerDriveID       int      `json:"newer_drive_id"`
	StartDate          string   `json:"start_date"`
	EndDate            string   `json:"end_date"`
	Address            *string  `json:"address"`
	StartBatteryLevel  *int     `json:"start_battery_level"`
	EndBatteryLevel    *int     `json:"end_battery_level"`
	BatteryDelta       *int     `json:"battery_delta"`
	EnergyKWh          *float64 `json:"energy_kwh"`
	AveragePowerKW     *float64 `json:"average_power_kw"`
	PeakPowerKW        *float64 `json:"peak_power_kw"`
	InsideTempAverage  *float64 `json:"inside_temp_average"`
	OutsideTempAverage *float64 `json:"outside_temp_average"`
	SampleCount        int      `json:"sample_count"`
	CoverageSeconds    int64    `json:"coverage_seconds"`
	CoverageRatio      float64  `json:"coverage_ratio"`
	Source             string   `json:"source"`
}

type dataStore interface {
	Snapshot(context.Context, int) (snapshot, error)
	Parked(context.Context, int, int, int) (parkedDetail, error)
}

type postgresStore struct {
	db  *sql.DB
	loc *time.Location
}

type server struct {
	store    dataStore
	proxy    http.Handler
	apiToken string
}

func main() {
	db, err := sql.Open("pgx", env("DATABASE_URL", "postgres://teslamate:teslamate@database:5432/teslamate?sslmode=disable"))
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		log.Fatalf("database unavailable: %v", err)
	}
	loc, err := time.LoadLocation(env("TZ", "Asia/Shanghai"))
	if err != nil {
		loc = time.Local
	}
	upstream, err := url.Parse(env("UPSTREAM_URL", "http://teslamateapi:8080"))
	if err != nil {
		log.Fatal(err)
	}
	s := &server{store: &postgresStore{db: db, loc: loc}, proxy: httputil.NewSingleHostReverseProxy(upstream), apiToken: strings.TrimSpace(os.Getenv("API_TOKEN"))}
	log.Printf("MateLink Adapter listening on :8080")
	log.Fatal(http.ListenAndServe(":8080", s.routes()))
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/matelink/v1/capabilities", s.capabilities)
	mux.HandleFunc("GET /api/matelink/v1/cars/{carId}/snapshot", s.vehicleSnapshot)
	mux.HandleFunc("GET /api/matelink/v1/cars/{carId}/parked/{olderDriveId}/{newerDriveId}", s.parked)
	mux.Handle("/api/", s.proxy)
	return s.authenticate(mux)
}
func (s *server) authenticate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.apiToken != "" && r.Header.Get("Authorization") != "Bearer "+s.apiToken {
			writeError(w, 401, "invalid API token")
			return
		}
		next.ServeHTTP(w, r)
	})
}
func (s *server) capabilities(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, 200, map[string]any{"data": map[string]any{"adapter_version": "1", "features": []string{"snapshot", "parked_detail", "legacy_proxy"}}})
}
func (s *server) vehicleSnapshot(w http.ResponseWriter, r *http.Request) {
	id, err := pathInt(r, "carId")
	if err != nil {
		writeError(w, 400, err.Error())
		return
	}
	value, err := s.store.Snapshot(r.Context(), id)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, 404, "no vehicle snapshot")
		return
	}
	if err != nil {
		log.Printf("snapshot store request failed")
		writeError(w, 500, "snapshot unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"data": value})
}
func (s *server) parked(w http.ResponseWriter, r *http.Request) {
	carID, err := pathInt(r, "carId")
	if err != nil {
		writeError(w, 400, err.Error())
		return
	}
	olderID, err := pathInt(r, "olderDriveId")
	if err != nil {
		writeError(w, 400, err.Error())
		return
	}
	newerID, err := pathInt(r, "newerDriveId")
	if err != nil {
		writeError(w, 400, err.Error())
		return
	}
	value, err := s.store.Parked(r.Context(), carID, olderID, newerID)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, 404, "parked interval not found")
		return
	}
	if err != nil {
		log.Printf("parked-detail store request failed")
		writeError(w, 500, "parked data unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"data": value})
}

func (p *postgresStore) Snapshot(ctx context.Context, carID int) (snapshot, error) {
	const query = `SELECT p.date,c.name,st.state,st.start_date,p.odometer,p.latitude,p.longitude,p.speed,p.power,p.elevation,p.inside_temp,p.outside_temp,p.is_climate_on,p.battery_level,p.usable_battery_level,p.est_battery_range_km,p.rated_battery_range_km,p.ideal_battery_range_km,p.tpms_pressure_fl,p.tpms_pressure_fr,p.tpms_pressure_rl,p.tpms_pressure_rr FROM positions p JOIN cars c ON c.id=p.car_id LEFT JOIN LATERAL (SELECT state,start_date FROM states WHERE car_id=p.car_id ORDER BY start_date DESC LIMIT 1) st ON true WHERE p.car_id=$1 ORDER BY p.date DESC LIMIT 1`
	var observed time.Time
	var name, state sql.NullString
	var stateSince sql.NullTime
	var odometer, lat, lon, inside, outside sql.NullFloat64
	var speed, power, elevation, battery, usable sql.NullInt64
	var climate sql.NullBool
	var est, rated, ideal, fl, fr, rl, rr sql.NullFloat64
	err := p.db.QueryRowContext(ctx, query, carID).Scan(&observed, &name, &state, &stateSince, &odometer, &lat, &lon, &speed, &power, &elevation, &inside, &outside, &climate, &battery, &usable, &est, &rated, &ideal, &fl, &fr, &rl, &rr)
	if err != nil {
		return snapshot{}, err
	}
	status := map[string]any{"display_name": nullString(name), "state": nullString(state), "state_since": nullTime(stateSince, p.loc), "odometer": nullFloat(odometer), "car_status": map[string]any{"locked": nil, "doors_open": nil, "windows_open": nil}, "car_geodata": map[string]any{"latitude": nullFloat(lat), "longitude": nullFloat(lon)}, "driving_details": map[string]any{"speed": nullInt(speed), "power": nullInt(power), "elevation": nullInt(elevation)}, "climate_details": map[string]any{"inside_temp": nullFloat(inside), "outside_temp": nullFloat(outside), "is_climate_on": nullBool(climate)}, "battery_details": map[string]any{"battery_level": nullInt(battery), "usable_battery_level": nullInt(usable), "est_battery_range": nullFloat(est), "rated_battery_range": nullFloat(rated), "ideal_battery_range": nullFloat(ideal)}, "tpms_details": map[string]any{"tpms_pressure_fl": nullFloat(fl), "tpms_pressure_fr": nullFloat(fr), "tpms_pressure_rl": nullFloat(rl), "tpms_pressure_rr": nullFloat(rr)}}
	fields := map[string]string{}
	for _, key := range []string{"battery", "range", "odometer", "location", "temperature", "climate", "tpms", "state"} {
		fields[key] = "database_latest"
	}
	fields["locked"] = "unavailable"
	fields["doors"] = "unavailable"
	return snapshot{Status: status, Units: map[string]string{"unit_of_length": "km", "unit_of_pressure": "bar", "unit_of_temperature": "C"}, ObservedAt: formatTime(observed, p.loc), Source: "database_latest", FieldSources: fields}, nil
}

func (p *postgresStore) Parked(ctx context.Context, carID, olderID, newerID int) (parkedDetail, error) {
	const bounds = `SELECT older.end_date,newer.start_date,COALESCE(a.display_name,a.name),op.battery_level,np.battery_level FROM drives older JOIN drives newer ON newer.id=$3 AND newer.car_id=$1 LEFT JOIN addresses a ON a.id=older.end_address_id LEFT JOIN positions op ON op.id=older.end_position_id LEFT JOIN positions np ON np.id=newer.start_position_id WHERE older.id=$2 AND older.car_id=$1 AND older.end_date IS NOT NULL AND newer.start_date>older.end_date`
	var start, end time.Time
	var address sql.NullString
	var startBattery, endBattery sql.NullInt64
	if err := p.db.QueryRowContext(ctx, bounds, carID, olderID, newerID).Scan(&start, &end, &address, &startBattery, &endBattery); err != nil {
		return parkedDetail{}, err
	}
	rows, err := p.db.QueryContext(ctx, `SELECT date,power,inside_temp,outside_temp FROM positions WHERE car_id=$1 AND drive_id IS NULL AND date BETWEEN $2 AND $3 ORDER BY date`, carID, start, end)
	if err != nil {
		return parkedDetail{}, err
	}
	defer rows.Close()
	type sample struct {
		at                     time.Time
		power, inside, outside sql.NullFloat64
	}
	var values []sample
	for rows.Next() {
		var v sample
		if err := rows.Scan(&v.at, &v.power, &v.inside, &v.outside); err != nil {
			return parkedDetail{}, err
		}
		values = append(values, v)
	}
	if err := rows.Err(); err != nil {
		return parkedDetail{}, err
	}
	result := parkedDetail{OlderDriveID: olderID, NewerDriveID: newerID, StartDate: formatTime(start, p.loc), EndDate: formatTime(end, p.loc), Address: nullStringPtr(address), StartBatteryLevel: nullIntPtr(startBattery), EndBatteryLevel: nullIntPtr(endBattery), SampleCount: len(values), Source: "database_latest"}
	if result.StartBatteryLevel != nil && result.EndBatteryLevel != nil {
		d := *result.EndBatteryLevel - *result.StartBatteryLevel
		result.BatteryDelta = &d
	}
	if len(values) > 0 {
		var powerSum, peak, insideSum, outsideSum, energy float64
		var powerCount, insideCount, outsideCount int
		var coverage int64
		for i, v := range values {
			if v.power.Valid {
				pw := math.Max(v.power.Float64, 0)
				powerSum += pw
				powerCount++
				if pw > peak {
					peak = pw
				}
			}
			if v.inside.Valid {
				insideSum += v.inside.Float64
				insideCount++
			}
			if v.outside.Valid {
				outsideSum += v.outside.Float64
				outsideCount++
			}
			if i > 0 && values[i-1].power.Valid && v.power.Valid {
				seconds := int64(v.at.Sub(values[i-1].at).Seconds())
				if seconds > 0 && seconds <= 900 {
					energy += (math.Max(values[i-1].power.Float64, 0) + math.Max(v.power.Float64, 0)) / 2 * float64(seconds) / 3600
					coverage += seconds
				}
			}
		}
		if powerCount > 0 {
			avg := powerSum / float64(powerCount)
			result.AveragePowerKW = &avg
			result.PeakPowerKW = &peak
		}
		if insideCount > 0 {
			v := insideSum / float64(insideCount)
			result.InsideTempAverage = &v
		}
		if outsideCount > 0 {
			v := outsideSum / float64(outsideCount)
			result.OutsideTempAverage = &v
		}
		if energy > 0 {
			result.EnergyKWh = &energy
		}
		result.CoverageSeconds = coverage
		total := int64(end.Sub(start).Seconds())
		if total > 0 {
			result.CoverageRatio = math.Min(float64(coverage)/float64(total), 1)
		}
	}
	return result, nil
}

func pathInt(r *http.Request, name string) (int, error) {
	value, err := strconv.Atoi(r.PathValue(name))
	if err != nil || value <= 0 {
		return 0, fmt.Errorf("invalid %s", name)
	}
	return value, nil
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}
func env(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}
func formatTime(value time.Time, loc *time.Location) string {
	return value.In(loc).Format(time.RFC3339Nano)
}
func nullString(v sql.NullString) any {
	if v.Valid {
		return v.String
	}
	return nil
}
func nullStringPtr(v sql.NullString) *string {
	if !v.Valid {
		return nil
	}
	x := v.String
	return &x
}
func nullFloat(v sql.NullFloat64) any {
	if v.Valid {
		return v.Float64
	}
	return nil
}
func nullInt(v sql.NullInt64) any {
	if v.Valid {
		return v.Int64
	}
	return nil
}
func nullBool(v sql.NullBool) any {
	if v.Valid {
		return v.Bool
	}
	return nil
}
func nullTime(v sql.NullTime, loc *time.Location) any {
	if v.Valid {
		return formatTime(v.Time, loc)
	}
	return nil
}
func nullIntPtr(v sql.NullInt64) *int {
	if !v.Valid {
		return nil
	}
	x := int(v.Int64)
	return &x
}
