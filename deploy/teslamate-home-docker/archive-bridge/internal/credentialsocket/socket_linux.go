//go:build linux

package credentialsocket

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

const maxCredentialBytes = 16 << 10

type Server struct {
	listener *net.UnixListener
	lock     *os.File
	payload  []byte
	done     chan struct{}
	once     sync.Once
}

func privateDirectory(path string) error {
	if !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return ErrRejected
	}
	dir := filepath.Dir(path)
	resolved, err := filepath.EvalSymlinks(dir)
	if err != nil {
		return ErrUnavailable
	}
	if resolved != dir {
		return ErrRejected
	}
	info, err := os.Lstat(dir)
	if err != nil {
		return ErrUnavailable
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || !info.IsDir() || info.Mode().Perm()&0077 != 0 || stat.Uid != uint32(os.Geteuid()) {
		return ErrRejected
	}
	return nil
}

func sameUID(c *net.UnixConn) bool {
	raw, err := c.SyscallConn()
	if err != nil {
		return false
	}
	var ucred *syscall.Ucred
	var socketErr error
	if raw.Control(func(fd uintptr) {
		ucred, socketErr = syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
	}) != nil {
		return false
	}
	return socketErr == nil && ucred != nil && ucred.Uid == uint32(os.Geteuid())
}

func Listen(path string, credentials Credentials) (*Server, error) {
	if err := privateDirectory(path); err != nil {
		return nil, err
	}
	payload, err := json.Marshal(credentials)
	if err != nil || len(payload) > maxCredentialBytes || credentials.DatabaseURL == "" || credentials.ArchiveToken == "" {
		return nil, ErrRejected
	}
	fd, err := syscall.Open(path+".lock", syscall.O_CREAT|syscall.O_RDWR|syscall.O_CLOEXEC|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return nil, ErrRejected
	}
	lock := os.NewFile(uintptr(fd), "credential-broker-lock")
	var st syscall.Stat_t
	if syscall.Fstat(fd, &st) != nil || st.Mode&syscall.S_IFMT != syscall.S_IFREG || st.Mode&0077 != 0 || st.Uid != uint32(os.Geteuid()) || st.Nlink != 1 || syscall.Flock(fd, syscall.LOCK_EX|syscall.LOCK_NB) != nil {
		lock.Close()
		return nil, ErrRejected
	}
	if info, e := os.Lstat(path); e == nil {
		stat, ok := info.Sys().(*syscall.Stat_t)
		if !ok || info.Mode()&os.ModeSocket == 0 || stat.Uid != uint32(os.Geteuid()) {
			lock.Close()
			return nil, ErrRejected
		}
		conn, dialErr := net.DialTimeout("unix", path, 200*time.Millisecond)
		if dialErr == nil {
			conn.Close()
			lock.Close()
			return nil, ErrRejected
		}
		if !errors.Is(dialErr, syscall.ECONNREFUSED) || os.Remove(path) != nil {
			lock.Close()
			return nil, ErrRejected
		}
	} else if !errors.Is(e, os.ErrNotExist) {
		lock.Close()
		return nil, ErrRejected
	}
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		lock.Close()
		return nil, ErrUnavailable
	}
	if os.Chmod(path, 0600) != nil {
		listener.Close()
		lock.Close()
		return nil, ErrRejected
	}
	s := &Server{listener: listener, lock: lock, payload: payload, done: make(chan struct{})}
	go s.serve()
	return s, nil
}

func (s *Server) serve() {
	defer close(s.done)
	for {
		conn, err := s.listener.AcceptUnix()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			time.Sleep(time.Second)
			continue
		}
		_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
		if sameUID(conn) {
			_, _ = conn.Write(s.payload)
		}
		_ = conn.Close()
	}
}

func (s *Server) Close() error {
	var err error
	s.once.Do(func() {
		err = s.listener.Close()
		<-s.done
		clear(s.payload)
		_ = s.lock.Close()
	})
	return err
}

func Fetch(ctx context.Context, path string) (Credentials, error) {
	if err := privateDirectory(path); err != nil {
		return Credentials{}, err
	}
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return Credentials{}, ErrUnavailable
	}
	if err != nil {
		return Credentials{}, ErrUnavailable
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || info.Mode()&os.ModeSocket == 0 || info.Mode().Perm() != 0600 || stat.Uid != uint32(os.Geteuid()) {
		return Credentials{}, ErrRejected
	}
	conn, err := (&net.Dialer{Timeout: 2 * time.Second}).DialContext(ctx, "unix", path)
	if err != nil {
		return Credentials{}, ErrUnavailable
	}
	defer conn.Close()
	unixConn, ok := conn.(*net.UnixConn)
	if !ok || !sameUID(unixConn) {
		return Credentials{}, ErrRejected
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	data, err := io.ReadAll(io.LimitReader(conn, maxCredentialBytes+1))
	defer clear(data)
	if err != nil {
		return Credentials{}, ErrUnavailable
	}
	if len(data) > maxCredentialBytes {
		return Credentials{}, ErrRejected
	}
	var credentials Credentials
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&credentials) != nil || decoder.Decode(&struct{}{}) != io.EOF || credentials.DatabaseURL == "" || credentials.ArchiveToken == "" {
		return Credentials{}, ErrRejected
	}
	return credentials, nil
}
