package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	c, err := loadRunConfig(os.Args[1:], os.Stdin, os.LookupEnv)
	if err != nil {
		log.Print("battery trend configuration rejected")
		os.Exit(1)
	}
	lock, err := acquireStateLock(c.StateFile + ".lock")
	if err != nil {
		log.Print("battery trend state already in use or unavailable")
		os.Exit(1)
	}
	defer lock.Close()
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	pg, err := pgxpool.ParseConfig(c.DatabaseURL)
	if err != nil {
		log.Print("battery trend database configuration rejected")
		os.Exit(1)
	}
	pg.ConnConfig.RuntimeParams["default_transaction_read_only"] = "on"
	pg.ConnConfig.RuntimeParams["timezone"] = "UTC"
	pool, err := pgxpool.NewWithConfig(ctx, pg)
	if err != nil {
		log.Print("battery trend database unavailable")
		os.Exit(1)
	}
	defer pool.Close()
	ping, cancel := context.WithTimeout(ctx, 10*time.Second)
	err = pool.Ping(ping)
	cancel()
	if err != nil {
		log.Print("battery trend database unavailable")
		os.Exit(1)
	}
	client := &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	b := bridge{config: c, source: postgresSource{pool: pool, carID: c.SourceCarID}, client: client}
	if err = b.run(ctx); err != nil && !errors.Is(err, context.Canceled) {
		log.Print("battery trend stopped")
		os.Exit(1)
	}
}
