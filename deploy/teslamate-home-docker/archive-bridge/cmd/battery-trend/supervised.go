package main

import (
	"context"
	"errors"
)

var (
	errManagedHalt            = errors.New("battery trend needs review")
	errManagedHaltUnpersisted = errors.New("battery trend halt could not be persisted")
)

func runManaged(ctx context.Context, c config, run func(context.Context) error) error {
	halted, err := checkHalt(c)
	if err != nil {
		return errManagedHaltUnpersisted
	}
	if halted {
		return errManagedHalt
	}
	err = run(ctx)
	var status trendHTTPError
	if errors.As(err, &status) && status.status >= 400 && status.status < 500 && status.status != 408 && status.status != 429 {
		if markHalt(c, status.status) != nil {
			return errManagedHaltUnpersisted
		}
		return errManagedHalt
	}
	return err
}
