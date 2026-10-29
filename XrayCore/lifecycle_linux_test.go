package XrayCore_test

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	xray "XrayCore"

	applog "github.com/xtls/xray-core/app/log"
	"github.com/xtls/xray-core/app/proxyman"
	commonlog "github.com/xtls/xray-core/common/log"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/core"
)

func TestFacadeRepeatedValidationReleasesLogFiles(t *testing.T) {
	dir := isolateFacadeCore(t)
	logPath := filepath.Join(t.TempDir(), "validation.log")
	encodedPath, err := json.Marshal(logPath)
	if err != nil {
		t.Fatal(err)
	}
	config := writeFacadeConfig(t, fmt.Sprintf(`{"log":{"loglevel":"debug","error":%s},"outbounds":[{"protocol":"freedom"}]}`, encodedPath))
	for range 8 {
		if diagnostic := xray.ValidateInWorker(dir, config); diagnostic != "" {
			t.Fatal(diagnostic)
		}
	}
	requireNativeLogClosed(t, logPath)
}

func TestCoreFailedConstructionReleasesLogFiles(t *testing.T) {
	if facadeFailureSubprocess(t) {
		return
	}
	for _, constructor := range []struct {
		name string
		new  func(*core.Config) (*core.Instance, error)
	}{
		{"New", core.New},
		{"NewWithContext", func(config *core.Config) (*core.Instance, error) {
			return core.NewWithContext(context.Background(), config)
		}},
	} {
		t.Run(constructor.name, func(t *testing.T) {
			logPath := filepath.Join(t.TempDir(), "failed-construction.log")
			config := &core.Config{
				App: []*serial.TypedMessage{
					serial.ToTypedMessage(&applog.Config{
						ErrorLogType: applog.LogType_File, ErrorLogLevel: commonlog.Severity_Debug,
						ErrorLogPath: logPath, AccessLogType: applog.LogType_None,
					}),
					serial.ToTypedMessage(&proxyman.OutboundConfig{}),
				},
				Outbound: []*core.OutboundHandlerConfig{{
					ProxySettings: &serial.TypedMessage{Type: "yaxc.test.UnregisteredOutbound"},
				}},
			}
			server, err := constructor.new(config)
			if server != nil {
				server.Close()
				t.Fatal("failed constructor published a partially initialized instance")
			}
			if err == nil {
				t.Fatal("constructor accepted an unregistered outbound")
			}
			requireNativeLogClosed(t, logPath)
		})
	}
}

func requireNativeLogClosed(t *testing.T, logPath string) {
	t.Helper()
	// Native file logging closes asynchronously. Inspect only this fixture's file,
	// not aggregate process FD/goroutine counts; require a quiescent closed window
	// so an initial unscheduled logger cannot make a leak appear to pass.
	deadline := time.NewTimer(2 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(5 * time.Millisecond)
	defer tick.Stop()
	var closedSince time.Time
	for {
		entries, err := os.ReadDir("/proc/self/fd")
		if err != nil {
			t.Fatal(err)
		}
		open := false
		for _, entry := range entries {
			target, err := os.Readlink(filepath.Join("/proc/self/fd", entry.Name()))
			if err == nil && target == logPath {
				open = true
				break
			}
		}
		if open {
			closedSince = time.Time{}
		} else if closedSince.IsZero() {
			closedSince = time.Now()
		} else if time.Since(closedSince) >= 100*time.Millisecond {
			return
		}
		select {
		case <-tick.C:
		case <-deadline.C:
			t.Fatal("completed native operation retained an open log file")
		}
	}
}
