package lib

import (
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
)

func connectionLedgerFixture() *connectionLedger {
	return &connectionLedger{connectionHistory: &connectionHistory{epoch: "isolated-history", enabled: true, records: make(map[uint64]*observedConnection)},
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
	if len(snapshot.Connections) != maxConnectionRecords || snapshot.NativeEvicted != 1 || snapshot.NativeRejected != 1 {
		t.Fatalf("wrong bounded history or event classification: %+v", snapshot)
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

func TestFinishedRetentionUsesCompletionFIFOInsteadOfAdmissionOrder(t *testing.T) {
	ledger := connectionLedgerFixture()
	active := make([]session.ConnectionObservation, maxConnectionRecords)
	for index := range active {
		active[index] = ledger.Open(connectionMetadataFixture(index))
	}
	active[1].Finish("closed")
	active[0].Finish("closed")
	ledger.Open(connectionMetadataFixture(maxConnectionRecords))
	ledger.mu.Lock()
	_, retainedFirst := ledger.records[1]
	_, retainedSecond := ledger.records[2]
	ledger.mu.Unlock()
	if !retainedFirst || retainedSecond || ledger.snapshot().NativeEvicted != 1 {
		t.Fatal("retention used start order instead of completion FIFO")
	}
	ledger.Open(connectionMetadataFixture(maxConnectionRecords + 1))
	ledger.mu.Lock()
	_, retainedFirst = ledger.records[1]
	ledger.mu.Unlock()
	if retainedFirst || ledger.snapshot().NativeEvicted != 2 {
		t.Fatal("the next completed row was not the next FIFO victim")
	}
}

func TestClearResetsMetricsAndFencesSnapshotsWithoutTouchingActiveAccounting(t *testing.T) {
	ledger := connectionLedgerFixture()
	previous := currentConnections.Swap(ledger)
	t.Cleanup(func() { currentConnections.Store(previous) })
	active := ledger.Open(connectionMetadataFixture(1))
	closed := ledger.Open(connectionMetadataFixture(2))
	closed.Finish("closed")
	ledger.mu.Lock()
	ledger.evicted, ledger.rejected = 7, 3
	ledger.mu.Unlock()
	before := ledger.snapshot()
	ClearConnectionHistory()
	active.AddUpload(31)
	active.AddDownload(73)
	closed.AddDownload(999)
	closed.Finish("closed")
	after := ledger.snapshot()
	if after.Generation != before.Generation+1 || after.NativeEvicted != 0 || after.NativeRejected != 0 || len(after.Connections) != 1 {
		t.Fatalf("clear did not reset history and advance its fence: %+v", after)
	}
	if flow := after.Connections[0]; flow.State != "active" || flow.Upload != 31 || flow.Download != 73 {
		t.Fatalf("clear changed a forwarding observation: %+v", flow)
	}
	active.Finish("closed")
	if snapshot := ledger.snapshot(); len(snapshot.Connections) != 1 || snapshot.Connections[0].Download != 73 {
		t.Fatalf("preserved active observation lost its terminal accounting: %+v", snapshot)
	}
}

func TestNativeTTLExpiryIsEvictionNotCaptureRejection(t *testing.T) {
	ledger := connectionLedgerFixture()
	closed := ledger.Open(connectionMetadataFixture(1)).(*observedConnection)
	active := ledger.Open(connectionMetadataFixture(2))
	closed.Finish("closed")
	ledger.mu.Lock()
	closed.document.EndedAt = time.Now().Add(-connectionRetention - time.Second).UnixMilli()
	ledger.mu.Unlock()
	active.AddUpload(13)
	snapshot := ledger.snapshot()
	if snapshot.NativeEvicted != 1 || snapshot.NativeRejected != 0 || len(snapshot.Connections) != 1 || snapshot.Connections[0].Upload != 13 {
		t.Fatalf("TTL expiry changed active accounting or misclassified an event: %+v", snapshot)
	}
}

func TestExplicitDisabledSnapshotStillExpiresRetainedHistory(t *testing.T) {
	ledger := connectionLedgerFixture()
	expired := ledger.Open(connectionMetadataFixture(1)).(*observedConnection)
	recent := ledger.Open(connectionMetadataFixture(2)).(*observedConnection)
	expired.AddUpload(11)
	expired.Finish("closed")
	recent.AddDownload(17)
	recent.Finish("closed")
	ledger.mu.Lock()
	expired.document.EndedAt = time.Now().Add(-connectionRetention - time.Second).UnixMilli()
	ledger.enabled = false
	ledger.captureGeneration = 3
	ledger.mu.Unlock()
	snapshot := ledger.snapshot()
	if snapshot.Enabled || snapshot.CaptureGeneration != 3 || snapshot.Generation != 0 || snapshot.NativeEvicted != 1 || snapshot.NativeRejected != 0 || len(snapshot.Connections) != 1 || snapshot.Connections[0].Download != 17 {
		t.Fatalf("disabled read suspended retention or changed capture fences: %+v", snapshot)
	}
}
