package aetherlinkx

import (
	"crypto/rand"
	"encoding/binary"

	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/errors"
)

const recordContentHeaderSize = 4

type StealthSettings struct {
	Enabled                   bool
	MinChunkSize              int
	MaxChunkSize              int
	MaxPaddingBytes           int
	PaddingProbabilityPercent uint32
}

func ParseStealthConfig(config *StealthConfig) (StealthSettings, error) {
	if config == nil || !config.Enabled {
		return StealthSettings{MaxChunkSize: int(buf.Size) - recordContentHeaderSize}, nil
	}
	minChunk := int(config.MinChunkSize)
	maxChunk := int(config.MaxChunkSize)
	maxPadding := int(config.MaxPaddingBytes)
	probability := config.PaddingProbabilityPercent
	if minChunk == 0 {
		minChunk = 256
	}
	if maxChunk == 0 {
		maxChunk = 1200
	}
	if maxPadding == 0 {
		maxPadding = 64
	}
	if probability == 0 {
		probability = 25
	}
	if minChunk < 64 || maxChunk < minChunk || maxChunk > int(buf.Size)-recordContentHeaderSize {
		return StealthSettings{}, errors.New("invalid AetherLink X Stealth chunk range")
	}
	if maxPadding > 4096 || maxChunk+maxPadding+recordContentHeaderSize > int(buf.Size) {
		return StealthSettings{}, errors.New("AetherLink X Stealth padding exceeds record capacity")
	}
	if probability > 100 {
		return StealthSettings{}, errors.New("AetherLink X Stealth padding probability must not exceed 100")
	}
	return StealthSettings{
		Enabled:                   true,
		MinChunkSize:              minChunk,
		MaxChunkSize:              maxChunk,
		MaxPaddingBytes:           maxPadding,
		PaddingProbabilityPercent: probability,
	}, nil
}

func randomBounded(maxInclusive int) (int, error) {
	if maxInclusive <= 0 {
		return 0, nil
	}
	var value [4]byte
	if _, err := rand.Read(value[:]); err != nil {
		return 0, err
	}
	return int(binary.BigEndian.Uint32(value[:]) % uint32(maxInclusive+1)), nil
}

func (s StealthSettings) chunkSize(remaining int) (int, error) {
	if !s.Enabled {
		if remaining > s.MaxChunkSize {
			return s.MaxChunkSize, nil
		}
		return remaining, nil
	}
	span := s.MaxChunkSize - s.MinChunkSize
	offset, err := randomBounded(span)
	if err != nil {
		return 0, err
	}
	size := s.MinChunkSize + offset
	if remaining < size {
		size = remaining
	}
	return size, nil
}

func (s StealthSettings) paddingSize() (int, error) {
	if !s.Enabled || s.MaxPaddingBytes == 0 || s.PaddingProbabilityPercent == 0 {
		return 0, nil
	}
	roll, err := randomBounded(99)
	if err != nil {
		return 0, err
	}
	if uint32(roll) >= s.PaddingProbabilityPercent {
		return 0, nil
	}
	return randomBounded(s.MaxPaddingBytes)
}
