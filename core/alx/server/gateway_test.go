package server

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/base64"
	"net"
	"testing"
	"time"

	"github.com/AetherLinkX/aetherlink-x/core/alx"
)

func TestClientHelloALPNRouting(t *testing.T) {
	for _, test := range []struct {
		name  string
		alpn  []string
		match bool
	}{
		{name: "native", alpn: []string{"alx/1"}, match: true},
		{name: "xray passthrough", alpn: []string{"h2", "http/1.1"}, match: false},
	} {
		t.Run(test.name, func(t *testing.T) {
			clientSide, serverSide := net.Pipe()
			defer clientSide.Close()
			defer serverSide.Close()
			go func() {
				client := tls.Client(clientSide, &tls.Config{
					ServerName:         "www.yahoo.com",
					NextProtos:         test.alpn,
					InsecureSkipVerify: true,
				})
				_ = client.Handshake()
			}()
			if actual := clientHelloHasALPN(bufio.NewReader(serverSide), "alx/1"); actual != test.match {
				t.Fatalf("match=%v, want %v", actual, test.match)
			}
		})
	}
}

func TestClientHelloServerNameRouting(t *testing.T) {
	clientSide, serverSide := net.Pipe()
	defer clientSide.Close()
	defer serverSide.Close()
	go func() {
		client := tls.Client(clientSide, &tls.Config{
			ServerName:         "turbo.sinfor.fun",
			NextProtos:         []string{"h2", "http/1.1"},
			InsecureSkipVerify: true,
		})
		_ = client.Handshake()
	}()
	info, ok := inspectClientHello(bufio.NewReader(serverSide))
	if !ok {
		t.Fatal("ClientHello was not parsed")
	}
	if info.serverName != "turbo.sinfor.fun" {
		t.Fatalf("serverName=%q", info.serverName)
	}
	if len(info.protocols) != 2 || info.protocols[0] != "h2" || info.protocols[1] != "http/1.1" {
		t.Fatalf("protocols=%v", info.protocols)
	}
}

func TestWebSocketAcceptRFCExample(t *testing.T) {
	const key = "dGhlIHNhbXBsZSBub25jZQ=="
	const want = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
	if got := webSocketAccept(key); got != want {
		t.Fatalf("webSocketAccept()=%q, want %q", got, want)
	}
}

func TestAuthFailureLimiterEscalatesAndResets(t *testing.T) {
	limiter := newAuthFailureLimiter()
	remote := &net.TCPAddr{IP: net.ParseIP("192.0.2.10"), Port: 44321}
	now := time.Unix(1_800_000_000, 0)
	first := limiter.delay(remote, now)
	second := limiter.delay(remote, now.Add(time.Second))
	if first < 75*time.Millisecond || first > 200*time.Millisecond {
		t.Fatalf("first delay=%s", first)
	}
	if second < 150*time.Millisecond || second > 275*time.Millisecond {
		t.Fatalf("second delay=%s", second)
	}
	limiter.reset(remote)
	afterReset := limiter.delay(remote, now.Add(2*time.Second))
	if afterReset < 75*time.Millisecond || afterReset > 200*time.Millisecond {
		t.Fatalf("delay after reset=%s", afterReset)
	}
}

func TestServerAcceptsPreviousTokenDuringRotation(t *testing.T) {
	current := bytes.Repeat([]byte{0x31}, 32)
	previous := bytes.Repeat([]byte{0x32}, 32)
	server, err := New(Config{
		CertFile:       "unused-cert.pem",
		KeyFile:        "unused-key.pem",
		Token:          base64.RawURLEncoding.EncodeToString(current),
		PreviousTokens: base64.RawURLEncoding.EncodeToString(previous),
	})
	if err != nil {
		t.Fatal(err)
	}
	binding := bytes.Repeat([]byte{0x71}, 32)
	now := time.Unix(1_800_000_000, 0)
	var wire bytes.Buffer
	if err := alx.WriteTurboAuth(&wire, previous, binding, now); err != nil {
		t.Fatal(err)
	}
	auth, err := alx.ReadTurboAuth(&wire)
	if err != nil {
		t.Fatal(err)
	}
	if !server.verifyTurboAuth(auth, binding, now) {
		t.Fatal("previous rotation token was rejected")
	}
	wrongBinding := bytes.Repeat([]byte{0x72}, 32)
	if server.verifyTurboAuth(auth, wrongBinding, now) {
		t.Fatal("previous token bypassed TLS session binding")
	}
}
