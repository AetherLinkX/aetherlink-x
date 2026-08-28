package aetherlinkx

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"io"
	"math"
	"sync"
	"time"

	"github.com/cloudflare/circl/kem/xwing"
	"golang.org/x/crypto/hkdf"

	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/errors"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
)

const (
	versionMajor byte = 1
	versionMinor byte = 1

	commandTCP byte = 1
	commandUDP byte = 2

	maxHandshakeSize = 4096
	authTagSize      = 32
	turboFlag        = 1 << 0
	pqFlag           = 1 << 1
	innerAEADFlag    = 1 << 2
)

var (
	clientMagic       = []byte{'A', 'L', 'X', '1'}
	serverMagic       = []byte{'A', 'L', 'X', 'R'}
	clientAuthContext = []byte("AetherLink X ClientInit binder v1")
	serverAuthContext = []byte("AetherLink X ServerAccept binder v1")
	clientKeyInfo     = []byte("AetherLink X client auth key v1")
	serverKeyInfo     = []byte("AetherLink X server auth key v1")

	addrParser = protocol.NewAddressParser(
		protocol.PortThenAddress(),
		protocol.AddressFamilyByte(0x01, net.AddressFamilyIPv4),
		protocol.AddressFamilyByte(0x02, net.AddressFamilyDomain),
		protocol.AddressFamilyByte(0x03, net.AddressFamilyIPv6),
	)
)

type Session struct {
	Secret       [secretSize]byte
	ClientNonce  [16]byte
	SessionID    [8]byte
	ServerNonce  [16]byte
	HybridSecret [xwing.SharedKeySize]byte
	PQ           bool
	InnerAEAD    bool
	Turbo        TurboSettings
	LocalEpoch   time.Time
	PeerEpoch    time.Time
}

type Request struct {
	Target  net.Destination
	User    *protocol.MemoryUser
	Session Session
}

func deriveKey(secret []byte, salt []byte, info []byte) ([32]byte, error) {
	var key [32]byte
	if _, err := io.ReadFull(hkdf.New(sha256.New, secret, salt, info), key[:]); err != nil {
		return key, err
	}
	return key, nil
}

func calculateMAC(key [32]byte, context, message []byte) [authTagSize]byte {
	mac := hmac.New(sha256.New, key[:])
	_, _ = mac.Write(context)
	_, _ = mac.Write(message)
	var tag [authTagSize]byte
	copy(tag[:], mac.Sum(nil))
	return tag
}

func writeFrame(writer io.Writer, body []byte) error {
	if len(body) > maxHandshakeSize {
		return errors.New("AetherLink X handshake is too large")
	}
	var length [2]byte
	binary.BigEndian.PutUint16(length[:], uint16(len(body)))
	if err := writeAll(writer, length[:]); err != nil {
		return err
	}
	return writeAll(writer, body)
}

func readFrame(reader io.Reader) ([]byte, error) {
	var length [2]byte
	if _, err := io.ReadFull(reader, length[:]); err != nil {
		return nil, err
	}
	size := int(binary.BigEndian.Uint16(length[:]))
	if size == 0 || size > maxHandshakeSize {
		return nil, errors.New("invalid AetherLink X handshake length")
	}
	body := make([]byte, size)
	if _, err := io.ReadFull(reader, body); err != nil {
		return nil, err
	}
	return body, nil
}

// EncodeRequestHeader writes an authenticated ClientInit and returns the state
// needed to authenticate ServerAccept.
func EncodeRequestHeader(writer io.Writer, target net.Destination, account *MemoryAccount, option ...HandshakeOptions) (*Session, error) {
	if !target.IsValid() || target.Address == nil || target.Address.String() == "" {
		return nil, errors.New("invalid AetherLink X target")
	}
	command := commandTCP
	if target.Network == net.Network_UDP {
		command = commandUDP
	} else if target.Network != net.Network_TCP {
		return nil, errors.New("unsupported AetherLink X network: ", target.Network)
	}
	if command == commandTCP && target.Port == 0 {
		return nil, errors.New("AetherLink X TCP target port must not be zero")
	}

	options := HandshakeOptions{Turbo: TurboSettings{MaxUDPPayload: defaultMaxUDPPayload}}
	if len(option) > 0 {
		options = option[0]
	}
	session := &Session{Secret: account.Secret, Turbo: options.Turbo, InnerAEAD: options.Security.InnerAEAD, LocalEpoch: time.Now()}
	if _, err := io.ReadFull(rand.Reader, session.ClientNonce[:]); err != nil {
		return nil, errors.New("failed to generate client nonce").Base(err)
	}
	if _, err := io.ReadFull(rand.Reader, session.SessionID[:]); err != nil {
		return nil, errors.New("failed to generate session id").Base(err)
	}

	var body bytes.Buffer
	_, _ = body.Write(clientMagic)
	_ = body.WriteByte(versionMajor)
	_ = body.WriteByte(versionMinor)
	_ = body.WriteByte(command)
	flags := byte(0)
	if options.Turbo.Enabled {
		flags |= turboFlag
	}
	var pqCiphertext []byte
	if options.Security.PQMode != PQModeOff {
		if len(options.Security.XWingPublic) != xwing.PublicKeySize {
			return nil, errors.New("invalid AetherLink X X-Wing public key")
		}
		sharedSecret, ciphertext, err := xwing.Encapsulate(options.Security.XWingPublic, nil)
		if err != nil {
			return nil, errors.New("failed to encapsulate AetherLink X X-Wing secret").Base(err)
		}
		copy(session.HybridSecret[:], sharedSecret)
		session.PQ = true
		pqCiphertext = ciphertext
		flags |= pqFlag
	}
	if session.InnerAEAD {
		flags |= innerAEADFlag
	}
	_ = body.WriteByte(flags)
	_, _ = body.Write(account.KeyID[:])
	_, _ = body.Write(session.ClientNonce[:])
	_, _ = body.Write(session.SessionID[:])
	var clientTime [8]byte
	binary.BigEndian.PutUint64(clientTime[:], uint64(session.LocalEpoch.Unix()))
	_, _ = body.Write(clientTime[:])
	var turboParameters [6]byte
	if options.Turbo.Enabled {
		binary.BigEndian.PutUint16(turboParameters[0:2], durationMillis16(options.Turbo.MaxDatagramAge))
		binary.BigEndian.PutUint16(turboParameters[2:4], options.Turbo.DestinationCacheSize)
		binary.BigEndian.PutUint16(turboParameters[4:6], options.Turbo.MaxUDPPayload)
	}
	_, _ = body.Write(turboParameters[:])
	var pqLength [2]byte
	binary.BigEndian.PutUint16(pqLength[:], uint16(len(pqCiphertext)))
	_, _ = body.Write(pqLength[:])
	_, _ = body.Write(pqCiphertext)
	if err := addrParser.WriteAddressPort(&body, target.Address, target.Port); err != nil {
		return nil, errors.New("failed to encode AetherLink X target").Base(err)
	}

	key, err := deriveKey(combinedSecret(account.Secret, session.HybridSecret, session.PQ), session.ClientNonce[:], clientKeyInfo)
	if err != nil {
		return nil, errors.New("failed to derive client authentication key").Base(err)
	}
	tag := calculateMAC(key, clientAuthContext, body.Bytes())
	_, _ = body.Write(tag[:])
	if err := writeFrame(writer, body.Bytes()); err != nil {
		return nil, errors.New("failed to write AetherLink X ClientInit").Base(err)
	}
	return session, nil
}

// DecodeRequestHeader authenticates and parses one ClientInit. No target data
// is returned until its binder has been verified.
func DecodeRequestHeader(reader io.Reader, validator *Validator, option ...HandshakeOptions) (*Request, error) {
	body, err := readFrame(reader)
	if err != nil {
		return nil, errors.New("failed to read AetherLink X ClientInit").Base(err)
	}
	const fixedPrefixSize = 64
	if len(body) < fixedPrefixSize+authTagSize+3 {
		return nil, errors.New("AetherLink X ClientInit is too short")
	}
	if !bytes.Equal(body[:4], clientMagic) || body[4] != versionMajor {
		return nil, errors.New("unsupported AetherLink X version")
	}
	if body[5] != versionMinor || body[7]&^byte(turboFlag|pqFlag|innerAEADFlag) != 0 {
		return nil, errors.New("unsupported AetherLink X ClientInit flags")
	}
	pqCiphertextSize := int(binary.BigEndian.Uint16(body[62:64]))
	if len(body) < fixedPrefixSize+pqCiphertextSize+authTagSize+3 {
		return nil, errors.New("invalid AetherLink X ClientInit extension length")
	}
	options := HandshakeOptions{Turbo: TurboSettings{MaxUDPPayload: defaultMaxUDPPayload}}
	if len(option) > 0 {
		options = option[0]
	}

	var keyID, clientNonce [16]byte
	copy(keyID[:], body[8:24])
	copy(clientNonce[:], body[24:40])
	user := validator.Get(keyID)
	if user == nil {
		return nil, errors.New("unknown AetherLink X user")
	}
	account := user.Account.(*MemoryAccount)
	var hybridSecret [xwing.SharedKeySize]byte
	hybrid := body[7]&pqFlag != 0
	if hybrid {
		if pqCiphertextSize != xwing.CiphertextSize || len(options.Security.XWingPrivate) != xwing.PrivateKeySize || options.Security.PQMode == PQModeOff {
			return nil, errors.New("unsupported AetherLink X X-Wing ClientInit")
		}
		sharedSecret := xwing.Decapsulate(body[fixedPrefixSize:fixedPrefixSize+pqCiphertextSize], options.Security.XWingPrivate)
		copy(hybridSecret[:], sharedSecret)
	} else if pqCiphertextSize != 0 {
		return nil, errors.New("unexpected AetherLink X X-Wing ciphertext")
	}
	message := body[:len(body)-authTagSize]
	receivedTag := body[len(body)-authTagSize:]
	key, err := deriveKey(combinedSecret(account.Secret, hybridSecret, hybrid), clientNonce[:], clientKeyInfo)
	if err != nil {
		return nil, errors.New("failed to derive client authentication key").Base(err)
	}
	expectedTag := calculateMAC(key, clientAuthContext, message)
	if subtle.ConstantTimeCompare(receivedTag, expectedTag[:]) != 1 {
		return nil, errors.New("invalid AetherLink X ClientInit binder")
	}
	now := time.Now()
	clientUnix := binary.BigEndian.Uint64(body[48:56])
	if clientUnix > math.MaxInt64 {
		return nil, errors.New("invalid AetherLink X client time")
	}
	clientTime := time.Unix(int64(clientUnix), 0)
	if clientTime.Before(now.Add(-replayTTL)) || clientTime.After(now.Add(replayTTL)) {
		return nil, errors.New("AetherLink X ClientInit is outside the accepted clock window")
	}
	if options.Security.PQMode == PQModeRequired && !hybrid {
		return nil, errors.New("AetherLink X server requires X-Wing")
	}
	requestedInnerAEAD := body[7]&innerAEADFlag != 0
	if requestedInnerAEAD != options.Security.InnerAEAD {
		return nil, errors.New("AetherLink X inner AEAD policy mismatch")
	}

	var sessionID [8]byte
	copy(sessionID[:], body[40:48])
	requestedTurbo := TurboSettings{MaxUDPPayload: defaultMaxUDPPayload}
	if body[7]&turboFlag != 0 {
		requestedTurbo = TurboSettings{
			Enabled:              true,
			MaxDatagramAge:       time.Duration(binary.BigEndian.Uint16(body[56:58])) * time.Millisecond,
			DestinationCacheSize: binary.BigEndian.Uint16(body[58:60]),
			MaxUDPPayload:        binary.BigEndian.Uint16(body[60:62]),
		}
		if requestedTurbo.MaxUDPPayload < 512 || requestedTurbo.MaxUDPPayload > uint16(buf.Size) ||
			requestedTurbo.DestinationCacheSize > maxDestinationCache {
			return nil, errors.New("invalid AetherLink X Turbo parameters")
		}
	}
	availableTurbo := options.Turbo
	if requestedTurbo.Enabled && !availableTurbo.Enabled {
		return nil, errors.New("AetherLink X Turbo is not available on the server")
	}
	selectedTurbo := negotiateTurbo(requestedTurbo, availableTurbo)
	addressReader := bytes.NewReader(message[fixedPrefixSize+pqCiphertextSize:])
	address, port, err := addrParser.ReadAddressPort(nil, addressReader)
	if err != nil || addressReader.Len() != 0 {
		return nil, errors.New("invalid AetherLink X target address").Base(err)
	}

	network := net.Network_TCP
	switch body[6] {
	case commandTCP:
	case commandUDP:
		network = net.Network_UDP
	default:
		return nil, errors.New("unsupported AetherLink X command")
	}
	if address == nil || address.String() == "" || (network == net.Network_TCP && port == 0) {
		return nil, errors.New("invalid AetherLink X target")
	}
	if !validator.CheckAndMarkReplay(keyID, clientNonce, now) {
		return nil, errors.New("replayed or saturated AetherLink X ClientInit")
	}
	return &Request{
		Target: net.Destination{Network: network, Address: address, Port: port},
		User:   user,
		Session: Session{
			Secret:       account.Secret,
			ClientNonce:  clientNonce,
			SessionID:    sessionID,
			HybridSecret: hybridSecret,
			PQ:           hybrid,
			InnerAEAD:    requestedInnerAEAD,
			Turbo:        selectedTurbo,
			PeerEpoch:    now,
		},
	}, nil
}

func EncodeResponseHeader(writer io.Writer, session *Session, status byte) error {
	var serverNonce [16]byte
	if _, err := io.ReadFull(rand.Reader, serverNonce[:]); err != nil {
		return errors.New("failed to generate server nonce").Base(err)
	}
	var body bytes.Buffer
	_, _ = body.Write(serverMagic)
	_ = body.WriteByte(versionMajor)
	_ = body.WriteByte(versionMinor)
	_ = body.WriteByte(status)
	flags := byte(0)
	if session.Turbo.Enabled {
		flags |= turboFlag
	}
	if session.PQ {
		flags |= pqFlag
	}
	if session.InnerAEAD {
		flags |= innerAEADFlag
	}
	_ = body.WriteByte(flags)
	_, _ = body.Write(serverNonce[:])
	copy(session.ServerNonce[:], serverNonce[:])
	_, _ = body.Write(session.SessionID[:])
	var turboParameters [6]byte
	if session.Turbo.Enabled {
		binary.BigEndian.PutUint16(turboParameters[0:2], durationMillis16(session.Turbo.MaxDatagramAge))
		binary.BigEndian.PutUint16(turboParameters[2:4], session.Turbo.DestinationCacheSize)
		binary.BigEndian.PutUint16(turboParameters[4:6], session.Turbo.MaxUDPPayload)
	}
	_, _ = body.Write(turboParameters[:])
	session.LocalEpoch = time.Now()

	salt := make([]byte, 0, len(session.ClientNonce)+len(serverNonce))
	salt = append(salt, session.ClientNonce[:]...)
	salt = append(salt, serverNonce[:]...)
	key, err := deriveKey(combinedSecret(session.Secret, session.HybridSecret, session.PQ), salt, serverKeyInfo)
	if err != nil {
		return errors.New("failed to derive server authentication key").Base(err)
	}
	tag := calculateMAC(key, serverAuthContext, body.Bytes())
	_, _ = body.Write(tag[:])
	return writeFrame(writer, body.Bytes())
}

func DecodeResponseHeader(reader io.Reader, session *Session) error {
	body, err := readFrame(reader)
	if err != nil {
		return errors.New("failed to read AetherLink X ServerAccept").Base(err)
	}
	const responseMessageSize = 38
	if len(body) != responseMessageSize+authTagSize || !bytes.Equal(body[:4], serverMagic) {
		return errors.New("invalid AetherLink X ServerAccept")
	}
	if body[4] != versionMajor || body[5] != versionMinor || body[7]&^byte(turboFlag|pqFlag|innerAEADFlag) != 0 {
		return errors.New("unsupported AetherLink X ServerAccept version or flags")
	}
	if subtle.ConstantTimeCompare(body[24:32], session.SessionID[:]) != 1 {
		return errors.New("AetherLink X session id mismatch")
	}
	salt := make([]byte, 0, len(session.ClientNonce)+16)
	salt = append(salt, session.ClientNonce[:]...)
	salt = append(salt, body[8:24]...)
	key, err := deriveKey(combinedSecret(session.Secret, session.HybridSecret, session.PQ), salt, serverKeyInfo)
	if err != nil {
		return errors.New("failed to derive server authentication key").Base(err)
	}
	expectedTag := calculateMAC(key, serverAuthContext, body[:responseMessageSize])
	if subtle.ConstantTimeCompare(body[responseMessageSize:], expectedTag[:]) != 1 {
		return errors.New("invalid AetherLink X ServerAccept binder")
	}
	if body[6] != 0 {
		return errors.New("AetherLink X server rejected the request with status ", body[6])
	}
	if session.PQ != (body[7]&pqFlag != 0) {
		return errors.New("AetherLink X X-Wing negotiation downgrade or mismatch")
	}
	if session.InnerAEAD != (body[7]&innerAEADFlag != 0) {
		return errors.New("AetherLink X inner AEAD negotiation downgrade or mismatch")
	}
	serverTurbo := body[7]&turboFlag != 0
	if session.Turbo.Enabled != serverTurbo {
		return errors.New("AetherLink X Turbo negotiation downgrade or mismatch")
	}
	if serverTurbo {
		selected := TurboSettings{
			Enabled:              true,
			MaxDatagramAge:       time.Duration(binary.BigEndian.Uint16(body[32:34])) * time.Millisecond,
			DestinationCacheSize: binary.BigEndian.Uint16(body[34:36]),
			MaxUDPPayload:        binary.BigEndian.Uint16(body[36:38]),
		}
		if selected.MaxUDPPayload < 512 || selected.MaxUDPPayload > session.Turbo.MaxUDPPayload ||
			selected.DestinationCacheSize > session.Turbo.DestinationCacheSize || selected.DestinationCacheSize > maxDestinationCache {
			return errors.New("invalid AetherLink X Turbo selection")
		}
		session.Turbo = selected
	}
	session.PeerEpoch = time.Now()
	copy(session.ServerNonce[:], body[8:24])
	return nil
}

// PacketWriter frames each UDP datagram with its own destination.
type PacketWriter struct {
	Writer io.Writer
	Target net.Destination
	Turbo  TurboSettings
	Epoch  time.Time
	mu     sync.Mutex
	cache  *destinationCache
	seq    uint32
}

func (w *PacketWriter) WriteMultiBuffer(mb buf.MultiBuffer) error {
	defer buf.ReleaseMulti(mb)
	w.mu.Lock()
	defer w.mu.Unlock()
	for _, payload := range mb {
		if payload == nil {
			continue
		}
		target := w.Target
		if payload.UDP != nil {
			target = *payload.UDP
		}
		if target.Address == nil || target.Address.String() == "" || target.Port == 0 {
			return errors.New("invalid AetherLink X UDP destination")
		}
		maxPayload := int32(w.Turbo.MaxUDPPayload)
		if maxPayload == 0 {
			maxPayload = defaultMaxUDPPayload
		}
		if payload.Len() > maxPayload {
			return errors.New("AetherLink X UDP payload is too large")
		}
		if w.Turbo.Enabled {
			if err := w.writeTurboPacket(payload.Bytes(), target); err != nil {
				return err
			}
			continue
		}
		var frame bytes.Buffer
		if err := addrParser.WriteAddressPort(&frame, target.Address, target.Port); err != nil {
			return err
		}
		var length [2]byte
		binary.BigEndian.PutUint16(length[:], uint16(payload.Len()))
		_, _ = frame.Write(length[:])
		_, _ = frame.Write(payload.Bytes())
		if err := writeAll(w.Writer, frame.Bytes()); err != nil {
			return err
		}
	}
	return nil
}

func (w *PacketWriter) writeTurboPacket(payload []byte, target net.Destination) error {
	if w.cache == nil {
		w.cache = newDestinationCache(w.Turbo.DestinationCacheSize)
	}
	if w.Epoch.IsZero() {
		w.Epoch = time.Now()
	}
	id, cached := w.cache.lookup(target)
	if !cached {
		id = w.cache.reserve(target)
	}
	var frame bytes.Buffer
	_ = frame.WriteByte(0xa1)
	flags := byte(0)
	if cached {
		flags |= 1
	}
	_ = frame.WriteByte(flags)
	w.seq++
	var number [4]byte
	binary.BigEndian.PutUint32(number[:], w.seq)
	_, _ = frame.Write(number[:])
	delta := time.Since(w.Epoch).Milliseconds()
	if delta < 0 {
		delta = 0
	}
	if delta > math.MaxUint32 {
		delta = math.MaxUint32
	}
	binary.BigEndian.PutUint32(number[:], uint32(delta))
	_, _ = frame.Write(number[:])
	var short [2]byte
	binary.BigEndian.PutUint16(short[:], id)
	_, _ = frame.Write(short[:])
	if !cached {
		if err := addrParser.WriteAddressPort(&frame, target.Address, target.Port); err != nil {
			return err
		}
	}
	binary.BigEndian.PutUint16(short[:], uint16(len(payload)))
	_, _ = frame.Write(short[:])
	_, _ = frame.Write(payload)
	return writeAll(w.Writer, frame.Bytes())
}

// PacketReader restores exactly one UDP datagram per ReadMultiBuffer call.
type PacketReader struct {
	Reader io.Reader
	Turbo  TurboSettings
	Epoch  time.Time
	cache  *destinationCache
	seq    uint32
}

func (r *PacketReader) ReadMultiBuffer() (buf.MultiBuffer, error) {
	if r.Turbo.Enabled {
		return r.readTurboPacket()
	}
	address, port, err := addrParser.ReadAddressPort(nil, r.Reader)
	if err != nil {
		return nil, errors.New("failed to read AetherLink X UDP destination").Base(err)
	}
	var length [2]byte
	if _, err := io.ReadFull(r.Reader, length[:]); err != nil {
		return nil, errors.New("failed to read AetherLink X UDP length").Base(err)
	}
	size := int(binary.BigEndian.Uint16(length[:]))
	if address == nil || address.String() == "" || port == 0 {
		return nil, errors.New("invalid AetherLink X UDP destination")
	}
	maxPayload := int(r.Turbo.MaxUDPPayload)
	if maxPayload == 0 {
		maxPayload = defaultMaxUDPPayload
	}
	if size > maxPayload {
		return nil, errors.New("AetherLink X UDP payload is too large")
	}
	payload := buf.New()
	target := net.UDPDestination(address, port)
	payload.UDP = &target
	if _, err := payload.ReadFullFrom(r.Reader, int32(size)); err != nil {
		payload.Release()
		return nil, errors.New("failed to read AetherLink X UDP payload").Base(err)
	}
	return buf.MultiBuffer{payload}, nil
}

func (r *PacketReader) readTurboPacket() (buf.MultiBuffer, error) {
	if r.cache == nil {
		r.cache = newDestinationCache(r.Turbo.DestinationCacheSize)
	}
	if r.Epoch.IsZero() {
		r.Epoch = time.Now()
	}
	for {
		var fixed [12]byte
		if _, err := io.ReadFull(r.Reader, fixed[:]); err != nil {
			return nil, errors.New("failed to read AetherLink X Turbo UDP header").Base(err)
		}
		if fixed[0] != 0xa1 || fixed[1]&^byte(1) != 0 {
			return nil, errors.New("invalid AetherLink X Turbo UDP header")
		}
		sequence := binary.BigEndian.Uint32(fixed[2:6])
		if sequence != r.seq+1 {
			return nil, errors.New("invalid AetherLink X Turbo UDP sequence")
		}
		r.seq = sequence
		delta := time.Duration(binary.BigEndian.Uint32(fixed[6:10])) * time.Millisecond
		cacheID := binary.BigEndian.Uint16(fixed[10:12])

		var destination net.Destination
		var found bool
		if fixed[1]&1 != 0 {
			destination, found = r.cache.get(cacheID)
			if !found {
				return nil, errors.New("unknown AetherLink X Turbo destination cache id")
			}
		} else {
			address, port, err := addrParser.ReadAddressPort(nil, r.Reader)
			if err != nil || address == nil || address.String() == "" || port == 0 {
				return nil, errors.New("invalid AetherLink X Turbo UDP destination").Base(err)
			}
			destination = net.UDPDestination(address, port)
			if cacheID != 0 && !r.cache.store(cacheID, destination) {
				return nil, errors.New("invalid AetherLink X Turbo destination cache id")
			}
		}

		var length [2]byte
		if _, err := io.ReadFull(r.Reader, length[:]); err != nil {
			return nil, errors.New("failed to read AetherLink X Turbo UDP length").Base(err)
		}
		size := int(binary.BigEndian.Uint16(length[:]))
		if size > int(r.Turbo.MaxUDPPayload) {
			return nil, errors.New("AetherLink X Turbo UDP payload is too large")
		}
		payload := buf.New()
		payload.UDP = &destination
		if _, err := payload.ReadFullFrom(r.Reader, int32(size)); err != nil {
			payload.Release()
			return nil, errors.New("failed to read AetherLink X Turbo UDP payload").Base(err)
		}
		extraDelay := time.Since(r.Epoch) - delta
		if r.Turbo.MaxDatagramAge > 0 && extraDelay > r.Turbo.MaxDatagramAge {
			payload.Release()
			continue
		}
		return buf.MultiBuffer{payload}, nil
	}
}
