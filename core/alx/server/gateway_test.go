package server

import (
	"bufio"
	"crypto/tls"
	"net"
	"testing"
)

func TestClientHelloALPNRouting(t *testing.T) {
	for _, test := range []struct {
		name  string
		alpn  []string
		match bool
	}{
		{name: "native", alpn: []string{"alx/1"}, match: true},
		{name: "xray passthrough", alpn: []string{"h2", "http/1.1"}, match: false},
	} {
		t.Run(test.name, func(t *testing.T) {
			clientSide, serverSide := net.Pipe()
			defer clientSide.Close()
			defer serverSide.Close()
			go func() {
				client := tls.Client(clientSide, &tls.Config{
					ServerName:         "www.yahoo.com",
					NextProtos:         test.alpn,
					InsecureSkipVerify: true,
				})
				_ = client.Handshake()
			}()
			if actual := clientHelloHasALPN(bufio.NewReader(serverSide), "alx/1"); actual != test.match {
				t.Fatalf("match=%v, want %v", actual, test.match)
			}
		})
	}
}
