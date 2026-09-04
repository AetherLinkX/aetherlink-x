package aetherlinkx

import (
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
)

// clientDiagnosticState exposes protocol-stage counters to embedders such as
// AndroidLibXrayLite.  It deliberately contains no address, user id, key or
// target information, so a user can safely attach the snapshot to a bug report.
type clientDiagnosticState struct {
	connections  atomic.Uint64
	dialOK       atomic.Uint64
	clientInitOK atomic.Uint64
	serverAccept atomic.Uint64
	tcpReady     atomic.Uint64
	udpReady     atomic.Uint64
	failures     atomic.Uint64

	mu        sync.Mutex
	lastStage string
	lastError string
}

var clientDiagnostics clientDiagnosticState

func markClientStage(stage string) {
	switch stage {
	case "process":
		clientDiagnostics.connections.Add(1)
	case "dial":
		clientDiagnostics.dialOK.Add(1)
	case "client-init":
		clientDiagnostics.clientInitOK.Add(1)
	case "server-accept":
		clientDiagnostics.serverAccept.Add(1)
	case "tcp-ready":
		clientDiagnostics.tcpReady.Add(1)
	case "udp-ready":
		clientDiagnostics.udpReady.Add(1)
	}
	clientDiagnostics.mu.Lock()
	clientDiagnostics.lastStage = stage
	clientDiagnostics.mu.Unlock()
}

func markClientError(stage string, err error) {
	clientDiagnostics.failures.Add(1)
	message := "unknown"
	if err != nil {
		message = strings.NewReplacer("\n", " ", "\r", " ", ";", ",").Replace(err.Error())
		if len(message) > 240 {
			message = message[:240]
		}
	}
	clientDiagnostics.mu.Lock()
	clientDiagnostics.lastStage = stage
	clientDiagnostics.lastError = message
	clientDiagnostics.mu.Unlock()
}

// ClientDiagnostics returns a stable, credential-free snapshot.  Counters are
// process-lifetime totals, allowing Android to prove whether a new connection
// reached the transport, authenticated ALX, or entered the payload phase.
func ClientDiagnostics() string {
	clientDiagnostics.mu.Lock()
	stage := clientDiagnostics.lastStage
	lastError := clientDiagnostics.lastError
	clientDiagnostics.mu.Unlock()
	if stage == "" {
		stage = "idle"
	}
	if lastError == "" {
		lastError = "none"
	}
	return fmt.Sprintf(
		"connections=%d,dial_ok=%d,client_init=%d,server_accept=%d,tcp_ready=%d,udp_ready=%d,failures=%d,last_stage=%s,last_error=%s",
		clientDiagnostics.connections.Load(),
		clientDiagnostics.dialOK.Load(),
		clientDiagnostics.clientInitOK.Load(),
		clientDiagnostics.serverAccept.Load(),
		clientDiagnostics.tcpReady.Load(),
		clientDiagnostics.udpReady.Load(),
		clientDiagnostics.failures.Load(),
		stage,
		lastError,
	)
}
