package XrayCore_test

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net"
	"reflect"
	"strings"
	"testing"
	"time"

	xray "XrayCore"
)

func setObservedCapture(t *testing.T, enabled bool) {
	t.Helper()
	if diagnostic := xray.SetConnectionAnalyticsEnabled(enabled); diagnostic != "" {
		t.Fatal(diagnostic)
	}
}

func readObservedCapture(t *testing.T) observedFlows {
	t.Helper()
	var snapshot observedFlows
	if err := json.Unmarshal([]byte(xray.ConnectionSnapshot()), &snapshot); err != nil {
		t.Fatal(err)
	}
	return snapshot
}

func restoreObservedCapture(t *testing.T) {
	t.Helper()
	t.Cleanup(func() {
		if diagnostic := xray.Stop(); diagnostic != "" {
			t.Error(diagnostic)
		}
		if diagnostic := xray.SetConnectionAnalyticsEnabled(true); diagnostic != "" {
			t.Error(diagnostic)
		}
	})
}

func TestConnectionCaptureHotTCPPreservesForwardingAndOnlyResumesNewFlows(t *testing.T) {
	dir := isolateFacadeCore(t)
	restoreObservedCapture(t)
	peer := newLiveRoutingEcho(t, 'C')
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	config := func(id int64) string {
		return configWithObservedProfiles(t, liveRoutingConfig(port, peer, peer, peer, peer, "select"), map[string]int64{"selected": id})
	}
	setObservedCapture(t, false)
	beforeStart := readObservedCapture(t)
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, config(501))); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	xray.ClearConnectionHistory()
	dial := func() net.Conn {
		t.Helper()
		conn, marker, err := dialLiveRoutingSOCKS(address, "synthetic-default", "route.invalid")
		if err != nil || marker != 'C' {
			t.Fatalf("real TCP route failed: marker=%q err=%v", marker, err)
		}
		t.Cleanup(func() { conn.Close() })
		return conn
	}
	startedOff := dial()
	exchangeObservedPayload(t, startedOff, "off-before-start")
	initialOff := readObservedCapture(t)
	if initialOff.Enabled || !initialOff.Running || len(initialOff.Connections) != 0 || initialOff.CaptureGeneration != beforeStart.CaptureGeneration {
		t.Fatalf("disabled startup captured traffic: %+v", initialOff)
	}
	setObservedCapture(t, true)
	exchangeObservedPayload(t, startedOff, "old-startup-flow-stays-unobserved")
	held := dial()
	size := exchangeObservedPayload(t, held, "captured-before-cutoff")
	before := awaitObservedFlows(t, func(s observedFlows) bool {
		return len(s.Connections) == 1 && s.Connections[0].Upload == size
	})
	setObservedCapture(t, false)
	cutoff := readObservedCapture(t)
	if cutoff.Enabled || !cutoff.Running || cutoff.CaptureGeneration != before.CaptureGeneration+1 || cutoff.Generation != before.Generation || cutoff.Epoch != before.Epoch || len(cutoff.Connections) != 1 {
		t.Fatalf("wrong synchronous cutoff fence: %+v", cutoff)
	}
	flow := cutoff.Connections[0]
	if flow.State != "interrupted" || flow.EndedAt == 0 || flow.LiveBytesExact || flow.Upload != size {
		t.Fatalf("cutoff failed to retain interrupted/incomplete totals: %+v", flow)
	}
	setObservedCapture(t, false)
	if got := readObservedCapture(t); !reflect.DeepEqual(got, cutoff) {
		t.Fatalf("idempotent disable changed snapshot: before=%+v after=%+v", cutoff, got)
	}
	if diagnostic := xray.ApplyRouting(config(502)); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	openedOff := dial()
	for _, conn := range []net.Conn{startedOff, held, openedOff} {
		exchangeObservedPayload(t, conn, "still-forwarding-while-off")
	}
	if got := readObservedCapture(t); !reflect.DeepEqual(got, cutoff) {
		t.Fatalf("off traffic or apply changed frozen history: before=%+v after=%+v", cutoff, got)
	}
	setObservedCapture(t, true)
	for _, conn := range []net.Conn{startedOff, held, openedOff} {
		exchangeObservedPayload(t, conn, "old-flows-never-reattach")
	}
	fresh := dial()
	freshSize := exchangeObservedPayload(t, fresh, "fresh-capture-after-resume")
	after := awaitObservedFlows(t, func(s observedFlows) bool {
		return len(s.Connections) == 2 && s.Connections[1].Upload == freshSize
	})
	if !after.Enabled || after.CaptureGeneration != cutoff.CaptureGeneration+1 || after.Generation != cutoff.Generation || after.Epoch != cutoff.Epoch || !reflect.DeepEqual(after.Connections[0], flow) || after.Connections[1].ProfileID != 502 {
		t.Fatalf("resume lost history or route identity: %+v", after)
	}
	held.Close()
	openedOff.Close()
	startedOff.Close()
	setObservedCapture(t, false)
	final := readObservedCapture(t)
	if len(final.Connections) != 2 || !reflect.DeepEqual(final.Connections[0], flow) || final.Connections[1].State != "interrupted" || final.Connections[1].LiveBytesExact {
		t.Fatalf("later finalization revived frozen flow: %+v", final)
	}
	xray.ClearConnectionHistory()
	cleared := readObservedCapture(t)
	if cleared.Enabled || len(cleared.Connections) != 0 || cleared.Generation != final.Generation+1 || cleared.CaptureGeneration != final.CaptureGeneration {
		t.Fatalf("clear-off enabled capture or retained cleared rows: %+v", cleared)
	}
	if diagnostic := xray.Stop(); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, config(503))); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	reloadedOff := dial()
	exchangeObservedPayload(t, reloadedOff, "disabled-preference-after-reload")
	reloaded := readObservedCapture(t)
	if reloaded.Enabled || !reloaded.Running || len(reloaded.Connections) != 0 || reloaded.CaptureGeneration != cleared.CaptureGeneration || reloaded.Generation != cleared.Generation || reloaded.Epoch != cleared.Epoch {
		t.Fatalf("reload discarded disabled preference or fences: %+v", reloaded)
	}
}

func TestConnectionCaptureHotUDPKeepsOldAssociationsUnobservedAfterResume(t *testing.T) {
	dir := isolateFacadeCore(t)
	restoreObservedCapture(t)
	setObservedCapture(t, true)
	peer, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
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
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	config := fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"tag":"socks","listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"yaxc.uid.10001","pass":"synthetic-password"}],"udp":true}}],"outbounds":[{"tag":"udp-route","protocol":"freedom"}],"_yaxcConnectionProfiles":{"udp-route":{"profileId":601,"profileFingerprint":"%s","route":"proxy"}}}`, port, strings.Repeat("c", 64))
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, config)); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	xray.ClearConnectionHistory()
	exchange := func(client *net.UDPConn, label string) int64 {
		t.Helper()
		targetPort := peer.LocalAddr().(*net.UDPAddr).Port
		payload := []byte(label + "\x00東京")
		packet := append([]byte{0, 0, 0, 1, 127, 0, 0, 1, byte(targetPort >> 8), byte(targetPort)}, payload...)
		client.SetDeadline(time.Now().Add(3 * time.Second))
		if _, err := client.Write(packet); err != nil {
			t.Fatal(err)
		}
		var response [2048]byte
		size, err := client.Read(response[:])
		if err != nil || size < 10 || !bytes.Equal(response[10:size], payload) {
			t.Fatalf("real UDP echo changed: size=%d err=%v", size, err)
		}
		return int64(len(payload))
	}
	_, held := openObservedUDPAssociation(t, address)
	size := exchange(held, "captured-udp")
	before := awaitObservedFlows(t, func(s observedFlows) bool {
		return len(s.Connections) == 1 && s.Connections[0].Upload == size && s.Connections[0].Download == size
	})
	setObservedCapture(t, false)
	cutoff := readObservedCapture(t)
	if cutoff.CaptureGeneration != before.CaptureGeneration+1 || len(cutoff.Connections) != 1 || cutoff.Connections[0].State != "interrupted" || cutoff.Connections[0].LiveBytesExact {
		t.Fatalf("UDP cutoff not finalized: %+v", cutoff)
	}
	_, openedOff := openObservedUDPAssociation(t, address)
	exchange(held, "held-off")
	exchange(openedOff, "opened-off")
	if got := readObservedCapture(t); !reflect.DeepEqual(got, cutoff) {
		t.Fatalf("UDP off period changed analytics: %+v", got)
	}
	setObservedCapture(t, true)
	exchange(held, "held-on-remains-inert")
	exchange(openedOff, "opened-off-on-remains-inert")
	_, fresh := openObservedUDPAssociation(t, address)
	freshSize := exchange(fresh, "fresh-on")
	after := awaitObservedFlows(t, func(s observedFlows) bool {
		return len(s.Connections) == 2 && s.Connections[1].Upload == freshSize && s.Connections[1].Download == freshSize
	})
	if !reflect.DeepEqual(after.Connections[0], cutoff.Connections[0]) || after.Epoch != cutoff.Epoch || after.Generation != cutoff.Generation || after.CaptureGeneration != cutoff.CaptureGeneration+1 {
		t.Fatalf("UDP resumed old accounting or lost fences: %+v", after)
	}
}
