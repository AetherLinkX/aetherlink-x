package client

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/AetherLinkX/aetherlink-x/core/alx"
	quic "github.com/quic-go/quic-go"
)

type Config struct {
	Listen             string `json:"listen"`
	Server             string `json:"server"`
	FallbackServer     string `json:"fallbackServer"`
	TransportMode      string `json:"transportMode"`
	Token              string `json:"token"`
	CertificatePin     string `json:"certificatePin"`
	ServerName         string `json:"serverName"`
	HandshakeTimeoutMS int    `json:"handshakeTimeoutMs"`
	QUICProbeTimeoutMS int    `json:"quicProbeTimeoutMs"`
}

func ParseConfig(raw string) (Config, error) {
	var config Config
	if err := json.Unmarshal([]byte(raw), &config); err != nil {
		return config, fmt.Errorf("decode ALX config: %w", err)
	}
	if config.Listen == "" {
		config.Listen = "127.0.0.1:10808"
	}
	if config.Server == "" {
		return config, errors.New("ALX server is required")
	}
	if _, _, err := net.SplitHostPort(config.Server); err != nil {
		return config, fmt.Errorf("invalid ALX server: %w", err)
	}
	if config.FallbackServer != "" {
		if _, _, err := net.SplitHostPort(config.FallbackServer); err != nil {
			return config, fmt.Errorf("invalid ALX fallback server: %w", err)
		}
	}
	config.TransportMode = strings.ToLower(strings.TrimSpace(config.TransportMode))
	if config.TransportMode == "" {
		config.TransportMode = "auto"
	}
	switch config.TransportMode {
	case "auto", "quic", "tcp-first", "tls-tcp":
	default:
		return config, fmt.Errorf("invalid ALX transport mode %q", config.TransportMode)
	}
	if config.TransportMode == "tls-tcp" && config.FallbackServer == "" {
		return config, errors.New("ALX TLS/TCP transport requires a fallback server")
	}
	if _, err := alx.DecodeToken(config.Token); err != nil {
		return config, err
	}
	if _, err := normalizePin(config.CertificatePin); err != nil {
		return config, err
	}
	if config.ServerName == "" {
		config.ServerName = "www.yahoo.com"
	}
	if config.HandshakeTimeoutMS <= 0 {
		config.HandshakeTimeoutMS = 8000
	}
	if config.QUICProbeTimeoutMS <= 0 {
		config.QUICProbeTimeoutMS = 1500
	}
	return config, nil
}

type Runtime struct {
	config Config
	token  []byte
	ctx    context.Context
	cancel context.CancelFunc

	listener net.Listener
	manager  *connectionManager
	nextUDP  atomic.Uint32

	bytesUp     atomic.Uint64
	bytesDown   atomic.Uint64
	connections atomic.Uint64
	lastError   atomic.Value
	wait        sync.WaitGroup
}

type Stats struct {
	BytesUp     uint64 `json:"bytesUp"`
	BytesDown   uint64 `json:"bytesDown"`
	Connections uint64 `json:"connections"`
	LastError   string `json:"lastError"`
	Transport   string `json:"transport"`
}

func Start(config Config) (*Runtime, error) {
	token, err := alx.DecodeToken(config.Token)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	runtime := &Runtime{config: config, token: token, ctx: ctx, cancel: cancel}
	runtime.nextUDP.Store(100)
	runtime.manager = newConnectionManager(runtime)

	listener, err := net.Listen("tcp4", config.Listen)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("listen on local SOCKS endpoint: %w", err)
	}
	runtime.listener = listener

	probeCtx, probeCancel := context.WithTimeout(ctx, time.Duration(config.HandshakeTimeoutMS)*time.Millisecond)
	defer probeCancel()
	if err := runtime.manager.probe(probeCtx); err != nil {
		_ = listener.Close()
		cancel()
		return nil, fmt.Errorf("connect ALX server: %w", err)
	}
	runtime.lastError.Store("")

	runtime.wait.Add(1)
	go runtime.acceptLoop()
	return runtime, nil
}

func (r *Runtime) Stop() {
	r.cancel()
	if r.listener != nil {
		_ = r.listener.Close()
	}
	r.manager.close()
	r.wait.Wait()
}

func (r *Runtime) Running() bool {
	select {
	case <-r.ctx.Done():
		return false
	default:
		return r.listener != nil
	}
}

func (r *Runtime) Stats() Stats {
	lastError, _ := r.lastError.Load().(string)
	transport := "quic"
	if r.manager.fallback.Load() {
		transport = "tls-tcp"
	}
	return Stats{
		BytesUp:     r.bytesUp.Load(),
		BytesDown:   r.bytesDown.Load(),
		Connections: r.connections.Load(),
		LastError:   lastError,
		Transport:   transport,
	}
}

func (r *Runtime) setError(err error) {
	if err != nil && !errors.Is(err, context.Canceled) && !errors.Is(err, net.ErrClosed) {
		r.lastError.Store(err.Error())
	}
}

func (r *Runtime) acceptLoop() {
	defer r.wait.Done()
	for {
		connection, err := r.listener.Accept()
		if err != nil {
			r.setError(err)
			return
		}
		r.wait.Add(1)
		go func() {
			defer r.wait.Done()
			defer connection.Close()
			if err := r.handleSOCKS(connection); err != nil {
				r.setError(err)
			}
		}()
	}
}

func (r *Runtime) handleSOCKS(local net.Conn) error {
	reader := bufio.NewReader(local)
	if err := negotiateSOCKS(reader, local); err != nil {
		return err
	}
	command, destination, err := readSOCKSRequest(reader)
	if err != nil {
		return err
	}
	switch command {
	case 0x01:
		return r.handleTCP(local, reader, destination)
	case 0x03:
		return r.handleUDP(local)
	default:
		_ = writeSOCKSReply(local, 0x07, nil)
		return errors.New("unsupported SOCKS command")
	}
}

func (r *Runtime) handleTCP(local net.Conn, buffered *bufio.Reader, destination string) error {
	ctx, cancel := context.WithTimeout(r.ctx, time.Duration(r.config.HandshakeTimeoutMS)*time.Millisecond)
	defer cancel()
	stream, connection, err := r.manager.openStream(ctx)
	if err != nil {
		_ = writeSOCKSReply(local, 0x01, nil)
		return err
	}
	defer stream.Close()
	clearDeadline := setStreamDeadline(stream, ctx)
	defer clearDeadline()
	if err := alx.WriteOpen(stream, alx.OpenRequest{Command: alx.CommandTCP, Address: destination}); err != nil {
		if connection != nil {
			r.manager.invalidate(connection)
		}
		_ = writeSOCKSReply(local, 0x01, nil)
		return err
	}
	status := []byte{0xff}
	if _, err := io.ReadFull(stream, status); err != nil || status[0] != alx.StatusOK {
		_ = writeSOCKSReply(local, 0x05, nil)
		if err != nil {
			return err
		}
		return fmt.Errorf("ALX server rejected destination with status %d", status[0])
	}
	if err := writeSOCKSReply(local, 0x00, local.LocalAddr()); err != nil {
		return err
	}
	clearDeadline()
	r.connections.Add(1)
	return proxyTCP(local, buffered, stream, &r.bytesUp, &r.bytesDown)
}

func (r *Runtime) handleUDP(control net.Conn) error {
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = writeSOCKSReply(control, 0x01, nil)
		return err
	}
	defer udp.Close()
	if err := writeSOCKSReply(control, 0x00, udp.LocalAddr()); err != nil {
		return err
	}

	ctx, cancel := context.WithCancel(r.ctx)
	defer cancel()
	association := &udpAssociation{
		id:      r.nextUDP.Add(1),
		local:   udp,
		ctx:     ctx,
		cancel:  cancel,
		runtime: r,
	}
	if err := r.manager.registerUDP(association); err != nil {
		return err
	}
	defer r.manager.unregisterUDP(association.id)
	r.connections.Add(1)

	errorChannel := make(chan error, 2)
	go func() { errorChannel <- association.readLocal() }()
	go func() { errorChannel <- association.readReliable() }()

	// SOCKS5 keeps the TCP control connection open for the lifetime of UDP ASSOCIATE.
	go func() {
		one := make([]byte, 1)
		_, readErr := control.Read(one)
		if readErr != nil {
			errorChannel <- readErr
		}
	}()
	select {
	case <-r.ctx.Done():
		return r.ctx.Err()
	case err := <-errorChannel:
		return err
	}
}

type connectionManager struct {
	runtime      *Runtime
	mutex        sync.Mutex
	conn         *quic.Conn
	fallback     atomic.Bool
	sessionCache tls.ClientSessionCache
	udpMu        sync.RWMutex
	udp          map[uint32]*udpAssociation
}

func newConnectionManager(runtime *Runtime) *connectionManager {
	return &connectionManager{
		runtime:      runtime,
		sessionCache: tls.NewLRUClientSessionCache(64),
		udp:          make(map[uint32]*udpAssociation),
	}
}

func (m *connectionManager) probe(ctx context.Context) error {
	if m.runtime.config.TransportMode == "tls-tcp" || m.runtime.config.TransportMode == "tcp-first" {
		connection, err := m.dialFallback(ctx)
		if err == nil {
			m.fallback.Store(true)
			return connection.Close()
		}
		if m.runtime.config.TransportMode == "tls-tcp" {
			return fmt.Errorf("ALX TLS/TCP transport unavailable: %w", err)
		}
		// TCP-first is adaptive: QUIC remains a working fallback on networks
		// where the TCP endpoint is filtered or unavailable.
	}
	quicCtx, cancel := context.WithTimeout(ctx, time.Duration(m.runtime.config.QUICProbeTimeoutMS)*time.Millisecond)
	defer cancel()
	if _, err := m.connection(quicCtx); err == nil {
		return nil
	} else if m.runtime.config.FallbackServer == "" {
		return err
	}
	connection, err := m.dialFallback(ctx)
	if err != nil {
		return fmt.Errorf("QUIC unavailable and TCP fallback failed: %w", err)
	}
	m.fallback.Store(true)
	return connection.Close()
}

func (m *connectionManager) openStream(ctx context.Context) (io.ReadWriteCloser, *quic.Conn, error) {
	if m.fallback.Load() {
		connection, err := m.dialFallback(ctx)
		return connection, nil, err
	}
	connection, err := m.connection(ctx)
	if err != nil {
		if m.runtime.config.FallbackServer == "" {
			return nil, nil, err
		}
		fallback, fallbackErr := m.dialFallback(ctx)
		if fallbackErr != nil {
			return nil, nil, fmt.Errorf("QUIC unavailable and TCP fallback failed: %w", fallbackErr)
		}
		m.fallback.Store(true)
		return fallback, nil, nil
	}
	stream, err := connection.OpenStreamSync(ctx)
	if err != nil {
		m.invalidate(connection)
	}
	return stream, connection, err
}

func (m *connectionManager) connection(ctx context.Context) (*quic.Conn, error) {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	if m.conn != nil && m.conn.Context().Err() == nil {
		return m.conn, nil
	}
	tlsConfig, err := m.tlsConfig([]string{"h3"})
	if err != nil {
		return nil, err
	}
	quicConfig := &quic.Config{
		HandshakeIdleTimeout:           time.Duration(m.runtime.config.HandshakeTimeoutMS) * time.Millisecond,
		MaxIdleTimeout:                 30 * time.Second,
		KeepAlivePeriod:                10 * time.Second,
		EnableDatagrams:                true,
		MaxIncomingStreams:             256,
		InitialStreamReceiveWindow:     1 << 20,
		MaxStreamReceiveWindow:         8 << 20,
		InitialConnectionReceiveWindow: 4 << 20,
		MaxConnectionReceiveWindow:     32 << 20,
	}
	connection, err := quic.DialAddr(ctx, m.runtime.config.Server, tlsConfig, quicConfig)
	if err != nil {
		return nil, err
	}
	authStream, err := connection.OpenStreamSync(ctx)
	if err != nil {
		_ = connection.CloseWithError(1, "auth stream failed")
		return nil, err
	}
	clearDeadline := setStreamDeadline(authStream, ctx)
	defer clearDeadline()
	if err := alx.WriteAuth(authStream, m.runtime.token, time.Now()); err != nil {
		_ = connection.CloseWithError(1, "auth write failed")
		return nil, err
	}
	status := []byte{0xff}
	if _, err := io.ReadFull(authStream, status); err != nil || status[0] != alx.StatusOK {
		_ = connection.CloseWithError(2, "authentication rejected")
		if err != nil {
			return nil, err
		}
		return nil, errors.New("ALX authentication rejected")
	}
	_ = authStream.Close()
	m.conn = connection
	go m.receiveDatagrams(connection)
	go m.monitorConnection(connection)
	return connection, nil
}

func (m *connectionManager) monitorConnection(connection *quic.Conn) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-m.runtime.ctx.Done():
			return
		case <-connection.Context().Done():
			m.invalidate(connection)
			return
		case <-ticker.C:
			ctx, cancel := context.WithTimeout(m.runtime.ctx, 4*time.Second)
			stream, err := connection.OpenStreamSync(ctx)
			if err == nil {
				clearDeadline := setStreamDeadline(stream, ctx)
				err = alx.WriteOpen(stream, alx.OpenRequest{Command: alx.CommandPing})
				status := []byte{0xff}
				if err == nil {
					_, err = io.ReadFull(stream, status)
				}
				if err == nil && status[0] != alx.StatusOK {
					err = fmt.Errorf("ALX heartbeat rejected with status %d", status[0])
				}
				clearDeadline()
				_ = stream.Close()
			}
			cancel()
			if err != nil {
				m.runtime.setError(fmt.Errorf("ALX heartbeat failed: %w", err))
				m.invalidate(connection)
				return
			}
		}
	}
}

func (m *connectionManager) dialFallback(ctx context.Context) (*tls.Conn, error) {
	tlsConfig, err := m.tlsConfig([]string{"alx/1"})
	if err != nil {
		return nil, err
	}
	dialer := &tls.Dialer{Config: tlsConfig, NetDialer: &net.Dialer{Timeout: time.Duration(m.runtime.config.HandshakeTimeoutMS) * time.Millisecond, KeepAlive: 30 * time.Second}}
	raw, err := dialer.DialContext(ctx, "tcp", m.runtime.config.FallbackServer)
	if err != nil {
		return nil, err
	}
	connection := raw.(*tls.Conn)
	if err := alx.WriteAuth(connection, m.runtime.token, time.Now()); err != nil {
		_ = connection.Close()
		return nil, err
	}
	status := []byte{0xff}
	if _, err := io.ReadFull(connection, status); err != nil || status[0] != alx.StatusOK {
		_ = connection.Close()
		if err != nil {
			return nil, err
		}
		return nil, errors.New("ALX fallback authentication rejected")
	}
	return connection, nil
}

func (m *connectionManager) tlsConfig(nextProtocols []string) (*tls.Config, error) {
	pin, err := normalizePin(m.runtime.config.CertificatePin)
	if err != nil {
		return nil, err
	}
	return &tls.Config{
		MinVersion:         tls.VersionTLS13,
		ServerName:         m.runtime.config.ServerName,
		NextProtos:         nextProtocols,
		ClientSessionCache: m.sessionCache,
		InsecureSkipVerify: true, // Replaced by mandatory SPKI verification below.
		VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return errors.New("ALX server sent no certificate")
			}
			certificate, err := x509.ParseCertificate(rawCerts[0])
			if err != nil {
				return err
			}
			actual := sha256.Sum256(certificate.RawSubjectPublicKeyInfo)
			if !constantEqual(actual[:], pin) {
				return errors.New("ALX server certificate pin mismatch")
			}
			return nil
		},
	}, nil
}

func (m *connectionManager) invalidate(connection *quic.Conn) {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	if m.conn == connection {
		_ = m.conn.CloseWithError(3, "reconnecting")
		m.conn = nil
	}
}

func (m *connectionManager) close() {
	m.mutex.Lock()
	if m.conn != nil {
		_ = m.conn.CloseWithError(0, "client stopped")
		m.conn = nil
	}
	m.mutex.Unlock()
	m.udpMu.Lock()
	for _, association := range m.udp {
		association.cancel()
		_ = association.local.Close()
		association.closeTransport()
	}
	clear(m.udp)
	m.udpMu.Unlock()
}

func (m *connectionManager) registerUDP(association *udpAssociation) error {
	ctx, cancel := context.WithTimeout(association.ctx, time.Duration(m.runtime.config.HandshakeTimeoutMS)*time.Millisecond)
	defer cancel()
	stream, connection, err := m.openUDPTransport(ctx, association.id)
	if err != nil {
		return err
	}
	association.replaceTransport(connection, stream)
	m.udpMu.Lock()
	m.udp[association.id] = association
	m.udpMu.Unlock()
	return nil
}

func (m *connectionManager) openUDPTransport(ctx context.Context, id uint32) (io.ReadWriteCloser, *quic.Conn, error) {
	stream, connection, err := m.openStream(ctx)
	if err != nil {
		return nil, nil, err
	}
	clearDeadline := setStreamDeadline(stream, ctx)
	defer clearDeadline()
	if err := alx.WriteOpen(stream, alx.OpenRequest{Command: alx.CommandUDP, AssociationID: id}); err != nil {
		_ = stream.Close()
		if connection != nil {
			m.invalidate(connection)
		}
		return nil, nil, err
	}
	status := []byte{0xff}
	if _, err := io.ReadFull(stream, status); err != nil || status[0] != alx.StatusOK {
		_ = stream.Close()
		if connection != nil {
			m.invalidate(connection)
		}
		if err != nil {
			return nil, nil, err
		}
		return nil, nil, errors.New("ALX UDP association rejected")
	}
	clearDeadline()
	return stream, connection, nil
}

type deadlineStream interface {
	SetDeadline(time.Time) error
}

func setStreamDeadline(stream io.ReadWriteCloser, ctx context.Context) func() {
	deadline, hasDeadline := ctx.Deadline()
	deadlineWriter, supported := stream.(deadlineStream)
	if !hasDeadline || !supported {
		return func() {}
	}
	_ = deadlineWriter.SetDeadline(deadline)
	return func() { _ = deadlineWriter.SetDeadline(time.Time{}) }
}

func (m *connectionManager) unregisterUDP(id uint32) {
	m.udpMu.Lock()
	association := m.udp[id]
	delete(m.udp, id)
	m.udpMu.Unlock()
	if association != nil {
		association.cancel()
		association.closeTransport()
	}
}

func (m *connectionManager) receiveDatagrams(connection *quic.Conn) {
	for {
		raw, err := connection.ReceiveDatagram(m.runtime.ctx)
		if err != nil {
			if connection.Context().Err() != nil {
				m.invalidate(connection)
			}
			return
		}
		datagram, err := alx.DecodeDatagram(raw)
		if err != nil {
			continue
		}
		m.udpMu.RLock()
		association := m.udp[datagram.AssociationID]
		m.udpMu.RUnlock()
		if association != nil {
			association.deliver(datagram.Address, datagram.Payload)
		}
	}
}

type udpAssociation struct {
	id          uint32
	local       *net.UDPConn
	clientAddr  *net.UDPAddr
	ctx         context.Context
	cancel      context.CancelFunc
	runtime     *Runtime
	connection  *quic.Conn
	stream      io.ReadWriteCloser
	transportMu sync.RWMutex
	reconnectMu sync.Mutex
	writeMu     sync.Mutex
	clientMu    sync.RWMutex
}

func (a *udpAssociation) readLocal() error {
	buffer := make([]byte, alx.MaxUDPPayload+alx.MaxAddressLength+32)
	for {
		n, sender, err := a.local.ReadFromUDP(buffer)
		if err != nil {
			return err
		}
		target, payload, err := parseSOCKSUDP(buffer[:n])
		if err != nil {
			continue
		}
		a.clientMu.Lock()
		a.clientAddr = sender
		a.clientMu.Unlock()
		a.runtime.bytesUp.Add(uint64(len(payload)))
		if err := a.send(target, payload); err != nil {
			return err
		}
	}
}

func (a *udpAssociation) readReliable() error {
	for {
		_, stream := a.transport()
		if stream == nil {
			if err := a.reconnect(nil); err != nil {
				a.runtime.setError(err)
				if !a.waitRetry() {
					return a.ctx.Err()
				}
				continue
			}
			continue
		}
		address, payload, err := alx.ReadUDPFrame(stream)
		if err != nil {
			a.runtime.setError(err)
			if reconnectErr := a.reconnect(stream); reconnectErr != nil {
				a.runtime.setError(reconnectErr)
				if !a.waitRetry() {
					return a.ctx.Err()
				}
			}
			continue
		}
		a.deliver(address, payload)
	}
}

func (a *udpAssociation) send(address string, payload []byte) error {
	datagram, encodeErr := alx.EncodeDatagram(alx.Datagram{AssociationID: a.id, Address: address, Payload: payload})
	for {
		if err := a.ctx.Err(); err != nil {
			return err
		}
		connection, stream := a.transport()
		if connection != nil && encodeErr == nil && len(datagram) <= alx.FastDatagramLimit {
			if err := connection.SendDatagram(datagram); err == nil {
				return nil
			}
		}
		var writeErr error
		if stream != nil {
			a.writeMu.Lock()
			writeErr = alx.WriteUDPFrame(stream, address, payload)
			a.writeMu.Unlock()
			if writeErr == nil {
				return nil
			}
			a.runtime.setError(writeErr)
		}
		if err := a.reconnect(stream); err != nil {
			a.runtime.setError(err)
			if !a.waitRetry() {
				return a.ctx.Err()
			}
		}
	}
}

func (a *udpAssociation) reconnect(failed io.ReadWriteCloser) error {
	a.reconnectMu.Lock()
	defer a.reconnectMu.Unlock()
	if err := a.ctx.Err(); err != nil {
		return err
	}
	_, current := a.transport()
	if current != nil && current != failed {
		return nil
	}
	ctx, cancel := context.WithTimeout(a.ctx, time.Duration(a.runtime.config.HandshakeTimeoutMS)*time.Millisecond)
	defer cancel()
	stream, connection, err := a.runtime.manager.openUDPTransport(ctx, a.id)
	if err != nil {
		return fmt.Errorf("restore ALX UDP association: %w", err)
	}
	a.replaceTransport(connection, stream)
	a.runtime.lastError.Store("")
	return nil
}

func (a *udpAssociation) transport() (*quic.Conn, io.ReadWriteCloser) {
	a.transportMu.RLock()
	defer a.transportMu.RUnlock()
	return a.connection, a.stream
}

func (a *udpAssociation) replaceTransport(connection *quic.Conn, stream io.ReadWriteCloser) {
	a.transportMu.Lock()
	previous := a.stream
	a.connection = connection
	a.stream = stream
	a.transportMu.Unlock()
	if previous != nil && previous != stream {
		_ = previous.Close()
	}
}

func (a *udpAssociation) closeTransport() {
	a.transportMu.Lock()
	stream := a.stream
	a.connection = nil
	a.stream = nil
	a.transportMu.Unlock()
	if stream != nil {
		_ = stream.Close()
	}
}

func (a *udpAssociation) waitRetry() bool {
	select {
	case <-a.ctx.Done():
		return false
	case <-time.After(250 * time.Millisecond):
		return true
	}
}

func (a *udpAssociation) deliver(address string, payload []byte) {
	a.clientMu.RLock()
	clientAddr := a.clientAddr
	a.clientMu.RUnlock()
	if clientAddr == nil {
		return
	}
	packet, err := buildSOCKSUDP(address, payload)
	if err != nil {
		return
	}
	if _, err := a.local.WriteToUDP(packet, clientAddr); err == nil {
		a.runtime.bytesDown.Add(uint64(len(payload)))
	}
}

func negotiateSOCKS(reader *bufio.Reader, writer io.Writer) error {
	header := make([]byte, 2)
	if _, err := io.ReadFull(reader, header); err != nil {
		return err
	}
	if header[0] != 5 || header[1] == 0 {
		return errors.New("invalid SOCKS greeting")
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(reader, methods); err != nil {
		return err
	}
	accepted := false
	for _, method := range methods {
		if method == 0 {
			accepted = true
			break
		}
	}
	if !accepted {
		_, _ = writer.Write([]byte{5, 0xff})
		return errors.New("SOCKS client does not support no-auth")
	}
	_, err := writer.Write([]byte{5, 0})
	return err
}

func readSOCKSRequest(reader io.Reader) (byte, string, error) {
	header := make([]byte, 4)
	if _, err := io.ReadFull(reader, header); err != nil {
		return 0, "", err
	}
	if header[0] != 5 || header[2] != 0 {
		return 0, "", errors.New("invalid SOCKS request")
	}
	host, err := readSOCKSAddress(reader, header[3])
	if err != nil {
		return 0, "", err
	}
	port := make([]byte, 2)
	if _, err := io.ReadFull(reader, port); err != nil {
		return 0, "", err
	}
	return header[1], net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(port)))), nil
}

func readSOCKSAddress(reader io.Reader, addressType byte) (string, error) {
	switch addressType {
	case 1:
		address := make([]byte, 4)
		_, err := io.ReadFull(reader, address)
		return net.IP(address).String(), err
	case 4:
		address := make([]byte, 16)
		_, err := io.ReadFull(reader, address)
		return net.IP(address).String(), err
	case 3:
		length := []byte{0}
		if _, err := io.ReadFull(reader, length); err != nil {
			return "", err
		}
		if length[0] == 0 {
			return "", errors.New("empty SOCKS domain")
		}
		address := make([]byte, int(length[0]))
		_, err := io.ReadFull(reader, address)
		return string(address), err
	default:
		return "", errors.New("unsupported SOCKS address type")
	}
}

func writeSOCKSReply(writer io.Writer, status byte, address net.Addr) error {
	ip := net.IPv4zero
	port := 0
	if tcp, ok := address.(*net.TCPAddr); ok {
		ip, port = tcp.IP, tcp.Port
	} else if udp, ok := address.(*net.UDPAddr); ok {
		ip, port = udp.IP, udp.Port
	}
	if v4 := ip.To4(); v4 != nil {
		response := []byte{5, status, 0, 1, v4[0], v4[1], v4[2], v4[3], 0, 0}
		binary.BigEndian.PutUint16(response[8:10], uint16(port))
		_, err := writer.Write(response)
		return err
	}
	response := make([]byte, 4+16+2)
	copy(response[:4], []byte{5, status, 0, 4})
	copy(response[4:20], ip.To16())
	binary.BigEndian.PutUint16(response[20:22], uint16(port))
	_, err := writer.Write(response)
	return err
}

func parseSOCKSUDP(packet []byte) (string, []byte, error) {
	if len(packet) < 7 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0 {
		return "", nil, errors.New("invalid SOCKS UDP packet")
	}
	reader := bytesReader(packet[4:])
	host, err := readSOCKSAddress(&reader, packet[3])
	if err != nil {
		return "", nil, err
	}
	port := make([]byte, 2)
	if _, err := io.ReadFull(&reader, port); err != nil {
		return "", nil, err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(port)))), append([]byte(nil), reader...), nil
}

func buildSOCKSUDP(address string, payload []byte) ([]byte, error) {
	host, portRaw, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portRaw)
	if err != nil || port < 0 || port > 65535 {
		return nil, errors.New("invalid UDP source port")
	}
	result := []byte{0, 0, 0}
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			result = append(result, 1)
			result = append(result, v4...)
		} else {
			result = append(result, 4)
			result = append(result, ip.To16()...)
		}
	} else {
		if len(host) > 255 {
			return nil, errors.New("UDP source host is too long")
		}
		result = append(result, 3, byte(len(host)))
		result = append(result, host...)
	}
	portBytes := []byte{0, 0}
	binary.BigEndian.PutUint16(portBytes, uint16(port))
	result = append(result, portBytes...)
	return append(result, payload...), nil
}

type bytesReader []byte

func (r *bytesReader) Read(p []byte) (int, error) {
	if len(*r) == 0 {
		return 0, io.EOF
	}
	n := copy(p, *r)
	*r = (*r)[n:]
	return n, nil
}

type countingWriter struct {
	writer io.Writer
	count  *atomic.Uint64
}

func (w countingWriter) Write(data []byte) (int, error) {
	n, err := w.writer.Write(data)
	w.count.Add(uint64(n))
	return n, err
}

func proxyTCP(local net.Conn, buffered *bufio.Reader, remote io.ReadWriteCloser, up, down *atomic.Uint64) error {
	errorsChannel := make(chan error, 2)
	go func() {
		_, err := io.Copy(countingWriter{writer: remote, count: up}, buffered)
		_ = remote.Close()
		errorsChannel <- err
	}()
	go func() {
		_, err := io.Copy(countingWriter{writer: local, count: down}, remote)
		errorsChannel <- err
	}()
	first := <-errorsChannel
	if first != nil && !errors.Is(first, net.ErrClosed) {
		return first
	}
	return nil
}

func normalizePin(value string) ([]byte, error) {
	value = strings.TrimSpace(strings.TrimPrefix(value, "sha256/"))
	if decoded, err := hex.DecodeString(strings.ReplaceAll(value, ":", "")); err == nil && len(decoded) == sha256.Size {
		return decoded, nil
	}
	for _, encoding := range []*base64.Encoding{base64.RawStdEncoding, base64.StdEncoding, base64.RawURLEncoding, base64.URLEncoding} {
		if decoded, err := encoding.DecodeString(value); err == nil && len(decoded) == sha256.Size {
			return decoded, nil
		}
	}
	return nil, errors.New("ALX certificate pin must be a SHA-256 SPKI hash")
}

func constantEqual(left, right []byte) bool {
	if len(left) != len(right) {
		return false
	}
	var difference byte
	for index := range left {
		difference |= left[index] ^ right[index]
	}
	return difference == 0
}
