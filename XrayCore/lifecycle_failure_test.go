package XrayCore_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	xray "XrayCore"

	"github.com/xtls/xray-core/common/log"
)

// Before the fix, losing the global handle leaves a listener that Stop cannot
// reach. Keep failing-before runs isolated without a test-only cleanup API.
func facadeFailureSubprocess(t *testing.T) bool {
	t.Helper()
	if os.Getenv("YAXC_LIFECYCLE_CHILD") == t.Name() {
		return false
	}
	parts := strings.Split(t.Name(), "/")
	for index, part := range parts {
		parts[index] = "^" + regexp.QuoteMeta(part) + "$"
	}
	command := exec.Command(os.Args[0], "-test.run="+strings.Join(parts, "/"), "-test.timeout=20s")
	command.Env = append(os.Environ(), "YAXC_LIFECYCLE_CHILD="+t.Name())
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("isolated lifecycle scenario failed: %v\n%s", err, output)
	}
	return true
}

func TestFacadeRepeatedStartPreservesActiveOwner(t *testing.T) {
	for _, replacement := range []string{"invalid", "valid"} {
		t.Run(replacement, func(t *testing.T) {
			if facadeFailureSubprocess(t) {
				return
			}
			dir := isolateFacadeCore(t)
			reservation := reserveFacadeAddress(t)
			address := reservation.Addr().String()
			port := reservation.Addr().(*net.TCPAddr).Port
			reservation.Close()
			config := writeFacadeConfig(t, facadeProxyConfig("http", port))
			server, requests := newFacadeTarget(t)
			client := newFacadeProxyClient(t, "http", address)
			if diagnostic := xray.Start(dir, config); diagnostic != "" {
				t.Fatal(diagnostic)
			}
			requireFacadePayload(t, client, server.URL, requests, "before repeated start")
			candidate := writeFacadeConfig(t, `{"outbounds":[{"protocol":"not-a-protocol"}]}`)
			var candidateAddress string
			if replacement == "valid" {
				other := reserveFacadeAddress(t)
				candidateAddress = other.Addr().String()
				candidate = writeFacadeConfig(t, facadeProxyConfig("http", other.Addr().(*net.TCPAddr).Port))
				other.Close()
			}
			if diagnostic := xray.Start(t.TempDir(), candidate); diagnostic == "" {
				t.Fatal("a second Start must reject replacing the active core")
			}
			requireFacadePayload(t, client, server.URL, requests, "after rejected start")
			if diagnostic := xray.Stop(); diagnostic != "" {
				t.Fatal(diagnostic)
			}
			requireFacadeListenerReleased(t, address)
			if candidateAddress != "" {
				requireFacadeListenerReleased(t, candidateAddress)
			}
		})
	}
}

func TestFacadeFailedStartReleasesEarlierPortBeforeStop(t *testing.T) {
	dir := isolateFacadeCore(t)
	first := reserveFacadeAddress(t)
	address := first.Addr().String()
	port := first.Addr().(*net.TCPAddr).Port
	blocked := reserveFacadeAddress(t)
	blockedPort := blocked.Addr().(*net.TCPAddr).Port
	first.Close()
	// One inbound's workers follow this explicit port-list order. The first
	// listener starts before the second hits our deliberately occupied port.
	config := writeFacadeConfig(t, fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":"%d,%d","protocol":"http"}],"outbounds":[{"protocol":"freedom"}]}`, port, blockedPort))
	if diagnostic := xray.Start(dir, config); diagnostic == "" {
		t.Fatal("Start succeeded despite a reserved second port")
	}
	requireFacadeListenerReleased(t, address)
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, facadeProxyConfig("http", port))); diagnostic != "" {
		t.Fatalf("failed Start poisoned the next attempt: %s", diagnostic)
	}
	server, requests := newFacadeTarget(t)
	requireFacadePayload(t, newFacadeProxyClient(t, "http", address), server.URL, requests, "after partial start failure")
	if diagnostic := xray.Stop(); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	requireFacadeListenerReleased(t, address)
}

func TestFacadeConcurrentStartsHaveOneOwner(t *testing.T) {
	if facadeFailureSubprocess(t) {
		return
	}
	dir := isolateFacadeCore(t)
	addresses := make([]string, 2)
	configs := make([]string, 2)
	for index := range configs {
		listener := reserveFacadeAddress(t)
		addresses[index] = listener.Addr().String()
		configs[index] = writeFacadeConfig(t, facadeProxyConfig("http", listener.Addr().(*net.TCPAddr).Port))
		listener.Close()
	}
	start := make(chan struct{})
	type result struct {
		index int
		error string
	}
	done := make(chan result, 2)
	for index, config := range configs {
		go func() {
			<-start
			done <- result{index, xray.Start(dir, config)}
		}()
	}
	close(start)
	winner, successes := 0, 0
	for range configs {
		select {
		case result := <-done:
			if result.error == "" {
				winner = result.index
				successes++
			}
		case <-time.After(5 * time.Second):
			t.Fatal("concurrent Start did not finish")
		}
	}
	if successes != 1 {
		t.Fatalf("concurrent Start created %d cores, want exactly one", successes)
	}
	server, requests := newFacadeTarget(t)
	requireFacadePayload(t, newFacadeProxyClient(t, "http", addresses[winner]), server.URL, requests, "concurrent start winner")
	requireFacadeListenerReleased(t, addresses[1-winner])
	if diagnostic := xray.Stop(); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	for _, address := range addresses {
		requireFacadeListenerReleased(t, address)
	}
}

func TestFacadeValidationPreservesActiveDialerAndLogger(t *testing.T) {
	dir := isolateFacadeCore(t)
	clientReservation := reserveFacadeAddress(t)
	hopReservation := reserveFacadeAddress(t)
	address := clientReservation.Addr().String()
	clientPort := clientReservation.Addr().(*net.TCPAddr).Port
	hopPort := hopReservation.Addr().(*net.TCPAddr).Port
	clientReservation.Close()
	hopReservation.Close()
	logPath := filepath.Join(t.TempDir(), "active.log")
	encodedLog, _ := json.Marshal(logPath)
	config := writeFacadeConfig(t, fmt.Sprintf(`{
		"log":{"loglevel":"info","error":%s},
		"inbounds":[
			{"tag":"client","listen":"127.0.0.1","port":%d,"protocol":"http"},
			{"tag":"hop","listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"noauth"}}
		],
		"outbounds":[
			{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":%d}]},"streamSettings":{"sockopt":{"dialerProxy":"bridge"}}},
			{"tag":"bridge","protocol":"freedom"},
			{"tag":"direct","protocol":"freedom"}
		],
		"routing":{"rules":[{"type":"field","inboundTag":["hop"],"outboundTag":"direct"}]}
	}`, encodedLog, clientPort, hopPort, hopPort))
	if diagnostic := xray.Start(dir, config); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	server, requests := newFacadeTarget(t)
	client := newFacadeProxyClient(t, "http", address)
	requireFacadePayload(t, client, server.URL, requests, "before isolated validation")
	for _, candidate := range []string{
		`{"log":{"loglevel":"none"},"outbounds":[{"protocol":"freedom"}]}`,
		`{"log":{"loglevel":"none"},"outbounds":[{"protocol":"not-a-protocol"}]}`,
	} {
		diagnostic := validateFacadeInWorker(t, t.TempDir(), writeFacadeConfig(t, candidate))
		if strings.Contains(candidate, "not-a-protocol") == (diagnostic == "") {
			t.Fatalf("validation reported the wrong outcome: %q", diagnostic)
		}
		if got := os.Getenv("xray.location.asset"); got != dir {
			t.Fatalf("validation changed the active asset directory: got %q, want %q", got, dir)
		}
		requireFacadePayload(t, client, server.URL, requests, "after isolated validation")
	}
	candidatePath := writeFacadeConfig(t, `{"log":{"loglevel":"none"},"outbounds":[{"protocol":"freedom"}]}`)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	finished := make(chan struct{})
	defer func() {
		cancel()
		<-finished
	}()
	validated := make(chan error, 1)
	go func() {
		defer close(finished)
		for range 8 {
			diagnostic, err := runFacadeValidationWorker(ctx, dir, candidatePath)
			if err != nil {
				validated <- err
				return
			}
			if diagnostic != "" {
				validated <- fmt.Errorf("worker rejected a valid configuration: %s", diagnostic)
				return
			}
		}
		validated <- nil
	}()
	validationComplete := false
	var validationErr error
	// Keep exact-payload traffic flowing until the worker batch finishes, not
	// merely while its first child process is still starting.
	for count := 0; count < 8 || !validationComplete; count++ {
		requireFacadePayload(t, client, server.URL, requests, "traffic alongside validation")
		if !validationComplete {
			select {
			case validationErr = <-validated:
				validationComplete = true
			default:
			}
		}
	}
	// Each worker has a bounded process deadline, and the batch shares ctx.
	if validationErr != nil {
		t.Fatalf("concurrent validation failed: %v", validationErr)
	}
	requireFacadeLoggerMarker(t, logPath, "synthetic-active-logger-after-validation")
	if diagnostic := xray.Stop(); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	requireFacadeListenerReleased(t, address)
}

func TestFacadeRejectsValidationAlongsideActiveCore(t *testing.T) {
	dir := isolateFacadeCore(t)
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	logPath := filepath.Join(t.TempDir(), "active.log")
	encodedLog, err := json.Marshal(logPath)
	if err != nil {
		t.Fatal(err)
	}
	config := writeFacadeConfig(t, fmt.Sprintf(`{"log":{"loglevel":"info","error":%s},"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"http"}],"outbounds":[{"protocol":"freedom"}]}`, encodedLog, port))
	if diagnostic := xray.Start(dir, config); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	server, requests := newFacadeTarget(t)
	client := newFacadeProxyClient(t, "http", address)
	requireFacadePayload(t, client, server.URL, requests, "before rejected in-process validation")
	for _, candidate := range []struct {
		name     string
		protocol string
	}{
		{"valid", "freedom"},
		{"invalid", "not-a-protocol"},
		{"missing", ""},
	} {
		t.Run(candidate.name, func(t *testing.T) {
			candidateDir := t.TempDir()
			candidateLog := filepath.Join(candidateDir, "candidate.log")
			encodedCandidateLog, err := json.Marshal(candidateLog)
			if err != nil {
				t.Fatal(err)
			}
			candidatePath := filepath.Join(candidateDir, "missing.json")
			if candidate.protocol != "" {
				candidatePath = writeFacadeConfig(t, fmt.Sprintf(`{"log":{"loglevel":"debug","error":%s},"outbounds":[{"protocol":%q}]}`, encodedCandidateLog, candidate.protocol))
			}
			if diagnostic := xray.ValidateInWorker(candidateDir, candidatePath); diagnostic == "" {
				t.Fatal("validation accepted a candidate alongside the active core")
			}
			if got, present := os.LookupEnv("xray.location.asset"); !present || got != dir {
				t.Fatalf("rejected validation changed the active asset directory: got %q (present %t), want %q", got, present, dir)
			}
			if _, err := os.Stat(candidateLog); !os.IsNotExist(err) {
				t.Fatalf("rejected validation constructed the candidate logger: %v", err)
			}
			requireFacadePayload(t, client, server.URL, requests, "after rejected "+candidate.name+" in-process validation")
			requireFacadeLoggerMarker(t, logPath, "active-logger-after-rejected-"+candidate.name)
		})
	}
	if diagnostic := xray.Stop(); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	requireFacadeListenerReleased(t, address)
}

func requireFacadeLoggerMarker(t *testing.T, logPath, marker string) {
	t.Helper()
	log.Record(&log.GeneralMessage{Severity: log.Severity_Info, Content: marker})
	deadline := time.NewTimer(2 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(5 * time.Millisecond)
	defer tick.Stop()
	for {
		data, err := os.ReadFile(logPath)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(data), marker) {
			return
		}
		select {
		case <-tick.C:
		case <-deadline.C:
			t.Fatalf("active logger did not record %q after validation", marker)
		}
	}
}
