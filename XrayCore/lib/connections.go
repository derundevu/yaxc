package lib

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"github.com/xtls/xray-core/app/dispatcher"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
)

const connectionMetadataKey = "_yaxcConnectionProfiles"
const maxConnectionRecords = 2048
const maxConnectionDestinations = 8
const connectionRetention = 24 * time.Hour

// The latest stopped ledger remains readable until the next successful Start.
// Readers never contend with the core lifecycle lock or retain a core instance.
var currentConnections atomic.Pointer[connectionLedger]

// Changes are serialized with Start, Stop and ApplyRouting by lifecycleMu.
// A nil preference is the process default: capture enabled, generation zero.
var connectionCapturePreference atomic.Pointer[connectionCaptureSettings]

type connectionCaptureSettings struct {
	enabled    bool
	generation uint64
}

func connectionCaptureSetting() connectionCaptureSettings {
	if setting := connectionCapturePreference.Load(); setting != nil {
		return *setting
	}
	return connectionCaptureSettings{enabled: true}
}

type connectionProfile struct {
	ProfileID   int64  `json:"profileId"`
	ProfileName string `json:"profileName"`
	Fingerprint string `json:"profileFingerprint"`
	Route       string `json:"route"`
	logicalTag  string
}

type connectionDocument struct {
	ID                    string   `json:"id"`
	Protocol              string   `json:"protocol"`
	Destination           string   `json:"destination"`
	Domain                string   `json:"domain"`
	Destinations          []string `json:"destinations"`
	DestinationsTruncated bool     `json:"destinationsTruncated"`
	DestinationKind       string   `json:"destinationKind"`
	StartedAt             int64    `json:"startedAt"`
	EndedAt               int64    `json:"endedAt"`
	State                 string   `json:"state"`
	Upload                int64    `json:"upload"`
	Download              int64    `json:"download"`
	LiveBytesExact        bool     `json:"liveBytesExact"`
	UID                   int      `json:"uid"`
	ProfileID             int64    `json:"profileId"`
	ProfileName           string   `json:"profileName"`
	ProfileFingerprint    string   `json:"profileFingerprint"`
	OutboundTag           string   `json:"outboundTag"`
	Route                 string   `json:"route"`
}

type connectionSnapshot struct {
	Running           bool                 `json:"running"`
	Enabled           bool                 `json:"enabled"`
	CaptureGeneration uint64               `json:"captureGeneration"`
	Epoch             string               `json:"epoch"`
	Generation        uint64               `json:"generation"`
	NativeEvicted     uint64               `json:"nativeEvicted"`
	NativeRejected    uint64               `json:"nativeRejected"`
	Connections       []connectionDocument `json:"connections"`
}

type connectionHistory struct {
	mu                sync.Mutex
	epoch             string
	running           bool
	activeRuntime     string
	nextID            uint64
	generation        uint64
	enabled           bool
	captureGeneration uint64
	ledgers           map[*connectionLedger]struct{}
	evicted           uint64
	rejected          uint64
	records           map[uint64]*observedConnection
	closed            [maxConnectionRecords]*observedConnection
	head              int
	count             int
}

// Each core owns its tag bindings, while records share one bounded process history.
// A stopped core can therefore finish accounting after an immediate replacement.
type connectionLedger struct {
	*connectionHistory
	runtimeID string
	accepting bool
	routes    map[string]connectionProfile
	observer  *dispatcher.DefaultDispatcher
	pending   int
}

type observedConnection struct {
	history      *connectionHistory
	bindings     *connectionLedger
	owner        *connectionLedger
	runtimeID    string
	sequence     uint64
	document     connectionDocument // history.mu owns metadata, never session pointers.
	destinations [maxConnectionDestinations]net.Destination
	destCount    int
	upload       atomic.Int64
	download     atomic.Int64
	deferred     atomic.Bool
	incomplete   atomic.Bool
	finished     atomic.Bool
}

func connectionProfiles(document map[string]any) (map[string]connectionProfile, error) {
	raw, exists := document[connectionMetadataKey]
	if !exists {
		return nil, nil
	}
	values, ok := raw.(map[string]any)
	if !ok {
		return nil, errors.New("invalid connection profile metadata")
	}
	result := make(map[string]connectionProfile, len(values))
	for tag, raw := range values {
		value, ok := raw.(map[string]any)
		if !ok {
			return nil, errors.New("invalid connection profile entry")
		}
		id, err := strconv.ParseInt(stringValueNumber(value["profileId"]), 10, 64)
		if err != nil || id < 0 {
			return nil, errors.New("invalid connection profile identity")
		}
		route := stringValue(value["route"])
		if route != "proxy" && route != "direct" && route != "block" && route != "unknown" {
			return nil, errors.New("invalid connection route kind")
		}
		fingerprint := stringValue(value["profileFingerprint"])
		if id > 0 && (len(fingerprint) != 64 || !validConnectionFingerprint(fingerprint)) {
			return nil, errors.New("invalid connection profile fingerprint")
		}
		result[tag] = connectionProfile{ProfileID: id, ProfileName: boundConnectionText(stringValue(value["profileName"]), 256),
			Fingerprint: fingerprint, Route: route, logicalTag: tag}
	}
	return result, nil
}

func stringValueNumber(value any) string {
	if number, ok := value.(json.Number); ok {
		return string(number)
	}
	return ""
}

func validConnectionFingerprint(value string) bool {
	for _, c := range value {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return false
		}
	}
	return true
}

func newConnectionLedger(server *core.Instance, document map[string]any, state *liveRoutingState) (*connectionLedger, error) {
	profiles, err := connectionProfiles(document)
	if err != nil {
		return nil, err
	}
	var nonce [16]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, err
	}
	observer, ok := server.GetFeature(routing.DispatcherType()).(*dispatcher.DefaultDispatcher)
	if !ok {
		return nil, errors.New("core dispatcher does not support connection observation")
	}
	runtimeID := hex.EncodeToString(nonce[:])
	var history *connectionHistory
	if previous := currentConnections.Load(); previous != nil {
		history = previous.connectionHistory
	} else {
		setting := connectionCaptureSetting()
		history = &connectionHistory{epoch: runtimeID, enabled: setting.enabled,
			captureGeneration: setting.generation, records: make(map[uint64]*observedConnection)}
	}
	ledger := &connectionLedger{connectionHistory: history, runtimeID: runtimeID, accepting: true,
		routes: make(map[string]connectionProfile), observer: observer}
	ledger.publishProfiles(profiles, state.outbounds)
	ledger.mu.Lock()
	if ledger.ledgers == nil {
		ledger.ledgers = make(map[*connectionLedger]struct{})
	}
	ledger.ledgers[ledger] = struct{}{}
	enabled := ledger.enabled
	ledger.mu.Unlock()
	if enabled {
		observer.SetConnectionObserver(ledger)
	}
	return ledger, nil
}

func (l *connectionLedger) publishProfiles(profiles map[string]connectionProfile, entries map[string]*liveOutbound) []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	var added []string
	for key, entry := range entries {
		profile, ok := profiles[key]
		if !ok {
			profile = connectionProfile{Route: "unknown", logicalTag: key}
		}
		tag := entry.physical
		if tag == "" && entry.handler != nil {
			tag = entry.handler.Tag()
		}
		if _, exists := l.routes[tag]; exists {
			continue
		}
		added = append(added, tag)
		l.routes[tag] = profile
	}
	return added
}

func (l *connectionLedger) discardProfiles(tags []string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, tag := range tags {
		delete(l.routes, tag)
	}
}

func (l *connectionLedger) Open(metadata session.ConnectionMetadata) session.ConnectionObservation {
	// Internal sessions are excluded in the dispatcher, before reaching the ledger.
	l.mu.Lock()
	defer l.mu.Unlock()
	if !l.accepting || !l.enabled {
		return nil
	}
	l.evictExpiredLocked(time.Now().Add(-connectionRetention).UnixMilli())
	if len(l.records) >= maxConnectionRecords {
		if l.count == 0 {
			l.rejected++
			return nil
		}
		l.evictOldestLocked()
	}
	l.nextID++
	uid := -1
	if uidAccount(metadata.User) {
		value, _ := strconv.Atoi(strings.TrimPrefix(metadata.User, "yaxc.uid."))
		uid = value
	}
	kind := "connection"
	if metadata.Network == net.Network_UDP {
		kind = "association"
	}
	l.pending++
	observation := &observedConnection{history: l.connectionHistory, bindings: l, owner: l, runtimeID: l.runtimeID, sequence: l.nextID,
		document: connectionDocument{ID: l.epoch + "." + strconv.FormatUint(l.nextID, 10), Protocol: metadata.Network.SystemString(),
			Destination: boundConnectionText(metadata.Destination.NetAddr(), 320), DestinationKind: kind,
			StartedAt: time.Now().UnixMilli(), State: "active", UID: uid, Route: "unknown", LiveBytesExact: true}}
	observation.addDestinationLocked(metadata.Destination)
	l.records[l.nextID] = observation
	return observation
}

func (l *connectionLedger) evictOldestLocked() {
	entry := l.closed[l.head]
	l.closed[l.head] = nil
	l.head = (l.head + 1) % maxConnectionRecords
	l.count--
	delete(l.records, entry.sequence)
	l.evicted++
}

func (l *connectionLedger) evictExpiredLocked(before int64) {
	for l.count > 0 && l.closed[l.head].document.EndedAt < before {
		l.evictOldestLocked()
	}
}

func (c *observedConnection) Routed(route session.ConnectionRoute) {
	history := c.history
	history.mu.Lock()
	defer history.mu.Unlock()
	if c.bindings == nil {
		return
	}
	profile, ok := c.bindings.routes[route.OutboundTag]
	if !ok {
		profile = connectionProfile{Route: "unknown", logicalTag: route.OutboundTag}
	}
	c.document.ProfileID, c.document.ProfileName = profile.ProfileID, profile.ProfileName
	c.document.ProfileFingerprint = profile.Fingerprint
	c.document.Route, c.document.OutboundTag = profile.Route, boundConnectionText(profile.logicalTag, 256)
	c.document.Domain = boundConnectionText(route.Domain, 253)
	c.addDestinationLocked(route.Target)
	c.bindings = nil
}

func sameConnectionDestination(a, b net.Destination) bool {
	if a.Network != b.Network || a.Port != b.Port || a.Address == nil || b.Address == nil || a.Address.Family() != b.Address.Family() {
		return false
	}
	if a.Address.Family().IsDomain() {
		return a.Address.Domain() == b.Address.Domain()
	}
	return a.Address.IP().Equal(b.Address.IP())
}

func (c *observedConnection) addDestinationLocked(destination net.Destination) {
	if !destination.IsValid() {
		return
	}
	for i := range c.destCount {
		if sameConnectionDestination(c.destinations[i], destination) {
			return
		}
	}
	if c.destCount == maxConnectionDestinations {
		c.document.DestinationsTruncated = true
		return
	}
	c.destinations[c.destCount] = destination
	c.destCount++
	c.document.Destinations = append(c.document.Destinations, boundConnectionText(destination.NetAddr(), 320))
}

func (c *observedConnection) Destination(destination net.Destination) {
	c.history.mu.Lock()
	defer c.history.mu.Unlock()
	c.addDestinationLocked(destination)
}

func (c *observedConnection) AddUpload(size int64) {
	if size > 0 {
		c.upload.Add(size)
	}
}

func (c *observedConnection) AddDownload(size int64) {
	if size > 0 {
		c.download.Add(size)
	}
}

func (c *observedConnection) SetDownloadDeferred(deferred bool) { c.deferred.Store(deferred) }
func (c *observedConnection) MarkBytesIncomplete()              { c.incomplete.Store(true) }

func (c *observedConnection) Finish(state string) {
	if !c.finished.CompareAndSwap(false, true) {
		return
	}
	l := c.history
	l.mu.Lock()
	defer l.mu.Unlock()
	c.bindings = nil
	c.destinations = [maxConnectionDestinations]net.Destination{}
	if owner := c.owner; owner != nil {
		owner.pending--
		if !owner.accepting && owner.pending == 0 {
			delete(l.ledgers, owner)
			owner.observer = nil
		}
		c.owner = nil
	}
	c.deferred.Store(false)
	if _, retained := l.records[c.sequence]; !retained {
		return
	}
	switch state {
	case "closed", "error", "blocked", "interrupted":
	default:
		state = "interrupted"
	}
	if c.document.Route == "block" {
		state = "blocked"
	}
	if c.document.EndedAt == 0 {
		c.document.State, c.document.EndedAt = state, time.Now().UnixMilli()
		l.closed[(l.head+l.count)%maxConnectionRecords] = c
		l.count++
	}
}

func (l *connectionLedger) started() {
	l.mu.Lock()
	l.running, l.activeRuntime = true, l.runtimeID
	l.mu.Unlock()
}

func (l *connectionLedger) stop() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.accepting = false
	if l.pending == 0 {
		delete(l.ledgers, l)
		l.observer = nil
	}
	if l.activeRuntime == l.runtimeID {
		l.running = false
	}
	var interrupted []*observedConnection
	for _, observation := range l.records {
		if observation.runtimeID == l.runtimeID && observation.document.EndedAt == 0 {
			interrupted = append(interrupted, observation)
		}
	}
	// A stop completes these rows simultaneously. Use admission order to break the
	// tie, rather than allowing randomized map traversal to choose FIFO victims.
	sort.Slice(interrupted, func(i, j int) bool { return interrupted[i].sequence < interrupted[j].sequence })
	endedAt := time.Now().UnixMilli()
	for _, observation := range interrupted {
		// Close does not join every upstream forwarder. Retain its handle for
		// late bytes/Finish and explicitly mark this interim total incomplete.
		observation.deferred.Store(true)
		observation.document.State, observation.document.EndedAt = "interrupted", endedAt
		l.closed[(l.head+l.count)%maxConnectionRecords] = observation
		l.count++
	}
}

func (l *connectionLedger) snapshot() connectionSnapshot {
	l.mu.Lock()
	l.evictExpiredLocked(time.Now().Add(-connectionRetention).UnixMilli())
	result := connectionSnapshot{Running: l.running, Enabled: l.enabled, CaptureGeneration: l.captureGeneration,
		Epoch: l.epoch, Generation: l.generation, NativeEvicted: l.evicted, NativeRejected: l.rejected,
		Connections: make([]connectionDocument, 0, len(l.records))}
	for _, observation := range l.records {
		document := observation.document
		document.Destinations = append([]string(nil), document.Destinations...)
		document.Upload, document.Download = observation.upload.Load(), observation.download.Load()
		document.LiveBytesExact = !observation.deferred.Load() && !observation.incomplete.Load()
		result.Connections = append(result.Connections, document)
	}
	l.mu.Unlock()
	sort.Slice(result.Connections, func(i, j int) bool {
		if result.Connections[i].StartedAt != result.Connections[j].StartedAt {
			return result.Connections[i].StartedAt < result.Connections[j].StartedAt
		}
		return result.Connections[i].ID < result.Connections[j].ID
	})
	return result
}

// ConnectionSnapshot reports logical application bytes, not IP/encryption overhead.
// UDP rows are associations; splice may defer exact download totals until close.
func ConnectionSnapshot() string {
	setting := connectionCaptureSetting()
	result := connectionSnapshot{Enabled: setting.enabled, CaptureGeneration: setting.generation, Connections: []connectionDocument{}}
	if ledger := currentConnections.Load(); ledger != nil {
		result = ledger.snapshot()
	}
	encoded, _ := json.Marshal(result) // The model contains only finite integers, strings and booleans.
	return string(encoded)
}

// ClearConnectionHistory leaves active observations and all forwarding untouched.
func ClearConnectionHistory() {
	if ledger := currentConnections.Load(); ledger != nil {
		ledger.mu.Lock()
		defer ledger.mu.Unlock()
		for ledger.count > 0 {
			entry := ledger.closed[ledger.head]
			ledger.closed[ledger.head] = nil
			ledger.head = (ledger.head + 1) % maxConnectionRecords
			ledger.count--
			delete(ledger.records, entry.sequence)
		}
		ledger.generation++
		ledger.evicted, ledger.rejected = 0, 0
	}
}

// SetConnectionAnalyticsEnabled changes only observation, never forwarding or
// routing. Disabling joins callbacks from the current and any still-draining
// stopped cores before publishing the final, fenced snapshot.
func SetConnectionAnalyticsEnabled(enabled bool) error {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	setting := connectionCaptureSetting()
	if setting.enabled == enabled {
		return nil
	}
	ledger := currentConnections.Load()
	if coreServer != nil && (routingState == nil || routingState.connections == nil) {
		return errors.New("running core has no connection observer")
	}
	if coreServer != nil {
		ledger = routingState.connections
		if ledger.observer == nil {
			return errors.New("core dispatcher does not support connection observation")
		}
	}
	if !enabled && ledger != nil {
		ledger.mu.Lock()
		observers := make([]*dispatcher.DefaultDispatcher, 0, len(ledger.ledgers))
		for owner := range ledger.ledgers {
			if owner.observer != nil {
				observers = append(observers, owner.observer)
			}
		}
		ledger.mu.Unlock()
		// Never hold the history lock: the setter completes observation callbacks.
		for _, observer := range observers {
			observer.SetConnectionObserver(nil)
		}
	}
	setting.enabled = enabled
	setting.generation++
	if ledger != nil {
		ledger.mu.Lock()
		ledger.enabled, ledger.captureGeneration = enabled, setting.generation
		ledger.mu.Unlock()
	}
	connectionCapturePreference.Store(&setting)
	if enabled && coreServer != nil {
		ledger.observer.SetConnectionObserver(ledger)
	}
	return nil
}

func boundConnectionText(value string, limit int) string {
	value = strings.ToValidUTF8(value, "")
	if len(value) <= limit {
		return value
	}
	for limit > 0 && !utf8.RuneStart(value[limit]) {
		limit--
	}
	return value[:limit]
}
