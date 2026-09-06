// Package alx contains the small, versioned wire format shared by the native
// AetherLink client and server. QUIC/TLS provides encryption, integrity,
// congestion control and stream multiplexing. ALX only describes tunnel
// authentication and destination forwarding.
package alx

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"
)

const (
	Version byte = 1

	CommandAuth byte = iota
	CommandTCP
	CommandUDP

	StatusOK          byte = 0
	StatusDenied      byte = 1
	StatusDialFailure byte = 2
	StatusBadRequest  byte = 3

	MaxAddressLength  = 512
	MaxUDPPayload     = 65535
	FastDatagramLimit = 1050
)

var magic = [4]byte{'A', 'L', 'X', '1'}

// DecodeToken accepts URL-safe Base64, regular Base64, hex, or a raw secret.
// A native endpoint deliberately requires at least 256 bits of entropy.
func DecodeToken(value string) ([]byte, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil, errors.New("empty ALX token")
	}
	decoders := []*base64.Encoding{
		base64.RawURLEncoding,
		base64.URLEncoding,
		base64.RawStdEncoding,
		base64.StdEncoding,
	}
	for _, encoding := range decoders {
		if decoded, err := encoding.DecodeString(value); err == nil && len(decoded) >= 32 {
			return decoded, nil
		}
	}
	if decoded, err := hex.DecodeString(value); err == nil && len(decoded) >= 32 {
		return decoded, nil
	}
	if len(value) >= 32 {
		return []byte(value), nil
	}
	return nil, errors.New("ALX token must contain at least 32 bytes")
}

// WriteAuth creates a fresh authenticated connection preface. It is sent on
// the first QUIC stream before any proxy streams or datagrams are accepted.
func WriteAuth(w io.Writer, token []byte, now time.Time) error {
	frame := make([]byte, 4+1+1+8+16)
	copy(frame[:4], magic[:])
	frame[4] = Version
	frame[5] = CommandAuth
	binary.BigEndian.PutUint64(frame[6:14], uint64(now.Unix()))
	if _, err := rand.Read(frame[14:30]); err != nil {
		return fmt.Errorf("generate auth nonce: %w", err)
	}
	mac := hmac.New(sha256.New, token)
	_, _ = mac.Write(frame)
	frame = append(frame, mac.Sum(nil)...)
	return writeAll(w, frame)
}

type Auth struct {
	Timestamp time.Time
	Nonce     [16]byte
	MAC       [32]byte
	raw       [30]byte
}

func ReadAuth(r io.Reader) (Auth, error) {
	var auth Auth
	if _, err := io.ReadFull(r, auth.raw[:]); err != nil {
		return auth, err
	}
	if string(auth.raw[:4]) != string(magic[:]) || auth.raw[4] != Version || auth.raw[5] != CommandAuth {
		return auth, errors.New("invalid ALX auth preface")
	}
	auth.Timestamp = time.Unix(int64(binary.BigEndian.Uint64(auth.raw[6:14])), 0)
	copy(auth.Nonce[:], auth.raw[14:30])
	if _, err := io.ReadFull(r, auth.MAC[:]); err != nil {
		return auth, err
	}
	return auth, nil
}

func (a Auth) Verify(token []byte, now time.Time, tolerance time.Duration) bool {
	if delta := now.Sub(a.Timestamp); delta > tolerance || delta < -tolerance {
		return false
	}
	mac := hmac.New(sha256.New, token)
	_, _ = mac.Write(a.raw[:])
	return hmac.Equal(a.MAC[:], mac.Sum(nil))
}

type OpenRequest struct {
	Command       byte
	AssociationID uint32
	Address       string
}

func WriteOpen(w io.Writer, request OpenRequest) error {
	if request.Command != CommandTCP && request.Command != CommandUDP {
		return errors.New("unsupported ALX command")
	}
	if len(request.Address) > MaxAddressLength {
		return errors.New("destination address is too long")
	}
	header := make([]byte, 4+1+1+4+2)
	copy(header[:4], magic[:])
	header[4] = Version
	header[5] = request.Command
	binary.BigEndian.PutUint32(header[6:10], request.AssociationID)
	binary.BigEndian.PutUint16(header[10:12], uint16(len(request.Address)))
	if err := writeAll(w, header); err != nil {
		return err
	}
	return writeAll(w, []byte(request.Address))
}

func ReadOpen(r io.Reader) (OpenRequest, error) {
	var request OpenRequest
	header := make([]byte, 12)
	if _, err := io.ReadFull(r, header); err != nil {
		return request, err
	}
	if string(header[:4]) != string(magic[:]) || header[4] != Version {
		return request, errors.New("invalid ALX stream preface")
	}
	request.Command = header[5]
	if request.Command != CommandTCP && request.Command != CommandUDP {
		return request, errors.New("unsupported ALX stream command")
	}
	request.AssociationID = binary.BigEndian.Uint32(header[6:10])
	length := int(binary.BigEndian.Uint16(header[10:12]))
	if length > MaxAddressLength || (request.Command == CommandTCP && length == 0) {
		return request, errors.New("invalid ALX destination length")
	}
	address := make([]byte, length)
	if _, err := io.ReadFull(r, address); err != nil {
		return request, err
	}
	request.Address = string(address)
	return request, nil
}

// Datagram is the low-latency path for UDP payloads that fit safely into a
// QUIC datagram. Larger packets use the reliable UDP association stream.
type Datagram struct {
	AssociationID uint32
	Address       string
	Payload       []byte
}

func EncodeDatagram(d Datagram) ([]byte, error) {
	if len(d.Address) == 0 || len(d.Address) > MaxAddressLength {
		return nil, errors.New("invalid datagram address")
	}
	if len(d.Payload) > MaxUDPPayload {
		return nil, errors.New("UDP payload is too large")
	}
	result := make([]byte, 1+4+2+len(d.Address)+len(d.Payload))
	result[0] = Version
	binary.BigEndian.PutUint32(result[1:5], d.AssociationID)
	binary.BigEndian.PutUint16(result[5:7], uint16(len(d.Address)))
	copy(result[7:], d.Address)
	copy(result[7+len(d.Address):], d.Payload)
	return result, nil
}

func DecodeDatagram(raw []byte) (Datagram, error) {
	var d Datagram
	if len(raw) < 8 || raw[0] != Version {
		return d, errors.New("invalid ALX datagram")
	}
	d.AssociationID = binary.BigEndian.Uint32(raw[1:5])
	length := int(binary.BigEndian.Uint16(raw[5:7]))
	if length == 0 || length > MaxAddressLength || 7+length > len(raw) {
		return d, errors.New("invalid ALX datagram address")
	}
	d.Address = string(raw[7 : 7+length])
	d.Payload = append([]byte(nil), raw[7+length:]...)
	return d, nil
}

func WriteUDPFrame(w io.Writer, address string, payload []byte) error {
	if len(address) == 0 || len(address) > MaxAddressLength || len(payload) > MaxUDPPayload {
		return errors.New("invalid ALX UDP frame")
	}
	header := make([]byte, 2+4)
	binary.BigEndian.PutUint16(header[:2], uint16(len(address)))
	binary.BigEndian.PutUint32(header[2:6], uint32(len(payload)))
	if err := writeAll(w, header); err != nil {
		return err
	}
	if err := writeAll(w, []byte(address)); err != nil {
		return err
	}
	return writeAll(w, payload)
}

func ReadUDPFrame(r io.Reader) (string, []byte, error) {
	header := make([]byte, 6)
	if _, err := io.ReadFull(r, header); err != nil {
		return "", nil, err
	}
	addressLength := int(binary.BigEndian.Uint16(header[:2]))
	payloadLength := int(binary.BigEndian.Uint32(header[2:6]))
	if addressLength == 0 || addressLength > MaxAddressLength || payloadLength > MaxUDPPayload {
		return "", nil, errors.New("invalid ALX UDP frame size")
	}
	address := make([]byte, addressLength)
	if _, err := io.ReadFull(r, address); err != nil {
		return "", nil, err
	}
	payload := make([]byte, payloadLength)
	if _, err := io.ReadFull(r, payload); err != nil {
		return "", nil, err
	}
	return string(address), payload, nil
}

func writeAll(w io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := w.Write(data)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		data = data[n:]
	}
	return nil
}
