package XrayCore_test

import (
	"bytes"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	xray "XrayCore"

	"github.com/xtls/xray-core/common/geodata"
	"google.golang.org/protobuf/proto"
)

// Xray owns process-global state. These tests intentionally never run in
// parallel, and every Start has cleanup even when a payload assertion fails.
func isolateFacadeCore(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("xray.location.asset", dir)
	t.Setenv("xray.mph.cache", "")
	t.Cleanup(func() {
		if diagnostic := xray.Stop(); diagnostic != "" {
			t.Errorf("Stop cleanup failed: %s", diagnostic)
		}
	})
	return dir
}

func writeFacadeConfig(t *testing.T, config string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "synthetic-config.json")
	if err := os.WriteFile(path, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}

func reserveFacadeAddress(t *testing.T) net.Listener {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	return listener
}

func facadeProxyConfig(protocol string, port int) string {
	// This pinned core has no Freedom private-destination allow policy. A core
	// migration adding one must explicitly allow these synthetic loopback targets.
	return fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"tag":"synthetic-in","listen":"127.0.0.1","port":%d,"protocol":%q,"settings":{"auth":"noauth"}}],"outbounds":[{"tag":"direct","protocol":"freedom","settings":{"domainStrategy":"AsIs"}}]}`, port, protocol)
}

func TestFacadeValidatesWithoutOpeningListeners(t *testing.T) {
	dir := isolateFacadeCore(t)
	// Keep the port occupied: validation must construct without starting it.
	listener := reserveFacadeAddress(t)
	port := listener.Addr().(*net.TCPAddr).Port
	cases := map[string]string{
		"HTTP direct":  facadeProxyConfig("http", port),
		"SOCKS direct": facadeProxyConfig("socks", port),
		"DNS IPv6 and ordered routing": fmt.Sprintf(`{
			"log":{"loglevel":"none"},
			"dns":{"servers":["127.0.0.1"],"queryStrategy":"UseIPv4"},
			"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"noauth","udp":true},"sniffing":{"enabled":true,"destOverride":["http","tls"]}}],
			"outbounds":[{"tag":"direct","protocol":"freedom","settings":{"domainStrategy":"AsIs"}},{"tag":"blocked","protocol":"blackhole"}],
			"routing":{"domainStrategy":"AsIs","rules":[{"type":"field","domain":["full:blocked.invalid"],"outboundTag":"blocked"},{"type":"field","ip":["127.0.0.0/8","::1/128"],"outboundTag":"direct"}]}
		}`, port),
		"VLESS websocket TLS outbound": `{"log":{"loglevel":"none"},"outbounds":[{"protocol":"vless","settings":{"address":"synthetic.invalid","port":443,"id":"e9bff11a-27a7-49c7-8c21-2303c304de78","encryption":"none"},"streamSettings":{"network":"ws","security":"tls","wsSettings":{"host":"cdn.invalid","path":"/synthetic"},"tlsSettings":{"serverName":"synthetic.invalid","fingerprint":"chrome","alpn":["http/1.1"]}}}]}`,
	}
	for name, config := range cases {
		t.Run(name, func(t *testing.T) {
			if diagnostic := xray.ValidateInWorker(dir, writeFacadeConfig(t, config)); diagnostic != "" {
				t.Fatalf("ValidateInWorker rejected valid local configuration: %s", diagnostic)
			}
		})
	}
}

func TestFacadeRejectsInvalidConfiguration(t *testing.T) {
	dir := isolateFacadeCore(t)
	cases := map[string]string{
		"malformed JSON":         `{"outbounds":[`,
		"unknown outbound":       `{"log":{"loglevel":"none"},"outbounds":[{"protocol":"not-a-protocol"}]}`,
		"invalid routing CIDR":   `{"log":{"loglevel":"none"},"outbounds":[{"tag":"direct","protocol":"freedom"}],"routing":{"rules":[{"type":"field","ip":["127.0.0.1/33"],"outboundTag":"direct"}]}}`,
		"invalid VLESS identity": `{"log":{"loglevel":"none"},"outbounds":[{"protocol":"vless","settings":{"address":"synthetic.invalid","port":443,"id":"e9bff11a-27a7-49c7-8c21-2303c304de7k","encryption":"none"}}]}`,
	}
	for name, config := range cases {
		t.Run(name, func(t *testing.T) {
			if diagnostic := xray.ValidateInWorker(dir, writeFacadeConfig(t, config)); diagnostic == "" {
				t.Fatal("ValidateInWorker accepted an invalid configuration without a diagnostic")
			}
		})
	}
	missing := filepath.Join(t.TempDir(), "missing-synthetic-config.json")
	if diagnostic := xray.ValidateInWorker(dir, missing); diagnostic == "" {
		t.Fatal("ValidateInWorker accepted a missing configuration file")
	}
}

func TestFacadeValidationRestoresAssetEnvironment(t *testing.T) {
	for _, state := range []string{"set", "unset"} {
		t.Run(state, func(t *testing.T) {
			isolateFacadeCore(t)
			if state == "unset" {
				if err := os.Unsetenv("xray.location.asset"); err != nil {
					t.Fatal(err)
				}
			}
			previousDir, hadDir := os.LookupEnv("xray.location.asset")
			candidateDir := t.TempDir()
			for _, candidate := range []struct {
				name  string
				valid bool
				json  string
			}{
				{"valid", true, `{"log":{"loglevel":"none"},"outbounds":[{"protocol":"freedom"}]}`},
				{"invalid", false, `{"log":{"loglevel":"none"},"outbounds":[{"protocol":"not-a-protocol"}]}`},
			} {
				t.Run(candidate.name, func(t *testing.T) {
					diagnostic := xray.ValidateInWorker(candidateDir, writeFacadeConfig(t, candidate.json))
					if (diagnostic == "") != candidate.valid {
						t.Fatalf("validation reported the wrong outcome: %q", diagnostic)
					}
					got, present := os.LookupEnv("xray.location.asset")
					if got != previousDir || present != hadDir {
						t.Fatalf("validation changed the asset environment: got %q (present %t), want %q (present %t)", got, present, previousDir, hadDir)
					}
				})
			}
		})
	}
}

func writeSyntheticGeo(t *testing.T, dir, filename string, message proto.Message) {
	t.Helper()
	data, err := proto.Marshal(message)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, filename), data, 0600); err != nil {
		t.Fatal(err)
	}
}

func geoFacadeConfig(field, reference string) string {
	return fmt.Sprintf(`{"log":{"loglevel":"none"},"outbounds":[{"tag":"direct","protocol":"freedom"}],"routing":{"rules":[{"type":"field",%q:[%q],"outboundTag":"direct"}]}}`, field, reference)
}

func TestFacadeValidatesReferencedGeoAssets(t *testing.T) {
	dir := isolateFacadeCore(t)
	sites := &geodata.GeoSiteList{Entry: []*geodata.GeoSite{{
		Code:   "YAXCTEST",
		Domain: []*geodata.Domain{{Type: geodata.Domain_Full, Value: "synthetic.invalid"}},
	}}}
	ips := &geodata.GeoIPList{Entry: []*geodata.GeoIP{{
		Code: "YAXCTEST",
		Cidr: []*geodata.CIDR{{Ip: []byte{127, 0, 0, 0}, Prefix: 8}},
	}}}
	writeSyntheticGeo(t, dir, "geosite.dat", sites)
	writeSyntheticGeo(t, dir, "geoip.dat", ips)
	writeSyntheticGeo(t, dir, "synthetic-sites.dat", sites)
	writeSyntheticGeo(t, dir, "synthetic-ips.dat", ips)
	for _, tc := range []struct {
		field     string
		reference string
	}{
		{"domain", "geosite:yaXcTest"},
		{"ip", "geoip:yaXcTest"},
		{"domain", "ext:synthetic-sites.dat:yaXcTest"},
		{"ip", "ext:synthetic-ips.dat:yaXcTest"},
	} {
		t.Run(tc.reference, func(t *testing.T) {
			if diagnostic := xray.ValidateInWorker(dir, writeFacadeConfig(t, geoFacadeConfig(tc.field, tc.reference))); diagnostic != "" {
				t.Fatalf("ValidateInWorker rejected synthetic referenced geo data: %s", diagnostic)
			}
		})
	}
}

func TestFacadeRejectsMissingOrInvalidReferencedGeo(t *testing.T) {
	isolateFacadeCore(t)
	for _, tc := range []struct {
		name      string
		field     string
		filename  string
		reference string
		data      []byte
	}{
		{"missing geosite", "domain", "geosite.dat", "geosite:YAXCMISSING", nil},
		{"missing geoip", "ip", "geoip.dat", "geoip:YAXCMISSING", nil},
		{"missing external sites", "domain", "missing-sites.dat", "ext:missing-sites.dat:YAXCMISSING", nil},
		{"missing external IPs", "ip", "missing-ips.dat", "ext:missing-ips.dat:YAXCMISSING", nil},
		{"invalid geosite", "domain", "geosite.dat", "geosite:YAXCINVALID", []byte("not a protobuf geo database")},
		{"invalid geoip", "ip", "geoip.dat", "geoip:YAXCINVALID", []byte("not a protobuf geo database")},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			if tc.data != nil {
				if err := os.WriteFile(filepath.Join(dir, tc.filename), tc.data, 0600); err != nil {
					t.Fatal(err)
				}
			}
			diagnostic := xray.ValidateInWorker(dir, writeFacadeConfig(t, geoFacadeConfig(tc.field, tc.reference)))
			if diagnostic == "" || !strings.Contains(diagnostic, tc.filename) {
				t.Fatalf("ValidateInWorker must identify the unusable referenced asset %q, got %q", tc.filename, diagnostic)
			}
		})
	}
}

type facadeRequest struct {
	method string
	uri    string
	header string
	body   []byte
	err    error
}

func newFacadeTarget(t *testing.T) (*httptest.Server, <-chan facadeRequest) {
	t.Helper()
	requests := make(chan facadeRequest, 8)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		requests <- facadeRequest{r.Method, r.URL.RequestURI(), r.Header.Get("X-Synthetic"), body, err}
		w.Header().Set("X-Synthetic-Response", "native-facade")
		w.WriteHeader(http.StatusCreated)
		w.Write([]byte("native response\x00\xff\n東京"))
	}))
	t.Cleanup(server.Close)
	return server, requests
}

func newFacadeProxyClient(t *testing.T, scheme, address string) *http.Client {
	t.Helper()
	transport := &http.Transport{
		Proxy:             http.ProxyURL(&url.URL{Scheme: scheme, Host: address}),
		DialContext:       (&net.Dialer{Timeout: 2 * time.Second}).DialContext,
		DisableKeepAlives: true,
	}
	t.Cleanup(transport.CloseIdleConnections)
	return &http.Client{Transport: transport, Timeout: 3 * time.Second}
}

func requireFacadePayload(t *testing.T, client *http.Client, target string, requests <-chan facadeRequest, stage string) {
	t.Helper()
	const requestBody = "synthetic request\x00\xfe\n東京"
	uri := "/native-contract?stage=" + url.QueryEscape(stage) + "&literal=a%2Bb"
	request, err := http.NewRequest(http.MethodPost, target+uri, strings.NewReader(requestBody))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-Synthetic", stage)
	request.Header.Set("Content-Type", "application/octet-stream")
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("%s: loopback proxy request failed: %v", stage, err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusCreated || response.Header.Get("X-Synthetic-Response") != "native-facade" || !bytes.Equal(body, []byte("native response\x00\xff\n東京")) {
		t.Fatalf("%s: proxy changed response: status=%d header=%q body=%q", stage, response.StatusCode, response.Header.Get("X-Synthetic-Response"), body)
	}
	select {
	case got := <-requests:
		if got.err != nil || got.method != http.MethodPost || got.uri != uri || got.header != stage || !bytes.Equal(got.body, []byte(requestBody)) {
			t.Fatalf("%s: proxy changed request: %+v", stage, got)
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("%s: proxy returned without delivering the expected target request", stage)
	}
}

func requireFacadeListenerReleased(t *testing.T, address string) {
	t.Helper()
	connection, err := net.DialTimeout("tcp", address, time.Second)
	if err == nil {
		connection.Close()
		t.Fatalf("stopped or rejected core still accepts connections on %s", address)
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		t.Fatalf("core did not release listener %s: %v", address, err)
	}
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestFacadeCoreTrafficValidationStopAndRestart(t *testing.T) {
	for _, tc := range []struct {
		protocol string
		scheme   string
	}{{"http", "http"}, {"socks", "socks5"}} {
		t.Run(tc.protocol, func(t *testing.T) {
			dir := isolateFacadeCore(t)
			reservation := reserveFacadeAddress(t)
			address := reservation.Addr().String()
			port := reservation.Addr().(*net.TCPAddr).Port
			if err := reservation.Close(); err != nil {
				t.Fatal(err)
			}
			config := writeFacadeConfig(t, facadeProxyConfig(tc.protocol, port))
			server, requests := newFacadeTarget(t)
			client := newFacadeProxyClient(t, tc.scheme, address)
			if diagnostic := xray.Start(dir, config); diagnostic != "" {
				t.Fatalf("Start failed: %s", diagnostic)
			}
			requireFacadePayload(t, client, server.URL, requests, "before validation")

			// Validate the exact same occupied inbound port in a real worker.
			// It must neither bind nor stop the running core's listener.
			if diagnostic := validateFacadeInWorker(t, dir, config); diagnostic != "" {
				t.Fatalf("validation of active configuration failed: %s", diagnostic)
			}
			requireFacadePayload(t, client, server.URL, requests, "after valid validation")
			invalid := writeFacadeConfig(t, `{"outbounds":[{"protocol":"not-a-protocol"}]}`)
			if diagnostic := validateFacadeInWorker(t, dir, invalid); diagnostic == "" {
				t.Fatal("validation accepted an invalid profile while the core was active")
			}
			requireFacadePayload(t, client, server.URL, requests, "after rejected validation")

			if diagnostic := xray.Stop(); diagnostic != "" {
				t.Fatalf("Stop failed: %s", diagnostic)
			}
			requireFacadeListenerReleased(t, address)
			if diagnostic := xray.Stop(); diagnostic != "" {
				t.Fatalf("repeated Stop failed: %s", diagnostic)
			}
			if diagnostic := xray.Start(dir, config); diagnostic != "" {
				t.Fatalf("Start could not reuse the stopped listener: %s", diagnostic)
			}
			requireFacadePayload(t, client, server.URL, requests, "after restart")
			if diagnostic := xray.Stop(); diagnostic != "" {
				t.Fatalf("Stop after restart failed: %s", diagnostic)
			}
			requireFacadeListenerReleased(t, address)
		})
	}
}

func TestFacadeFailedConfigurationDoesNotOpenListener(t *testing.T) {
	dir := isolateFacadeCore(t)
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	if err := reservation.Close(); err != nil {
		t.Fatal(err)
	}
	invalid := writeFacadeConfig(t, fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"http"}],"outbounds":[{"protocol":"not-a-protocol"}]}`, port))
	if diagnostic := xray.Start(dir, invalid); diagnostic == "" {
		t.Fatal("Start accepted an invalid outbound configuration")
	}
	// Check before Stop: rejection itself must not have opened this listener.
	requireFacadeListenerReleased(t, address)
	server, requests := newFacadeTarget(t)
	client := newFacadeProxyClient(t, "http", address)
	valid := writeFacadeConfig(t, facadeProxyConfig("http", port))
	if diagnostic := xray.Start(dir, valid); diagnostic != "" {
		t.Fatalf("valid Start after rejected configuration failed: %s", diagnostic)
	}
	requireFacadePayload(t, client, server.URL, requests, "after failed configuration")
	if diagnostic := xray.Stop(); diagnostic != "" {
		t.Fatalf("Stop failed: %s", diagnostic)
	}
	requireFacadeListenerReleased(t, address)
}
