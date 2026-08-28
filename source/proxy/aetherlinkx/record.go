package aetherlinkx

import (
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"io"
	"math"
	"sync"

	"golang.org/x/crypto/chacha20poly1305"

	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/errors"
)

var (
	clientToServerRecordInfo = []byte("AetherLink X client-to-server record key v1")
	serverToClientRecordInfo = []byte("AetherLink X server-to-client record key v1")
)

type recordReader struct {
	reader  io.Reader
	aead    cipher.AEAD
	counter uint64
	pending []byte
}

type recordWriter struct {
	writer  io.Writer
	aead    cipher.AEAD
	stealth StealthSettings
	counter uint64
	mu      sync.Mutex
}

func newRecordLayer(reader io.Reader, writer io.Writer, session *Session, client bool, stealth StealthSettings) (io.Reader, io.Writer, error) {
	salt := make([]byte, 0, len(session.ClientNonce)+len(session.ServerNonce)+len(session.SessionID))
	salt = append(salt, session.ClientNonce[:]...)
	salt = append(salt, session.ServerNonce[:]...)
	salt = append(salt, session.SessionID[:]...)
	secret := combinedSecret(session.Secret, session.HybridSecret, session.PQ)
	c2sKey, err := deriveKey(secret, salt, clientToServerRecordInfo)
	if err != nil {
		return nil, nil, errors.New("failed to derive AetherLink X uplink record key").Base(err)
	}
	s2cKey, err := deriveKey(secret, salt, serverToClientRecordInfo)
	if err != nil {
		return nil, nil, errors.New("failed to derive AetherLink X downlink record key").Base(err)
	}
	readKey, writeKey := s2cKey, c2sKey
	if !client {
		readKey, writeKey = c2sKey, s2cKey
	}
	readAEAD, err := chacha20poly1305.New(readKey[:])
	if err != nil {
		return nil, nil, err
	}
	writeAEAD, err := chacha20poly1305.New(writeKey[:])
	if err != nil {
		return nil, nil, err
	}
	if stealth.MaxChunkSize == 0 {
		stealth.MaxChunkSize = int(buf.Size) - recordContentHeaderSize
	}
	return &recordReader{reader: reader, aead: readAEAD}, &recordWriter{writer: writer, aead: writeAEAD, stealth: stealth}, nil
}

func recordNonce(counter uint64) [chacha20poly1305.NonceSize]byte {
	var nonce [chacha20poly1305.NonceSize]byte
	binary.BigEndian.PutUint64(nonce[4:], counter)
	return nonce
}

func (r *recordReader) Read(target []byte) (int, error) {
	if len(target) == 0 {
		return 0, nil
	}
	if len(r.pending) == 0 {
		if r.counter == math.MaxUint64 {
			return 0, errors.New("AetherLink X record nonce exhausted")
		}
		var header [2]byte
		if _, err := io.ReadFull(r.reader, header[:]); err != nil {
			return 0, err
		}
		length := int(binary.BigEndian.Uint16(header[:]))
		if length < recordContentHeaderSize || length > int(buf.Size) {
			return 0, errors.New("invalid AetherLink X record length")
		}
		ciphertext := make([]byte, length+r.aead.Overhead())
		if _, err := io.ReadFull(r.reader, ciphertext); err != nil {
			return 0, err
		}
		nonce := recordNonce(r.counter)
		plaintext, err := r.aead.Open(nil, nonce[:], ciphertext, header[:])
		if err != nil {
			return 0, errors.New("invalid AetherLink X encrypted record").Base(err)
		}
		if len(plaintext) < recordContentHeaderSize {
			return 0, errors.New("invalid AetherLink X record content header")
		}
		contentLength := int(binary.BigEndian.Uint16(plaintext[0:2]))
		paddingLength := int(binary.BigEndian.Uint16(plaintext[2:4]))
		if recordContentHeaderSize+contentLength+paddingLength != len(plaintext) || contentLength == 0 {
			return 0, errors.New("invalid AetherLink X record content lengths")
		}
		r.counter++
		r.pending = plaintext[recordContentHeaderSize : recordContentHeaderSize+contentLength]
	}
	written := copy(target, r.pending)
	r.pending = r.pending[written:]
	return written, nil
}

func (w *recordWriter) Write(payload []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	total := 0
	for len(payload) > 0 {
		if w.counter == math.MaxUint64 {
			return total, errors.New("AetherLink X record nonce exhausted")
		}
		contentLength, err := w.stealth.chunkSize(len(payload))
		if err != nil {
			return total, errors.New("failed to select AetherLink X record chunk size").Base(err)
		}
		paddingLength, err := w.stealth.paddingSize()
		if err != nil {
			return total, errors.New("failed to select AetherLink X record padding").Base(err)
		}
		plaintext := make([]byte, recordContentHeaderSize+contentLength+paddingLength)
		binary.BigEndian.PutUint16(plaintext[0:2], uint16(contentLength))
		binary.BigEndian.PutUint16(plaintext[2:4], uint16(paddingLength))
		copy(plaintext[recordContentHeaderSize:], payload[:contentLength])
		if paddingLength > 0 {
			if _, err := io.ReadFull(rand.Reader, plaintext[recordContentHeaderSize+contentLength:]); err != nil {
				return total, errors.New("failed to generate AetherLink X record padding").Base(err)
			}
		}
		var header [2]byte
		binary.BigEndian.PutUint16(header[:], uint16(len(plaintext)))
		nonce := recordNonce(w.counter)
		ciphertext := w.aead.Seal(nil, nonce[:], plaintext, header[:])
		if err := writeAll(w.writer, header[:]); err != nil {
			return total, err
		}
		if err := writeAll(w.writer, ciphertext); err != nil {
			return total, err
		}
		w.counter++
		total += contentLength
		payload = payload[contentLength:]
	}
	return total, nil
}

func writeAll(writer io.Writer, value []byte) error {
	for len(value) > 0 {
		written, err := writer.Write(value)
		if err != nil {
			return err
		}
		if written <= 0 {
			return io.ErrShortWrite
		}
		value = value[written:]
	}
	return nil
}
