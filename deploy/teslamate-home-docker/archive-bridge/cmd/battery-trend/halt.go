package main

import (
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
)

var errHaltUnavailable = errors.New("battery trend halt marker unavailable")

func checkHalt(c config) (bool, error) {
	path := c.StateFile + ".halt"
	if err := distinctPaths(c.OriginalStateFile, path); err != nil {
		return true, errHaltUnavailable
	}
	if _, err := os.Lstat(path); err == nil {
		return true, nil
	} else if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	return true, errHaltUnavailable
}

func markHalt(c config, status int) error {
	halted, err := checkHalt(c)
	if err != nil {
		return err
	}
	if status < 400 || status > 499 || status == http.StatusRequestTimeout || status == http.StatusTooManyRequests {
		return errors.New("battery trend status is not permanent")
	}
	if halted {
		return nil
	}
	path := c.StateFile + ".halt"
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if errors.Is(err, os.ErrExist) {
		halted, checkErr := checkHalt(c)
		if checkErr == nil && halted {
			return nil
		}
		return errHaltUnavailable
	}
	if err != nil {
		return errHaltUnavailable
	}
	_, writeErr := fmt.Fprintf(f, "http_status=%d\n", status)
	if writeErr == nil {
		writeErr = f.Sync()
	}
	closeErr := f.Close()
	if writeErr != nil || closeErr != nil {
		return errHaltUnavailable
	}
	if runtime.GOOS == "linux" {
		dir, openErr := os.Open(filepath.Dir(path))
		if openErr != nil {
			return errHaltUnavailable
		}
		syncErr := dir.Sync()
		closeErr = dir.Close()
		if syncErr != nil || closeErr != nil {
			return errHaltUnavailable
		}
	}
	return nil
}
