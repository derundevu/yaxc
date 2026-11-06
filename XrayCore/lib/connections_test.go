package lib

import (
	"fmt"
	"strings"
	"sync"
	"testing"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
)

func connectionLedgerFixture() *connectionLedger {
	return &connectionLedger{connectionHistory: &connectionHistory{epoch: "isolated-history", records: make(map[uint64]*observedConnection)},
		runtimeID: "first-core", accepting: true,
		routes: map[string]connectionProfile{"proxy": {ProfileID: 41, Fingerprint: strings.Repeat("a", 64), Route: "proxy", logicalTag: "proxy"}}}
}

func connectionMetadataFixture(index int) session.ConnectionMetadata {
	return session.ConnectionMetadata{Network: net.Network_TCP,
		Destination: net.TCPDestination(net.DomainAddress(fmt.Sprintf("flow-%d.invalid", index)), 443), User: "yaxc.uid.10001"}
}

func TestConnectionHistorySaturationNeverEvictsAnActiveFlowOrChangesItsCounters(t *testing.T) {
	ledger := connectionLedgerFixture()
	ledger.started()
	active := make([]session.ConnectionObservation, maxConnectionRecords)
	for index := range active {
		active[index] = ledger.Open(connectionMetadataFixture(index))
	}
	if extra := ledger.Open(connectionMetadataFixture(maxConnectionRecords)); extra != nil {
		t.Fatal("full active history admitted an unbounded observation")
	}
	active[1].AddUpload(73)
	active[1].AddDownload(127)
	active[0].Finish("closed")
	if next := ledger.Open(connectionMetadataFixture(maxConnectionRecords + 1)); next == nil {
		t.Fatal("closed-row eviction did not admit a new real flow")
	}
	snapshot := ledger.snapshot()
	if len(snapshot.Connections) != maxConnectionRecords || snapshot.Dropped != 2 {
		t.Fatalf("wrong bounded history: count=%d dropped=%d", len(snapshot.Connections), snapshot.Dropped)
	}
	for _, flow := range snapshot.Connections {
		if flow.Destination == "flow-0.invalid:443" {
			t.Fatal("oldest closed row was not evicted")
		}
		if flow.Destination == "flow-1.invalid:443" && (flow.Upload != 73 || flow.Download != 127 || flow.State != "active") {
			t.Fatal("active counters changed during eviction")
		}
	}
}

func TestStoppedCoreLateBytesRemainVisibleAcrossNewCoreWithoutRelabeling(t *testing.T) {
	first := connectionLedgerFixture()
	first.started()
	old := first.Open(connectionMetadataFixture(1))
	old.AddUpload(17)
	first.stop()
	second := &connectionLedger{connectionHistory: first.connectionHistory, runtimeID: "second-core", accepting: true,
		routes: map[string]connectionProfile{"proxy": {ProfileID: 42, Fingerprint: strings.Repeat("b", 64), Route: "proxy", logicalTag: "proxy"}}}
	second.started()
	newFlow := second.Open(connectionMetadataFixture(2))
	newFlow.Routed(session.ConnectionRoute{OutboundTag: "proxy"})
	// A canceled old dispatcher may publish/finish after the replacement has started.
	old.Routed(session.ConnectionRoute{OutboundTag: "proxy"})
	old.AddDownload(89)
	old.Finish("closed")
	snapshot := second.snapshot()
	if !snapshot.Running || len(snapshot.Connections) != 2 {
		t.Fatalf("replacement lost shared history: %+v", snapshot)
	}
	for _, flow := range snapshot.Connections {
		switch flow.Destination {
		case "flow-1.invalid:443":
			if flow.ProfileID != 41 || flow.State != "interrupted" || flow.EndedAt == 0 || flow.Upload != 17 || flow.Download != 89 || !flow.LiveBytesExact {
				t.Fatalf("old flow relabeled or lost final credit: %+v", flow)
			}
		case "flow-2.invalid:443":
			if flow.ProfileID != 42 || flow.State != "active" || flow.EndedAt != 0 {
				t.Fatalf("new flow inherited old lifecycle: %+v", flow)
			}
		default:
			t.Fatalf("unrelated flow in isolated history: %+v", flow)
		}
	}
}

func TestConcurrentConnectionCountersAndRepeatedFinishPreserveOneClosedObservation(t *testing.T) {
	ledger := connectionLedgerFixture()
	ledger.started()
	observation := ledger.Open(connectionMetadataFixture(1))
	var workers sync.WaitGroup
	for range 8 {
		workers.Go(func() {
			for range 1000 {
				observation.AddUpload(3)
				observation.AddDownload(7)
			}
		})
	}
	workers.Wait()
	var finishers sync.WaitGroup
	for range 8 {
		finishers.Go(func() { observation.Finish("closed") })
	}
	finishers.Wait()
	snapshot := ledger.snapshot()
	if len(snapshot.Connections) != 1 || snapshot.Connections[0].Upload != 24000 || snapshot.Connections[0].Download != 56000 || ledger.count != 1 {
		t.Fatalf("concurrent accounting or repeated terminal callback corrupted history: %+v", snapshot)
	}
}
