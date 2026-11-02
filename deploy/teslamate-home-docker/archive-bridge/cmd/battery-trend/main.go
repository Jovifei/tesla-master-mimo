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
	"github.com/matelink/teslamate-archive-bridge/internal/credentialsocket"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	socketMode := false
	migrationMode := false
	for _, arg := range os.Args[1:] {
		switch arg {
		case "--credentials-socket":
			socketMode = true
		case "--migrate-v1-binding":
			migrationMode = true
		}
	}
	var c config
	var err error
	if socketMode {
		if len(os.Args) != 2 && !(migrationMode && len(os.Args) == 3) {
			socketMode = false
		}
		c, err = waitForSocketConfig(ctx)
	} else {
		args := os.Args[1:]
		if migrationMode {
			if len(args) != 1 || args[0] != "--migrate-v1-binding" {
				err = errors.New("invalid migration mode")
			} else {
				args = nil
			}
		}
		if err == nil {
			c, err = loadRunConfig(args, os.Stdin, os.LookupEnv)
		}
	}
	if err != nil {
		log.Print("battery trend configuration rejected")
		if socketMode {
			<-ctx.Done()
			return
		}
		os.Exit(1)
	}
	if socketMode && requireReadOnlyOriginal(c, os.Getenv("BATTERY_TREND_CREDENTIAL_SOCKET")) != nil {
		log.Print("battery trend original state isolation rejected")
		<-ctx.Done()
		return
	}
	lock, err := acquireStateLock(c.StateFile + ".lock")
	if err != nil {
		log.Print("battery trend state already in use or unavailable")
		if socketMode {
			<-ctx.Done()
			return
		}
		os.Exit(1)
	}
	defer lock.Close()
	if socketMode {
		halted, haltErr := checkHalt(c)
		if haltErr != nil || halted {
			log.Print("battery trend halted; review required")
			<-ctx.Done()
			return
		}
	}
	if !migrationMode {
		if err = requireLocallySafeRestartState(c); err != nil {
			log.Print("battery trend requires verified owner")
			if socketMode {
				<-ctx.Done()
				return
			}
			os.Exit(1)
		}
	}
	client := &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	var identity string
	for {
		identity, err = fetchBindingIdentity(ctx, c, client)
		if err == nil {
			break
		}
		log.Print("battery trend owner identity unavailable")
		if !socketMode || !waitRetry(ctx, 10*time.Second) {
			if socketMode {
				return
			}
			os.Exit(1)
		}
	}
	c.BindingIdentity = identity
	if migrationMode {
		if os.Getenv("BATTERY_TREND_CONFIRM_LEGACY_STATE_OWNER") != "I_CONFIRM_THIS_LOCAL_STATE_BELONGS_TO_CURRENT_BINDING" {
			log.Print("battery trend migration confirmation required")
			os.Exit(1)
		}
		if err = migrateLegacyState(c, identity); err != nil {
			log.Print("battery trend legacy state migration rejected")
			os.Exit(1)
		}
		log.Print("battery trend legacy state migrated")
		return
	}
	if _, err = verifyOwnerState(c, identity); err != nil {
		log.Print("battery trend requires verified owner")
		if socketMode {
			<-ctx.Done()
			return
		}
		os.Exit(1)
	}
	pg, err := pgxpool.ParseConfig(c.DatabaseURL)
	if err != nil {
		log.Print("battery trend database configuration rejected")
		if socketMode {
			<-ctx.Done()
			return
		}
		os.Exit(1)
	}
	pg.ConnConfig.RuntimeParams["default_transaction_read_only"] = "on"
	pg.ConnConfig.RuntimeParams["timezone"] = "UTC"
	var pool *pgxpool.Pool
	for {
		pool, err = pgxpool.NewWithConfig(ctx, pg)
		if err == nil {
			ping, cancel := context.WithTimeout(ctx, 10*time.Second)
			err = pool.Ping(ping)
			cancel()
			if err == nil {
				break
			}
			pool.Close()
		}
		log.Print("battery trend database unavailable")
		if !socketMode {
			os.Exit(1)
		}
		if !waitRetry(ctx, 10*time.Second) {
			return
		}
	}
	defer pool.Close()
	b := bridge{config: c, source: postgresSource{pool: pool, carID: c.SourceCarID}, client: client}
	if socketMode {
		err = runManaged(ctx, c, b.run)
	} else {
		err = b.run(ctx)
	}
	if errors.Is(err, errManagedHalt) || errors.Is(err, errManagedHaltUnpersisted) {
		if errors.Is(err, errManagedHaltUnpersisted) {
			log.Print("battery trend halt could not be persisted; review required")
		} else {
			log.Print("battery trend halted; review required")
		}
		<-ctx.Done()
		return
	}
	if err != nil && !errors.Is(err, context.Canceled) {
		log.Print("battery trend stopped")
		os.Exit(1)
	}
}

func waitForSocketConfig(ctx context.Context) (config, error) {
	for {
		c, err := loadSocketConfig(ctx, os.LookupEnv)
		if err == nil || !errors.Is(err, credentialsocket.ErrUnavailable) {
			return c, err
		}
		log.Print("battery trend waiting for credential broker or original state")
		if !waitRetry(ctx, 10*time.Second) {
			return config{}, ctx.Err()
		}
	}
}

func waitRetry(ctx context.Context, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
