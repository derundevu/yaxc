package XrayCore

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func pingConfig(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	path := filepath.Join(t.TempDir(), "ping.json")
	config := fmt.Sprintf(`{"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"noauth"}}],"outbounds":[{"protocol":"freedom"}]}`, port)
	if err := os.WriteFile(path, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}

func pingSucceeded(t *testing.T, encoded string) bool {
	t.Helper()
	decoded, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		t.Fatal(err)
	}
	var response struct {
		Success bool `json:"success"`
	}
	if err := json.Unmarshal(decoded, &response); err != nil {
		t.Fatal(err)
	}
	return response.Success
}

func TestPreparedPingSerializesCoreLifetimes(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(50 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	// Both requests need the same listener. Overlapping core lifetimes fail to bind it.
	config := pingConfig(t)
	results := make(chan string, 2)
	for _, id := range []int64{1, 2} {
		PreparePing(id)
		go func(id int64) {
			results <- PingPrepared(id, t.TempDir(), config, 2, server.URL, "", "get")
		}(id)
	}
	for range 2 {
		if !pingSucceeded(t, <-results) {
			t.Fatal("serialized native ping failed")
		}
	}
}

func TestPreparedPingCancellationAllowsNextRequest(t *testing.T) {
	entered := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/wait" {
			close(entered)
			<-r.Context().Done()
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	config := pingConfig(t)
	PreparePing(3)
	done := make(chan string, 1)
	go func() { done <- PingPrepared(3, t.TempDir(), config, 7, server.URL+"/wait", "", "get") }()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("native ping did not reach target")
	}
	CancelPing(3)
	select {
	case result := <-done:
		if pingSucceeded(t, result) {
			t.Fatal("cancelled request succeeded")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("native ping ignored cancellation")
	}
	PreparePing(4)
	if !pingSucceeded(t, PingPrepared(4, t.TempDir(), config, 2, server.URL, "", "get")) {
		t.Fatal("next request could not reuse listener after cancellation")
	}
}

func TestInvalidNativeConfigReturnsFailure(t *testing.T) {
	PreparePing(5)
	missing := filepath.Join(t.TempDir(), "deleted-profile.json")
	if pingSucceeded(t, PingPrepared(5, t.TempDir(), missing, 1, "http://127.0.0.1", "", "get")) {
		t.Fatal("missing profile succeeded")
	}
}

func TestPreparedPingCancelledBeforeExecution(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	config := pingConfig(t)
	PreparePing(6)
	CancelPing(6)
	if pingSucceeded(t, PingPrepared(6, t.TempDir(), config, 1, server.URL, "", "get")) {
		t.Fatal("request cancelled before execution succeeded")
	}
}
