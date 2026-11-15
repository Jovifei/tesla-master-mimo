//go:build !linux && !windows

package main

import (
	"errors"
	"os"
)

func acquireStateLock(string) (*os.File, error) {
	return nil, errors.New("state locking is not supported on this platform")
}
