package client

import (
	"strings"
	"testing"
)

const validTestToken = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

func TestParseConfigTransportModes(t *testing.T) {
	for _, testCase := range []struct {
		name     string
		mode     string
		fallback string
		want     string
	}{
		{name: "default", fallback: "example.com:443", want: "auto"},
		{name: "quic", mode: "QUIC", want: "quic"},
		{name: "tcp first", mode: " tcp-first ", fallback: "example.com:443", want: "tcp-first"},
		{name: "tcp only", mode: "tls-tcp", fallback: "example.com:443", want: "tls-tcp"},
		{name: "turbo", mode: "turbo", fallback: "example.com:443", want: "turbo"},
		{name: "turbo", mode: "turbo", fallback: "example.com:443", want: "turbo"},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			raw := `{"server":"example.com:443","fallbackServer":"` + testCase.fallback + `","transportMode":"` + testCase.mode + `","token":"` + validTestToken + `","certificatePin":"` + strings.Repeat("00", 32) + `"}`
			config, err := ParseConfig(raw)
			if err != nil {
				t.Fatalf("ParseConfig() error = %v", err)
			}
			if config.TransportMode != testCase.want {
				t.Fatalf("TransportMode = %q, want %q", config.TransportMode, testCase.want)
			}
		})
	}
}

func TestParseConfigRejectsInvalidTransportMode(t *testing.T) {
	raw := `{"server":"example.com:443","transportMode":"warp-speed","token":"` + validTestToken + `","certificatePin":"` + strings.Repeat("00", 32) + `"}`
	if _, err := ParseConfig(raw); err == nil {
		t.Fatal("ParseConfig() accepted an invalid transport mode")
	}
}

func TestParseConfigRequiresFallbackForTLSOnly(t *testing.T) {
	raw := `{"server":"example.com:443","transportMode":"tls-tcp","token":"` + validTestToken + `","certificatePin":"` + strings.Repeat("00", 32) + `"}`
	if _, err := ParseConfig(raw); err == nil {
		t.Fatal("ParseConfig() accepted tls-tcp without a fallback endpoint")
	}
}

func TestParseConfigRequiresFallbackForTurbo(t *testing.T) {
	raw := `{"server":"example.com:443","transportMode":"turbo","token":"` + validTestToken + `","certificatePin":"` + strings.Repeat("00", 32) + `"}`
	if _, err := ParseConfig(raw); err == nil {
		t.Fatal("ParseConfig() accepted turbo without a TCP endpoint")
	}
}
