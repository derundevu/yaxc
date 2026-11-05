package lib

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/proxy"
)

const healthTestBalancer = "_yaxc.health.balance.synthetic"
const healthTestBlock = "_yaxc.health.block.synthetic"

type healthTestProbe struct {
	path     string
	reply    chan int
	canceled chan time.Time
}

type healthTestNode struct {
	server  *httptest.Server
	marker  byte
	status  atomic.Int32
	delay   atomic.Int64
	manual  atomic.Bool
	probes  atomic.Int64
	streams atomic.Int64
	events  chan *healthTestProbe
	mu      sync.Mutex
	held    map[net.Conn]bool
	workers sync.WaitGroup
}

// Both the probes and application traffic traverse the configured outbound to
// this node. A successful direct request to the URL cannot make a node healthy.
func newHealthTestNode(t *testing.T, marker byte, status int, manual bool) *healthTestNode {
	t.Helper()
	node := &healthTestNode{marker: marker, events: make(chan *healthTestProbe, 256), held: make(map[net.Conn]bool)}
	node.status.Store(int32(status))
	node.manual.Store(manual)
	node.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/stream" {
			node.probes.Add(1)
			event := &healthTestProbe{path: r.URL.Path, reply: make(chan int, 1), canceled: make(chan time.Time, 1)}
			manual := node.manual.Load()
			if manual {
				select {
				case node.events <- event:
				case <-r.Context().Done():
					return
				}
			} else {
				// Undrained observations must not stop automatic health responses.
				select {
				case node.events <- event:
				default:
				}
			}
			code := int(node.status.Load())
			if manual {
				select {
				case code = <-event.reply:
				case <-r.Context().Done():
					event.canceled <- time.Now()
					return
				}
			}
			if delay := time.Duration(node.delay.Load()); delay > 0 {
				timer := time.NewTimer(delay)
				defer timer.Stop()
				select {
				case <-timer.C:
				case <-r.Context().Done():
					return
				}
			}
			w.WriteHeader(code)
			return
		}
		conn, buffered, err := w.(http.Hijacker).Hijack()
		if err != nil {
			return
		}
		node.mu.Lock()
		node.held[conn] = true
		node.mu.Unlock()
		node.workers.Add(1)
		defer node.workers.Done()
		defer func() {
			conn.Close()
			node.mu.Lock()
			delete(node.held, conn)
			node.mu.Unlock()
		}()
		node.streams.Add(1)
		if _, err := fmt.Fprintf(buffered, "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: synthetic-health-echo\r\n\r\n%c", node.marker); err != nil {
			return
		}
		if err := buffered.Flush(); err != nil {
			return
		}
		io.Copy(conn, buffered)
	}))
	t.Cleanup(func() {
		node.server.CloseClientConnections()
		node.mu.Lock()
		for conn := range node.held {
			conn.Close()
		}
		node.mu.Unlock()
		node.server.Close()
		node.workers.Wait()
	})
	return node
}

func (node *healthTestNode) address() string {
	return strings.TrimPrefix(node.server.URL, "http://")
}

func useFastHealthTestOptions(t *testing.T) {
	t.Helper()
	lifecycleMu.Lock()
	previous := liveHealthDefaults
	liveHealthDefaults = liveHealthOptions{
		interval: 75 * time.Millisecond, timeout: 10 * time.Second,
		concurrency: 4, failureThreshold: 2, recoveryThreshold: 3,
		staleAfter: 30 * time.Second,
	}
	lifecycleMu.Unlock()
	t.Cleanup(func() {
		lifecycleMu.Lock()
		liveHealthDefaults = previous
		lifecycleMu.Unlock()
	})
}

func healthTestConfig(t *testing.T, port int, url, strategy string, nodes []*healthTestNode, order []int64, enabled bool, defaultNode *healthTestNode) string {
	t.Helper()
	outbounds := []any{
		map[string]any{"tag": "synthetic-default", "protocol": "freedom", "settings": map[string]any{"redirect": defaultNode.address()}},
		map[string]any{"tag": healthTestBlock, "protocol": "blackhole"},
	}
	for i, node := range nodes {
		outbounds = append(outbounds, map[string]any{
			"tag": "member." + strconv.Itoa(i+1), "protocol": "freedom",
			"settings": map[string]any{"redirect": node.address()},
		})
	}
	groupRule := map[string]any{"type": "field", "domain": []string{"full:group.invalid"}}
	routing := map[string]any{"domainStrategy": "AsIs", "rules": []any{groupRule}}
	if strategy == "Select" && !enabled {
		groupRule["outboundTag"] = "member." + strconv.FormatInt(order[0], 10)
	} else {
		groupRule["balancerTag"] = healthTestBalancer
		selector := []string{"member."}
		if enabled {
			selector = []string{healthTestBlock}
		}
		balancerStrategy := "random"
		if strategy == "RoundRobin" {
			balancerStrategy = "roundRobin"
		}
		routing["balancers"] = []any{map[string]any{
			"tag": healthTestBalancer, "selector": selector,
			"strategy": map[string]any{"type": balancerStrategy},
		}}
	}
	document := map[string]any{
		"log": map[string]any{"loglevel": "none"},
		"inbounds": []any{map[string]any{
			"tag": "synthetic-socks", "listen": "127.0.0.1", "port": port, "protocol": "socks",
			"settings": map[string]any{"auth": "noauth"},
		}},
		"outbounds": outbounds, "routing": routing,
	}
	if enabled {
		members := make([]any, 0, len(order))
		for _, id := range order {
			members = append(members, map[string]any{
				"profileId": id, "tag": "member." + strconv.FormatInt(id, 10),
				"fingerprint": strings.Repeat(fmt.Sprintf("%x", id), 64),
			})
		}
		document["_yaxcLiveHealth"] = map[string]any{"url": url, "groups": []any{map[string]any{
			"id": "synthetic-health-group", "strategy": strategy,
			"balancerTag": healthTestBalancer, "blockTag": healthTestBlock,
			"selectedProfileId": order[0], "members": members,
		}}}
	}
	encoded, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	return string(encoded)
}

func healthTestListenAddress(t *testing.T) (string, int) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	return address, port
}

func startHealthTestCore(t *testing.T, config string) {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("xray.location.asset", dir)
	t.Setenv("xray.mph.cache", "")
	path := filepath.Join(dir, "synthetic-health.json")
	if err := os.WriteFile(path, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	if err := Start(dir, path); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := Stop(); err != nil {
			t.Error(err)
		}
	})
}

type healthTestMemberStatus struct {
	ProfileID     int64  `json:"profileId"`
	Fingerprint   string `json:"fingerprint"`
	State         string `json:"state"`
	LastCheckedAt int64  `json:"lastCheckedAt"`
	DelayMS       int64  `json:"delayMs"`
}

type healthTestGroupStatus struct {
	ID                string                   `json:"id"`
	Strategy          string                   `json:"strategy"`
	SelectedProfileID *int64                   `json:"selectedProfileId"`
	State             string                   `json:"state"`
	ActiveProfileID   *int64                   `json:"activeProfileId"`
	EligibleIDs       []int64                  `json:"eligibleProfileIds"`
	Members           []healthTestMemberStatus `json:"members"`
}

type healthTestStatus struct {
	Running bool                    `json:"running"`
	Groups  []healthTestGroupStatus `json:"groups"`
}

func readHealthTestStatus(t *testing.T) healthTestStatus {
	t.Helper()
	encoded := LiveRoutingStatus()
	var status healthTestStatus
	if err := json.Unmarshal([]byte(encoded), &status); err != nil {
		t.Fatalf("invalid health status JSON %q: %v", encoded, err)
	}
	if status.Groups == nil {
		t.Fatalf("groups must be an array, not null: %s", encoded)
	}
	return status
}

func waitHealthTestGroup(t *testing.T, label string, predicate func(healthTestGroupStatus) bool) healthTestGroupStatus {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var status healthTestStatus
	for time.Now().Before(deadline) {
		status = readHealthTestStatus(t)
		if status.Running && len(status.Groups) == 1 && predicate(status.Groups[0]) {
			return status.Groups[0]
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("health did not reach %s: %+v", label, status)
	return healthTestGroupStatus{}
}

func healthTestMember(group healthTestGroupStatus, id int64) healthTestMemberStatus {
	for _, member := range group.Members {
		if member.ProfileID == id {
			return member
		}
	}
	return healthTestMemberStatus{}
}

func healthTestActive(group healthTestGroupStatus, id int64) bool {
	return group.ActiveProfileID != nil && *group.ActiveProfileID == id
}

func nextHealthTestProbe(t *testing.T, node *healthTestNode) *healthTestProbe {
	t.Helper()
	select {
	case event := <-node.events:
		return event
	case <-time.After(3 * time.Second):
		t.Fatal("no health request reached the configured member outbound")
		return nil
	}
}

func answerHealthTestProbe(t *testing.T, node *healthTestNode, id int64, code int) healthTestGroupStatus {
	t.Helper()
	group := waitHealthTestGroup(t, "running group", func(group healthTestGroupStatus) bool { return true })
	previous := healthTestMember(group, id).LastCheckedAt
	event := nextHealthTestProbe(t, node)
	select {
	case <-event.canceled:
		t.Fatal("controlled probe expired before its response was supplied")
	default:
	}
	event.reply <- code
	return waitHealthTestGroup(t, "completed controlled probe", func(group healthTestGroupStatus) bool {
		return healthTestMember(group, id).LastCheckedAt > previous
	})
}

type healthTestBufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (conn *healthTestBufferedConn) Read(p []byte) (int, error) {
	return conn.reader.Read(p)
}

func dialHealthTestStream(address, destination string) (net.Conn, byte, error) {
	dialer, err := proxy.SOCKS5("tcp", address, nil, &net.Dialer{Timeout: time.Second})
	if err != nil {
		return nil, 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, err := dialer.(proxy.ContextDialer).DialContext(ctx, "tcp", destination+":80")
	if err != nil {
		return nil, 0, err
	}
	conn.SetDeadline(time.Now().Add(time.Second))
	if _, err := fmt.Fprintf(conn, "GET /stream HTTP/1.1\r\nHost: %s\r\nConnection: Upgrade\r\nUpgrade: synthetic-health-echo\r\n\r\n", destination); err != nil {
		conn.Close()
		return nil, 0, err
	}
	reader := bufio.NewReader(conn)
	response, err := http.ReadResponse(reader, &http.Request{Method: "GET"})
	if err != nil {
		conn.Close()
		return nil, 0, err
	}
	if response.StatusCode != http.StatusSwitchingProtocols {
		response.Body.Close()
		conn.Close()
		return nil, 0, fmt.Errorf("unexpected application response: %s", response.Status)
	}
	marker, err := reader.ReadByte()
	if err != nil {
		conn.Close()
		return nil, 0, err
	}
	return &healthTestBufferedConn{Conn: conn, reader: reader}, marker, nil
}

func requireHealthTestPayload(t *testing.T, conn net.Conn, label string) {
	t.Helper()
	conn.SetDeadline(time.Now().Add(time.Second))
	payload := append([]byte(label+"\x00\xff\n"), bytes.Repeat([]byte("health payload 東京"), 256)...)
	if _, err := conn.Write(payload); err != nil {
		t.Fatalf("%s write: %v", label, err)
	}
	received := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, received); err != nil {
		t.Fatalf("%s read: %v", label, err)
	}
	if !bytes.Equal(received, payload) {
		t.Fatalf("%s: application payload was changed", label)
	}
}

func requireHealthTestTarget(t *testing.T, address string, want byte) {
	t.Helper()
	conn, marker, err := dialHealthTestStream(address, "group.invalid")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if marker != want {
		t.Fatalf("application reached %q, want %q", marker, want)
	}
	requireHealthTestPayload(t, conn, "new flow")
}

func requireHealthTestBlocked(t *testing.T, address string, defaultNode *healthTestNode) {
	t.Helper()
	before := defaultNode.streams.Load()
	conn, marker, err := dialHealthTestStream(address, "group.invalid")
	if conn != nil {
		conn.Close()
	}
	if err == nil {
		t.Fatalf("all-down group reached a real recipient %q", marker)
	}
	if after := defaultNode.streams.Load(); after != before {
		t.Fatalf("all-down group leaked to the default outbound: before=%d after=%d", before, after)
	}
}

func TestLiveHealthSelectTransitionsPreserveRealStreams(t *testing.T) {
	useFastHealthTestOptions(t)
	primary := newHealthTestNode(t, 'A', 503, true)
	backup := newHealthTestNode(t, 'B', 204, false)
	defaultNode := newHealthTestNode(t, 'D', 204, false)
	address, port := healthTestListenAddress(t)
	config := healthTestConfig(t, port, "http://probe.invalid/probe", "Select", []*healthTestNode{primary, backup}, []int64{1, 2}, true, defaultNode)
	startHealthTestCore(t, config)
	waitHealthTestGroup(t, "backup while primary is checking", func(group healthTestGroupStatus) bool {
		return healthTestActive(group, 2) && healthTestMember(group, 1).State == "checking"
	})
	requireHealthTestTarget(t, address, 'B')
	group := answerHealthTestProbe(t, primary, 1, 503)
	if healthTestMember(group, 1).State != "checking" || !healthTestActive(group, 2) {
		t.Fatalf("first startup failure left safe checking state: %+v", group)
	}
	group = answerHealthTestProbe(t, primary, 1, 503)
	if healthTestMember(group, 1).State != "unavailable" || !healthTestActive(group, 2) {
		t.Fatalf("two startup failures did not mark the primary unavailable: %+v", group)
	}
	for range 2 {
		group = answerHealthTestProbe(t, primary, 1, 204)
		if !healthTestActive(group, 2) {
			t.Fatalf("partial recovery became eligible: %+v", group)
		}
	}
	group = answerHealthTestProbe(t, primary, 1, 503)
	if !healthTestActive(group, 2) {
		t.Fatalf("interrupted recovery did not remain unavailable: %+v", group)
	}
	for success := 1; success <= 3; success++ {
		group = answerHealthTestProbe(t, primary, 1, 204)
		want := int64(2)
		if success == 3 {
			want = 1
		}
		if !healthTestActive(group, want) {
			t.Fatalf("recovery success %d selected the wrong recipient: %+v", success, group)
		}
		requireHealthTestTarget(t, address, byte('A'+want-1))
	}
	held, marker, err := dialHealthTestStream(address, "group.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("held primary stream: marker=%q err=%v", marker, err)
	}
	defer held.Close()
	requireHealthTestPayload(t, held, "healthy before outage")
	group = answerHealthTestProbe(t, primary, 1, 503)
	if !healthTestActive(group, 1) {
		t.Fatalf("a single failure evicted the healthy primary: %+v", group)
	}
	group = answerHealthTestProbe(t, primary, 1, 503)
	if !healthTestActive(group, 2) {
		t.Fatalf("two failures did not fail over: %+v", group)
	}
	requireHealthTestTarget(t, address, 'B')
	requireHealthTestPayload(t, held, "held healthy stream after failover")
	backup.status.Store(503)
	waitHealthTestGroup(t, "all members unavailable", func(group healthTestGroupStatus) bool {
		return group.State == "unavailable" && len(group.EligibleIDs) == 0 && group.ActiveProfileID == nil
	})
	requireHealthTestBlocked(t, address, defaultNode)
	requireHealthTestPayload(t, held, "held stream while no new flow is eligible")
	for success := 1; success <= 3; success++ {
		group = answerHealthTestProbe(t, primary, 1, 204)
		if success < 3 && len(group.EligibleIDs) != 0 {
			t.Fatalf("all-down recovery admitted success %d too early: %+v", success, group)
		}
	}
	if !healthTestActive(group, 1) {
		t.Fatalf("three successes did not restore the preferred primary: %+v", group)
	}
	requireHealthTestTarget(t, address, 'A')
	requireHealthTestPayload(t, held, "held stream after sustained recovery")
}

func TestLiveHealthDistributesOnlyAcrossEligibleMembers(t *testing.T) {
	for _, strategy := range []string{"Random", "RoundRobin"} {
		t.Run(strategy, func(t *testing.T) {
			useFastHealthTestOptions(t)
			nodes := []*healthTestNode{
				newHealthTestNode(t, 'A', 204, false),
				newHealthTestNode(t, 'B', 204, false),
				newHealthTestNode(t, 'C', 503, false),
			}
			defaultNode := newHealthTestNode(t, 'D', 204, false)
			address, port := healthTestListenAddress(t)
			startHealthTestCore(t, healthTestConfig(t, port, "http://probe.invalid/probe", strategy, nodes, []int64{1, 2, 3}, true, defaultNode))
			group := waitHealthTestGroup(t, "both healthy distribution members", func(group healthTestGroupStatus) bool {
				return reflect.DeepEqual(group.EligibleIDs, []int64{1, 2}) && healthTestMember(group, 3).State == "unavailable"
			})
			if group.ActiveProfileID != nil {
				t.Fatalf("%s invented a single active member: %+v", strategy, group)
			}
			seen := map[byte]int{}
			var previous byte
			for i := range 64 {
				conn, marker, err := dialHealthTestStream(address, "group.invalid")
				if err != nil {
					t.Fatal(err)
				}
				if marker != 'A' && marker != 'B' {
					conn.Close()
					t.Fatalf("%s selected an ineligible member %q", strategy, marker)
				}
				if strategy == "RoundRobin" && i > 0 && marker == previous {
					conn.Close()
					t.Fatalf("round robin repeated %q instead of alternating eligible members", marker)
				}
				seen[marker]++
				previous = marker
				requireHealthTestPayload(t, conn, strategy)
				conn.Close()
			}
			if len(seen) != 2 {
				t.Fatalf("%s did not distribute across all eligible members: %v", strategy, seen)
			}
			nodes[0].status.Store(503)
			waitHealthTestGroup(t, "only second member remains eligible", func(group healthTestGroupStatus) bool {
				return reflect.DeepEqual(group.EligibleIDs, []int64{2})
			})
			for range 8 {
				requireHealthTestTarget(t, address, 'B')
			}
			if nodes[2].streams.Load() != 0 || defaultNode.streams.Load() != 0 {
				t.Fatal("distribution escaped the eligible set")
			}
		})
	}
}

func TestLiveHealthDisabledStrategiesKeepTheirExistingRecipients(t *testing.T) {
	for _, strategy := range []string{"Select", "Random", "RoundRobin"} {
		t.Run(strategy, func(t *testing.T) {
			useFastHealthTestOptions(t)
			nodes := []*healthTestNode{newHealthTestNode(t, 'A', 503, false), newHealthTestNode(t, 'B', 503, false)}
			defaultNode := newHealthTestNode(t, 'D', 204, false)
			address, port := healthTestListenAddress(t)
			startHealthTestCore(t, healthTestConfig(t, port, "http://probe.invalid/probe", strategy, nodes, []int64{2, 1}, false, defaultNode))
			status := readHealthTestStatus(t)
			if !status.Running || len(status.Groups) != 0 {
				t.Fatalf("checks-disabled group appeared as managed health: %+v", status)
			}
			seen := map[byte]bool{}
			for range 64 {
				conn, marker, err := dialHealthTestStream(address, "group.invalid")
				if err != nil {
					t.Fatal(err)
				}
				if marker != 'A' && marker != 'B' || strategy == "Select" && marker != 'B' {
					conn.Close()
					t.Fatalf("disabled %s changed its recipient to %q", strategy, marker)
				}
				seen[marker] = true
				requireHealthTestPayload(t, conn, "checks disabled")
				conn.Close()
			}
			if strategy != "Select" && len(seen) != 2 {
				t.Fatalf("disabled %s stopped distributing: %v", strategy, seen)
			}
			if nodes[0].probes.Load() != 0 || nodes[1].probes.Load() != 0 {
				t.Fatal("checks-disabled traffic triggered health probes")
			}
		})
	}
}

func TestLiveHealthGraphAndURLRevisionsRejectStaleResults(t *testing.T) {
	useFastHealthTestOptions(t)
	oldNode := newHealthTestNode(t, 'A', 204, true)
	replacement := newHealthTestNode(t, 'C', 503, true)
	backup := newHealthTestNode(t, 'B', 503, false)
	defaultNode := newHealthTestNode(t, 'D', 204, false)
	address, port := healthTestListenAddress(t)
	config := healthTestConfig(t, port, "http://probe.invalid/probe", "Select", []*healthTestNode{oldNode, backup}, []int64{1, 2}, true, defaultNode)
	startHealthTestCore(t, config)
	answerHealthTestProbe(t, oldNode, 1, 204)
	requireHealthTestTarget(t, address, 'A')
	held, marker, err := dialHealthTestStream(address, "group.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("original graph stream: marker=%q err=%v", marker, err)
	}
	defer held.Close()
	oldProbe := nextHealthTestProbe(t, oldNode)
	updated := healthTestConfig(t, port, "http://probe.invalid/probe", "Select", []*healthTestNode{replacement, backup}, []int64{1, 2}, true, defaultNode)
	if err := ApplyRouting(updated); err != nil {
		t.Fatal(err)
	}
	waitHealthTestGroup(t, "same logical ID resets to checking", func(group healthTestGroupStatus) bool {
		member := healthTestMember(group, 1)
		return member.State == "checking" && member.LastCheckedAt == 0 && len(group.EligibleIDs) == 0
	})
	select {
	case <-oldProbe.canceled:
	case <-time.After(time.Second):
		t.Fatal("replaced graph did not cancel its in-flight request")
	}
	oldProbe.reply <- 204
	requireHealthTestBlocked(t, address, defaultNode)
	group := answerHealthTestProbe(t, replacement, 1, 503)
	if len(group.EligibleIDs) != 0 {
		t.Fatalf("stale success made the replacement eligible: %+v", group)
	}
	group = answerHealthTestProbe(t, replacement, 1, 503)
	if healthTestMember(group, 1).State != "unavailable" {
		t.Fatalf("replacement's own failures did not control its state: %+v", group)
	}
	for success := 1; success <= 3; success++ {
		group = answerHealthTestProbe(t, replacement, 1, 204)
		if success < 3 && len(group.EligibleIDs) != 0 {
			t.Fatalf("replacement inherited an old success streak at %d: %+v", success, group)
		}
	}
	requireHealthTestTarget(t, address, 'C')
	requireHealthTestPayload(t, held, "old graph stream survives replacement")
	oldURLProbe := nextHealthTestProbe(t, replacement)
	updatedURL := healthTestConfig(t, port, "http://probe.invalid/probe-v2", "Select", []*healthTestNode{replacement, backup}, []int64{1, 2}, true, defaultNode)
	if err := ApplyRouting(updatedURL); err != nil {
		t.Fatal(err)
	}
	waitHealthTestGroup(t, "URL revision resets observations", func(group healthTestGroupStatus) bool {
		member := healthTestMember(group, 1)
		return member.State == "checking" && member.LastCheckedAt == 0 && len(group.EligibleIDs) == 0
	})
	select {
	case <-oldURLProbe.canceled:
	case <-time.After(time.Second):
		t.Fatal("probe URL revision did not cancel its old request")
	}
	oldURLProbe.reply <- 204
	currentProbe := nextHealthTestProbe(t, replacement)
	if currentProbe.path != "/probe-v2" {
		t.Fatalf("URL revision kept probing %q", currentProbe.path)
	}
	currentProbe.reply <- 503
	answerHealthTestProbe(t, replacement, 1, 503)
	waitHealthTestGroup(t, "new URL failure remains ineligible", func(group healthTestGroupStatus) bool {
		return healthTestMember(group, 1).State == "unavailable" && len(group.EligibleIDs) == 0
	})
	requireHealthTestBlocked(t, address, defaultNode)
}

func TestLiveHealthPriorityAndMembershipHotApplyKeepMatchingObservations(t *testing.T) {
	useFastHealthTestOptions(t)
	nodes := []*healthTestNode{newHealthTestNode(t, 'A', 204, false), newHealthTestNode(t, 'B', 204, false), newHealthTestNode(t, 'C', 204, true)}
	defaultNode := newHealthTestNode(t, 'D', 204, false)
	address, port := healthTestListenAddress(t)
	startHealthTestCore(t, healthTestConfig(t, port, "http://probe.invalid/probe", "Select", nodes, []int64{1, 2}, true, defaultNode))
	initial := waitHealthTestGroup(t, "both initial members healthy", func(group healthTestGroupStatus) bool {
		return reflect.DeepEqual(group.EligibleIDs, []int64{1, 2})
	})
	held, marker, err := dialHealthTestStream(address, "group.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("initial held stream: marker=%q err=%v", marker, err)
	}
	defer held.Close()
	updated := healthTestConfig(t, port, "http://probe.invalid/probe", "Select", nodes, []int64{3, 2, 1}, true, defaultNode)
	if err := ApplyRouting(updated); err != nil {
		t.Fatal(err)
	}
	group := waitHealthTestGroup(t, "new preferred member checking, existing backup selected", func(group healthTestGroupStatus) bool {
		return healthTestActive(group, 2) && healthTestMember(group, 3).State == "checking"
	})
	for _, id := range []int64{1, 2} {
		if healthTestMember(group, id).LastCheckedAt < healthTestMember(initial, id).LastCheckedAt {
			t.Fatalf("unchanged graph %d lost its observation on priority change: %+v", id, group)
		}
	}
	requireHealthTestTarget(t, address, 'B')
	group = answerHealthTestProbe(t, nodes[2], 3, 204)
	if !healthTestActive(group, 3) {
		t.Fatalf("new member did not become eligible on its first success: %+v", group)
	}
	requireHealthTestTarget(t, address, 'C')
	requireHealthTestPayload(t, held, "stream before priority and membership hot apply")
}

func TestLiveHealthStopCancelsRequestsAndDoesNotResurrectState(t *testing.T) {
	useFastHealthTestOptions(t)
	node := newHealthTestNode(t, 'A', 503, true)
	defaultNode := newHealthTestNode(t, 'D', 204, false)
	address, port := healthTestListenAddress(t)
	config := healthTestConfig(t, port, "http://probe.invalid/probe", "Select", []*healthTestNode{node}, []int64{1}, true, defaultNode)
	startHealthTestCore(t, config)
	pending := nextHealthTestProbe(t, node)
	requireHealthTestBlocked(t, address, defaultNode)
	started := time.Now()
	if err := Stop(); err != nil {
		t.Fatal(err)
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("Stop waited for a probe timeout instead of canceling: %s", elapsed)
	}
	select {
	case <-pending.canceled:
	case <-time.After(time.Second):
		t.Fatal("Stop left the in-flight probe connected")
	}
	status := readHealthTestStatus(t)
	if status.Running || len(status.Groups) != 0 {
		t.Fatalf("stopped status retained observations: %+v", status)
	}
	pending.reply <- 204
	node.manual.Store(false)
	startHealthTestCore(t, config)
	waitHealthTestGroup(t, "restarted core sees its actual failed member", func(group healthTestGroupStatus) bool {
		return group.State == "unavailable" && len(group.EligibleIDs) == 0
	})
	requireHealthTestBlocked(t, address, defaultNode)
}

func TestLiveHealthProbeConcurrencyIsGloballyBounded(t *testing.T) {
	useFastHealthTestOptions(t)
	nodes := make([]*healthTestNode, 7)
	order := make([]int64, len(nodes))
	for i := range nodes {
		nodes[i] = newHealthTestNode(t, byte('A'+i), 204, true)
		order[i] = int64(i + 1)
	}
	defaultNode := newHealthTestNode(t, 'D', 204, false)
	_, port := healthTestListenAddress(t)
	config := healthTestConfig(t, port, "http://probe.invalid/probe", "Random", nodes, order, true, defaultNode)
	var document map[string]any
	if err := json.Unmarshal([]byte(config), &document); err != nil {
		t.Fatal(err)
	}
	metadata := document["_yaxcLiveHealth"].(map[string]any)
	first := metadata["groups"].([]any)[0].(map[string]any)
	members := first["members"].([]any)
	first["members"] = members[:3]
	const secondBlock = healthTestBlock + ".second"
	const secondBalancer = healthTestBalancer + ".second"
	metadata["groups"] = []any{first, map[string]any{
		"id": "second-health-group", "strategy": "Random", "selectedProfileId": 4,
		"balancerTag": secondBalancer, "blockTag": secondBlock, "members": members[3:],
	}}
	document["outbounds"] = append(document["outbounds"].([]any), map[string]any{"tag": secondBlock, "protocol": "blackhole"})
	routing := document["routing"].(map[string]any)
	routing["balancers"] = append(routing["balancers"].([]any), map[string]any{
		"tag": secondBalancer, "selector": []string{secondBlock}, "strategy": map[string]any{"type": "random"},
	})
	routing["rules"] = append(routing["rules"].([]any), map[string]any{
		"type": "field", "domain": []string{"full:second-group.invalid"}, "balancerTag": secondBalancer,
	})
	encoded, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	startHealthTestCore(t, string(encoded))
	count := func() int64 {
		var total int64
		for _, node := range nodes {
			total += node.probes.Load()
		}
		return total
	}
	deadline := time.Now().Add(time.Second)
	for count() < 4 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if got := count(); got != 4 {
		t.Fatalf("initial bounded scheduling started %d probes, want 4", got)
	}
	time.Sleep(100 * time.Millisecond)
	if got := count(); got != 4 {
		t.Fatalf("global concurrency exceeded four held requests: %d", got)
	}
	for _, node := range nodes {
		select {
		case event := <-node.events:
			event.reply <- 204
			deadline = time.Now().Add(time.Second)
			for count() == 4 && time.Now().Before(deadline) {
				time.Sleep(time.Millisecond)
			}
			if count() < 5 {
				t.Fatal("a completed probe did not release capacity for another member")
			}
			return
		default:
		}
	}
	t.Fatal("no held request could be released")
}

func healthTestDependentConfig(t *testing.T, config string, carrier *healthTestNode) string {
	t.Helper()
	var document map[string]any
	if err := json.Unmarshal([]byte(config), &document); err != nil {
		t.Fatal(err)
	}
	outbounds := document["outbounds"].([]any)
	for _, value := range outbounds {
		entry := value.(map[string]any)
		if entry["tag"] == "member.1" {
			entry["settings"] = map[string]any{"domainStrategy": "AsIs"}
			entry["streamSettings"] = map[string]any{"sockopt": map[string]any{"dialerProxy": "synthetic-carrier"}}
		}
	}
	document["outbounds"] = append(outbounds, map[string]any{
		"tag": "synthetic-carrier", "protocol": "freedom",
		"settings": map[string]any{"redirect": carrier.address()},
	})
	encoded, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	return string(encoded)
}

func TestLiveHealthDependencyVersionInvalidatesUnchangedLogicalRoot(t *testing.T) {
	useFastHealthTestOptions(t)
	oldCarrier := newHealthTestNode(t, 'A', 204, true)
	newCarrier := newHealthTestNode(t, 'C', 204, true)
	defaultNode := newHealthTestNode(t, 'D', 204, false)
	address, port := healthTestListenAddress(t)
	base := healthTestConfig(t, port, "http://probe.invalid/probe", "Select", []*healthTestNode{oldCarrier}, []int64{1}, true, defaultNode)
	startHealthTestCore(t, healthTestDependentConfig(t, base, oldCarrier))
	answerHealthTestProbe(t, oldCarrier, 1, 204)
	held, marker, err := dialHealthTestStream(address, "group.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("initial dependent graph: marker=%q err=%v", marker, err)
	}
	defer held.Close()
	oldProbe := nextHealthTestProbe(t, oldCarrier)
	// Only synthetic-carrier changes: member.1, profileId, and fingerprint do not.
	if err := ApplyRouting(healthTestDependentConfig(t, base, newCarrier)); err != nil {
		t.Fatal(err)
	}
	waitHealthTestGroup(t, "dependency revision resets root health", func(group healthTestGroupStatus) bool {
		member := healthTestMember(group, 1)
		return member.State == "checking" && member.LastCheckedAt == 0 && len(group.EligibleIDs) == 0
	})
	select {
	case <-oldProbe.canceled:
	case <-time.After(time.Second):
		t.Fatal("changed dependency retained its old in-flight request")
	}
	oldProbe.reply <- 204
	requireHealthTestBlocked(t, address, defaultNode)
	group := answerHealthTestProbe(t, newCarrier, 1, 204)
	if !healthTestActive(group, 1) {
		t.Fatalf("new graph did not qualify on its own initial success: %+v", group)
	}
	requireHealthTestTarget(t, address, 'C')
	requireHealthTestPayload(t, held, "old dependent graph stream after child version changed")
}

func TestLiveHealthInvalidMetadataCannotReplaceHealthyRuntime(t *testing.T) {
	useFastHealthTestOptions(t)
	node := newHealthTestNode(t, 'A', 204, false)
	defaultNode := newHealthTestNode(t, 'D', 204, false)
	address, port := healthTestListenAddress(t)
	config := healthTestConfig(t, port, "http://probe.invalid/probe", "Select", []*healthTestNode{node}, []int64{1}, true, defaultNode)
	startHealthTestCore(t, config)
	waitHealthTestGroup(t, "initial healthy metadata", func(group healthTestGroupStatus) bool { return healthTestActive(group, 1) })
	held, marker, err := dialHealthTestStream(address, "group.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("initial metadata stream: marker=%q err=%v", marker, err)
	}
	defer held.Close()
	for _, test := range []struct {
		name   string
		mutate func(map[string]any, map[string]any, map[string]any, map[string]any)
	}{
		{"unsupported URL", func(_ map[string]any, metadata, _ map[string]any, _ map[string]any) {
			metadata["url"] = "ftp://probe.invalid/probe"
		}},
		{"interval too short", func(_ map[string]any, metadata, _ map[string]any, _ map[string]any) { metadata["intervalSeconds"] = 4 }},
		{"interval too long", func(_ map[string]any, metadata, _ map[string]any, _ map[string]any) {
			metadata["intervalSeconds"] = 3601
		}},
		{"interval zero", func(_ map[string]any, metadata, _ map[string]any, _ map[string]any) { metadata["intervalSeconds"] = 0 }},
		{"interval negative", func(_ map[string]any, metadata, _ map[string]any, _ map[string]any) { metadata["intervalSeconds"] = -5 }},
		{"interval string", func(_ map[string]any, metadata, _ map[string]any, _ map[string]any) {
			metadata["intervalSeconds"] = "5"
		}},
		{"interval fractional", func(_ map[string]any, metadata, _ map[string]any, _ map[string]any) {
			metadata["intervalSeconds"] = 5.5
		}},
		{"interval null", func(_ map[string]any, metadata, _ map[string]any, _ map[string]any) {
			metadata["intervalSeconds"] = nil
		}},
		{"missing outbound", func(_ map[string]any, _ map[string]any, group, _ map[string]any) {
			group["members"].([]any)[0].(map[string]any)["tag"] = "missing-member"
		}},
		{"duplicate member", func(_ map[string]any, _ map[string]any, group, _ map[string]any) {
			members := group["members"].([]any)
			group["members"] = append(members, members[0])
		}},
		{"duplicate group", func(_ map[string]any, metadata, group, _ map[string]any) {
			metadata["groups"] = append(metadata["groups"].([]any), group)
		}},
		{"unmanaged strategy", func(_ map[string]any, _ map[string]any, group, _ map[string]any) { group["strategy"] = "leastPing" }},
		{"nonblocking initial selector", func(_ map[string]any, _ map[string]any, _ map[string]any, balancer map[string]any) {
			balancer["selector"] = []string{"member.1"}
		}},
		{"default fallback", func(_ map[string]any, _ map[string]any, _ map[string]any, balancer map[string]any) {
			balancer["fallbackTag"] = "synthetic-default"
		}},
		{"nonblackhole block target", func(document map[string]any, _ map[string]any, _ map[string]any, _ map[string]any) {
			for _, value := range document["outbounds"].([]any) {
				entry := value.(map[string]any)
				if entry["tag"] == healthTestBlock {
					entry["protocol"] = "freedom"
				}
			}
		}},
	} {
		t.Run(test.name, func(t *testing.T) {
			var document map[string]any
			if err := json.Unmarshal([]byte(config), &document); err != nil {
				t.Fatal(err)
			}
			metadata := document["_yaxcLiveHealth"].(map[string]any)
			group := metadata["groups"].([]any)[0].(map[string]any)
			balancer := document["routing"].(map[string]any)["balancers"].([]any)[0].(map[string]any)
			test.mutate(document, metadata, group, balancer)
			encoded, err := json.Marshal(document)
			if err != nil {
				t.Fatal(err)
			}
			if err := ApplyRouting(string(encoded)); err == nil {
				t.Fatal("invalid health metadata was accepted")
			}
			current := readHealthTestStatus(t)
			if len(current.Groups) != 1 || !healthTestActive(current.Groups[0], 1) {
				t.Fatalf("rejected metadata changed the committed health runtime: %+v", current)
			}
			requireHealthTestTarget(t, address, 'A')
			requireHealthTestPayload(t, held, "held stream after rejected health metadata")
		})
	}
}

func TestLiveHealthRoutingOnlyHotApplyNeverInterruptsNewOrHeldFlows(t *testing.T) {
	useFastHealthTestOptions(t)
	primary := newHealthTestNode(t, 'A', 204, false)
	backup := newHealthTestNode(t, 'B', 204, false)
	defaultNode := newHealthTestNode(t, 'D', 204, false)
	address, port := healthTestListenAddress(t)
	config := healthTestConfig(t, port, "http://probe.invalid/probe", "Select", []*healthTestNode{primary, backup}, []int64{1, 2}, true, defaultNode)
	startHealthTestCore(t, config)
	waitHealthTestGroup(t, "both unchanged graphs healthy before hot applies", func(group healthTestGroupStatus) bool {
		return healthTestActive(group, 1) && reflect.DeepEqual(group.EligibleIDs, []int64{1, 2})
	})
	held, marker, err := dialHealthTestStream(address, "group.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("held stream before routing-only applies: marker=%q err=%v", marker, err)
	}
	defer held.Close()
	requireHealthTestPayload(t, held, "before continuous routing-only applies")

	ctx, cancel := context.WithCancel(context.Background())
	var workers sync.WaitGroup
	var completed atomic.Int64
	failures := make(chan error, 1)
	ready := make(chan struct{}, 4)
	fail := func(err error) {
		select {
		case failures <- err:
		default:
		}
		cancel()
	}
	for worker := range 4 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			payload := append([]byte(fmt.Sprintf("continuous worker %d\x00\xff\n", worker)), bytes.Repeat([]byte("routing-only exact payload 東京"), 128)...)
			received := make([]byte, len(payload))
			for flow := 0; ; flow++ {
				select {
				case <-ctx.Done():
					return
				default:
				}
				conn, recipient, err := dialHealthTestStream(address, "group.invalid")
				if err != nil {
					fail(fmt.Errorf("worker %d flow %d refused during a healthy routing-only apply: %w", worker, flow, err))
					return
				}
				if recipient != 'A' {
					conn.Close()
					fail(fmt.Errorf("worker %d flow %d reached %q instead of the unchanged healthy primary", worker, flow, recipient))
					return
				}
				conn.SetDeadline(time.Now().Add(time.Second))
				written, err := conn.Write(payload)
				if err == nil && written != len(payload) {
					err = io.ErrShortWrite
				}
				if err == nil {
					_, err = io.ReadFull(conn, received)
				}
				conn.Close()
				if err != nil {
					fail(fmt.Errorf("worker %d flow %d payload failed during routing-only apply: %w", worker, flow, err))
					return
				}
				if !bytes.Equal(received, payload) {
					fail(fmt.Errorf("worker %d flow %d response payload changed during routing-only apply", worker, flow))
					return
				}
				completed.Add(1)
				if flow == 0 {
					ready <- struct{}{}
				}
			}
		}()
	}
	defer func() {
		cancel()
		workers.Wait()
	}()
	for range 4 {
		select {
		case <-ready:
		case err := <-failures:
			t.Fatal(err)
		case <-time.After(3 * time.Second):
			t.Fatal("continuous traffic workers did not establish their initial real flows")
		}
	}

	var document map[string]any
	if err := json.Unmarshal([]byte(config), &document); err != nil {
		t.Fatal(err)
	}
	routing := document["routing"].(map[string]any)
	groupRule := routing["rules"].([]any)[0]
	for iteration := range 200 {
		// Only an unrelated rule changes. Every graph and its current healthy
		// observation must remain eligible throughout the router transaction.
		routing["rules"] = []any{groupRule, map[string]any{
			"type": "field", "domain": []string{fmt.Sprintf("full:unrelated-%d.invalid", iteration%2)},
			"outboundTag": "synthetic-default",
		}}
		encoded, err := json.Marshal(document)
		if err != nil {
			t.Fatal(err)
		}
		before := completed.Load()
		if err := ApplyRouting(string(encoded)); err != nil {
			t.Fatalf("routing-only apply %d: %v", iteration, err)
		}
		deadline := time.Now().Add(3 * time.Second)
		for completed.Load() == before {
			select {
			case err := <-failures:
				t.Fatal(err)
			default:
			}
			if time.Now().After(deadline) {
				t.Fatalf("new application flows stopped completing at routing-only apply %d", iteration)
			}
			time.Sleep(time.Millisecond)
		}
		select {
		case err := <-failures:
			t.Fatal(err)
		default:
		}
		if iteration%20 == 0 {
			requireHealthTestPayload(t, held, "held stream during routing-only applies")
		}
	}
	cancel()
	workers.Wait()
	select {
	case err := <-failures:
		t.Fatal(err)
	default:
	}
	requireHealthTestPayload(t, held, "held stream after 200 routing-only applies")
	requireHealthTestTarget(t, address, 'A')
	if backup.streams.Load() != 0 || defaultNode.streams.Load() != 0 {
		t.Fatal("routing-only applies sent healthy Select traffic to a backup or the default outbound")
	}
}

func TestLiveHealthLeastPingPublicationIsStableAndFailClosed(t *testing.T) {
	now := time.Now()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	records := []*healthRecord{
		{state: "available", delayMs: 30, checkedAt: now},
		{state: "available", delayMs: 10, checkedAt: now},
		{state: "available", delayMs: 10, checkedAt: now},
	}
	group := &healthGroup{
		config: healthGroupConfig{Strategy: "LeastPing"}, records: records,
		tags: []string{"first", "second", "third"}, candidates: &healthCandidates{},
	}
	plan := &healthPlan{options: liveHealthDefaults, groups: []*healthGroup{group}, records: map[string]*healthRecord{"a": records[0], "b": records[1], "c": records[2]}}
	monitor := &liveHealth{ctx: ctx, plan: plan}
	requireCandidates := func(want []string) {
		t.Helper()
		monitor.publishLocked(now)
		if got := group.candidates.Candidates(); !reflect.DeepEqual(got, want) {
			t.Fatalf("least-ping candidates = %v, want %v", got, want)
		}
	}
	requireCandidates([]string{"second"})
	snapshot := group.candidates.snapshot.Load()
	requireCandidates([]string{"second"})
	if group.candidates.snapshot.Load() != snapshot {
		t.Fatal("unchanged least-ping observations allocated a new candidate snapshot")
	}
	records[0].delayMs = 10
	requireCandidates([]string{"first"})
	records[0].delayMs = -1
	requireCandidates([]string{"second"})
	records[1].state = "unavailable"
	requireCandidates([]string{"third"})
	records[2].delayMs = -1
	requireCandidates(nil)
	records[2].delayMs = 0
	requireCandidates([]string{"third"})
	monitor.failed = true
	requireCandidates(nil)
	monitor.failed = false
	cancel()
	requireCandidates(nil)
}

func TestLiveHealthIntervalPlansAndStaleness(t *testing.T) {
	node := newHealthTestNode(t, 'A', 204, false)
	config := healthTestConfig(t, 10808, "http://probe.invalid/probe", "LeastPing", []*healthTestNode{node}, []int64{1}, true, node)
	var document map[string]any
	if err := json.Unmarshal([]byte(config), &document); err != nil {
		t.Fatal(err)
	}
	ordered, err := outboundDocuments(document)
	if err != nil {
		t.Fatal(err)
	}
	entries := make(map[string]*liveOutbound)
	for _, entry := range ordered {
		entries[entry.key] = entry
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	monitor := &liveHealth{ctx: ctx, options: liveHealthDefaults, changed: make(chan struct{})}
	_, initial, err := prepareLiveHealth(nil, document, entries, monitor)
	if err != nil {
		t.Fatal(err)
	}
	if initial.options.interval != 10*time.Second || initial.options.staleAfter != 35*time.Second {
		t.Fatalf("omitted interval changed production defaults: %+v", initial.options)
	}
	monitor.replace(initial)
	var record *healthRecord
	for _, member := range initial.records {
		record = member
	}
	record.inFlight = true
	canceled := false
	record.cancel = func() { canceled = true }
	record.lastStarted = time.Now()
	record.next = record.lastStarted.Add(initial.options.interval)
	metadata := document[liveHealthMetadata].(map[string]any)
	for _, seconds := range []int{3600, 5} {
		metadata["intervalSeconds"] = seconds
		_, plan, err := prepareLiveHealth(nil, document, entries, monitor)
		if err != nil {
			t.Fatal(err)
		}
		interval := time.Duration(seconds) * time.Second
		if plan.options.interval != interval || plan.options.staleAfter != 3*interval+5*time.Second {
			t.Fatalf("interval %d options: %+v", seconds, plan.options)
		}
		if plan.records[record.key] != record {
			t.Fatal("interval-only plan discarded an unchanged observation")
		}
		if monitor.plan.options.interval == interval {
			t.Fatal("preparing an interval changed the committed plan before acceptance")
		}
		monitor.replace(plan)
		if canceled || !record.inFlight {
			t.Fatal("interval-only apply canceled a matching in-flight probe")
		}
		if want := record.lastStarted.Add(interval); !record.next.Equal(want) {
			t.Fatalf("hot interval scheduled next=%s, want last start + %s", record.next, interval)
		}
		now := time.Now()
		record.state, record.recovering, record.successes = "available", false, 1
		record.checkedAt, record.delayMs = now.Add(-plan.options.staleAfter+time.Second), 5
		monitor.publishLocked(now)
		if got := plan.groups[0].candidates.Candidates(); len(got) != 1 {
			t.Fatalf("interval %d prematurely expired a fresh observation: %v", seconds, got)
		}
		record.checkedAt = now.Add(-plan.options.staleAfter)
		monitor.publishLocked(now)
		if got := plan.groups[0].candidates.Candidates(); len(got) != 0 || record.state != "checking" || !record.recovering || record.successes != 0 {
			t.Fatalf("interval %d did not expire at its staleness boundary: record=%+v candidates=%v", seconds, record, got)
		}
	}
	if monitor.options.interval != 10*time.Second || initial.options.interval != 10*time.Second {
		t.Fatal("hot interval mutated shared monitor or retained plan options")
	}
}

func TestLiveHealthLeastPingRealTrafficAndIntervalHotApply(t *testing.T) {
	// Production timeout, interval bounds and worker pool: no accelerated defaults.
	a := newHealthTestNode(t, 'A', 204, false)
	b := newHealthTestNode(t, 'B', 204, false)
	defaultNode := newHealthTestNode(t, 'D', 204, false)
	a.delay.Store(int64(300 * time.Millisecond))
	b.delay.Store(int64(5 * time.Millisecond))
	address, port := healthTestListenAddress(t)
	config := healthTestConfig(t, port, "http://probe.invalid/probe", "LeastPing", []*healthTestNode{a, b}, []int64{1, 2}, true, defaultNode)
	var document map[string]any
	if err := json.Unmarshal([]byte(config), &document); err != nil {
		t.Fatal(err)
	}
	metadata := document[liveHealthMetadata].(map[string]any)
	encode := func(seconds int) string {
		t.Helper()
		metadata["intervalSeconds"] = seconds
		encoded, err := json.Marshal(document)
		if err != nil {
			t.Fatal(err)
		}
		return string(encoded)
	}
	startHealthTestCore(t, encode(3600))
	first := time.Now()
	nextHealthTestProbe(t, a)
	nextHealthTestProbe(t, b)
	group := waitHealthTestGroup(t, "lowest measured delay, not configured first", func(group healthTestGroupStatus) bool {
		return healthTestActive(group, 2) && len(group.EligibleIDs) == 2
	})
	if healthTestMember(group, 1).DelayMS <= healthTestMember(group, 2).DelayMS {
		t.Fatalf("synthetic latency separation was not measured: %+v", group)
	}
	held, marker, err := dialHealthTestStream(address, "group.invalid")
	if err != nil || marker != 'B' {
		t.Fatalf("initial least-ping stream: marker=%q err=%v", marker, err)
	}
	defer held.Close()
	requireHealthTestPayload(t, held, "held on initially fastest member")
	requireHealthTestTarget(t, address, 'B')
	beforeA, beforeB := healthTestMember(group, 1).LastCheckedAt, healthTestMember(group, 2).LastCheckedAt
	a.delay.Store(int64(5 * time.Millisecond))
	b.delay.Store(int64(300 * time.Millisecond))
	if err := ApplyRouting(encode(5)); err != nil {
		t.Fatal(err)
	}
	after := readHealthTestStatus(t)
	if len(after.Groups) != 1 || !healthTestActive(after.Groups[0], 2) || healthTestMember(after.Groups[0], 1).LastCheckedAt != beforeA || healthTestMember(after.Groups[0], 2).LastCheckedAt != beforeB {
		t.Fatalf("interval-only apply lost the accepted observations: %+v", after)
	}
	select {
	case <-a.events:
		if elapsed := time.Since(first); elapsed < 4*time.Second || elapsed > 8*time.Second {
			t.Fatalf("hot 3600s -> 5s interval probe started after %s", elapsed)
		}
	case <-time.After(8 * time.Second):
		t.Fatal("shorter interval did not wake and reschedule the existing monitor")
	}
	select {
	case <-b.events:
	case <-time.After(3 * time.Second):
		t.Fatal("shorter interval did not reschedule the second member")
	}
	waitHealthTestGroup(t, "new lowest measured delay after hot interval", func(group healthTestGroupStatus) bool {
		return healthTestActive(group, 1) && healthTestMember(group, 1).LastCheckedAt > beforeA && healthTestMember(group, 2).LastCheckedAt > beforeB
	})
	requireHealthTestTarget(t, address, 'A')
	requireHealthTestPayload(t, held, "existing stream retains old fastest member")
	if err := ApplyRouting(encode(3600)); err != nil {
		t.Fatal(err)
	}
	// A fixed old 5s schedule would issue another request during this window.
	for range 11 {
		select {
		case <-a.events:
			t.Fatal("longer hot interval retained the old member A schedule")
		case <-b.events:
			t.Fatal("longer hot interval retained the old member B schedule")
		case <-time.After(500 * time.Millisecond):
		}
		requireHealthTestTarget(t, address, 'A')
		requireHealthTestPayload(t, held, "retained stream during longer interval")
	}
	if defaultNode.streams.Load() != 0 || defaultNode.probes.Load() != 0 {
		t.Fatal("least-ping traffic or probes escaped to the default outbound")
	}
}

func TestLiveHealthLeastPingFailuresKeepLatencyAndBlockAllDown(t *testing.T) {
	useFastHealthTestOptions(t)
	a := newHealthTestNode(t, 'A', 204, false)
	b := newHealthTestNode(t, 'B', 204, true)
	defaultNode := newHealthTestNode(t, 'D', 204, false)
	address, port := healthTestListenAddress(t)
	config := healthTestConfig(t, port, "http://probe.invalid/probe", "LeastPing", []*healthTestNode{a, b}, []int64{1, 2}, true, defaultNode)
	startHealthTestCore(t, config)
	waitHealthTestGroup(t, "first measured healthy recipient", func(group healthTestGroupStatus) bool { return healthTestActive(group, 1) })
	// The manual member has a deliberately slower successful observation.
	event := nextHealthTestProbe(t, b)
	time.Sleep(100 * time.Millisecond)
	event.reply <- 204
	healthy := waitHealthTestGroup(t, "both measured healthy", func(group healthTestGroupStatus) bool {
		return healthTestActive(group, 1) && len(group.EligibleIDs) == 2
	})
	slowDelay := healthTestMember(healthy, 2).DelayMS
	if slowDelay < 80 {
		t.Fatalf("controlled slow probe delay = %dms", slowDelay)
	}
	firstFailure := answerHealthTestProbe(t, b, 2, 503)
	if healthTestMember(firstFailure, 2).DelayMS != slowDelay || !healthTestActive(firstFailure, 1) || len(firstFailure.EligibleIDs) != 2 {
		t.Fatalf("transient failure replaced measured latency or eligibility: %+v", firstFailure)
	}
	secondFailure := answerHealthTestProbe(t, b, 2, 503)
	if healthTestMember(secondFailure, 2).State != "unavailable" || !healthTestActive(secondFailure, 1) {
		t.Fatalf("sustained failure did not remove the slow member: %+v", secondFailure)
	}
	held, marker, err := dialHealthTestStream(address, "group.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("held least-ping stream: marker=%q err=%v", marker, err)
	}
	defer held.Close()
	a.status.Store(503)
	waitHealthTestGroup(t, "all least-ping members unavailable", func(group healthTestGroupStatus) bool {
		return group.State == "unavailable" && len(group.EligibleIDs) == 0 && group.ActiveProfileID == nil
	})
	requireHealthTestBlocked(t, address, defaultNode)
	requireHealthTestPayload(t, held, "least-ping held stream survives all-down")
	b.delay.Store(int64(100 * time.Millisecond))
	for success := 1; success <= 3; success++ {
		recovery := answerHealthTestProbe(t, b, 2, 204)
		if success < 3 && recovery.ActiveProfileID != nil {
			t.Fatalf("least-ping recovered before three successes: %+v", recovery)
		}
	}
	waitHealthTestGroup(t, "recovered slower backup wins when fastest is unavailable", func(group healthTestGroupStatus) bool {
		return healthTestActive(group, 2)
	})
	requireHealthTestTarget(t, address, 'B')
	requireHealthTestPayload(t, held, "held fastest stream survives backup recovery")
	a.status.Store(204)
	waitHealthTestGroup(t, "fastest member wins after hysteretic recovery", func(group healthTestGroupStatus) bool {
		return healthTestActive(group, 1) && len(group.EligibleIDs) == 2
	})
	requireHealthTestTarget(t, address, 'A')
}
