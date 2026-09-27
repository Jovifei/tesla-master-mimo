package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
)

func loadCursor(path string) (Cursor, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return Cursor{}, nil
	}
	if err != nil {
		return Cursor{}, err
	}
	var cursor Cursor
	if err := json.Unmarshal(data, &cursor); err != nil {
		return Cursor{}, err
	}
	if cursor.LastDriveID < 0 || cursor.LastChargeID < 0 {
		return Cursor{}, errors.New("cursor IDs must not be negative")
	}
	return cursor, nil
}

func saveCursor(path string, cursor Cursor) error {
	return saveCursorWithRename(path, cursor, os.Rename)
}

func saveCursorWithRename(path string, cursor Cursor, rename func(string, string) error) (err error) {
	data, err := json.Marshal(cursor)
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(dir, "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer func() {
		if temporaryPath != "" {
			_ = os.Remove(temporaryPath)
		}
	}()
	if err := temporary.Chmod(0o600); err != nil {
		_ = temporary.Close()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		_ = temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		_ = temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := rename(temporaryPath, path); err != nil {
		return err
	}
	temporaryPath = ""
	return nil
}
