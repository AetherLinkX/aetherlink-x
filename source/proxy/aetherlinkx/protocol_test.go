package aetherlinkx

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/cloudflare/circl/kem/xwing"
	"github.com/google/go-cmp/cmp"
	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
)

type shortWriter struct {
	writer io.Writer
	limit  int
}

func (w shortWriter) Write(value []byte) (int, error) {
	if len(value) > w.limit {
		value = value[:w.limit]
	}
	return w.writer.Write(value)
}

type zeroWriter struct{}

func (zeroWriter) Write([]byte) (int, error) { return 0, nil }

func testAccount(t testing.TB) *MemoryAccount {
	t.Helper()
	secret := make([]byte, secretSize)
	for i := range secret {
		secret[i] = byte(i + 1)
	}
	account, err := (&Account{
		Id:     "66ad4540-b58c-4ad2-9926-ea63445a9b57",
		Secret: base64.RawURLEncoding.EncodeToString(secret),
	}).AsAccount()
	if err != nil {
		t.Fatal(err)
	}
	return account.(*MemoryAccount)
}

func testValidator(t testing.TB, account *MemoryAccount) *Validator {
	t.Helper()
	validator := new(Validator)
	if err := validator.Add(&protocol.MemoryUser{Email: "alx@example.com", Account: account}); err != nil {
		t.Fatal(err)
	}
	return validator
}

func TestAuthenticatedHandshakeRoundTrip(t *testing.T) {
	account := testAccount(t)
	target := net.TCPDestination(net.DomainAddress("example.com"), 443)
	var wire bytes.Buffer
	clientSession, err := EncodeRequestHeader(&wire, target, account)
	if err != nil {
		t.Fatal(err)
	}
	request, err := DecodeRequestHeader(&wire, testValidator(t, account))
	if err != nil {
		t.Fatal(err)
	}
	if diff := cmp.Diff(target, request.Target); diff != "" {
		t.Fatalf("target mismatch (-want +got):\n%s", diff)
	}
	if err := EncodeResponseHeader(&wire, &request.Session, 0); err != nil {
		t.Fatal(err)
	}
	if err := DecodeResponseHeader(&wire, clientSession); err != nil {
		t.Fatal(err)
	}
}

func TestFramingHandlesShortWrites(t *testing.T) {
	account := testAccount(t)
	var wire bytes.Buffer
	if _, err := EncodeRequestHeader(shortWriter{writer: &wire, limit: 3}, net.TCPDestination(net.LocalHostIP, 443), account); err != nil {
		t.Fatal(err)
	}
	if _, err := DecodeRequestHeader(&wire, testValidator(t, account)); err != nil {
		t.Fatal(err)
	}
	if err := writeFrame(zeroWriter{}, []byte("value")); !errors.Is(err, io.ErrShortWrite) {
		t.Fatalf("writeFrame returned %v, want io.ErrShortWrite", err)
	}
}

func TestSecurityConfigurationRejectsWrongSideAndWeakKeys(t *testing.T) {
	privateKey, publicKey, err := xwing.GenerateKeyPairPacked(nil)
	if err != nil {
		t.Fatal(err)
	}
	encodedPublic := base64.RawURLEncoding.EncodeToString(publicKey)
	encodedPrivate := base64.RawURLEncoding.EncodeToString(privateKey)
	if _, err := ParseClientSecurity(&SecurityConfig{PqMode: "required", XwingPrivateKey: encodedPrivate}); err == nil {
		t.Fatal("client accepted a server private key")
	}
	if _, err := ParseServerSecurity(&SecurityConfig{PqMode: "required", XwingPublicKey: encodedPublic}); err == nil {
		t.Fatal("server accepted a client public key field")
	}
	if _, err := ParseClientSecurity(&SecurityConfig{PqMode: "off", XwingPublicKey: encodedPublic}); err == nil {
		t.Fatal("client accepted a key while PQ was disabled")
	}
	zeroKey := base64.RawURLEncoding.EncodeToString(make([]byte, xwing.PrivateKeySize))
	if _, err := ParseServerSecurity(&SecurityConfig{PqMode: "required", XwingPrivateKey: zeroKey}); err == nil {
		t.Fatal("server accepted an all-zero private key")
	}
}

func TestTurboNegotiation(t *testing.T) {
	account := testAccount(t)
	requested := TurboSettings{
		Enabled:              true,
		MaxDatagramAge:       35 * time.Millisecond,
		DestinationCacheSize: 64,
		MaxUDPPayload:        8192,
	}
	available := TurboSettings{
		Enabled:              true,
		MaxDatagramAge:       50 * time.Millisecond,
		DestinationCacheSize: 32,
		MaxUDPPayload:        4096,
	}
	var wire bytes.Buffer
	clientSession, err := EncodeRequestHeader(&wire, net.UDPDestination(net.LocalHostIP, 53), account, HandshakeOptions{Turbo: requested})
	if err != nil {
		t.Fatal(err)
	}
	request, err := DecodeRequestHeader(&wire, testValidator(t, account), HandshakeOptions{Turbo: available})
	if err != nil {
		t.Fatal(err)
	}
	if !request.Session.Turbo.Enabled || request.Session.Turbo.DestinationCacheSize != 32 || request.Session.Turbo.MaxUDPPayload != 4096 {
		t.Fatalf("unexpected Turbo selection: %+v", request.Session.Turbo)
	}
	if err := EncodeResponseHeader(&wire, &request.Session, 0); err != nil {
		t.Fatal(err)
	}
	if err := DecodeResponseHeader(&wire, clientSession); err != nil {
		t.Fatal(err)
	}
	if diff := cmp.Diff(request.Session.Turbo, clientSession.Turbo); diff != "" {
		t.Fatalf("Turbo selection mismatch (-server +client):\n%s", diff)
	}
}

func TestTurboDowngradeIsRejected(t *testing.T) {
	account := testAccount(t)
	requested := TurboSettings{Enabled: true, MaxDatagramAge: 35 * time.Millisecond, DestinationCacheSize: 8, MaxUDPPayload: 1024}
	var wire bytes.Buffer
	if _, err := EncodeRequestHeader(&wire, net.UDPDestination(net.LocalHostIP, 53), account, HandshakeOptions{Turbo: requested}); err != nil {
		t.Fatal(err)
	}
	if _, err := DecodeRequestHeader(&wire, testValidator(t, account)); err == nil {
		t.Fatal("server accepted a Turbo request while Turbo was unavailable")
	}
}

func TestInnerAEADPolicyMismatchIsRejectedBeforeReplayMark(t *testing.T) {
	account := testAccount(t)
	validator := testValidator(t, account)
	var wire bytes.Buffer
	if _, err := EncodeRequestHeader(&wire, net.TCPDestination(net.LocalHostIP, 443), account, HandshakeOptions{
		Security: SecuritySettings{InnerAEAD: true},
	}); err != nil {
		t.Fatal(err)
	}
	encoded := append([]byte(nil), wire.Bytes()...)
	if _, err := DecodeRequestHeader(bytes.NewReader(encoded), validator); err == nil {
		t.Fatal("server accepted an inner AEAD policy mismatch")
	}
	if _, err := DecodeRequestHeader(bytes.NewReader(encoded), validator, HandshakeOptions{
		Security: SecuritySettings{InnerAEAD: true},
	}); err != nil {
		t.Fatalf("policy rejection polluted the replay cache: %v", err)
	}
}

func TestXWingAndInnerAEADRoundTrip(t *testing.T) {
	privateKey, publicKey, err := xwing.GenerateKeyPairPacked(nil)
	if err != nil {
		t.Fatal(err)
	}
	clientSecurity := SecuritySettings{PQMode: PQModeRequired, XWingPublic: publicKey, InnerAEAD: true}
	serverSecurity := SecuritySettings{PQMode: PQModeRequired, XWingPrivate: privateKey, InnerAEAD: true}
	account := testAccount(t)
	var handshake bytes.Buffer
	clientSession, err := EncodeRequestHeader(&handshake, net.TCPDestination(net.LocalHostIP, 443), account, HandshakeOptions{Security: clientSecurity})
	if err != nil {
		t.Fatal(err)
	}
	request, err := DecodeRequestHeader(&handshake, testValidator(t, account), HandshakeOptions{Security: serverSecurity})
	if err != nil {
		t.Fatal(err)
	}
	if !request.Session.PQ || !request.Session.InnerAEAD {
		t.Fatalf("security profile was not selected: %+v", request.Session)
	}
	if err := EncodeResponseHeader(&handshake, &request.Session, 0); err != nil {
		t.Fatal(err)
	}
	if err := DecodeResponseHeader(&handshake, clientSession); err != nil {
		t.Fatal(err)
	}

	payload := make([]byte, int(buf.Size)+1234)
	for index := range payload {
		payload[index] = byte(index)
	}
	var encrypted bytes.Buffer
	_, clientWriter, err := newRecordLayer(&encrypted, &encrypted, clientSession, true, StealthSettings{})
	if err != nil {
		t.Fatal(err)
	}
	serverReader, _, err := newRecordLayer(&encrypted, &encrypted, &request.Session, false, StealthSettings{})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := clientWriter.Write(payload); err != nil {
		t.Fatal(err)
	}
	decoded := make([]byte, len(payload))
	if _, err := io.ReadFull(serverReader, decoded); err != nil {
		t.Fatal(err)
	}
	if diff := cmp.Diff(payload, decoded); diff != "" {
		t.Fatalf("inner AEAD payload mismatch (-want +got):\n%s", diff)
	}
}

func TestInnerAEADTamperIsRejected(t *testing.T) {
	session := &Session{InnerAEAD: true}
	for index := range session.Secret {
		session.Secret[index] = byte(index + 1)
	}
	for index := range session.ClientNonce {
		session.ClientNonce[index] = byte(index + 2)
		session.ServerNonce[index] = byte(index + 3)
	}
	for index := range session.SessionID {
		session.SessionID[index] = byte(index + 4)
	}
	var encrypted bytes.Buffer
	_, clientWriter, err := newRecordLayer(&encrypted, &encrypted, session, true, StealthSettings{})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := clientWriter.Write([]byte("authenticated")); err != nil {
		t.Fatal(err)
	}
	wire := encrypted.Bytes()
	wire[len(wire)-1] ^= 1
	serverReader, _, err := newRecordLayer(bytes.NewReader(wire), io.Discard, session, false, StealthSettings{})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadAll(serverReader); err == nil {
		t.Fatal("tampered inner AEAD record was accepted")
	}
}

func TestClientInitTamperAndReplayAreRejected(t *testing.T) {
	account := testAccount(t)
	target := net.TCPDestination(net.LocalHostIP, 80)
	var original bytes.Buffer
	if _, err := EncodeRequestHeader(&original, target, account); err != nil {
		t.Fatal(err)
	}
	wire := append([]byte(nil), original.Bytes()...)
	wire[len(wire)-1] ^= 0x01
	if _, err := DecodeRequestHeader(bytes.NewReader(wire), testValidator(t, account)); err == nil {
		t.Fatal("tampered ClientInit was accepted")
	}

	validator := testValidator(t, account)
	if _, err := DecodeRequestHeader(bytes.NewReader(original.Bytes()), validator); err != nil {
		t.Fatal(err)
	}
	if _, err := DecodeRequestHeader(bytes.NewReader(original.Bytes()), validator); err == nil {
		t.Fatal("replayed ClientInit was accepted")
	}
}

func TestStaleAuthenticatedClientInitIsRejected(t *testing.T) {
	account := testAccount(t)
	var wire bytes.Buffer
	if _, err := EncodeRequestHeader(&wire, net.TCPDestination(net.LocalHostIP, 80), account); err != nil {
		t.Fatal(err)
	}
	encoded := append([]byte(nil), wire.Bytes()...)
	body := encoded[2:]
	binary.BigEndian.PutUint64(body[48:56], uint64(time.Now().Add(-3*replayTTL).Unix()))
	var nonce [16]byte
	copy(nonce[:], body[24:40])
	key, err := deriveKey(account.Secret[:], nonce[:], clientKeyInfo)
	if err != nil {
		t.Fatal(err)
	}
	message := body[:len(body)-authTagSize]
	tag := calculateMAC(key, clientAuthContext, message)
	copy(body[len(body)-authTagSize:], tag[:])
	if _, err := DecodeRequestHeader(bytes.NewReader(encoded), testValidator(t, account)); err == nil {
		t.Fatal("stale authenticated ClientInit was accepted")
	}
}

func TestUDPPacketRoundTrip(t *testing.T) {
	target := net.UDPDestination(net.DomainAddress("game.example"), 27015)
	payload := buf.FromBytes([]byte("game-packet"))
	payload.UDP = &target
	var wire bytes.Buffer
	if err := (&PacketWriter{Writer: &wire, Target: target}).WriteMultiBuffer(buf.MultiBuffer{payload}); err != nil {
		t.Fatal(err)
	}
	decoded, err := (&PacketReader{Reader: &wire}).ReadMultiBuffer()
	if err != nil {
		t.Fatal(err)
	}
	defer buf.ReleaseMulti(decoded)
	if len(decoded) != 1 || decoded[0].UDP == nil {
		t.Fatal("invalid decoded UDP packet")
	}
	if diff := cmp.Diff(target, *decoded[0].UDP); diff != "" {
		t.Fatalf("UDP target mismatch (-want +got):\n%s", diff)
	}
	if got := string(decoded[0].Bytes()); got != "game-packet" {
		t.Fatalf("UDP payload mismatch: %q", got)
	}
}

func TestTurboUDPDestinationCompressionAndDeadlineDrop(t *testing.T) {
	turbo := TurboSettings{Enabled: true, MaxDatagramAge: 5 * time.Millisecond, DestinationCacheSize: 8, MaxUDPPayload: 2048}
	target := net.UDPDestination(net.DomainAddress("game.example"), 27015)
	epoch := time.Now()
	var wire bytes.Buffer
	writer := &PacketWriter{Writer: &wire, Target: target, Turbo: turbo, Epoch: epoch}
	first := buf.FromBytes([]byte("stale"))
	first.UDP = &target
	if err := writer.WriteMultiBuffer(buf.MultiBuffer{first}); err != nil {
		t.Fatal(err)
	}
	firstFrameSize := wire.Len()
	time.Sleep(20 * time.Millisecond)
	second := buf.FromBytes([]byte("fresh"))
	second.UDP = &target
	if err := writer.WriteMultiBuffer(buf.MultiBuffer{second}); err != nil {
		t.Fatal(err)
	}
	secondFrameSize := wire.Len() - firstFrameSize
	if secondFrameSize >= firstFrameSize {
		t.Fatalf("cached destination did not reduce frame size: first=%d second=%d", firstFrameSize, secondFrameSize)
	}

	reader := &PacketReader{Reader: &wire, Turbo: turbo, Epoch: epoch}
	decoded, err := reader.ReadMultiBuffer()
	if err != nil {
		t.Fatal(err)
	}
	defer buf.ReleaseMulti(decoded)
	if got := string(decoded[0].Bytes()); got != "fresh" {
		t.Fatalf("deadline-aware reader returned %q, want fresh packet", got)
	}
}

func FuzzDecodeRequestHeader(f *testing.F) {
	account := testAccount(f)
	var valid bytes.Buffer
	_, _ = EncodeRequestHeader(&valid, net.TCPDestination(net.LocalHostIP, 443), account)
	f.Add([]byte{})
	f.Add([]byte{0, 1, 0})
	f.Add(valid.Bytes())
	f.Fuzz(func(t *testing.T, data []byte) {
		validator := testValidator(t, account)
		_, _ = DecodeRequestHeader(bytes.NewReader(data), validator)
	})
}

func FuzzRecordReader(f *testing.F) {
	session := &Session{InnerAEAD: true}
	for index := range session.Secret {
		session.Secret[index] = byte(index + 1)
	}
	for index := range session.ClientNonce {
		session.ClientNonce[index] = byte(index + 2)
		session.ServerNonce[index] = byte(index + 3)
	}
	for index := range session.SessionID {
		session.SessionID[index] = byte(index + 4)
	}
	var valid bytes.Buffer
	_, writer, err := newRecordLayer(bytes.NewReader(nil), &valid, session, true, StealthSettings{})
	if err != nil {
		f.Fatal(err)
	}
	_, _ = writer.Write([]byte("valid record"))
	f.Add([]byte{})
	f.Add([]byte{0xff, 0xff})
	f.Add(valid.Bytes())
	f.Fuzz(func(t *testing.T, data []byte) {
		reader, _, err := newRecordLayer(bytes.NewReader(data), io.Discard, session, false, StealthSettings{})
		if err != nil {
			t.Fatal(err)
		}
		_, _ = io.ReadAll(reader)
	})
}
