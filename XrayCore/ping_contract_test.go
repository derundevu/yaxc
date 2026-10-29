package XrayCore

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"
)

func pingProxy(t *testing.T, config string) string {
	t.Helper()
	data, err := os.ReadFile(config)
	if err != nil {
		t.Fatal(err)
	}
	var parsed struct {
		Inbounds []struct {
			Port int `json:"port"`
		} `json:"inbounds"`
	}
	if err := json.Unmarshal(data, &parsed); err != nil {
		t.Fatal(err)
	}
	return fmt.Sprintf("socks5://127.0.0.1:%d", parsed.Inbounds[0].Port)
}

func awaitPing(t *testing.T, done <-chan string) string {
	t.Helper()
	select {
	case result := <-done:
		return result
	case <-time.After(5 * time.Second):
		t.Fatal("native ping did not finish within its bounded deadline")
		return ""
	}
}

func TestPreparedPingTraversesSOCKSWithSelectedHTTPMethod(t *testing.T) {
	methods := make(chan string, 2)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		methods <- r.Method
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	config := pingConfig(t)
	for index, method := range []string{"get", "head"} {
		t.Run(method, func(t *testing.T) {
			id := int64(100 + index)
			PreparePing(id)
			t.Cleanup(func() { CancelPing(id) })
			if !pingSucceeded(t, PingPrepared(id, t.TempDir(), config, 2, server.URL, pingProxy(t, config), method)) {
				t.Fatal("ping through the real Xray SOCKS inbound failed")
			}
			select {
			case got := <-methods:
				want := map[string]string{"get": http.MethodGet, "head": http.MethodHead}[method]
				if got != want {
					t.Fatalf("target received %s, want %s", got, want)
				}
			default:
				t.Fatal("successful ping never reached the target")
			}
		})
	}
}

func TestPreparedPingSOCKSHandshakeDeadlineClosesConnection(t *testing.T) {
	for index, method := range []string{"get", "tcp"} {
		t.Run(method, func(t *testing.T) {
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			accepted, closed := make(chan struct{}), make(chan struct{})
			go func() {
				conn, err := listener.Accept()
				if err != nil {
					return
				}
				defer conn.Close()
				// A broken client must not leave the fixture blocked forever.
				_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
				close(accepted)
				_, _ = io.Copy(io.Discard, conn)
				close(closed)
			}()
			id := int64(110 + index)
			PreparePing(id)
			t.Cleanup(func() { CancelPing(id) })
			config, dir := pingConfig(t), t.TempDir()
			done := make(chan string, 1)
			start := time.Now()
			go func() {
				done <- PingPrepared(id, dir, config, 1, "http://127.0.0.1:9", "socks5://"+listener.Addr().String(), method)
			}()
			select {
			case <-accepted:
			case <-time.After(3 * time.Second):
				t.Fatal("ping never attempted SOCKS negotiation")
			}
			if pingSucceeded(t, awaitPing(t, done)) {
				t.Fatal("unresponsive SOCKS proxy was reported as reachable")
			}
			if elapsed := time.Since(start); elapsed < 800*time.Millisecond || elapsed > 4*time.Second {
				t.Fatalf("one-second SOCKS deadline took %v", elapsed)
			}
			select {
			case <-closed:
			case <-time.After(2 * time.Second):
				t.Fatal("timed-out SOCKS connection was not closed")
			}
		})
	}
}

func TestPreparedPingCancelledWhileWaitingForCoreNeverReachesTarget(t *testing.T) {
	entered, disconnected := make(chan struct{}), make(chan struct{})
	unexpected := make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/hold":
			close(entered)
			<-r.Context().Done()
			close(disconnected)
		case "/cancelled":
			unexpected <- struct{}{}
		default:
			w.WriteHeader(http.StatusNoContent)
		}
	}))
	t.Cleanup(server.Close)
	config, dir := pingConfig(t), t.TempDir()
	proxy := pingProxy(t, config)
	for _, id := range []int64{120, 121, 122} {
		PreparePing(id)
		t.Cleanup(func() { CancelPing(id) })
	}
	active := make(chan string, 1)
	go func() { active <- PingPrepared(120, dir, config, 7, server.URL+"/hold", proxy, "get") }()
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		CancelPing(120)
		t.Fatal("first ping never occupied the core")
	}
	queued := make(chan string, 1)
	go func() { queued <- PingPrepared(121, dir, config, 2, server.URL+"/cancelled", proxy, "get") }()
	// The first request holds the core until cancelled. Whether the second goroutine
	// has reached the mutex yet cannot change its already-registered cancellation.
	CancelPing(121)
	CancelPing(120)
	activeSucceeded := pingSucceeded(t, awaitPing(t, active))
	queuedSucceeded := pingSucceeded(t, awaitPing(t, queued))
	if activeSucceeded || queuedSucceeded {
		t.Fatal("an active or queued cancelled ping succeeded")
	}
	select {
	case <-disconnected:
	case <-time.After(2 * time.Second):
		t.Fatal("cancelled target connection remained open")
	}
	select {
	case <-unexpected:
		t.Fatal("cancelled queued ping reached the target")
	default:
	}
	if !pingSucceeded(t, PingPrepared(122, dir, config, 2, server.URL+"/next", proxy, "get")) {
		t.Fatal("a cancelled queue prevented the next real SOCKS ping from reusing the core listener")
	}
}
