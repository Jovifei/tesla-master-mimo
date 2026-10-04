package main

import (
	"encoding/json"
	"testing"
)

func TestArchiveRouteBatteryForwardingPreservesObservedZeroAndUnknown(t *testing.T) {
	for _, sample := range []struct {
		name   string
		levels string
		want   []any
	}{
		{"unchanged", "61,61", []any{float64(61), float64(61)}},
		{"decreased", "74,73", []any{float64(74), float64(73)}},
		{"zero_and_unknown", "0,null", []any{float64(0), nil}},
		{"invalid_unknown", "-1,101", []any{nil, nil}},
	} {
		t.Run(sample.name, func(t *testing.T) {
			var levels []json.RawMessage
			if err := json.Unmarshal([]byte("["+sample.levels+"]"), &levels); err != nil {
				t.Fatal(err)
			}
			points := make([]map[string]any, len(levels))
			for i, level := range levels {
				points[i] = map[string]any{"date": testTime(i + 1), "latitude": nil, "longitude": 121.5, "battery_level": level}
			}
			raw, err := json.Marshal(points)
			if err != nil {
				t.Fatal(err)
			}
			route, err := decodeRoute(raw)
			if err != nil {
				t.Fatal(err)
			}
			batch := buildArchiveBatch(testConfig("http://archive.test", "unused"), Cursor{}, []DriveRecord{{ID: 1, StartedAt: timePointer(testTime(1)), EndedAt: timePointer(testTime(2)), Route: route}}, nil)
			encoded, err := json.Marshal(batch)
			if err != nil {
				t.Fatal(err)
			}
			var body struct {
				Drives []struct {
					Route []map[string]any `json:"route"`
				} `json:"drives"`
			}
			if err := json.Unmarshal(encoded, &body); err != nil {
				t.Fatal(err)
			}
			if len(body.Drives) != 1 || len(body.Drives[0].Route) != len(sample.want) {
				t.Fatalf("lost route points")
			}
			for i, want := range sample.want {
				point := body.Drives[0].Route[i]
				if point["battery_level"] != want || point["latitude"] != nil {
					t.Fatalf("point %d battery=%v want=%v latitude=%v", i, point["battery_level"], want, point["latitude"])
				}
			}
		})
	}
}

func TestLegacyRouteWithoutBatteryRemainsUnknown(t *testing.T) {
	route, err := decodeRoute([]byte(`[{"date":"2026-10-04T01:00:00Z","latitude":1,"longitude":2}]`))
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(route)
	if err != nil {
		t.Fatal(err)
	}
	var points []map[string]any
	if err := json.Unmarshal(encoded, &points); err != nil {
		t.Fatal(err)
	}
	if len(points) != 1 || points[0]["battery_level"] != nil {
		t.Fatal("missing battery promoted to observed")
	}
}
