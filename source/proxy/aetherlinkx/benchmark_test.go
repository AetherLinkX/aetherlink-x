package aetherlinkx

import (
	"bytes"
	"io"
	"testing"
	"time"

	"github.com/cloudflare/circl/kem/xwing"
	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/net"
)

func BenchmarkAetherLinkXHandshakeClassic(b *testing.B) {
	account := testAccount(b)
	target := net.TCPDestination(net.DomainAddress("game.example"), 443)
	b.ReportAllocs()
	for b.Loop() {
		var wire bytes.Buffer
		if _, err := EncodeRequestHeader(&wire, target, account); err != nil {
			b.Fatal(err)
		}
		if _, err := DecodeRequestHeader(&wire, testValidator(b, account)); err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkAetherLinkXHandshakeXWing(b *testing.B) {
	privateKey, publicKey, err := xwing.GenerateKeyPairPacked(nil)
	if err != nil {
		b.Fatal(err)
	}
	account := testAccount(b)
	target := net.TCPDestination(net.DomainAddress("game.example"), 443)
	client := HandshakeOptions{Security: SecuritySettings{PQMode: PQModeRequired, XWingPublic: publicKey, InnerAEAD: true}}
	server := HandshakeOptions{Security: SecuritySettings{PQMode: PQModeRequired, XWingPrivate: privateKey, InnerAEAD: true}}
	b.ReportAllocs()
	for b.Loop() {
		var wire bytes.Buffer
		if _, err := EncodeRequestHeader(&wire, target, account, client); err != nil {
			b.Fatal(err)
		}
		if _, err := DecodeRequestHeader(&wire, testValidator(b, account), server); err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkAetherLinkXRecord1K(b *testing.B) {
	session := benchmarkSession()
	payload := make([]byte, 1024)
	b.SetBytes(int64(len(payload)))
	b.ReportAllocs()
	for b.Loop() {
		_, writer, err := newRecordLayer(bytes.NewReader(nil), io.Discard, session, true, StealthSettings{})
		if err != nil {
			b.Fatal(err)
		}
		if _, err := writer.Write(payload); err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkAetherLinkXTurboUDPCached(b *testing.B) {
	target := net.UDPDestination(net.DomainAddress("game.example"), 27015)
	turbo := TurboSettings{Enabled: true, MaxDatagramAge: 35 * time.Millisecond, DestinationCacheSize: 64, MaxUDPPayload: 8192}
	writer := &PacketWriter{Writer: io.Discard, Target: target, Turbo: turbo, Epoch: time.Now()}
	b.SetBytes(512)
	b.ReportAllocs()
	for b.Loop() {
		packet := buf.New()
		_, _ = packet.Write(make([]byte, 512))
		packet.UDP = &target
		if err := writer.WriteMultiBuffer(buf.MultiBuffer{packet}); err != nil {
			b.Fatal(err)
		}
	}
}

func benchmarkSession() *Session {
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
	return session
}
