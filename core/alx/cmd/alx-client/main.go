package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/AetherLinkX/aetherlink-x/core/alx/client"
)

func main() {
	configPath := flag.String("config", "", "path to the ALX client JSON config")
	flag.Parse()
	if *configPath == "" {
		log.Fatal("-config is required")
	}

	raw, err := os.ReadFile(*configPath)
	if err != nil {
		log.Fatalf("read config: %v", err)
	}
	config, err := client.ParseConfig(string(raw))
	if err != nil {
		log.Fatalf("parse config: %v", err)
	}
	runtime, err := client.Start(config)
	if err != nil {
		log.Fatalf("start ALX client: %v", err)
	}
	defer runtime.Stop()

	fmt.Printf("AetherLink Native SOCKS endpoint is ready on %s\n", config.Listen)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()
}
