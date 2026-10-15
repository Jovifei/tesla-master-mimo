package main

import (
	"context"
	"os"
	"testing"
	"time"
)

func TestOpenStorePreservesExistingGradesOnReopen(t *testing.T) {
	database := boundedHistoryTestStore(t)
	user, car := boundedHistoryTestTenant(t, database, "schema-grade-guard")
	ctx := context.Background()
	at := time.Date(2026, 10, 15, 0, 0, 0, 0, time.UTC)
	for i, tc := range []struct{ source, grade string }{{"telemetry_mqtt", "incomplete"}, {"local_import", "quarantined"}} {
		id := boundedHistoryInsertSession(t, database, user, car, "drive", at.Add(time.Duration(i)*time.Hour), tc.source, tc.grade, []historyImportRoutePoint{}, "")
		reopened, err := openStore(ctx, os.Getenv("JOURVOLT_TEST_DATABASE_URL"))
		if err != nil {
			t.Fatal(err)
		}
		var grade, reason string
		err = reopened.pool.QueryRow(ctx, "SELECT quality_state,quality_reason FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND public_id=$2", user, id).Scan(&grade, &reason)
		reopened.close()
		if err != nil {
			t.Fatal(err)
		}
		if grade != tc.grade || reason != "bounded_test" {
			t.Fatalf("startup reclassified existing %s record: grade=%s reason=%s", tc.source, grade, reason)
		}
	}
}
