package conf

import (
	"strings"
	"unicode"

	"github.com/xtls/xray-core/common/errors"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/proxy/aetherlinkx"
	"google.golang.org/protobuf/proto"
)

type AetherLinkXTurboConfig struct {
	Enabled              bool   `json:"enabled"`
	MaxDatagramAgeMs     uint32 `json:"maxDatagramAgeMs"`
	DestinationCacheSize uint32 `json:"destinationCacheSize"`
	MaxUDPPayload        uint32 `json:"maxUdpPayload"`
	TCPKeepAliveIdle     int32  `json:"tcpKeepAliveIdle"`
	TCPKeepAliveInterval int32  `json:"tcpKeepAliveInterval"`
	TCPUserTimeout       int32  `json:"tcpUserTimeout"`
	Congestion           string `json:"congestion"`
	MultipathTCP         bool   `json:"multipathTcp"`
	MuxConcurrency       int32  `json:"muxConcurrency"`
	XUDPConcurrency      int32  `json:"xudpConcurrency"`
	XUDPProxyUDP443      string `json:"xudpProxyUdp443"`
}

type AetherLinkXSecurityConfig struct {
	PQMode          string `json:"pqMode"`
	XWingPublicKey  string `json:"xwingPublicKey"`
	XWingPrivateKey string `json:"xwingPrivateKey"`
	InnerAEAD       bool   `json:"innerAead"`
}

type AetherLinkXStealthConfig struct {
	Enabled                   bool   `json:"enabled"`
	MinChunkSize              uint32 `json:"minChunkSize"`
	MaxChunkSize              uint32 `json:"maxChunkSize"`
	MaxPaddingBytes           uint32 `json:"maxPaddingBytes"`
	PaddingProbabilityPercent uint32 `json:"paddingProbabilityPercent"`
}

func (c *AetherLinkXStealthConfig) Build() *aetherlinkx.StealthConfig {
	if c == nil {
		return nil
	}
	return &aetherlinkx.StealthConfig{
		Enabled:                   c.Enabled,
		MinChunkSize:              c.MinChunkSize,
		MaxChunkSize:              c.MaxChunkSize,
		MaxPaddingBytes:           c.MaxPaddingBytes,
		PaddingProbabilityPercent: c.PaddingProbabilityPercent,
	}
}

func (c *AetherLinkXSecurityConfig) Build() *aetherlinkx.SecurityConfig {
	if c == nil {
		return nil
	}
	return &aetherlinkx.SecurityConfig{
		PqMode:          c.PQMode,
		XwingPublicKey:  c.XWingPublicKey,
		XwingPrivateKey: c.XWingPrivateKey,
		InnerAead:       c.InnerAEAD,
	}
}

func (c *AetherLinkXTurboConfig) Build() (*aetherlinkx.TurboConfig, error) {
	if c == nil {
		return nil, nil
	}
	if c.MaxDatagramAgeMs > 65535 {
		return nil, errors.New("AetherLink X maxDatagramAgeMs must not exceed 65535")
	}
	if c.DestinationCacheSize > 1024 {
		return nil, errors.New("AetherLink X destinationCacheSize must not exceed 1024")
	}
	if c.MaxUDPPayload != 0 && (c.MaxUDPPayload < 512 || c.MaxUDPPayload > 32768) {
		return nil, errors.New("AetherLink X maxUdpPayload must be between 512 and 32768")
	}
	if c.TCPKeepAliveIdle < 0 || c.TCPKeepAliveInterval < 0 || c.TCPUserTimeout < 0 {
		return nil, errors.New("AetherLink X Turbo socket timeouts must not be negative")
	}
	if c.MuxConcurrency < 0 || c.MuxConcurrency > 1024 || c.XUDPConcurrency < 0 || c.XUDPConcurrency > 1024 {
		return nil, errors.New("AetherLink X Turbo mux concurrency must be between 0 and 1024")
	}
	xudpPolicy := strings.ToLower(strings.TrimSpace(c.XUDPProxyUDP443))
	if xudpPolicy == "" {
		xudpPolicy = "reject"
	}
	if xudpPolicy != "reject" && xudpPolicy != "allow" && xudpPolicy != "skip" {
		return nil, errors.New("invalid AetherLink X xudpProxyUdp443 policy")
	}
	congestion := strings.ToLower(strings.TrimSpace(c.Congestion))
	if len(congestion) > 32 {
		return nil, errors.New("AetherLink X congestion name is too long")
	}
	for _, char := range congestion {
		if !unicode.IsLower(char) && !unicode.IsDigit(char) && char != '-' && char != '_' {
			return nil, errors.New("invalid AetherLink X congestion name")
		}
	}
	return &aetherlinkx.TurboConfig{
		Enabled:              c.Enabled,
		MaxDatagramAgeMs:     c.MaxDatagramAgeMs,
		DestinationCacheSize: c.DestinationCacheSize,
		MaxUdpPayload:        c.MaxUDPPayload,
		TcpKeepAliveIdle:     c.TCPKeepAliveIdle,
		TcpKeepAliveInterval: c.TCPKeepAliveInterval,
		TcpUserTimeout:       c.TCPUserTimeout,
		Congestion:           congestion,
		MultipathTcp:         c.MultipathTCP,
		MuxConcurrency:       c.MuxConcurrency,
		XudpConcurrency:      c.XUDPConcurrency,
		XudpProxyUdp443:      xudpPolicy,
	}, nil
}

type AetherLinkXServerTarget struct {
	Address *Address `json:"address"`
	Port    uint16   `json:"port"`
	Level   byte     `json:"level"`
	Email   string   `json:"email"`
	ID      string   `json:"id"`
	Secret  string   `json:"secret"`
}

type AetherLinkXClientConfig struct {
	Address                *Address                   `json:"address"`
	Port                   uint16                     `json:"port"`
	Level                  byte                       `json:"level"`
	Email                  string                     `json:"email"`
	ID                     string                     `json:"id"`
	Secret                 string                     `json:"secret"`
	Servers                []*AetherLinkXServerTarget `json:"servers"`
	AllowInsecureTransport bool                       `json:"allowInsecureTransport"`
	Turbo                  *AetherLinkXTurboConfig    `json:"turbo"`
	Security               *AetherLinkXSecurityConfig `json:"security"`
	Stealth                *AetherLinkXStealthConfig  `json:"stealth"`
}

func (c *AetherLinkXClientConfig) Build() (proto.Message, error) {
	if c.Address != nil {
		c.Servers = []*AetherLinkXServerTarget{{
			Address: c.Address,
			Port:    c.Port,
			Level:   c.Level,
			Email:   c.Email,
			ID:      c.ID,
			Secret:  c.Secret,
		}}
	}
	if len(c.Servers) != 1 {
		return nil, errors.New(`AetherLink X "servers" must contain exactly one endpoint`)
	}
	server := c.Servers[0]
	if server.Address == nil || server.Port == 0 {
		return nil, errors.New("AetherLink X server address and port are required")
	}
	account := &aetherlinkx.Account{Id: server.ID, Secret: server.Secret}
	if _, err := account.AsAccount(); err != nil {
		return nil, err
	}
	turbo, err := c.Turbo.Build()
	if err != nil {
		return nil, err
	}
	security := c.Security.Build()
	if _, err := aetherlinkx.ParseClientSecurity(security); err != nil {
		return nil, err
	}
	stealth := c.Stealth.Build()
	if _, err := aetherlinkx.ParseStealthConfig(stealth); err != nil {
		return nil, err
	}
	if stealth != nil && stealth.Enabled && (security == nil || !security.InnerAead) {
		return nil, errors.New("AetherLink X Stealth requires innerAead")
	}
	return &aetherlinkx.ClientConfig{
		Server: &protocol.ServerEndpoint{
			Address: server.Address.Build(),
			Port:    uint32(server.Port),
			User: &protocol.User{
				Level:   uint32(server.Level),
				Email:   server.Email,
				Account: serial.ToTypedMessage(account),
			},
		},
		AllowInsecureTransport: c.AllowInsecureTransport,
		Turbo:                  turbo,
		Security:               security,
		Stealth:                stealth,
	}, nil
}

type AetherLinkXUserConfig struct {
	ID     string `json:"id"`
	Secret string `json:"secret"`
	Level  byte   `json:"level"`
	Email  string `json:"email"`
}

type AetherLinkXServerConfig struct {
	Users                   []*AetherLinkXUserConfig   `json:"users"`
	Clients                 []*AetherLinkXUserConfig   `json:"clients"`
	AllowInsecureTransport  bool                       `json:"allowInsecureTransport"`
	HandshakeTimeoutSeconds uint32                     `json:"handshakeTimeoutSeconds"`
	Turbo                   *AetherLinkXTurboConfig    `json:"turbo"`
	Security                *AetherLinkXSecurityConfig `json:"security"`
	Stealth                 *AetherLinkXStealthConfig  `json:"stealth"`
}

func (c *AetherLinkXServerConfig) Build() (proto.Message, error) {
	if c.Clients != nil {
		c.Users = c.Clients
	}
	if len(c.Users) == 0 {
		return nil, errors.New("AetherLink X requires at least one user")
	}
	turbo, err := c.Turbo.Build()
	if err != nil {
		return nil, err
	}
	security := c.Security.Build()
	if _, err := aetherlinkx.ParseServerSecurity(security); err != nil {
		return nil, err
	}
	stealth := c.Stealth.Build()
	if _, err := aetherlinkx.ParseStealthConfig(stealth); err != nil {
		return nil, err
	}
	if stealth != nil && stealth.Enabled && (security == nil || !security.InnerAead) {
		return nil, errors.New("AetherLink X Stealth requires innerAead")
	}
	config := &aetherlinkx.ServerConfig{
		Users:                   make([]*protocol.User, len(c.Users)),
		AllowInsecureTransport:  c.AllowInsecureTransport,
		HandshakeTimeoutSeconds: c.HandshakeTimeoutSeconds,
		Turbo:                   turbo,
		Security:                security,
		Stealth:                 stealth,
	}
	for index, rawUser := range c.Users {
		account := &aetherlinkx.Account{Id: rawUser.ID, Secret: rawUser.Secret}
		if _, err := account.AsAccount(); err != nil {
			return nil, errors.New("invalid AetherLink X user at index ", index).Base(err)
		}
		config.Users[index] = &protocol.User{
			Level:   uint32(rawUser.Level),
			Email:   rawUser.Email,
			Account: serial.ToTypedMessage(account),
		}
	}
	return config, nil
}
