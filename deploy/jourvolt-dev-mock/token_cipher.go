package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"io"
)

type tokenCipher struct {
	aead cipher.AEAD
}

func newTokenCipher(key []byte) (*tokenCipher, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("create token cipher: %w", err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("create token GCM: %w", err)
	}
	return &tokenCipher{aead: aead}, nil
}

func (c *tokenCipher) encrypt(value string) (string, error) {
	nonce := make([]byte, c.aead.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", fmt.Errorf("create token nonce: %w", err)
	}
	sealed := c.aead.Seal(nonce, nonce, []byte(value), nil)
	return base64.RawURLEncoding.EncodeToString(sealed), nil
}

func (c *tokenCipher) decrypt(value string) (string, error) {
	sealed, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(sealed) < c.aead.NonceSize() {
		return "", fmt.Errorf("invalid encrypted value")
	}
	nonce := sealed[:c.aead.NonceSize()]
	plain, err := c.aead.Open(nil, nonce, sealed[c.aead.NonceSize():], nil)
	if err != nil {
		return "", fmt.Errorf("decrypt value: %w", err)
	}
	return string(plain), nil
}
