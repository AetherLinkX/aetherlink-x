package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/AetherLinkX/aetherlink-x/core/alx/server"
)

func main() {
	listen := flag.String("listen", envOr("ALX_LISTEN", ":443"), "UDP listen address")
	tcpListen := flag.String("tcp-listen", envOr("ALX_TCP_LISTEN", ":8443"), "TCP fallback listen address")
	tcpDefaultBackend := flag.String("tcp-default-backend", os.Getenv("ALX_TCP_DEFAULT_BACKEND"), "non-ALX TLS passthrough backend")
	cert := flag.String("cert", envOr("ALX_CERT_FILE", "/etc/aetherlink-x/server.crt"), "TLS certificate")
	key := flag.String("key", envOr("ALX_KEY_FILE", "/etc/aetherlink-x/server.key"), "TLS private key")
	token := flag.String("token", os.Getenv("ALX_TOKEN"), "pre-shared client token")
	flag.Parse()

	instance, err := server.New(server.Config{
		Listen:            *listen,
		TCPListen:         *tcpListen,
		TCPDefaultBackend: *tcpDefaultBackend,
		CertFile:          *cert,
		KeyFile:           *key,
		Token:             *token,
	})
	if err != nil {
		log.Fatal(err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if err := instance.Run(ctx); err != nil {
		log.Fatal(err)
	}
}

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
