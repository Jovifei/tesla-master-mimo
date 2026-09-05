package main

import (
	"context"
	"testing"
	"time"
)

func TestTelemetryWorkerWatcherExitsWhenStartupContextIsCancelled(t *testing.T) {
	t.Run("startup context cancellation", func(t *testing.T) {
		subscriber := newTelemetrySubscriber(newTelemetryServiceForTest("partner.example.com"))
		ctx, cancel := context.WithCancel(context.Background())
		t.Cleanup(cancel)
		t.Cleanup(subscriber.stop)
		subscriber.startWorker(ctx)
		cancel()
		waitForWorkerWatcherExit(t, subscriber)
	})

	t.Run("subscriber stop", func(t *testing.T) {
		subscriber := newTelemetrySubscriber(newTelemetryServiceForTest("partner.example.com"))
		t.Cleanup(subscriber.stop)
		subscriber.startWorker(context.Background())
		subscriber.stop()
		waitForWorkerWatcherExit(t, subscriber)
	})
}

func waitForWorkerWatcherExit(t *testing.T, subscriber *telemetrySubscriber) {
	t.Helper()
	if subscriber.workerWatcherDone == nil {
		t.Fatal("worker watcher completion signal is unavailable")
	}
	select {
	case <-subscriber.workerWatcherDone:
	case <-time.After(time.Second):
		t.Fatal("worker watcher remained after shutdown")
	}
}
