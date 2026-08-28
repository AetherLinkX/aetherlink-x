package conf

import (
	"encoding/base64"
	"testing"

	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/proxy/aetherlinkx"
	"github.com/xtls/xray-core/transport/internet"
)

func validALXSecret() string {
	secret := make([]byte, 32)
	for index := range secret {
		secret[index] = byte(index + 1)
	}
	return base64.RawURLEncoding.EncodeToString(secret)
}

func TestAetherLinkXAccountValidation(t *testing.T) {
	config := &AetherLinkXClientConfig{
		Address: &Address{Address: net.DomainAddress("example.com")},
		Port:    443,
		ID:      "66ad4540-b58c-4ad2-9926-ea63445a9b57",
		Secret:  validALXSecret(),
	}
	if _, err := config.Build(); err != nil {
		t.Fatal(err)
	}
	config.Secret = base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	if _, err := config.Build(); err == nil {
		t.Fatal("all-zero AetherLink X secret was accepted")
	}
	config.Secret = "not-a-32-byte-secret"
	if _, err := config.Build(); err == nil {
		t.Fatal("invalid AetherLink X secret was accepted")
	}
}

func TestAetherLinkXPublicOutboundRequiresTransportSecurity(t *testing.T) {
	config := &AetherLinkXClientConfig{
		Address: &Address{Address: net.DomainAddress("example.com")},
	}
	if err := validateOutboundTransportSecurity(config, &proxyman.SenderConfig{}); err == nil {
		t.Fatal("public AetherLink X outbound without TLS/REALITY was accepted")
	}
	secure := &proxyman.SenderConfig{StreamSettings: &internet.StreamConfig{SecurityType: "xray.transport.internet.reality.Config"}}
	if err := validateOutboundTransportSecurity(config, secure); err != nil {
		t.Fatalf("secure AetherLink X outbound was rejected: %v", err)
	}
}

func TestAetherLinkXInboundRequiresTransportSecurity(t *testing.T) {
	config := &AetherLinkXServerConfig{}
	if err := validateInboundTransportSecurity(config, &proxyman.ReceiverConfig{}); err == nil {
		t.Fatal("AetherLink X inbound without TLS/REALITY was accepted")
	}
	config.AllowInsecureTransport = true
	if err := validateInboundTransportSecurity(config, &proxyman.ReceiverConfig{}); err != nil {
		t.Fatalf("explicit private/test override was rejected: %v", err)
	}
}

func TestAetherLinkXTurboTransportTuning(t *testing.T) {
	stream := &internet.StreamConfig{SocketSettings: &internet.SocketConfig{TcpKeepAliveIdle: 99}}
	turbo := &AetherLinkXTurboConfig{Enabled: true, Congestion: "auto", MultipathTCP: true}
	applyAetherLinkXTransportTuning(turbo, &stream)
	if stream.SocketSettings.TcpKeepAliveIdle != 99 {
		t.Fatal("explicit keep-alive setting was overwritten")
	}
	if stream.SocketSettings.TcpKeepAliveInterval != 5 || stream.SocketSettings.TcpUserTimeout != 10000 {
		t.Fatalf("Turbo defaults were not applied: %+v", stream.SocketSettings)
	}
	if stream.SocketSettings.TcpCongestion != "" {
		t.Fatal("auto congestion must preserve the operating system default")
	}
	if !stream.SocketSettings.TcpMptcp {
		t.Fatal("MPTCP setting was not propagated")
	}

	stream = &internet.StreamConfig{}
	turbo.Congestion = "bbr"
	applyAetherLinkXTransportTuning(turbo, &stream)
	if stream.SocketSettings.TcpCongestion != "bbr" {
		t.Fatal("explicit congestion algorithm was not applied")
	}
	sender := new(proxyman.SenderConfig)
	turbo.MuxConcurrency = 8
	turbo.XUDPConcurrency = 4
	turbo.XUDPProxyUDP443 = "allow"
	applyAetherLinkXMuxTuning(turbo, sender)
	if sender.MultiplexSettings == nil || !sender.MultiplexSettings.Enabled || sender.MultiplexSettings.Concurrency != 8 || sender.MultiplexSettings.XudpConcurrency != 4 {
		t.Fatalf("Turbo mux settings were not applied: %+v", sender.MultiplexSettings)
	}
}

func TestAetherLinkXSecurityValidation(t *testing.T) {
	client := (&AetherLinkXSecurityConfig{PQMode: "required", XWingPublicKey: "invalid"}).Build()
	if _, err := aetherlinkx.ParseClientSecurity(client); err == nil {
		t.Fatal("invalid X-Wing public key was accepted")
	}
	server := (&AetherLinkXSecurityConfig{PQMode: "required", XWingPrivateKey: "invalid"}).Build()
	if _, err := aetherlinkx.ParseServerSecurity(server); err == nil {
		t.Fatal("invalid X-Wing private key was accepted")
	}
}
