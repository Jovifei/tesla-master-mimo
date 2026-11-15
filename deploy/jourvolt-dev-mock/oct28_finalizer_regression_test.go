package main

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// These are driver-contract simulations, not real PostgreSQL integration tests.
// pgx v5.7.2 rows.go requires Close or Next == false before the same connection
// can run another command. The fake keeps that connection occupied even while
// positioned on the last row, matching the driver rather than a buffered mock.
func TestOct28FinalizerDriverContractSimulation(t *testing.T) {
	now := time.Date(2026, 10, 28, 12, 0, 0, 0, time.UTC)
	debounce := 20 * time.Second
	t.Run("not due keeps session open and releases rows before commit", func(t *testing.T) {
		row := oct28FinalizerSession{id: "not-due", publicID: 1, started: now.Add(-time.Hour), candidate: now.Add(-19 * time.Second)}
		tx := oct28FinalizerTxFor(row)
		completed, err := finalizeDuePostgresTx(context.Background(), tx, now, debounce)
		if err != nil || completed != 0 || tx.sessions[0].ended != nil {
			t.Fatalf("not-due session: completed=%d err=%v ended=%v", completed, err, tx.sessions[0].ended)
		}
		oct28CheckFinalizerTx(t, tx, 1, 0)
	})
	t.Run("due session completes without connection busy", func(t *testing.T) {
		row := oct28FinalizerSession{id: "due", publicID: 2, started: now.Add(-time.Hour), candidate: now.Add(-debounce)}
		tx := oct28FinalizerTxFor(row)
		completed, err := finalizeDuePostgresTx(context.Background(), tx, now, debounce)
		if err != nil || completed != 1 {
			t.Fatalf("due session must finalize after releasing pgx rows: completed=%d err=%v", completed, err)
		}
		if tx.sessions[0].ended == nil || !tx.sessions[0].ended.Equal(now) || tx.sessions[0].completionKey == "" {
			t.Fatalf("completion boundaries were not persisted: %+v", tx.sessions[0])
		}
		oct28CheckFinalizerTx(t, tx, 1, 0)
	})
	t.Run("multiple due sessions leave not due session open", func(t *testing.T) {
		tx := oct28FinalizerTxFor(
			oct28FinalizerSession{id: "due-a", publicID: 3, started: now.Add(-3 * time.Hour), candidate: now.Add(-time.Minute)},
			oct28FinalizerSession{id: "not-due", publicID: 4, started: now.Add(-2 * time.Hour), candidate: now.Add(-time.Second)},
			oct28FinalizerSession{id: "due-b", publicID: 5, started: now.Add(-time.Hour), candidate: now.Add(-debounce)},
		)
		completed, err := finalizeDuePostgresTx(context.Background(), tx, now, debounce)
		if err != nil || completed != 2 {
			t.Fatalf("multiple due sessions: completed=%d err=%v", completed, err)
		}
		if tx.sessions[0].ended == nil || !tx.sessions[0].ended.Equal(now.Add(-40*time.Second)) || tx.sessions[1].ended != nil || tx.sessions[2].ended == nil || !tx.sessions[2].ended.Equal(now) {
			t.Fatalf("wrong completed session set: %+v", tx.sessions)
		}
		oct28CheckFinalizerTx(t, tx, 1, 0)
	})
	t.Run("repeat execution does not complete a session twice", func(t *testing.T) {
		tx := oct28FinalizerTxFor(oct28FinalizerSession{id: "repeat", publicID: 6, started: now.Add(-time.Hour), candidate: now.Add(-debounce)})
		completed, err := finalizeDuePostgresTx(context.Background(), tx, now, debounce)
		if err != nil || completed != 1 {
			t.Fatalf("first execution: completed=%d err=%v", completed, err)
		}
		originalEnd, originalKey := *tx.sessions[0].ended, tx.sessions[0].completionKey
		repeated := oct28FinalizerTxFor(tx.sessions...)
		completed, err = finalizeDuePostgresTx(context.Background(), repeated, now.Add(time.Minute), debounce)
		if err != nil || completed != 0 || repeated.execCalls != 0 || !repeated.sessions[0].ended.Equal(originalEnd) || repeated.sessions[0].completionKey != originalKey {
			t.Fatalf("repeat changed completion: completed=%d err=%v session=%+v", completed, err, repeated.sessions[0])
		}
		oct28CheckFinalizerTx(t, repeated, 1, 0)
	})
	for _, failure := range []string{"query", "scan", "rows", "exec", "commit"} {
		t.Run(failure+" failure rolls back without persisting completion", func(t *testing.T) {
			sentinel := errors.New("injected " + failure + " failure")
			// Commit failure needs no due row to exercise the existing commit path
			// independently of the busy-connection regression.
			candidate := now.Add(-debounce)
			if failure == "commit" {
				candidate = now.Add(-time.Second)
			}
			tx := oct28FinalizerTxFor(oct28FinalizerSession{id: "error", publicID: 7, started: now.Add(-time.Hour), candidate: candidate})
			switch failure {
			case "query":
				tx.queryErr = sentinel
			case "scan":
				tx.scanErr = sentinel
			case "rows":
				tx.rowsErr = sentinel
			case "exec":
				tx.execErr = sentinel
			case "commit":
				tx.commitErr = sentinel
			}
			completed, err := finalizeDuePostgresTx(context.Background(), tx, now, debounce)
			if !errors.Is(err, sentinel) || completed != 0 || tx.sessions[0].ended != nil || len(tx.staged) != 0 {
				t.Fatalf("%s error boundary: completed=%d err=%v session=%+v staged=%v", failure, completed, err, tx.sessions[0], tx.staged)
			}
			commits, rollbacks := 0, 1
			if failure == "commit" {
				commits = 1
				rollbacks = 0 // pgx Commit closes the transaction even on error.
			}
			oct28CheckFinalizerTx(t, tx, commits, rollbacks)
		})
	}
}

type oct28FinalizerSession struct {
	id            string
	publicID      int
	started       time.Time
	candidate     time.Time
	ended         *time.Time
	completionKey string
}

type oct28FinalizerTx struct {
	pgx.Tx                                         // Unexpected calls panic; only the actual transaction operations below are supported.
	sessions                                       []oct28FinalizerSession
	rows                                           *oct28FinalizerRows
	staged                                         map[string]oct28FinalizerSession
	queryErr, scanErr, rowsErr, execErr, commitErr error
	execCalls, commitCalls, rollbackCalls          int
	rollbackEffects                                int
	closed                                         bool
}

func oct28FinalizerTxFor(sessions ...oct28FinalizerSession) *oct28FinalizerTx {
	return &oct28FinalizerTx{sessions: append([]oct28FinalizerSession(nil), sessions...), staged: make(map[string]oct28FinalizerSession)}
}

func (tx *oct28FinalizerTx) Query(_ context.Context, sql string, args ...any) (pgx.Rows, error) {
	if tx.closed || tx.rows != nil || len(args) != 0 || sql != "SELECT id, public_id, started_at, stop_candidate_at FROM jourvolt_telemetry_sessions WHERE kind='drive' AND ended_at IS NULL AND stop_candidate_at IS NOT NULL FOR UPDATE" {
		return nil, errors.New("unexpected finalizer SELECT or transaction state")
	}
	if tx.queryErr != nil {
		return nil, tx.queryErr
	}
	rows := &oct28FinalizerRows{tx: tx, index: -1}
	for _, session := range tx.sessions {
		if session.ended == nil && !session.candidate.IsZero() {
			rows.sessions = append(rows.sessions, session)
		}
	}
	tx.rows = rows
	return rows, nil
}

func (tx *oct28FinalizerTx) Exec(_ context.Context, sql string, args ...any) (pgconn.CommandTag, error) {
	tx.execCalls++
	if tx.rows != nil && !tx.rows.closed {
		return pgconn.CommandTag{}, errors.New("conn busy")
	}
	if tx.closed || len(args) != 3 || sql != "UPDATE jourvolt_telemetry_sessions SET ended_at=$1, stop_candidate_at=NULL, completion_key=$2 WHERE id=$3 AND ended_at IS NULL" {
		return pgconn.CommandTag{}, errors.New("unexpected finalizer UPDATE or transaction state")
	}
	if tx.execErr != nil {
		return pgconn.CommandTag{}, tx.execErr
	}
	end, endOK := args[0].(time.Time)
	key, keyOK := args[1].(string)
	id, idOK := args[2].(string)
	if !endOK || !keyOK || !idOK || key == "" {
		return pgconn.CommandTag{}, fmt.Errorf("invalid completion arguments: %T, %T, %T", args[0], args[1], args[2])
	}
	for _, session := range tx.sessions {
		if session.id == id && session.ended == nil {
			if _, alreadyStaged := tx.staged[id]; alreadyStaged {
				return pgconn.NewCommandTag("UPDATE 0"), nil
			}
			session.ended, session.completionKey = &end, key
			tx.staged[id] = session
			return pgconn.NewCommandTag("UPDATE 1"), nil
		}
	}
	return pgconn.NewCommandTag("UPDATE 0"), nil
}

func (tx *oct28FinalizerTx) Commit(_ context.Context) error {
	tx.commitCalls++
	if tx.rows != nil && !tx.rows.closed {
		return errors.New("conn busy")
	}
	tx.closed = true
	if tx.commitErr != nil {
		clear(tx.staged)
		return tx.commitErr
	}
	for index := range tx.sessions {
		if session, ok := tx.staged[tx.sessions[index].id]; ok {
			tx.sessions[index] = session
		}
	}
	clear(tx.staged)
	return nil
}

func (tx *oct28FinalizerTx) Rollback(_ context.Context) error {
	tx.rollbackCalls++
	if tx.closed {
		return pgx.ErrTxClosed
	}
	if tx.rows != nil && !tx.rows.closed {
		return errors.New("conn busy")
	}
	tx.closed = true
	tx.rollbackEffects++
	clear(tx.staged)
	return nil
}

type oct28FinalizerRows struct {
	pgx.Rows
	tx       *oct28FinalizerTx
	sessions []oct28FinalizerSession
	index    int
	closed   bool
}

func (rows *oct28FinalizerRows) Next() bool {
	if rows.closed {
		return false
	}
	rows.index++
	if rows.index >= len(rows.sessions) || rows.tx.rowsErr != nil {
		rows.Close()
		return false
	}
	return true
}

func (rows *oct28FinalizerRows) Scan(dest ...any) error {
	if rows.tx.scanErr != nil {
		return rows.tx.scanErr
	}
	if rows.closed || rows.index < 0 || rows.index >= len(rows.sessions) || len(dest) != 4 {
		return errors.New("Scan called without an active finalizer row")
	}
	session := rows.sessions[rows.index]
	*dest[0].(*string) = session.id
	*dest[1].(*int) = session.publicID
	*dest[2].(*time.Time) = session.started
	*dest[3].(*time.Time) = session.candidate
	return nil
}

func (rows *oct28FinalizerRows) Close()     { rows.closed = true }
func (rows *oct28FinalizerRows) Err() error { return rows.tx.rowsErr }

func oct28CheckFinalizerTx(t *testing.T, tx *oct28FinalizerTx, commits, rollbacks int) {
	t.Helper()
	if tx.commitCalls != commits || tx.rollbackCalls != 1 || tx.rollbackEffects != rollbacks || !tx.closed || (tx.rows != nil && !tx.rows.closed) {
		t.Fatalf("transaction boundary: commits=%d rollbackCalls=%d rollbackEffects=%d closed=%v rows=%+v", tx.commitCalls, tx.rollbackCalls, tx.rollbackEffects, tx.closed, tx.rows)
	}
}
