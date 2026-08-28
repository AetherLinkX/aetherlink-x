package aetherlinkx

import (
	"context"
	"io"
	"time"

	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/errors"
	"github.com/xtls/xray-core/common/log"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	udpProtocol "github.com/xtls/xray-core/common/protocol/udp"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/common/signal"
	"github.com/xtls/xray-core/common/task"
	core "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/policy"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/transport/internet/stat"
	"github.com/xtls/xray-core/transport/internet/udp"
)

// Server implements the AetherLink X inbound proxy.
type Server struct {
	policyManager    policy.Manager
	validator        *Validator
	handshakeTimeout time.Duration
	turbo            TurboSettings
	security         SecuritySettings
	stealth          StealthSettings
}

func NewServer(ctx context.Context, config *ServerConfig) (*Server, error) {
	validator := new(Validator)
	for _, rawUser := range config.Users {
		user, err := rawUser.ToMemoryUser()
		if err != nil {
			return nil, errors.New("failed to parse AetherLink X user").Base(err)
		}
		if err := validator.Add(user); err != nil {
			return nil, errors.New("failed to add AetherLink X user").Base(err)
		}
	}
	if validator.GetCount() == 0 {
		return nil, errors.New("AetherLink X requires at least one user")
	}
	timeout := time.Duration(config.HandshakeTimeoutSeconds) * time.Second
	security, err := ParseServerSecurity(config.Security)
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
	return &Server{
		policyManager:    v.GetFeature(policy.ManagerType()).(policy.Manager),
		validator:        validator,
		handshakeTimeout: timeout,
		turbo:            normalizeTurbo(config.Turbo),
		security:         security,
		stealth:          stealth,
	}, nil
}

func (s *Server) AddUser(_ context.Context, user *protocol.MemoryUser) error {
	return s.validator.Add(user)
}

func (s *Server) RemoveUser(_ context.Context, email string) error {
	return s.validator.Del(email)
}

func (s *Server) GetUser(_ context.Context, email string) *protocol.MemoryUser {
	return s.validator.GetByEmail(email)
}

func (s *Server) GetUsers(context.Context) []*protocol.MemoryUser {
	return s.validator.GetAll()
}

func (s *Server) GetUsersCount(context.Context) int64 {
	return s.validator.GetCount()
}

func (s *Server) Network() []net.Network {
	return []net.Network{net.Network_TCP, net.Network_UNIX}
}

// Process implements proxy.Inbound.
func (s *Server) Process(ctx context.Context, _ net.Network, connection stat.Connection, dispatcher routing.Dispatcher) error {
	basePolicy := s.policyManager.ForLevel(0)
	handshakeTimeout := s.handshakeTimeout
	if handshakeTimeout <= 0 {
		handshakeTimeout = basePolicy.Timeouts.Handshake
	}
	if err := connection.SetReadDeadline(time.Now().Add(handshakeTimeout)); err != nil {
		return errors.New("failed to set AetherLink X handshake deadline").Base(err)
	}
	request, err := DecodeRequestHeader(connection, s.validator, HandshakeOptions{Turbo: s.turbo, Security: s.security})
	if err != nil {
		log.Record(&log.AccessMessage{
			From:   connection.RemoteAddr(),
			Status: log.AccessRejected,
			Reason: err,
		})
		return err
	}
	if !request.Target.IsValid() {
		return errors.New("invalid AetherLink X destination")
	}
	if err := EncodeResponseHeader(connection, &request.Session, 0); err != nil {
		return err
	}
	if err := connection.SetReadDeadline(time.Time{}); err != nil {
		return errors.New("failed to clear AetherLink X handshake deadline").Base(err)
	}
	var payloadReader io.Reader = connection
	var payloadWriter io.Writer = connection
	if request.Session.InnerAEAD {
		payloadReader, payloadWriter, err = newRecordLayer(connection, connection, &request.Session, false, s.stealth)
		if err != nil {
			return err
		}
	}

	inbound := session.InboundFromContext(ctx)
	inbound.Name = "aetherlinkx"
	inbound.CanSpliceCopy = 3
	inbound.User = request.User
	ctx = log.ContextWithAccessMessage(ctx, &log.AccessMessage{
		From:   connection.RemoteAddr(),
		To:     request.Target,
		Status: log.AccessAccepted,
		Email:  request.User.Email,
	})
	errors.LogInfo(ctx, "AetherLink X request for ", request.Target)

	sessionPolicy := s.policyManager.ForLevel(request.User.Level)
	if request.Target.Network == net.Network_UDP {
		return s.handleUDP(ctx, sessionPolicy, payloadReader, payloadWriter, request.Target, &request.Session, dispatcher)
	}
	return s.handleTCP(ctx, sessionPolicy, payloadReader, payloadWriter, request.Target, dispatcher)
}

func (s *Server) handleTCP(ctx context.Context, sessionPolicy policy.Session, payloadReader io.Reader, payloadWriter io.Writer, destination net.Destination, dispatcher routing.Dispatcher) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	timer := signal.CancelAfterInactivity(ctx, cancel, sessionPolicy.Timeouts.ConnectionIdle)
	defer timer.SetTimeout(0)
	ctx = policy.ContextWithBufferPolicy(ctx, sessionPolicy.Buffer)

	link, err := dispatcher.Dispatch(ctx, destination)
	if err != nil {
		return errors.New("failed to dispatch AetherLink X request").Base(err)
	}
	uplink := func() error {
		defer timer.SetTimeout(sessionPolicy.Timeouts.DownlinkOnly)
		if err := buf.Copy(buf.NewReader(payloadReader), link.Writer, buf.UpdateActivity(timer)); err != nil {
			return errors.New("failed to read AetherLink X request payload").Base(err)
		}
		return nil
	}
	downlink := func() error {
		defer timer.SetTimeout(sessionPolicy.Timeouts.UplinkOnly)
		if err := buf.Copy(link.Reader, buf.NewWriter(payloadWriter), buf.UpdateActivity(timer)); err != nil {
			return errors.New("failed to write AetherLink X response payload").Base(err)
		}
		return nil
	}
	if err := task.Run(ctx, task.OnSuccess(uplink, task.Close(link.Writer)), downlink); err != nil {
		common.Must(common.Interrupt(link.Reader))
		common.Must(common.Interrupt(link.Writer))
		return errors.New("AetherLink X TCP connection ended").Base(err)
	}
	return nil
}

func (s *Server) handleUDP(ctx context.Context, sessionPolicy policy.Session, payloadReader io.Reader, payloadWriter io.Writer, initialTarget net.Destination, alxSession *Session, dispatcher routing.Dispatcher) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	timer := signal.CancelAfterInactivity(ctx, cancel, sessionPolicy.Timeouts.ConnectionIdle)
	defer timer.SetTimeout(0)
	reader := &PacketReader{Reader: payloadReader, Turbo: alxSession.Turbo, Epoch: alxSession.PeerEpoch}
	writer := &PacketWriter{Writer: payloadWriter, Target: initialTarget, Turbo: alxSession.Turbo, Epoch: alxSession.LocalEpoch}

	udpServer := udp.NewDispatcher(dispatcher, func(_ context.Context, packet *udpProtocol.Packet) {
		payload := packet.Payload
		if payload.UDP == nil {
			payload.UDP = &packet.Source
		}
		if err := writer.WriteMultiBuffer(buf.MultiBuffer{payload}); err != nil {
			errors.LogWarningInner(ctx, err, "failed to write AetherLink X UDP response")
			cancel()
			return
		}
		timer.Update()
	})
	defer udpServer.RemoveRay()

	for {
		mb, err := reader.ReadMultiBuffer()
		if err != nil {
			if errors.Cause(err) == io.EOF {
				return nil
			}
			return err
		}
		_, payload := buf.SplitFirst(mb)
		if payload == nil {
			continue
		}
		timer.Update()
		destination := initialTarget
		if payload.UDP != nil {
			destination = *payload.UDP
		}
		udpServer.Dispatch(ctx, destination, payload)
	}
}

func init() {
	common.Must(common.RegisterConfig((*ServerConfig)(nil), func(ctx context.Context, config interface{}) (interface{}, error) {
		return NewServer(ctx, config.(*ServerConfig))
	}))
}
