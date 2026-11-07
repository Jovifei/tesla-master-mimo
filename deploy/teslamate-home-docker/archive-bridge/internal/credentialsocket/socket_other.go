//go:build !linux

package credentialsocket

import "context"

type Server struct{}

func Listen(string, Credentials) (*Server, error)        { return nil, ErrRejected }
func (*Server) Close() error                             { return nil }
func Fetch(context.Context, string) (Credentials, error) { return Credentials{}, ErrRejected }
