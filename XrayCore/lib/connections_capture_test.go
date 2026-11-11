package lib

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/xtls/xray-core/app/dispatcher"
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

func TestRetiredConnectionLedgerStaysReachableOnlyUntilLastLateFinish(t *testing.T) {
	ledger := connectionLedgerFixture()
	ledger.observer = &dispatcher.DefaultDispatcher{}
	ledger.ledgers = map[*connectionLedger]struct{}{ledger: {}}
	ledger.started()
	first := ledger.Open(connectionMetadataFixture(1))
	second := ledger.Open(connectionMetadataFixture(2))
	ledger.stop()
	if _, retained := ledger.ledgers[ledger]; !retained || ledger.pending != 2 || ledger.observer == nil {
		t.Fatal("stop lost dispatcher needed for a later explicit analytics cutoff")
	}
	first.AddDownload(7)
	first.Finish("closed")
	if _, retained := ledger.ledgers[ledger]; !retained || ledger.pending != 1 || ledger.observer == nil {
		t.Fatal("one late finish discarded another pending observation")
	}
	second.AddUpload(9)
	second.Finish("closed")
	second.Finish("closed")
	if len(ledger.ledgers) != 0 || ledger.pending != 0 || ledger.observer != nil {
		t.Fatal("completed stopped ledger retained dispatcher/route metadata")
	}
	snapshot := ledger.snapshot()
	if len(snapshot.Connections) != 2 || snapshot.Connections[0].Download != 7 || snapshot.Connections[1].Upload != 9 {
		t.Fatalf("releasing dispatcher deleted retained history or late bytes: %+v", snapshot)
	}
}

func TestStoppedConnectionLedgerWithoutPendingFlowsReleasesDispatcher(t *testing.T) {
	ledger := connectionLedgerFixture()
	ledger.observer = &dispatcher.DefaultDispatcher{}
	ledger.ledgers = map[*connectionLedger]struct{}{ledger: {}}
	flow := ledger.Open(connectionMetadataFixture(1))
	flow.AddUpload(23)
	flow.Finish("closed")
	if ledger.observer == nil {
		t.Fatal("finishing a running core observation discarded its dispatcher")
	}
	ledger.stop()
	if ledger.observer != nil || len(ledger.ledgers) != 0 {
		t.Fatal("stopped drained ledger retained its dispatcher")
	}
	snapshot := ledger.snapshot()
	if len(snapshot.Connections) != 1 || snapshot.Connections[0].Upload != 23 {
		t.Fatalf("releasing stopped dispatcher deleted retained history: %+v", snapshot)
	}
}
