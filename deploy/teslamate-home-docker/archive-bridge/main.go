package main

import (
	"context"
	"errors"
	"log"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	config, err := LoadConfig()
	if err != nil {
		log.Printf("archive bridge configuration rejected: %v", err)
		return
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
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
