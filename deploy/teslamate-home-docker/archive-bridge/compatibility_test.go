package main

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

type compatibilityQuery struct {
	args  []any
	query string
}

func (q *compatibilityQuery) Query(_ context.Context, query string, args ...any) (pgx.Rows, error) {
	q.query, q.args = query, args
	return nil, errors.New("synthetic query stop")
}

func TestCompatibilityActualSourcePassesScopedKeysetAndLimit(t *testing.T) {
	for _, kind := range []string{"drives", "charges"} {
		query := &compatibilityQuery{}
		source := &PostgresSource{pool: query, carID: 8}
		var err error
		if kind == "drives" {
			_, err = source.FetchDrives(context.Background(), 42, 50)
		} else {
			_, err = source.FetchCharges(context.Background(), 42, 50)
		}
		if err == nil || !reflect.DeepEqual(query.args, []any{int64(8), int64(42), int64(50)}) {
			t.Fatalf("source paging arguments changed: %#v", query.args)
		}
		if !strings.Contains(query.query, ".car_id = $1") || !strings.Contains(query.query, ".id > $2") || !strings.Contains(query.query, "LIMIT $3") {
			t.Fatal("keyset source scope/paging contract changed")
		}
	}
}

func TestCompatibilityRouteDecoderPreservesNativeUnitsNullZeroNegativeAndInstant(t *testing.T) {
	route, err := decodeRoute([]byte(`[{"date":"2026-10-01T08:00:00+08:00","latitude":null,"longitude":0,"speed":0,"power":-3,"heading":null}]`))
	if err != nil || len(route) != 1 {
		t.Fatalf("decode failed: %v", err)
	}
	p := route[0]
	if p.Latitude != nil || p.Heading != nil || p.Longitude == nil || *p.Longitude != 0 || p.Speed == nil || *p.Speed != 0 || p.Power == nil || *p.Power != -3 {
		t.Fatalf("native km/h, kW or null values changed: %#v", p)
	}
	expected := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	if p.Date == nil || !p.Date.Equal(expected) {
		t.Fatal("timestamp instant changed")
	}
	if _, err := decodeRoute([]byte(`[{"date":"invalid"}]`)); err == nil {
		t.Fatal("invalid timestamp accepted")
	}
}
