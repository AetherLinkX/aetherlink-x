package alx

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"time"
)

// TurboAuthSize is deliberately fixed. The frame is carried only after the
// TLS handshake (inside a normal-looking HTTPS/WebSocket upgrade on TCP), so
// no protocol marker is exposed on the wire.
const TurboAuthSize = 8 + 16 + sha256.Size

const turboKeyLabel = "aetherlink-x/turbo/auth/v1"

// TurboAuth binds authentication to both a fresh nonce and the current TLS
// session. Capturing a valid frame from one connection cannot authenticate a
// different TLS connection.
type TurboAuth struct {
	Timestamp time.Time
	Nonce     [16]byte
	MAC       [sha256.Size]byte
	raw       [8 + 16]byte
}

func WriteTurboAuth(w io.Writer, token, sessionBinding []byte, now time.Time) error {
	if len(sessionBinding) < 32 {
		return errors.New("ALX Turbo requires a 32-byte TLS session binding")
	}
	frame := make([]byte, TurboAuthSize)
	binary.BigEndian.PutUint64(frame[:8], uint64(now.Unix()))
	if _, err := rand.Read(frame[8:24]); err != nil {
		return fmt.Errorf("generate Turbo auth nonce: %w", err)
	}
	mac := hmac.New(sha256.New, deriveTurboKey(token, sessionBinding))
	_, _ = mac.Write(frame[:24])
	copy(frame[24:], mac.Sum(nil))
	return writeAll(w, frame)
}

func ReadTurboAuth(r io.Reader) (TurboAuth, error) {
	var auth TurboAuth
	if _, err := io.ReadFull(r, auth.raw[:]); err != nil {
		return auth, err
	}
	auth.Timestamp = time.Unix(int64(binary.BigEndian.Uint64(auth.raw[:8])), 0)
	copy(auth.Nonce[:], auth.raw[8:24])
	if _, err := io.ReadFull(r, auth.MAC[:]); err != nil {
		return auth, err
	}
	return auth, nil
}

func (a TurboAuth) Verify(token, sessionBinding []byte, now time.Time, tolerance time.Duration) bool {
	if len(sessionBinding) < 32 {
		return false
	}
	if delta := now.Sub(a.Timestamp); delta > tolerance || delta < -tolerance {
		return false
	}
	mac := hmac.New(sha256.New, deriveTurboKey(token, sessionBinding))
	_, _ = mac.Write(a.raw[:])
	return hmac.Equal(a.MAC[:], mac.Sum(nil))
}

func deriveTurboKey(token, sessionBinding []byte) []byte {
	// HMAC-based extract-and-expand: the PSK is never used directly as the
	// per-session authentication key and the TLS exporter supplies uniqueness.
	extract := hmac.New(sha256.New, token)
	_, _ = extract.Write([]byte(turboKeyLabel))
	_, _ = extract.Write(sessionBinding)
	prk := extract.Sum(nil)
	expand := hmac.New(sha256.New, prk)
	_, _ = expand.Write([]byte{1})
	return expand.Sum(nil)
}
