// Synthetic HTTPS archive receipt sink for an isolated Docker network only.
// It stores counts, not submitted records. No database/provider/client credentials.
package main

import (
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

func main() {
	if os.Getenv("FIXTURE_MODE") == "probe" {
		probeRuntime()
		return
	}
	directory := "/fixture"
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		log.Fatal(err)
	}
	certificate := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "SYNTHETIC-COMPAT430-SINK"}, DNSNames: []string{"archive-sink"}, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(24 * time.Hour), KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, BasicConstraintsValid: true, IsCA: true}
	der, err := x509.CreateCertificate(rand.Reader, certificate, certificate, &key.PublicKey, key)
	if err != nil {
		log.Fatal(err)
	}
	certPath, keyPath := filepath.Join(directory, "synthetic.crt"), filepath.Join(directory, "synthetic.key")
	if err = os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600); err != nil {
		log.Fatal(err)
	}
	if err = os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)}), 0600); err != nil {
		log.Fatal(err)
	}
	var mu sync.Mutex
	counts := map[string]int{"requests": 0, "drives": 0, "charges": 0}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		_ = json.NewEncoder(w).Encode(counts)
	})
	mux.HandleFunc("POST /api/v1/cars/fixture/history/archive/import", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-MateLink-Archive-Binding") != "SYNTHETIC-ARCHIVE-ONLY" {
			http.Error(w, "invalid synthetic binding", 401)
			return
		}
		var batch struct {
			SourceInstanceID string            `json:"source_instance_id"`
			SourceVehicleID  string            `json:"source_vehicle_id"`
			Drives           []json.RawMessage `json:"drives"`
			Charges          []json.RawMessage `json:"charges"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<20)).Decode(&batch) != nil || batch.SourceInstanceID != "synthetic-instance" || batch.SourceVehicleID != "7" {
			http.Error(w, "invalid synthetic scope", 400)
			return
		}
		mu.Lock()
		counts["requests"]++
		counts["drives"] += len(batch.Drives)
		counts["charges"] += len(batch.Charges)
		raw, _ := json.Marshal(counts)
		err := os.WriteFile(filepath.Join(directory, "receipt-counts.json"), raw, 0600)
		mu.Unlock()
		if err != nil {
			http.Error(w, "fixture write failed", 500)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"data": map[string]int{"imported_drives": len(batch.Drives), "imported_charges": len(batch.Charges)}})
	})
	log.Fatal(http.ListenAndServeTLS(":8443", certPath, keyPath, mux))
}

func probeRuntime() {
	client := &http.Client{Timeout: 15 * time.Second}
	call := func(method, endpoint string, payload []byte) []byte {
		req, err := http.NewRequest(method, endpoint, bytes.NewReader(payload))
		if err != nil {
			log.Fatal(err)
		}
		req.SetBasicAuth("synthetic-admin", "SYNTHETIC_GRAFANA_ONLY_1234567890123")
		req.Header.Set("Content-Type", "application/json")
		response, err := client.Do(req)
		if err != nil {
			log.Fatal(err)
		}
		defer response.Body.Close()
		body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
		if err != nil || response.StatusCode != 200 {
			log.Fatalf("synthetic probe failed %s status=%d body=%s err=%v", endpoint, response.StatusCode, body, err)
		}
		return body
	}
	call("GET", "http://teslamate:4000", nil)
	health := call("GET", "http://grafana:3000/api/health", nil)
	var sources []struct {
		Name string `json:"name"`
		UID  string `json:"uid"`
		Type string `json:"type"`
	}
	if json.Unmarshal(call("GET", "http://grafana:3000/api/datasources", nil), &sources) != nil {
		log.Fatal("invalid synthetic datasource response")
	}
	uid, kind := "", ""
	for _, source := range sources {
		if source.Name == "TeslaMate" {
			uid, kind = source.UID, source.Type
		}
	}
	if uid == "" {
		log.Fatal("TeslaMate datasource missing")
	}
	request, _ := json.Marshal(map[string]any{"from": "now-1h", "to": "now", "queries": []any{map[string]any{"refId": "A", "datasource": map[string]string{"uid": uid, "type": kind}, "rawSql": "SELECT count(*) AS fixture_drive_count FROM drives WHERE car_id = 7", "format": "table", "intervalMs": 1000, "maxDataPoints": 10}}})
	result := call("POST", "http://grafana:3000/api/ds/query", request)
	var response struct {
		Results map[string]struct {
			Error  string `json:"error"`
			Frames []struct {
				Data struct {
					Values [][]any `json:"values"`
				} `json:"data"`
			} `json:"frames"`
		} `json:"results"`
	}
	if json.Unmarshal(result, &response) != nil || response.Results["A"].Error != "" || len(response.Results["A"].Frames) != 1 {
		log.Fatalf("Grafana SQL failed: %s", result)
	}
	values := response.Results["A"].Frames[0].Data.Values
	if len(values) != 1 || len(values[0]) != 1 || values[0][0] != float64(3) {
		log.Fatalf("Grafana SQL fixture count mismatch: %s", result)
	}
	fmt.Printf("{\"status\":\"PASS_ISOLATED_RUNTIME\",\"teslamate_http\":200,\"grafana_sql_drive_count\":3,\"grafana_health\":%s}\n", health)
}
