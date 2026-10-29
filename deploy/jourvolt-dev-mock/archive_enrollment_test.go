package main

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestArchiveEnrollmentUnavailableDoesNotFallThroughToSessionAuth(t *testing.T) {
	a := &app{}
	w := httptest.NewRecorder()
	a.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/v1/archive/enrollments", strings.NewReader(`{"vehicle_id":1,"source_instance_id":"home","source_vehicle_id":"car-1"}`)))
	if w.Code != http.StatusServiceUnavailable || !strings.Contains(w.Body.String(), "archive_enrollment_unavailable") {
		t.Fatalf("enrollment must fail closed without storage: status %d", w.Code)
	}
}

func TestArchiveEnrollmentRejectsActionAliases(t *testing.T) {
	a := &app{}
	handle := strings.Repeat("a", 64)
	for _, test := range []struct{ method, suffix string }{
		{http.MethodPost, "/create"},
		{http.MethodGet, "/preview"},
	} {
		t.Run(test.method+test.suffix, func(t *testing.T) {
			w := httptest.NewRecorder()
			a.ServeHTTP(w, httptest.NewRequest(test.method, archiveEnrollmentBase+"/"+handle+test.suffix, nil))
			requireEnrollmentStatus(t, w, http.StatusNotFound)
		})
	}
}

type enrollmentFixture struct {
	t            *testing.T
	app          *app
	db           *store
	owner, other session
	scope        archiveEnrollmentScope
	oldToken     string
	remote       string
}

func newEnrollmentFixture(t *testing.T) *enrollmentFixture {
	t.Helper()
	db := boundedHistoryTestStore(t)
	user, vehicle := boundedHistoryTestTenant(t, db, "enrollment")
	other, _ := boundedHistoryTestTenant(t, db, "enrollment_other")
	ownerSession, err := db.createSession(context.Background(), user)
	if err != nil {
		t.Fatal(err)
	}
	otherSession, err := db.createSession(context.Background(), other)
	if err != nil {
		t.Fatal(err)
	}
	cipher := mustTestCipher(t)
	f := &enrollmentFixture{t: t, db: db, owner: ownerSession, other: otherSession, scope: archiveEnrollmentScope{VehicleID: vehicle, SourceInstanceID: "home-instance", SourceVehicleID: "car-1"}}
	f.app = &app{store: db, provider: enrollmentNoDiscovery{}, oauth: &teslaOAuth{cipher: cipher}, telemetry: &telemetryService{store: db, config: &telemetryConfig{StopDebounce: defaultDriveStopDebounce}}}
	f.oldToken, _, err = db.createArchiveBinding(context.Background(), user, vehicle, archiveBindingRequest{SourceInstanceID: f.scope.SourceInstanceID, SourceVehicleID: f.scope.SourceVehicleID})
	if err != nil {
		t.Fatal(err)
	}
	ip := net.IP([]byte(hashToken(user)[:16])).String()
	f.remote = net.JoinHostPort(ip, "1234")
	t.Cleanup(func() {
		_, _ = db.pool.Exec(context.Background(), `DELETE FROM jourvolt_archive_enrollments WHERE ip_hash=$1`, hashToken(ip))
	})
	return f
}

type enrollmentNoDiscovery struct{}

func (enrollmentNoDiscovery) Vehicles(context.Context, string) ([]vehicle, error) {
	panic("enrollment must not discover vehicles")
}
func (enrollmentNoDiscovery) Status(context.Context, string, int) (vehicleStatus, error) {
	panic("enrollment must not contact Tesla")
}

func (f *enrollmentFixture) request(method, path, access, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.RemoteAddr = f.remote
	if access != "" {
		r.Header.Set("Authorization", "Bearer "+access)
	}
	w := httptest.NewRecorder()
	f.app.ServeHTTP(w, r)
	return w
}
func requireEnrollmentStatus(t *testing.T, w *httptest.ResponseRecorder, status int) {
	t.Helper()
	if w.Code != status {
		t.Fatalf("enrollment status=%d want=%d", w.Code, status)
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("credential boundary must disable caching")
	}
}
func enrollmentData(t *testing.T, w *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var envelope struct {
		Data map[string]any `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	return envelope.Data
}
func (f *enrollmentFixture) create(scope archiveEnrollmentScope) map[string]any {
	f.t.Helper()
	body, _ := json.Marshal(scope)
	w := f.request("POST", archiveEnrollmentBase, "", string(body))
	requireEnrollmentStatus(f.t, w, 201)
	return enrollmentData(f.t, w)
}
func (f *enrollmentFixture) decide(e map[string]any, approve bool) *httptest.ResponseRecorder {
	body, _ := json.Marshal(map[string]any{"approve": approve, "verification_code": e["verification_code"]})
	return f.request("POST", archiveEnrollmentBase+"/"+e["handle"].(string)+"/decision", f.owner.AccessToken, string(body))
}
func (f *enrollmentFixture) device(e map[string]any, action string) *httptest.ResponseRecorder {
	return f.request("POST", archiveEnrollmentBase+"/"+e["handle"].(string)+"/"+action, "", `{"device_secret":"`+e["device_secret"].(string)+`"}`)
}
func (f *enrollmentFixture) exec(query string, args ...any) {
	f.t.Helper()
	if _, err := f.db.pool.Exec(context.Background(), query, args...); err != nil {
		f.t.Fatal(err)
	}
}

func TestArchiveEnrollmentPostgresOwnerClaimRetryACKAndHistoryContinuity(t *testing.T) {
	f := newEnrollmentFixture(t)
	importOld := archiveImportRequestForVehicleTest(f.oldToken, f.scope.VehicleID, f.scope.SourceInstanceID, f.scope.SourceVehicleID)
	w := httptest.NewRecorder()
	f.app.ServeHTTP(w, importOld)
	if w.Code != 200 {
		t.Fatalf("seed old binding upload status=%d", w.Code)
	}
	var historyBefore string
	if err := f.db.pool.QueryRow(context.Background(), `SELECT row_to_json(s)::text FROM jourvolt_telemetry_sessions s WHERE user_id=$1`, f.owner.UserID).Scan(&historyBefore); err != nil {
		t.Fatal(err)
	}
	// Renew an expired but nonrevoked original binding; this is the recovery case.
	f.exec(`UPDATE jourvolt_history_archive_bindings SET expires_at=now()-interval '1 day' WHERE user_id=$1`, f.owner.UserID)
	e := f.create(f.scope)
	if len(e["handle"].(string)) != 64 || len(e["device_secret"].(string)) != 64 || len(e["verification_code"].(string)) != 6 {
		t.Fatal("entropy/code contract")
	}
	preview := f.request("GET", archiveEnrollmentBase+"/"+e["handle"].(string), f.owner.AccessToken, "")
	requireEnrollmentStatus(t, preview, 200)
	data := enrollmentData(t, preview)
	if data["scope_fingerprint"] != f.scope.fingerprint() || data["binding_state"] != "expired" || data["vehicle_id"] != float64(f.scope.VehicleID) {
		t.Fatal("owner preview scope")
	}
	for _, forbidden := range []string{"user_id", "source_instance_id", "source_vehicle_id", "vin", "credential", "token", "device_secret"} {
		if _, ok := data[forbidden]; ok {
			t.Fatalf("preview leaked %s", forbidden)
		}
	}
	requireEnrollmentStatus(t, f.decide(e, true), 200)
	requireEnrollmentStatus(t, f.decide(e, true), 200)
	// Approval alone does not rotate the original hash or rewrite creation time.
	var hash, id string
	var created time.Time
	if err := f.db.pool.QueryRow(context.Background(), `SELECT token_hash,id,created_at FROM jourvolt_history_archive_bindings WHERE user_id=$1`, f.owner.UserID).Scan(&hash, &id, &created); err != nil {
		t.Fatal(err)
	}
	if hash != hashToken(f.oldToken) {
		t.Fatal("approval rotated token")
	}
	claimed := f.device(e, "claim")
	requireEnrollmentStatus(t, claimed, 200)
	credential := enrollmentData(t, claimed)["credential"].(string)
	// The encrypted issued receipt survives an API process/store restart.
	reopened, err := openStore(context.Background(), f.db.pool.Config().ConnString())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(reopened.close)
	f.app.store = reopened
	retried := f.device(e, "claim")
	requireEnrollmentStatus(t, retried, 200)
	if claimed.Body.String() != retried.Body.String() {
		t.Fatal("lost response retry changed receipt")
	}
	var storedCipher, storedProof string
	if err := f.db.pool.QueryRow(context.Background(), `SELECT issued_ciphertext,secret_hash FROM jourvolt_archive_enrollments WHERE handle=$1`, e["handle"]).Scan(&storedCipher, &storedProof); err != nil {
		t.Fatal(err)
	}
	if storedCipher == "" || strings.Contains(storedCipher, credential) || storedProof != hashToken(e["device_secret"].(string)) {
		t.Fatal("plaintext credential/proof persisted")
	}
	if _, err := f.db.resolveArchiveBinding(context.Background(), f.oldToken); err == nil {
		t.Fatal("old binding still usable")
	}
	binding, err := f.db.resolveArchiveBinding(context.Background(), credential)
	if err != nil || !archiveBindingMatches(binding, f.owner.UserID, f.scope.VehicleID, archiveBindingRequest{SourceInstanceID: f.scope.SourceInstanceID, SourceVehicleID: f.scope.SourceVehicleID}) || !binding.CreatedAt.Equal(created) || time.Until(binding.ExpiresAt) < 29*24*time.Hour {
		t.Fatal("same binding renewal failed")
	}
	newImport := archiveImportRequestForVehicleTest(credential, f.scope.VehicleID, f.scope.SourceInstanceID, f.scope.SourceVehicleID)
	newBody, _ := io.ReadAll(newImport.Body)
	newImport.Body = io.NopCloser(strings.NewReader(strings.ReplaceAll(strings.ReplaceAll(string(newBody), "record-1", "record-2"), "drive-1", "drive-2")))
	w = httptest.NewRecorder()
	f.app.ServeHTTP(w, newImport)
	if w.Code != 200 {
		t.Fatalf("new token upload status=%d", w.Code)
	}
	var historyAfter string
	if err := f.db.pool.QueryRow(context.Background(), `SELECT row_to_json(s)::text FROM jourvolt_telemetry_sessions s WHERE user_id=$1 AND source_record_id='record-1'`, f.owner.UserID).Scan(&historyAfter); err != nil {
		t.Fatal(err)
	}
	if historyAfter != historyBefore {
		t.Fatal("renewal/new upload changed existing archive history")
	}
	var count int
	if err := f.db.pool.QueryRow(context.Background(), `SELECT count(*) FROM jourvolt_telemetry_sessions WHERE user_id=$1 AND source_instance_id=$2 AND source_vehicle_id=$3`, f.owner.UserID, f.scope.SourceInstanceID, f.scope.SourceVehicleID).Scan(&count); err != nil || count != 2 {
		t.Fatal("archive source identity/count continuity failed")
	}
	// Upload-only token is never an owner session.
	requireEnrollmentStatus(t, f.request("GET", archiveEnrollmentBase+"/"+e["handle"].(string), credential, ""), 401)
	requireEnrollmentStatus(t, f.device(e, "ack"), 200)
	requireEnrollmentStatus(t, f.device(e, "ack"), 200)
	final := f.device(e, "claim")
	requireEnrollmentStatus(t, final, 200)
	if strings.Contains(final.Body.String(), "credential") || enrollmentData(t, final)["state"] != "installed" {
		t.Fatal("installed request replayed token")
	}
	if err := f.db.pool.QueryRow(context.Background(), `SELECT issued_ciphertext FROM jourvolt_archive_enrollments WHERE handle=$1`, e["handle"]).Scan(&storedCipher); err != nil || storedCipher != "" {
		t.Fatal("ACK did not clear receipt")
	}
	var finalID string
	if err := f.db.pool.QueryRow(context.Background(), `SELECT id FROM jourvolt_history_archive_bindings WHERE user_id=$1`, f.owner.UserID).Scan(&finalID); err != nil || finalID != id {
		t.Fatal("renewal replaced binding identity")
	}
}

func TestArchiveEnrollmentPostgresOwnerScopeProofExpiryAndRejection(t *testing.T) {
	for _, scenario := range []string{"anonymous", "other-user", "wrong-vehicle", "wrong-source", "revoked", "wrong-proof", "expired", "rejected", "wrong-code", "session-revoked", "session-expired", "changed-after-approval", "changed-after-issue", "revoked-after-issue"} {
		t.Run(scenario, func(t *testing.T) {
			f := newEnrollmentFixture(t)
			scope := f.scope
			if scenario == "wrong-vehicle" {
				scope.VehicleID++
			}
			if scenario == "wrong-source" {
				scope.SourceVehicleID = "other"
			}
			e := f.create(scope)
			path := archiveEnrollmentBase + "/" + e["handle"].(string)
			switch scenario {
			case "anonymous":
				requireEnrollmentStatus(t, f.request("GET", path, "", ""), 401)
			case "other-user":
				requireEnrollmentStatus(t, f.request("GET", path, f.other.AccessToken, ""), 403)
				body, _ := json.Marshal(map[string]any{"approve": true, "verification_code": e["verification_code"]})
				requireEnrollmentStatus(t, f.request("POST", path+"/decision", f.other.AccessToken, string(body)), 403)
			case "wrong-vehicle", "wrong-source":
				requireEnrollmentStatus(t, f.request("GET", path, f.owner.AccessToken, ""), 403)
				requireEnrollmentStatus(t, f.decide(e, true), 403)
			case "revoked":
				f.exec(`UPDATE jourvolt_history_archive_bindings SET revoked_at=now() WHERE user_id=$1`, f.owner.UserID)
				requireEnrollmentStatus(t, f.decide(e, true), 403)
			case "wrong-proof":
				e["device_secret"] = strings.Repeat("a", 64)
				requireEnrollmentStatus(t, f.device(e, "claim"), 401)
			case "expired":
				f.exec(`UPDATE jourvolt_archive_enrollments SET expires_at=now()-interval '1 second' WHERE handle=$1`, e["handle"])
				requireEnrollmentStatus(t, f.decide(e, true), 410)
				requireEnrollmentStatus(t, f.device(e, "claim"), 410)
			case "rejected":
				requireEnrollmentStatus(t, f.decide(e, false), 200)
				requireEnrollmentStatus(t, f.decide(e, false), 200)
				requireEnrollmentStatus(t, f.decide(e, true), 403)
				requireEnrollmentStatus(t, f.device(e, "claim"), 403)
			case "wrong-code":
				e["verification_code"] = "xxxxxx"
				requireEnrollmentStatus(t, f.decide(e, true), 403)
			case "session-revoked":
				f.exec(`UPDATE jourvolt_sessions SET revoked_at=now() WHERE access_hash=$1`, hashToken(f.owner.AccessToken))
				requireEnrollmentStatus(t, f.decide(e, true), 401)
			case "session-expired":
				f.exec(`UPDATE jourvolt_sessions SET access_expires_at=now()-interval '1 second' WHERE access_hash=$1`, hashToken(f.owner.AccessToken))
				requireEnrollmentStatus(t, f.decide(e, true), 401)
			default:
				requireEnrollmentStatus(t, f.decide(e, true), 200)
				if scenario != "changed-after-approval" {
					requireEnrollmentStatus(t, f.device(e, "claim"), 200)
				}
				if scenario == "revoked-after-issue" {
					f.exec(`UPDATE jourvolt_history_archive_bindings SET revoked_at=now() WHERE user_id=$1`, f.owner.UserID)
				} else {
					_, _, err := f.db.createArchiveBinding(context.Background(), f.owner.UserID, f.scope.VehicleID, archiveBindingRequest{SourceInstanceID: f.scope.SourceInstanceID, SourceVehicleID: f.scope.SourceVehicleID})
					if err != nil {
						t.Fatal(err)
					}
				}
				requireEnrollmentStatus(t, f.device(e, "claim"), 403)
				requireEnrollmentStatus(t, f.device(e, "ack"), 403)
			}
		})
	}
}

func TestArchiveEnrollmentPostgresBoundedInputPollingAndCapacity(t *testing.T) {
	f := newEnrollmentFixture(t)
	e := f.create(f.scope)
	path := archiveEnrollmentBase + "/" + e["handle"].(string)
	requireEnrollmentStatus(t, f.device(e, "claim"), 202)
	throttled := f.device(e, "claim")
	requireEnrollmentStatus(t, throttled, 429)
	if throttled.Header().Get("Retry-After") == "" {
		t.Fatal("retry interval missing")
	}
	requireEnrollmentStatus(t, f.device(e, "ack"), 403)
	for _, tc := range []struct {
		path, body string
		status     int
	}{
		{archiveEnrollmentBase, `{"vehicle_id":1,"source_instance_id":"a","source_vehicle_id":"b","extra":true}`, 400},
		{archiveEnrollmentBase, `{"vehicle_id":1,"source_instance_id":"a","source_vehicle_id":"b"} {}`, 400},
		{archiveEnrollmentBase, `{"vehicle_id":1,"source_instance_id":"` + strings.Repeat("a", 4096) + `","source_vehicle_id":"b"}`, 400},
		{path + "/claim?device_secret=" + e["device_secret"].(string), `{}`, 400},
		{path + "/claim/extra", `{}`, 404}, {path + "/", `{}`, 404},
		{archiveEnrollmentBase + "/" + strings.Repeat("a", 63), `{}`, 404},
		{archiveEnrollmentBase + "/%61" + e["handle"].(string)[1:] + "/claim", `{}`, 400},
		{path + "/decision", `{"approve":true,"verification_code":"123456","source_vehicle_id":"other"}`, 400},
		{path + "/decision", `{"verification_code":"123456"}`, 400},
		{path + "/claim", `{"device_secret":"` + e["handle"].(string) + `"}`, 401},
	} {
		requireEnrollmentStatus(t, f.request("POST", tc.path, f.owner.AccessToken, tc.body), tc.status)
	}
	for i := 0; i < 4; i++ {
		f.create(f.scope)
	}
	body, _ := json.Marshal(f.scope)
	limited := f.request("POST", archiveEnrollmentBase, "", string(body))
	requireEnrollmentStatus(t, limited, 429)
	// Capacity is tested in the isolated database without creating unbounded rows.
	f.exec(`INSERT INTO jourvolt_archive_enrollments(handle,secret_hash,ip_hash,vehicle_id,source_instance_id,source_vehicle_id,verification_code,status,created_at,expires_at,next_poll_at)
 SELECT 'capacity-'||i,'h','capacity',1,'s','v','123456','pending',now()-interval '2 minutes',now()+interval '1 minute',now() FROM generate_series(1,1000) i`)
	t.Cleanup(func() { f.exec(`DELETE FROM jourvolt_archive_enrollments WHERE ip_hash='capacity'`) })
	f.remote = "127.0.0.2:1234"
	requireEnrollmentStatus(t, f.request("POST", archiveEnrollmentBase, "", string(body)), 429)
}

func TestArchiveEnrollmentPostgresConcurrentClaimsAndIndependentApprovalCAS(t *testing.T) {
	f := newEnrollmentFixture(t)
	first := f.create(f.scope)
	second := f.create(f.scope)
	requireEnrollmentStatus(t, f.decide(first, true), 200)
	requireEnrollmentStatus(t, f.decide(second, true), 200)
	results := make(chan *httptest.ResponseRecorder, 12)
	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); results <- f.device(first, "claim") }()
	}
	wg.Wait()
	close(results)
	var receipt string
	for w := range results {
		requireEnrollmentStatus(t, w, 200)
		if receipt == "" {
			receipt = w.Body.String()
		} else if receipt != w.Body.String() {
			t.Fatal("concurrent claims rotated twice")
		}
	}
	requireEnrollmentStatus(t, f.device(second, "claim"), 403)
}

func TestArchiveEnrollmentPostgresApprovedCancellationAndClaimRace(t *testing.T) {
	f := newEnrollmentFixture(t)
	e := f.create(f.scope)
	requireEnrollmentStatus(t, f.decide(e, true), 200)
	requireEnrollmentStatus(t, f.decide(e, false), 200)
	requireEnrollmentStatus(t, f.device(e, "claim"), 403)
	if _, err := f.db.resolveArchiveBinding(context.Background(), f.oldToken); err != nil {
		t.Fatal("cancelled approval rotated binding")
	}
	next := f.create(f.scope)
	requireEnrollmentStatus(t, f.decide(next, true), 200)
	var claim, deny *httptest.ResponseRecorder
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); claim = f.device(next, "claim") }()
	go func() { defer wg.Done(); deny = f.decide(next, false) }()
	wg.Wait()
	if deny.Code == 200 {
		requireEnrollmentStatus(t, claim, 403)
	} else {
		requireEnrollmentStatus(t, claim, 200)
		requireEnrollmentStatus(t, deny, 403)
	}
}

func TestArchiveEnrollmentPostgresACKRevocationRaceAndExpiredReceipt(t *testing.T) {
	for _, scenario := range []string{"revoke-race", "expired-receipt", "cipher-unavailable", "receipt-corrupt", "installed-rebind"} {
		t.Run(scenario, func(t *testing.T) {
			f := newEnrollmentFixture(t)
			e := f.create(f.scope)
			requireEnrollmentStatus(t, f.decide(e, true), 200)
			if scenario == "cipher-unavailable" {
				f.app.oauth = nil
				requireEnrollmentStatus(t, f.device(e, "claim"), 503)
				if _, err := f.db.resolveArchiveBinding(context.Background(), f.oldToken); err != nil {
					t.Fatal("cipher failure rotated binding")
				}
				return
			}
			requireEnrollmentStatus(t, f.device(e, "claim"), 200)
			switch scenario {
			case "revoke-race":
				var ack *httptest.ResponseRecorder
				var revokeErr error
				var wg sync.WaitGroup
				wg.Add(2)
				go func() { defer wg.Done(); ack = f.device(e, "ack") }()
				go func() {
					defer wg.Done()
					revokeErr = f.db.revokeArchiveBinding(context.Background(), f.owner.UserID, f.scope.VehicleID, archiveBindingRequest{SourceInstanceID: f.scope.SourceInstanceID, SourceVehicleID: f.scope.SourceVehicleID})
				}()
				wg.Wait()
				if revokeErr != nil {
					t.Fatal(revokeErr)
				}
				if ack.Code != 200 && ack.Code != 403 {
					t.Fatalf("ACK/revoke race status %d", ack.Code)
				}
				requireEnrollmentStatus(t, f.device(e, "claim"), 403)
				requireEnrollmentStatus(t, f.device(e, "ack"), 403)
			case "expired-receipt":
				f.exec(`UPDATE jourvolt_archive_enrollments SET expires_at=clock_timestamp()-interval '1 second' WHERE handle=$1`, e["handle"])
				requireEnrollmentStatus(t, f.device(e, "claim"), 410)
				requireEnrollmentStatus(t, f.device(e, "ack"), 410)
			case "receipt-corrupt":
				f.exec(`UPDATE jourvolt_archive_enrollments SET issued_ciphertext='invalid' WHERE handle=$1`, e["handle"])
				requireEnrollmentStatus(t, f.device(e, "claim"), 503)
			case "installed-rebind":
				requireEnrollmentStatus(t, f.device(e, "ack"), 200)
				_, _, err := f.db.createArchiveBinding(context.Background(), f.owner.UserID, f.scope.VehicleID, archiveBindingRequest{SourceInstanceID: f.scope.SourceInstanceID, SourceVehicleID: f.scope.SourceVehicleID})
				if err != nil {
					t.Fatal(err)
				}
				requireEnrollmentStatus(t, f.device(e, "ack"), 403)
				requireEnrollmentStatus(t, f.device(e, "claim"), 403)
			}
		})
	}
}

func TestArchiveEnrollmentPostgresExpiryAndSessionCheckedAfterBindingLock(t *testing.T) {
	for _, mode := range []string{"enrollment", "session", "session-preview"} {
		t.Run(mode, func(t *testing.T) {
			f := newEnrollmentFixture(t)
			e := f.create(f.scope)
			if mode == "enrollment" {
				f.exec(`UPDATE jourvolt_archive_enrollments SET expires_at=clock_timestamp()+interval '500 milliseconds' WHERE handle=$1`, e["handle"])
			} else {
				f.exec(`UPDATE jourvolt_sessions SET access_expires_at=clock_timestamp()+interval '500 milliseconds' WHERE access_hash=$1`, hashToken(f.owner.AccessToken))
			}
			tx, err := f.db.pool.Begin(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			defer tx.Rollback(context.Background())
			if _, err = tx.Exec(context.Background(), `SELECT id FROM jourvolt_history_archive_bindings WHERE user_id=$1 FOR UPDATE`, f.owner.UserID); err != nil {
				t.Fatal(err)
			}
			done := make(chan *httptest.ResponseRecorder, 1)
			go func() {
				if mode == "session-preview" {
					done <- f.request("GET", archiveEnrollmentBase+"/"+e["handle"].(string), f.owner.AccessToken, "")
				} else {
					done <- f.decide(e, true)
				}
			}()
			time.Sleep(700 * time.Millisecond)
			if err = tx.Commit(context.Background()); err != nil {
				t.Fatal(err)
			}
			want := 410
			if mode != "enrollment" {
				want = 401
			}
			requireEnrollmentStatus(t, <-done, want)
		})
	}
}

func TestArchiveEnrollmentFingerprintContract(t *testing.T) {
	scope := archiveEnrollmentScope{VehicleID: 1, SourceInstanceID: "home-instance", SourceVehicleID: "car-1"}
	if got := scope.fingerprint(); got != hashToken(`[1,"home-instance","car-1"]`)[:16] {
		t.Fatalf("fingerprint mismatch: %s", got)
	}
}
