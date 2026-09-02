package aetherlinkx

import (
	"math"
	"time"

	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/net"
)

const (
	defaultDestinationCache    = 64
	defaultMaxUDPPayload       = 8192
	maxDestinationCache        = 1024
)

// TurboSettings is the negotiated per-connection data-plane configuration.
// Transport sockopts are applied by infra/conf and are intentionally not part
// of this structure.
type TurboSettings struct {
	Enabled              bool
	MaxDatagramAge       time.Duration
	DestinationCacheSize uint16
	MaxUDPPayload        uint16
}

type destinationCache struct {
	entries []net.Destination
	keys    map[string]uint16
	next    uint16
}

func newDestinationCache(size uint16) *destinationCache {
	return &destinationCache{
		entries: make([]net.Destination, int(size)+1),
		keys:    make(map[string]uint16, size),
		next:    1,
	}
}

func (c *destinationCache) lookup(destination net.Destination) (uint16, bool) {
	if len(c.entries) <= 1 {
		return 0, false
	}
	id, found := c.keys[destination.String()]
	return id, found
}

func (c *destinationCache) reserve(destination net.Destination) uint16 {
	if len(c.entries) <= 1 {
		return 0
	}
	id := c.next
	if previous := c.entries[id]; previous.IsValid() && previous.Address != nil {
		delete(c.keys, previous.String())
	}
	c.entries[id] = destination
	c.keys[destination.String()] = id
	c.next++
	if int(c.next) >= len(c.entries) {
		c.next = 1
	}
	return id
}

func (c *destinationCache) store(id uint16, destination net.Destination) bool {
	if id == 0 || int(id) >= len(c.entries) {
		return false
	}
	if previous := c.entries[id]; previous.IsValid() && previous.Address != nil {
		delete(c.keys, previous.String())
	}
	c.entries[id] = destination
	c.keys[destination.String()] = id
	return true
}

func (c *destinationCache) get(id uint16) (net.Destination, bool) {
	if id == 0 || int(id) >= len(c.entries) {
		return net.Destination{}, false
	}
	destination := c.entries[id]
	return destination, destination.IsValid() && destination.Address != nil
}

func normalizeTurbo(config *TurboConfig) TurboSettings {
	if config == nil || !config.Enabled {
		return TurboSettings{MaxUDPPayload: defaultMaxUDPPayload}
	}
	age := time.Duration(config.MaxDatagramAgeMs) * time.Millisecond
	cacheSize := config.DestinationCacheSize
	if cacheSize == 0 {
		cacheSize = defaultDestinationCache
	}
	if cacheSize > maxDestinationCache {
		cacheSize = maxDestinationCache
	}
	maxPayload := config.MaxUdpPayload
	if maxPayload == 0 {
		maxPayload = defaultMaxUDPPayload
	}
	if maxPayload < 512 {
		maxPayload = 512
	}
	if maxPayload > uint32(buf.Size) {
		maxPayload = uint32(buf.Size)
	}
	return TurboSettings{
		Enabled:              true,
		MaxDatagramAge:       age,
		DestinationCacheSize: uint16(cacheSize),
		MaxUDPPayload:        uint16(maxPayload),
	}
}

func negotiateTurbo(requested, available TurboSettings) TurboSettings {
	if !requested.Enabled || !available.Enabled {
		return TurboSettings{MaxUDPPayload: defaultMaxUDPPayload}
	}
	selected := requested
	// Zero explicitly disables deadline drops. Reliability takes precedence if
	// either peer disables this optional lossy optimisation.
	if requested.MaxDatagramAge == 0 || available.MaxDatagramAge == 0 {
		selected.MaxDatagramAge = 0
	} else if available.MaxDatagramAge < selected.MaxDatagramAge {
		selected.MaxDatagramAge = available.MaxDatagramAge
	}
	if available.DestinationCacheSize < selected.DestinationCacheSize {
		selected.DestinationCacheSize = available.DestinationCacheSize
	}
	if available.MaxUDPPayload < selected.MaxUDPPayload {
		selected.MaxUDPPayload = available.MaxUDPPayload
	}
	return selected
}

func durationMillis16(value time.Duration) uint16 {
	millis := value.Milliseconds()
	if millis < 0 {
		return 0
	}
	if millis > math.MaxUint16 {
		return math.MaxUint16
	}
	return uint16(millis)
}
