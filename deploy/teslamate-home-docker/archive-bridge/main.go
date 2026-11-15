package main

import (
	"context"
	"errors"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/matelink/teslamate-archive-bridge/internal/credentialsocket"
)

func main() {
	config, err := LoadConfig()
	if err != nil {
		log.Printf("archive bridge configuration rejected: %v", err)
		return
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if path := os.Getenv("BATTERY_TREND_CREDENTIAL_SOCKET"); path != "" {
		brokerCtx, cancel := context.WithCancel(ctx)
		done := make(chan struct{})
		credentials := credentialsocket.Credentials{
			DatabaseURL: config.DatabaseURL, ArchiveToken: config.ArchiveToken, ArchiveURL: config.ArchiveURL,
			VehicleID: config.ArchiveVehicleID, SourceInstanceID: config.SourceInstanceID, SourceVehicleID: config.SourceVehicleID,
		}
		go func() { defer close(done); runCredentialBroker(brokerCtx, path, credentials, 10*time.Second) }()
		defer func() { cancel(); <-done }()
	}
	pool, err := pgxpool.New(ctx, config.DatabaseURL)
	if err != nil {
		log.Print("archive bridge database initialization failed")
		return
	}
	defer pool.Close()
	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	err = pool.Ping(pingCtx)
	cancel()
	if err != nil {
		log.Print("archive bridge database is unavailable")
		return
	}

	bridge := NewBridge(config, NewPostgresSource(pool, config.SourceCarID))
	if err := bridge.Run(ctx); err != nil && !errors.Is(err, context.Canceled) {
		log.Print("archive bridge stopped")
	}
}

func runCredentialBroker(ctx context.Context, path string, credentials credentialsocket.Credentials, retry time.Duration) {
	for {
		broker, err := credentialsocket.Listen(path, credentials)
		if err == nil {
			<-ctx.Done()
			_ = broker.Close()
			return
		}
		log.Print("battery trend credential broker unavailable")
		timer := time.NewTimer(retry)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
	}
}
