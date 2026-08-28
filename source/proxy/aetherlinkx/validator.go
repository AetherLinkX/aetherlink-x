package aetherlinkx

import (
	"strings"
	"sync"
	"time"

	"github.com/xtls/xray-core/common/errors"
	"github.com/xtls/xray-core/common/protocol"
)

const (
	replayTTL        = 2 * time.Minute
	maxReplayEntries = 65536
)

// Validator stores ALX users and the bounded replay window for authenticated
// ClientInit messages.
type Validator struct {
	email sync.Map
	users sync.Map

	replayMu     sync.Mutex
	replays      map[[32]byte]time.Time
	lastReplayGC time.Time
}

func (v *Validator) Add(user *protocol.MemoryUser) error {
	account, ok := user.Account.(*MemoryAccount)
	if !ok {
		return errors.New("invalid AetherLink X account type")
	}

	email := strings.ToLower(user.Email)
	if email != "" {
		if _, loaded := v.email.LoadOrStore(email, user); loaded {
			return errors.New("user ", user.Email, " already exists")
		}
	}
	if _, loaded := v.users.LoadOrStore(account.KeyID, user); loaded {
		if email != "" {
			v.email.Delete(email)
		}
		return errors.New("AetherLink X key id already exists")
	}
	return nil
}

func (v *Validator) Del(email string) error {
	if email == "" {
		return errors.New("email must not be empty")
	}
	key := strings.ToLower(email)
	value, ok := v.email.Load(key)
	if !ok {
		return errors.New("user ", email, " not found")
	}
	user := value.(*protocol.MemoryUser)
	v.email.Delete(key)
	v.users.Delete(user.Account.(*MemoryAccount).KeyID)
	return nil
}

func (v *Validator) Get(keyID [16]byte) *protocol.MemoryUser {
	value, ok := v.users.Load(keyID)
	if !ok {
		return nil
	}
	return value.(*protocol.MemoryUser)
}

func (v *Validator) GetByEmail(email string) *protocol.MemoryUser {
	value, ok := v.email.Load(strings.ToLower(email))
	if !ok {
		return nil
	}
	return value.(*protocol.MemoryUser)
}

func (v *Validator) GetAll() []*protocol.MemoryUser {
	users := make([]*protocol.MemoryUser, 0)
	v.users.Range(func(_, value any) bool {
		users = append(users, value.(*protocol.MemoryUser))
		return true
	})
	return users
}

func (v *Validator) GetCount() int64 {
	var count int64
	v.users.Range(func(_, _ any) bool {
		count++
		return true
	})
	return count
}

// CheckAndMarkReplay returns false when the same authenticated key-id/nonce
// tuple is still inside the replay window or the cache is saturated.
func (v *Validator) CheckAndMarkReplay(keyID, nonce [16]byte, now time.Time) bool {
	var replayKey [32]byte
	copy(replayKey[:16], keyID[:])
	copy(replayKey[16:], nonce[:])

	v.replayMu.Lock()
	defer v.replayMu.Unlock()
	if v.replays == nil {
		v.replays = make(map[[32]byte]time.Time)
	}
	if expiry, found := v.replays[replayKey]; found && now.Before(expiry) {
		return false
	}
	if len(v.replays) >= maxReplayEntries || now.Sub(v.lastReplayGC) >= replayTTL {
		for key, expiry := range v.replays {
			if !now.Before(expiry) {
				delete(v.replays, key)
			}
		}
		v.lastReplayGC = now
	}
	if len(v.replays) >= maxReplayEntries {
		return false
	}
	v.replays[replayKey] = now.Add(replayTTL)
	return true
}
