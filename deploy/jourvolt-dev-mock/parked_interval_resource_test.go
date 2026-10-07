package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type parkedResourceNode struct {
	NodeType          string               `json:"Node Type"`
	Relation          string               `json:"Relation Name,omitempty"`
	Index             string               `json:"Index Name,omitempty"`
	ActualRows        float64              `json:"Actual Rows"`
	ActualLoops       float64              `json:"Actual Loops"`
	RowsRemoved       float64              `json:"Rows Removed by Filter,omitempty"`
	SharedHitBlocks   int64                `json:"Shared Hit Blocks"`
	SharedReadBlocks  int64                `json:"Shared Read Blocks"`
	TempWrittenBlocks int64                `json:"Temp Written Blocks"`
	Plans             []parkedResourceNode `json:"Plans,omitempty"`
}

// Only allowlisted plan fields are serialized. In particular, do not publish
// raw Filter/Index Cond expressions, row data, connection strings, or tokens.
type parkedResourcePlan struct {
	Plan          parkedResourceNode `json:"Plan"`
	PlanningTime  float64            `json:"Planning Time"`
	ExecutionTime float64            `json:"Execution Time"`
}

type parkedResourceResult struct {
	Case            string             `json:"case"`
	Mode            string             `json:"mode"`
	ServerVersion   string             `json:"server_version"`
	TargetRows      int                `json:"target_rows"`
	ForeignRows     int                `json:"foreign_rows"`
	SourceSQLSHA256 string             `json:"source_sql_sha256"`
	Plan            parkedResourcePlan `json:"plan"`
}

// Explicit CI-only benchmark; ordinary go test must not add a skipped test.
// All rows are synthetic and every scope is deleted before its pool closes.
func BenchmarkParkedIntervalResourceScope(b *testing.B) {
	if os.Getenv("JOURVOLT_RUN_PARKED_RESOURCE_MATRIX") != "1" {
		b.Fatal("set JOURVOLT_RUN_PARKED_RESOURCE_MATRIX=1 for isolated qualification")
	}
	dsn := os.Getenv("JOURVOLT_TEST_DATABASE_URL")
	config, err := pgxpool.ParseConfig(dsn)
	if err != nil || dsn == "" {
		b.Fatal("an isolated test database is required")
	}
	if config.ConnConfig.Database != "matelink_test" || config.ConnConfig.User != "matelink_test" ||
		(config.ConnConfig.Host != "localhost" && config.ConnConfig.Host != "127.0.0.1") {
		b.Fatal("resource qualification only accepts the existing localhost matelink_test CI database")
	}
	for _, spec := range []struct {
		name                     string
		archive, native, foreign int
	}{
		{"sparse", 1000, 1000, 198000},
		{"mixed", 20000, 20000, 180000},
		{"dominant", 90000, 10000, 10000},
		{"all_scope", 100000, 100000, 0},
	} {
		b.Run(spec.name, func(b *testing.B) {
			if b.N != 1 {
				b.Fatal("parked qualification requires -benchtime=1x")
			}
			b.StopTimer()
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
			defer cancel()
			db, err := openStore(ctx, dsn)
			if err != nil {
				b.Fatal(err)
			}
			b.Cleanup(db.close)
			token, err := randomToken()
			if err != nil {
				b.Fatal(err)
			}
			user, foreign := "parked_matrix_"+token, "parked_foreign_"+token
			var car, foreignCar int
			for _, owner := range []struct {
				user string
				car  *int
			}{{user, &car}, {foreign, &foreignCar}} {
				if err := db.ensureUser(ctx, owner.user); err != nil {
					b.Fatal(err)
				}
				ownerID := owner.user
				b.Cleanup(func() {
					cleanup, stop := context.WithTimeout(context.Background(), 30*time.Second)
					defer stop()
					if err := db.deleteUser(cleanup, ownerID); err != nil {
						b.Errorf("synthetic scope cleanup: %v", err)
					}
				})
				if err := db.pool.QueryRow(ctx, `INSERT INTO jourvolt_vehicles
                    (user_id,provider_vehicle_id,vin_ciphertext,display_name,state,updated_at)
                    VALUES($1,$1,'synthetic','Synthetic','offline',now()) RETURNING id`, owner.user).Scan(owner.car); err != nil {
					b.Fatal(err)
				}
			}
			seed := func(owner string, carID, count int, source, prefix string) {
				if count == 0 {
					return
				}
				_, err := db.pool.Exec(ctx, `INSERT INTO jourvolt_telemetry_sessions
                    (id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,source_instance_id,source_vehicle_id,source_record_id)
                    SELECT $1||'-'||$5||'-'||i,$1,$2,'drive',
                           '2025-01-01'::timestamptz+i*interval '1 second',
                           '2025-01-01'::timestamptz+i*interval '1 second'+interval '0.5 second',
                           $4,'observed','parked-matrix','synthetic-car',$5||'-'||i
                    FROM generate_series(1,$3::int) i`, owner, carID, count, source, prefix)
				if err != nil {
					b.Fatal(err)
				}
			}
			seed(user, car, spec.archive, "teslamate_archive", "archive")
			seed(user, car, spec.native, "telemetry_mqtt", "native")
			seed(foreign, foreignCar, spec.foreign, "teslamate_archive", "foreign")
			older, newer := parkedTestPair()
			for _, row := range []*telemetrySession{&older, &newer} {
				if err := db.pool.QueryRow(ctx, `INSERT INTO jourvolt_telemetry_sessions
                    (id,user_id,vehicle_id,kind,started_at,ended_at,source,quality_state,source_instance_id,source_vehicle_id,source_record_id)
                    VALUES($1,$2,$3,'drive',$4,$5,'teslamate_archive','observed','parked-matrix','synthetic-car',$1) RETURNING public_id`,
					user+row.ID, user, car, row.StartAt, row.EndAt).Scan(&row.PublicID); err != nil {
					b.Fatal(err)
				}
			}
			if _, err := db.pool.Exec(ctx, `ANALYZE jourvolt_telemetry_sessions`); err != nil {
				b.Fatal(err)
			}
			s := &telemetryService{store: db}
			if _, found, err := s.parkedInterval(ctx, user, car, older.PublicID, newer.PublicID); err != nil || !found {
				b.Fatalf("synthetic no-blocker interval unavailable: found=%t err=%v", found, err)
			}
			conn, err := db.pool.Acquire(ctx)
			if err != nil {
				b.Fatal(err)
			}
			defer conn.Release()
			var version string
			if err := conn.QueryRow(ctx, `SHOW server_version_num`).Scan(&version); err != nil || !strings.HasPrefix(version, "16") {
				b.Fatalf("qualification requires PostgreSQL 16, got %q err=%v", version, err)
			}
			// Both PREPARE and EXECUTE stay on this acquired connection. The
			// only literal text is a safely quoted, generated synthetic user ID.
			if _, err := conn.Exec(ctx, "PREPARE parked_resource_probe(text,integer,integer,integer) AS "+parkedIntervalSQL); err != nil {
				b.Fatal(err)
			}
			quotedUser := "'" + strings.ReplaceAll(user, "'", "''") + "'"
			explain := fmt.Sprintf("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) EXECUTE parked_resource_probe(%s,%d,%d,%d)", quotedUser, car, older.PublicID, newer.PublicID)
			sqlHash := sha256.Sum256([]byte(parkedIntervalSQL))
			b.StartTimer()
			for _, mode := range []string{"custom", "generic"} {
				if _, err := conn.Exec(ctx, "SET plan_cache_mode=force_"+mode+"_plan"); err != nil {
					b.Fatal(err)
				}
				queryContext, done := historyReadContext(ctx)
				var raw []byte
				err := conn.QueryRow(queryContext, explain).Scan(&raw)
				done()
				if err != nil {
					b.Fatalf("%s plan failed within the existing query budget: %v", mode, err)
				}
				var plans []parkedResourcePlan
				if err := json.Unmarshal(raw, &plans); err != nil || len(plans) != 1 {
					b.Fatalf("invalid plan result: %v", err)
				}
				if err := validateParkedResourcePlan(plans[0].Plan); err != nil {
					b.Fatal(err)
				}
				result := parkedResourceResult{Case: spec.name, Mode: mode, ServerVersion: version,
					TargetRows: spec.archive + spec.native + 2, ForeignRows: spec.foreign,
					SourceSQLSHA256: hex.EncodeToString(sqlHash[:]), Plan: plans[0]}
				encoded, err := json.Marshal(result)
				if err != nil {
					b.Fatal(err)
				}
				b.Logf("PARKED_RESOURCE_RESULT %s", encoded)
			}
			b.StopTimer()
			if _, err := conn.Exec(ctx, `DEALLOCATE parked_resource_probe; RESET plan_cache_mode`); err != nil {
				b.Fatal(err)
			}
		})
	}
}

func validateParkedResourcePlan(root parkedResourceNode) error {
	seen := map[string]bool{}
	var visit func(parkedResourceNode) error
	visit = func(node parkedResourceNode) error {
		if node.TempWrittenBlocks != 0 || node.NodeType == "Sort" || node.NodeType == "Incremental Sort" {
			return fmt.Errorf("unexpected sort/temp write in parked resource plan: %s", node.NodeType)
		}
		if node.Relation == "jourvolt_telemetry_sessions" && node.NodeType == "Seq Scan" {
			return fmt.Errorf("unscoped sequential scan in parked resource plan")
		}
		seen[node.Index] = true
		for _, child := range node.Plans {
			if err := visit(child); err != nil {
				return err
			}
		}
		return nil
	}
	if err := visit(root); err != nil {
		return err
	}
	for _, index := range []string{"jourvolt_telemetry_sessions_native_identity_idx", "jourvolt_telemetry_sessions_archive_identity_idx"} {
		if !seen[index] {
			return fmt.Errorf("expected scoped index absent: %s", index)
		}
	}
	return nil
}

func TestParkedResourcePlanSummaryKeepsOnlySafeFields(t *testing.T) {
	var plan parkedResourcePlan
	input := `{"Plan":{"Node Type":"Index Scan","Index Name":"sample","Filter":"private-value","Plans":[]},"Planning Time":1,"Execution Time":2,"Query Text":"private-value"}`
	if err := json.Unmarshal([]byte(input), &plan); err != nil {
		t.Fatal(err)
	}
	output, err := json.Marshal(plan)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(output), "private-value") || strings.Contains(string(output), "Filter") || strings.Contains(string(output), "Query Text") {
		t.Fatal("plan summary exposed raw query or filter data")
	}
}
