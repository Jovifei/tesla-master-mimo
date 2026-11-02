package main

import (
	"context"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"
)

func requireIsolatedEnrollmentRaceDB(t *testing.T) {
	t.Helper()
	if os.Getenv("MATELINK_TEST_ISOLATED_POSTGRES") != "1" {
		t.Skip("explicit isolated PostgreSQL opt-in not set")
	}
	dsn, err := url.Parse(os.Getenv("JOURVOLT_TEST_DATABASE_URL"))
	if err != nil || dsn == nil ||
		(dsn.Hostname() != "127.0.0.1" && dsn.Hostname() != "localhost" && dsn.Hostname() != "::1") ||
		!strings.HasPrefix(strings.TrimPrefix(dsn.Path, "/"), "matelink_partial_test_") {
		t.Fatal("test URL must name an isolated local matelink_partial_test_* database")
	}
}

// The trigger pauses only this isolated test after the initial session check.
func TestArchiveEnrollmentSessionExpiryBeforeCommit(t *testing.T) {
	requireIsolatedEnrollmentRaceDB(t)
	for _, scenario := range []string{"logout", "expires"} {
		t.Run(scenario, func(t *testing.T) {
			f := newEnrollmentFixture(t)
			e := f.create(f.scope)
			f.exec(`CREATE OR REPLACE FUNCTION synthetic_enrollment_gate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status='pending' AND NEW.status='approved' THEN PERFORM pg_advisory_xact_lock(91827463); END IF;
  RETURN NEW;
END $$`)
			f.exec(`CREATE TRIGGER synthetic_enrollment_gate BEFORE UPDATE OF status ON jourvolt_archive_enrollments FOR EACH ROW EXECUTE FUNCTION synthetic_enrollment_gate()`)
			t.Cleanup(func() {
				_, _ = f.db.pool.Exec(context.Background(), `DROP TRIGGER IF EXISTS synthetic_enrollment_gate ON jourvolt_archive_enrollments`)
				_, _ = f.db.pool.Exec(context.Background(), `DROP FUNCTION IF EXISTS synthetic_enrollment_gate()`)
			})
			hold, err := f.db.pool.Acquire(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			defer hold.Release()
			if _, err = hold.Exec(context.Background(), `SELECT pg_advisory_lock(91827463)`); err != nil {
				t.Fatal(err)
			}
			defer hold.Exec(context.Background(), `SELECT pg_advisory_unlock(91827463)`)
			if scenario == "expires" {
				f.exec(`UPDATE jourvolt_sessions SET access_expires_at=clock_timestamp()+interval '2 seconds' WHERE access_hash=$1`, hashToken(f.owner.AccessToken))
			}
			done := make(chan int, 1)
			go func() { done <- f.decide(e, true).Code }()
			blocked := false
			for i := 0; i < 100; i++ {
				err = f.db.pool.QueryRow(context.Background(), `SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT granted)`).Scan(&blocked)
				if err != nil {
					t.Fatal(err)
				}
				if blocked {
					break
				}
				time.Sleep(10 * time.Millisecond)
			}
			if !blocked {
				t.Fatal("approval did not reach the post-session-check gate")
			}
			var logoutDone chan error
			if scenario == "logout" {
				logoutDone = make(chan error, 1)
				go func() { logoutDone <- f.db.revokeSession(context.Background(), f.owner.AccessToken) }()
				waiting := false
				for i := 0; i < 100; i++ {
					err = f.db.pool.QueryRow(context.Background(), `SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype<>'advisory' AND NOT granted)`).Scan(&waiting)
					if err != nil {
						t.Fatal(err)
					}
					if waiting {
						break
					}
					time.Sleep(10 * time.Millisecond)
				}
				if !waiting {
					t.Fatal("logout did not wait behind the approval session lock")
				}
				select {
				case <-logoutDone:
					t.Fatal("logout completed before approval released the lock")
				default:
				}
			} else {
				expired := false
				for i := 0; i < 300; i++ {
					if err = f.db.pool.QueryRow(context.Background(), `SELECT access_expires_at<=clock_timestamp() FROM jourvolt_sessions WHERE access_hash=$1`, hashToken(f.owner.AccessToken)).Scan(&expired); err != nil {
						t.Fatal(err)
					}
					if expired {
						break
					}
					time.Sleep(10 * time.Millisecond)
				}
				if !expired {
					t.Fatal("synthetic session did not expire before approval commit")
				}
			}
			if _, err = hold.Exec(context.Background(), `SELECT pg_advisory_unlock(91827463)`); err != nil {
				t.Fatal(err)
			}
			select {
			case status := <-done:
				want := 200
				if scenario == "expires" {
					want = 401
				}
				if status != want {
					t.Fatalf("decision status = %d, want %d", status, want)
				}
			case <-time.After(5 * time.Second):
				t.Fatal("approval did not complete")
			}
			var state, approvedUser, bindingID, approvedHash string
			if err = f.db.pool.QueryRow(context.Background(), `SELECT status,approved_user_id,binding_id,approved_token_hash FROM jourvolt_archive_enrollments WHERE handle=$1`, e["handle"]).Scan(&state, &approvedUser, &bindingID, &approvedHash); err != nil {
				t.Fatal(err)
			}
			if scenario == "expires" {
				if state != "pending" || approvedUser != "" || bindingID != "" || approvedHash != "" {
					t.Fatal("expired decision left approval data behind")
				}
				if _, err := f.db.resolveArchiveBinding(context.Background(), f.oldToken); err != nil {
					t.Fatal("expired decision changed the archive binding")
				}
			} else if state != "approved" {
				t.Fatal("valid approval did not commit")
			}
			if scenario == "logout" {
				select {
				case err = <-logoutDone:
					if err != nil {
						t.Fatal(err)
					}
				case <-time.After(5 * time.Second):
					t.Fatal("logout did not complete after approval")
				}
				t.Log("confirmed: logout waited until approval released the session lock")
			} else {
				t.Log("expired approval rolled back without changing the binding")
			}
		})
	}
}
