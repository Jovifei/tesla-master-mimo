package main

import (
    "context"
    "database/sql"
    "database/sql/driver"
    "errors"
    "io"
    "math"
    "strings"
    "testing"
    "time"
)

// Runs postgresStore.Standby's real row/integration path using synthetic database rows.
type standbyZeroDriver struct{}
type standbyZeroConn struct{ mode string }
type standbyZeroRows struct { columns []string; values [][]driver.Value; index int }

func (standbyZeroDriver) Open(mode string) (driver.Conn, error) { return &standbyZeroConn{mode}, nil }
func (c *standbyZeroConn) Prepare(string) (driver.Stmt, error) { return nil, errors.New("unused prepare") }
func (c *standbyZeroConn) Close() error { return nil }
func (c *standbyZeroConn) Begin() (driver.Tx, error) { return nil, errors.New("unused transaction") }
func (r *standbyZeroRows) Columns() []string { return r.columns }
func (r *standbyZeroRows) Close() error { return nil }
func (r *standbyZeroRows) Next(dest []driver.Value) error {
    if r.index >= len(r.values) { return io.EOF }
    copy(dest, r.values[r.index]); r.index++; return nil
}
func (c *standbyZeroConn) QueryContext(_ context.Context, query string, _ []driver.NamedValue) (driver.Rows, error) {
    at := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
    switch {
    case strings.Contains(query, "SELECT older.end_date"):
        return &standbyZeroRows{columns: []string{"start", "end", "address", "start_soc", "end_soc"},
            values: [][]driver.Value{{at, at.Add(2*time.Hour), "synthetic", int64(80), int64(79)}}}, nil
    case strings.Contains(query, "SELECT id FROM charging_processes"):
        return &standbyZeroRows{columns: []string{"id"}}, nil
    case strings.Contains(query, "SELECT date,power,inside_temp"):
        rows := &standbyZeroRows{columns: []string{"date", "power", "inside", "outside", "climate"}}
        limit := 8
        if c.mode == "gap" { limit = 4 }
        for i := 0; i <= limit; i++ {
            var power driver.Value = float64(0)
            if c.mode == "missing" { power = nil }
            if c.mode == "nan" { power = math.NaN() }
            if c.mode == "inf" { power = math.Inf(1) }
            elapsed := time.Duration(i)*15*time.Minute
            if c.mode == "gap" { elapsed = time.Duration(i)*30*time.Minute }
            rows.values = append(rows.values, []driver.Value{at.Add(elapsed), power, nil, nil, nil})
        }
        return rows, nil
    default:
        return nil, errors.New("unexpected synthetic query")
    }
}

func init() { sql.Register("f4-standby-zero", standbyZeroDriver{}) }

func TestF4StandbyObservedZeroIsKnownOnlyWithCoveredPowerIntervals(t *testing.T) {
    for _, mode := range []string{"zero", "missing", "gap", "nan", "inf"} {
        t.Run(mode, func(t *testing.T) {
            db, err := sql.Open("f4-standby-zero", mode)
            if err != nil { t.Fatal(err) }
            defer db.Close()
            store := &postgresStore{db: db, loc: time.UTC}
            windows, err := store.Standby(context.Background(), 7)
            if err != nil || len(windows) != 1 { t.Fatalf("windows=%+v err=%v", windows, err) }
            value := windows[0]
            if mode == "zero" {
                if value.EnergyKWh == nil || *value.EnergyKWh != 0 || value.AveragePowerW == nil || *value.AveragePowerW != 0 || value.CoverageRatio != 1 {
                    t.Fatalf("observed full-coverage zero must survive: %+v", value)
                }
            } else if value.EnergyKWh != nil || value.AveragePowerW != nil || value.CoverageRatio != 0 {
                t.Fatalf("uncovered/missing samples cannot invent zero: %+v", value)
            }
        })
    }
}
