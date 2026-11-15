//go:build linux

package main

import (
	"bufio"
	"bytes"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

type mountBacking struct{ device, root, point string }

func pathWithin(path, parent string) bool {
	rel, err := filepath.Rel(parent, path)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

func backingFor(path string, mounts []mountBacking) (mountBacking, string, error) {
	best := mountBacking{}
	for _, mount := range mounts {
		if pathWithin(path, mount.point) && len(mount.point) > len(best.point) {
			best = mount
		}
	}
	if best.point == "" {
		return mountBacking{}, "", errors.New("mount unavailable")
	}
	rel, err := filepath.Rel(best.point, path)
	if err != nil {
		return mountBacking{}, "", errors.New("mount unavailable")
	}
	return best, filepath.Join(best.root, rel), nil
}

func readMountBackings(reader io.Reader) ([]mountBacking, error) {
	data, err := io.ReadAll(io.LimitReader(reader, (2<<20)+1))
	if err != nil || len(data) > 2<<20 {
		return nil, errors.New("mount information unavailable")
	}
	var mounts []mountBacking
	seen := make(map[string]mountBacking)
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 4096), 1<<20)
	decode := strings.NewReplacer(`\040`, " ", `\011`, "\t", `\012`, "\n", `\134`, `\`)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 10 {
			return nil, errors.New("invalid mount information")
		}
		m := mountBacking{device: fields[2], root: decode.Replace(fields[3]), point: decode.Replace(fields[4])}
		if prior, ok := seen[m.point]; ok && (m.device != prior.device || m.root != prior.root) {
			return nil, errors.New("ambiguous mount information")
		}
		seen[m.point] = m
		mounts = append(mounts, m)
	}
	if scanner.Err() != nil || len(mounts) == 0 {
		return nil, errors.New("mount information unavailable")
	}
	return mounts, nil
}

func requireReadOnlyOriginal(c config, socketPath string) error {
	var fs syscall.Statfs_t
	if syscall.Statfs(c.OriginalStateFile, &fs) != nil || fs.Flags&1 == 0 {
		return errors.New("original state mount is not read-only")
	}
	fileInfo, err := os.Stat(c.OriginalStateFile)
	if err != nil || !fileInfo.Mode().IsRegular() {
		return errors.New("original state is not a regular file")
	}
	fileStat, ok := fileInfo.Sys().(*syscall.Stat_t)
	if !ok || fileStat.Nlink != 1 {
		return errors.New("original state has ambiguous aliases")
	}
	original, err := os.Stat(filepath.Dir(c.OriginalStateFile))
	if err != nil {
		return errors.New("original state directory unavailable")
	}
	trend, err := os.Stat(filepath.Dir(c.StateFile))
	if err != nil || os.SameFile(original, trend) {
		return errors.New("trend state directory is not independent")
	}
	mountFile, err := os.Open("/proc/self/mountinfo")
	if err != nil {
		return errors.New("mount information unavailable")
	}
	mounts, err := readMountBackings(mountFile)
	_ = mountFile.Close()
	if err != nil {
		return err
	}
	originalPath, err := filepath.EvalSymlinks(c.OriginalStateFile)
	if err != nil {
		return errors.New("original state unavailable")
	}
	trendPath, err := filepath.EvalSymlinks(filepath.Dir(c.StateFile))
	if err != nil {
		return errors.New("trend state directory unavailable")
	}
	socketDir, err := filepath.EvalSymlinks(filepath.Dir(socketPath))
	if err != nil {
		return errors.New("credential socket directory unavailable")
	}
	om, op, oe := backingFor(originalPath, mounts)
	tm, tp, te := backingFor(trendPath, mounts)
	sm, sp, se := backingFor(socketDir, mounts)
	if oe != nil || te != nil || se != nil || (om.device == tm.device && pathWithin(op, tp)) ||
		(sm.device == tm.device && (pathWithin(sp, tp) || pathWithin(tp, sp))) ||
		(om.device == sm.device && pathWithin(op, sp)) {
		return errors.New("state and credential mounts are not independent")
	}
	return nil
}
