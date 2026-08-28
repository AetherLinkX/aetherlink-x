package aetherlinkx

import (
	"context"
	"io"
	"time"

	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/errors"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/retry"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/common/signal"
	"github.com/xtls/xray-core/common/task"
	core "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/policy"
	"github.com/xtls/xray-core/transport"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/stat"
)

// Client implements the AetherLink X outbound proxy.
type Client struct {
	server        *protocol.ServerSpec
	policyManager policy.Manager
	turbo         TurboSettings
	security      SecuritySettings
	stealth       StealthSettings
}

func NewClient(ctx context.Context, config *ClientConfig) (*Client, error) {
	if config.Server == nil {
		return nil, errors.New("no AetherLink X server configured")
	}
	server, err := protocol.NewServerSpecFromPB(config.Server)
	if err != nil {
		return nil, errors.New("failed to parse AetherLink X server").Base(err)
	}
	security, err := ParseClientSecurity(config.Security)
	if err != nil {
		return nil, err
	}
	stealth, err := ParseStealthConfig(config.Stealth)
	if err != nil {
		return nil, err
	}
	if stealth.Enabled && !security.InnerAEAD {
		return nil, errors.New("AetherLink X Stealth requires innerAead")
	}
	v := core.MustFromContext(ctx)
	return &Client{
		server:        server,
		policyManager: v.GetFeature(policy.ManagerType()).(policy.Manager),
		turbo:         normalizeTurbo(config.Turbo),
		security:      security,
		stealth:       stealth,
	}, nil
}

// Process implements proxy.Outbound.
func (c *Client) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer) error {
	outbounds := session.OutboundsFromContext(ctx)
	if len(outbounds) == 0 || !outbounds[len(outbounds)-1].Target.IsValid() {
		return errors.New("AetherLink X target is not specified")
	}
	outbound := outbounds[len(outbounds)-1]
	outbound.Name = "aetherlinkx"
	outbound.CanSpliceCopy = 3
	target := outbound.Target

	var connection stat.Connection
	if err := retry.ExponentialBackoff(5, 100).On(func() error {
		conn, err := dialer.Dial(ctx, c.server.Destination)
		if err == nil {
			connection = conn
		}
		return err
	}); err != nil {
		return errors.New("failed to dial AetherLink X server").Base(err).AtWarning()
	}
	defer connection.Close()

	user := c.server.User
	account, ok := user.Account.(*MemoryAccount)
	if !ok {
		return errors.New("invalid AetherLink X user account")
	}
	sessionState, err := EncodeRequestHeader(connection, target, account, HandshakeOptions{Turbo: c.turbo, Security: c.security})
	if err != nil {
		return err
	}
	sessionPolicy := c.policyManager.ForLevel(user.Level)
	if err := connection.SetReadDeadline(time.Now().Add(sessionPolicy.Timeouts.Handshake)); err != nil {
		return errors.New("failed to set AetherLink X response deadline").Base(err)
	}
	if err := DecodeResponseHeader(connection, sessionState); err != nil {
		return err
	}
	if err := connection.SetReadDeadline(time.Time{}); err != nil {
		return errors.New("failed to clear AetherLink X response deadline").Base(err)
	}
	var payloadReader io.Reader = connection
	var payloadWriter io.Writer = connection
	if sessionState.InnerAEAD {
		payloadReader, payloadWriter, err = newRecordLayer(connection, connection, sessionState, true, c.stealth)
		if err != nil {
			return err
		}
	}

	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	timer := signal.CancelAfterInactivity(ctx, cancel, sessionPolicy.Timeouts.ConnectionIdle)
	defer timer.SetTimeout(0)

	uplink := func() error {
		defer timer.SetTimeout(sessionPolicy.Timeouts.DownlinkOnly)
		var writer buf.Writer = buf.NewWriter(payloadWriter)
		if target.Network == net.Network_UDP {
			writer = &PacketWriter{Writer: payloadWriter, Target: target, Turbo: sessionState.Turbo, Epoch: sessionState.LocalEpoch}
		}
		if err := buf.Copy(link.Reader, writer, buf.UpdateActivity(timer)); err != nil {
			return errors.New("failed to upload AetherLink X payload").Base(err)
		}
		return nil
	}

	downlink := func() error {
		defer timer.SetTimeout(sessionPolicy.Timeouts.UplinkOnly)
		var reader buf.Reader = buf.NewReader(payloadReader)
		if target.Network == net.Network_UDP {
			reader = &PacketReader{Reader: payloadReader, Turbo: sessionState.Turbo, Epoch: sessionState.PeerEpoch}
		}
		if err := buf.Copy(reader, link.Writer, buf.UpdateActivity(timer)); err != nil {
			return errors.New("failed to download AetherLink X payload").Base(err)
		}
		return nil
	}

	if err := task.Run(ctx, uplink, task.OnSuccess(downlink, task.Close(link.Writer))); err != nil {
		return errors.New("AetherLink X connection ended").Base(err)
	}
	return nil
}

func init() {
	common.Must(common.RegisterConfig((*ClientConfig)(nil), func(ctx context.Context, config interface{}) (interface{}, error) {
		return NewClient(ctx, config.(*ClientConfig))
	}))
}
