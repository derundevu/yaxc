package XrayCore_test

import (
	"encoding/base64"
	"encoding/json"
	"net/url"
	"strings"
	"testing"

	xray "XrayCore"

	"github.com/xtls/xray-core/infra/conf"
	"google.golang.org/protobuf/proto"
)

func requireEquivalentNativeConfig(t *testing.T, first, second []byte) {
	t.Helper()
	var before, after conf.Config
	if err := json.Unmarshal(first, &before); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(second, &after); err != nil {
		t.Fatal(err)
	}
	want, err := before.Build()
	if err != nil {
		t.Fatalf("input native configuration is invalid: %v", err)
	}
	got, err := after.Build()
	if err != nil {
		t.Fatalf("round-tripped native configuration is invalid: %v", err)
	}
	if !proto.Equal(want, got) {
		t.Fatal("share roundtrip changed the effective native configuration")
	}
}

func TestNaiveFacadeSemanticRoundtrip(t *testing.T) {
	for _, tc := range []struct {
		name   string
		scheme string
		host   string
		query  string
		quic   bool
	}{
		{"HTTPS encoded credentials and SNI", "naive+https", "proxy.example.org:8443", "sni=tls%2Eexample%2Eorg&padding=1&tfo=1", false},
		{"QUIC IPv6 and encoded SNI", "naive+quic", "[2001:db8::1]:443", "sni=2001%3Adb8%3A%3A2&padding=true&tfo=0", true},
		{"HTTPS default port", "naive+https", "proxy.example.org", "", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			link := url.URL{Scheme: tc.scheme, Host: tc.host, User: url.UserPassword("test-user+@/?", "test-pass:@/?#%+"), Fragment: "Naive 東京 #1", RawQuery: tc.query}
			config := importShare(t, link.String())
			var parsed conf.Config
			if err := json.Unmarshal(config, &parsed); err != nil || len(parsed.OutboundConfigs) != 1 {
				t.Fatalf("invalid native result: %v", err)
			}
			outbound := parsed.OutboundConfigs[0]
			var settings conf.NaiveClientConfig
			if err := json.Unmarshal(*outbound.Settings, &settings); err != nil {
				t.Fatal(err)
			}
			if outbound.Protocol != "naive" || settings.QUIC != tc.quic || settings.UDPOverTCP || settings.Username != link.User.Username() || settings.Password != "test-pass:@/?#%+" {
				t.Fatal("Naive import changed protocol, credentials or UDP behavior")
			}
			exported := xray.Share(string(config))
			actual, err := url.Parse(exported)
			if err != nil || exported == "" || actual.Scheme != tc.scheme || actual.Fragment != link.Fragment || actual.Hostname() != link.Hostname() || actual.User.Username() != link.User.Username() {
				t.Fatalf("Naive export changed URI identity: %q (%v)", exported, err)
			}
			if password, ok := actual.User.Password(); !ok || password != "test-pass:@/?#%+" {
				t.Fatal("Naive export changed percent-encoded password")
			}
			requireEquivalentNativeConfig(t, config, importShare(t, exported))
		})
	}
}

func TestNaiveFacadeMixedSubscriptions(t *testing.T) {
	first := "naive+https://test-user:test-pass@proxy.example.org:443?sni=tls.example.org#first"
	second := "vless://e9bff11a-27a7-49c7-8c21-2303c304de78@vless.example.org:443?encryption=none&security=tls#second"
	third := "naive+quic://test-user:test-pass@quic.example.org:443#third"
	plain := first + "\r\n" + second + "\r\n" + third
	for name, text := range map[string]string{
		"plain":                      plain,
		"Base64":                     base64.StdEncoding.EncodeToString([]byte(plain)),
		"URL Base64 without padding": base64.RawURLEncoding.EncodeToString([]byte(plain)),
	} {
		t.Run(name, func(t *testing.T) {
			config := importShare(t, text)
			var document map[string]json.RawMessage
			if err := json.Unmarshal(config, &document); err != nil {
				t.Fatal(err)
			}
			// Direct/block outbounds are not share records and must not produce
			// empty lines or steal names from supported records.
			var outbounds []json.RawMessage
			if err := json.Unmarshal(document["outbounds"], &outbounds); err != nil {
				t.Fatal(err)
			}
			outbounds = append([]json.RawMessage{json.RawMessage(`{"protocol":"freedom","tag":"direct"}`)}, outbounds...)
			document["outbounds"], _ = json.Marshal(outbounds)
			mixed, _ := json.Marshal(document)
			exported := strings.Split(xray.Share(string(mixed)), "\n")
			if len(exported) != 3 {
				t.Fatalf("lost supported mixed subscription records: %q", exported)
			}
			for i, name := range []string{"first", "second", "third"} {
				link, err := url.Parse(exported[i])
				if err != nil || link.Fragment != name {
					t.Fatalf("wrong record identity at index %d: %q", i, exported[i])
				}
			}
			importShare(t, strings.Join(exported, "\n"))
		})
	}
}

func TestNaiveFacadeRejectsUnsupportedURISemantics(t *testing.T) {
	const base = "naive+https://test-user:test-pass@proxy.example.org:443"
	for name, input := range map[string]string{
		"disabled padding":    base + "?padding=0",
		"custom padding":      base + "?padding=100-200",
		"insecure TLS":        base + "?allowInsecure=1",
		"fingerprint":         base + "?fp=chrome",
		"ALPN":                base + "?alpn=h2",
		"UDP":                 base + "?udpOverTcp=true",
		"invalid query":       base + "?sni=%zz",
		"duplicate option":    base + "?sni=one.example.org&sni=two.example.org",
		"invalid SNI":         base + "?sni=invalid%2Fname",
		"path":                base + "/unsupported",
		"invalid port":        "naive+https://test-user:test-pass@proxy.example.org:65536",
		"colon username":      "naive+https://test%3Auser:test-pass@proxy.example.org:443",
		"QUIC TCP Fast Open":  "naive+quic://test-user:test-pass@proxy.example.org:443?tfo=true",
		"mixed unsupported":   base + "\nanytls://test-pass@unsupported.example.org:443",
		"mixed invalid Naive": base + "\n" + base + "?padding=false",
	} {
		t.Run(name, func(t *testing.T) {
			response := decodeShareResponse(t, xray.Json(input))
			if response.Success || response.Error == "" {
				t.Fatal("unsupported Naive semantics were silently accepted")
			}
			if strings.Contains(response.Error, "test-pass") || strings.Contains(response.Error, "proxy.example.org") || strings.Contains(response.Error, "://") {
				t.Fatal("Naive diagnostic leaked URI credentials or endpoint")
			}
		})
	}
}

func TestNaiveFacadeRejectsLossyExports(t *testing.T) {
	for name, fields := range map[string]string{
		"UDP behavior":     `"settings":{"address":"proxy.example.org","port":443,"udpOverTcp":true}`,
		"headers":          `"settings":{"address":"proxy.example.org","port":443,"headers":{"X-Test":"one"}}`,
		"policy level":     `"settings":{"address":"proxy.example.org","port":443,"level":1}`,
		"QUIC congestion":  `"settings":{"address":"proxy.example.org","port":443,"quic":true,"quicCongestionControl":"bbr"}`,
		"unknown settings": `"settings":{"address":"proxy.example.org","port":443,"padding":false}`,
		"insecure TLS":     `"streamSettings":{"network":"raw","security":"tls","tlsSettings":{"allowInsecure":true}}`,
		"fingerprint":      `"streamSettings":{"security":"tls","tlsSettings":{"fingerprint":"chrome"}}`,
		"ALPN":             `"streamSettings":{"security":"tls","tlsSettings":{"alpn":["h2"]}}`,
		"unknown TLS":      `"streamSettings":{"security":"tls","tlsSettings":{"unsupported":true}}`,
		"custom roots":     `"streamSettings":{"security":"tls","tlsSettings":{"certificates":[{"usage":"verify","certificate":["synthetic-certificate"]}]}}`,
		"nonraw transport": `"streamSettings":{"network":"ws","security":"tls"}`,
		"socket routing":   `"streamSettings":{"security":"tls","sockopt":{"dialerProxy":"detour"}}`,
		"sender binding":   `"sendThrough":"192.0.2.1"`,
		"target strategy":  `"targetStrategy":"UseIPv4"`,
		"multiplexing":     `"mux":{"enabled":true}`,
	} {
		t.Run(name, func(t *testing.T) {
			var outbound map[string]json.RawMessage
			if err := json.Unmarshal([]byte(`{"protocol":"naive","tag":"synthetic","settings":{"address":"proxy.example.org","port":443,"user":"test-user","pass":"test-pass"},"streamSettings":{"network":"raw","security":"tls"}}`), &outbound); err != nil {
				t.Fatal(err)
			}
			var override map[string]json.RawMessage
			if err := json.Unmarshal([]byte("{"+fields+"}"), &override); err != nil {
				t.Fatal(err)
			}
			for key, value := range override {
				outbound[key] = value
			}
			raw, _ := json.Marshal(outbound)
			if exported := xray.Share(`{"outbounds":[` + string(raw) + `]}`); exported != "" {
				t.Fatalf("Naive export discarded unsupported semantics: %q", exported)
			}
		})
	}
}

func TestNaiveFacadeNativeJSONValidation(t *testing.T) {
	valid := `{"outbounds":[{"protocol":"naive","tag":"native","settings":{"address":"proxy.example.org","port":443,"user":"test-user","pass":"test-pass","quic":true,"quicCongestionControl":"bbr","udpOverTcp":true,"headers":{"X-Test":"one"}},"streamSettings":{"network":"raw","security":"tls"}}]}`
	importShare(t, valid)
	for _, input := range []string{
		strings.Replace(valid, `"quic":true`, `"quic":false`, 1),
		strings.Replace(valid, `"security":"tls"`, `"security":"tls","tlsSettings":{"allowInsecure":true}`, 1),
		strings.Replace(valid, `"headers":{"X-Test":"one"}`, `"unknown":true`, 1),
	} {
		response := decodeShareResponse(t, xray.Json(input))
		if response.Success || response.Error == "" {
			t.Fatal("native Naive JSON silently accepted unsupported semantics")
		}
	}
}
