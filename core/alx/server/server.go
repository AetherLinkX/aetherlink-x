package server

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"sync"
	"time"

	"github.com/AetherLinkX/aetherlink-x/core/alx"
	quic "github.com/quic-go/quic-go"
)

type Config struct {
	Listen   string
	CertFile string
	KeyFile  string
	Token    string
	Logger   *log.Logger
}

type Server struct {
	config Config
	token  []byte
	logger *log.Logger
	replay *replayCache
}

func New(config Config) (*Server, error) {
	if config.Listen == "" {
		config.Listen = ":443"
	}
	if config.CertFile == "" || config.KeyFile == "" {
		return nil, errors.New("TLS certificate and key are required")
	}
	token, err := alx.DecodeToken(config.Token)
	if err != nil {
		return nil, err
	}
	logger := config.Logger
	if logger == nil {
		logger = log.Default()
	}
	return &Server{config: config, token: token, logger: logger, replay: newReplayCache()}, nil
}

func (s *Server) Run(ctx context.Context) error {
	certificate, err := tls.LoadX509KeyPair(s.config.CertFile, s.config.KeyFile)
	if err != nil {
		return fmt.Errorf("load TLS key pair: %w", err)
	}
	tlsConfig := &tls.Config{
		MinVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{certificate},
		NextProtos:   []string{"h3"},
	}
	listener, err := quic.ListenAddr(s.config.Listen, tlsConfig, &quic.Config{
		HandshakeIdleTimeout: 8 * time.Second,
		MaxIdleTimeout:       75 * time.Second,
		KeepAlivePeriod:      15 * time.Second,
		EnableDatagrams:      true,
		MaxIncomingStreams:   512,
		Allow0RTT:            false,
	})
	if err != nil {
		return fmt.Errorf("listen for ALX/1: %w", err)
	}
	defer listener.Close()
	go func() {
		<-ctx.Done()
		_ = listener.Close()
	}()
	s.logger.Printf("ALX/1 listening on %s/udp", s.config.Listen)
	for {
		connection, err := listener.Accept(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go s.handleConnection(connection)
	}
}

func (s *Server) handleConnection(connection *quic.Conn) {
	defer connection.CloseWithError(0, "connection closed")
	authContext, cancel := context.WithTimeout(connection.Context(), 8*time.Second)
	defer cancel()
	authStream, err := connection.AcceptStream(authContext)
	if err != nil {
		return
	}
	auth, err := alx.ReadAuth(authStream)
	if err != nil || !auth.Verify(s.token, time.Now(), 60*time.Second) || !s.replay.accept(auth.Nonce, time.Now()) {
		_, _ = authStream.Write([]byte{alx.StatusDenied})
		_ = authStream.Close()
		return
	}
	_, _ = authStream.Write([]byte{alx.StatusOK})
	_ = authStream.Close()

	state := &connectionState{
		server:       s,
		connection:   connection,
		associations: make(map[uint32]*udpAssociation),
	}
	defer state.close()
	go state.receiveDatagrams()
	for {
		stream, err := connection.AcceptStream(connection.Context())
		if err != nil {
			return
		}
		go state.handleStream(stream)
	}
}

type connectionState struct {
	server       *Server
	connection   *quic.Conn
	associationM sync.RWMutex
	associations map[uint32]*udpAssociation
}

func (s *connectionState) handleStream(stream *quic.Stream) {
	request, err := alx.ReadOpen(stream)
	if err != nil {
		_, _ = stream.Write([]byte{alx.StatusBadRequest})
		_ = stream.Close()
		return
	}
	switch request.Command {
	case alx.CommandTCP:
		s.handleTCP(stream, request.Address)
	case alx.CommandUDP:
		s.handleUDP(stream, request.AssociationID)
	default:
		_, _ = stream.Write([]byte{alx.StatusBadRequest})
		_ = stream.Close()
	}
}

func (s *connectionState) handleTCP(stream *quic.Stream, destination string) {
	defer stream.Close()
	ctx, cancel := context.WithTimeout(s.connection.Context(), 10*time.Second)
	remote, err := dialPublic(ctx, "tcp", destination)
	cancel()
	if err != nil {
		_, _ = stream.Write([]byte{alx.StatusDialFailure})
		return
	}
	defer remote.Close()
	if _, err := stream.Write([]byte{alx.StatusOK}); err != nil {
		return
	}
	proxyBidirectional(stream, remote)
}

func (s *connectionState) handleUDP(stream *quic.Stream, id uint32) {
	if id == 0 {
		_, _ = stream.Write([]byte{alx.StatusBadRequest})
		_ = stream.Close()
		return
	}
	udp, err := net.ListenUDP("udp", nil)
	if err != nil {
		_, _ = stream.Write([]byte{alx.StatusDialFailure})
		_ = stream.Close()
		return
	}
	association := &udpAssociation{
		id:         id,
		connection: s.connection,
		stream:     stream,
		udp:        udp,
	}
	s.associationM.Lock()
	if previous := s.associations[id]; previous != nil {
		_ = previous.udp.Close()
		_ = previous.stream.Close()
	}
	s.associations[id] = association
	s.associationM.Unlock()
	defer func() {
		s.associationM.Lock()
		if s.associations[id] == association {
			delete(s.associations, id)
		}
		s.associationM.Unlock()
		_ = udp.Close()
		_ = stream.Close()
	}()
	if _, err := stream.Write([]byte{alx.StatusOK}); err != nil {
		return
	}
	go association.readRemote()
	for {
		destination, payload, err := alx.ReadUDPFrame(stream)
		if err != nil {
			return
		}
		_ = association.send(destination, payload)
	}
}

func (s *connectionState) receiveDatagrams() {
	for {
		raw, err := s.connection.ReceiveDatagram(s.connection.Context())
		if err != nil {
			return
		}
		datagram, err := alx.DecodeDatagram(raw)
		if err != nil {
			continue
		}
		s.associationM.RLock()
		association := s.associations[datagram.AssociationID]
		s.associationM.RUnlock()
		if association != nil {
			_ = association.send(datagram.Address, datagram.Payload)
		}
	}
}

func (s *connectionState) close() {
	s.associationM.Lock()
	defer s.associationM.Unlock()
	for _, association := range s.associations {
		_ = association.udp.Close()
		_ = association.stream.Close()
	}
	clear(s.associations)
}

type udpAssociation struct {
	id         uint32
	connection *quic.Conn
	stream     *quic.Stream
	udp        *net.UDPConn
	writeMu    sync.Mutex
}

func (a *udpAssociation) send(destination string, payload []byte) error {
	address, err := resolvePublicUDP(destination)
	if err != nil {
		return err
	}
	_, err = a.udp.WriteToUDP(payload, address)
	return err
}

func (a *udpAssociation) readRemote() {
	buffer := make([]byte, alx.MaxUDPPayload)
	for {
		n, source, err := a.udp.ReadFromUDP(buffer)
		if err != nil {
			return
		}
		payload := append([]byte(nil), buffer[:n]...)
		datagram, encodeErr := alx.EncodeDatagram(alx.Datagram{
			AssociationID: a.id,
			Address:       source.String(),
			Payload:       payload,
		})
		if encodeErr == nil && len(datagram) <= alx.FastDatagramLimit {
			if err := a.connection.SendDatagram(datagram); err == nil {
				continue
			}
		}
		a.writeMu.Lock()
		err = alx.WriteUDPFrame(a.stream, source.String(), payload)
		a.writeMu.Unlock()
		if err != nil {
			return
		}
	}
}

func dialPublic(ctx context.Context, network, destination string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(destination)
	if err != nil {
		return nil, errors.New("invalid destination")
	}
	addresses, err := net.DefaultResolver.LookupIP(ctx, "ip", host)
	if err != nil {
		return nil, err
	}
	dialer := net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	for _, address := range addresses {
		if !isPublic(address) {
			continue
		}
		connection, dialErr := dialer.DialContext(ctx, network, net.JoinHostPort(address.String(), port))
		if dialErr == nil {
			return connection, nil
		}
		err = dialErr
	}
	if err == nil {
		err = errors.New("destination has no public address")
	}
	return nil, err
}

func resolvePublicUDP(destination string) (*net.UDPAddr, error) {
	host, portRaw, err := net.SplitHostPort(destination)
	if err != nil {
		return nil, errors.New("invalid UDP destination")
	}
	port, err := strconv.Atoi(portRaw)
	if err != nil || port < 1 || port > 65535 {
		return nil, errors.New("invalid UDP port")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	addresses, err := net.DefaultResolver.LookupIP(ctx, "ip", host)
	if err != nil {
		return nil, err
	}
	for _, address := range addresses {
		if isPublic(address) {
			return &net.UDPAddr{IP: address, Port: port}, nil
		}
	}
	return nil, errors.New("UDP destination has no public address")
}

func isPublic(address net.IP) bool {
	return address.IsGlobalUnicast() &&
		!address.IsPrivate() &&
		!address.IsLoopback() &&
		!address.IsLinkLocalUnicast() &&
		!address.IsLinkLocalMulticast() &&
		!address.IsMulticast() &&
		!address.IsUnspecified()
}

func proxyBidirectional(left, right io.ReadWriteCloser) {
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(left, right)
		if closeWriter, ok := left.(interface{ CloseWrite() error }); ok {
			_ = closeWriter.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(right, left)
		if closeWriter, ok := right.(interface{ CloseWrite() error }); ok {
			_ = closeWriter.CloseWrite()
		}
		done <- struct{}{}
	}()
	<-done
}

type replayCache struct {
	mutex sync.Mutex
	seen  map[[16]byte]time.Time
}

func newReplayCache() *replayCache {
	return &replayCache{seen: make(map[[16]byte]time.Time)}
}

func (r *replayCache) accept(nonce [16]byte, now time.Time) bool {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	for value, expiry := range r.seen {
		if now.After(expiry) {
			delete(r.seen, value)
		}
	}
	if _, exists := r.seen[nonce]; exists {
		return false
	}
	r.seen[nonce] = now.Add(2 * time.Minute)
	return true
}
