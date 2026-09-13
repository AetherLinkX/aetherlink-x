package server

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"testing"
)

func TestRuntimeConfigRotatesNativeTokens(t *testing.T) {
	initial := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	replacementRaw := make([]byte, 32)
	replacementRaw[0] = 1
	replacement := base64.RawURLEncoding.EncodeToString(replacementRaw)
	path := filepath.Join(t.TempDir(), "panel-runtime.json")

	instance, err := New(Config{
		Listen:            ":443",
		TCPListen:         ":443",
		CertFile:          "unused.crt",
		KeyFile:           "unused.key",
		Token:             initial,
		RuntimeConfigFile: path,
	})
	if err != nil {
		t.Fatal(err)
	}
	writeRuntimeTestFile(t, path, `{"version":1,"enabled":true,"listen":":443","tcpListen":":443","token":"`+replacement+`","previousTokens":[]}`)
	if err := instance.reloadRuntimeConfig(); err != nil {
		t.Fatal(err)
	}
	if len(instance.tokens) != 1 || instance.tokens[0][0] != 1 {
		t.Fatal("runtime token was not applied")
	}

	writeRuntimeTestFile(t, path, `{"version":1,"enabled":false,"listen":":443","tcpListen":":443"}`)
	if err := instance.reloadRuntimeConfig(); err != nil {
		t.Fatal(err)
	}
	if len(instance.tokens) != 0 {
		t.Fatal("disabled runtime profile must reject every token")
	}
}

func TestRuntimeConfigRejectsListenerMismatch(t *testing.T) {
	token := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	path := filepath.Join(t.TempDir(), "panel-runtime.json")
	instance, err := New(Config{
		Listen:            ":443",
		TCPListen:         ":443",
		CertFile:          "unused.crt",
		KeyFile:           "unused.key",
		Token:             token,
		RuntimeConfigFile: path,
	})
	if err != nil {
		t.Fatal(err)
	}
	writeRuntimeTestFile(t, path, `{"version":1,"enabled":true,"listen":":8443","tcpListen":":443","token":"`+token+`"}`)
	if err := instance.reloadRuntimeConfig(); err == nil {
		t.Fatal("listener mismatch must be rejected")
	}
}

func writeRuntimeTestFile(t *testing.T, path, value string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(value), 0o600); err != nil {
		t.Fatal(err)
	}
}
