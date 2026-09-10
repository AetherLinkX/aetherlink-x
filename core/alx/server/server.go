package server

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha1"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
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
	TurboCertFile     string
	TurboKeyFile      string
	TurboServerName   string
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
	legacyCertificate, err := tls.LoadX509KeyPair(s.config.CertFile, s.config.KeyFile)
	if err != nil {
		return fmt.Errorf("load TLS key pair: %w", err)
	}
	turboCertificate := legacyCertificate
	if s.config.TurboCertFile != "" || s.config.TurboKeyFile != "" {
		if s.config.TurboCertFile == "" || s.config.TurboKeyFile == "" {
			return errors.New("both Turbo TLS certificate and key are required")
		}
		turboCertificate, err = tls.LoadX509KeyPair(s.config.TurboCertFile, s.config.TurboKeyFile)
		if err != nil {
			return fmt.Errorf("load Turbo TLS key pair: %w", err)
		}
	}
	quicTLS := &tls.Config{
		MinVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{legacyCertificate},
		NextProtos:   []string{"h3"},
	}
	if s.config.TurboServerName != "" {
		quicTLS.GetCertificate = certificateSelector(s.config.TurboServerName, legacyCertificate, turboCertificate)
	}
	legacyTCP := &tls.Config{
		MinVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{legacyCertificate},
		NextProtos:   []string{"alx/1"},
	}
	turboTCP := &tls.Config{
		MinVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{turboCertificate},
		// The client offers the browser-standard h2 + http/1.1 list. Preview 8
		// deliberately selects HTTP/1.1 because the authenticated Upgrade then
		// becomes a full-duplex low-overhead tunnel.
		NextProtos: []string{"http/1.1"},
	}
	errorChannel := make(chan error, 2)
	go func() { errorChannel <- s.runTCP(ctx, legacyTCP, turboTCP) }()
	go func() { errorChannel <- s.runQUIC(ctx, quicTLS) }()
	select {
	case <-ctx.Done():
		return nil
	case err := <-errorChannel:
		return err
	}
}

func certificateSelector(serverName string, legacy, turbo tls.Certificate) func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
	return func(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
		if strings.EqualFold(strings.TrimSuffix(hello.ServerName, "."), strings.TrimSuffix(serverName, ".")) {
			return &turbo, nil
		}
		return &legacy, nil
	}
}

func (s *Server) runQUIC(ctx context.Context, tlsConfig *tls.Config) error {
	listener, err := quic.ListenAddr(s.config.Listen, tlsConfig, &quic.Config{
		HandshakeIdleTimeout:           8 * time.Second,
		MaxIdleTimeout:                 30 * time.Second,
		KeepAlivePeriod:                10 * time.Second,
		EnableDatagrams:                true,
		MaxIncomingStreams:             512,
		InitialStreamReceiveWindow:     1 << 20,
		MaxStreamReceiveWindow:         8 << 20,
		InitialConnectionReceiveWindow: 4 << 20,
		MaxConnectionReceiveWindow:     32 << 20,
		Allow0RTT:                      false,
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

func (s *Server) runTCP(ctx context.Context, legacyTLS, turboTLS *tls.Config) error {
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
		if tcp, ok := connection.(*net.TCPConn); ok {
			_ = tcp.SetNoDelay(true)
			_ = tcp.SetReadBuffer(1 << 20)
			_ = tcp.SetWriteBuffer(1 << 20)
		}
		go s.handleTCPGateway(connection, legacyTLS, turboTLS)
	}
}

func (s *Server) handleTCPGateway(connection net.Conn, legacyTLS, turboTLS *tls.Config) {
	if s.config.TCPDefaultBackend == "" {
		if s.config.TurboServerName != "" {
			s.handleTurboTCP(tls.Server(connection, turboTLS))
		} else {
			s.handleTCPFallback(tls.Server(connection, legacyTLS))
		}
		return
	}
	reader := bufio.NewReaderSize(connection, 32*1024)
	_ = connection.SetReadDeadline(time.Now().Add(5 * time.Second))
	hello, parsed := inspectClientHello(reader)
	_ = connection.SetReadDeadline(time.Time{})
	buffered := &bufferedConn{Conn: connection, reader: reader}
	if parsed && s.config.TurboServerName != "" && strings.EqualFold(strings.TrimSuffix(hello.serverName, "."), strings.TrimSuffix(s.config.TurboServerName, ".")) {
		s.handleTurboTCP(tls.Server(buffered, turboTLS))
		return
	}
	if parsed && containsString(hello.protocols, "alx/1") {
		s.handleTCPFallback(tls.Server(buffered, legacyTLS))
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

func (s *Server) handleTurboTCP(connection *tls.Conn) {
	defer connection.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := connection.HandshakeContext(ctx); err != nil {
		return
	}
	_ = connection.SetReadDeadline(time.Now().Add(10 * time.Second))
	reader := bufio.NewReader(connection)
	request, err := http.ReadRequest(reader)
	if err != nil {
		return
	}
	defer request.Body.Close()
	if !isWebSocketUpgrade(request) || request.URL.Path != "/gateway" {
		writeCoverResponse(connection, request.URL.Path)
		return
	}
	cookie, err := request.Cookie("session")
	if err != nil {
		writeCoverResponse(connection, request.URL.Path)
		return
	}
	rawAuth, err := base64.RawURLEncoding.DecodeString(cookie.Value)
	if err != nil || len(rawAuth) != alx.TurboAuthSize {
		writeCoverResponse(connection, request.URL.Path)
		return
	}
	state := connection.ConnectionState()
	binding, err := state.ExportKeyingMaterial("EXPORTER-AetherLink-Turbo-v1", nil, 32)
	if err != nil {
		return
	}
	auth, err := alx.ReadTurboAuth(bytes.NewReader(rawAuth))
	if err != nil || !auth.Verify(s.token, binding, time.Now(), 60*time.Second) || !s.replay.accept(auth.Nonce, time.Now()) {
		writeCoverResponse(connection, request.URL.Path)
		return
	}
	webSocketKey := request.Header.Get("Sec-WebSocket-Key")
	if webSocketKey == "" {
		writeCoverResponse(connection, request.URL.Path)
		return
	}
	response := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + webSocketAccept(webSocketKey) + "\r\n\r\n"
	if _, err := io.WriteString(connection, response); err != nil {
		return
	}
	requestOpen, err := alx.ReadOpen(reader)
	if err != nil {
		_, _ = connection.Write([]byte{alx.StatusBadRequest})
		return
	}
	_ = connection.SetDeadline(time.Time{})
	buffered := &bufferedConn{Conn: connection, reader: reader}
	switch requestOpen.Command {
	case alx.CommandTCP:
		s.handleFallbackTCP(buffered, requestOpen.Address)
	case alx.CommandUDP:
		s.handleFallbackUDP(buffered, requestOpen.AssociationID)
	case alx.CommandPing:
		_, _ = buffered.Write([]byte{alx.StatusOK})
	default:
		_, _ = buffered.Write([]byte{alx.StatusBadRequest})
	}
}

func isWebSocketUpgrade(request *http.Request) bool {
	return request.Method == http.MethodGet &&
		strings.EqualFold(request.Header.Get("Upgrade"), "websocket") &&
		headerContainsToken(request.Header.Get("Connection"), "upgrade") &&
		request.Header.Get("Sec-WebSocket-Version") == "13"
}

func headerContainsToken(value, expected string) bool {
	for _, token := range strings.Split(value, ",") {
		if strings.EqualFold(strings.TrimSpace(token), expected) {
			return true
		}
	}
	return false
}

func webSocketAccept(key string) string {
	digest := sha1.Sum([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	return base64.StdEncoding.EncodeToString(digest[:])
}

func writeCoverResponse(connection io.Writer, path string) {
	if path == "" {
		path = "/"
	}
	body := "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>AetherLink Edge</title></head><body><h1>Service online</h1><p>Secure edge gateway is ready.</p></body></html>"
	status := "200 OK"
	if path != "/" && path != "/robots.txt" && path != "/favicon.ico" && path != "/gateway" {
		status = "404 Not Found"
		body = "<!doctype html><html><head><title>Not Found</title></head><body><h1>404</h1></body></html>"
	}
	_, _ = fmt.Fprintf(connection, "HTTP/1.1 %s\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %d\r\nCache-Control: public, max-age=300\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n%s", status, len(body), body)
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
	case alx.CommandPing:
		_, _ = connection.Write([]byte{alx.StatusOK})
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
	validAuth := false
	var nonce [16]byte
	if s.config.TurboServerName != "" && strings.EqualFold(strings.TrimSuffix(connection.ConnectionState().TLS.ServerName, "."), strings.TrimSuffix(s.config.TurboServerName, ".")) {
		state := connection.ConnectionState().TLS
		binding, bindingErr := state.ExportKeyingMaterial("EXPORTER-AetherLink-Turbo-v1", nil, 32)
		auth, authErr := alx.ReadTurboAuth(authStream)
		if bindingErr == nil && authErr == nil && auth.Verify(s.token, binding, time.Now(), 60*time.Second) {
			nonce = auth.Nonce
			validAuth = true
		}
	} else {
		auth, authErr := alx.ReadAuth(authStream)
		if authErr == nil && auth.Verify(s.token, time.Now(), 60*time.Second) {
			nonce = auth.Nonce
			validAuth = true
		}
	}
	if !validAuth || !s.replay.accept(nonce, time.Now()) {
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
	case alx.CommandPing:
		_, _ = stream.Write([]byte{alx.StatusOK})
		_ = stream.Close()
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
			if tcp, ok := connection.(*net.TCPConn); ok {
				_ = tcp.SetNoDelay(true)
				_ = tcp.SetReadBuffer(1 << 20)
				_ = tcp.SetWriteBuffer(1 << 20)
			}
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
		buffer := tunnelBufferPool.Get().([]byte)
		_, _ = io.CopyBuffer(left, right, buffer)
		tunnelBufferPool.Put(buffer)
		if closeWriter, ok := left.(interface{ CloseWrite() error }); ok {
			_ = closeWriter.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		buffer := tunnelBufferPool.Get().([]byte)
		_, _ = io.CopyBuffer(right, left, buffer)
		tunnelBufferPool.Put(buffer)
		if closeWriter, ok := right.(interface{ CloseWrite() error }); ok {
			_ = closeWriter.CloseWrite()
		}
		done <- struct{}{}
	}()
	<-done
}

var tunnelBufferPool = sync.Pool{New: func() any { return make([]byte, 128<<10) }}

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(buffer []byte) (int, error) {
	return c.reader.Read(buffer)
}

type clientHelloInfo struct {
	serverName string
	protocols  []string
}

// inspectClientHello only inspects the first TLS ClientHello record. It never
// consumes bytes, so non-ALX traffic can be forwarded byte-for-byte to Xray.
func inspectClientHello(reader *bufio.Reader) (clientHelloInfo, bool) {
	var info clientHelloInfo
	header, err := reader.Peek(5)
	if err != nil || header[0] != 0x16 || header[1] != 0x03 {
		return info, false
	}
	recordLength := int(binary.BigEndian.Uint16(header[3:5]))
	if recordLength < 4 || recordLength > 32*1024-5 {
		return info, false
	}
	record, err := reader.Peek(5 + recordLength)
	if err != nil {
		return info, false
	}
	handshake := record[5:]
	if len(handshake) < 4 || handshake[0] != 0x01 {
		return info, false
	}
	bodyLength := int(handshake[1])<<16 | int(handshake[2])<<8 | int(handshake[3])
	if bodyLength+4 > len(handshake) {
		return info, false
	}
	body := handshake[4 : 4+bodyLength]
	if len(body) < 35 {
		return info, false
	}
	offset := 34
	sessionLength := int(body[offset])
	offset++
	if offset+sessionLength+2 > len(body) {
		return info, false
	}
	offset += sessionLength
	cipherLength := int(binary.BigEndian.Uint16(body[offset : offset+2]))
	offset += 2
	if offset+cipherLength+1 > len(body) {
		return info, false
	}
	offset += cipherLength
	compressionLength := int(body[offset])
	offset++
	if offset+compressionLength+2 > len(body) {
		return info, false
	}
	offset += compressionLength
	extensionsLength := int(binary.BigEndian.Uint16(body[offset : offset+2]))
	offset += 2
	if offset+extensionsLength > len(body) {
		return info, false
	}
	end := offset + extensionsLength
	for offset+4 <= end {
		extensionType := binary.BigEndian.Uint16(body[offset : offset+2])
		extensionLength := int(binary.BigEndian.Uint16(body[offset+2 : offset+4]))
		offset += 4
		if offset+extensionLength > end {
			return info, false
		}
		extension := body[offset : offset+extensionLength]
		switch extensionType {
		case 0:
			info.serverName = parseServerNameExtension(extension)
		case 16:
			info.protocols = parseALPNExtension(extension)
		}
		offset += extensionLength
	}
	return info, true
}

func clientHelloHasALPN(reader *bufio.Reader, expected string) bool {
	info, ok := inspectClientHello(reader)
	return ok && containsString(info.protocols, expected)
}

func containsString(values []string, expected string) bool {
	for _, value := range values {
		if value == expected {
			return true
		}
	}
	return false
}

func alpnContains(extension []byte, expected string) bool {
	return containsString(parseALPNExtension(extension), expected)
}

func parseALPNExtension(extension []byte) []string {
	if len(extension) < 2 || int(binary.BigEndian.Uint16(extension[:2])) != len(extension)-2 {
		return nil
	}
	var protocols []string
	for offset := 2; offset < len(extension); {
		length := int(extension[offset])
		offset++
		if length == 0 || offset+length > len(extension) {
			return nil
		}
		protocols = append(protocols, string(extension[offset:offset+length]))
		offset += length
	}
	return protocols
}

func parseServerNameExtension(extension []byte) string {
	if len(extension) < 5 || int(binary.BigEndian.Uint16(extension[:2])) != len(extension)-2 {
		return ""
	}
	for offset := 2; offset+3 <= len(extension); {
		nameType := extension[offset]
		length := int(binary.BigEndian.Uint16(extension[offset+1 : offset+3]))
		offset += 3
		if offset+length > len(extension) {
			return ""
		}
		if nameType == 0 {
			return string(extension[offset : offset+length])
		}
		offset += length
	}
	return ""
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
