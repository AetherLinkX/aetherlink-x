package scenarios

import (
	"encoding/base64"
	"encoding/hex"
	"testing"
	"time"

	"github.com/cloudflare/circl/kem/xwing"
	"golang.org/x/sync/errgroup"

	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/protocol/tls/cert"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/common/uuid"
	core "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/proxy/aetherlinkx"
	"github.com/xtls/xray-core/proxy/dokodemo"
	"github.com/xtls/xray-core/proxy/freedom"
	"github.com/xtls/xray-core/testing/servers/tcp"
	udpServer "github.com/xtls/xray-core/testing/servers/udp"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/reality"
	transportTCP "github.com/xtls/xray-core/transport/internet/tcp"
	"github.com/xtls/xray-core/transport/internet/tls"
)

func TestAetherLinkXReality(t *testing.T) {
	echoServer := tcp.Server{MsgProcessor: xor}
	destination, err := echoServer.Start()
	common.Must(err)
	defer echoServer.Close()

	secretBytes := make([]byte, 32)
	for index := range secretBytes {
		secretBytes[index] = byte(index + 1)
	}
	userID := uuid.New()
	account := &aetherlinkx.Account{Id: userID.String(), Secret: base64.RawURLEncoding.EncodeToString(secretBytes)}
	xwingPrivate, xwingPublic, err := xwing.GenerateKeyPairPacked(nil)
	common.Must(err)
	serverSecurity := &aetherlinkx.SecurityConfig{PqMode: "required", XwingPrivateKey: base64.RawURLEncoding.EncodeToString(xwingPrivate), InnerAead: true}
	clientSecurity := &aetherlinkx.SecurityConfig{PqMode: "required", XwingPublicKey: base64.RawURLEncoding.EncodeToString(xwingPublic), InnerAead: true}
	stealth := &aetherlinkx.StealthConfig{Enabled: true, MinChunkSize: 256, MaxChunkSize: 1200, MaxPaddingBytes: 128, PaddingProbabilityPercent: 25}

	realityPrivate, _ := base64.RawURLEncoding.DecodeString("aGSYystUbf59_9_6LKRxD27rmSW_-2_nyd9YG_Gwbks")
	realityPublic, _ := base64.RawURLEncoding.DecodeString("E59WjnvZcQMu7tR7_BgyhycuEdBS-CtKxfImRCdAvFM")
	shortID := make([]byte, 8)
	_, _ = hex.Decode(shortID, []byte("0123456789abcdef"))

	serverPort := tcp.PickPort()
	serverConfig := &core.Config{
		Inbound: []*core.InboundHandlerConfig{{
			ReceiverSettings: serial.ToTypedMessage(&proxyman.ReceiverConfig{
				PortList: &net.PortList{Range: []*net.PortRange{net.SinglePortRange(serverPort)}},
				Listen:   net.NewIPOrDomain(net.LocalHostIP),
				StreamSettings: &internet.StreamConfig{
					ProtocolName: "tcp",
					SecurityType: serial.GetMessageType(&reality.Config{}),
					SecuritySettings: []*serial.TypedMessage{serial.ToTypedMessage(&reality.Config{
						Show: true, Dest: "www.google.com:443", ServerNames: []string{"www.google.com"},
						PrivateKey: realityPrivate, ShortIds: [][]byte{shortID}, Type: "tcp",
					})},
				},
			}),
			ProxySettings: serial.ToTypedMessage(&aetherlinkx.ServerConfig{
				Users: []*protocol.User{{Account: serial.ToTypedMessage(account)}},
				Turbo: &aetherlinkx.TurboConfig{Enabled: true, DestinationCacheSize: 64, MaxUdpPayload: 8192},
				Security: serverSecurity, Stealth: stealth,
			}),
		}},
		Outbound: []*core.OutboundHandlerConfig{{ProxySettings: serial.ToTypedMessage(&freedom.Config{
			FinalRules: []*freedom.FinalRuleConfig{{Action: freedom.RuleAction_Allow}},
		})}},
	}

	clientPort := tcp.PickPort()
	clientConfig := &core.Config{
		Inbound: []*core.InboundHandlerConfig{{
			ReceiverSettings: serial.ToTypedMessage(&proxyman.ReceiverConfig{
				PortList: &net.PortList{Range: []*net.PortRange{net.SinglePortRange(clientPort)}},
				Listen: net.NewIPOrDomain(net.LocalHostIP),
			}),
			ProxySettings: serial.ToTypedMessage(&dokodemo.Config{
				RewriteAddress: net.NewIPOrDomain(destination.Address), RewritePort: uint32(destination.Port),
				AllowedNetworks: []net.Network{net.Network_TCP},
			}),
		}},
		Outbound: []*core.OutboundHandlerConfig{{
			ProxySettings: serial.ToTypedMessage(&aetherlinkx.ClientConfig{
				Server: &protocol.ServerEndpoint{Address: net.NewIPOrDomain(net.LocalHostIP), Port: uint32(serverPort), User: &protocol.User{Account: serial.ToTypedMessage(account)}},
				Turbo: &aetherlinkx.TurboConfig{Enabled: true, DestinationCacheSize: 64, MaxUdpPayload: 8192},
				Security: clientSecurity, Stealth: stealth,
			}),
			SenderSettings: serial.ToTypedMessage(&proxyman.SenderConfig{StreamSettings: &internet.StreamConfig{
				ProtocolName: "tcp",
				TransportSettings: []*internet.TransportConfig{{ProtocolName: "tcp", Settings: serial.ToTypedMessage(&transportTCP.Config{})}},
				SecurityType: serial.GetMessageType(&reality.Config{}),
				SecuritySettings: []*serial.TypedMessage{serial.ToTypedMessage(&reality.Config{
					Show: true, Fingerprint: "chrome", ServerName: "www.google.com", PublicKey: realityPublic, ShortId: shortID, SpiderX: "/",
				})},
			}}),
		}},
	}

	servers, err := InitializeServerConfigs(serverConfig, clientConfig)
	common.Must(err)
	defer CloseAllServers(servers)

	var group errgroup.Group
	for range 3 {
		group.Go(testTCPConn(clientPort, 256*1024, 30*time.Second))
	}
	if err := group.Wait(); err != nil {
		t.Fatal(err)
	}
}

func TestAetherLinkXTLS(t *testing.T) {
	echoServer := tcp.Server{MsgProcessor: xor}
	destination, err := echoServer.Start()
	common.Must(err)
	defer echoServer.Close()

	certificate, certificateHash := cert.MustGenerate(nil, cert.CommonName("localhost"))
	secretBytes := make([]byte, 32)
	for index := range secretBytes {
		secretBytes[index] = byte(index + 1)
	}
	userID := uuid.New()
	account := &aetherlinkx.Account{
		Id:     userID.String(),
		Secret: base64.RawURLEncoding.EncodeToString(secretBytes),
	}
	xwingPrivate, xwingPublic, err := xwing.GenerateKeyPairPacked(nil)
	common.Must(err)
	serverSecurity := &aetherlinkx.SecurityConfig{PqMode: "required", XwingPrivateKey: base64.RawURLEncoding.EncodeToString(xwingPrivate), InnerAead: true}
	clientSecurity := &aetherlinkx.SecurityConfig{PqMode: "required", XwingPublicKey: base64.RawURLEncoding.EncodeToString(xwingPublic), InnerAead: true}
	stealth := &aetherlinkx.StealthConfig{Enabled: true, MinChunkSize: 256, MaxChunkSize: 1200, MaxPaddingBytes: 128, PaddingProbabilityPercent: 50}

	serverPort := tcp.PickPort()
	serverConfig := &core.Config{
		Inbound: []*core.InboundHandlerConfig{{
			ReceiverSettings: serial.ToTypedMessage(&proxyman.ReceiverConfig{
				PortList: &net.PortList{Range: []*net.PortRange{net.SinglePortRange(serverPort)}},
				Listen:   net.NewIPOrDomain(net.LocalHostIP),
				StreamSettings: &internet.StreamConfig{
					ProtocolName: "tcp",
					TransportSettings: []*internet.TransportConfig{{
						ProtocolName: "tcp",
						Settings:     serial.ToTypedMessage(&transportTCP.Config{}),
					}},
					SecurityType: serial.GetMessageType(&tls.Config{}),
					SecuritySettings: []*serial.TypedMessage{serial.ToTypedMessage(&tls.Config{
						Certificate: []*tls.Certificate{tls.ParseCertificate(certificate)},
					})},
				},
			}),
			ProxySettings: serial.ToTypedMessage(&aetherlinkx.ServerConfig{
				Users:    []*protocol.User{{Account: serial.ToTypedMessage(account)}},
				Turbo:    &aetherlinkx.TurboConfig{Enabled: true, DestinationCacheSize: 64, MaxUdpPayload: 8192},
				Security: serverSecurity,
				Stealth:  stealth,
			}),
		}},
		Outbound: []*core.OutboundHandlerConfig{{
			ProxySettings: serial.ToTypedMessage(&freedom.Config{
				FinalRules: []*freedom.FinalRuleConfig{{Action: freedom.RuleAction_Allow}},
			}),
		}},
	}

	clientPort := tcp.PickPort()
	clientConfig := &core.Config{
		Inbound: []*core.InboundHandlerConfig{{
			ReceiverSettings: serial.ToTypedMessage(&proxyman.ReceiverConfig{
				PortList: &net.PortList{Range: []*net.PortRange{net.SinglePortRange(clientPort)}},
				Listen:   net.NewIPOrDomain(net.LocalHostIP),
			}),
			ProxySettings: serial.ToTypedMessage(&dokodemo.Config{
				RewriteAddress:  net.NewIPOrDomain(destination.Address),
				RewritePort:     uint32(destination.Port),
				AllowedNetworks: []net.Network{net.Network_TCP},
			}),
		}},
		Outbound: []*core.OutboundHandlerConfig{{
			ProxySettings: serial.ToTypedMessage(&aetherlinkx.ClientConfig{
				Server: &protocol.ServerEndpoint{
					Address: net.NewIPOrDomain(net.LocalHostIP),
					Port:    uint32(serverPort),
					User:    &protocol.User{Account: serial.ToTypedMessage(account)},
				},
				Turbo:    &aetherlinkx.TurboConfig{Enabled: true, DestinationCacheSize: 64, MaxUdpPayload: 8192},
				Security: clientSecurity,
				Stealth:  stealth,
			}),
			SenderSettings: serial.ToTypedMessage(&proxyman.SenderConfig{
				StreamSettings: &internet.StreamConfig{
					ProtocolName: "tcp",
					TransportSettings: []*internet.TransportConfig{{
						ProtocolName: "tcp",
						Settings:     serial.ToTypedMessage(&transportTCP.Config{}),
					}},
					SecurityType: serial.GetMessageType(&tls.Config{}),
					SecuritySettings: []*serial.TypedMessage{serial.ToTypedMessage(&tls.Config{
						PinnedPeerCertSha256: [][]byte{certificateHash[:]},
					})},
				},
			}),
		}},
	}

	servers, err := InitializeServerConfigs(serverConfig, clientConfig)
	common.Must(err)
	defer CloseAllServers(servers)

	var group errgroup.Group
	for range 3 {
		group.Go(testTCPConn(clientPort, 256*1024, 30*time.Second))
	}
	if err := group.Wait(); err != nil {
		t.Fatal(err)
	}
}

func TestAetherLinkXTurboUDP(t *testing.T) {
	echoServer := udpServer.Server{MsgProcessor: xor}
	destination, err := echoServer.Start()
	common.Must(err)
	defer echoServer.Close()

	secretBytes := make([]byte, 32)
	for index := range secretBytes {
		secretBytes[index] = byte(255 - index)
	}
	userID := uuid.New()
	account := &aetherlinkx.Account{
		Id:     userID.String(),
		Secret: base64.RawURLEncoding.EncodeToString(secretBytes),
	}
	xwingPrivate, xwingPublic, err := xwing.GenerateKeyPairPacked(nil)
	common.Must(err)
	serverSecurity := &aetherlinkx.SecurityConfig{PqMode: "required", XwingPrivateKey: base64.RawURLEncoding.EncodeToString(xwingPrivate), InnerAead: true}
	clientSecurity := &aetherlinkx.SecurityConfig{PqMode: "required", XwingPublicKey: base64.RawURLEncoding.EncodeToString(xwingPublic), InnerAead: true}
	stealth := &aetherlinkx.StealthConfig{Enabled: true, MinChunkSize: 128, MaxChunkSize: 900, MaxPaddingBytes: 64, PaddingProbabilityPercent: 25}
	turbo := &aetherlinkx.TurboConfig{
		Enabled:              true,
		MaxDatagramAgeMs:     1000,
		DestinationCacheSize: 64,
		MaxUdpPayload:        8192,
	}

	serverPort := tcp.PickPort()
	serverConfig := &core.Config{
		Inbound: []*core.InboundHandlerConfig{{
			ReceiverSettings: serial.ToTypedMessage(&proxyman.ReceiverConfig{
				PortList: &net.PortList{Range: []*net.PortRange{net.SinglePortRange(serverPort)}},
				Listen:   net.NewIPOrDomain(net.LocalHostIP),
			}),
			ProxySettings: serial.ToTypedMessage(&aetherlinkx.ServerConfig{
				Users:    []*protocol.User{{Account: serial.ToTypedMessage(account)}},
				Turbo:    turbo,
				Security: serverSecurity,
				Stealth:  stealth,
			}),
		}},
		Outbound: []*core.OutboundHandlerConfig{{
			ProxySettings: serial.ToTypedMessage(&freedom.Config{
				FinalRules: []*freedom.FinalRuleConfig{{Action: freedom.RuleAction_Allow}},
			}),
		}},
	}

	clientPort := udpServer.PickPort()
	clientConfig := &core.Config{
		Inbound: []*core.InboundHandlerConfig{{
			ReceiverSettings: serial.ToTypedMessage(&proxyman.ReceiverConfig{
				PortList: &net.PortList{Range: []*net.PortRange{net.SinglePortRange(clientPort)}},
				Listen:   net.NewIPOrDomain(net.LocalHostIP),
			}),
			ProxySettings: serial.ToTypedMessage(&dokodemo.Config{
				RewriteAddress:  net.NewIPOrDomain(destination.Address),
				RewritePort:     uint32(destination.Port),
				AllowedNetworks: []net.Network{net.Network_UDP},
			}),
		}},
		Outbound: []*core.OutboundHandlerConfig{{
			ProxySettings: serial.ToTypedMessage(&aetherlinkx.ClientConfig{
				Server: &protocol.ServerEndpoint{
					Address: net.NewIPOrDomain(net.LocalHostIP),
					Port:    uint32(serverPort),
					User:    &protocol.User{Account: serial.ToTypedMessage(account)},
				},
				Turbo:    turbo,
				Security: clientSecurity,
				Stealth:  stealth,
			}),
		}},
	}

	servers, err := InitializeServerConfigs(serverConfig, clientConfig)
	common.Must(err)
	defer CloseAllServers(servers)

	var group errgroup.Group
	for range 3 {
		group.Go(testUDPConn(clientPort, 8*1024, 10*time.Second))
	}
	if err := group.Wait(); err != nil {
		t.Fatal(err)
	}
}
