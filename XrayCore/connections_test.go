package XrayCore_test

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	xray "XrayCore"
)

type observedFlow struct {
	ID              string   `json:"id"`
	Protocol        string   `json:"protocol"`
	Destination     string   `json:"destination"`
	Destinations    []string `json:"destinations"`
	DestinationKind string   `json:"destinationKind"`
	StartedAt       int64    `json:"startedAt"`
	EndedAt         int64    `json:"endedAt"`
	State           string   `json:"state"`
	Upload          int64    `json:"upload"`
	Download        int64    `json:"download"`
	LiveBytesExact  bool     `json:"liveBytesExact"`
	UID             int      `json:"uid"`
	ProfileID       int64    `json:"profileId"`
	Fingerprint     string   `json:"profileFingerprint"`
	OutboundTag     string   `json:"outboundTag"`
	Route           string   `json:"route"`
}

type observedFlows struct {
	Running           bool           `json:"running"`
	Enabled           bool           `json:"enabled"`
	CaptureGeneration uint64         `json:"captureGeneration"`
	Epoch             string         `json:"epoch"`
	Generation        uint64         `json:"generation"`
	NativeEvicted     uint64         `json:"nativeEvicted"`
	NativeRejected    uint64         `json:"nativeRejected"`
	Connections       []observedFlow `json:"connections"`
}

func awaitObservedFlows(t *testing.T, accept func(observedFlows) bool) observedFlows {
	t.Helper()
	deadline := time.Now().Add(4 * time.Second)
	var snapshot observedFlows
	for {
		if err := json.Unmarshal([]byte(xray.ConnectionSnapshot()), &snapshot); err != nil {
			t.Fatal(err)
		}
		if accept(snapshot) {
			return snapshot
		}
		if time.Now().After(deadline) {
			t.Fatalf("connection observer did not converge: %+v", snapshot)
		}
		time.Sleep(time.Millisecond)
	}
}

func configWithObservedProfiles(t *testing.T, config string, ids map[string]int64) string {
	t.Helper()
	var root map[string]any
	if err := json.Unmarshal([]byte(config), &root); err != nil {
		t.Fatal(err)
	}
	profiles := make(map[string]any, len(ids))
	for tag, id := range ids {
		profiles[tag] = map[string]any{"profileId": id, "profileFingerprint": strings.Repeat("a", 64), "route": "proxy"}
	}
	root["_yaxcConnectionProfiles"] = profiles
	encoded, err := json.Marshal(root)
	if err != nil {
		t.Fatal(err)
	}
	return string(encoded)
}

func exchangeObservedPayload(t *testing.T, conn net.Conn, value string) int64 {
	t.Helper()
	payload := []byte(value + "\x00\xff東京")
	if _, err := conn.Write(payload); err != nil {
		t.Fatal(err)
	}
	actual := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, actual); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(payload, actual) {
		t.Fatal("real echo payload changed")
	}
	return int64(len(payload))
}

func TestConnectionAttributionTracksActualRouteAndNeverRelabelsRetainedStreams(t *testing.T) {
	dir := isolateFacadeCore(t)
	a, b := newLiveRoutingEcho(t, 'A'), newLiveRoutingEcho(t, 'B')
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	config := func(selected string, profileID int64) string {
		return configWithObservedProfiles(t, liveRoutingConfig(port, a, selected, a, b, "select", "yaxc.uid.10001"),
			map[string]int64{"default": 101, "selected": profileID, "member.a": 201, "member.b": 202})
	}
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, config(a, 102))); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	xray.ClearConnectionHistory()
	held, marker, err := dialLiveRoutingSOCKS(address, "synthetic-default", "route.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("initial route: marker=%q err=%v", marker, err)
	}
	defer held.Close()
	firstSize := exchangeObservedPayload(t, held, "old-profile-before-apply")
	initial := awaitObservedFlows(t, func(s observedFlows) bool {
		return len(s.Connections) == 1 && s.Connections[0].Upload == firstSize
	})
	original := initial.Connections[0]
	if original.ProfileID != 102 || original.UID != -1 || original.Upload != firstSize || original.State != "active" ||
		original.OutboundTag != "selected" || original.Destination != "route.invalid:80" ||
		original.Download > firstSize+1 || (original.LiveBytesExact && original.Download != firstSize+1) {
		t.Fatalf("wrong initial real-flow attribution: %+v", original)
	}

	uidFlow, marker, err := dialLiveRoutingSOCKS(address, "yaxc.uid.10001", "uid.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("UID route: marker=%q err=%v", marker, err)
	}
	uidSize := exchangeObservedPayload(t, uidFlow, "authenticated-original-owner")
	uidFlow.Close()
	awaitObservedFlows(t, func(s observedFlows) bool {
		for _, flow := range s.Connections {
			if flow.UID == 10001 && flow.ProfileID == 201 && flow.Upload == uidSize && flow.Download == uidSize+1 && flow.EndedAt > 0 {
				return true
			}
		}
		return false
	})

	if diagnostic := xray.ApplyRouting(config(b, 103)); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	second, marker, err := dialLiveRoutingSOCKS(address, "synthetic-default", "route.invalid")
	if err != nil || marker != 'B' {
		t.Fatalf("updated route: marker=%q err=%v", marker, err)
	}
	defer second.Close()
	secondSize := exchangeObservedPayload(t, second, "new-profile-after-apply")
	// Identity-only changes still need a distinct routing alias, not a shared
	// mutable physical-tag lookup that could rewrite an established flow.
	if diagnostic := xray.ApplyRouting(config(b, 104)); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	third, marker, err := dialLiveRoutingSOCKS(address, "synthetic-default", "route.invalid")
	if err != nil || marker != 'B' {
		t.Fatalf("identity-only route: marker=%q err=%v", marker, err)
	}
	defer third.Close()
	thirdSize := exchangeObservedPayload(t, third, "same-graph-different-config")
	firstSize += exchangeObservedPayload(t, held, "retained-real-old-route")
	xray.ClearConnectionHistory()
	awaitObservedFlows(t, func(s observedFlows) bool {
		if !s.Running || s.Epoch != initial.Epoch || len(s.Connections) != 3 {
			return false
		}
		sizes := map[int64]int64{102: firstSize, 103: secondSize, 104: thirdSize}
		for _, flow := range s.Connections {
			size, ok := sizes[flow.ProfileID]
			if !ok || flow.UID != -1 || flow.State != "active" || flow.Upload != size ||
				flow.Download > size+1 || (flow.LiveBytesExact && flow.Download != size+1) {
				return false
			}
			delete(sizes, flow.ProfileID)
		}
		return len(sizes) == 0
	})
	held.Close()
	second.Close()
	third.Close()
	awaitObservedFlows(t, func(s observedFlows) bool {
		if len(s.Connections) != 3 {
			return false
		}
		sizes := map[int64]int64{102: firstSize, 103: secondSize, 104: thirdSize}
		for _, flow := range s.Connections {
			size, ok := sizes[flow.ProfileID]
			if !ok || flow.EndedAt <= 0 || flow.State == "active" || !flow.LiveBytesExact ||
				flow.Upload != size || flow.Download != size+1 {
				return false
			}
		}
		return true
	})
	if diagnostic := xray.Stop(); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	if snapshot := awaitObservedFlows(t, func(s observedFlows) bool { return !s.Running }); len(snapshot.Connections) != 3 {
		t.Fatal("stopping discarded closed observations")
	}
}

func openObservedUDPAssociation(t *testing.T, address string) (net.Conn, *net.UDPConn) {
	t.Helper()
	control, err := net.DialTimeout("tcp", address, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { control.Close() })
	control.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := control.Write([]byte{5, 1, 2}); err != nil {
		t.Fatal(err)
	}
	var method [2]byte
	if _, err := io.ReadFull(control, method[:]); err != nil || method != [2]byte{5, 2} {
		t.Fatalf("SOCKS auth negotiation: %v %v", method, err)
	}
	user, password := "yaxc.uid.10001", "synthetic-password"
	auth := append([]byte{1, byte(len(user))}, []byte(user)...)
	auth = append(auth, byte(len(password)))
	auth = append(auth, []byte(password)...)
	if _, err := control.Write(auth); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(control, method[:]); err != nil || method != [2]byte{1, 0} {
		t.Fatalf("SOCKS auth: %v %v", method, err)
	}
	if _, err := control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		t.Fatal(err)
	}
	var reply [10]byte
	if _, err := io.ReadFull(control, reply[:]); err != nil || reply[0] != 5 || reply[1] != 0 || reply[3] != 1 {
		t.Fatalf("UDP association: %v %v", reply, err)
	}
	endpoint := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(binary.BigEndian.Uint16(reply[8:]))}
	client, err := net.DialUDP("udp", nil, endpoint)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { client.Close() })
	return control, client
}

func TestUDPConnectionObservationReportsAssociationDestinationsAndExactPayloadBytes(t *testing.T) {
	dir := isolateFacadeCore(t)
	var destinations []*net.UDPConn
	for range 2 {
		peer, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			t.Fatal(err)
		}
		destinations = append(destinations, peer)
		done := make(chan struct{})
		go func() {
			defer close(done)
			var buffer [2048]byte
			for {
				size, remote, err := peer.ReadFromUDP(buffer[:])
				if err != nil {
					return
				}
				if _, err := peer.WriteToUDP(buffer[:size], remote); err != nil {
					return
				}
			}
		}()
		t.Cleanup(func() { peer.Close(); <-done })
	}
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	config := fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"tag":"socks","listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"yaxc.uid.10001","pass":"synthetic-password"}],"udp":true}}],"outbounds":[{"tag":"udp-route","protocol":"freedom","settings":{"domainStrategy":"AsIs"}}],"_yaxcConnectionProfiles":{"udp-route":{"profileId":301,"profileFingerprint":"%s","route":"proxy"}}}`, port, strings.Repeat("b", 64))
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, config)); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	xray.ClearConnectionHistory()
	control, client := openObservedUDPAssociation(t, address)
	var total int64
	for index := range 4 {
		port := destinations[index%2].LocalAddr().(*net.UDPAddr).Port
		payload := bytes.Repeat([]byte{byte(index + 1)}, 257+index)
		packet := append([]byte{0, 0, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)}, payload...)
		client.SetDeadline(time.Now().Add(3 * time.Second))
		if _, err := client.Write(packet); err != nil {
			t.Fatal(err)
		}
		var response [2048]byte
		size, err := client.Read(response[:])
		if err != nil {
			t.Fatal(err)
		}
		if size < 10 || !bytes.Equal(response[10:size], payload) {
			t.Fatal("UDP real payload or SOCKS framing changed")
		}
		total += int64(len(payload))
	}
	awaitObservedFlows(t, func(s observedFlows) bool {
		if len(s.Connections) != 1 {
			return false
		}
		flow := s.Connections[0]
		return flow.Protocol == "udp" && flow.DestinationKind == "association" && len(flow.Destinations) == 2 &&
			flow.ProfileID == 301 && flow.UID == 10001 && flow.Upload == total && flow.Download == total
	})
	control.Close()
	client.Close()
	if diagnostic := xray.Stop(); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	awaitObservedFlows(t, func(s observedFlows) bool {
		return !s.Running && len(s.Connections) == 1 && s.Connections[0].EndedAt > 0
	})
}

func TestConnectionIdentityAliasFollowsStockFallbackBalancerWithoutRelabelingOldFlows(t *testing.T) {
	dir := isolateFacadeCore(t)
	peer := newLiveRoutingEcho(t, 'A')
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	config := func(id int64) string {
		raw := configWithObservedProfiles(t, liveRoutingConfig(port, peer, peer, peer, peer, "random"),
			map[string]int64{"member.a": id})
		var document map[string]any
		if err := json.Unmarshal([]byte(raw), &document); err != nil {
			t.Fatal(err)
		}
		balancer := document["routing"].(map[string]any)["balancers"].([]any)[0].(map[string]any)
		balancer["selector"] = []string{"member.a"}
		balancer["fallbackTag"] = "default"
		document["observatory"] = map[string]any{"subjectSelector": []string{}}
		encoded, err := json.Marshal(document)
		if err != nil {
			t.Fatal(err)
		}
		return string(encoded)
	}
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, config(201))); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	xray.ClearConnectionHistory()
	held, marker, err := dialLiveRoutingSOCKS(address, "synthetic-default", "group.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("old stock balancer route: marker=%q err=%v", marker, err)
	}
	defer held.Close()
	exchangeObservedPayload(t, held, "old-stock-balancer")
	awaitObservedFlows(t, func(snapshot observedFlows) bool {
		return len(snapshot.Connections) == 1 && snapshot.Connections[0].ProfileID == 201
	})
	if diagnostic := xray.ApplyRouting(config(301)); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	next, marker, err := dialLiveRoutingSOCKS(address, "synthetic-default", "group.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("new stock balancer route: marker=%q err=%v", marker, err)
	}
	defer next.Close()
	exchangeObservedPayload(t, next, "new-stock-balancer")
	exchangeObservedPayload(t, held, "old-stock-balancer-retained")
	awaitObservedFlows(t, func(snapshot observedFlows) bool {
		if len(snapshot.Connections) != 2 {
			return false
		}
		return snapshot.Connections[0].ProfileID == 201 && snapshot.Connections[1].ProfileID == 301
	})
}

func TestConnectionObservationDoesNotLimitValidLargeOutboundConfigurations(t *testing.T) {
	dir := isolateFacadeCore(t)
	peer := newLiveRoutingEcho(t, 'L')
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	const count = 4097
	outbounds := make([]any, 0, count)
	profiles := make(map[string]any, count)
	for index := range count {
		tag := fmt.Sprintf("large.%d", index)
		outbounds = append(outbounds, map[string]any{"tag": tag, "protocol": "freedom", "settings": map[string]any{"redirect": peer}})
		profiles[tag] = map[string]any{"profileId": index + 1, "profileFingerprint": strings.Repeat("a", 64), "route": "direct"}
	}
	config := map[string]any{
		"log": map[string]any{"loglevel": "none"},
		"inbounds": []any{map[string]any{"tag": "socks", "listen": "127.0.0.1", "port": port, "protocol": "socks",
			"settings": map[string]any{"auth": "password", "accounts": []any{map[string]any{"user": "synthetic-default", "pass": "synthetic-password"}}}}},
		"outbounds":               outbounds,
		"routing":                 map[string]any{"rules": []any{map[string]any{"type": "field", "domain": []string{"full:large.invalid"}, "outboundTag": "large.4096"}}},
		"_yaxcConnectionProfiles": profiles,
	}
	encoded, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, string(encoded))); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	xray.ClearConnectionHistory()
	conn, marker, err := dialLiveRoutingSOCKS(address, "synthetic-default", "large.invalid")
	if err != nil || marker != 'L' {
		t.Fatalf("large configuration traffic failed: marker=%q err=%v", marker, err)
	}
	defer conn.Close()
	size := exchangeObservedPayload(t, conn, "large-configuration-selected-last-outbound")
	awaitObservedFlows(t, func(snapshot observedFlows) bool {
		return len(snapshot.Connections) == 1 && snapshot.Connections[0].ProfileID == count &&
			snapshot.Connections[0].OutboundTag == "large.4096" && snapshot.Connections[0].Upload == size
	})
}

func TestConnectionSnapshotExportsSeparateNativeEventCountersAndClearGeneration(t *testing.T) {
	var snapshot map[string]json.RawMessage
	if err := json.Unmarshal([]byte(xray.ConnectionSnapshot()), &snapshot); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"running", "enabled", "captureGeneration", "epoch", "generation", "nativeEvicted", "nativeRejected", "connections"} {
		if _, exists := snapshot[name]; !exists {
			t.Fatalf("missing connection snapshot field %q", name)
		}
	}
	if _, exists := snapshot["dropped"]; exists {
		t.Fatal("connection snapshot still exports an ambiguous aggregate")
	}
}
