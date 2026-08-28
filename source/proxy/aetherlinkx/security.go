package aetherlinkx

import (
	"encoding/base64"
	"strings"

	"github.com/cloudflare/circl/kem/xwing"
	"github.com/xtls/xray-core/common/errors"
)

type PQMode byte

const (
	PQModeOff PQMode = iota
	PQModePrefer
	PQModeRequired
)

type SecuritySettings struct {
	PQMode       PQMode
	XWingPublic  []byte
	XWingPrivate []byte
	InnerAEAD    bool
}

type HandshakeOptions struct {
	Turbo    TurboSettings
	Security SecuritySettings
}

func parsePQMode(value string) (PQMode, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "", "off":
		return PQModeOff, nil
	case "prefer":
		return PQModePrefer, nil
	case "required":
		return PQModeRequired, nil
	default:
		return PQModeOff, errors.New("unknown AetherLink X pqMode: ", value)
	}
}

func decodeBase64URL(value string, expected int, field string) ([]byte, error) {
	if value == "" {
		return nil, nil
	}
	decoded, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(value))
	if err != nil {
		decoded, err = base64.URLEncoding.DecodeString(strings.TrimSpace(value))
	}
	if err != nil || len(decoded) != expected {
		return nil, errors.New("AetherLink X ", field, " must contain exactly ", expected, " bytes as Base64URL")
	}
	return decoded, nil
}

func ParseClientSecurity(config *SecurityConfig) (SecuritySettings, error) {
	if config == nil {
		return SecuritySettings{}, nil
	}
	mode, err := parsePQMode(config.PqMode)
	if err != nil {
		return SecuritySettings{}, err
	}
	if config.XwingPrivateKey != "" {
		return SecuritySettings{}, errors.New("AetherLink X client config must not contain xwingPrivateKey")
	}
	publicKey, err := decodeBase64URL(config.XwingPublicKey, xwing.PublicKeySize, "xwingPublicKey")
	if err != nil {
		return SecuritySettings{}, err
	}
	if mode == PQModeOff && len(publicKey) != 0 {
		return SecuritySettings{}, errors.New("AetherLink X xwingPublicKey requires pqMode prefer or required")
	}
	if len(publicKey) != 0 {
		var parsed xwing.PublicKey
		if err := parsed.Unpack(publicKey); err != nil {
			return SecuritySettings{}, errors.New("invalid AetherLink X X-Wing public key").Base(err)
		}
	}
	if mode == PQModeRequired && len(publicKey) == 0 {
		return SecuritySettings{}, errors.New("AetherLink X pqMode required needs xwingPublicKey")
	}
	if mode == PQModePrefer && len(publicKey) == 0 {
		mode = PQModeOff
	}
	return SecuritySettings{PQMode: mode, XWingPublic: publicKey, InnerAEAD: config.InnerAead}, nil
}

func ParseServerSecurity(config *SecurityConfig) (SecuritySettings, error) {
	if config == nil {
		return SecuritySettings{}, nil
	}
	mode, err := parsePQMode(config.PqMode)
	if err != nil {
		return SecuritySettings{}, err
	}
	if config.XwingPublicKey != "" {
		return SecuritySettings{}, errors.New("AetherLink X server config must not contain xwingPublicKey")
	}
	privateKey, err := decodeBase64URL(config.XwingPrivateKey, xwing.PrivateKeySize, "xwingPrivateKey")
	if err != nil {
		return SecuritySettings{}, err
	}
	if mode == PQModeOff && len(privateKey) != 0 {
		return SecuritySettings{}, errors.New("AetherLink X xwingPrivateKey requires pqMode prefer or required")
	}
	if len(privateKey) != 0 && isAllZero(privateKey) {
		return SecuritySettings{}, errors.New("AetherLink X xwingPrivateKey must not be all zero")
	}
	if mode == PQModeRequired && len(privateKey) == 0 {
		return SecuritySettings{}, errors.New("AetherLink X pqMode required needs xwingPrivateKey")
	}
	if mode == PQModePrefer && len(privateKey) == 0 {
		mode = PQModeOff
	}
	return SecuritySettings{PQMode: mode, XWingPrivate: privateKey, InnerAEAD: config.InnerAead}, nil
}

func isAllZero(value []byte) bool {
	var combined byte
	for _, item := range value {
		combined |= item
	}
	return combined == 0
}

func combinedSecret(accountSecret [secretSize]byte, hybridSecret [xwing.SharedKeySize]byte, hybrid bool) []byte {
	combined := make([]byte, 0, secretSize+xwing.SharedKeySize)
	combined = append(combined, accountSecret[:]...)
	if hybrid {
		combined = append(combined, hybridSecret[:]...)
	}
	return combined
}
