//go:build !linux

package main

import "errors"

func requireReadOnlyOriginal(config, string) error {
	return errors.New("read-only mount validation unsupported")
}
