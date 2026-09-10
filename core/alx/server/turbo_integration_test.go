package server_test

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	alxclient "github.com/AetherLinkX/aetherlink-x/core/alx/client"
	alxserver "github.com/AetherLinkX/aetherlink-x/core/alx/server"
)

// TestTurboTCPStart exercises the complete Preview 8 TCP handshake: Chrome
// ClientHello, SNI routing, certificate pinning, TLS exporter-bound auth and
// HTTP Upgrade. The intentionally unused UDP port makes the TCP result
// deterministic while still exercising Turbo's parallel path probe.
func TestTurboTCPStart(t *testing.T) {
	certificateFile, keyFile, pin := writeTestCertificate(t, "turbo.test")
	tokenBytes := make([]byte, 32)
	if _, err := rand.Read(tokenBytes); err != nil {
		t.Fatal(err)
	}
	token := base64.RawURLEncoding.EncodeToString(tokenBytes)
	tcpAddress := unusedAddress(t, "tcp")
	quicAddress := unusedAddress(t, "udp")
	deadQUICAddress := unusedAddress(t, "udp")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	server, err := alxserver.New(alxserver.Config{
		Listen:          quicAddress,
		TCPListen:       tcpAddress,
		CertFile:        certificateFile,
		KeyFile:         keyFile,
		TurboCertFile:   certificateFile,
		TurboKeyFile:    keyFile,
		TurboServerName: "turbo.test",
		Token:           token,
	})
	if err != nil {
		t.Fatal(err)
	}
	serverErrors := make(chan error, 1)
	go func() { serverErrors <- server.Run(ctx) }()

	var runtime *alxclient.Runtime
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		runtime, err = alxclient.Start(alxclient.Config{
			Listen:             "127.0.0.1:0",
			Server:             deadQUICAddress,
			FallbackServer:     tcpAddress,
			TransportMode:      "turbo",
			Token:              token,
			CertificatePin:     pin,
			ServerName:         "turbo.test",
			HandshakeTimeoutMS: 1000,
			QUICProbeTimeoutMS: 150,
		})
		if err == nil {
			break
		}
		select {
		case serverErr := <-serverErrors:
			t.Fatalf("server stopped during startup: %v", serverErr)
		default:
		}
		time.Sleep(25 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("Turbo client did not authenticate: %v", err)
	}
	defer runtime.Stop()
	if got := runtime.Stats().Transport; got != "tls-tcp" {
		t.Fatalf("selected transport = %q, want tls-tcp", got)
	}
}

func unusedAddress(t *testing.T, network string) string {
	t.Helper()
	if network == "tcp" {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		address := listener.Addr().String()
		_ = listener.Close()
		return address
	}
	connection, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := connection.LocalAddr().String()
	_ = connection.Close()
	return address
}

func writeTestCertificate(t *testing.T, serverName string) (string, string, string) {
	t.Helper()
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	publicKey := &privateKey.PublicKey
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: serverName},
		DNSNames:     []string{serverName},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, publicKey, privateKey)
	if err != nil {
		t.Fatal(err)
	}
	encodedKey, err := x509.MarshalPKCS8PrivateKey(privateKey)
	if err != nil {
		t.Fatal(err)
	}
	directory := t.TempDir()
	certificateFile := filepath.Join(directory, "server.crt")
	keyFile := filepath.Join(directory, "server.key")
	if err := os.WriteFile(certificateFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: encodedKey}), 0o600); err != nil {
		t.Fatal(err)
	}
	publicKeyInfo, err := x509.MarshalPKIXPublicKey(publicKey)
	if err != nil {
		t.Fatal(err)
	}
	pinHash := sha256.Sum256(publicKeyInfo)
	return certificateFile, keyFile, base64.RawURLEncoding.EncodeToString(pinHash[:])
}
