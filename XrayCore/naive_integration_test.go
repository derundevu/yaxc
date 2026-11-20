//go:build with_naive_outbound && with_purego

package XrayCore_test

import (
	"bufio"
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	xray "XrayCore"

	"github.com/apernet/quic-go/http3"
	cronet "github.com/sagernet/cronet-go"
)

func naivePeerCertificate(t *testing.T) (tls.Certificate, string) {
	t.Helper()
	rootKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	root := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "yaxc synthetic Naive root"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign,
	}
	rootDER, err := x509.CreateCertificate(rand.Reader, root, root, &rootKey.PublicKey, rootKey)
	if err != nil {
		t.Fatal(err)
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	leaf := &x509.Certificate{
		SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "proxy.example.org"},
		DNSNames: []string{"proxy.example.org"}, NotBefore: root.NotBefore, NotAfter: root.NotAfter,
		BasicConstraintsValid: true, KeyUsage: x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	leafDER, err := x509.CreateCertificate(rand.Reader, leaf, root, &key.PublicKey, rootKey)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{leafDER, rootDER}, PrivateKey: key}, string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: rootDER}))
}

type naivePeerState struct {
	accepted    atomic.Int64
	rejected    atomic.Int64
	readFrames  atomic.Int64
	writeFrames atomic.Int64
	mu          sync.Mutex
	failure     error
}

func (s *naivePeerState) fail(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failure == nil {
		s.failure = err
	}
}

func (s *naivePeerState) requireHealthy(t *testing.T) {
	t.Helper()
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failure != nil {
		t.Fatalf("real Naive peer rejected transport behavior: %v", s.failure)
	}
}

// Naive frames the first eight writes in each direction as a two-byte payload
// length, one-byte padding length, payload, padding. The peer implements that
// wire protocol independently rather than wrapping Cronet's own connection.
type naivePeerReader struct {
	reader    io.Reader
	state     *naivePeerState
	frames    int
	remaining int
	padding   int
}

func (r *naivePeerReader) Read(p []byte) (int, error) {
	for r.remaining == 0 {
		if r.padding != 0 {
			if _, err := io.CopyN(io.Discard, r.reader, int64(r.padding)); err != nil {
				return 0, err
			}
			r.padding = 0
		}
		if r.frames == 8 {
			return r.reader.Read(p)
		}
		var header [3]byte
		if _, err := io.ReadFull(r.reader, header[:]); err != nil {
			return 0, err
		}
		r.remaining = int(binary.BigEndian.Uint16(header[:2]))
		r.padding = int(header[2])
		r.frames++
		r.state.readFrames.Add(1)
	}
	if len(p) > r.remaining {
		p = p[:r.remaining]
	}
	n, err := r.reader.Read(p)
	r.remaining -= n
	return n, err
}

type naivePeerWriter struct {
	writer http.ResponseWriter
	state  *naivePeerState
	frames int
}

func (w *naivePeerWriter) Write(p []byte) (int, error) {
	written := 0
	for len(p) != 0 {
		size := min(len(p), 65535)
		if w.frames < 8 {
			var header [3]byte
			binary.BigEndian.PutUint16(header[:2], uint16(size))
			header[2] = 7
			if _, err := w.writer.Write(header[:]); err != nil {
				return written, err
			}
		}
		n, err := w.writer.Write(p[:size])
		written += n
		if err != nil {
			return written, err
		}
		if n != size {
			return written, io.ErrShortWrite
		}
		if w.frames < 8 {
			var padding [7]byte
			if _, err := w.writer.Write(padding[:]); err != nil {
				return written, err
			}
			w.frames++
			w.state.writeFrames.Add(1)
		}
		w.writer.(http.Flusher).Flush()
		p = p[size:]
	}
	return written, nil
}

func newNaivePeer(t *testing.T, quic bool, certificate tls.Certificate, destinations map[string]bool) (string, *naivePeerState) {
	t.Helper()
	state := &naivePeerState{}
	protocol := 2
	if quic {
		protocol = 3
	}
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect || r.ProtoMajor != protocol || !destinations[r.Host] || r.Header.Get("Padding") == "" {
			state.fail(fmt.Errorf("unexpected CONNECT method, HTTP version, authority or padding header"))
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if r.Header.Get("Proxy-Authorization") != "Basic "+base64.StdEncoding.EncodeToString([]byte("test-user:test-pass")) {
			state.rejected.Add(1)
			w.Header().Set("Proxy-Authenticate", `Basic realm="synthetic"`)
			w.WriteHeader(http.StatusProxyAuthRequired)
			return
		}
		connection, err := net.DialTimeout("tcp", r.Host, time.Second)
		if err != nil {
			state.fail(err)
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		defer connection.Close()
		connection.SetDeadline(time.Now().Add(10 * time.Second))
		state.accepted.Add(1)
		w.Header().Set("Padding", "synthetic-padding")
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		uplink := make(chan struct{})
		go func() {
			defer close(uplink)
			io.Copy(connection, &naivePeerReader{reader: r.Body, state: state})
			connection.(*net.TCPConn).CloseWrite()
		}()
		io.Copy(&naivePeerWriter{writer: w, state: state}, connection)
		connection.Close()
		r.Body.Close()
		<-uplink
	})
	if !quic {
		server := httptest.NewUnstartedServer(handler)
		server.EnableHTTP2 = true
		server.TLS = &tls.Config{Certificates: []tls.Certificate{certificate}}
		server.StartTLS()
		t.Cleanup(server.Close)
		return server.Listener.Addr().String(), state
	}
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &http3.Server{TLSConfig: &tls.Config{Certificates: []tls.Certificate{certificate}}, Handler: handler}
	done := make(chan error, 1)
	go func() { done <- server.Serve(packet) }()
	t.Cleanup(func() {
		server.Close()
		packet.Close()
		if err := <-done; err != nil && !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			t.Errorf("HTTP/3 peer failed: %v", err)
		}
	})
	return packet.LocalAddr().String(), state
}

func newNaiveEchoTarget(t *testing.T) string {
	t.Helper()
	listener := reserveFacadeAddress(t)
	var connections sync.Map
	var workers sync.WaitGroup
	workers.Add(1)
	go func() {
		defer workers.Done()
		for {
			connection, err := listener.Accept()
			if err != nil {
				return
			}
			connections.Store(connection, true)
			workers.Add(1)
			go func() {
				defer workers.Done()
				defer connection.Close()
				defer connections.Delete(connection)
				connection.SetDeadline(time.Now().Add(10 * time.Second))
				io.Copy(connection, connection)
			}()
		}
	}()
	t.Cleanup(func() {
		listener.Close()
		connections.Range(func(key, _ any) bool { key.(net.Conn).Close(); return true })
		workers.Wait()
	})
	return listener.Addr().String()
}

func requireNaivePaddingBoundary(t *testing.T, proxy, target string) {
	t.Helper()
	connection, err := net.DialTimeout("tcp", proxy, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	connection.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := fmt.Fprintf(connection, "CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n", target, target); err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(connection)
	response, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("facade CONNECT rejected echo tunnel: %d", response.StatusCode)
	}
	for i := range 10 {
		payload := bytes.Repeat([]byte{byte(i), 0, 0xff, '\n'}, 64)
		if _, err := connection.Write(payload); err != nil {
			t.Fatal(err)
		}
		actual := make([]byte, len(payload))
		if _, err := io.ReadFull(reader, actual); err != nil {
			t.Fatalf("echo exchange %d failed across Naive padding boundary: %v", i, err)
		}
		if !bytes.Equal(actual, payload) {
			t.Fatalf("echo exchange %d changed binary payload", i)
		}
	}
}

func naiveIntegrationConfig(t *testing.T, port int, peer string, root string, quic bool, password string) string {
	t.Helper()
	_, peerPort, err := net.SplitHostPort(peer)
	if err != nil {
		t.Fatal(err)
	}
	congestion := ""
	if quic {
		congestion = `,"quicCongestionControl":"bbr"`
	}
	rootLines, err := json.Marshal(strings.Split(strings.TrimSpace(root), "\n"))
	if err != nil {
		t.Fatal(err)
	}
	return fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"http"}],"outbounds":[{"tag":"naive-peer","protocol":"naive","settings":{"address":"127.0.0.1","port":%s,"user":"test-user","pass":%q,"quic":%t%s},"streamSettings":{"network":"raw","security":"tls","tlsSettings":{"serverName":"proxy.example.org","certificates":[{"usage":"verify","certificate":%s}]}}}]}`, port, peerPort, password, quic, congestion, rootLines)
}

func TestNaiveFacadeRealChromiumTransport(t *testing.T) {
	if err := cronet.LoadLibrary(""); err != nil {
		t.Fatalf("loading the required pinned Cronet library: %v", err)
	}
	certificate, root := naivePeerCertificate(t)
	for _, quic := range []bool{false, true} {
		name := "HTTP2"
		if quic {
			name = "HTTP3-BBR"
		}
		t.Run(name, func(t *testing.T) {
			dir := isolateFacadeCore(t)
			target, requests := newFacadeTarget(t)
			targetURL, err := url.Parse(target.URL)
			if err != nil {
				t.Fatal(err)
			}
			echo := newNaiveEchoTarget(t)
			peer, state := newNaivePeer(t, quic, certificate, map[string]bool{targetURL.Host: true, echo: true})
			reservation := reserveFacadeAddress(t)
			address := reservation.Addr().String()
			port := reservation.Addr().(*net.TCPAddr).Port
			reservation.Close()
			config := writeFacadeConfig(t, naiveIntegrationConfig(t, port, peer, root, quic, "test-pass"))
			client := newFacadeProxyClient(t, "http", address)
			// Stop Chromium before closing its local peer, even on assertion failure.
			t.Cleanup(func() { xray.Stop() })
			if diagnostic := xray.ValidateInWorker(dir, config); diagnostic != "" {
				t.Fatalf("validation rejected the real peer configuration: %s", diagnostic)
			}
			requireFacadeListenerReleased(t, address)
			if diagnostic := xray.Start(dir, config); diagnostic != "" {
				t.Fatalf("Start failed: %s", diagnostic)
			}
			requireFacadePayload(t, client, target.URL, requests, "Chromium before validation")
			requireNaivePaddingBoundary(t, address, echo)
			if state.readFrames.Load() < 8 || state.writeFrames.Load() < 8 {
				t.Fatal("real transport did not exercise both padding-to-unpadded transitions")
			}
			if diagnostic := validateFacadeInWorker(t, dir, config); diagnostic != "" {
				t.Fatalf("isolated validation failed while Chromium was active: %s", diagnostic)
			}
			requireFacadePayload(t, client, target.URL, requests, "Chromium after validation")
			if diagnostic := xray.Stop(); diagnostic != "" {
				t.Fatalf("Stop failed: %s", diagnostic)
			}
			requireFacadeListenerReleased(t, address)
			if diagnostic := xray.Start(dir, config); diagnostic != "" {
				t.Fatalf("restart failed: %s", diagnostic)
			}
			requireFacadePayload(t, client, target.URL, requests, "Chromium after restart")
			if diagnostic := xray.Stop(); diagnostic != "" {
				t.Fatalf("Stop after restart failed: %s", diagnostic)
			}
			requireFacadeListenerReleased(t, address)
			accepted := state.accepted.Load()
			invalid := writeFacadeConfig(t, naiveIntegrationConfig(t, port, peer, root, quic, "wrong-test-pass"))
			if diagnostic := xray.Start(dir, invalid); diagnostic != "" {
				t.Fatalf("Start with rejected server credentials failed before handshake: %s", diagnostic)
			}
			response, err := client.Post(target.URL+"/must-not-arrive", "text/plain", strings.NewReader("rejected user payload"))
			if err == nil {
				io.Copy(io.Discard, response.Body)
				response.Body.Close()
				if response.StatusCode == http.StatusCreated {
					t.Fatal("rejected Naive authentication delivered the user request")
				}
			}
			if diagnostic := xray.Stop(); diagnostic != "" {
				t.Fatalf("Stop after rejected authentication failed: %s", diagnostic)
			}
			requireFacadeListenerReleased(t, address)
			if state.rejected.Load() == 0 || state.accepted.Load() != accepted {
				t.Fatal("negative request did not reach real authentication rejection or opened an authenticated tunnel")
			}
			select {
			case <-requests:
				t.Fatal("rejected authentication reached the user-request target")
			default:
			}
			state.requireHealthy(t)
		})
	}
}
