package XrayCore_test

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
	"strings"
	"sync/atomic"
	"testing"
	"time"

	xray "XrayCore"

	"golang.org/x/net/proxy"
)

type facadeHealthStatus struct {
	Running bool `json:"running"`
	Groups  []struct {
		ID              string  `json:"id"`
		State           string  `json:"state"`
		ActiveProfileID *int64  `json:"activeProfileId"`
		EligibleIDs     []int64 `json:"eligibleProfileIds"`
	} `json:"groups"`
}

func facadeReadHealthStatus(t *testing.T) facadeHealthStatus {
	t.Helper()
	encoded := xray.LiveRoutingStatus()
	var status facadeHealthStatus
	if err := json.Unmarshal([]byte(encoded), &status); err != nil {
		t.Fatalf("facade health status is not a JSON object: %q: %v", encoded, err)
	}
	if status.Groups == nil {
		t.Fatalf("facade health groups must be [], not null: %s", encoded)
	}
	return status
}

// This deliberately uses the production 10-second cadence and 5-second timeout,
// not the package-lib accelerated controller options. Both nodes are real local
// HTTP recipients reached only through their configured Xray outbound graphs.
func TestFacadeLiveHealthProductionTimingAndRealRecipient(t *testing.T) {
	dir := isolateFacadeCore(t)
	if status := facadeReadHealthStatus(t); status.Running || len(status.Groups) != 0 {
		t.Fatalf("stopped facade health status: %+v", status)
	}
	requests := make(chan time.Time, 8)
	canceled := make(chan time.Time, 8)
	var primaryProbes atomic.Int64
	var backupProbes atomic.Int64
	primary := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/probe" {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		ordinal := primaryProbes.Add(1)
		requests <- time.Now()
		if ordinal == 1 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		<-r.Context().Done()
		canceled <- time.Now()
	}))
	t.Cleanup(func() {
		primary.CloseClientConnections()
		primary.Close()
	})
	backup := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/probe" {
			backupProbes.Add(1)
			w.WriteHeader(http.StatusNoContent)
			return
		}
		payload, err := io.ReadAll(r.Body)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.Write(append([]byte("actual-backup\x00"), payload...))
	}))
	t.Cleanup(backup.Close)
	defaultTarget := newLiveRoutingEcho(t, 'D')
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	var document map[string]any
	initial := liveRoutingConfig(port, defaultTarget, defaultTarget, strings.TrimPrefix(primary.URL, "http://"), strings.TrimPrefix(backup.URL, "http://"), "random")
	if err := json.Unmarshal([]byte(initial), &document); err != nil {
		t.Fatal(err)
	}
	const blockTag = "_yaxc.health.block.facade"
	document["outbounds"] = append(document["outbounds"].([]any), map[string]any{"tag": blockTag, "protocol": "blackhole"})
	balancer := document["routing"].(map[string]any)["balancers"].([]any)[0].(map[string]any)
	balancer["selector"] = []string{blockTag}
	document["_yaxcLiveHealth"] = map[string]any{
		"url": "http://probe.invalid/probe",
		"groups": []any{map[string]any{
			"id": "facade-health", "strategy": "Select", "selectedProfileId": 1,
			"balancerTag": "group", "blockTag": blockTag,
			"members": []any{
				map[string]any{"profileId": 1, "tag": "member.a", "fingerprint": strings.Repeat("a", 64)},
				map[string]any{"profileId": 2, "tag": "member.b", "fingerprint": strings.Repeat("b", 64)},
			},
		}},
	}
	encoded, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, string(encoded))); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	// Registered last so the core cancels probes before either HTTP server closes.
	t.Cleanup(func() {
		if diagnostic := xray.Stop(); diagnostic != "" {
			t.Error(diagnostic)
		}
	})
	var first time.Time
	select {
	case first = <-requests:
	case <-time.After(3 * time.Second):
		t.Fatal("production startup did not immediately probe the primary outbound")
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		status := facadeReadHealthStatus(t)
		if status.Running && len(status.Groups) == 1 && status.Groups[0].ActiveProfileID != nil && *status.Groups[0].ActiveProfileID == 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("startup primary failure did not select its healthy backup: %+v", status)
		}
		time.Sleep(5 * time.Millisecond)
	}
	requireFacadeHealthHTTPPayload(t, address)
	var second time.Time
	select {
	case second = <-requests:
	case <-time.After(14 * time.Second):
		t.Fatal("production primary was not probed again at its 10-second cadence")
	}
	if interval := second.Sub(first); interval < 9*time.Second || interval > 14*time.Second {
		t.Fatalf("production member probe cadence is %s, want 10s", interval)
	}
	select {
	case ended := <-canceled:
		if elapsed := ended.Sub(second); elapsed < 4500*time.Millisecond || elapsed > 7500*time.Millisecond {
			t.Fatalf("production probe timeout is %s, want 5s", elapsed)
		}
	case <-time.After(8 * time.Second):
		t.Fatal("production probe exceeded its 5-second timeout or failed to close the request")
	}
	primaryBefore, backupBefore := primaryProbes.Load(), backupProbes.Load()
	requireFacadeHealthHTTPPayload(t, address)
	if primaryProbes.Load() != primaryBefore || backupProbes.Load() != backupBefore {
		t.Fatal("a user flow triggered an extra availability probe")
	}
	encodedStatus := xray.LiveRoutingStatus()
	if strings.Contains(encodedStatus, "probe.invalid") || strings.Contains(encodedStatus, "127.0.0.1") || strings.Contains(encodedStatus, "synthetic-password") {
		t.Fatalf("health status leaked configuration details: %s", encodedStatus)
	}
	if diagnostic := xray.Stop(); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	if status := facadeReadHealthStatus(t); status.Running || len(status.Groups) != 0 {
		t.Fatalf("stopped facade retained health observations: %+v", status)
	}
}

func requireFacadeHealthHTTPPayload(t *testing.T, address string) {
	t.Helper()
	dialer, err := proxy.SOCKS5("tcp", address, &proxy.Auth{User: "synthetic-default", Password: "synthetic-password"}, &net.Dialer{Timeout: 2 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	conn, err := dialer.(proxy.ContextDialer).DialContext(ctx, "tcp", "group.invalid:80")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	payload := append([]byte("facade health payload\x00\xff"), bytes.Repeat([]byte("exact 東京 response"), 256)...)
	if _, err := fmt.Fprintf(conn, "POST /payload HTTP/1.1\r\nHost: group.invalid\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", len(payload)); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Write(payload); err != nil {
		t.Fatal(err)
	}
	response, err := http.ReadResponse(bufio.NewReader(conn), &http.Request{Method: "POST"})
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	actual, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	want := append([]byte("actual-backup\x00"), payload...)
	if response.StatusCode != http.StatusOK || !bytes.Equal(actual, want) {
		t.Fatalf("health route did not reach the backup with an exact response: status=%d bytes=%d want=%d", response.StatusCode, len(actual), len(want))
	}
}
