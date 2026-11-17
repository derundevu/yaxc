package lib

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/features/stats"
)

func TestConnectionCapturePreferenceSurvivesClearStopStartWithoutReplacingNativeCore(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "core.json")
	config := `{"log":{"loglevel":"none"},"stats":{},"outbounds":[{"tag":"direct","protocol":"freedom"}]}`
	if err := os.WriteFile(file, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := Stop(); err != nil {
			t.Error(err)
		}
		if err := SetConnectionAnalyticsEnabled(true); err != nil {
			t.Error(err)
		}
	})
	if err := SetConnectionAnalyticsEnabled(false); err != nil {
		t.Fatal(err)
	}
	var before connectionSnapshot
	if err := json.Unmarshal([]byte(ConnectionSnapshot()), &before); err != nil {
		t.Fatal(err)
	}
	if before.Enabled {
		t.Fatal("off-before-start preference was not published")
	}
	if err := Start(dir, file); err != nil {
		t.Fatal(err)
	}
	server := coreServer
	ledger := currentConnections.Load()
	initial := ledger.snapshot()
	if initial.Enabled || !initial.Running || initial.CaptureGeneration != before.CaptureGeneration {
		t.Fatalf("start reset capture preference: %+v", initial)
	}
	if observation := ledger.Open(connectionMetadataFixture(1)); observation != nil {
		t.Fatal("disabled ledger admitted observation")
	}
	counter, err := server.GetFeature(stats.ManagerType()).(stats.Manager).RegisterCounter("capture-lifecycle-sentinel")
	if err != nil {
		t.Fatal(err)
	}
	counter.Add(71)
	for index := range 8 {
		enabled := index%2 == 0
		if err := SetConnectionAnalyticsEnabled(enabled); err != nil {
			t.Fatal(err)
		}
		if err := SetConnectionAnalyticsEnabled(enabled); err != nil {
			t.Fatal(err)
		}
		got := ledger.snapshot()
		if coreServer != server || currentConnections.Load() != ledger || counter.Value() != 71 || !got.Running || got.Enabled != enabled || got.CaptureGeneration != initial.CaptureGeneration+uint64(index)+1 || got.Generation != initial.Generation {
			t.Fatalf("hot toggle replaced native core, reset stats or fences: %+v", got)
		}
	}
	if err := ApplyRouting(config); err != nil {
		t.Fatal(err)
	}
	if coreServer != server || currentConnections.Load() != ledger || counter.Value() != 71 {
		t.Fatal("disabled apply replaced native core or stats")
	}
	beforeClear := ledger.snapshot()
	ClearConnectionHistory()
	cleared := ledger.snapshot()
	if cleared.Enabled || cleared.Generation != beforeClear.Generation+1 || cleared.CaptureGeneration != beforeClear.CaptureGeneration || len(cleared.Connections) != 0 {
		t.Fatalf("clear changed capture preference or fence: %+v", cleared)
	}
	if err := Stop(); err != nil {
		t.Fatal(err)
	}
	stopped := ledger.snapshot()
	if stopped.Enabled || stopped.Running || stopped.CaptureGeneration != cleared.CaptureGeneration {
		t.Fatalf("stop changed capture preference: %+v", stopped)
	}
	if err := Start(dir, file); err != nil {
		t.Fatal(err)
	}
	reloaded := currentConnections.Load().snapshot()
	if reloaded.Enabled || !reloaded.Running || reloaded.CaptureGeneration != cleared.CaptureGeneration || reloaded.Generation != cleared.Generation || reloaded.Epoch != cleared.Epoch {
		t.Fatalf("reload reset persisted process capture state: %+v", reloaded)
	}
	if observation := currentConnections.Load().Open(connectionMetadataFixture(2)); observation != nil {
		t.Fatal("reloaded disabled core admitted observation")
	}
}

func TestConnectionAdmissionDisablePreservesLiveAccountingAndClearFences(t *testing.T) {
	ledger := connectionLedgerFixture()
	previous := currentConnections.Swap(ledger)
	preference := connectionCapturePreference.Swap(nil)
	t.Cleanup(func() {
		currentConnections.Store(previous)
		connectionCapturePreference.Store(preference)
	})
	ledger.started()
	flow := ledger.Open(connectionMetadataFixture(1)).(*observedConnection)
	flow.Routed(session.ConnectionRoute{OutboundTag: "proxy"})
	flow.AddUpload(17)
	flow.AddDownload(29)
	stale := ledger.snapshot()
	if err := SetConnectionAnalyticsEnabled(false); err != nil {
		t.Fatal(err)
	}
	for index := range maxConnectionRecords + 1 {
		if observation := ledger.Open(connectionMetadataFixture(index + 2)); observation != nil {
			t.Fatal("disabled admission accepted a new flow")
		}
	}
	flow.AddUpload(31)
	flow.AddDownload(43)
	ClearConnectionHistory()
	off := ledger.snapshot()
	if off.Enabled || !off.Running || off.CaptureGeneration != stale.CaptureGeneration+1 || off.Generation != stale.Generation+1 ||
		len(off.Connections) != 1 || off.NativeRejected != 0 || off.NativeEvicted != 0 {
		t.Fatalf("disabled admission/clear changed history bounds or fences: %+v", off)
	}
	if got := off.Connections[0]; got.ID != stale.Connections[0].ID || got.ProfileID != 41 || got.State != "active" || got.EndedAt != 0 ||
		got.Upload != 48 || got.Download != 72 || !got.LiveBytesExact {
		t.Fatalf("disabled admission froze or relabeled admitted accounting: %+v", got)
	}
	flow.AddUpload(5)
	flow.AddDownload(7)
	flow.Finish("closed")
	flow.Finish("closed")
	closed := ledger.snapshot()
	if got := closed.Connections[0]; got.State != "closed" || got.EndedAt == 0 || got.Upload != 53 || got.Download != 79 || !got.LiveBytesExact || ledger.count != 1 {
		t.Fatalf("off-period real finish lost exact totals or duplicated retention: %+v", closed)
	}
	if !stale.Enabled || stale.CaptureGeneration != 0 || stale.Generation != 0 || stale.Connections[0].State != "active" ||
		stale.Connections[0].Upload != 17 || stale.Connections[0].Download != 29 {
		t.Fatalf("later callbacks mutated a stale snapshot: %+v", stale)
	}
	ClearConnectionHistory()
	if got := ledger.snapshot(); got.Enabled || len(got.Connections) != 0 || got.Generation != off.Generation+1 || got.CaptureGeneration != off.CaptureGeneration {
		t.Fatalf("clear while disabled retained closed rows or changed admission: %+v", got)
	}
	if err := SetConnectionAnalyticsEnabled(true); err != nil {
		t.Fatal(err)
	}
	fresh := ledger.Open(connectionMetadataFixture(9))
	if fresh == nil {
		t.Fatal("re-enabled admission rejected a genuinely new flow")
	}
	fresh.AddUpload(11)
	fresh.Finish("closed")
	if got := ledger.snapshot(); !got.Enabled || got.CaptureGeneration != off.CaptureGeneration+1 || len(got.Connections) != 1 ||
		got.Connections[0].ID == flow.document.ID || got.Connections[0].Upload != 11 {
		t.Fatalf("re-enable revived cleared history or lost new accounting: %+v", got)
	}
}

func TestConnectionAdmissionCutoffSerializesConcurrentOpensAndKeepsHandlesLive(t *testing.T) {
	ledger := connectionLedgerFixture()
	previous := currentConnections.Swap(ledger)
	preference := connectionCapturePreference.Swap(nil)
	t.Cleanup(func() {
		currentConnections.Store(previous)
		connectionCapturePreference.Store(preference)
	})
	const workers = 32
	start := make(chan struct{})
	disabled := make(chan struct{})
	var ready, finished sync.WaitGroup
	ready.Add(workers)
	finished.Add(workers)
	for index := range workers {
		go func() {
			defer finished.Done()
			ready.Done()
			<-start
			flow := ledger.Open(connectionMetadataFixture(index))
			<-disabled
			if late := ledger.Open(connectionMetadataFixture(index + workers)); late != nil {
				t.Error("Open admitted a flow after synchronous disable returned")
				late.Finish("closed")
			}
			if flow != nil {
				flow.AddUpload(13)
				flow.AddDownload(19)
				flow.Finish("closed")
			}
		}()
	}
	// Guarantee one admitted handle regardless of how the competing opens order.
	admitted := ledger.Open(connectionMetadataFixture(workers * 2))
	ready.Wait()
	close(start)
	if err := SetConnectionAnalyticsEnabled(false); err != nil {
		t.Fatal(err)
	}
	close(disabled)
	finished.Wait()
	admitted.AddUpload(13)
	admitted.AddDownload(19)
	admitted.Finish("closed")
	snapshot := ledger.snapshot()
	if snapshot.Enabled || snapshot.CaptureGeneration != 1 || len(snapshot.Connections) == 0 || len(snapshot.Connections) > workers+1 {
		t.Fatalf("concurrent cutoff lost its admission boundary: %+v", snapshot)
	}
	for _, flow := range snapshot.Connections {
		if flow.State != "closed" || flow.EndedAt == 0 || !flow.LiveBytesExact || flow.Upload != 13 || flow.Download != 19 {
			t.Fatalf("concurrent cutoff froze an admitted handle: %+v", flow)
		}
	}
}
