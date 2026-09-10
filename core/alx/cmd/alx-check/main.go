package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"time"

	"github.com/AetherLinkX/aetherlink-x/core/alx/client"
	"golang.org/x/net/proxy"
)

type report struct {
	Transport     string  `json:"transport"`
	URL           string  `json:"url"`
	Attempts      int     `json:"attempts"`
	Succeeded     int     `json:"succeeded"`
	Downloaded    int64   `json:"downloadedBytes"`
	ElapsedMS     int64   `json:"elapsedMs"`
	MegabitsPS    float64 `json:"megabitsPerSecond"`
	FirstByteMS   int64   `json:"firstByteMs"`
	RuntimeUpload uint64  `json:"runtimeUploadBytes"`
	RuntimeDown   uint64  `json:"runtimeDownloadBytes"`
	LastError     string  `json:"lastError,omitempty"`
}

func main() {
	configPath := flag.String("config", "", "path to ALX client JSON config")
	testURL := flag.String("url", "https://speed.cloudflare.com/__down?bytes=10485760", "HTTPS test object")
	attempts := flag.Int("attempts", 3, "number of requests")
	timeout := flag.Duration("timeout", 45*time.Second, "timeout per request")
	flag.Parse()
	if *configPath == "" {
		log.Fatal("-config is required")
	}
	raw, err := os.ReadFile(*configPath)
	if err != nil {
		log.Fatal(err)
	}
	config, err := client.ParseConfig(string(raw))
	if err != nil {
		log.Fatal(err)
	}
	runtime, err := client.Start(config)
	if err != nil {
		log.Fatalf("start ALX: %v", err)
	}
	defer runtime.Stop()

	dialer, err := proxy.SOCKS5("tcp", config.Listen, nil, proxy.Direct)
	if err != nil {
		log.Fatal(err)
	}
	transport := &http.Transport{
		Proxy:               nil,
		ForceAttemptHTTP2:   true,
		DisableCompression:  true,
		MaxIdleConns:        16,
		MaxIdleConnsPerHost: 8,
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			return dialer.(proxy.ContextDialer).DialContext(ctx, network, address)
		},
	}
	defer transport.CloseIdleConnections()
	result := report{URL: *testURL, Attempts: *attempts, FirstByteMS: -1}
	started := time.Now()
	for range max(*attempts, 1) {
		ctx, cancel := context.WithTimeout(context.Background(), *timeout)
		requestStarted := time.Now()
		request, requestErr := http.NewRequestWithContext(ctx, http.MethodGet, *testURL, nil)
		if requestErr == nil {
			response, responseErr := transport.RoundTrip(request)
			if responseErr == nil {
				if result.FirstByteMS < 0 {
					result.FirstByteMS = time.Since(requestStarted).Milliseconds()
				}
				n, copyErr := io.Copy(io.Discard, response.Body)
				_ = response.Body.Close()
				result.Downloaded += n
				if copyErr == nil && response.StatusCode >= 200 && response.StatusCode < 400 {
					result.Succeeded++
				} else if copyErr != nil {
					result.LastError = copyErr.Error()
				} else {
					result.LastError = response.Status
				}
			} else {
				result.LastError = responseErr.Error()
			}
		} else {
			result.LastError = requestErr.Error()
		}
		cancel()
	}
	result.ElapsedMS = time.Since(started).Milliseconds()
	if result.ElapsedMS > 0 {
		result.MegabitsPS = float64(result.Downloaded*8) / float64(result.ElapsedMS) / 1000
	}
	stats := runtime.Stats()
	result.Transport = stats.Transport
	result.RuntimeUpload = stats.BytesUp
	result.RuntimeDown = stats.BytesDown
	if result.LastError == "" {
		result.LastError = stats.LastError
	}
	encoded, _ := json.MarshalIndent(result, "", "  ")
	fmt.Println(string(encoded))
	if result.Succeeded == 0 {
		os.Exit(1)
	}
}
