package server

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/binary"
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
	Listen            string
	TCPListen         string
	TCPDefaultBackend string
	CertFile          string
	KeyFile           string
	Token             string
	Logger            *log.Logger
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
	if config.TCPListen == "" {
		config.TCPListen = ":8443"
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
	quicTLS := &tls.Config{
		MinVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{certificate},
		NextProtos:   []string{"h3"},
	}
	tcpTLS := quicTLS.Clone()
	tcpTLS.NextProtos = []string{"alx/1"}
	errorChannel := make(chan error, 2)
	go func() { errorChannel <- s.runTCP(ctx, tcpTLS) }()
	go func() { errorChannel <- s.runQUIC(ctx, quicTLS) }()
	select {
	case <-ctx.Done():
		return nil
	case err := <-errorChannel:
		return err
	}
}

func (s *Server) runQUIC(ctx context.Context, tlsConfig *tls.Config) error {
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

func (s *Server) runTCP(ctx context.Context, tlsConfig *tls.Config) error {
	listener, err := net.Listen("tcp", s.config.TCPListen)
	if err != nil {
		return fmt.Errorf("listen for ALX/1 TCP fallback: %w", err)
	}
	defer listener.Close()
	go func() {
		<-ctx.Done()
		_ = listener.Close()
	}()
	if s.config.TCPDefaultBackend == "" {
		s.logger.Printf("ALX/1 fallback listening on %s/tcp", s.config.TCPListen)
	} else {
		s.logger.Printf("ALX/1 TLS gateway listening on %s/tcp; default backend %s", s.config.TCPListen, s.config.TCPDefaultBackend)
	}
	for {
		connection, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go s.handleTCPGateway(connection, tlsConfig)
	}
}

func (s *Server) handleTCPGateway(connection net.Conn, tlsConfig *tls.Config) {
	if s.config.TCPDefaultBackend == "" {
		s.handleTCPFallback(tls.Server(connection, tlsConfig))
		return
	}
	reader := bufio.NewReaderSize(connection, 32*1024)
	_ = connection.SetReadDeadline(time.Now().Add(5 * time.Second))
	isALX := clientHelloHasALPN(reader, "alx/1")
	_ = connection.SetReadDeadline(time.Time{})
	buffered := &bufferedConn{Conn: connection, reader: reader}
	if isALX {
		s.handleTCPFallback(tls.Server(buffered, tlsConfig))
		return
	}
	defer connection.Close()
	backend, err := net.DialTimeout("tcp", s.config.TCPDefaultBackend, 5*time.Second)
	if err != nil {
		return
	}
	defer backend.Close()
	proxyBidirectional(buffered, backend)
}

func (s *Server) handleTCPFallback(connection net.Conn) {
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(10 * time.Second))
	auth, err := alx.ReadAuth(connection)
	if err != nil || !auth.Verify(s.token, time.Now(), 60*time.Second) || !s.replay.accept(auth.Nonce, time.Now()) {
		_, _ = connection.Write([]byte{alx.StatusDenied})
		return
	}
	if _, err := connection.Write([]byte{alx.StatusOK}); err != nil {
		return
	}
	request, err := alx.ReadOpen(connection)
	if err != nil {
		_, _ = connection.Write([]byte{alx.StatusBadRequest})
		return
	}
	_ = connection.SetDeadline(time.Time{})
	switch request.Command {
	case alx.CommandTCP:
		s.handleFallbackTCP(connection, request.Address)
	case alx.CommandUDP:
		s.handleFallbackUDP(connection, request.AssociationID)
	default:
		_, _ = connection.Write([]byte{alx.StatusBadRequest})
	}
}

func (s *Server) handleFallbackTCP(connection net.Conn, destination string) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	remote, err := dialPublic(ctx, "tcp", destination)
	cancel()
	if err != nil {
		_, _ = connection.Write([]byte{alx.StatusDialFailure})
		return
	}
	defer remote.Close()
	if _, err := connection.Write([]byte{alx.StatusOK}); err != nil {
		return
	}
	proxyBidirectional(connection, remote)
}

func (s *Server) handleFallbackUDP(connection net.Conn, associationID uint32) {
	if associationID == 0 {
		_, _ = connection.Write([]byte{alx.StatusBadRequest})
		return
	}
	udp, err := net.ListenUDP("udp", nil)
	if err != nil {
		_, _ = connection.Write([]byte{alx.StatusDialFailure})
		return
	}
	defer udp.Close()
	if _, err := connection.Write([]byte{alx.StatusOK}); err != nil {
		return
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		buffer := make([]byte, alx.MaxUDPPayload)
		for {
			n, source, readErr := udp.ReadFromUDP(buffer)
			if readErr != nil {
				return
			}
			if writeErr := alx.WriteUDPFrame(connection, source.String(), buffer[:n]); writeErr != nil {
				return
			}
		}
	}()
	for {
		destination, payload, readErr := alx.ReadUDPFrame(connection)
		if readErr != nil {
			return
		}
		address, resolveErr := resolvePublicUDP(destination)
		if resolveErr == nil {
			_, _ = udp.WriteToUDP(payload, address)
		}
		select {
		case <-done:
			return
		default:
		}
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

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(buffer []byte) (int, error) {
	return c.reader.Read(buffer)
}

// clientHelloHasALPN only inspects the first TLS ClientHello record. It never
// consumes bytes, so non-ALX traffic can be forwarded byte-for-byte to Xray.
func clientHelloHasALPN(reader *bufio.Reader, expected string) bool {
	header, err := reader.Peek(5)
	if err != nil || header[0] != 0x16 || header[1] != 0x03 {
		return false
	}
	recordLength := int(binary.BigEndian.Uint16(header[3:5]))
	if recordLength < 4 || recordLength > 32*1024-5 {
		return false
	}
	record, err := reader.Peek(5 + recordLength)
	if err != nil {
		return false
	}
	handshake := record[5:]
	if len(handshake) < 4 || handshake[0] != 0x01 {
		return false
	}
	bodyLength := int(handshake[1])<<16 | int(handshake[2])<<8 | int(handshake[3])
	if bodyLength+4 > len(handshake) {
		return false
	}
	body := handshake[4 : 4+bodyLength]
	if len(body) < 35 {
		return false
	}
	offset := 34
	sessionLength := int(body[offset])
	offset++
	if offset+sessionLength+2 > len(body) {
		return false
	}
	offset += sessionLength
	cipherLength := int(binary.BigEndian.Uint16(body[offset : offset+2]))
	offset += 2
	if offset+cipherLength+1 > len(body) {
		return false
	}
	offset += cipherLength
	compressionLength := int(body[offset])
	offset++
	if offset+compressionLength+2 > len(body) {
		return false
	}
	offset += compressionLength
	extensionsLength := int(binary.BigEndian.Uint16(body[offset : offset+2]))
	offset += 2
	if offset+extensionsLength > len(body) {
		return false
	}
	end := offset + extensionsLength
	for offset+4 <= end {
		extensionType := binary.BigEndian.Uint16(body[offset : offset+2])
		extensionLength := int(binary.BigEndian.Uint16(body[offset+2 : offset+4]))
		offset += 4
		if offset+extensionLength > end {
			return false
		}
		if extensionType == 16 && alpnContains(body[offset:offset+extensionLength], expected) {
			return true
		}
		offset += extensionLength
	}
	return false
}

func alpnContains(extension []byte, expected string) bool {
	if len(extension) < 2 || int(binary.BigEndian.Uint16(extension[:2])) != len(extension)-2 {
		return false
	}
	for offset := 2; offset < len(extension); {
		length := int(extension[offset])
		offset++
		if length == 0 || offset+length > len(extension) {
			return false
		}
		if string(extension[offset:offset+length]) == expected {
			return true
		}
		offset += length
	}
	return false
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
