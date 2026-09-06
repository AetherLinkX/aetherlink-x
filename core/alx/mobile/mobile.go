// Package alxmobile exposes the native ALX client through gomobile. It is
// intentionally a tiny lifecycle API so the same Go runtime can be linked into
// the Android Xray AAR without duplicating libgojni.so.
package alxmobile

import (
	"encoding/json"
	"errors"
	"sync"

	"github.com/AetherLinkX/aetherlink-x/core/alx/client"
)

var (
	mutex   sync.Mutex
	runtime *client.Runtime
)

func StartClient(configJSON string) error {
	config, err := client.ParseConfig(configJSON)
	if err != nil {
		return err
	}
	mutex.Lock()
	defer mutex.Unlock()
	if runtime != nil {
		return errors.New("ALX client is already running")
	}
	started, err := client.Start(config)
	if err != nil {
		return err
	}
	runtime = started
	return nil
}

func StopClient() {
	mutex.Lock()
	current := runtime
	runtime = nil
	mutex.Unlock()
	if current != nil {
		current.Stop()
	}
}

func IsRunning() bool {
	mutex.Lock()
	defer mutex.Unlock()
	return runtime != nil && runtime.Running()
}

func StatsJson() string {
	mutex.Lock()
	current := runtime
	mutex.Unlock()
	if current == nil {
		return `{"bytesUp":0,"bytesDown":0,"connections":0,"lastError":"","transport":"stopped"}`
	}
	encoded, err := json.Marshal(current.Stats())
	if err != nil {
		return `{"bytesUp":0,"bytesDown":0,"connections":0,"lastError":"stats unavailable","transport":"unknown"}`
	}
	return string(encoded)
}
