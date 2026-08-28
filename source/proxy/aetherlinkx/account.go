package aetherlinkx

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"strings"

	"google.golang.org/protobuf/proto"

	"github.com/xtls/xray-core/common/errors"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/uuid"
)

const secretSize = 32

var keyIDContext = []byte("AetherLink X key-id v1")

// MemoryAccount is the validated in-memory representation of an ALX account.
type MemoryAccount struct {
	ID     *protocol.ID
	Secret [secretSize]byte
	KeyID  [16]byte
}

// AsAccount implements protocol.AsAccount.
func (a *Account) AsAccount() (protocol.Account, error) {
	if len(a.GetId()) != 36 {
		return nil, errors.New("AetherLink X id must be a canonical UUID")
	}
	id, err := uuid.ParseString(a.GetId())
	if err != nil {
		return nil, errors.New("failed to parse AetherLink X id").Base(err)
	}

	secretText := strings.TrimSpace(a.GetSecret())
	secret, err := base64.RawURLEncoding.DecodeString(secretText)
	if err != nil {
		secret, err = base64.URLEncoding.DecodeString(secretText)
	}
	if err != nil || len(secret) != secretSize {
		return nil, errors.New("AetherLink X secret must be exactly 32 bytes encoded as Base64URL")
	}
	var nonZero byte
	for _, value := range secret {
		nonZero |= value
	}
	if nonZero == 0 {
		return nil, errors.New("AetherLink X secret must not be all zeroes")
	}

	memory := &MemoryAccount{ID: protocol.NewID(id)}
	copy(memory.Secret[:], secret)
	mac := hmac.New(sha256.New, memory.Secret[:])
	_, _ = mac.Write(keyIDContext)
	_, _ = mac.Write(memory.ID.Bytes())
	copy(memory.KeyID[:], mac.Sum(nil)[:len(memory.KeyID)])
	return memory, nil
}

// Equals implements protocol.Account.
func (a *MemoryAccount) Equals(other protocol.Account) bool {
	b, ok := other.(*MemoryAccount)
	if !ok || !a.ID.Equals(b.ID) {
		return false
	}
	return subtle.ConstantTimeCompare(a.Secret[:], b.Secret[:]) == 1
}

// ToProto implements protocol.Account.
func (a *MemoryAccount) ToProto() proto.Message {
	return &Account{
		Id:     a.ID.String(),
		Secret: base64.RawURLEncoding.EncodeToString(a.Secret[:]),
	}
}
