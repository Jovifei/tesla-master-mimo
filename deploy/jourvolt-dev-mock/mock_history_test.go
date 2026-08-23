package main

import (
	"net/http/httptest"
	"testing"
)

func TestMockHistoryHasRecommendationCoverage(t *testing.T) {
	drives := mockDriveFixtures()
	charges := mockChargeFixtures()
	if len(drives) != 18 || len(charges) != 5 {
		t.Fatalf("fixture sizes = %d drives, %d charges", len(drives), len(charges))
	}
	if drives[0]["energy_consumed_net"] == nil || charges[0]["charge_energy_used"] == nil {
		t.Fatal("fixtures must include observed energy fields")
	}
}

func TestMockHistoryPaginationStopsAfterAvailableRecords(t *testing.T) {
	request := httptest.NewRequest("GET", "/api/v1/cars/1/drives?page=2&show=50", nil)
	if page := paginateFixture(mockDriveFixtures(), request); len(page) != 0 {
		t.Fatalf("second page contains %d records, want 0", len(page))
	}
}

func TestMockHistoryIsRestrictedToMockUser(t *testing.T) {
	a := &app{mockEnabled: true, mockHistoryEnabled: true}
	if !a.hasMockHistory("mock-user") {
		t.Fatal("mock user should receive the explicit history fixture")
	}
	if a.hasMockHistory("real-user") {
		t.Fatal("real users must never receive mock history")
	}
}
